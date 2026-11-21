package dev.larattalabs.steward.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * (slice PD, Noah 2026-11-15 "Propose in inbox") A district the town's approved plan names (D1: a zone's {@code program.districts}) that the founding
 * didn't build: none of its lots stands in the town region. Once the town is built, the steward proposes it in the inbox, once per district; accepted,
 * it is reserved beside the town and its first build plans its own region with the plan's lots (SD's path: billed from materials in survival, undone
 * and refunded as SD's). Pure: the plan, the region's lots and the settlement are read by the caller.
 */
public final class PlanDistricts {
	/** The proposal key prefix: {@code plan_district_<the plan's district id>}. */
	public static final String KEY_PREFIX = "plan_district_";

	/** A plan district not built: its id, its label (the plan's words), its lots as the plan has them, and the ground it prefers. */
	public record Unbuilt(String id, String label, List<JsonObject> lots, List<String> prefer) {
		public Unbuilt {
			lots = List.copyOf(lots);
			prefer = List.copyOf(prefer);
		}

		public String key() {
			return KEY_PREFIX + id;
		}
	}

	private PlanDistricts() {
	}

	public static boolean isKey(@Nullable String key) {
		return key != null && key.startsWith(KEY_PREFIX) && key.length() > KEY_PREFIX.length();
	}

	/** The plan district id a key names, or null. */
	public static @Nullable String idOf(@Nullable String key) {
		return isKey(key) ? key.substring(KEY_PREFIX.length()) : null;
	}

	/**
	 * Every district of a districts program in plan order of which no lot is in {@code regionLots} (any state: built, removed or grown), with at least one
	 * lot Architect takes as a region lot (not a secret, not a module lot); a district that waits ({@code waiting}) or is a secret is left out.
	 */
	public static List<Unbuilt> unbuilt(JsonObject plan, Set<String> regionLots, Set<String> waiting, Secrets secrets) {
		return unbuilt(plan, regionLots, waiting, secrets, dev.larattalabs.steward.module.ModuleTypes.KNOWN);
	}

	/** As above; (slice MODULE-TYPE, GPT review P1) a lot of any module's type ({@code types}, slugged: every catalog id) is left out too. */
	public static List<Unbuilt> unbuilt(JsonObject plan, Set<String> regionLots, Set<String> waiting, Secrets secrets, Map<String, String> types) {
		Map<String, List<PlanGrowth.Lot>> byDistrict = new LinkedHashMap<>();
		for (PlanGrowth.Lot l : PlanGrowth.lots(plan)) if (l.district() != null) byDistrict.computeIfAbsent(l.district(), k -> new ArrayList<>()).add(l);
		List<Unbuilt> out = new ArrayList<>();
		for (JsonObject d : districts(plan)) {
			String id = str(d, "id");
			if (id == null || !PlanGrowth.LOT_ID.matcher(id).matches() || waiting.contains(id) || secrets.hides(id)) continue;
			var lots = byDistrict.getOrDefault(id, List.of());
			if (lots.isEmpty() || lots.stream().anyMatch(l -> regionLots.contains(l.id()) || waiting.contains(l.zone()))) continue;
			List<JsonObject> take = new ArrayList<>();
			for (PlanGrowth.Lot l : lots) {
				if (secrets.hides(l.id()) || !PlanGrowth.LOT_ID.matcher(l.id()).matches()) continue;
				// a module lot (M5's reading, failing closed: a marker, readable or not, or a module's lot type such as a bare crop_farm) is never designed
				if (dev.larattalabs.steward.module.ComposedModules.asked(l.lot(), dev.larattalabs.steward.module.ComposedModules.LOT_TYPES) != null) continue;
				if (l.lot().has("type") && l.lot().get("type").isJsonPrimitive() && dev.larattalabs.steward.module.ModuleTypes.moduleOf(l.lot().get("type").getAsString(), types) != null) continue;
				take.add(l.lot());
			}
			if (take.isEmpty()) continue;
			String label = str(d, "label");
			label = label == null || label.isBlank() ? id.replace('_', ' ') : secrets.scrub(label).strip();
			List<String> prefer = new ArrayList<>();
			if (d.has("prefer") && d.get("prefer").isJsonArray()) for (JsonElement e : d.getAsJsonArray("prefer")) if (e.isJsonPrimitive()) prefer.add(e.getAsString());
			out.add(new Unbuilt(id, label, take, prefer));
		}
		return out;
	}

	/**
	 * The unbuilt districts still to offer: not open or declined, and neither accepted nor held by a settlement district of the same name (never twice)
	 * unless that district is only reserved beside the town with nothing built (an acceptance whose build ended unbuilt after a paid request, which
	 * isn't given back): it is offered again to build on that reservation.
	 */
	public static List<Unbuilt> toOffer(List<Unbuilt> unbuilt, Settlement.Proposals ps, List<Settlement.District> districts) {
		List<Unbuilt> out = new ArrayList<>();
		for (Unbuilt u : unbuilt) {
			String k = u.key();
			if (ps.declined().contains(k) || ps.open().stream().anyMatch(p -> p.key().equals(k))) continue;
			boolean resumes = districts.stream().anyMatch(d -> reserved(d, u.label()));
			if (!resumes && (ps.accepted().contains(k) || districts.stream().anyMatch(d -> d.name().equalsIgnoreCase(u.label())))) continue;
			out.add(u);
		}
		return out;
	}

