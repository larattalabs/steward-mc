package dev.larattalabs.steward.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.LotSpec;
import dev.larattalabs.architect.api.RegionPlan;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.model.Difficulty;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Architect's flat village (slice V, {@code Regions.plan("flat_village")}) for Steward's builds (docs/PLAN.md "V"): which builds use it, the lots it is
 * given, and its planned lots in Steward's plan shape (so the designs, the steward's walks and the saved build stay as they are). Pure.
 */
public final class VPlan {
	public static final String PROGRAM = "flat_village";
	/** The tag a V build's history carries in place of a site group: undo is {@code Regions.remove} of this region. */
	public static final String REGION_PREFIX = "region:";
	/** Ground that rises more than this across the surveyed square is not flat: the build keeps Steward's own layout. */
	public static final int FLAT_RANGE = 12;
	/** Settlements a host set to Steward's own layout (dev and the e2e check: both paths stay tested). One world's. */
	private static final Set<String> LEGACY = new HashSet<>();

	private VPlan() {
	}

	public static void legacy(String settlementId, boolean on) {
		if (on) LEGACY.add(settlementId);
		else LEGACY.remove(settlementId);
	}

	public static void clear() {
		LEGACY.clear();
	}

	/**
	 * Whether a build uses the flat village: Patron (no materials) in a world without survival, a settlement's first whole build (not an addition, not in a
	 * district, nothing standing), a card whose terrain is flat or as found (a sculpted form is a region program of its own), and flat ground.
	 */
	public static boolean applies(String settlementId, Difficulty difficulty, boolean worldSurvival, boolean addition, @Nullable String districtId, String terrain,
		boolean somethingStands, int groundLow, int groundHigh) {
		if (LEGACY.contains(settlementId)) return false;
		return difficulty == Difficulty.PATRON && !worldSurvival && !addition && districtId == null && !"sculpt".equals(terrain) && !somethingStands
			&& groundHigh - groundLow <= FLAT_RANGE;
	}

	/** The program's {@code params}: the card's program lots as Steward lays them out ({@code villager} left to Architect, which infers it from the type). */
	public static JsonObject params(List<VillageLayout.LotSpec> specs) {
		JsonArray lots = new JsonArray();
		for (var l : specs) {
			JsonObject o = new JsonObject();
			o.addProperty("id", l.id());
			o.addProperty("type", l.type());
			o.addProperty("role", l.role());
			JsonArray size = new JsonArray();
			size.add(l.sizeX());
			size.add(l.sizeZ());
			o.add("size", size);
			o.addProperty("landmark", l.landmark());
			if (l.placement() != null) o.addProperty("placement", l.placement());
			if (l.notes() != null) o.addProperty("brief", l.notes());
			lots.add(o);
		}
		JsonObject params = new JsonObject();
		params.add("lots", lots);
		return params;
	}

	/**
	 * Architect's planned lots as Steward's plan: each lot's box, floor and front; its role, type and notes from the card ({@code specs} by id); the lots it
	 * could not place as unplaced; the street's line and ends read from the lots' entrances (where the steward waits while the village is placed).
	 */
	public static VillageLayout.Plan toPlan(RegionPlan p, List<VillageLayout.LotSpec> specs) {
		return toPlan(p.lots(), specs);
	}

	public static VillageLayout.Plan toPlan(List<LotSpec> placed, List<VillageLayout.LotSpec> specs) {
		Map<String, VillageLayout.LotSpec> byId = new java.util.HashMap<>();
		for (var s : specs) byId.put(s.id(), s);
		List<VillageLayout.Lot> lots = new ArrayList<>();
		List<Integer> zs = new ArrayList<>();
		List<Integer> ys = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
		for (LotSpec l : placed) {
			var spec = byId.get(l.id());
			if (spec == null) continue;
			seen.add(l.id());
			var b = l.lot();
			VillageLayout.Front front = l.front() == net.minecraft.core.Direction.NORTH ? VillageLayout.Front.NORTH : VillageLayout.Front.SOUTH;
			lots.add(new VillageLayout.Lot(l.id(), spec.type(), b.minX(), b.minZ(), b.getXSpan(), b.getZSpan(), front, l.floorY(), spec.role(), spec.notes(),
				spec.landmark(), spec.placement()));
			var v = l.ext() != null && l.ext().has("architect_mc:village") ? l.ext().getAsJsonObject("architect_mc:village") : null;
			if (v != null && v.has("entrance") && v.get("entrance").isJsonArray() && v.getAsJsonArray("entrance").size() == 3) {
				zs.add(v.getAsJsonArray("entrance").get(2).getAsInt());
				ys.add(v.getAsJsonArray("entrance").get(1).getAsInt());
			} else {
				zs.add(front == VillageLayout.Front.SOUTH ? b.maxZ() + 1 : b.minZ() - 1);
				ys.add(l.floorY());
			}
			x0 = Math.min(x0, b.minX());
			x1 = Math.max(x1, b.maxX());
		}
		List<VillageLayout.LotSpec> unplaced = specs.stream().filter(s -> !seen.contains(s.id())).toList();
		zs.sort(Integer::compare);
		ys.sort(Integer::compare);
		int streetZ = zs.isEmpty() ? 0 : zs.get(zs.size() / 2);
		int streetY = ys.isEmpty() ? 64 : ys.get(ys.size() / 2);
		return new VillageLayout.Plan(List.copyOf(lots), streetZ, lots.isEmpty() ? 0 : x0, lots.isEmpty() ? 0 : x1, streetY, unplaced);
	}

	/** The region a history entry's site group names ({@code region:<id>}), or null for an ordinary site group. */
	public static @Nullable String regionOf(@Nullable String siteGroup) {
		return siteGroup != null && siteGroup.startsWith(REGION_PREFIX) ? siteGroup.substring(REGION_PREFIX.length()) : null;
	}
}
