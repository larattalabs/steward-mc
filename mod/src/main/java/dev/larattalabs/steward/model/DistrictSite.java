package dev.larattalabs.steward.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where a district goes (docs/PLAN.md "Zones", first step, Noah 2026-10-11): what ground it wants, read from words (its name, what it is for, where the
 * player said), and how each free side of the town scores on it. The side the player named wins, then where they stood, then the ground that suits the
 * district best, then today's order. Claude never picks coordinates: words describe the place, and the survey finds it. Pure.
 */
public record DistrictSite(@Nullable String way, Pref water, Pref trees, Ground ground, List<String> biomes) {
	public enum Pref { WANT, AVOID, ANY }

	public enum Ground { HIGH, LOW, FLAT, ANY }

	/** No wish: today's order decides. */
	public static final DistrictSite ANYWHERE = new DistrictSite(null, Pref.ANY, Pref.ANY, Ground.ANY, List.of());

	private static final Pattern WATER = Pattern.compile("\\b(harbou?rs?|ports?|docks?|wharf|wharves|piers?|fish\\w*|shores?|coast\\w*|beach\\w*|lakes?|lakeside|rivers?|riverside|"
		+ "sea|seaside|water\\w*|bay|lagoon|canals?|marina|boat\\w*|ships?|shipyard)\\b");
	private static final Pattern DRY = Pattern.compile("\\b(dry|away from the water|inland)\\b");
	private static final Pattern TREES = Pattern.compile("\\b(forest\\w*|woods?|woodland|lumber\\w*|logging|timber|grove|jungle|sawmill|foresters?|trees?)\\b");
	private static final Pattern OPEN = Pattern.compile("\\b(fields?|open|clearing|plains?|meadows?|pastures?|grassland)\\b");
	private static final Pattern HIGH = Pattern.compile("\\b(hills?|hilltop|high|heights|mountain\\w*|ridge|cliffs?|peaks?|mines?|mining|quarry|quarries|lookouts?|"
		+ "watch\\w*|temples?|castles?|keep|forts?|fortress|citadel|tower)\\b");
	private static final Pattern LOW = Pattern.compile("\\b(valley|low|lowlands?|hollow|basin|down by)\\b");
	private static final Pattern FLAT = Pattern.compile("\\b(farm\\w*|fields?|orchards?|pastures?|markets?|flat|plains?|crops?|wheat|ranch|stables?)\\b");
	/** Biome words a player might name, as they appear in biome ids (minecraft:jungle, minecraft:sparse_jungle, minecraft:desert...). */
	private static final List<String> BIOMES = List.of("desert", "jungle", "savanna", "swamp", "taiga", "snowy", "badlands", "plains", "birch", "dark_forest",
		"cherry", "mushroom", "ocean", "beach", "meadow", "grove", "mangrove", "bamboo");
	private static final Pattern WAY = Pattern.compile("\\b(north|south|east|west)(ern)?\\b");

	/** What ground the words ask for. A biome named (the jungle side) counts; "mesa" reads as badlands, "dark forest" as dark_forest. */
	public static DistrictSite fromWords(@Nullable String words) {
		if (words == null || words.isBlank()) return ANYWHERE;
		String w = words.toLowerCase(Locale.ROOT).replace("dark forest", "dark_forest").replace("mesa", "badlands");
		var m = WAY.matcher(w);
		String way = m.find() ? m.group(1).substring(0, 1).toUpperCase(Locale.ROOT) : null;
		Pref water = DRY.matcher(w).find() ? Pref.AVOID : WATER.matcher(w).find() ? Pref.WANT : Pref.ANY;
		Pref trees = TREES.matcher(w).find() ? Pref.WANT : OPEN.matcher(w).find() ? Pref.AVOID : Pref.ANY;
		Ground ground = HIGH.matcher(w).find() ? Ground.HIGH : LOW.matcher(w).find() ? Ground.LOW : FLAT.matcher(w).find() ? Ground.FLAT : Ground.ANY;
		List<String> biomes = new ArrayList<>();
		for (String b : BIOMES) if (Pattern.compile("\\b" + b + "\\b").matcher(w).find()) biomes.add(b);
		return new DistrictSite(way, water, trees, ground, List.copyOf(biomes));
	}

	/** Whether the ground matters to it (else today's order decides among the free sides). */
	public boolean caresAboutGround() {
		return water != Pref.ANY || trees != Pref.ANY || ground != Ground.ANY || !biomes.isEmpty();
	}

	/**
	 * What a candidate square is like, from the survey.
	 *
	 * @param known the share of its columns the survey read (the rest are in chunks not loaded)
	 * @param water the share of read columns under water (or frozen water)
	 * @param trees the share of read dry columns with trees
	 * @param height the mean ground height of its read dry columns
	 * @param spread how much that ground varies (its standard deviation)
	 * @param biomes each biome's share of the read columns, by its id's path (jungle, sparse_jungle, desert...)
	 */
	public record Profile(double known, double water, double trees, double height, double spread, Map<String, Double> biomes) {}

	/** Accumulates columns into a {@link Profile}. */
	public static final class ProfileBuilder {
		private int all, read, wet, wooded, dry;
		private double sum, sumSq;
		private final Map<String, Integer> biomeCount = new LinkedHashMap<>();

