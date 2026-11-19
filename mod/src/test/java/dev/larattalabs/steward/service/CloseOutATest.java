package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class CloseOutATest {
	@Test
	void aStageWaitIsSaidInTheStewardsWordsNeverArchitectsSay() {
		// (8bS) the wait read off ext's structured fields (an event from before Architect 8b): the target it waits for, never the say
		var ext = JsonParser.parseString("{\"architect_mc:region\":\"rg3\",\"architect_mc:waitsFor\":{\"target\":\"c9\",\"part\":\"town_wall\",\"waiter\":\"the street\","
			+ "\"say\":\"the street waits for the town wall to be built\"}}").getAsJsonObject();
		var w = SettlementRunner.ItemWait.ofExt(ext);
		assertEquals("stage", w.reason());
		assertEquals("c9", w.waitsOn());
		assertEquals("The street waits for the town wall to be built.", SettlementRunner.stageWait(w, "street", "town wall"));
		assertEquals("The street waits for an earlier part to be built.", SettlementRunner.stageWait(w, "street", null), "an unnamed target: never Architect's say");
		assertNull(SettlementRunner.ItemWait.ofExt(JsonParser.parseString("{\"architect_mc:region\":\"rg3\"}").getAsJsonObject()), "not a stage wait");
		assertNull(SettlementRunner.ItemWait.ofExt(null));
		assertNull(SettlementRunner.stageWait(null, "street", null));
		var terrain = SettlementRunner.ItemWait.ofExt(JsonParser.parseString("{\"architect_mc:waitsFor\":{\"kind\":\"terrain\",\"part\":\"relief_1\",\"say\":\"x\"}}").getAsJsonObject());
		assertEquals("relief_1", terrain.waitsOn());
		assertEquals("The market hall waits for its land to be shaped.", SettlementRunner.stageWait(terrain, "market hall", null));
	}

	@Test
	void aTypedStatusIsReadOverTheExt() {
		// (8bS) Architect 8b's ItemEvent.status wins: its reason and waitsOn; a status that isn't a wait is no wait
		var ext = JsonParser.parseString("{\"architect_mc:waitsFor\":{\"kind\":\"bill_cap\",\"say\":\"x waits bill_cap\"}}").getAsJsonObject();
		var st = new dev.larattalabs.architect.api.BuildStatus("waiting", "stage", "c9", java.util.Map.of(), 0, 10L, "anything", 2, null);
		var w = SettlementRunner.ItemWait.of(st, ext);
		assertEquals("stage", w.reason());
		assertEquals("c9", w.waitsOn());
		assertEquals(2, w.rid());
		assertFalse(w.capped());
		assertNull(SettlementRunner.ItemWait.of(dev.larattalabs.architect.api.BuildStatus.of("built", 0), ext));
		assertNull(SettlementRunner.ItemWait.of(new dev.larattalabs.architect.api.BuildStatus("waiting", null, null, java.util.Map.of(), 0, 0L, "", 0, null), ext),
			"an untyped wait is never guessed");
	}

	@Test
	void aRouteToALotThatWasntPlacedWaitsOnThatLot() {
		var part = JsonParser.parseString("{\"part\":\"east_street\",\"class\":\"blocked\",\"blockedBy\":[\"crafts_quarter\"],\"reasons\":[{\"code\":\"DEPENDENCY\","
			+ "\"evidence\":{\"kind\":\"unplaced\",\"lot\":\"smithy_1\",\"zone\":\"crafts_quarter\"}}]}").getAsJsonObject();
		assertEquals("smithy 1", Composer.unplacedEnd(part));
		var other = JsonParser.parseString("{\"part\":\"cellar\",\"class\":\"blocked\",\"reasons\":[{\"code\":\"DEPENDENCY\",\"evidence\":{\"blockedBy\":[\"hall\"]}}]}").getAsJsonObject();
		assertNull(Composer.unplacedEnd(other), "blocked on a part, not an unplaced lot");
	}
}
