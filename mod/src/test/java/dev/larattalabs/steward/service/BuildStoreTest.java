package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.gateway.ProgramPlanner;
import dev.larattalabs.steward.layout.Grid;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.pipeline.Pipeline;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildStoreTest {
	private static ConceptCard card() {
		var in = BuildStoreTest.class.getClassLoader().getResourceAsStream("crater_works.json");
		return ConceptCard.parse(JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
	}

	private static BuildStore.Saved saved() {
		ConceptCard c = card();
		Claim claim = new Claim("minecraft:overworld", 0, 0, 64, -64, 320);
		Settlement s = Settlement.found("set_4", c, claim, Permission.PROPOSALS, Difficulty.PATRON, 1L);
		int n = 129;
		Grid flat = new Grid(-64, -64, n, n, new int[n * n], new boolean[n * n]);
		VillageLayout.Plan plan = VillageLayout.plan(claim, flat, ProgramPlanner.lots(c, 6, 2).specs(), VillageLayout.Rules.defaults());
		Pipeline.State st = Pipeline.step(Pipeline.State.start("set_4", c, 35), new Pipeline.CardApproved(c), Permission.PROPOSALS).next();
		st = Pipeline.step(st, new Pipeline.BibleDone(true, "bib_1", 2, 1.4, null), Permission.PROPOSALS).next();
		st = Pipeline.step(st, new Pipeline.BibleApproved(), Permission.PROPOSALS).next();
		st = Pipeline.step(st, new Pipeline.GroupUpdate("grp_1", "awaiting_approval", Map.of("slag_foundry_1", "approval"), List.of("slag_foundry_1"), 3.1, null),
			Permission.PROPOSALS).next();
		return new BuildStore.Saved("set_4", "b1700000000000", UUID.fromString("00000000-0000-0000-0000-000000000042"), "minecraft:overworld", Permission.PROPOSALS, 0, s, st, plan, null, "grp_1",
			null, null, List.of("overseers_keep_1", "slag_foundry_1"), List.of("overseers_keep_1"), List.of("slag_foundry_1"));
	}

	@Test
	void aBuildRoundTripsThroughItsFileWithStateLayoutAndDecisions(@TempDir Path dir) throws Exception {
		BuildStore st = new BuildStore();
		BuildStore.Saved b = saved();
		assertEquals(Pipeline.Phase.AWAITING_MASSING_APPROVAL, b.state().phase());
		assertEquals(6, b.plan().lots().size());
		st.put(b);
		Path f = dir.resolve("w/steward-builds.json");
		st.save(f);
		BuildStore back = BuildStore.load(f);
		assertEquals(1, back.all().size());
		BuildStore.Saved r = back.all().get(0);
		assertEquals(b, r, "every field survives, including the card's program in the state and the lots' roles");
		assertEquals(Pipeline.Decision.MASSINGS, Pipeline.awaiting(r.state()));
		assertTrue(r.plan().lots().stream().anyMatch(l -> l.landmark() && l.role().equals("slag foundry")));
	}

	@Test
	void aDistrictBuildKeepsItsCardsLotsAsAskedOfArchitect(@TempDir Path dir) throws Exception {
		BuildStore.Saved b0 = saved();
		// a lot 11 wide and 9 deep, which Architect may lay facing east (x span 9, z span 11): saved as asked, never read back from where it lies
		var specs = List.of(new VillageLayout.LotSpec("smithy_1", "smithy", 11, 9, "smithy", null, false, null));
		var b = new BuildStore.Saved(b0.settlementId(), b0.buildId(), b0.playerId(), b0.dimension(), b0.permission(), b0.landmarks(), b0.settlement(), b0.state(), b0.plan(),
			b0.bibleJobId(), b0.groupId(), b0.batchId(), b0.siteGroupId(), b0.landmarkIds(), b0.decided(), b0.lastAwaiting(), true, "district_e", false, true, "plan_1", null,
			specs, Map.of("gate_1", 1));
		BuildStore st = new BuildStore();
		st.put(b);
		Path f = dir.resolve("b.json");
		st.save(f);
		assertEquals(specs, BuildStore.load(f).all().get(0).districtSpecs());
		assertEquals(Map.of("gate_1", 1), BuildStore.load(f).all().get(0).wallRepairs(), "a wall lot's repairs are kept across a restart");
		// a build saved before (no districtSpecs) reads with none
		assertNull(saved().districtSpecs());
		assertNull(saved().wallRepairs());
		assertNull(saved().wallRepair());
		// a repair being designed is kept with the build: its key (found again by it), its design, its reservation, a cancel waiting for it
		var repair = new BuildStore.WallRepair("gate_1", "steward.b1.repair.gate_1.1", 1, "d42", 2.0, false).stopped();
		var withRepair = new BuildStore.Saved(b.settlementId(), b.buildId(), b.playerId(), b.dimension(), b.permission(), b.landmarks(), b.settlement(), b.state(), b.plan(),
			b.bibleJobId(), b.groupId(), b.batchId(), b.siteGroupId(), b.landmarkIds(), b.decided(), b.lastAwaiting(), true, "district_e", false, true, "plan_1", null,
			specs, Map.of("gate_1", 1), repair);
		BuildStore st2 = new BuildStore();
		st2.put(withRepair);
		Path f2 = dir.resolve("b2.json");
		st2.save(f2);
		assertEquals(repair, BuildStore.load(f2).all().get(0).wallRepair());
		assertNull(new BuildStore.WallRepair("gate_1", "k", 1, null, 2.0, false).designId(), "asked, not yet answered: found by its key");
	}

	@Test
	void aFinishedBuildIsDroppedAndAMissingFileIsEmpty(@TempDir Path dir) throws Exception {
		BuildStore st = new BuildStore();
		st.put(saved());
		st.remove("set_4");
		assertTrue(st.all().isEmpty());
		assertTrue(BuildStore.load(dir.resolve("none.json")).all().isEmpty());
	}

	@Test
	void aCorruptOrIncompleteFileIsAnErrorNeverEmptied(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("b.json");
		Files.writeString(f, "{ not json");
		assertThrows(RuntimeException.class, () -> BuildStore.load(f));
		assertThrows(JsonParseException.class, () -> BuildStore.fromJson("{\"format\":1,\"builds\":[{\"settlementId\":\"set_1\"}]}"));
		assertThrows(JsonParseException.class, () -> BuildStore.fromJson("{\"format\":9,\"builds\":[]}"));
	}

	@Test
	void anEntryWithoutItsPlayerDimensionOrPermissionIsRefusedAndOldFieldsTakeDefaults() {
		BuildStore st = new BuildStore();
		st.put(saved());
		for (String field : List.of("playerId", "dimension", "permission")) {
			var j = JsonParser.parseString(st.toJson()).getAsJsonObject();
			j.getAsJsonArray("builds").get(0).getAsJsonObject().remove(field);
			assertThrows(JsonParseException.class, () -> BuildStore.fromJson(j.toString()), field);
		}
		// a card without its lists and lots without roles (older saves) read with their defaults
		var j = JsonParser.parseString(st.toJson()).getAsJsonObject();
		var b = j.getAsJsonArray("builds").get(0).getAsJsonObject();
		b.getAsJsonObject("settlement").getAsJsonObject("card").remove("avoid");
		b.getAsJsonObject("plan").getAsJsonArray("lots").get(0).getAsJsonObject().remove("role");
		BuildStore.Saved r = BuildStore.fromJson(j.toString()).all().get(0);
		assertEquals(List.of(), r.settlement().card().avoid());
		assertEquals(r.plan().lots().get(0).type(), r.plan().lots().get(0).role());
	}
}
