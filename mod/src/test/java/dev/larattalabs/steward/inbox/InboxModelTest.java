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
		assertEquals(Pipeline.minimumRaise(paused), b.minRaiseUsd());
		assertTrue(b.headline().contains("$" + (int) Pipeline.minimumRaise(paused)), b.headline());
	}

	@Test
	void theLinesSayTheBuildingsByStageAndAHeldUsageLimitInPlainWords() {
		State s = Pipeline.step(running(), new Pipeline.GroupUpdate("g", "held_usage", Map.of("cabin_1", "massing", "tower_1", "done"), List.of(), 2, "resets at 4pm"),
			Permission.PROPOSALS).next();
		InboxModel.Entry e = InboxModel.entry("set_4", "Stilt", s, List.of(), Attention.of(s, 1_800_000_000_000L, null, false, "", 1_799_999_000_000L));
		assertTrue(e.lines().stream().anyMatch(l -> l.startsWith("Buildings: ") && l.contains("1 massing") && l.contains("1 done")), e.lines().toString());
		// the usage limit is the reset time as a number (the inbox words it in the player's own clock), never a line to parse
		assertTrue(e.held());
		assertEquals(1_800_000_000_000L, e.limitResetsAt());
		assertTrue(e.lines().stream().noneMatch(l -> l.startsWith(InboxModel.HELD_LINE)), e.lines().toString());
		// the spend shows beside the state, not again as a line; no tool is named to the player
		assertTrue(e.lines().stream().noneMatch(l -> l.startsWith("Spent") || l.contains("Claude") || l.contains("Architect")), e.lines().toString());
		assertFalse(InboxModel.working(Pipeline.Phase.CANCELLING).contains("Architect"));
	}

	@Test
	void anEndedBuildIsARowSayingHowWithNothingToDecide() {
		State cancelled = Pipeline.step(Pipeline.step(running(), new Pipeline.Cancel(), Permission.PROPOSALS).next(), new Pipeline.CancelConfirmed(), Permission.PROPOSALS).next();
		assertTrue(cancelled.phase().terminal(), cancelled.phase().toString());
		InboxModel.Entry e = InboxModel.entry("set_4", "Stilt", cancelled, List.of());
		assertTrue(e.ended());
		assertFalse(e.waiting(), "an ended build waits for nothing (the HUD and the count leave it out)");
		assertEquals(cancelled.phase().name(), e.decision());
		assertEquals("set_4", e.key());
		assertTrue(e.headline().startsWith("Cancelled"), e.headline());
		InboxModel.Entry running = InboxModel.entry("set_4", "Stilt", running(), List.of());
		assertFalse(running.ended());
	}

	@Test
	void oneLandmarkRuleForTheRunnerTheHintAndTheScreen() {
		assertEquals(1, BudgetPolicy.landmarksFor("M", 4, 2), "one per four buildings");
		assertEquals(2, BudgetPolicy.landmarksFor("M", 8, 3), "M's typical count");
		assertEquals(0, BudgetPolicy.landmarksFor("L", 12, 0), "only flagged buildings");
		assertEquals(1, BudgetPolicy.maxLandmarks("S", 2), "at least one");
	}
}