		/** A column of the square: {@code read} false when its chunk was not loaded; {@code biome} its id or null. */
		public void add(boolean read, boolean water, boolean tree, int groundY, @Nullable String biome) {
			all++;
			if (!read) return;
			this.read++;
			if (biome != null) biomeCount.merge(biome.substring(biome.indexOf(':') + 1), 1, Integer::sum);
			if (water) {
				wet++;
				return;
			}
			dry++;
			if (tree) wooded++;
			sum += groundY;
			sumSq += (double) groundY * groundY;
		}

		public Profile build() {
			double mean = dry == 0 ? 0 : sum / dry;
			double var = dry == 0 ? 0 : Math.max(0, sumSq / dry - mean * mean);
			Map<String, Double> shares = new LinkedHashMap<>();
			for (var e : biomeCount.entrySet()) shares.put(e.getKey(), (double) e.getValue() / read);
			return new Profile(all == 0 ? 0 : (double) read / all, read == 0 ? 0 : (double) wet / read, dry == 0 ? 0 : (double) wooded / dry, mean, Math.sqrt(var), shares);
		}
	}

	/** A side's score and the words for why it suits (empty when nothing about it stands out). */
	public record Score(double value, String why) {}

	/** Too little of a square was read to judge it. */
	public static final double MIN_KNOWN = 0.3;

	/** How well {@code p} suits this district, beside a town whose ground stands at {@code townHeight}. */
	public Score score(Profile p, double townHeight) {
		if (p.known() < MIN_KNOWN) return new Score(0, "");
		double best = 0;
		String why = "";
		double total = 0;
		// each wish adds what the square offers of it; the strongest reason is what the player is told
		if (water == Pref.WANT) {
			double v = Math.min(1, p.water() * 4) * 3;
			total += v;
			if (v > best && p.water() >= 0.03) { best = v; why = "by the water (" + pct(p.water()) + " of it)"; }
		} else if (water == Pref.AVOID) total -= p.water() * 3;
		if (trees == Pref.WANT) {
			double v = p.trees() * 2.5;
			total += v;
			if (v > best && p.trees() >= 0.1) { best = v; why = "in the woods (" + pct(p.trees()) + " trees)"; }
		} else if (trees == Pref.AVOID) total -= p.trees() * 2;
		double rise = p.height() - townHeight;
		switch (ground) {
			case HIGH -> {
				double v = Math.max(-2, Math.min(2, rise / 8));
				total += v;
				if (v > best && rise >= 4) { best = v; why = "on high ground (" + Math.round(rise) + " above the town)"; }
			}
			case LOW -> {
				double v = Math.max(-2, Math.min(2, -rise / 8));
				total += v;
				if (v > best && rise <= -4) { best = v; why = "on low ground (" + Math.round(-rise) + " below the town)"; }
			}
			case FLAT -> {
				double v = Math.max(0, 2 - p.spread() / 2) - p.water() * 2;
				total += v;
				if (v > best && p.spread() <= 3) { best = v; why = "on flat, open ground"; }
			}
			default -> {
			}
		}
		for (String b : biomes) {
			double share = p.biomes().entrySet().stream().filter(e -> e.getKey().contains(b)).mapToDouble(Map.Entry::getValue).sum();
			double v = share * 4;
			total += v;
			if (v > best && share >= 0.2) { best = v; why = "in the " + b.replace('_', ' ') + " (" + pct(share) + " of it)"; }
		}
		// half-read squares count for less
		return new Score(total * Math.min(1, p.known() / 0.8), why);
	}

	/**
	 * The side for this district, and why: {@code candidates} the free sides (N, E, S, W order) that fit, {@code profiles} what the survey found on each,
	 * {@code standing} where the player stands when they point ("here"), or null.
	 */
	public Choice choose(List<String> candidates, Map<String, Profile> profiles, double townHeight, @Nullable Standing standing) {
		if (candidates.isEmpty()) return new Choice(null, "");
		if (way != null && candidates.contains(way)) return new Choice(way, "to the " + DistrictRules.directionName(way) + ", as you said");
		if (standing != null) {
			String near = null;
			double nearest = Double.MAX_VALUE;
			for (String c : candidates) {
				var d = standing.distances().get(c);
				if (d != null && d < nearest) {
					nearest = d;
					near = c;
				}
			}
			if (near != null) return new Choice(near, nearest <= 0 ? "where you stand" : "the side nearest where you stand");
		}
		if (caresAboutGround()) {
			String bestWay = null;
			Score best = null;
			for (String c : candidates) {
				Profile p = profiles.get(c);
				if (p == null) continue;
				Score s = score(p, townHeight);
				if (best == null || s.value() > best.value()) {
					best = s;
					bestWay = c;
				}
			}
			if (best != null && best.value() > 0.25 && !best.why().isEmpty()) return new Choice(bestWay, best.why());
		}
		return new Choice(candidates.get(0), "");
	}

	/** The distance from where the player stands to each side's square (0 inside it). */
	public record Standing(Map<String, Double> distances) {}

	/** The side chosen (null: none fits) and why (empty: today's order). */
	public record Choice(@Nullable String way, String why) {}

	private static String pct(double v) {
		return Math.round(v * 100) + "%";
	}
}
