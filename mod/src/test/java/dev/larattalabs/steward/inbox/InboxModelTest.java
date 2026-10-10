package dev.larattalabs.steward.inbox;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.BudgetPolicy;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.pipeline.Pipeline;
import dev.larattalabs.steward.pipeline.Pipeline.State;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InboxModelTest {
	private static ConceptCard card() {
		var in = InboxModelTest.class.getClassLoader().getResourceAsStream("stilt_village.json");
		return ConceptCard.parse(JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
	}

	private static State running() {
		Permission p = Permission.PROPOSALS;
		State s = Pipeline.step(State.start("set_4", card(), 30), new Pipeline.CardApproved(card()), p).next();
		s = Pipeline.step(s, new Pipeline.BibleDone(true, "b", 1, 1.2, null), p).next();
		return Pipeline.step(s, new Pipeline.BibleApproved(), p).next();
	}

	@Test
	void eachDecisionHasItsHeadlineAndTheMassingsTheirLots() {
		State s = running();
		InboxModel.Entry working = InboxModel.entry("set_4", "Stilt", s, List.of());
		assertFalse(working.waiting());
		assertEquals("Designing the buildings.", working.headline());

		State massings = Pipeline.step(s, new Pipeline.GroupUpdate("g", "awaiting_approval", Map.of("cabin_1", "approval"), List.of("cabin_1"), 2, null), Permission.PROPOSALS).next();
		var lots = List.of(new InboxModel.Lot("cabin_1", "fisher's stilt house", "10x9, 13 tall (stilts, hut)"));
		InboxModel.Entry e = InboxModel.entry("set_4", "Stilt", massings, lots);
		assertTrue(e.waiting());
		assertEquals("MASSINGS", e.decision());
		assertEquals(lots, e.lots());
		assertTrue(e.headline().startsWith("1 massings wait"), e.headline());

		State paused = Pipeline.step(s, new Pipeline.GroupUpdate("g", "paused_budget", Map.of(), List.of(), 24, null), Permission.PROPOSALS).next();
		InboxModel.Entry b = InboxModel.entry("set_4", "Stilt", paused, lots);
		assertEquals("BUDGET", b.decision());
		assertTrue(b.lots().isEmpty(), "lots only for massings");
		assertEquals(Pipeline.minimumRaise(paused), b.minRaiseUsd());
		assertTrue(b.headline().contains("$" + (int) Pipeline.minimumRaise(paused)), b.headline());
	}

	@Test
	void oneLandmarkRuleForTheRunnerTheHintAndTheScreen() {
		assertEquals(1, BudgetPolicy.landmarksFor("M", 4, 2), "one per four buildings");
		assertEquals(2, BudgetPolicy.landmarksFor("M", 8, 3), "M's typical count");
		assertEquals(0, BudgetPolicy.landmarksFor("L", 12, 0), "only flagged buildings");
		assertEquals(1, BudgetPolicy.maxLandmarks("S", 2), "at least one");
	}
}
