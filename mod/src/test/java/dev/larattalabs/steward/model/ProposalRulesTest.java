package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Progress.Tier;
import dev.larattalabs.steward.model.ProposalRules.Signs;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProposalRulesTest {
	private static final long NOW = 1_000_000_000L;

	private static Signs signs(Tier t) {
		return new Signs(t, 0, 0, Map.of());
	}

	@Test
	void theTierIsTheHighestAdvancementDone() {
		assertEquals(Tier.WOOD, Progress.tier(Set.of()));
		assertEquals(Tier.IRON, Progress.tier(Set.of("minecraft:story/mine_stone", "minecraft:story/smelt_iron")));
		assertEquals(Tier.NETHER, Progress.tier(Set.of("minecraft:story/enter_the_nether")), "a skipped tier does not stop a later one");
	}

	@Test
	void ironProposesASmithyThatTheSettlementLacks() {
		List<Proposal> p = ProposalRules.propose(signs(Tier.IRON), Set.of(), Set.of(), List.of(), 0, NOW);
		assertEquals(1, p.size(), "one at a time");
		assertEquals("smithy", p.get(0).key(), "the newest tier first");
		assertEquals("smithy", p.get(0).building().type());
		assertEquals("tier", p.get(0).trigger());
		// a settlement with a smithy is offered the workshop instead
		assertEquals("workshop", ProposalRules.propose(signs(Tier.IRON), Set.of("smithy"), Set.of(), List.of(), 0, NOW).get(0).key());
	}

	@Test
	void whatThePlayerDoesNowComesBeforeTheTier() {
		var s = new Signs(Tier.DIAMOND, 12, 0, Map.of());
		assertEquals("farm", ProposalRules.propose(s, Set.of(), Set.of(), List.of(), 0, NOW).get(0).key());
		var animals = new Signs(Tier.WOOD, 0, 0, Map.of("minecraft:sheep", 5));
		assertEquals("pens", ProposalRules.propose(animals, Set.of(), Set.of(), List.of(), 0, NOW).get(0).key());
		assertTrue(ProposalRules.propose(new Signs(Tier.WOOD, 7, 3, Map.of("minecraft:cow", 3)), Set.of(), Set.of(), List.of(), 0, NOW).isEmpty(), "below every threshold");
	}

	@Test
	void theGuardrailsHold() {
		// declined: never again
		assertEquals("workshop", ProposalRules.propose(signs(Tier.IRON), Set.of(), Set.of("smithy"), List.of(), 0, NOW).get(0).key());
		// the cooldown
		assertTrue(ProposalRules.propose(signs(Tier.IRON), Set.of(), Set.of(), List.of(), NOW - 60_000, NOW).isEmpty());
		assertFalse(ProposalRules.propose(signs(Tier.IRON), Set.of(), Set.of(), List.of(), NOW - ProposalRules.COOLDOWN_MS, NOW).isEmpty());
		// at most three open, and an open one is not made twice
		Proposal smithy = ProposalRules.propose(signs(Tier.IRON), Set.of(), Set.of(), List.of(), 0, NOW).get(0);
		assertEquals("workshop", ProposalRules.propose(signs(Tier.IRON), Set.of(), Set.of(), List.of(smithy), 0, NOW).get(0).key());
		assertTrue(ProposalRules.propose(signs(Tier.ELYTRA), Set.of(), Set.of(), List.of(smithy, smithy, smithy), 0, NOW).isEmpty());
	}

	@Test
	void proposalsSurviveTheSaveAndOldSavesHaveNone() throws Exception {
		Settlement s = Settlement.founded("set_1", "Here", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		assertEquals(Settlement.Proposals.NONE, s.proposals());
		Proposal p = ProposalRules.propose(signs(Tier.IRON), Set.of(), Set.of(), List.of(), 0, NOW).get(0);
		s = s.withProposals(new Settlement.Proposals(List.of(p), List.of("vault"), List.of(), NOW));
		SettlementStore st = new SettlementStore();
		st.put(s);
		Settlement back = SettlementStore.fromJson(st.toJson()).get("set_1").orElseThrow();
		assertEquals(s.proposals(), back.proposals());
		assertEquals(s.withLog(new Settlement.LogEntry(2L, Settlement.Kind.NOTE, "x", List.of())).proposals(), s.proposals(), "a log entry keeps them");
		try (var in = getClass().getClassLoader().getResourceAsStream("saves/v1/steward-settlements.json")) {
			var old = SettlementStore.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			assertTrue(old.all().stream().allMatch(x -> x.proposals().equals(Settlement.Proposals.NONE)));
		}
	}
}
