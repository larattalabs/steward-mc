package dev.larattalabs.steward.gateway;

import dev.larattalabs.architect.api.LandReport;
import dev.larattalabs.architect.api.LotLand;
import dev.larattalabs.architect.api.PartLand;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What the steward says about the land of its town and districts (docs/LAND.md, Architect LC): which of Architect's land-check rows count, and their
 * words. Volatile change (crops, leaves, snow, fluids) never counts; a gate's own state and a player's identical block Architect already leaves out. Pure.
 */
public final class LandWords {
	/** At most this many lines in a note (the rest are counted in one more line). */
	static final int MAX_LINES = 10;

	/** Whole utterances that ask the steward to look over the land (TalkRules' style: an optional "steward" and punctuation at the end). */
	private static final Pattern ASKED = Pattern.compile("^(what('s| has| is)? changed|has anything changed|(is |was )?anything (broken|changed|damaged|missing)"
		+ "|what('s| is) (broken|damaged|missing)|any damage|damage report|(check|look over|look around) the (town|village|land|settlement|buildings|place))"
		+ "( (here|around here|in (the )?(town|village)|lately|since i left))?(,? steward)?[\\s!.?]*$");

	private static final Pattern REVISION = Pattern.compile("^rv\\d+_");
	private static final Pattern STREET = Pattern.compile("^street_([nsew])$");

	private LandWords() {
	}

	/**
	 * One region's look: its label ("Hamlet", or a district's name), whether it is a district, and the report, or the refusal's reason name (
	 * {@code REGION_LIMIT}, ...) and message.
	 */
	public record Look(String label, boolean district, @Nullable LandReport report, @Nullable String refusal, @Nullable String message) {
		public static Look of(String label, boolean district, LandReport report) {
			return new Look(label, district, report, null, null);
		}

		public static Look refused(String label, boolean district, String reason, String message) {
			return new Look(label, district, null, reason, message);
		}
	}

	/**
	 * What the steward says: a headline, its lines, the damage (blocks broken or changed) and the player's blocks, whether anything real was seen,
	 * whether every region was seen whole ({@code complete}: none refused, no unloaded cell), and the damage signature (per region and per part or lot:
	 * what makes an automatic look speak; the player's blocks are not in it).
	 */
	public record Note(String headline, List<String> lines, long damage, long placed, boolean real, boolean complete, String signature) {
		public Note {
			lines = List.copyOf(lines);
		}
	}

	/** Whether the player asked the steward to look over the land ("what changed?", "anything broken?", "check the town"). */
	public static boolean asked(@Nullable String said) {
		return said != null && ASKED.matcher(said.strip().toLowerCase(Locale.ROOT)).matches();
	}

	public static boolean real(PartLand p) {
		return p.removed() + p.changed() + p.playerPlaced() > 0;
	}

	public static boolean real(LotLand l) {
		return l.changed() + l.playerPlaced() > 0;
	}

	/**
	 * The note for a settlement's looks.
	 *
	 * @param buildingName a lot site's building name as Steward calls it (its role), or null for the lot's own id
	 * @param onDemand the player asked: gaps (unloaded cells, a region too large, a refusal) are said; an automatic look says nothing of them
	 */
	public static Note note(String name, List<Look> looks, Function<String, @Nullable String> buildingName, boolean onDemand) {
		return note(name, looks, buildingName, onDemand, part -> false);
	}

