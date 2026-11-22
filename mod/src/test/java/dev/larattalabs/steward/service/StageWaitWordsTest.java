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
		// a road item names itself (its own part), as before
		assertEquals("street w", SettlementRunner.waitSubject(null, ext("{\"architect_mc:part\":\"street_w\",\"architect_mc:waitsFor\":{\"target\":\"c1\",\"part\":\"town\"}}"),
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
}
