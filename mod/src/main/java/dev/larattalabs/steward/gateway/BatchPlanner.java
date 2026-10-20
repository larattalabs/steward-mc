package dev.larattalabs.steward.gateway;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.Batch;
import dev.larattalabs.architect.api.LotFit;
import dev.larattalabs.architect.api.Mode;
import dev.larattalabs.architect.api.PlaceRequest;
import dev.larattalabs.architect.api.RoadRequest;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.layout.VillageLayout.Front;
import dev.larattalabs.steward.layout.VillageLayout.Lot;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Turns the lots of a village plan, once their designs exist, into Architect's placement batch: one item per lot (keyed by the lot id), staged so the
 * landmarks go first and the rest follow in rings from the centre, with the mode decided by the world and the settlement's difficulty. Lots are fitted to
 * their rectangles with {@code Sites.fitToLot} (the caller does that, with a live world, and passes the results in); everything else here is pure.
 */
public final class BatchPlanner {
	/** Lots per stage after the landmark stage. */
	public static final int STAGE_SIZE = 4;

	private BatchPlanner() {
	}

	/** The lot's box for {@code fitToLot}: its rectangle from the ground height up to {@code height} blocks. */
	public static BoundingBox lotBox(Lot lot, int height) {
		return new BoundingBox(lot.x(), lot.groundY(), lot.z(), lot.maxX(), lot.groundY() + height - 1, lot.maxZ());
	}

	/** The side of the lot the street is on (its front): a lot facing SOUTH has the street to the south. */
	public static Direction streetSide(Lot lot) {
		return switch (lot.front()) {
			case SOUTH -> Direction.SOUTH;
			case NORTH -> Direction.NORTH;
			case EAST -> Direction.EAST;
			case WEST -> Direction.WEST;
		};
	}

	/** Patron may place instantly only where the world allows it; every other case builds construction sites. */
	public static Mode modeFor(Difficulty d, boolean worldSurvival) {
		return d.instantAllowed(worldSurvival) ? Mode.INSTANT : Mode.CONSTRUCTION;
	}

	/** One stage: its name and the lot ids in it. */
	public record StageDef(String name, List<String> lotIds) {}

	/**
	 * Stage 1 is the landmarks (so the settlement has a heart first); the rest follow in stages of {@link #STAGE_SIZE}, nearest to the street's middle first,
	 * so a half-built village always reads as a village. Every lot of the plan lands in exactly one stage.
	 */
	public static List<StageDef> stages(VillageLayout.Plan plan, Set<String> landmarkIds) {
		int midX = (plan.streetX0() + plan.streetX1()) / 2;
		List<Lot> rest = new ArrayList<>();
		List<String> landmarks = new ArrayList<>();
		for (Lot l : plan.lots()) {
			if (landmarkIds.contains(l.id())) landmarks.add(l.id());
			else rest.add(l);
		}
		rest.sort(Comparator.<Lot>comparingInt(l -> Math.abs((l.x() + l.maxX()) / 2 - midX)).thenComparing(Lot::id));
		List<StageDef> out = new ArrayList<>();
		if (!landmarks.isEmpty()) out.add(new StageDef("landmarks", landmarks));
		int n = 1;
		for (int i = 0; i < rest.size(); i += STAGE_SIZE, n++) {
			out.add(new StageDef("district_" + n, rest.subList(i, Math.min(rest.size(), i + STAGE_SIZE)).stream().map(Lot::id).toList()));
		}
		return out;
	}

	/**
	 * The batch.
	 *
	 * @param entries lot id to the library entry id of its finished design
	 * @param fits lot id to the {@code fitToLot} result for that entry
	 * @param level the world (may be null only in tests)
	 * @param autoApprove place stages as they come (Autonomous/Full permission); otherwise each stage waits for approval
	 * @return the batch, and the lot ids left out because they had no design or a fit that is not ok
	 */
	public static Result build(Settlement s, VillageLayout.Plan plan, Set<String> landmarkIds, Map<String, String> entries, Map<String, LotFit> fits,
		@Nullable ServerLevel level, boolean worldSurvival, boolean autoApprove) {
		return build(s, plan, landmarkIds, entries, fits, level, worldSurvival, autoApprove, false);
	}

