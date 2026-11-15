package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** (slice PV1) An addition to a plan-v1 town takes the plan's own lot of the asked type that hasn't been built yet. */
class PlanGrowthTest {
	private static JsonObject plan(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	/** no_wood's town with a library the first build left out, a second street zone (north-south) with a smithy, and a districts zone. */
	private static final String PLAN = """
		{"schemaVersion":"plan/0","zones":[
		 {"id":"town","required":true,"program":{"template":"flat_village","params":{"lots":[
		   {"id":"cottage_1","type":"cottage","role":"cottage","label":"a stone cottage","size":[13,14]},
		   {"id":"cottage_2","type":"cottage","role":"cottage","label":"a stone cottage","size":[13,14]},
		   {"id":"library_1","type":"library","role":"library","label":"the town library","size":[11,13],"brief":"A tall hall of shelves.","placement":"central"}]}}},
		 {"id":"crafts","required":false,"program":{"layout":"street","orientation":"ns","lots":[
		   {"id":"smithy_1","type":"smithy","size":[9,11]},{"id":"mason_1","type":"mason","size":[9,9]}]}},
		 {"id":"quarters","program":{"districts":[{"id":"desert_side","lots":[{"id":"trader_1","type":"trader","size":[9,9]}]}]}},
		 {"id":"vault","required":false,"program":{"layout":"street","lots":[{"id":"bank_1","type":"bank","size":[9,9]}]}}]}""";

	@Test
	void theAskedTypeTakesThePlansUnbuiltLotUnderItsOwnIdAndZone() {
		var p = PlanGrowth.pick(plan(PLAN), Set.of(), Secrets.NONE, Set.of("cottage_1", "cottage_2"), "library", "scholars' library").orElseThrow();
		assertEquals("library_1", p.spec().id(), "the plan's own lot id, never renamed");
		assertEquals("library", p.spec().type());
		assertEquals(11, p.spec().sizeX());
		assertEquals(13, p.spec().sizeZ());
		assertEquals("the town library", p.spec().role(), "the plan's label");
		assertEquals("A tall hall of shelves.", p.spec().notes());
		assertEquals("central", p.spec().placement());
		assertEquals("town", p.zone());
		assertEquals("town", p.growZone(), "its zone stands: grown on that zone's streets");
		assertNull(p.district());
	}

	@Test
	void aLotTheRegionHoldsInAnyStateIsNeverTakenAgain() {
		assertTrue(PlanGrowth.pick(plan(PLAN), Set.of(), Secrets.NONE, Set.of("cottage_1", "library_1"), "library", null).isEmpty(),
			"built (or removed, or grown before): today's path");
	}

	@Test
	void noLotOfTheTypeIsTodaysPath() {
		assertTrue(PlanGrowth.pick(plan(PLAN), Set.of(), Secrets.NONE, Set.of("cottage_1"), "windmill", "a windmill").isEmpty());
	}

	@Test
	void theRoleOrLabelMatchesOnASecondPass() {
		var p = PlanGrowth.pick(plan(PLAN), Set.of(), Secrets.NONE, Set.of("cottage_1"), "archive", "The town library").orElseThrow();
		assertEquals("library_1", p.spec().id());
	}

	@Test
	void aZoneThatDoesntStandGrowsOnTheTownsStreetsAndANorthSouthZoneIsTakenAsAnyOther() {
		// the crafts zone (north-south, optional) was left out whole: its smithy is the plan's, grown on the town's streets
		var p = PlanGrowth.pick(plan(PLAN), Set.of(), Secrets.NONE, Set.of("cottage_1", "cottage_2"), "smithy", null).orElseThrow();
		assertEquals("smithy_1", p.spec().id());
		assertEquals("crafts", p.zone());
		assertNull(p.growZone(), "no lot of the zone stands: no zone streets to grow on");
		// the zone stands (its mason was built, north-south): the smithy grows on its streets, whatever their axis
		var q = PlanGrowth.pick(plan(PLAN), Set.of(), Secrets.NONE, Set.of("cottage_1", "mason_1"), "smithy", null).orElseThrow();
		assertEquals("crafts", q.growZone());
	}

	@Test
	void aDistrictsLotNamesItsDistrict() {
		var p = PlanGrowth.pick(plan(PLAN), Set.of(), Secrets.NONE, Set.of("cottage_1"), "trader", null).orElseThrow();
		assertEquals("quarters", p.zone());
		assertEquals("desert_side", p.district());
	}

	@Test
	void aWaitingPartASecretOrAModuleLotIsNeverTaken() {
		assertTrue(PlanGrowth.pick(plan(PLAN), Set.of("vault"), Secrets.NONE, Set.of("cottage_1"), "bank", null).isEmpty(), "its zone waits for Architect");
		assertTrue(PlanGrowth.pick(plan(PLAN), Set.of("desert_side"), Secrets.NONE, Set.of("cottage_1"), "trader", null).isEmpty(), "its district waits");
		var hidden = plan(PLAN);
		hidden.add("forms", JsonParser.parseString("[{\"id\":\"bank_1\",\"kind\":\"hidden_room\",\"params\":{\"host\":\"cottage_1\"}}]"));
		assertTrue(PlanGrowth.pick(hidden, Set.of(), Secrets.of(hidden, null), Set.of("cottage_1"), "bank", null).isEmpty(), "a secret is never named");
		var module = plan(PLAN.replace("{\"id\":\"bank_1\",\"type\":\"bank\",\"size\":[9,9]}", "{\"id\":\"bank_1\",\"type\":\"bank\",\"size\":[9,9],\"ext\":{\"steward_mc:module\":{}}}"));
		assertTrue(PlanGrowth.pick(module, Set.of(), Secrets.NONE, Set.of("cottage_1"), "bank", null).isEmpty(), "a module lot is the module catalog's");
		var badId = plan(PLAN.replace("\"library_1\"", "\"Library-1\""));
		assertTrue(PlanGrowth.pick(badId, Set.of(), Secrets.NONE, Set.of("cottage_1"), "library", null).isEmpty(), "an id Architect would refuse");
	}

	@Test
	void onlyThePlanTheTownWasBuiltFromGrowsIt() {
		var p = plan(PLAN);
		assertTrue(PlanGrowth.approvedFor(100, 200, Set.of("cottage_1", "cottage_2"), p));
		assertFalse(PlanGrowth.approvedFor(300, 200, Set.of("cottage_1"), p), "composed after the town stood (described again)");
		assertFalse(PlanGrowth.approvedFor(100, 200, Set.of("house_1", "house_2"), p), "a town realised from another plan");
		assertFalse(PlanGrowth.approvedFor(100, 200, Set.of(), p), "no first lots");
	}

	@Test
	void theGrowthsZoneSurvivesTheBuildsFileAndAFormat6BuildHasNone(@TempDir Path dir) throws Exception {
		var b0 = BuildStoreTestAccess.saved();
		var b = new BuildStore.Saved(b0.settlementId(), b0.buildId(), b0.playerId(), b0.dimension(), b0.permission(), b0.landmarks(), b0.settlement(), b0.state(),
			b0.plan(), b0.bibleJobId(), b0.groupId(), b0.batchId(), b0.siteGroupId(), b0.landmarkIds(), b0.decided(), b0.lastAwaiting(), true, null, false, true,
			b0.vPlan(), b0.vRegion(), b0.districtSpecs(), "rg7", 3, 2, "vgrow", b0.moduleBindings(), b0.moduleOrders(), "crafts");
		BuildStore st = new BuildStore();
		st.put(b);
		Path f = dir.resolve("steward-builds.json");
		st.save(f);
		assertTrue(Files.readString(f).contains("\"format\": 7"));
		var back = BuildStore.load(f).all().get(0);
		assertEquals(b, back);
		assertEquals("crafts", back.growZone());
		String v6 = st.toJson().replaceFirst("\"format\": 7", "\"format\": 6").replace("\"growZone\": \"crafts\"", "\"unused\": 0");
		var old = BuildStore.fromJson(v6);
		assertEquals(6, old.readFormat());
		assertNull(old.all().get(0).growZone(), "a format-6 growth grows on the town's streets");
		assertEquals("rg7", old.all().get(0).growRegion());
	}
}
