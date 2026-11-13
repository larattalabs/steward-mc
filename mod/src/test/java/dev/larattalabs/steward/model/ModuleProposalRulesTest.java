package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.Progress.Tier;
import dev.larattalabs.steward.model.ProposalRules.ModuleCandidate;
import dev.larattalabs.steward.model.ProposalRules.Signs;
import dev.larattalabs.steward.module.ModuleKind;
import dev.larattalabs.steward.module.ModuleOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** docs/MODULES.md 4.1 "Coalescing and declines" (slice M3): module identity, not building type; declines both ways; the decorative rule as before. */
class ModuleProposalRulesTest {
	static final ModuleKind K = ModuleKind.FixturePlot.INSTANCE;
	static final long NOW = 10_000_000L;

	static Signs seeds(int wheat) {
		return new Signs(Tier.WOOD, wheat, 0, Map.of(), Map.of("minecraft:wheat_seeds", wheat));
	}

	static Proposal offer() {
		var o = new ModuleOrder("fixture_plot", 1, "a".repeat(64), JsonParser.parseString("{\"side\": 5, \"crop\": \"wheat\"}").getAsJsonObject(), null, null, false, false);
		return Proposal.module(K.rule(), "A fenced, lit plot", "You carry wheat seeds.", new ConceptCard.Building("fenced plot", "module_fixture", 1, "L", false, null, null),
			NOW, "seeds", o);
	}

	static ModuleCandidate eligible() {
		return new ModuleCandidate(K, false, offer(), null);
	}

	@Test
	void aModuleWhoseSignFiresIsOfferedBeforeTheDecorativeFarm() {
		var out = ProposalRules.propose(seeds(16), Set.of(), Set.of(), List.of(), 0, NOW, List.of(eligible()));
		assertEquals(1, out.size());
		assertEquals("module.fixture_plot", out.get(0).key());
		assertTrue(out.get(0).offersModule());
		// 7 seeds: no sign (and no farm either)
		assertTrue(ProposalRules.propose(seeds(7), Set.of(), Set.of(), List.of(), 0, NOW, List.of(eligible())).isEmpty());
		// the seeds' kinds unknown (as before modules): the module's sign does not fire
		assertEquals("farm", ProposalRules.propose(new Signs(Tier.WOOD, 16, 0, Map.of()), Set.of(), Set.of(), List.of(), 0, NOW, List.of(eligible())).get(0).key());
	}

	@Test
	void anIneligibleModuleLeavesTheDecorativeFarmAsToday() {
		var no = new ModuleCandidate(K, false, null, "the water would freeze");
		var out = ProposalRules.propose(seeds(16), Set.of(), Set.of(), List.of(), 0, NOW, List.of(no));
		assertEquals("farm", out.get(0).key());
		assertFalse(out.get(0).offersModule());
	}

	@Test
	void aDecorativeFarmDoesNotSuppressTheModuleButAStandingModuleDoes() {
		// the town has a decorative farm (type farm): the module is still offered, once
		assertEquals("module.fixture_plot", ProposalRules.propose(seeds(16), Set.of("farm"), Set.of(), List.of(), 0, NOW, List.of(eligible())).get(0).key());
		// a module of the same id stands: neither it nor the decorative farm
		var standing = new ModuleCandidate(K, true, offer(), null);
		assertTrue(ProposalRules.propose(seeds(16), Set.of(), Set.of(), List.of(), 0, NOW, List.of(standing)).isEmpty());
	}

	@Test
	void anOfferOfTheSameModuleWaitingHoldsBothBack() {
		var asked = new Proposal("asked_x_module_fixture", offer().title(), offer().why(), offer().building(), NOW, "asked", null, null, null, null, null, null,
			offer().module());
		var out = ProposalRules.propose(seeds(16), Set.of(), Set.of(), List.of(asked), 0, NOW, List.of(eligible()));
		assertTrue(out.isEmpty(), "one asked in talk counts, by module id");
	}

	@Test
	void decliningTheModuleRetiresTheFarmButNotTheOtherWay() {
		assertEquals(List.of("module.fixture_plot", "farm"), ProposalRules.declines("module.fixture_plot"));
		assertEquals(List.of("farm"), ProposalRules.declines("farm"));
		// declined module: not offered; the farm declined with it
		var declined = Set.copyOf(ProposalRules.declines("module.fixture_plot"));
		assertTrue(ProposalRules.propose(seeds(16), Set.of(), declined, List.of(), 0, NOW, List.of(eligible())).isEmpty());
		// only the farm declined: the module is still offered
		assertEquals("module.fixture_plot", ProposalRules.propose(seeds(16), Set.of(), Set.of("farm"), List.of(), 0, NOW, List.of(eligible())).get(0).key());
	}

	@Test
	void theGuardrailsHoldForModules() {
		assertTrue(ProposalRules.propose(seeds(16), Set.of(), Set.of(), List.of(), NOW - 60_000, NOW, List.of(eligible())).isEmpty(), "the cooldown");
	}
}
