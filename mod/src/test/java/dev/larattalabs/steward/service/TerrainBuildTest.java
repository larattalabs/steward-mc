package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.PartBuild;
import dev.larattalabs.architect.api.RegionBuild;
import dev.larattalabs.architect.api.SiteWait;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

/** (slice 8aS) Architect 8a's staged terrain and half-built safety, Steward's side: the time line, the terrain wait, the held line, the NPC's boxes. */
class TerrainBuildTest {
	private static PartBuild part(String id, String state, long terrain, long built, String... targets) {
		return new PartBuild(id, state, List.of(targets), 100, 10, 0, terrain, built);
	}

	private static RegionBuild build(int rate, PartBuild... parts) {
		return new RegionBuild(false, false, 12, Map.of(), List.of(parts), rate);
	}

	private static JsonObject ext(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	@Test
	void minutesRoundUpAtTheWorldsRate() {
		// 16 a tick: 19,200 a minute
		assertEquals(1, TerrainBuild.minutes(1, 16));
		assertEquals(1, TerrainBuild.minutes(19_200, 16));
		assertEquals(2, TerrainBuild.minutes(19_201, 16));
		// x01's 32,078 staged cells at 16 a tick: about 2 min
		assertEquals(2, TerrainBuild.minutes(32_078, 16));
		assertEquals(0, TerrainBuild.minutes(0, 16));
		assertEquals(0, TerrainBuild.minutes(500, 0), "an older Architect's rate 0 is never divided by");
	}

	@Test
	void theTimeLineCountsWhatIsLeftOfEveryPart() {
		var b = build(16, part("town", "building", 8_139, 139), part("isle", "waiting", 8_884, 0), part("wall", "building", 0, 0));
		assertEquals(16_884, TerrainBuild.remaining(b));
		assertEquals("The ground is shaped first, and free: 16884 blocks of earth, about 1 min while its land is loaded.", TerrainBuild.timeLine(b, true));
		// at 1 a tick (the clamp's floor): 1,200 a minute
		assertEquals("The ground is shaped first, and free: 16884 blocks of earth, about 15 min while its land is loaded.", TerrainBuild.timeLine(build(1,
			part("town", "building", 8_139, 139), part("isle", "waiting", 8_884, 0)), true));
		assertEquals("The ground is shaped first, and free: 1 block of earth, about 1 min while its land is loaded.", TerrainBuild.timeLine(build(16,
			part("town", "building", 10, 9)), true));
	}

	@Test
	void noTimeLineWithoutTheFeatureARateOrTerrainLeft() {
		var b = build(16, part("town", "building", 8_139, 139));
		assertNull(TerrainBuild.timeLine(b, false), "feature terrainConstruction absent");
		assertNull(TerrainBuild.timeLine(build(0, part("town", "building", 8_139, 139)), true), "the 1.38.0 shape: rate 0");
		assertNull(TerrainBuild.timeLine(build(16, part("town", "building", 8_139, 8_139)), true), "the ground is built");
		assertNull(TerrainBuild.timeLine(build(16, part("town", "building", 0, 0)), true), "no staged terrain (a creative or paid-only build)");
		assertNull(TerrainBuild.timeLine(null, true));
		// built past its count (a cell vanilla changed counted twice) never reads as negative
		assertEquals(0, TerrainBuild.remaining(build(16, part("town", "building", 10, 12))));
	}

	@Test
	void aTerrainWaitInTheStewardsWords() {
		var e = ext("{\"architect_mc:region\": \"rg1\", \"architect_mc:waitsFor\": {\"target\": \"c4\", \"part\": \"isle\", \"waiter\": \"farm_1\", \"kind\": \"terrain\","
			+ " \"terrainCells\": 7200, \"terrainBuilt\": 1200, \"say\": \"the farm waits for the island to be built\"}}");
		assertEquals("The farm's ground is being shaped first: about 6000 blocks of earth to go.", TerrainBuild.waitLine(e, "farm"));
		// (8bS) the subject is the typed part in words, never cut out of the say
		assertEquals("The build's ground is being shaped first: about 6000 blocks of earth to go.", TerrainBuild.waitLine(e, null));
		assertEquals("terrain|c4|farm_1|isle", TerrainBuild.waitKey(TerrainBuild.terrainWait(e)));
		// its counts move as the ground is built: the same wait, said once
		var later = ext("{\"architect_mc:waitsFor\": {\"target\": \"c4\", \"part\": \"isle\", \"waiter\": \"farm_1\", \"kind\": \"terrain\", \"terrainCells\": 7200,"
			+ " \"terrainBuilt\": 7000, \"say\": \"the farm waits for the island to be built\"}}");
		assertEquals(TerrainBuild.waitKey(TerrainBuild.terrainWait(e)), TerrainBuild.waitKey(TerrainBuild.terrainWait(later)));
		assertEquals("The farm's ground is being shaped first: about 200 blocks of earth to go.", TerrainBuild.waitLine(later, "farm"));
		assertEquals("The street's ground is being shaped first: about 1 block of earth to go.", TerrainBuild.waitLine(ext(
			"{\"architect_mc:waitsFor\": {\"kind\": \"terrain\", \"terrainCells\": 5, \"terrainBuilt\": 4, \"say\": \"the street waits for the hamlet to be built\"}}"), "street"));
		assertEquals("The Mill Steps' ground is being shaped first: nearly done.", TerrainBuild.waitLine(ext(
			"{\"architect_mc:waitsFor\": {\"kind\": \"terrain\", \"terrainCells\": 5, \"terrainBuilt\": 5, \"say\": \"x\"}}"), "Mill Steps"));
	}

	@Test
	void notATerrainWaitOrNoSentence() {
		// another item of the same part carries the wait without its sentence: a terrain wait, said by the one that has it
		var quiet = ext("{\"architect_mc:waitsFor\": {\"target\": \"c4\", \"kind\": \"terrain\", \"terrainCells\": 7200, \"terrainBuilt\": 0}}");
		assertNotNull(TerrainBuild.terrainWait(quiet));
		assertNull(TerrainBuild.waitLine(quiet, "farm"));
		// a plain stage wait and a bill_cap wait are not terrain waits (Architect's sentence and the cap's words keep them)
		assertNull(TerrainBuild.terrainWait(ext("{\"architect_mc:waitsFor\": {\"say\": \"the street waits for the town wall to be built\"}}")));
		assertNull(TerrainBuild.terrainWait(ext("{\"architect_mc:waitsFor\": {\"kind\": \"bill_cap\", \"say\": \"x waits bill_cap\"}}")));
		assertNull(TerrainBuild.terrainWait(ext("{\"architect_mc:region\": \"rg3\"}")));
		assertNull(TerrainBuild.terrainWait(null));
		assertNull(TerrainBuild.waitLine(null, "farm"));
	}

	@Test
	void aTargetHeldByAnotherChange() {
		var held = new SiteWait("c4", true, 100, List.of(), 0, SiteWait.TXN_HELD, List.of());
		assertTrue(MaterialRequests.held(held));
		assertFalse(MaterialRequests.capped(held));
		assertFalse(MaterialRequests.held(new SiteWait("c4", true, 100, List.of(), 0)));
		assertFalse(MaterialRequests.held(new SiteWait("c4", true, 100, List.of(), 0, SiteWait.BILL_CAP, List.of())));
		assertEquals("The wheat farm is held by another change for a moment: it carries on by itself.", MaterialRequests.heldWords("wheat farm"));
		// said once per wait: its key carries why it waits
		assertTrue(MaterialRequests.saidKey(held).endsWith("|txn_held"));
	}

	@Test
	void theStewardKeepsOffPartsBeingBuilt() {
		var boxes = Map.of("t1", new BoundingBox(0, 60, 0, 15, 70, 15), "t2", new BoundingBox(16, 60, 0, 31, 70, 15), "t3", new BoundingBox(100, 60, 100, 110, 70, 110));
		var b = build(16, part("town", "building", 500, 10, "t1", "t2", "gone"), part("isle", "waiting", 400, 0, "t3"), part("wall", "built", 0, 0, "t3"));
		var off = TerrainBuild.keepOff(b, t -> Optional.ofNullable(boxes.get(t)));
		assertEquals(List.of(boxes.get("t1"), boxes.get("t2")), off, "only the building part's targets; one Architect no longer knows left out");
		assertTrue(TerrainBuild.keepOff(new RegionBuild(true, true, 100, Map.of(), List.of(part("town", "building", 0, 0, "t1")), 16),
			t -> Optional.ofNullable(boxes.get(t))).isEmpty(), "a built region keeps nothing off");
		assertTrue(TerrainBuild.keepOff(null, t -> Optional.empty()).isEmpty());
		// held: inside, and a block round its sides, two below
		assertSame(boxes.get("t1"), TerrainBuild.holding(off, 5, 65, 5));
		assertSame(boxes.get("t1"), TerrainBuild.holding(off, -1, 58, 16));
		assertNull(TerrainBuild.holding(off, -2, 65, 5));
		assertNull(TerrainBuild.holding(off, 5, 72, 5), "above it");
		assertSame(boxes.get("t2"), TerrainBuild.holding(off, 20, 65, 5));
	}

	@Test
	void clearOfTilesSideBySide() {
		var t1 = new BoundingBox(0, 60, 0, 15, 70, 15);
		var t2 = new BoundingBox(16, 60, 0, 31, 70, 15);
		var off = List.of(t1, t2);
		// from t1's east side the nearest edge is into t2: taken outside both together
		BlockPos at = TerrainBuild.clearOf(off, t1, new BlockPos(14, 65, 8));
		assertNull(TerrainBuild.holding(off, at.getX(), at.getY(), at.getZ()), "clear of both: " + at);
		// from the west side: just outside t1
		assertEquals(new BlockPos(-2, 65, 8), TerrainBuild.clearOf(off, t1, new BlockPos(1, 65, 8)));
		// a target outside every box stays where it is
		assertEquals(new BlockPos(-2, 65, 8), TerrainBuild.clearOf(off, t1, new BlockPos(-2, 65, 8)));
	}
}
