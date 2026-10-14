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
}