	/** As above; a region part {@code secret} names (a hidden room's, S-7c-4) is told as "other built areas", never by its own name. */
	public static Note note(String name, List<Look> looks, Function<String, @Nullable String> buildingName, boolean onDemand,
		java.util.function.Predicate<String> secret) {
		List<String> lines = new ArrayList<>();
		List<String> gaps = new ArrayList<>();
		long damage = 0, placed = 0;
		boolean seen = false;
		boolean complete = true;
		java.util.TreeSet<String> sig = new java.util.TreeSet<>();
		for (Look look : looks) {
			String in = look.district() ? "In " + look.label() + ", " : "";
			if (look.report() == null) {
				complete = false;
				if ("REGION_LIMIT".equals(look.refusal())) gaps.add(look.label() + " is too large for me to look over at once.");
				else gaps.add("I couldn't look over " + look.label() + (look.message() == null || look.message().isBlank() || secret.test(look.message()) ? "." : ": " + look.message()));
				continue;
			}
			seen = true;
			LandReport r = look.report();
			if (r.unloaded() > 0) complete = false;
			// two buildings of one role are told apart by their lot
			Map<String, Long> roles = new java.util.HashMap<>();
			for (LotLand l : r.lots()) roles.merge(nameOf(l, buildingName), 1L, Long::sum);
			if (r.unloaded() > 0) gaps.add("I couldn't see " + r.unloaded() + (r.unloaded() == 1 ? " block" : " blocks") + " of " + look.label()
				+ " from here: come closer and ask again.");
			for (LotLand l : r.lots().stream().filter(LandWords::real).sorted(Comparator.comparingLong((LotLand l) -> -(l.changed() + l.playerPlaced()))
				.thenComparing(LotLand::lot)).toList()) {
				damage += l.changed();
				placed += l.playerPlaced();
				if (l.changed() > 0) sig.add(r.region() + "|lot:" + l.siteId() + "=" + l.changed());
				String who = nameOf(l, buildingName);
				boolean twin = roles.getOrDefault(who, 0L) > 1 && !who.equals(l.lot());
				lines.add(cap(in + "the " + human(who) + (twin ? " (" + human(l.lot()) + ")" : "")) + ": " + counts(0, l.changed(), l.playerPlaced(), Map.of(), true)
					+ ".");
			}
			for (PartLand p : r.parts().stream().filter(LandWords::real).sorted(Comparator.comparingLong((PartLand p) -> -(p.removed() + p.changed() + p.playerPlaced()))
				.thenComparing(PartLand::part)).toList()) {
				damage += p.removed() + p.changed();
				placed += p.playerPlaced();
				if (p.removed() + p.changed() > 0) sig.add(r.region() + "|part:" + p.part() + "=" + (p.removed() + p.changed()));
				lines.add(cap(in + partName(secret.test(p.part()) ? null : p.part())) + ": " + counts(p.removed(), p.changed(), p.playerPlaced(), p.kinds(), false) + ".");
			}
		}
		boolean real = !lines.isEmpty();
		if (lines.size() > MAX_LINES) {
			int more = lines.size() - (MAX_LINES - 1);
			lines = new ArrayList<>(lines.subList(0, MAX_LINES - 1));
			lines.add("And " + more + " more places.");
		}
		String headline;
		if (real) {
			headline = damage > 0 ? "I looked over " + name + ": some of what we built is broken or changed." : "I looked over " + name + ": you have added to what we built.";
		} else if (seen) {
			headline = complete ? "I found nothing broken or changed in what we built in " + name + "."
				: "I found nothing broken or changed in what I could see of " + name + ".";
		} else {
			headline = "I couldn't look over " + name + ".";
		}
		if (onDemand) lines.addAll(gaps);
		return new Note(headline, lines, damage, placed, real, complete, String.join(";", sig));
	}

	/** A lot's building as Steward calls it, else the lot's own id. */
	private static String nameOf(LotLand l, Function<String, @Nullable String> buildingName) {
		String who = buildingName.apply(l.siteId());
		return who != null && !who.isBlank() ? who : l.lot();
	}

	/** A part of a region in words: its roads, the wall, a gate, the square and its props, a growth's parts. */
	public static String partName(String part) {
		if (part == null || part.isBlank() || "unattributed".equals(part)) return "other built areas";
		if (part.startsWith("mech:")) return "the gate of the " + human(part.substring(5));
		String p = REVISION.matcher(part).replaceFirst("");
		var street = STREET.matcher(p);
		if (street.matches()) return "the street's " + switch (street.group(1)) {
			case "n" -> "north";
			case "s" -> "south";
			case "e" -> "east";
			default -> "west";
		} + " end";
		if (p.startsWith("w_") || p.startsWith("wl_")) p = p.substring(p.indexOf('_') + 1);
		else if (p.startsWith("props_")) p = p.substring(6);
		return "the " + human(p);
	}

	/** "40 blocks gone, 6 replaced (now sponge), 2 placed by players"; a lot's {@code changed} holds its broken blocks too. */
	static String counts(long removed, long changed, long playerPlaced, Map<String, Long> kinds, boolean lot) {
		List<String> out = new ArrayList<>();
		if (removed > 0) out.add(blocks(removed) + " gone");
		if (changed > 0) {
			String now = lot ? "" : nowKinds(kinds);
			out.add(lot ? blocks(changed) + " broken or changed" : (removed > 0 ? changed + " replaced" : blocks(changed) + " replaced") + now);
		}
		if (playerPlaced > 0) out.add(playerPlaced + " placed by " + (playerPlaced == 1 ? "a player" : "players"));
		return String.join(", ", out);
	}

	/** " (now sponge, cobblestone)": the two kinds most often there now; Architect keys {@code kinds} by the block now in the cell. */
	static String nowKinds(Map<String, Long> kinds) {
		if (kinds == null || kinds.isEmpty()) return "";
		List<String> top = kinds.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(2)
			.map(e -> block(e.getKey())).toList();
		return " (now " + String.join(", ", top) + ")";
	}

	/** "minecraft:oak_planks[axis=y]" -> "oak planks". */
	static String block(String id) {
		String s = id == null ? "" : id;
		int b = s.indexOf('[');
		if (b >= 0) s = s.substring(0, b);
		int c = s.indexOf(':');
		if (c >= 0) s = s.substring(c + 1);
		return s.replace('_', ' ');
	}

	private static String blocks(long n) {
		return n + (n == 1 ? " block" : " blocks");
	}

	private static String human(String s) {
		return s.replace('_', ' ').strip();
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
