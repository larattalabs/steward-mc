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
		return lot.front() == Front.SOUTH ? Direction.SOUTH : Direction.NORTH;
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
		Mode mode = modeFor(s.difficulty(), worldSurvival);
		Map<String, Batch.Item> items = new LinkedHashMap<>();
		List<String> skipped = new ArrayList<>();
		List<StageDef> stageDefs = stages(plan, landmarkIds);
		Map<String, String> stageOf = new LinkedHashMap<>();
		for (StageDef sd : stageDefs) for (String id : sd.lotIds()) stageOf.put(id, sd.name());
		for (Lot l : plan.lots()) {
			String entry = entries.get(l.id());
			LotFit fit = fits.get(l.id());
			if (entry == null || fit == null || !fit.ok()) {
				skipped.add(l.id());
				continue;
			}
			JsonObject ext = new JsonObject();
			ext.addProperty("steward_mc:settlement", s.id());
			ext.addProperty("steward_mc:lot", l.id());
			PlaceRequest r = new PlaceRequest(entry, level, fit.origin(), fit.rotation(), mode, s.owner(), ext, false, null);
			items.put(l.id(), new Batch.Item(l.id(), r, stageOf.get(l.id()), List.of()));
		}
		List<Batch.StageSpec> specs = new ArrayList<>();
		String note = null;
		if (includeStreet) {
			if (mode == Mode.INSTANT && !plan.lots().isEmpty()) {
				items.put(STREET_KEY, Batch.Item.road(STREET_KEY, street(s, plan, level), "street", List.of()));
				specs.add(new Batch.StageSpec("street", List.of(STREET_KEY)));
			} else {
				note = "the street was left out: roads can only be placed instantly (Patron in a world with survival off)";
			}
		}
		for (StageDef sd : stageDefs) {
			List<String> ids = sd.lotIds().stream().filter(items::containsKey).toList();
			if (!ids.isEmpty()) specs.add(new Batch.StageSpec(sd.name(), ids));
		}
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		Batch b = new Batch(null, s.owner(), ext, s.id(), new ArrayList<>(items.values()), specs, Batch.WaitPolicy.DEFAULT,
			dev.larattalabs.architect.api.LoadPolicy.LOADED_ONLY, null, false, autoApprove, false, null);
		return new Result(b, List.copyOf(skipped), note);
	}

	public static final String STREET_KEY = "street";

	/** The village's main street as a road request: two waypoints along the street, a little past the outer lots, kept inside the claim. */
	static RoadRequest street(Settlement s, VillageLayout.Plan plan, @Nullable ServerLevel level) {
		int pad = 4;
		int x0 = Math.max(s.claim().centerX() - s.claim().radius(), plan.streetX0() - pad);
		int x1 = Math.min(s.claim().centerX() + s.claim().radius(), plan.streetX1() + pad);
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:street", true);
		return new RoadRequest(level, List.of(new BlockPos(x0, plan.streetY(), plan.streetZ()), new BlockPos(x1, plan.streetY(), plan.streetZ())), 3, null, null,
			true, false, Mode.INSTANT, s.owner(), ext, null, false);
	}

	public record Result(Batch batch, List<String> skippedLotIds, @Nullable String note) {
		public Result(Batch batch, List<String> skippedLotIds) {
			this(batch, skippedLotIds, null);
		}
	}
}
