package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** (WORDING-2) A stage wait's subject is the item that waits, never what it waits on, and a sentence never names one thing twice. */
class StageWaitWordsTest {
	/** batch 096's walled town (fixtures/strange-asks/plans/castle_city.json, its lots only) and a wall part. */
	static final String PLAN = "{\"zones\":[{\"id\":\"castle\",\"program\":{\"lots\":[{\"id\":\"keep_1\",\"type\":\"tower\",\"role\":\"great_keep\",\"label\":\"Great keep\"},"
		+ "{\"id\":\"gatehouse_1\",\"type\":\"gatehouse\",\"role\":\"main_gatehouse\",\"label\":\"Main gatehouse\"},{\"id\":\"tower_1\",\"type\":\"tower\","
		+ "\"role\":\"corner_tower\",\"label\":\"Corner wall tower\"},{\"id\":\"shed_1\",\"type\":\"workshop\"}]}}],"
		+ "\"connectors\":[{\"id\":\"curtain_wall\",\"kind\":\"wall\",\"label\":\"Curtain wall\"},{\"id\":\"river\",\"kind\":\"river\",\"label\":\"River\"}]}";

	static final SettlementRunner.ItemWait STAGE = new SettlementRunner.ItemWait("stage", "c161", java.util.List.of(), 0, null, 0);