	private static boolean reserved(Settlement.District d, String label) {
		return d.name().equalsIgnoreCase(label) && Settlement.District.BESIDE.equals(d.kind()) && Settlement.District.RESERVED.equals(d.state()) && d.region() == null;
	}

	/**
	 * What a plan district's side was chosen from: the town region, the kept plan (its time and words) and the settlement's districts. A district with no
	 * side to go is skipped while this stays the same ({@link #withoutNoSide}).
	 */
	public static String fingerprint(String townRegion, long planAt, String planJson, List<Settlement.District> districts) {
		StringBuilder b = new StringBuilder(townRegion).append('|').append(planAt).append('|').append(Integer.toHexString(planJson.hashCode()));
		for (Settlement.District d : districts) b.append('|').append(d.id()).append(':').append(d.state()).append(':').append(d.region());
		return b.toString();
	}

	/** The districts to offer less those found with no side to go ({@code noSide}: id -> fingerprint) under the same {@code fingerprint}. */
	public static List<Unbuilt> withoutNoSide(List<Unbuilt> offer, Map<String, String> noSide, String fingerprint) {
		return offer.stream().filter(u -> !fingerprint.equals(noSide.get(u.id()))).toList();
	}

	/** The side of the reservation an offer is made again on (an earlier acceptance's, only reserved), or empty for a first offer. */
	public static java.util.Optional<String> resumeWay(Unbuilt u, List<Settlement.District> districts) {
		return districts.stream().filter(d -> reserved(d, u.label())).reduce((a, b) -> b).map(Settlement.District::direction);
	}

	/** The district reserved beside the town under {@code label} with no region yet: what an earlier acceptance founded and didn't build. */
	public static java.util.Optional<Settlement.District> reservedFor(Settlement s, String label) {
		return s.lands().districts().stream().filter(d -> reserved(d, label)).reduce((a, b) -> b);
	}

	/** The lots as the build's specs: each its own id, type, size, label, brief (scrubbed), landmark and placement. */
	public static List<VillageLayout.LotSpec> specs(Unbuilt u, Secrets secrets) {
		List<VillageLayout.LotSpec> out = new ArrayList<>();
		for (JsonObject l : u.lots()) out.add(PlanGrowth.spec(l, secrets));
		return out;
	}

	/** The lots in words: "2 spice traders and a well house" (by label, else role or type, in plan order). */
	public static String lotWords(Unbuilt u) {
		Map<String, Integer> n = new LinkedHashMap<>();
		// by the label without its article ("a herbalist" twice is "2 herbalists"); one alone keeps "the" ("the old smithy")
		Map<String, String> one = new LinkedHashMap<>();
		for (JsonObject l : u.lots()) {
			String role = dev.larattalabs.steward.gateway.PPlan.role(l).strip();
			String base = role.replaceFirst("(?i)^(a|an) ", "");
			n.merge(base, 1, Integer::sum);
			one.putIfAbsent(base, base.equals(role) && !role.toLowerCase(Locale.ROOT).startsWith("the ") ? article(base) + " " + base : role);
		}
		List<String> parts = new ArrayList<>();
		n.forEach((what, c) -> parts.add(c == 1 ? one.get(what) : c + " " + plural(what.replaceFirst("(?i)^the ", ""))));
		if (parts.size() == 1) return parts.get(0);
		return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
	}

	/** The district's name with an article where it takes one: "the desert side", "Old riverside quarter". */
	static String named(String label) {
		String l = label.strip();
		if (l.isEmpty()) return "the district";
		return Character.isUpperCase(l.charAt(0)) || l.toLowerCase(Locale.ROOT).startsWith("the ") ? l : "the " + l;
	}

	/**
	 * The proposal: towards {@code way} (E or W, beside the town), its title and words naming the district and its lots, the building it stands for (the
	 * first lot's type, as many as its lots: the estimate's count), and {@code place} as the line shown on the ground.
	 */
	public static Proposal proposal(Unbuilt u, String way, @Nullable String why, int estimateUsd, boolean fromMaterials, long now) {
		return proposal(u, way, why, null, estimateUsd, fromMaterials, now);
	}

