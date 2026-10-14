package dev.larattalabs.steward.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.larattalabs.steward.layout.VillageLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Building a settlement from its composed plan (docs/COMPOSITION.md section 10, plan v1): the plan as it is built (the parts that wait are not required,
 * so Architect drops them and builds the rest; each lot carries Steward's tags), and the approved artifact's lots in Steward's plan shape (as {@link VPlan}
 * does for the flat village). Pure.
 */
public final class PPlan {
	/** A build realised from an approved artifact keeps it where a flat village keeps its plan id, with this prefix (the saved build's format is unchanged). */
	public static final String ARTIFACT_PREFIX = "artifact:";
	/** The plan's part lists, each of whose parts has an id and may be required. */
	static final List<String> PART_LISTS = List.of("zones", "connectors", "forms", "rules");
	/** Settlements a host set to the flat village without their plan (dev and the e2e check: the V path stays tested). One world's. */
	private static final Set<String> VILLAGE_ONLY = new HashSet<>();

	private PPlan() {
	}

	public static void villageOnly(String settlementId, boolean on) {
		if (on) VILLAGE_ONLY.add(settlementId);
		else VILLAGE_ONLY.remove(settlementId);
	}

	public static boolean villageOnly(String settlementId) {
		return VILLAGE_ONLY.contains(settlementId);
	}

	public static void clear() {
		VILLAGE_ONLY.clear();
	}

	public static @Nullable String artifactOf(@Nullable String vPlan) {
		return vPlan != null && vPlan.startsWith(ARTIFACT_PREFIX) ? vPlan.substring(ARTIFACT_PREFIX.length()) : null;
	}

	/**
	 * The plan as this build builds it: a copy of {@code plan} with the parts that wait for Architect ({@code waiting}, by part id) no longer required (Noah's
	 * 9.2 default: the town is built now, the part stays pending), and each lot of each zone tagged as Steward tags a batch lot (settlement, lot, role, build;
	 * Architect C19 puts them on the lot's site). Deterministic: the same inputs make the same plan, so a re-check after a restart or a stale artifact checks
	 * the same plan.
	 */
	public static JsonObject forBuild(JsonObject plan, Set<String> waiting, String settlementId, String buildId) {
		JsonObject out = plan.deepCopy();
		for (String list : PART_LISTS) {
			if (!out.has(list) || !out.get(list).isJsonArray()) continue;
			for (JsonElement e : out.getAsJsonArray(list)) {
				if (!e.isJsonObject()) continue;
				JsonObject part = e.getAsJsonObject();
				if (part.has("id") && waiting.contains(part.get("id").getAsString())) part.addProperty("required", false);
			}
		}
		for (JsonObject lot : lots(out)) {
			JsonObject ext = lot.has("ext") && lot.get("ext").isJsonObject() ? lot.getAsJsonObject("ext") : new JsonObject();
			ext.addProperty("steward_mc:settlement", settlementId);
			ext.addProperty("steward_mc:lot", lot.get("id").getAsString());
			ext.addProperty("steward_mc:role", role(lot));
			ext.addProperty(BatchPlanner.BUILD_EXT, buildId);
			lot.add("ext", ext);
		}
		return out;
	}

	/** Every lot of every zone: a zone's own {@code program.lots}, or its named template's {@code program.params.lots}. */
	static List<JsonObject> lots(JsonObject plan) {
		List<JsonObject> out = new ArrayList<>();
		if (!plan.has("zones") || !plan.get("zones").isJsonArray()) return out;
		for (JsonElement z : plan.getAsJsonArray("zones")) {
			if (!z.isJsonObject() || !z.getAsJsonObject().has("program") || !z.getAsJsonObject().get("program").isJsonObject()) continue;
			JsonObject program = z.getAsJsonObject().getAsJsonObject("program");
			JsonArray lots = program.has("lots") && program.get("lots").isJsonArray() ? program.getAsJsonArray("lots")
				: program.has("params") && program.get("params").isJsonObject() && program.getAsJsonObject("params").has("lots")
					&& program.getAsJsonObject("params").get("lots").isJsonArray() ? program.getAsJsonObject("params").getAsJsonArray("lots") : null;
			if (lots == null) continue;
			for (JsonElement l : lots) if (l.isJsonObject() && l.getAsJsonObject().has("id")) out.add(l.getAsJsonObject());
		}
		return out;
	}

	/** A lot's role for the screens and the history: its label (the card's own words), else its role slug, else its type. */
	static String role(JsonObject lot) {
		for (String k : List.of("label", "role", "type")) if (lot.has(k) && lot.get(k).isJsonPrimitive() && !lot.get(k).getAsString().isBlank()) return lot.get(k).getAsString().replace('_', ' ');
		return lot.get("id").getAsString().replace('_', ' ');
	}

