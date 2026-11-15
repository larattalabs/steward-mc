package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.inbox.InboxModel;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.Settlement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PendingOffersTest {
	@Test
	void aWaitingPartIsAProposalThatSavesAndReadsBack() {
		Proposal p = Proposal.part("town_wall", "The town wall can be built now", "It can be built now.", 5L);
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

	@Test
	void onlyThePartAskedForIsBuilt() {
		var one = JsonParser.parseString("{\"connectors\":[{\"id\":\"town_wall\",\"kind\":\"wall\"}]}").getAsJsonObject();
		assertNull(PendingOffers.outOfScope(one, "town_wall", "connector.wall"));
		assertNotNull(PendingOffers.outOfScope(one, "town_wall", "connector.rope_bridge"), "the wrong kind for its block");
		var nullId = JsonParser.parseString("{\"connectors\":[{\"id\":null,\"kind\":\"wall\"}]}").getAsJsonObject();
		assertNotNull(PendingOffers.outOfScope(nullId, "town_wall", "connector.wall"), "an id of the wrong type is out of scope, not an exception");
		var two = JsonParser.parseString("{\"connectors\":[{\"id\":\"town_wall\",\"kind\":\"wall\"},{\"id\":\"outer_wall\",\"kind\":\"wall\"}]}").getAsJsonObject();
		assertNotNull(PendingOffers.outOfScope(two, "town_wall", "connector.wall"), "an unasked second wall");
		var zoned = JsonParser.parseString("{\"zones\":[{\"id\":\"town_wall\"}]}").getAsJsonObject();
		assertNotNull(PendingOffers.outOfScope(zoned, "town_wall", "connector.wall"), "no zones in a part plan");
		assertNotNull(PendingOffers.outOfScope(JsonParser.parseString("{}").getAsJsonObject(), "town_wall", "connector.wall"), "the part missing");
	}

	@Test
	void aGateIsNeverOfferedAlone() {
		assertFalse(PendingOffers.offerable("connector.gate"));
		assertFalse(PendingOffers.offerable("zone.outpost"), "a zone needs a layout");
		assertTrue(PendingOffers.offerable("connector.wall"));
		assertTrue(PendingOffers.offerable("form.hidden_room"));
	}

	@Test
	void theGroupFitsAnAdjustedPartFirstThenOneThatHasNotFailed() {
		var parts = List.of("town_wall", "moat", "bell_tower");
		assertEquals("town_wall", PendingOffers.pick(parts, Set.of(), Set.of()));
		assertEquals("moat", PendingOffers.pick(parts, Set.of("moat"), Set.of()), "one Architect adjusted waits for its second accept");
		assertEquals("moat", PendingOffers.pick(parts, Set.of(), Set.of("town_wall")), "a part that did not fit does not block the rest");
		assertEquals("town_wall", PendingOffers.pick(parts, Set.of(), Set.copyOf(parts)), "all failed: the first again");
		assertNull(PendingOffers.pick(List.of(), Set.of(), Set.of()));
	}

	@Test
	void theWaitingPartsAreOneRowInPlainWords() {
		Settlement s = Settlement.founded("set_parts", "Mossbank", new Claim("minecraft:overworld", 0, 0, 128, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		var parts = List.of(Proposal.part("town_wall", "The town wall can be built now", "", 1L), Proposal.part("north_moat", "The north moat can be built now", "", 1L));
		// no plan kept for it: each part by its id in words
		InboxModel.Lot row = Proposals.partsRow(s, parts);
		assertEquals(PendingOffers.GROUP_KEY, row.id());
		assertEquals("Waiting parts (2)", row.role());
		assertTrue(row.detail().contains("the town wall, the north moat"), row.detail());
		for (String word : List.of("Architect", "Claude", "connector.", "_")) assertFalse(row.detail().contains(word), word + " in " + row.detail());
		InboxModel.Entry e = InboxModel.proposals(s.id(), s.name(), List.of(row));
		assertTrue(e.headline().startsWith("Parts of Mossbank's plan can be built now"), e.headline());
		InboxModel.Entry mixed = InboxModel.proposals(s.id(), s.name(), List.of(new InboxModel.Lot("smithy", "A smithy", "proposal", false, true, "about $10"), row));
		assertTrue(mixed.headline().startsWith("The steward has an idea for Mossbank"), mixed.headline());
	}

	@Test
	void aNewOffersWordsNameNoToolAndNoBlock() {
		Proposal p = Proposal.part("town_wall", "The town wall can be built now", "", 1L);
		assertFalse(p.trigger().contains("Architect"));
	}

	@Test
	void theOfferSaysWhatAcceptingDoesAndThatAChangeIsAskedFirst() {
		String why = PendingOffers.offerWords("Mossbank");
		assertTrue(why.startsWith("Say yes and I fit it round Mossbank as it stands (up to $1)"), why);
		assertTrue(why.contains("an undo takes away just this part and leaves the rest of the town standing"), why);
		assertTrue(why.contains("I show you the change and ask you once more before anything is built"), why);
		for (String word : List.of("Architect", "Claude", "connector.", "_")) assertFalse(why.contains(word), word + " in " + why);
		String adjusted = PendingOffers.adjustedWords("The town wall.", ChangeWords.of(JsonParser.parseString(
			"[{\"what\":\"move\",\"value\":15,\"max\":16},{\"what\":\"mechanism\",\"kind\":\"swing\",\"gate\":\"east_gate\",\"why\":\"villager\",\"edge\":\"in\"}]")));
		assertEquals("The town wall. It had to change to fit (moved 15 blocks and a wooden door instead of the iron gate), so nothing is built yet. Accept it once more"
			+ " in the inbox and I'll build it as shown.", adjusted);
		for (String raw : List.of("{", "\"what\"", "[")) assertFalse(adjusted.contains(raw), raw + " in " + adjusted);
		assertFalse(adjusted.contains("Architect"), adjusted);
		assertEquals("The town wall. It had to change to fit, so nothing is built yet. Accept it once more in the inbox and I'll build it as shown.",
			PendingOffers.adjustedWords("The town wall.", ""));
	}

	@Test
	void theFailLineHasOneColonAndKeepsArchitectsWords() {
		String say = "no room beside the hall: the market takes the east side";
		String line = PendingOffers.notYetWords("Mossbank", say);
		assertEquals("it doesn't fit round Mossbank yet (no room beside the hall: the market takes the east side)", line);
		assertTrue(line.contains(say), line);
		assertFalse(line.contains("yet:"), line);
		// a full stop ending Architect's words is left to the sentence's own ("X waits: ... .")
		assertEquals("it doesn't fit round Mossbank yet (the wall needs 3 more blocks of room)", PendingOffers.notYetWords("Mossbank", "the wall needs 3 more blocks of room."));
		assertEquals("it doesn't fit round Mossbank yet", PendingOffers.notYetWords("Mossbank", " "));
	}
}
