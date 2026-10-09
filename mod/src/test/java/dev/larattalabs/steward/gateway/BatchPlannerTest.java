package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.Batch;
import dev.larattalabs.architect.api.LotFit;
import dev.larattalabs.architect.api.Mode;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.architect.api.Refusal;
import dev.larattalabs.architect.api.Verdict;
import dev.larattalabs.steward.layout.Grid;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.layout.VillageLayout.Lot;
import dev.larattalabs.steward.layout.VillageLayout.LotSpec;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class BatchPlannerTest {
	private static final Claim CLAIM = new Claim("minecraft:overworld", 0, 0, 96, -64, 320);

	private static Settlement settlement(Difficulty d) throws Exception {
		var in = BatchPlannerTest.class.getClassLoader().getResourceAsStream("stilt_village.json");
		ConceptCard c = ConceptCard.parse(JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
		return Settlement.found("set_1", c, CLAIM, Permission.PROPOSALS, d, 1L);
	}

	private static VillageLayout.Plan plan(int n) {
		List<LotSpec> specs = new ArrayList<>();
		for (int i = 0; i < n; i++) specs.add(new LotSpec("lot_" + i, "house", 12, 11));
		return VillageLayout.plan(CLAIM, Grid.flat(-96, -96, 193, 193, 70), specs, VillageLayout.Rules.defaults());
	}

	private static LotFit okFit(Lot l) {
		BoundingBox b = BatchPlanner.lotBox(l, 24);
		return new LotFit(new BlockPos(l.x(), l.groundY(), l.z()), Rotation.NONE, b, Optional.of(b), new Verdict(List.of(), List.of(), false, Map.of(), Optional.of(b), Optional.of(b)));
	}

	@Test
	void lotBoxCoversTheRectangleFromTheGroundUp() {
		Lot l = new Lot("a", "house", 10, 20, 12, 11, VillageLayout.Front.SOUTH, 70);
		BoundingBox b = BatchPlanner.lotBox(l, 24);
		assertEquals(10, b.minX());
		assertEquals(21, b.maxX());
		assertEquals(20, b.minZ());
		assertEquals(30, b.maxZ());
		assertEquals(70, b.minY());
		assertEquals(93, b.maxY());
	}

	@Test
	void streetSideIsTheFrontDirection() {
		assertEquals(Direction.SOUTH, BatchPlanner.streetSide(new Lot("a", "x", 0, 0, 9, 9, VillageLayout.Front.SOUTH, 70)));
		assertEquals(Direction.NORTH, BatchPlanner.streetSide(new Lot("a", "x", 0, 0, 9, 9, VillageLayout.Front.NORTH, 70)));
	}

	@Test
	void patronIsInstantOnlyWhereTheWorldAllowsIt() {
		assertEquals(Mode.INSTANT, BatchPlanner.modeFor(Difficulty.PATRON, false));
		assertEquals(Mode.CONSTRUCTION, BatchPlanner.modeFor(Difficulty.PATRON, true));
		assertEquals(Mode.CONSTRUCTION, BatchPlanner.modeFor(Difficulty.SUPPLIED, false));
		assertEquals(Mode.CONSTRUCTION, BatchPlanner.modeFor(Difficulty.HARDCORE, true));
	}

	@Test
	void everyLotLandsInExactlyOneStageAndLandmarksComeFirst() {
		VillageLayout.Plan p = plan(11);
		Set<String> landmarks = Set.of("lot_0", "lot_5");
		List<BatchPlanner.StageDef> st = BatchPlanner.stages(p, landmarks);
		assertEquals("landmarks", st.get(0).name());
		assertEquals(Set.copyOf(st.get(0).lotIds()), landmarks);
		List<String> all = st.stream().flatMap(s -> s.lotIds().stream()).toList();
		assertEquals(p.lots().size(), all.size());
		assertEquals(p.lots().size(), new HashSet<>(all).size());
		for (int i = 1; i < st.size(); i++) assertTrue(st.get(i).lotIds().size() <= BatchPlanner.STAGE_SIZE);
	}

	@Test
	void laterStagesAreFartherFromTheStreetMiddle() {
		VillageLayout.Plan p = plan(12);
		List<BatchPlanner.StageDef> st = BatchPlanner.stages(p, Set.of());
		Map<String, Lot> byId = new HashMap<>();
		p.lots().forEach(l -> byId.put(l.id(), l));
		int mid = (p.streetX0() + p.streetX1()) / 2;
		int prevMax = -1;
		for (BatchPlanner.StageDef s : st) {
			int min = s.lotIds().stream().mapToInt(id -> Math.abs((byId.get(id).x() + byId.get(id).maxX()) / 2 - mid)).min().orElse(0);
			int max = s.lotIds().stream().mapToInt(id -> Math.abs((byId.get(id).x() + byId.get(id).maxX()) / 2 - mid)).max().orElse(0);
			assertTrue(min >= prevMax - 0, "stage " + s.name() + " starts no nearer than the previous one ended");
			prevMax = Math.max(prevMax, max);
		}
	}

	@Test
	void theBatchHasOneItemPerDesignedLotWithOwnerGroupModeAndNoActor() throws Exception {
		Settlement s = settlement(Difficulty.PATRON);
		VillageLayout.Plan p = plan(6);
		Map<String, String> entries = new HashMap<>();
		Map<String, LotFit> fits = new HashMap<>();
		for (Lot l : p.lots()) { entries.put(l.id(), "gen_" + l.id()); fits.put(l.id(), okFit(l)); }
		BatchPlanner.Result r = BatchPlanner.build(s, p, Set.of("lot_0"), entries, fits, null, false, false);
		Batch b = r.batch();
		assertEquals(p.lots().size(), b.items().size());
		assertTrue(r.skippedLotIds().isEmpty());
		assertEquals("steward_mc:settlement/set_1", b.owner());
		assertNull(b.group(), "a new batch makes a new site group; an existing group id is only for appending");
		assertFalse(b.autoApprove());
		for (Batch.Item i : b.items()) {
			assertEquals(Mode.INSTANT, i.request().mode());
			assertNull(i.request().actor());
			assertEquals(i.itemKey(), i.request().ext().get("steward_mc:lot").getAsString());
			assertNotNull(i.stage());
		}
		assertEquals("landmarks", b.stages().get(0).name());
	}

	@Test
	void lotsWithoutADesignOrAGoodFitAreSkippedAndNamed() throws Exception {
		Settlement s = settlement(Difficulty.SUPPLIED);
		VillageLayout.Plan p = plan(4);
		Map<String, String> entries = new HashMap<>();
		Map<String, LotFit> fits = new HashMap<>();
		List<Lot> lots = p.lots();
		for (Lot l : lots) { entries.put(l.id(), "gen_" + l.id()); fits.put(l.id(), okFit(l)); }
		entries.remove(lots.get(0).id()); // no design
		BoundingBox b = BatchPlanner.lotBox(lots.get(1), 24);
		fits.put(lots.get(1).id(), new LotFit(new BlockPos(0, 0, 0), Rotation.NONE, b, Optional.empty(),
			new Verdict(List.of(new Refusal(Reason.LOT_TOO_SMALL, "too small")), List.of(), false, Map.of(), Optional.empty(), Optional.empty())));
		BatchPlanner.Result r = BatchPlanner.build(s, p, Set.of(), entries, fits, null, false, true);
		assertEquals(2, r.batch().items().size());
		assertEquals(Set.of(lots.get(0).id(), lots.get(1).id()), new HashSet<>(r.skippedLotIds()));
		assertTrue(r.batch().autoApprove());
		for (Batch.Item i : r.batch().items()) assertEquals(Mode.CONSTRUCTION, i.request().mode());
		for (Batch.StageSpec st : r.batch().stages()) assertFalse(st.items().contains(lots.get(0).id()));
	}

	@Test
	void theStreetIsARoadInAFirstStageWhenInstant() throws Exception {
		Settlement s = settlement(Difficulty.PATRON);
		VillageLayout.Plan p = plan(6);
		Map<String, String> entries = new HashMap<>();
		Map<String, LotFit> fits = new HashMap<>();
		for (Lot l : p.lots()) { entries.put(l.id(), "gen_" + l.id()); fits.put(l.id(), okFit(l)); }
		BatchPlanner.Result r = BatchPlanner.build(s, p, Set.of(), entries, fits, null, false, false, true);
		assertNull(r.note());
		assertEquals(p.lots().size() + 1, r.batch().items().size());
		assertEquals("street", r.batch().stages().get(0).name());
		Batch.Item street = r.batch().items().stream().filter(i -> "street".equals(i.itemKey())).findFirst().orElseThrow();
		assertNull(street.request());
		assertNotNull(street.road());
		assertEquals(3, street.road().width());
		assertEquals(Mode.INSTANT, street.road().mode());
		assertEquals(p.streetZ(), street.road().points().get(0).getZ());
		assertTrue(CLAIM.contains("minecraft:overworld", street.road().points().get(0).getX(), 70, street.road().points().get(0).getZ()));
		assertTrue(CLAIM.contains("minecraft:overworld", street.road().points().get(1).getX(), 70, street.road().points().get(1).getZ()));
	}

	@Test
	void noStreetInConstructionModeAndTheResultSaysWhy() throws Exception {
		Settlement s = settlement(Difficulty.SUPPLIED);
		VillageLayout.Plan p = plan(4);
		Map<String, String> entries = new HashMap<>();
		Map<String, LotFit> fits = new HashMap<>();
		for (Lot l : p.lots()) { entries.put(l.id(), "gen_" + l.id()); fits.put(l.id(), okFit(l)); }
		BatchPlanner.Result r = BatchPlanner.build(s, p, Set.of(), entries, fits, null, false, false, true);
		assertNotNull(r.note());
		assertEquals(p.lots().size(), r.batch().items().size());
		assertTrue(r.batch().items().stream().noneMatch(i -> "street".equals(i.itemKey())));
	}
}