	/**
	 * As above, with {@code note} said after where it goes (PD-2: {@link #betterSideNote}, the ground it asks for lying north or south of the town, where
	 * a district beside the town doesn't go yet), or null.
	 */
	public static Proposal proposal(Unbuilt u, String way, @Nullable String why, @Nullable String note, int estimateUsd, boolean fromMaterials, long now) {
		String side = dev.larattalabs.steward.model.DistrictRules.directionName(way);
		String name = named(u.label());
		String lots = lotWords(u);
		String words = "The town's plan names " + name + " (" + lots + "), which its founding didn't build. It would go to the " + side + " of the town"
			+ (why == null || why.isBlank() ? "" : ", " + why) + ", with a street of its own joined to the town's. "
			+ (note == null || note.isBlank() ? "" : note.strip() + " ") + "Founding it costs nothing; building its "
			+ (u.lots().size() == 1 ? "lot" : u.lots().size() + " lots") + " costs about $" + estimateUsd
			+ (fromMaterials ? ", and from materials its bill is said before anything is placed." : ".");
		String type = PlanGrowth.spec(u.lots().get(0), Secrets.NONE).type();
		var building = new ConceptCard.Building(u.label(), type, u.lots().size(), "M", false, null, null);
		String title = "The plan's " + (name.startsWith("the ") ? name.substring(4) : name) + " (" + side + ")";
		return new Proposal(u.key(), title, words, building, now, "the town's plan", null, way)
			.withPlace(dev.larattalabs.steward.model.DistrictSite.line(why == null ? "" : why, u.label()));
	}

	/**
	 * (PD-2) Beside the town a district goes only east or west (Noah's Q3; docs/DISTRICTS.md section 9): the line said when the ground the district asks
	 * for is to the north or south ({@code groundWay}, chosen from those sides alone, {@code groundWhy} its reason) and the side offered was chosen without
	 * a reason of its own ({@code offeredWhy} blank). Null otherwise: no ground wish, none found north or south, or the side offered suits it too.
	 */
	public static @Nullable String betterSideNote(@Nullable String offeredWhy, @Nullable String groundWay, @Nullable String groundWhy) {
		if (offeredWhy != null && !offeredWhy.isBlank()) return null;
		if (groundWay == null || !(groundWay.equals("N") || groundWay.equals("S")) || groundWhy == null) return null;
		String ground = groundWhy.replaceAll("\\s*\\([^)]*\\)", "").strip();
		// a side its words name ("the north ward": "to the north, as you said") is no ground it asks for
		if (ground.isEmpty() || ground.startsWith("to the ") || ground.contains("as you said")) return null;
		return "The ground it asks for (" + ground + ") is to the " + dev.larattalabs.steward.model.DistrictRules.directionName(groundWay)
			+ ", but a district beside the town goes only east or west for now.";
	}

	/**
	 * (PD-2) What the player hears once when the plan's district has no side beside the town to go (neither east nor west fits), so it isn't proposed: its
	 * name and lots, why, and that north and south are not offered for it yet.
	 */
	public static String noSideWords(Unbuilt u) {
		return "The town's plan names " + named(u.label()) + " (" + lotWords(u) + "), which its founding didn't build, but there is no room for it beside the town"
			+ " to the east or west, and a district beside the town doesn't go north or south yet. I haven't proposed it; it is looked at again when the town or"
			+ " its plan changes.";
	}

	/** The ground a district asks for, in words for the side chooser: its label and its {@code prefer} words ("near_water" as "by the water"). */
	public static String siteWords(Unbuilt u) {
		StringBuilder b = new StringBuilder(u.label());
		for (String p : u.prefer()) b.append(' ').append(switch (p) {
			case "near_water", "wet" -> "by the water";
			case "wooded" -> "in the woods";
			case "dry" -> "dry ground";
			case "open", "flat" -> "open flat ground";
			case "sloped" -> "on the hillside";
			default -> p.replace('_', ' ');
		});
		return b.toString();
	}

	/** The districts of every districts program in plan order. */
	static List<JsonObject> districts(JsonObject plan) {
		List<JsonObject> out = new ArrayList<>();
		if (!plan.has("zones") || !plan.get("zones").isJsonArray()) return out;
		for (JsonElement ze : plan.getAsJsonArray("zones")) {
			if (!ze.isJsonObject() || !ze.getAsJsonObject().has("program") || !ze.getAsJsonObject().get("program").isJsonObject()) continue;
			JsonObject p = ze.getAsJsonObject().getAsJsonObject("program");
			if (!p.has("districts") || !p.get("districts").isJsonArray()) continue;
			for (JsonElement de : p.getAsJsonArray("districts")) if (de.isJsonObject()) out.add(de.getAsJsonObject());
		}
		return out;
	}

	private static String article(String what) {
		return "aeiou".indexOf(Character.toLowerCase(what.charAt(0))) >= 0 ? "an" : "a";
	}

	private static String plural(String what) {
		if (what.endsWith("s") || what.endsWith("sh") || what.endsWith("ch") || what.endsWith("x")) return what + "es";
		if (what.endsWith("y") && what.length() > 1 && "aeiou".indexOf(what.charAt(what.length() - 2)) < 0) return what.substring(0, what.length() - 1) + "ies";
		return what + "s";
	}

	private static @Nullable String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}
}
