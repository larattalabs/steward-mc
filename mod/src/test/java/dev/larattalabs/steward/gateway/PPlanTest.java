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