	/** Whether any of the artifact's lots faces east or west (Steward's designs and growth know an east-west street only, as {@link VPlan#northSouth}). */
	public static boolean northSouth(JsonArray artifactLots) {
		for (JsonElement e : artifactLots) {
			String f = e.isJsonObject() && e.getAsJsonObject().has("front") && e.getAsJsonObject().get("front").isJsonPrimitive()
				? e.getAsJsonObject().get("front").getAsString().toLowerCase(java.util.Locale.ROOT) : "";
			if (f.equals("east") || f.equals("west")) return true;
		}
		return false;
	}

	/**
	 * The approved artifact's lots ({@code $.lots[]}: id, box {@code [x0,y0,z0,x1,y1,z1]}, front, entrance) as Steward's plan, each with its type, role,
	 * brief, landmark and placement from the plan's own lot ({@code plan}, as built); the plan's lots the artifact has not as unplaced; the street's line and
	 * ends read from the entrances (where the steward waits while it is placed).
	 */
	public static VillageLayout.Plan toPlan(JsonArray artifactLots, JsonObject plan) {
		Map<String, JsonObject> byId = new java.util.LinkedHashMap<>();
		for (JsonObject l : lots(plan)) byId.put(l.get("id").getAsString(), l);
		List<VillageLayout.Lot> out = new ArrayList<>();
		List<Integer> zs = new ArrayList<>(), ys = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
		for (JsonElement e : artifactLots) {
			if (!e.isJsonObject()) continue;
			JsonObject a = e.getAsJsonObject();
			String id = a.get("id").getAsString();
			JsonObject p = byId.get(id);
			if (p == null || !a.has("box") || !a.get("box").isJsonArray() || a.getAsJsonArray("box").size() != 6) continue;
			seen.add(id);
			JsonArray b = a.getAsJsonArray("box");
			int bx0 = b.get(0).getAsInt(), by0 = b.get(1).getAsInt(), bz0 = b.get(2).getAsInt(), bx1 = b.get(3).getAsInt(), bz1 = b.get(5).getAsInt();
			String f = a.has("front") && a.get("front").isJsonPrimitive() ? a.get("front").getAsString().toLowerCase(java.util.Locale.ROOT) : "south";
			VillageLayout.Front front = f.equals("north") ? VillageLayout.Front.NORTH : VillageLayout.Front.SOUTH;
			String type = str(p, "type", id.replaceFirst("_\\d+$", ""));
			out.add(new VillageLayout.Lot(id, type, bx0, bz0, bx1 - bx0 + 1, bz1 - bz0 + 1, front, by0, role(p), p.has("brief") ? str(p, "brief", null) : null,
				p.has("landmark") && p.get("landmark").isJsonPrimitive() && p.get("landmark").getAsBoolean(), p.has("placement") ? str(p, "placement", null) : null));
			JsonArray en = a.has("entrance") && a.get("entrance").isJsonArray() && a.getAsJsonArray("entrance").size() == 3 ? a.getAsJsonArray("entrance") : null;
			zs.add(en != null ? en.get(2).getAsInt() : front == VillageLayout.Front.SOUTH ? bz1 + 1 : bz0 - 1);
			ys.add(en != null ? en.get(1).getAsInt() : by0);
			x0 = Math.min(x0, bx0);
			x1 = Math.max(x1, bx1);
		}
		List<VillageLayout.LotSpec> unplaced = new ArrayList<>();
		for (var p : byId.values()) {
			String id = p.get("id").getAsString();
			if (seen.contains(id)) continue;
			int[] size = size(p);
			unplaced.add(new VillageLayout.LotSpec(id, str(p, "type", id.replaceFirst("_\\d+$", "")), size[0], size[1], role(p), p.has("brief") ? str(p, "brief", null) : null,
				p.has("landmark") && p.get("landmark").isJsonPrimitive() && p.get("landmark").getAsBoolean(), p.has("placement") ? str(p, "placement", null) : null));
		}
		zs.sort(Integer::compare);
		ys.sort(Integer::compare);
		return new VillageLayout.Plan(List.copyOf(out), zs.isEmpty() ? 0 : zs.get(zs.size() / 2), out.isEmpty() ? 0 : x0, out.isEmpty() ? 0 : x1,
			ys.isEmpty() ? 64 : ys.get(ys.size() / 2), List.copyOf(unplaced));
	}

	private static int[] size(JsonObject lot) {
		if (lot.has("size") && lot.get("size").isJsonArray() && lot.getAsJsonArray("size").size() == 2) return new int[] {lot.getAsJsonArray("size").get(0).getAsInt(),
			lot.getAsJsonArray("size").get(1).getAsInt()};
		return new int[] {9, 9};
	}

	private static @Nullable String str(JsonObject o, String k, @Nullable String dflt) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : dflt;
	}
}
