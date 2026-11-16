package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.Settlement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** (slice PD) A district the town's approved plan names and its founding didn't build is proposed once, from the plan's own lots. */
class PlanDistrictsTest {
	private static JsonObject plan(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	/** A town zone of lots, and a districts zone: the desert side built, the jungle side not, a waiting one, a module lot, a bad id. */
	private static final String PLAN = """
		{"schemaVersion":"plan/0","zones":[
		 {"id":"town","required":true,"program":{"lots":[{"id":"cottage_1","type":"cottage","size":[13,14]}]}},
		 {"id":"quarters","program":{"districts":[
		   {"id":"desert_side","label":"the desert side","lots":[{"id":"trader_1","type":"trader","size":[13,14]}]},
		   {"id":"jungle_side","label":"the jungle side","prefer":["wooded","near_water"],"lots":[
		     {"id":"herbalist_1","type":"herbalist","role":"herbalist","label":"a herbalist","size":[13,14],"brief":"Drying racks under the eaves."},
		     {"id":"herbalist_2","type":"herbalist","role":"herbalist","label":"a herbalist","size":[13,14]},
		     {"id":"well_1","type":"well","label":"well house","size":[7,7]},
		     {"id":"farm_1","type":"crop_farm","size":[9,9],"ext":{"steward_mc:module":"crop_farm"}},
		     {"id":"Bad Id","type":"shed","size":[5,5]}]},
		   {"id":"harbour","label":"Old harbour","lots":[{"id":"dock_1","type":"dock","size":[9,9]}]},
		   {"id":"mine_camp","label":"the mine camp","lots":[{"id":"mine_1","type":"mine","size":[9,9]}]}]}}]}""";

	@Test
	void aDistrictWithNoLotInTheRegionIsUnbuilt() {
		var u = PlanDistricts.unbuilt(plan(PLAN), Set.of("cottage_1", "trader_1"), Set.of("mine_camp"), Secrets.NONE);
		assertEquals(List.of("jungle_side", "harbour"), u.stream().map(PlanDistricts.Unbuilt::id).toList(),
			"the desert side stands (a lot of it is in the region), the mine camp waits");
		var jungle = u.get(0);
		assertEquals("the jungle side", jungle.label());
		assertEquals(List.of("herbalist_1", "herbalist_2", "well_1"), jungle.lots().stream().map(l -> l.get("id").getAsString()).toList(),
			"a module lot and an id Architect won't take are left out");
		assertEquals(List.of("wooded", "near_water"), jungle.prefer());
		assertEquals("plan_district_jungle_side", jungle.key());
	}

	@Test
	void aLotInTheRegionInAnyStateHoldsItsDistrict() {
		// one lot of the district grown, removed or built since (any state the region lists): the district is not unbuilt
		var u = PlanDistricts.unbuilt(plan(PLAN), Set.of("cottage_1", "trader_1", "well_1"), Set.of(), Secrets.NONE);
		assertEquals(List.of("harbour", "mine_camp"), u.stream().map(PlanDistricts.Unbuilt::id).toList());
	}

	@Test
	void aPlanWithoutDistrictsHasNone() {
		assertTrue(PlanDistricts.unbuilt(plan("{\"zones\":[{\"id\":\"town\",\"program\":{\"lots\":[{\"id\":\"a_1\",\"type\":\"house\"}]}}]}"), Set.of(), Set.of(), Secrets.NONE).isEmpty());
		assertTrue(PlanDistricts.unbuilt(plan("{}"), Set.of(), Set.of(), Secrets.NONE).isEmpty());
	}

	@Test
	void neverOfferedTwice() {
		var u = PlanDistricts.unbuilt(plan(PLAN), Set.of("trader_1"), Set.of(), Secrets.NONE);
		var none = new Settlement.Proposals(List.of(), List.of(), List.of(), 0L);
		assertEquals(3, PlanDistricts.toOffer(u, none, List.of()).size());
		var jungle = u.get(0);
		Proposal open = PlanDistricts.proposal(jungle, "E", "", 40, false, 1L);
		// open, declined or accepted: never offered again
		assertEquals(List.of("harbour", "mine_camp"), ids(PlanDistricts.toOffer(u, new Settlement.Proposals(List.of(open), List.of(), List.of(), 0L), List.of())));
		assertEquals(List.of("harbour", "mine_camp"), ids(PlanDistricts.toOffer(u, new Settlement.Proposals(List.of(), List.of(jungle.key()), List.of(), 0L), List.of())));
		assertEquals(List.of("harbour", "mine_camp"), ids(PlanDistricts.toOffer(u, new Settlement.Proposals(List.of(), List.of(), List.of(jungle.key()), 0L), List.of())));
		// a district of its name stands (built, or a square the player named so): held
		var c1 = new Claim("minecraft:overworld", 100, 0, 48, -64, 320);
		var d = new Settlement.District("dst_1", "The Jungle Side", "E", c1, 1L, List.of(), false,
			Settlement.District.BESIDE, Settlement.District.STANDING, "rg_1", "rg_9", dev.larattalabs.steward.model.Land.of(c1), null, "steward.s.dst_1");
		assertEquals(List.of("harbour", "mine_camp"), ids(PlanDistricts.toOffer(u, none, List.of(d))));
		assertEquals(List.of("harbour", "mine_camp"), ids(PlanDistricts.toOffer(u, new Settlement.Proposals(List.of(), List.of(), List.of(jungle.key()), 0L), List.of(d))));
		// only reserved, nothing built (its build ended unbuilt after a paid request, so the acceptance wasn't given back): offered again, on that reservation
		var r = new Settlement.District("dst_1", "The Jungle Side", "E", c1, 1L, List.of(), false,
			Settlement.District.BESIDE, Settlement.District.RESERVED, "rg_1", null, dev.larattalabs.steward.model.Land.of(c1), null, "steward.s.dst_1");
		assertEquals(List.of("jungle_side", "harbour", "mine_camp"), ids(PlanDistricts.toOffer(u, new Settlement.Proposals(List.of(), List.of(), List.of(jungle.key()), 0L), List.of(r))));
		assertEquals(List.of("harbour", "mine_camp"), ids(PlanDistricts.toOffer(u, new Settlement.Proposals(List.of(), List.of(jungle.key()), List.of(), 0L), List.of(r))),
			"declined: never again");
	}

	@Test
	void theReservationAnEarlierAcceptanceLeftIsFound() {
		var c1 = new Claim("minecraft:overworld", 100, 0, 48, -64, 320);
		var reserved = new Settlement.District("dst_1", "the jungle side", "E", c1, 1L, List.of(), false, Settlement.District.BESIDE, Settlement.District.RESERVED,
			"rg_1", null, dev.larattalabs.steward.model.Land.of(c1), null, "steward.s.dst_1");
		var c2 = new Claim("minecraft:overworld", -100, 0, 48, -64, 320);
		var standing = new Settlement.District("dst_2", "the desert side", "W", c2, 1L, List.of(), false,
			Settlement.District.BESIDE, Settlement.District.STANDING, "rg_1", "rg_2", dev.larattalabs.steward.model.Land.of(c2), null, "steward.s.dst_2");
		var s = Settlement.founded("s", "S", new Claim("minecraft:overworld", 0, 0, 40, -64, 320), dev.larattalabs.steward.model.Permission.PROPOSALS,
			dev.larattalabs.steward.model.Difficulty.SUPPLIED, 1L);
		s = s.withLands(s.lands().next().withDistrict(reserved).next().withDistrict(standing));
		assertEquals("dst_1", PlanDistricts.reservedFor(s, "The Jungle Side").orElseThrow().id());
		assertTrue(PlanDistricts.reservedFor(s, "the desert side").isEmpty(), "a standing one is built: not taken again");
	}

	@Test
	void theProposalNamesTheDistrictAndItsLots() {
		var jungle = PlanDistricts.unbuilt(plan(PLAN), Set.of("trader_1"), Set.of(), Secrets.NONE).get(0);
		assertEquals("2 herbalists and a well house", PlanDistricts.lotWords(jungle));
		var p = PlanDistricts.proposal(jungle, "E", "by the water", 40, true, 7L);
		assertEquals("plan_district_jungle_side", p.key());
		assertEquals("The plan's jungle side (east)", p.title());
		assertTrue(p.why().contains("names the jungle side (2 herbalists and a well house), which its founding didn't build"), p.why());
		assertTrue(p.why().contains("to the east of the town, by the water"), p.why());
		assertTrue(p.why().contains("costs about $40, and from materials its bill is said before anything is placed."), p.why());
		assertTrue(p.foundsDistrict(), "a district: always the player's call");
		assertFalse(p.foundsQuarter());
		assertEquals("E", p.found());
		assertEquals(3, p.building().count(), "the estimate counts its lots");
		assertEquals(Proposals.planDistrictEstimate(3), Proposals.estimate(p), "priced as its lots' designs, not free");
		assertFalse(PlanDistricts.proposal(jungle, "W", "", 40, false, 7L).why().contains("materials"));
		var harbour = PlanDistricts.unbuilt(plan(PLAN), Set.of("trader_1"), Set.of(), Secrets.NONE).get(1);
		assertEquals("The plan's Old harbour (west)", PlanDistricts.proposal(harbour, "W", "", 20, false, 7L).title());
		assertEquals("a dock", PlanDistricts.lotWords(harbour));
	}

	@Test
	void itsSpecsAreThePlansOwnLots() {
		var jungle = PlanDistricts.unbuilt(plan(PLAN), Set.of(), Set.of(), Secrets.NONE).get(1);
		assertEquals("jungle_side", jungle.id());
		var specs = PlanDistricts.specs(jungle, Secrets.NONE);
		assertEquals(List.of("herbalist_1", "herbalist_2", "well_1"), specs.stream().map(x -> x.id()).toList(), "under the plan's ids, never renamed");
		assertEquals(13, specs.get(0).sizeX());
		assertEquals(14, specs.get(0).sizeZ());
		assertEquals("a herbalist", specs.get(0).role());
		assertEquals("Drying racks under the eaves.", specs.get(0).notes());
		assertEquals("well", specs.get(2).type());
	}

	@Test
	void aBareModuleTypeIsLeftOutAsM5ReadsIt() {
		// (batch GPT review 3) a bare crop_farm or storage_hall lot is a module ask to M5 (failing closed), never a paid design; so is an unreadable marker
		var p = plan("""
			{"zones":[{"id":"q","program":{"districts":[
			  {"id":"farm_side","label":"the farm side","lots":[
			    {"id":"farm_1","type":"crop_farm","size":[13,14]},{"id":"hall_1","type":"storage_hall","size":[13,14]},
			    {"id":"barn_1","type":"barn","size":[13,14],"ext":{"steward_mc:module":"crop_farm"}},
			    {"id":"house_1","type":"house","size":[13,14]}]},
			  {"id":"fields","label":"the fields","lots":[{"id":"farm_2","type":"crop_farm","size":[13,14]}]}]}}]}""");
		var u = PlanDistricts.unbuilt(p, Set.of(), Set.of(), Secrets.NONE);
		assertEquals(List.of("farm_side"), ids(u), "a district of module lots alone has nothing to design");
		assertEquals(List.of("house_1"), u.get(0).lots().stream().map(l -> l.get("id").getAsString()).toList());
	}

	@Test
	void aReofferUsesItsReservationsSide() {
		// (batch GPT review 1) offered again on its reservation: that reservation's direction, never a side chosen again
		var jungle = PlanDistricts.unbuilt(plan(PLAN), Set.of("trader_1"), Set.of(), Secrets.NONE).get(0);
		var c1 = new Claim("minecraft:overworld", -100, 0, 48, -64, 320);
		var west = new Settlement.District("dst_1", "the jungle side", "W", c1, 1L, List.of(), false, Settlement.District.BESIDE, Settlement.District.RESERVED,
			"rg_1", null, dev.larattalabs.steward.model.Land.of(c1), null, "steward.s.dst_1");
		assertEquals("W", PlanDistricts.resumeWay(jungle, List.of(west)).orElseThrow());
		var built = new Settlement.District("dst_1", "the jungle side", "W", c1, 1L, List.of(), false, Settlement.District.BESIDE, Settlement.District.STANDING,
			"rg_1", "rg_2", dev.larattalabs.steward.model.Land.of(c1), null, "steward.s.dst_1");
		assertTrue(PlanDistricts.resumeWay(jungle, List.of(built)).isEmpty(), "a first offer chooses its side");
		assertTrue(PlanDistricts.resumeWay(jungle, List.of()).isEmpty());
	}

	@Test
	void aDistrictWithNoSideIsSkippedUntilTheTownOrPlanChanges() {
		// (batch GPT review 2) no side to go: skipped quietly while what it was read from stays the same, so the rest of the minute's pass runs
		var u = PlanDistricts.unbuilt(plan(PLAN), Set.of("trader_1"), Set.of(), Secrets.NONE);
		String f = PlanDistricts.fingerprint("rg_1", 5L, PLAN, List.of());
		var noSide = java.util.Map.of("jungle_side", f);
		assertEquals(List.of("harbour", "mine_camp"), ids(PlanDistricts.withoutNoSide(u, noSide, f)));
		assertEquals(3, PlanDistricts.withoutNoSide(u, noSide, PlanDistricts.fingerprint("rg_1", 6L, PLAN, List.of())).size(), "the plan kept again");
		assertEquals(3, PlanDistricts.withoutNoSide(u, noSide, PlanDistricts.fingerprint("rg_1", 5L, PLAN + " ", List.of())).size(), "the plan changed");
		assertEquals(3, PlanDistricts.withoutNoSide(u, noSide, PlanDistricts.fingerprint("rg_3", 5L, PLAN, List.of())).size(), "another town region");
		var c1 = new Claim("minecraft:overworld", 100, 0, 48, -64, 320);
		var d = new Settlement.District("dst_1", "East", "E", c1, 1L, List.of(), false, Settlement.District.BESIDE, Settlement.District.RESERVED,
			"rg_1", null, dev.larattalabs.steward.model.Land.of(c1), null, "steward.s.dst_1");
		assertEquals(3, PlanDistricts.withoutNoSide(u, noSide, PlanDistricts.fingerprint("rg_1", 5L, PLAN, List.of(d))).size(), "a district founded or given up");
	}

	@Test
	void groundNorthOrSouthIsSaid() {
		// (PD-2) beside the town a district goes only east or west (Q3): ground it asks for that lies north or south is said, never hidden
		var jungle = PlanDistricts.unbuilt(plan(PLAN), Set.of("trader_1"), Set.of(), Secrets.NONE).get(0);
		String note = PlanDistricts.betterSideNote("", "N", "by the water (12% of it)");
		assertEquals("The ground it asks for (by the water) is to the north, but a district beside the town goes only east or west for now.", note);
		var p = PlanDistricts.proposal(jungle, "E", "", note, 40, false, 7L);
		assertTrue(p.why().contains("It would go to the east of the town, with a street of its own joined to the town's. The ground it asks for (by the water) is to the north"), p.why());
		assertTrue(p.why().contains("for now. Founding it costs nothing"), p.why());
		assertEquals(PlanDistricts.proposal(jungle, "E", "", 40, false, 7L).why(), PlanDistricts.proposal(jungle, "E", "", null, 40, false, 7L).why(), "no note: as before");
		assertNull(PlanDistricts.betterSideNote("in the woods (30% trees)", "S", "by the water (12% of it)"), "the side offered suits it: nothing to say");
		assertNull(PlanDistricts.betterSideNote("", "S", ""), "no ground found north or south");
		assertNull(PlanDistricts.betterSideNote("", null, ""), "neither north nor south fits");
		assertNull(PlanDistricts.betterSideNote("", "E", "by the water"), "only north or south is said");
		assertEquals("The ground it asks for (on low ground) is to the south, but a district beside the town goes only east or west for now.",
			PlanDistricts.betterSideNote(null, "S", "on low ground (6 below the town)"));
	}

	@Test
	void noSideIsSaidOnce() {
		var jungle = PlanDistricts.unbuilt(plan(PLAN), Set.of("trader_1"), Set.of(), Secrets.NONE).get(0);
		String w = PlanDistricts.noSideWords(jungle);
		assertTrue(w.startsWith("The town's plan names the jungle side (2 herbalists and a well house), which its founding didn't build, but there is no room"), w);
		assertTrue(w.contains("east or west") && w.contains("doesn't go north or south yet") && w.contains("I haven't proposed it"), w);
	}

	@Test
	void keys() {
		assertTrue(PlanDistricts.isKey("plan_district_jungle_side"));
		assertEquals("jungle_side", PlanDistricts.idOf("plan_district_jungle_side"));
		assertFalse(PlanDistricts.isKey("district_E"));
		assertFalse(PlanDistricts.isKey("plan_district_"));
		assertTrue(PlanDistricts.siteWords(PlanDistricts.unbuilt(plan(PLAN), Set.of("trader_1"), Set.of(), Secrets.NONE).get(0)).contains("by the water"));
	}

	private static List<String> ids(List<PlanDistricts.Unbuilt> u) {
		return u.stream().map(PlanDistricts.Unbuilt::id).toList();
	}
}