	/**
	 * As {@link #build(Settlement, VillageLayout.Plan, Set, Map, Map, ServerLevel, boolean, boolean)}, optionally with the village street as an Architect road
	 * placed in a first stage, so every lot's approach stops at it (Architect 4e: roads are sites; an approach stopped by a road is not an overlap).
	 * Roads are instant-only in Architect 1.5.0: in a construction-mode batch the street is left out and the result says so.
	 */
	public static Result build(Settlement s, VillageLayout.Plan plan, Set<String> landmarkIds, Map<String, String> entries, Map<String, LotFit> fits,
		@Nullable ServerLevel level, boolean worldSurvival, boolean autoApprove, boolean includeStreet) {
		return build(s, plan, landmarkIds, entries, fits, level, worldSurvival, autoApprove, includeStreet, null);
	}

	/** As above, tagging every item's ext with {@code steward_mc:build = buildId} so a restart can tell this build's sites from an earlier build's. */
	public static Result build(Settlement s, VillageLayout.Plan plan, Set<String> landmarkIds, Map<String, String> entries, Map<String, LotFit> fits,
		@Nullable ServerLevel level, boolean worldSurvival, boolean autoApprove, boolean includeStreet, @Nullable String buildId) {
		return build(s, plan, landmarkIds, entries, fits, level, worldSurvival, autoApprove, includeStreet ? List.of(new int[] {plan.streetX0(), plan.streetX1()}) : List.of(),
			buildId, s.claim());
	}

	/**
	 * As above, with the street laid as {@code streetSpans} (x0..x1 pieces along the plan's street line: the whole street for a new one, the pieces an addition
	 * reaches past an existing street's ends), kept inside {@code land} (the town's claim or the district's the build is in).
	 */
	public static Result build(Settlement s, VillageLayout.Plan plan, Set<String> landmarkIds, Map<String, String> entries, Map<String, LotFit> fits,
		@Nullable ServerLevel level, boolean worldSurvival, boolean autoApprove, List<int[]> streetSpans, @Nullable String buildId, Claim land) {
		// the difficulty by the world's rule now (a world can switch survival on or off between builds)
		Mode mode = modeFor(Difficulty.effective(s.difficulty(), worldSurvival), worldSurvival);
		Map<String, Batch.Item> items = new LinkedHashMap<>();
		List<String> skipped = new ArrayList<>();
		List<StageDef> stageDefs = stages(plan, landmarkIds);
		Map<String, String> stageOf = new LinkedHashMap<>();
		for (StageDef sd : stageDefs) for (String id : sd.lotIds()) stageOf.put(id, sd.name());
		for (Lot l : plan.lots()) {
			String entry = entries.get(l.id());
			LotFit fit = fits.get(l.id());
			if (entry == null || fit == null || !placeable(fit)) {
				skipped.add(l.id());
				continue;
			}
			JsonObject ext = new JsonObject();
			ext.addProperty("steward_mc:settlement", s.id());
			ext.addProperty("steward_mc:lot", l.id());
			ext.addProperty("steward_mc:role", l.role());
			if (buildId != null) ext.addProperty(BUILD_EXT, buildId);
			PlaceRequest r = new PlaceRequest(entry, level, fit.origin(), fit.rotation(), mode, s.owner(), ext, false, null);
			items.put(l.id(), new Batch.Item(l.id(), r, stageOf.get(l.id()), List.of()));
		}
		List<Batch.StageSpec> specs = new ArrayList<>();
		String note = null;
		// construction (survival, or Supplied): one crate feeds the whole build, street included (Architect C16: a road in a shared-crate batch is built from
		// it; a dirt path on grass is free, fill and lanterns cost their items)
		boolean construction = mode == Mode.CONSTRUCTION;
		if (!streetSpans.isEmpty() && !plan.lots().isEmpty()) {
			List<String> keys = new ArrayList<>();
			for (int i = 0; i < streetSpans.size(); i++) {
				// the first piece is the street; more pieces are its extensions, along the same line ("#" keeps them out of the lots' ids, type_n)
				String key = i == 0 ? STREET_KEY : STREET_KEY + "#" + (i + 1);
				items.put(key, Batch.Item.road(key, street(s, plan, streetSpans.get(i)[0], streetSpans.get(i)[1], land, level, buildId, mode), "street", List.of()));
				keys.add(key);
			}
			specs.add(new Batch.StageSpec("street", keys));
		}
		if (construction) note = "built from materials: one crate feeds the whole build (its street first), filled by hand or by hoppers";
		for (StageDef sd : stageDefs) {
			List<String> ids = sd.lotIds().stream().filter(items::containsKey).toList();
			if (!ids.isEmpty()) specs.add(new Batch.StageSpec(sd.name(), ids));
		}
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		if (buildId != null) ext.addProperty(BUILD_EXT, buildId);
		// the batch's item order is the stockpile's feed order (Architect C24): the street first, then each stage's lots in stage order (the landmarks first)
		List<Batch.Item> ordered = new ArrayList<>();
		for (var e : items.entrySet()) if (isStreet(e.getKey())) ordered.add(e.getValue());
		for (StageDef sd : stageDefs) for (String id : sd.lotIds()) if (items.containsKey(id)) ordered.add(items.get(id));
		Batch b = new Batch(null, s.owner(), ext, null, ordered, specs, Batch.WaitPolicy.DEFAULT,
			dev.larattalabs.architect.api.LoadPolicy.LOADED_ONLY, null, false, autoApprove, construction, null);
		return new Result(b, List.copyOf(skipped), note);
	}

