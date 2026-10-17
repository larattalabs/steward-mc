package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.Proposal;
import org.junit.jupiter.api.Test;

class PendingOffersTest {
	@Test
	void aWaitingPartIsAProposalThatSavesAndReadsBack() {
		Proposal p = Proposal.part("town_wall", "The town wall can be built now", "Architect can build it now.", 5L);
		assertTrue(p.offersPart());
		assertEquals("part_town_wall", p.key());
		assertEquals(p, new Gson().fromJson(new Gson().toJson(p), Proposal.class));
		// a proposal saved before waiting parts reads back with none
		Proposal old = new Gson().fromJson("{\"key\":\"smithy\",\"title\":\"A smithy\",\"why\":\"\",\"building\":{\"role\":\"smithy\",\"type\":\"smithy\",\"count\":1,"
			+ "\"footprint\":\"M\",\"landmark\":false},\"at\":1,\"trigger\":\"\"}", Proposal.class);
		assertFalse(old.offersPart());
	}

	@Test
	void aPartIsFoundInAnyListAndNamedByItsLabel() {
		var plan = JsonParser.parseString("{\"zones\":[{\"id\":\"town\"}],\"connectors\":[{\"id\":\"town_wall\",\"kind\":\"wall\",\"label\":\"the town wall\"}],"
			+ "\"forms\":[{\"id\":\"secret_cellar\",\"kind\":\"hidden_room\"}]}").getAsJsonObject();
		assertEquals("the town wall", PendingOffers.name(PendingOffers.partOf(plan, "town_wall").orElseThrow(), "town_wall"));
		assertEquals("the secret cellar", PendingOffers.name(PendingOffers.partOf(plan, "secret_cellar").orElseThrow(), "secret_cellar"));
		assertTrue(PendingOffers.partOf(plan, "moat").isEmpty());
	}
}
