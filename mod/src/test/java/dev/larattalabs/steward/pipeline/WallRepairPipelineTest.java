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

/** (Architect K3) a wall lot's repair at the bind, as the pipeline owns it: one rule a case, each a way the repair could escape its owner. */
class WallRepairPipelineTest {
	private static final Permission P = Permission.PROPOSALS;
	private static final String KEY = "steward.b1.repair.gate_1.1";

	private static State placing(double spent, double budget) throws Exception {
		var in = WallRepairPipelineTest.class.getClassLoader().getResourceAsStream("crater_works.json");
		ConceptCard c = ConceptCard.parse(JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
		return State.start("set_1", c, budget).withSpent(spent).with(Phase.PLACING);
	}

	private static Step step(State s, Event e) {
		return Pipeline.step(s, e, P);
	}

	private static State refused(State s, String key) {
		return step(s, new WallRefused("gate_1", "gatehouse", "the design leaves the wall open at 1,64,2 (3 cells)", true, key)).next();
	}

	@Test
	void aRefusedWallLotIsRepairedWithItsMostReserved() throws Exception {
		var st = step(placing(5, 35), new WallRefused("gate_1", "gatehouse", "open at 1,64,2", true, KEY));
		assertEquals(Phase.REPAIRING, st.next().phase());
		assertEquals(5 + Pipeline.WALL_REPAIR_USD, st.next().spentUsd(), 1e-9);
		assertEquals(1, st.next().repairs().get("gate_1"));
		assertEquals(KEY, st.next().repair().key());
		assertTrue(st.commands().contains(new AskRepair("gate_1", KEY, "open at 1,64,2")));
	}

	@Test
	void doneItIsPlacedAgainWithItsCostTruedUp() throws Exception {
		var s = refused(placing(5, 35), KEY);
		s = step(s, new RepairAsked(KEY, "d7")).next();
		assertEquals("d7", s.repair().designId());
		var done = step(s, new RepairEnded(KEY, RepairEnd.DONE, 0.8, null));
		assertEquals(Phase.PLACING, done.next().phase());
		assertNull(done.next().repair());
		assertEquals(5.8, done.next().spentUsd(), 1e-9, "its reservation trued up to what it cost");
		assertTrue(done.commands().contains(new Rebind()));
	}

	@Test
	void failedRefusedOrLostTheBuildStopsNamingTheLot() throws Exception {
		var failed = step(refused(placing(5, 35), KEY), new RepairEnded(KEY, RepairEnd.FAILED, 1.1, "the critic gave up"));
		assertEquals(Phase.FAILED, failed.next().phase());
		assertTrue(failed.next().failure().contains("gatehouse on the wall"));
		assertEquals(6.1, failed.next().spentUsd(), 1e-9);
		var refused = step(refused(placing(5, 35), KEY), new RepairEnded(KEY, RepairEnd.REFUSED, 0, "VERSION_REFUSED"));
		assertEquals(5, refused.next().spentUsd(), 1e-9, "nothing designed: the reservation given back");
		var lost = step(refused(placing(5, 35), KEY), new RepairEnded(KEY, RepairEnd.LOST, 0, "its answer was lost"));
		assertEquals(Phase.FAILED, lost.next().phase());
		assertEquals(7, lost.next().spentUsd(), 1e-9, "its most stays counted");
	}

	@Test
	void pastItsRoundsItsBudgetOrWithNoDesignTheBuildStops() throws Exception {
		var s = refused(placing(5, 35), KEY);
		s = step(s, new RepairEnded(KEY, RepairEnd.DONE, 1, null)).next();
		s = refused(s, "steward.b1.repair.gate_1.2");
		s = step(s, new RepairEnded("steward.b1.repair.gate_1.2", RepairEnd.DONE, 1, null)).next();
		var third = step(s, new WallRefused("gate_1", "gatehouse", "still open", true, "steward.b1.repair.gate_1.3"));
		assertEquals(Phase.FAILED, third.next().phase());
		assertTrue(third.next().failure().contains("after 2 repairs"));
		assertEquals(Phase.FAILED, refused(placing(34, 35), KEY).phase(), "past the budget");
		assertEquals(Phase.FAILED, step(placing(5, 35), new WallRefused("gate_1", "gatehouse", "open", false, KEY)).next().phase(), "no design to repair");
	}

	@Test
	void whileItIsDesignedNothingElseEndsTheBuild() throws Exception {
		var s = refused(placing(5, 35), KEY);
		assertEquals(Phase.REPAIRING, step(s, new BatchDone(3, 0, null)).next().phase(), "never built while a repair is outstanding");
		assertEquals(Phase.REPAIRING, step(s, new RepairEnded("another.key", RepairEnd.DONE, 1, null)).next().phase(), "another key is not its end");
	}

	@Test
	void aCancelWaitsForTheRepairToStop() throws Exception {
		var s = step(refused(placing(5, 35), KEY), new RepairAsked(KEY, "d7")).next();
		var c = step(s, new Cancel());
		assertEquals(Phase.CANCELLING, c.next().phase());
		assertTrue(c.next().repair().stopping());
		assertTrue(c.commands().contains(new StopRepair(KEY, "d7")));
		s = c.next();
		// the original group's, the batch's or the region's word does not end it while the repair is owned
		assertEquals(Phase.CANCELLING, step(s, new GroupUpdate("g1", "done", Map.of(), List.of(), 3, null)).next().phase());
		assertEquals(Phase.CANCELLING, step(s, new BatchDone(0, 0, null)).next().phase());
		assertEquals(Phase.CANCELLING, step(s, new CancelConfirmed()).next().phase());
		// a second cancel asks it to stop again and keeps owning it
		var again = step(s, new Cancel());
		assertEquals(Phase.CANCELLING, again.next().phase());
		assertTrue(again.commands().contains(new StopRepair(KEY, "d7")));
		// stopped: cancelled, its cost counted
		var stopped = step(s, new RepairEnded(KEY, RepairEnd.FAILED, 0.4, "cancelled"));
		assertEquals(Phase.CANCELLED, stopped.next().phase());
		assertEquals(5.4, stopped.next().spentUsd(), 1e-9);
	}

	@Test
	void aCancelBeforeArchitectAnsweredStopsItOnceItsDesignIsKnown() throws Exception {
		var s = step(refused(placing(5, 35), KEY), new Cancel()).next();
		assertEquals(new StopRepair(KEY, null), step(refused(placing(5, 35), KEY), new Cancel()).commands().get(0));
		var known = step(s, new RepairAsked(KEY, "d9"));
		assertTrue(known.commands().contains(new StopRepair(KEY, "d9")));
	}

	@Test
	void aStateSavedBeforeRepairsHasNone() throws Exception {
		var old = new State(Phase.PLACING, "set_1", null, null, null, null, Map.of(), 0, 0, 35, false, null, null);
		assertNull(old.repair());
		assertEquals(Map.of(), old.repairs());
	}
}
