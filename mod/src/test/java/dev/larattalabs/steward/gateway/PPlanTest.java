package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.layout.VillageLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PPlanTest {
	private static JsonObject walledTown() throws Exception {
		return JsonParser.parseString(Files.readString(Path.of("../fixtures/plans/walled_town.json"))).getAsJsonObject().getAsJsonObject("answer").getAsJsonObject("plan");
	}

	@Test
	void theBuiltPlanDropsWhatWaitsAndTagsEveryLot() throws Exception {
		JsonObject plan = walledTown();
		JsonObject built = PPlan.forBuild(plan, Set.of("town_wall"), "set_7", "b1");
		var wall = built.getAsJsonArray("connectors").get(0).getAsJsonObject();
		assertEquals("town_wall", wall.get("id").getAsString());
		assertFalse(wall.get("required").getAsBoolean(), "the wall waits: built without it");
		assertTrue(built.getAsJsonArray("zones").get(0).getAsJsonObject().get("required").getAsBoolean(), "the town stays required");
		var lots = PPlan.lots(built);
		assertEquals(3, lots.size());
		for (var l : lots) {
			var ext = l.getAsJsonObject("ext");
			assertEquals("set_7", ext.get("steward_mc:settlement").getAsString());
			assertEquals(l.get("id").getAsString(), ext.get("steward_mc:lot").getAsString());
			assertEquals("b1", ext.get(BatchPlanner.BUILD_EXT).getAsString());
			assertFalse(ext.get("steward_mc:role").getAsString().isBlank());
		}
		// the town's street runs east-west and the zone is not turned (C37): Steward's designs know an east-west street
		var town = built.getAsJsonArray("zones").get(0).getAsJsonObject();
		assertEquals("ew", town.getAsJsonObject("program").getAsJsonObject("params").get("orientation").getAsString());
		assertFalse(town.getAsJsonObject("tolerance").getAsJsonObject("rotate").get("allowed").getAsBoolean());
		// the kept plan is not changed, and the same inputs make the same plan (a re-check checks the same thing)
		assertTrue(plan.getAsJsonArray("connectors").get(0).getAsJsonObject().get("required").getAsBoolean());
		assertEquals(built, PPlan.forBuild(plan, Set.of("town_wall"), "set_7", "b1"));
	}

	@Test
	void theArtifactsLotsBecomeTheBuildsPlan() throws Exception {
		JsonObject built = PPlan.forBuild(walledTown(), Set.of("town_wall"), "set_7", "b1");
		var ids = PPlan.lots(built).stream().map(l -> l.get("id").getAsString()).toList();
		JsonArray art = new JsonArray();
		art.add(lot(ids.get(0), new int[] {10, 64, 20, 22, 80, 31}, "south", new int[] {16, 64, 32}));
		art.add(lot(ids.get(1), new int[] {30, 65, 20, 40, 80, 29}, "north", null));
		VillageLayout.Plan p = PPlan.toPlan(art, built);
		assertEquals(2, p.lots().size());
		var a = p.lots().get(0);
		assertEquals(ids.get(0), a.id());
		assertEquals(10, a.x());
		assertEquals(20, a.z());
		assertEquals(13, a.sizeX());
		assertEquals(12, a.sizeZ());
		assertEquals(VillageLayout.Front.SOUTH, a.front());
		assertEquals(64, a.groundY());
		assertEquals(VillageLayout.Front.NORTH, p.lots().get(1).front());
		assertEquals(1, p.unplaced().size(), "the lot the artifact has not");
		assertEquals(ids.get(2), p.unplaced().get(0).id());
		assertEquals(10, p.streetX0());
		assertEquals(40, p.streetX1());
		assertFalse(PPlan.northSouth(art));
		art.add(lot("x", new int[] {0, 64, 0, 8, 70, 8}, "east", null));
		assertTrue(PPlan.northSouth(art), "a lot facing east: Steward's designs know an east-west street");
	}

	@Test
	void theArtifactIsKeptWhereTheVillagesPlanIs() {
		assertEquals("abc", PPlan.artifactOf(PPlan.ARTIFACT_PREFIX + "abc"));
		assertNull(PPlan.artifactOf("vplan_12"));
		assertNull(PPlan.artifactOf(null));
	}

	@Test
	void aDesignPastItsApprovedLotIsCaughtBeforeTheRealise() {
		JsonObject lot = lot("cottage_1", new int[] {0, 64, 0, 12, 95, 11}, "south", null);
		lot.addProperty("provisional", true);
		JsonArray range = new JsonArray();
		range.add(13 * 12 * 4);
		range.add(13 * 12 * 16);
		lot.add("costRange", range);
		assertNull(PPlan.lotProblem(lot, 11, 12, 12, 900), "fits");
		assertNull(PPlan.lotProblem(lot, 12, 20, 13, 900), "fits only turned (13 deep in a 13 x 12 lot)");
		assertNotNull(PPlan.lotProblem(lot, 14, 20, 13, 900), "fits neither way");
		assertNull(PPlan.lotProblem(lot, 11, 12, 12, -1), "the cost not known: the box alone");
		assertNotNull(PPlan.lotProblem(lot, 11, 33, 12, 900), "taller than its box (32)");
		assertNotNull(PPlan.lotProblem(lot, 14, 10, 14, 900), "wider than its box either way");
		assertNotNull(PPlan.lotProblem(lot, 11, 12, 12, 13 * 12 * 16 + 1), "past the top of its cost range");
		lot.addProperty("provisional", false);
		assertNull(PPlan.lotProblem(lot, 40, 40, 40, 1_000_000), "a lot that named its entry takes any");
	}

	@Test
	void theRetryIsKeptWithTheArtifact() {
		assertFalse(PPlan.retried(PPlan.ARTIFACT_PREFIX + "abc"));
		assertTrue(PPlan.retried(PPlan.RETRIED_PREFIX + "abc"));
		assertEquals("abc", PPlan.artifactOf(PPlan.RETRIED_PREFIX + "abc"));
	}

	@Test
	void aLotsDesignIsCappedAtItsApprovedHeight() throws Exception {
		JsonObject built = PPlan.forBuild(walledTown(), Set.of("town_wall"), "set_7", "b1");
		JsonArray art = new JsonArray();
		JsonObject a = lot(PPlan.lots(built).get(0).get("id").getAsString(), new int[] {10, 64, 20, 22, 95, 31}, "south", null);
		JsonArray fit = new JsonArray();
		for (int v : new int[] {10, 64, 20, 22, 79, 31}) fit.add(v);
		a.add("fitBox", fit);
		art.add(a);
		assertEquals(16, PPlan.toPlan(art, built).lots().get(0).maxHeight(), "the fit box, not the box");
	}

	private static JsonObject lot(String id, int[] box, String front, int[] entrance) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		JsonArray b = new JsonArray();
		for (int v : box) b.add(v);
		o.add("box", b);
		o.addProperty("front", front);
		if (entrance != null) {
			JsonArray e = new JsonArray();
			for (int v : entrance) e.add(v);
			o.add("entrance", e);
		}
		return o;
	}

	@Test
	void aDistrictsPlansLotsAreEveryDistricts() throws Exception {
		JsonObject plan = JsonParser.parseString(Files.readString(Path.of("../fixtures/plans/two_sides.json"))).getAsJsonObject().getAsJsonObject("answer")
			.getAsJsonObject("plan");
		var ids = PPlan.lots(PPlan.forBuild(plan, Set.of(), "set_9", "b2")).stream().map(l -> l.get("id").getAsString()).toList();
		assertEquals(java.util.List.of("trader_1", "trader_2", "herbalist_1", "herbalist_2"), ids);
		for (var l : PPlan.lots(PPlan.forBuild(plan, Set.of(), "set_9", "b2"))) assertEquals("b2", l.getAsJsonObject("ext").get(BatchPlanner.BUILD_EXT).getAsString());
	}

	@Test
	void anIrrigatedLotsReservedCellsAreItsRunsInItsFrame() {
		var lots = JsonParser.parseString("[{\"id\":\"farm_1\",\"front\":\"east\",\"irrigation\":{\"frame\":[19,20],\"lotCells\":[4,5,0,1,9,5,0,0]}},"
			+ "{\"id\":\"cottage_1\",\"front\":\"south\"}]").getAsJsonArray();
		var r = PPlan.reserved(lots);
		assertEquals(java.util.Set.of("farm_1"), r.keySet());
		var f = r.get("farm_1");
		assertEquals("east", f.front());
		assertEquals(19, f.width());
		assertEquals(20, f.depth());
		assertEquals(java.util.List.of(new net.minecraft.core.BlockPos(4, 0, 5), new net.minecraft.core.BlockPos(4, 1, 5), new net.minecraft.core.BlockPos(9, 0, 5)), f.cells());
	}

	@Test
	void aLotOnAWallIsATowerOrGatehouseFillingItsFitBoxWithItsPassageReserved() throws Exception {
		JsonObject built = PPlan.forBuild(walledTown(), Set.of("town_wall"), "set_7", "b1");
		String id = PPlan.lots(built).get(0).get("id").getAsString();
		JsonArray art = new JsonArray();
		// (Architect K3) a gatehouse on the wall: its box past its fit box, a 5 x 3 frame under 7, its passage's cells in its frame
		JsonObject a = lot(id, new int[] {10, 64, 20, 18, 80, 26}, "south", null);
		JsonArray fit = new JsonArray();
		for (int v : new int[] {10, 64, 20, 14, 72, 22}) fit.add(v);
		a.add("fitBox", fit);
		a.add("onWall", JsonParser.parseString("{\"wall\":\"town_wall\",\"at\":\"gate\",\"frame\":[5,3],"
			+ "\"passage\":{\"width\":1,\"height\":3,\"lotCells\":[2,0,0,2,2,1,0,2,2,2,0,2]}}"));
		art.add(a);
		var l = PPlan.toPlan(art, built).lots().get(0);
		assertTrue(l.exact());
		assertEquals("gatehouse", l.type());
		assertEquals(5, l.sizeX(), "the fit box's footprint, not the box's");
		assertEquals(3, l.sizeZ());
		assertEquals(9, l.maxHeight());
		assertTrue(l.notes().endsWith(PPlan.WALL_BRIEF));
		var r = PPlan.reserved(art).get(id);
		assertEquals(5, r.width());
		assertEquals(3, r.depth());
		assertEquals(9, r.cells().size(), "three columns of the passage, three high");
		// a lot off the wall is not exact
		JsonArray plain = new JsonArray();
		plain.add(lot(id, new int[] {10, 64, 20, 14, 72, 22}, "south", null));
		assertFalse(PPlan.toPlan(plain, built).lots().get(0).exact());
	}

	@Test
	void aGatehousesMechanismBriefLinesReachItsDesignerWordForWordAndItsEnvelopeIsReservedAsItIs() throws Exception {
		JsonObject built = PPlan.forBuild(walledTown(), Set.of("town_wall"), "set_7", "b1");
		String id = PPlan.lots(built).get(0).get("id").getAsString();
		// (Architect GM-a) the gate's envelope merged into passage.lotCells, its five brief lines on passage.mechanism.brief
		String[] lines = {"keep 4 clear cells above the passage lintel for the winch room", "give a sky-exposed roof or crenel cell for the daylight sensor",
			"keep 5 cells of jamb a side of the passage clear of ladders and stairs",
			"leave the reserved staircase from the gate up to the roof empty or solid, with no redstone, light source or opening on it",
			"keep the roof over the gate's logic block (the 13 columns centred on the passage, from 1 to 9 cells in from the front face) at or below row 18 above "
				+ "the passage floor so the gate can sense the sky; towers elsewhere on the lot may rise higher"};
		JsonObject a = lot(id, new int[] {10, 64, 20, 26, 103, 30}, "south", null);
		JsonArray brief = new JsonArray();
		for (String l : lines) brief.add(l);
		JsonObject mech = new JsonObject();
		mech.addProperty("kind", "portcullis");
		mech.add("brief", brief);
		JsonObject wall = JsonParser.parseString("{\"wall\":\"town_wall\",\"at\":\"gate\",\"frame\":[17,11],"
			+ "\"passage\":{\"width\":3,\"height\":3,\"lotCells\":[7,0,0,2,8,0,0,2,9,0,0,2,4,1,4,8,4,2,5,8,12,1,4,8]}}").getAsJsonObject();
		wall.getAsJsonObject("passage").add("mechanism", mech);
		a.add("onWall", wall);
		JsonArray art = new JsonArray();
		art.add(a);
		var l = PPlan.toPlan(art, built).lots().get(0);
		for (String line : lines) assertTrue(l.notes().contains(line + "."), line);
		// a backstory past the notes cap never clips them: the lot's brief comes before the settlement's prose
		var in = PPlanTest.class.getClassLoader().getResourceAsStream("crater_works.json");
		var c = dev.larattalabs.steward.model.ConceptCard.parse(JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
		var card = new dev.larattalabs.steward.model.ConceptCard(c.name(), c.site(), c.style(), c.purpose(), new dev.larattalabs.steward.model.ConceptCard.Story("x ".repeat(1500)),
			c.constraints(), c.avoid(), c.interpretation(), c.contradictions(), c.assumptions(), c.program());
		var big = dev.larattalabs.steward.model.Settlement.found("set_7", card, new dev.larattalabs.steward.model.Claim("minecraft:overworld", 0, 0, 128, -64, 320),
			dev.larattalabs.steward.model.Permission.PROPOSALS, dev.larattalabs.steward.model.Difficulty.PATRON, 1L);
		String notes = LotBrief.build(big, l, l.maxHeight(), null, null).notes();
		assertTrue(notes.length() <= LotBrief.MAX_NOTES);
		for (String line : lines) assertTrue(notes.contains(line + "."), line);
		// the merged envelope is forwarded as the artifact has it: the lanes and the jamb and rung runs
		assertEquals(3 * 3 + 5 + 4 + 5, PPlan.reserved(art).get(id).cells().size());
		// a gate with no mechanism gets no mechanism lines
		wall.getAsJsonObject("passage").remove("mechanism");
		assertNull(PPlan.mechanismWords(wall));
	}

	@Test
	void aWallLotsOuterFacesGoToItsDesignerInWordsAndInItsExt() {
		// a corner tower 5 x 5: its whole back outside, and the middle of its front (the corner's mirror)
		var lots = JsonParser.parseString("[{\"id\":\"tower_1\",\"front\":\"south\",\"onWall\":{\"wall\":\"w\",\"at\":\"corner\",\"frame\":[5,5],"
			+ "\"outerFaces\":{\"cells\":[],\"lotCells\":[0,0,0,2,1,0,0,2,2,0,0,2,3,0,0,2,4,0,0,2,2,4,0,2]}}},{\"id\":\"cottage_1\",\"front\":\"south\"}]").getAsJsonArray();
		var words = PPlan.outerFacesWords(lots.get(0).getAsJsonObject().getAsJsonObject("onWall"));
		assertEquals("Outside the wall: the back, the front from 3 to 3 of 5 (counted from the west), the west side from 1 to 1 of 5 (counted from the back) and "
			+ "the east side from 1 to 1 of 5 (counted from the back). Put its doors elsewhere.", words);
		var ext = PPlan.wallLots(lots);
		assertEquals(java.util.Set.of("tower_1"), ext.keySet());
		assertEquals(lots.get(0).getAsJsonObject().getAsJsonObject("onWall").getAsJsonObject("outerFaces").get("lotCells"), ext.get("tower_1").get("outerFaces"),
			"the artifact's lotCells as they are");
		// a gatehouse on the wall with no outer faces recorded still carries the ext, empty: Architect keys its exact massing on it
		var gate = JsonParser.parseString("[{\"id\":\"gatehouse_1\",\"front\":\"south\",\"onWall\":{\"wall\":\"w\",\"at\":\"run\",\"frame\":[9,7]}}]").getAsJsonArray();
		assertEquals(new com.google.gson.JsonObject(), PPlan.wallLots(gate).get("gatehouse_1"));
	}

	@Test
	void aLotTheWallPutsOnItsLineIsDesignedThoughNoProgramHasIt() throws Exception {
		JsonObject built = PPlan.forBuild(walledTown(), Set.of("town_wall"), "set_7", "b1");
		JsonArray art = new JsonArray();
		JsonObject corner = lot("town_wall_corner_1", new int[] {0, 64, 0, 8, 80, 8}, "south", null);
		corner.add("onWall", JsonParser.parseString("{\"wall\":\"town_wall\",\"at\":\"corner\",\"frame\":[9,9]}"));
		art.add(corner);
		var plan = PPlan.toPlan(art, built);
		var l = plan.lots().stream().filter(x -> x.id().equals("town_wall_corner_1")).findFirst().orElseThrow();
		assertEquals("tower", l.type());
		assertEquals("corner tower", l.role());
		assertTrue(l.exact());
	}
}
