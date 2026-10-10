package dev.larattalabs.steward.pipeline;

import static dev.larattalabs.steward.pipeline.ResyncRules.Action.*;
import static dev.larattalabs.steward.pipeline.ResyncRules.BatchSeen.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.pipeline.Pipeline.Phase;
import dev.larattalabs.steward.pipeline.ResyncRules.StageSeen;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResyncRulesTest {
	private static final List<StageSeen> NO_STAGES = List.of();

	@Test
	void designPhasesRereadOrReportAnInterruptedRequest() {
		assertEquals(ResyncRules.Action.REREAD_BIBLE, ResyncRules.decide(Phase.BIBLE_RUNNING, true, false, false, ResyncRules.BatchSeen.ABSENT, false, NO_STAGES, 0),
			"the job is read again: its DONE event may already have been delivered");
		assertEquals(ResyncRules.Action.NONE, ResyncRules.decide(Phase.GROUP_RUNNING, true, false, false, ResyncRules.BatchSeen.ABSENT, false, NO_STAGES, 0, true),
			"paused before the group was requested: wait for the raise");
		assertEquals(ResyncRules.Action.RESUME_CANCEL, ResyncRules.decide(Phase.CANCELLING, false, true, true, ResyncRules.BatchSeen.RUNNING, false, NO_STAGES, 0));
		assertEquals(INTERRUPTED, ResyncRules.decide(Phase.BIBLE_RUNNING, false, false, false, ResyncRules.BatchSeen.ABSENT, false, NO_STAGES, 0));
		assertEquals(REREAD_GROUP, ResyncRules.decide(Phase.AWAITING_MASSING_APPROVAL, true, true, false, ResyncRules.BatchSeen.ABSENT, false, NO_STAGES, 0));
		assertEquals(INTERRUPTED, ResyncRules.decide(Phase.GROUP_RUNNING, true, false, false, ResyncRules.BatchSeen.ABSENT, false, NO_STAGES, 0));
		assertEquals(ResyncRules.Action.NONE, ResyncRules.decide(Phase.AWAITING_BIBLE_APPROVAL, true, false, false, ResyncRules.BatchSeen.ABSENT, false, NO_STAGES, 0));
	}

	@Test
	void readyToPlaceNeverQueuesASecondCopy() {
		assertEquals(ADOPT_RUNNING_BATCH, ResyncRules.decide(Phase.READY_TO_PLACE, true, true, false, ResyncRules.BatchSeen.ABSENT, true, NO_STAGES, 0));
		assertEquals(FINISH_FROM_SITES, ResyncRules.decide(Phase.READY_TO_PLACE, true, true, false, ResyncRules.BatchSeen.ABSENT, false, NO_STAGES, 3), "the batch got through and finished");
		assertEquals(REQUEUE, ResyncRules.decide(Phase.READY_TO_PLACE, true, true, false, ResyncRules.BatchSeen.ABSENT, false, NO_STAGES, 0));
	}

	@Test
	void placementWaitsWhileArchitectStillHasTheBatch() {
		assertEquals(WAIT_FOR_BATCH, ResyncRules.decide(Phase.AWAITING_PLACEMENT_APPROVAL, true, true, true, RUNNING, true, List.of(new StageSeen("landmarks", false)), 0));
		assertEquals(WAIT_FOR_BATCH, ResyncRules.decide(Phase.PLACING, true, true, true, RUNNING, true, NO_STAGES, 2));
		assertEquals(FINISH_FROM_BATCH, ResyncRules.decide(Phase.PLACING, true, true, true, FINISHED, false, NO_STAGES, 6));
	}

	@Test
	void aGoneBatchIsDoneOnlyWhenEveryStageIsFinished() {
		List<StageSeen> done = List.of(new StageSeen("street", true), new StageSeen("landmarks", true), new StageSeen("district_1", true));
		List<StageSeen> half = List.of(new StageSeen("landmarks", true), new StageSeen("district_1", false));
		assertEquals(FINISH_FROM_SITES, ResyncRules.decide(Phase.PLACING, true, true, true, ResyncRules.BatchSeen.ABSENT, false, done, 6));
		assertEquals(INTERRUPTED, ResyncRules.decide(Phase.PLACING, true, true, true, ResyncRules.BatchSeen.ABSENT, false, half, 3), "a planned stage nobody will place");
		assertEquals(FINISH_FROM_SITES, ResyncRules.decide(Phase.PLACING, true, true, true, ResyncRules.BatchSeen.ABSENT, false, NO_STAGES, 4));
		assertEquals(INTERRUPTED, ResyncRules.decide(Phase.PLACING, true, true, true, ResyncRules.BatchSeen.ABSENT, false, NO_STAGES, 0));
		assertEquals(ADOPT_RUNNING_BATCH, ResyncRules.decide(Phase.AWAITING_PLACEMENT_APPROVAL, true, true, false, ResyncRules.BatchSeen.ABSENT, true, NO_STAGES, 0));
	}

	@Test
	void finishedBuildsAreLeftAlone() {
		for (Phase p : List.of(Phase.DONE, Phase.FAILED, Phase.CANCELLED)) assertEquals(ResyncRules.Action.NONE, ResyncRules.decide(p, true, true, true, FINISHED, false, NO_STAGES, 9));
	}
}
