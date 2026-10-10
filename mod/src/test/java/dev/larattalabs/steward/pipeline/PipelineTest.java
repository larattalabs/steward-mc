package dev.larattalabs.steward.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.pipeline.Pipeline.*;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PipelineTest {
	private static ConceptCard card() throws Exception {
		var in = PipelineTest.class.getClassLoader().getResourceAsStream("real/crater_works_real.json");
		return ConceptCard.parse(JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
	}

	private static <T extends Command> T only(Step st, Class<T> type) {
		List<T> m = st.commands().stream().filter(type::isInstance).map(type::cast).toList();
		assertEquals(1, m.size(), "commands: " + st.commands());
		return m.get(0);
	}

	private static State started() throws Exception {
		return State.start("set_1", card(), 35.0);
	}

	private static GroupUpdate group(String status, Map<String, String> items, List<String> awaiting, double cost) {
		return new GroupUpdate("g1", status, items, awaiting, cost, null);
	}

	@Test
	void approvingTheCardRequestsTheBibleWithTheCardsWordsAndABoundedBudget() throws Exception {
		Step st = Pipeline.step(started(), new CardApproved(card()), Permission.PROPOSALS);
		assertEquals(Phase.BIBLE_RUNNING, st.next().phase());
		RequestBible rb = only(st, RequestBible.class);
		assertTrue(rb.prompt().contains("hellish evil lair"));
		assertTrue(rb.prompt().contains("giant meteor crater"));
		assertTrue(rb.prompt().contains("mining facility"));
		assertEquals(3.0, rb.budgetUsd(), 1e-9);
	}

	@Test
	void aBibleAtProposalsWaitsForApprovalThenRequestsTheGroupWithTheRemainingBudget() throws Exception {
		State s = Pipeline.step(started(), new CardApproved(card()), Permission.PROPOSALS).next();
		Step st = Pipeline.step(s, new BibleDone(true, "bib_1", 1, 1.4, null), Permission.PROPOSALS);
		assertEquals(Phase.AWAITING_BIBLE_APPROVAL, st.next().phase());
		assertTrue(only(st, Notify.class).needsDecision());
		Step go = Pipeline.step(st.next(), new BibleApproved(), Permission.PROPOSALS);
		assertEquals(Phase.GROUP_RUNNING, go.next().phase());
		RequestGroup rg = only(go, RequestGroup.class);
		assertEquals("bib_1", rg.bibleId());
		assertEquals(35.0 - 1.4, rg.budgetUsd(), 1e-9);
	}

	@Test
	void anAutonomousSettlementSkipsTheBibleApproval() throws Exception {
		State s = Pipeline.step(started(), new CardApproved(card()), Permission.AUTONOMOUS).next();
		Step st = Pipeline.step(s, new BibleDone(true, "bib_1", 2, 1.2, null), Permission.AUTONOMOUS);
		assertEquals(Phase.GROUP_RUNNING, st.next().phase());
		assertEquals(2, only(st, RequestGroup.class).bibleVersion());
	}

	@Test
	void aFailedBibleEndsThePipelineAndTellsThePlayer() throws Exception {
		State s = Pipeline.step(started(), new CardApproved(card()), Permission.FULL).next();
		Step st = Pipeline.step(s, new BibleDone(false, null, 0, 0.3, "budget"), Permission.FULL);
		assertEquals(Phase.FAILED, st.next().phase());
		assertTrue(st.next().failure().contains("budget"));
		assertTrue(only(st, Notify.class).needsDecision());
	}

	private static State groupRunning(Permission p) throws Exception {
		State s = Pipeline.step(started(), new CardApproved(card()), p).next();
		s = Pipeline.step(s, new BibleDone(true, "bib_1", 1, 1.4, null), p).next();
		if (s.phase() == Phase.AWAITING_BIBLE_APPROVAL) s = Pipeline.step(s, new BibleApproved(), p).next();
		return s;
	}

	@Test
	void massingsWaitForThePlayerAtProposalsAndTheDecisionGoesToArchitect() throws Exception {
		State s = groupRunning(Permission.PROPOSALS);
		Step st = Pipeline.step(s, group("awaiting_approval", Map.of("lot_0", "approval", "lot_1", "approval"), List.of("lot_0", "lot_1"), 0.5), Permission.PROPOSALS);
		assertEquals(Phase.AWAITING_MASSING_APPROVAL, st.next().phase());
		assertTrue(only(st, Notify.class).text().contains("2 massings"));
		Step d = Pipeline.step(st.next(), new MassingDecision(List.of("lot_0"), Map.of("lot_1", "make it L-shaped"), List.of()), Permission.PROPOSALS);
		assertEquals(Phase.GROUP_RUNNING, d.next().phase());
		ApproveGroup ag = only(d, ApproveGroup.class);
		assertEquals(List.of("lot_0"), ag.approve());
		assertEquals("make it L-shaped", ag.redirect().get("lot_1"));
		assertEquals("g1", ag.groupId());
	}

	@Test
	void autonomousSettlementsApproveEveryMassingAtOnce() throws Exception {
		State s = groupRunning(Permission.AUTONOMOUS);
		Step st = Pipeline.step(s, group("awaiting_approval", Map.of("lot_0", "approval"), List.of("lot_0"), 0.3), Permission.AUTONOMOUS);
		assertEquals(Phase.GROUP_RUNNING, st.next().phase());
		assertEquals(List.of("lot_0"), only(st, ApproveGroup.class).approve());
	}

	@Test
	void aSoftBudgetPauseAsksForMoneyEvenWhenAutonomousAndRaisingItResumes() throws Exception {
		State s = groupRunning(Permission.FULL);
		Step st = Pipeline.step(s, group("paused_budget", Map.of("lot_0", "detail"), List.of(), 28.2), Permission.FULL);
		assertTrue(st.next().pausedForBudget());
		Notify n = only(st, Notify.class);
		assertTrue(n.needsDecision());
		assertTrue(n.text().contains("80%"));
		Step r = Pipeline.step(st.next(), new BudgetRaised(60), Permission.FULL);
		assertFalse(r.next().pausedForBudget());
		assertEquals(60.0, r.next().budgetUsd());
		ExtendAndResumeGroup e = only(r, ExtendAndResumeGroup.class);
		assertEquals(60.0 - 1.4, e.groupBudgetUsd(), 1e-9, "the group's cap leaves out what the bible cost, so the total stays $60");
		assertEquals(35.0, e.previousBudgetUsd());
	}

	@Test
	void aUsageHoldIsAQuietNote() throws Exception {
		State s = groupRunning(Permission.PROPOSALS);
		Step st = Pipeline.step(s, new GroupUpdate("g1", "held_usage", Map.of(), List.of(), 3.0, "until 14:00"), Permission.PROPOSALS);
		assertFalse(only(st, Notify.class).needsDecision());
		assertEquals("until 14:00", st.next().heldNote());
	}

	@Test
	void aFinishedGroupFitsAndQueuesWithAutoApproveOnlyWhenPermitted() throws Exception {
		Step manual = Pipeline.step(groupRunning(Permission.PROPOSALS), group("done", Map.of("lot_0", "done"), List.of(), 20), Permission.PROPOSALS);
		assertEquals(Phase.READY_TO_PLACE, manual.next().phase());
		assertFalse(only(manual, FitAndQueue.class).autoApprove());
		Step auto = Pipeline.step(groupRunning(Permission.FULL), group("done", Map.of("lot_0", "done"), List.of(), 20), Permission.FULL);
		assertTrue(only(auto, FitAndQueue.class).autoApprove());
	}

	@Test
	void theBatchCompletingFinishesThePipeline() throws Exception {
		State s = Pipeline.step(groupRunning(Permission.FULL), group("done", Map.of(), List.of(), 20), Permission.FULL).next();
		State placing = Pipeline.step(s, new BatchQueued("b1"), Permission.FULL).next(); // Full: stages place as they come
		assertEquals(Phase.PLACING, placing.phase());
		Step done = Pipeline.step(placing, new BatchDone(11, 1), Permission.FULL);
		assertEquals(Phase.DONE, done.next().phase());
		assertTrue(only(done, Notify.class).text().contains("11 buildings placed, 1 skipped"));
	}

	@Test
	void cancellingStopsTheGroupAndFreezesThePipeline() throws Exception {
		State s = groupRunning(Permission.PROPOSALS);
		Step st = Pipeline.step(s, new Cancel(), Permission.PROPOSALS);
		assertEquals(Phase.CANCELLING, st.next().phase(), "the build keeps its slot until Architect stops");
		assertFalse(st.next().phase().terminal());
		only(st, CancelGroup.class);
		assertEquals(Phase.CANCELLING, Pipeline.step(st.next(), group("running", Map.of(), List.of(), 1), Permission.PROPOSALS).next().phase());
		Step stopped = Pipeline.step(st.next(), group("cancelled", Map.of(), List.of(), 1.2), Permission.PROPOSALS);
		assertEquals(Phase.CANCELLED, stopped.next().phase());
		assertEquals(1.4 + 1.2, stopped.next().spentUsd(), 1e-9);
		Step again = Pipeline.step(stopped.next(), new GroupUpdate("g1", "done", Map.of(), List.of(), 1, null), Permission.PROPOSALS);
		assertTrue(again.commands().isEmpty(), "a terminal pipeline ignores events");
		assertEquals(Phase.CANCELLED, again.next().phase());
	}

	@Test
	void eventsOutOfOrderAreIgnored() throws Exception {
		State s = started();
		assertTrue(Pipeline.step(s, new BibleDone(true, "b", 1, 1, null), Permission.FULL).commands().isEmpty());
		assertEquals(Phase.AWAITING_CARD_APPROVAL, Pipeline.step(s, new MassingDecision(List.of(), Map.of(), List.of()), Permission.FULL).next().phase());
	}

	@Test
	void stageCountsForTheHud() throws Exception {
		State s = groupRunning(Permission.PROPOSALS);
		State n = Pipeline.step(s, group("running", Map.of("a", "massing", "b", "massing", "c", "detail", "d", "done"), List.of(), 2), Permission.PROPOSALS).next();
		Map<String, Integer> c = Pipeline.stageCounts(n);
		assertEquals(2, c.get("massing"));
		assertEquals(1, c.get("detail"));
		assertEquals(1, c.get("done"));
	}

	@Test
	void spendIsTheBiblesCostPlusTheGroupsAggregate() throws Exception {
		State s = groupRunning(Permission.PROPOSALS); // the bible cost 1.4
		State n = Pipeline.step(s, group("running", Map.of("a", "detail"), List.of(), 5.0), Permission.PROPOSALS).next();
		assertEquals(6.4, n.spentUsd(), 1e-9);
		State m = Pipeline.step(n, group("running", Map.of("a", "done"), List.of(), 7.0), Permission.PROPOSALS).next();
		assertEquals(8.4, m.spentUsd(), 1e-9);
	}

	@Test
	void anUnnamedCardIsCalledByTheSettlementIdNeverNull() throws Exception {
		ConceptCard c = card();
		ConceptCard unnamed = new ConceptCard(null, c.site(), c.style(), c.purpose(), c.story(), c.constraints(), c.avoid(), c.interpretation(), c.contradictions(), c.assumptions(), c.program());
		State s = State.start("set_7", unnamed, 35.0);
		Step st = Pipeline.step(s, new Cancel(), Permission.FULL);
		Notify n = only(st, Notify.class);
		assertFalse(n.text().contains("null"), n.text());
		assertEquals("set_7", Pipeline.label(s));
	}

	@Test
	void atProposalsEveryDecisionWaitsForThePlayerAndTheWholePathCompletes() throws Exception {
		Permission p = Permission.PROPOSALS;
		State s = Pipeline.step(started(), new CardApproved(card()), p).next();
		s = Pipeline.step(s, new BibleDone(true, "bib1", 1, 1.5, null), p).next();
		assertEquals(Pipeline.Decision.BIBLE, Pipeline.awaiting(s));
		s = Pipeline.step(s, new BibleApproved(), p).next();
		assertEquals(Pipeline.Decision.NONE, Pipeline.awaiting(s));
		s = Pipeline.step(s, group("awaiting_approval", Map.of("lot_0", "approval"), List.of("lot_0"), 2), p).next();
		assertEquals(Pipeline.Decision.MASSINGS, Pipeline.awaiting(s));
		Step approve = Pipeline.step(s, new MassingDecision(List.of("lot_0"), Map.of(), List.of()), p);
		assertEquals(List.of("lot_0"), only(approve, ApproveGroup.class).approve());
		s = Pipeline.step(approve.next(), group("done", Map.of("lot_0", "done"), List.of(), 6), p).next();
		Step queued = Pipeline.step(s, new BatchQueued("b1"), p);
		assertEquals(Pipeline.Decision.PLACEMENT, Pipeline.awaiting(queued.next()));
		assertTrue(only(queued, Notify.class).needsDecision());
		Step place = Pipeline.step(queued.next(), new PlacementApproved(), p);
		only(place, ApproveStages.class);
		assertEquals(Phase.PLACING, place.next().phase());
		assertEquals(Phase.DONE, Pipeline.step(place.next(), new BatchDone(1, 0), p).next().phase());
	}

	@Test
	void aRepeatedAwaitingUpdateDoesNotNotifyAgain() throws Exception {
		State s = groupRunning(Permission.PROPOSALS);
		Step first = Pipeline.step(s, group("awaiting_approval", Map.of(), List.of("lot_0"), 2), Permission.PROPOSALS);
		only(first, Notify.class);
		Step again = Pipeline.step(first.next(), group("awaiting_approval", Map.of(), List.of("lot_0"), 2), Permission.PROPOSALS);
		assertTrue(again.commands().isEmpty(), "commands: " + again.commands());
	}

	@Test
	void aBudgetPauseIsADecisionAndCancellingWhilePlacingStopsTheBatch() throws Exception {
		State s = groupRunning(Permission.FULL);
		State paused = Pipeline.step(s, group("paused_budget", Map.of(), List.of(), 28), Permission.FULL).next();
		assertEquals(Pipeline.Decision.BUDGET, Pipeline.awaiting(paused));
		State placing = Pipeline.step(Pipeline.step(s, group("done", Map.of(), List.of(), 20), Permission.FULL).next(), new BatchQueued("b1"), Permission.FULL).next();
		only(Pipeline.step(placing, new Cancel(), Permission.FULL), CancelBatch.class);
	}

	@Test
	void aPlacementFoundFinishedAfterARestartGoesStraightToDone() throws Exception {
		State ready = Pipeline.step(groupRunning(Permission.PROPOSALS), group("done", Map.of(), List.of(), 20), Permission.PROPOSALS).next();
		Step st = Pipeline.step(ready, new BatchDone(5, 1), Permission.PROPOSALS);
		assertEquals(Phase.DONE, st.next().phase());
		assertFalse(only(st, Notify.class).needsDecision(), "no approval is asked for what is already placed");
	}

	@Test
	void aRepeatedBudgetPauseAsksOnce() throws Exception {
		State s = groupRunning(Permission.PROPOSALS);
		Step first = Pipeline.step(s, group("paused_budget", Map.of(), List.of(), 24), Permission.PROPOSALS);
		assertTrue(only(first, Notify.class).needsDecision());
		Step again = Pipeline.step(first.next(), group("paused_budget", Map.of(), List.of(), 24), Permission.PROPOSALS);
		assertTrue(again.commands().isEmpty(), "commands: " + again.commands());
		assertEquals(Pipeline.Decision.BUDGET, Pipeline.awaiting(again.next()));
	}

	@Test
	void theMinimumRaiseClearsTheSoftPause() throws Exception {
		State paused = Pipeline.step(groupRunning(Permission.PROPOSALS), group("paused_budget", Map.of(), List.of(), 24.9), Permission.PROPOSALS).next();
		double min = Pipeline.minimumRaise(paused);
		assertTrue(paused.spentUsd() < min * dev.larattalabs.steward.model.BudgetPolicy.SOFT_FRACTION - 4.99, "room under the new pause: " + min);
		assertEquals(0.0, min % 5.0, 1e-9);
		// the gate run: $31.03 spent at a $35 budget: (31.03 + 5) / 0.8 = 45.04, so $50
		State gate = new State(Phase.GROUP_RUNNING, "set_4", paused.card(), "b", 1, "g", Map.of(), 1.16, 31.03, 35, true, null, null);
		assertEquals(50.0, Pipeline.minimumRaise(gate), 1e-9);
	}

	@Test
	void theBibleNeverAsksForMoreThanTheWholeBudget() throws Exception {
		Step st = Pipeline.step(State.start("set_1", card(), 1.0), new CardApproved(card()), Permission.PROPOSALS);
		assertEquals(1.0, only(st, RequestBible.class).budgetUsd(), 1e-9);
	}

	@Test
	void aBibleThatUsesTheBudgetUpPausesBeforeTheGroupAndARaiseRequestsIt() throws Exception {
		State s = Pipeline.step(State.start("set_1", card(), 2.0), new CardApproved(card()), Permission.FULL).next();
		Step st = Pipeline.step(s, new BibleDone(true, "bib_1", 1, 1.8, null), Permission.FULL);
		assertTrue(st.commands().stream().noneMatch(RequestGroup.class::isInstance), "no group on $0.20: " + st.commands());
		assertEquals(Pipeline.Decision.BUDGET, Pipeline.awaiting(st.next()));
		assertTrue(only(st, Notify.class).needsDecision());
		Step raised = Pipeline.step(st.next(), new BudgetRaised(10), Permission.FULL);
		assertEquals(10.0 - 1.8, only(raised, RequestGroup.class).budgetUsd(), 1e-9);
		// cancelling while paused there: nothing runs at Architect
		assertEquals(Phase.CANCELLED, Pipeline.step(st.next(), new Cancel(), Permission.FULL).next().phase());
	}

	@Test
	void aRaiseArchitectRefusesGoesBackAndStaysPaused() throws Exception {
		State paused = Pipeline.step(groupRunning(Permission.PROPOSALS), group("paused_budget", Map.of(), List.of(), 26), Permission.PROPOSALS).next();
		State raised = Pipeline.step(paused, new BudgetRaised(50), Permission.PROPOSALS).next();
		assertFalse(raised.pausedForBudget());
		Step failed = Pipeline.step(raised, new BudgetRaiseFailed(35, "boom"), Permission.PROPOSALS);
		assertEquals(35.0, failed.next().budgetUsd());
		assertEquals(Pipeline.Decision.BUDGET, Pipeline.awaiting(failed.next()));
		assertTrue(only(failed, Notify.class).text().contains("boom"));
	}

	@Test
	void cancellingTheBibleStopsItAndWaitsForItsEnd() throws Exception {
		State s = Pipeline.step(started(), new CardApproved(card()), Permission.PROPOSALS).next();
		Step st = Pipeline.step(s, new Cancel(), Permission.PROPOSALS);
		assertEquals(Phase.CANCELLING, st.next().phase());
		only(st, CancelBible.class);
		assertEquals(Phase.CANCELLED, Pipeline.step(st.next(), new BibleDone(false, null, 0, 0.3, "CANCELLED"), Permission.PROPOSALS).next().phase());
	}

	@Test
	void cancellingAPlacementWaitsForTheRollbackAndSaysWhatStayed() throws Exception {
		State ready = Pipeline.step(groupRunning(Permission.FULL), group("done", Map.of(), List.of(), 20), Permission.FULL).next();
		State placing = Pipeline.step(ready, new BatchQueued("b1"), Permission.FULL).next();
		Step st = Pipeline.step(placing, new Cancel(), Permission.FULL);
		assertEquals(Phase.CANCELLING, st.next().phase());
		Step done = Pipeline.step(st.next(), new BatchDone(2, 5), Permission.FULL);
		assertEquals(Phase.CANCELLED, done.next().phase());
		assertTrue(only(done, Notify.class).text().contains("2 buildings"));
		// before the queue's ack: cancelled on arrival by the adapter, the pipeline only waits
		only(Pipeline.step(ready, new Cancel(), Permission.FULL), CancelBatch.class);
	}

	@Test
	void aSecondCancelStopsWaitingAndConfirmationFinishes() throws Exception {
		State cancelling = Pipeline.step(groupRunning(Permission.PROPOSALS), new Cancel(), Permission.PROPOSALS).next();
		assertEquals(Phase.CANCELLED, Pipeline.step(cancelling, new Cancel(), Permission.PROPOSALS).next().phase());
		assertEquals(Phase.CANCELLED, Pipeline.step(cancelling, new CancelConfirmed(), Permission.PROPOSALS).next().phase());
		assertEquals(Pipeline.Decision.NONE, Pipeline.awaiting(cancelling));
	}

	@Test
	void aRaiseWhoseResumeFailsKeepsTheNewBudgetAndARaiseToItRetries() throws Exception {
		State paused = Pipeline.step(groupRunning(Permission.PROPOSALS), group("paused_budget", Map.of(), List.of(), 26), Permission.PROPOSALS).next();
		State raised = Pipeline.step(paused, new BudgetRaised(50), Permission.PROPOSALS).next();
		Step failed = Pipeline.step(raised, new BudgetRaiseFailed(35, "helper down", true), Permission.PROPOSALS);
		assertEquals(50.0, failed.next().budgetUsd(), "the group's cap was raised: the budget stands");
		assertEquals(Pipeline.Decision.BUDGET, Pipeline.awaiting(failed.next()));
		only(Pipeline.step(failed.next(), new BudgetRaised(50), Permission.PROPOSALS), ExtendAndResumeGroup.class);
	}
}
