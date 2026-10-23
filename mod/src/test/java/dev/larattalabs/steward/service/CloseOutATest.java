package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class CloseOutATest {
	@Test
	void aStageWaitIsSaidInArchitectsWords() {
		var ext = JsonParser.parseString("{\"architect_mc:region\":\"rg3\",\"architect_mc:waitsFor\":{\"target\":\"c9\",\"part\":\"town_wall\",\"waiter\":\"the street\","
			+ "\"say\":\"the street waits for the town wall to be built\"}}").getAsJsonObject();
		assertEquals("The street waits for the town wall to be built", SettlementRunner.stageWait(ext));
		assertNull(SettlementRunner.stageWait(JsonParser.parseString("{\"architect_mc:region\":\"rg3\"}").getAsJsonObject()), "not a stage wait");
		assertNull(SettlementRunner.stageWait(null));
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