	public static final String STREET_KEY = "street";
	/** The ext key that names the build a placed site belongs to. */
	public static final String BUILD_EXT = "steward_mc:build";

	/** Whether an item key is a street's: the street, or an extension an addition laid ({@code street#2}, ...). A lot's id ({@code street_market_1}) is not. */
	public static boolean isStreet(@Nullable String itemKey) {
		// and a region's street: a flat village's (Architect V: road:street_w, road:street_e, split at its square), a district's (D1/D2:
		// road:<district>_street_w) and a free layout's (FL: road:street_ring, road:<prefix>street_a1), all keeping the street_ stem
		return itemKey != null && (itemKey.equals(STREET_KEY) || itemKey.startsWith(STREET_KEY + "#") || REGION_STREET.matcher(itemKey).matches());
	}

	private static final java.util.regex.Pattern REGION_STREET = java.util.regex.Pattern.compile("road:(?:[a-z0-9_]*_)?street(?:_[a-z0-9]+)?");

	/** (Architect FL) The road ext flag a region writes on every street road ({@code {zone, district?}}); read where present, the key otherwise. */
	public static final String REGION_STREET_EXT = "architect_mc:street";

	/** The ext keys a street piece records its centre line and ends under (its written box also holds lanterns, so it is not the line). */
	public static final String STREET_Z = "steward_mc:street_z", STREET_X0 = "steward_mc:street_x0", STREET_X1 = "steward_mc:street_x1";

	/** The village's main street as a road request: two waypoints along the street, a little past the outer lots, kept inside the claim. */
	static RoadRequest street(Settlement s, VillageLayout.Plan plan, @Nullable ServerLevel level, @Nullable String buildId) {
		return street(s, plan, plan.streetX0(), plan.streetX1(), s.claim(), level, buildId, Mode.INSTANT);
	}

	/** A piece of the street (x0..x1 along its line) as a road request, kept inside {@code land}, placed as the build's lots are ({@code mode}). */
	static RoadRequest street(Settlement s, VillageLayout.Plan plan, int from, int to, Claim land, @Nullable ServerLevel level, @Nullable String buildId, Mode mode) {
		// no run-out past the outer lots: in the phase 1 gate run the 4-block extension went over a drop (TOO_STEEP) and the whole street was refused
		int pad = 0;
		int x0 = Math.max(land.centerX() - land.radius(), from - pad);
		int x1 = Math.min(land.centerX() + land.radius(), to + pad);
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:street", true);
		ext.addProperty(STREET_Z, plan.streetZ());
		ext.addProperty(STREET_X0, x0);
		ext.addProperty(STREET_X1, x1);
		if (buildId != null) ext.addProperty(BUILD_EXT, buildId);
		return new RoadRequest(level, List.of(new BlockPos(x0, plan.streetY(), plan.streetZ()), new BlockPos(x1, plan.streetY(), plan.streetZ())), 3, null, null,
			true, false, mode, s.owner(), ext, null, false);
	}

	/** A fit is placeable when it has no refusal, or only temporary ones (player or mob in the box, chunks not loaded): the queue waits for those. */
	static boolean placeable(LotFit fit) {
		return fit.verdict().refusals().stream().allMatch(r -> r.reason() == dev.larattalabs.architect.api.Reason.PLAYER_IN_BOX || r.reason() == dev.larattalabs.architect.api.Reason.OCCUPIED
			|| r.reason() == dev.larattalabs.architect.api.Reason.NOT_LOADED);
	}

	public record Result(Batch batch, List<String> skippedLotIds, @Nullable String note) {
		public Result(Batch batch, List<String> skippedLotIds) {
			this(batch, skippedLotIds, null);
		}
	}
}