	private static JsonObject ext(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	@Test
	void aLotWaitingOnAPartIsNamedByItsLotNotByThePart() {
		// 11:59:46 lot:tower_1 waits for curtain_wall: Architect's typed part fell back to waitsFor.part (the blocker), so it read "The Curtain wall waits
		// for the Curtain wall to be built."
		var e = ext("{\"architect_mc:lot\":\"tower_1\",\"architect_mc:waitsFor\":{\"target\":\"c161\",\"part\":\"curtain_wall\",\"waiter\":\"lot tower_1\"}}");
		String what = SettlementRunner.waitSubject(null, e, "lot:tower_1", PLAN, "Castle Walls");
		assertEquals("Corner wall tower", what);
		assertEquals("The Corner wall tower waits for the Curtain wall to be built.", SettlementRunner.stageWait(STAGE, what, "Curtain wall"));
		// no waiter: the lot by its key
		assertEquals("Corner wall tower", SettlementRunner.waitSubject(null, ext("{\"architect_mc:waitsFor\":{\"target\":\"c161\",\"part\":\"curtain_wall\"}}"),
			"lot:tower_1", PLAN, "Castle Walls"));
		// a lot with no label or role: its type
		assertEquals("workshop", MaterialRequests.lotWords(PLAN, "shed_1"));
		assertEquals("barn 2", MaterialRequests.lotWords(PLAN, "barn_2"), "not in the plan: its id in words, never \"lot barn 2\"");
	}

	@Test
	void aGateMechanismIsNamedByItsBuildingNeverByItsKey() {
		// 12:03:55 mech:gatehouse_1 (its portcullis) waits for its lot: "The lot gatehouse 1 waits for the gatehouse 1 to be built."
		var e = ext("{\"architect_mc:lot\":\"gatehouse_1\",\"architect_mc:waitsFor\":{\"target\":\"s66\",\"part\":\"lot gatehouse_1\",\"waiter\":\"lot gatehouse_1\"}}");
		String what = SettlementRunner.waitSubject(null, e, "mech:gatehouse_1", PLAN, "Castle Walls");
		assertEquals("Main gatehouse's gate", what);
		assertEquals("The Main gatehouse's gate waits for the Main gatehouse to be built.", SettlementRunner.stageWait(STAGE, what, MaterialRequests.lotWords(PLAN,
			"gatehouse_1")));
		// a wall's own gate (no lot): its id in words, never "mech:curtain g1"
		assertEquals("curtain g1", SettlementRunner.waitSubject(null, ext("{}"), "mech:curtain_g1", PLAN, "Castle Walls"));
		assertEquals("The curtain g1 waits for materials: 2 iron door. They go in the stockpile.", MaterialRequests.waitWords(SettlementRunner.waitSubject(null,
			ext("{}"), "mech:curtain_g1", PLAN, "Castle Walls"), "2 iron door", MaterialRequests.Pile.UNREAD, ""));
	}

	@Test
	void aPartWaitingOnItselfSaysItWaitsForAnEarlierPartOfItself() {
		// the sculpt flows: "The River waits for the River to be built." (one stage of the river waits for the one before it)
		var e = ext("{\"architect_mc:part\":\"river\",\"architect_mc:waitsFor\":{\"target\":\"c25\",\"part\":\"river\"}}");
		String what = SettlementRunner.waitSubject(null, e, "tile:fluids:3", PLAN, "Castle Walls");
		assertEquals("River", what);
		assertEquals("The River waits for an earlier part of itself to be built.", SettlementRunner.stageWait(STAGE, what, "River"));
		// one rule: case, articles, a lot prefix and underscores don't make two names of one thing
		assertEquals("The curtain waits for an earlier part of itself to be built.", SettlementRunner.stageWait(STAGE, "curtain", "the Curtain"));
		assertEquals("The gatehouse 1 waits for an earlier part of itself to be built.", SettlementRunner.stageWait(STAGE, "lot gatehouse 1", "gatehouse_1"));
		assertEquals("The route join waits for an earlier part of itself to be built.", SettlementRunner.stageWait(STAGE, "route join", "Route join"));
	}

	@Test
	void aNameIsSaidWithOneArticle() {
		// batch 096: "The the village waits for the the village", "The street w waits for the the stone town", "The a stone cottage waits ..."
		assertEquals("The street w waits for the stone town to be built.", SettlementRunner.stageWait(STAGE, "street w", "the stone town"));
		assertEquals("The stone cottage waits for the street to be built.", SettlementRunner.stageWait(STAGE, "A stone cottage", "street"));
		assertEquals("The village field waits for the street to be built.", SettlementRunner.stageWait(STAGE, "the village field", "street"));
		assertEquals("The inn waits for the town's street to be built.", SettlementRunner.stageWait(STAGE, "an inn", "the town's street"));
		assertEquals("The Great keep", MaterialRequests.theName("Great keep"));
		assertEquals("The angler's hut", MaterialRequests.theName("angler's hut"), "only a whole article word is dropped");
	}

	@Test
	void aRoleOrTheItemsOwnPartIsTheSubject() {
		var e = ext("{\"steward_mc:role\":\"fishers' cottage\",\"architect_mc:waitsFor\":{\"target\":\"r2\",\"part\":\"street_e\"}}");
		assertEquals("fishers' cottage", SettlementRunner.waitSubject("fishers' cottage", e, "lot:cottage_1", PLAN, "Castle Walls"));
		// a road item names itself (its own part): a street by its side (WORDING-3: was "street w")
		assertEquals("west street", SettlementRunner.waitSubject(null, ext("{\"architect_mc:part\":\"street_w\",\"architect_mc:waitsFor\":{\"target\":\"c1\",\"part\":\"town\"}}"),
			"road:street_w", PLAN, "Castle Walls"));
		// a tile over several parts: its first
		assertEquals("Curtain wall", SettlementRunner.waitSubject(null, ext("{\"architect_mc:parts\":[\"curtain_wall\",\"river\"],\"architect_mc:waitsFor\":{\"target\":\"r9\","
			+ "\"part\":\"route_join\"}}"), "tile:links:4", PLAN, "Castle Walls"));
		// a waiter that is a part, not a lot
		assertEquals("River", SettlementRunner.waitSubject(null, ext("{\"architect_mc:waitsFor\":{\"target\":\"c3\",\"part\":\"curtain_wall\",\"waiter\":\"river\"}}"),
			"tile:fluids:1", PLAN, "Castle Walls"));
		// nothing of its own: never the blocker's part; a street by its kind, else its key in words without the kind
		assertEquals("street", SettlementRunner.waitSubject(null, ext("{\"architect_mc:waitsFor\":{\"target\":\"c1\",\"part\":\"town\"}}"), "street", PLAN, "Castle Walls"));
		assertEquals("north spur", SettlementRunner.waitSubject(null, ext("{\"architect_mc:waitsFor\":{\"target\":\"c1\",\"part\":\"town\"}}"), "road:north_spur", PLAN,
			"Castle Walls"));
	}

	/**
	 * (WORDING-3) The two quarters of fixtures/strange-asks/plans/old_new_town.json (its zone and districts, lots cut), plus a district with no label
	 * ({@code b}), one with a short id ({@code d2}), a second zone and a connector whose id ends in "street".
	 */
	static final String DPLAN = "{\"zones\":[{\"id\":\"town\",\"label\":\"Two Quarters on the River and Hill\",\"program\":{\"districts\":["
		+ "{\"id\":\"old_quarter\",\"label\":\"Old riverside quarter\",\"lots\":[{\"id\":\"tavern_1\",\"type\":\"tavern\",\"label\":\"old town hall\"}]},"
		+ "{\"id\":\"b\",\"lots\":[]},{\"id\":\"d2\",\"label\":\"the river district\",\"lots\":[]}]}},"
		+ "{\"id\":\"hill\",\"label\":\"Hill quarter\",\"program\":{\"lots\":[]}}],"
		+ "\"connectors\":[{\"id\":\"high_street\",\"kind\":\"road\",\"label\":\"High Street\"}]}";

	@Test
	void aRoadIsNamedAsAPlayerWouldNeverByItsId() {
		// a district's street, its own part the road id: was "The old quarter street w waits for the Two Quarters on the River and Hill to be built."
		var e = ext("{\"architect_mc:part\":\"old_quarter_street_w\",\"architect_mc:waitsFor\":{\"target\":\"c7\",\"part\":\"town\",\"waiter\":\"road old_quarter_street_w\"}}");
		String what = SettlementRunner.waitSubject(null, e, "road:old_quarter_street_w", DPLAN, "Two Quarters");
		assertEquals("west street of the Old riverside quarter", what);
		assertEquals("The west street of the Old riverside quarter waits for the Two Quarters on the River and Hill to be built.", SettlementRunner.stageWait(STAGE, what,
			"Two Quarters on the River and Hill"));
		// a road waiter only ("road <id>", Architect's waitsFor.waiter): was "road b street e"; a district with no label: "district b"
		assertEquals("east street of district b", SettlementRunner.waitSubject(null, ext("{\"architect_mc:waitsFor\":{\"target\":\"c7\",\"part\":\"town\","
			+ "\"waiter\":\"road b_street_e\"}}"), "road:b_street_e", DPLAN, "Two Quarters"));
		// nothing but the key: was "street"; the id as a part: was "road b street w"
		assertEquals("west street of district b", SettlementRunner.waitSubject(null, ext("{}"), "road:b_street_w", DPLAN, "Two Quarters"));
		assertEquals("west street of district b", MaterialRequests.partWords(DPLAN, "road_b_street_w", "Two Quarters"));
		// the street flag (Architect S-FL-5) names its district before the id does
		assertEquals("east street of the Old riverside quarter", SettlementRunner.waitSubject(null, ext("{\"architect_mc:part\":\"street_e\",\"architect_mc:street\":"
			+ "{\"zone\":\"town\",\"district\":\"old_quarter\"}}"), "road:street_e", DPLAN, "Two Quarters"));
		// a second zone's street by its flag's zone label; its z2_ prefix is by position (Architect plan compile), never a zone id: dropped
		assertEquals("west street of the Hill quarter", MaterialRequests.idWords(DPLAN, ext("{\"architect_mc:street\":{\"zone\":\"hill\"}}"), "z2_street_w"));
		assertEquals("west street", MaterialRequests.idWords(DPLAN, null, "z2_street_w"));
		assertEquals("west street", MaterialRequests.idWords(PLAN, ext("{\"architect_mc:street\":{\"zone\":\"castle\"}}"), "street_w"), "one zone: the town's own street");
		// a split piece; a free layout's avenue and ring
		assertEquals("west street of the Old riverside quarter", MaterialRequests.idWords(DPLAN, null, "old_quarter_street_w_2"));
		assertEquals("avenue 1 of the Old riverside quarter", MaterialRequests.idWords(DPLAN, null, "old_quarter_street_a1"));
		assertEquals("ring street", MaterialRequests.idWords(DPLAN, null, "street_ring"));
		// a street lamp is not a street: one rule reads only a street's own names
		assertEquals("props street lamp", MaterialRequests.idWords(DPLAN, null, "props_street_lamp"));
		// the plan's own name first: a connector named high_street is its label, never "street of district high"
		assertEquals("High Street", MaterialRequests.partWords(DPLAN, "high_street", "Two Quarters"));
		assertEquals("High Street", SettlementRunner.waitSubject(null, ext("{\"architect_mc:part\":\"high_street\"}"), "road:high_street", DPLAN, "Two Quarters"));
	}

	@Test
	void aDistrictIsNamedByItsOwnName() {
		// what a part waits on, a plan district by id: was "d2" ("The Corner wall tower waits for the d2 to be built.")
		assertEquals("the river district", MaterialRequests.partWords(DPLAN, "d2", "Two Quarters"));
		assertEquals("The Corner wall tower waits for the river district to be built.", SettlementRunner.stageWait(STAGE, "Corner wall tower",
			MaterialRequests.partWords(DPLAN, "d2", "Two Quarters")));
		assertEquals("Old riverside quarter", MaterialRequests.partWords(DPLAN, "old_quarter", "Two Quarters"));
		// no label: "district b", never "b"
		assertEquals("district b", MaterialRequests.partWords(DPLAN, "b", "Two Quarters"));
		// a materials line says the same subject
		assertEquals("The west street of district b waits for materials: 2 iron door. They go in the stockpile.", MaterialRequests.waitWords(
			SettlementRunner.waitSubject(null, ext("{}"), "road:b_street_w", DPLAN, "Two Quarters"), "2 iron door", MaterialRequests.Pile.UNREAD, ""));
	}

	static final SettlementRunner.ItemWait CAPPED = new SettlementRunner.ItemWait("bill_cap", "c4", java.util.List.of(java.util.Map.entry("minecraft:observer", 3)), 0,
		"region", 2);

	@Test
	void aCapWaitSaysTheSameSubjectAsAStageWait() {
		// the typed part fell back to the blocker's (curtain_wall): the cap line said "The curtain wall has stopped" for the wall tower
		var e = ext("{\"architect_mc:lot\":\"tower_1\",\"architect_mc:waitsFor\":{\"target\":\"c161\",\"part\":\"curtain_wall\",\"waiter\":\"lot tower_1\"}}");
		String capped = SettlementRunner.capStageWait(CAPPED, null, e, "lot:tower_1", PLAN, "Castle Walls", "set_1", MaterialRequests.TakeUp.UNDO);
		assertTrue(capped.startsWith("The corner wall tower has stopped: "), capped);
		String what = SettlementRunner.waitSubject(null, e, "lot:tower_1", PLAN, "Castle Walls");
		assertTrue(SettlementRunner.stageWait(STAGE, what, "Curtain wall").startsWith("The Corner wall tower waits"), "the same words, a sentence's case aside");
		// a road: its words, never its id or "street"
		var r = ext("{\"architect_mc:part\":\"old_quarter_street_w\",\"architect_mc:waitsFor\":{\"target\":\"c7\",\"part\":\"town\"}}");
		assertTrue(SettlementRunner.capStageWait(CAPPED, null, r, "road:old_quarter_street_w", DPLAN, "Two Quarters", "set_1", MaterialRequests.TakeUp.UNDO)
			.startsWith("The west street of the Old riverside quarter has stopped: "));
		// a lot's role and the article rule as a stage wait's
		assertTrue(SettlementRunner.capStageWait(CAPPED, "lot gatehouse 1", e, "lot:gatehouse_1", PLAN, "Castle Walls", "set_1", MaterialRequests.TakeUp.UNDO)
			.startsWith("The gatehouse 1 has stopped: "));
		assertNull(SettlementRunner.capStageWait(STAGE, null, e, "lot:tower_1", PLAN, "Castle Walls", "set_1", MaterialRequests.TakeUp.UNDO), "not a cap wait");
	}

	@Test
	void aSubjectWithNoNameIsTheBuildNeverEmpty() {
		for (String key : new String[] {"", "road:", "tile:", "road: "}) {
			String what = SettlementRunner.waitSubject(null, ext("{}"), key, null, "Castle Walls");
			assertEquals("build", what, "key \"" + key + "\"");
			assertEquals("The build waits for an earlier part to be built.", SettlementRunner.stageWait(STAGE, what, null));
			assertTrue(SettlementRunner.capStageWait(CAPPED, null, ext("{}"), key, null, "Castle Walls", "set_1", MaterialRequests.TakeUp.UNDO).startsWith("The build has stopped"));
		}
		// a blank waiter or part is no name: the key names it
		assertEquals("west street", SettlementRunner.waitSubject(null, ext("{\"architect_mc:part\":\" \",\"architect_mc:waitsFor\":{\"waiter\":\"road \"}}"), "road:street_w",
			null, "Castle Walls"));
		assertEquals("build", MaterialRequests.idWords(null, null, "  "));
		assertEquals("build", MaterialRequests.idWords(null, null, "road "));
		// an unreadable plan, or a prefix no plan names: the street's side alone, never "district zz"
		assertEquals("north street", MaterialRequests.idWords("not json", null, "zz_street_n"));
		assertEquals("north street", MaterialRequests.idWords(DPLAN, null, "zz_street_n"));
	}
}
