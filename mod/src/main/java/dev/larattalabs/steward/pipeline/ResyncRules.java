package dev.larattalabs.steward.pipeline;

import java.util.List;

/**
 * What a build restored after a restart does first, from what Steward saved and what Architect kept. Pure, so every branch is tested; the runner gathers the
 * inputs and carries the action out.
 *
 * <p>What Architect keeps across a restart (its liaison, 2026-10-09): design groups and their results (and GROUP_DONE / BIBLE_DONE catch up); queued and
 * running batches, under the same id, with their planned stages; site groups and their stages; placed sites with their batch id, item key and ext. Finished
 * batches are not kept, so a placement that finished while the world was closing is read back from the sites.
 */
public final class ResyncRules {
	private ResyncRules() {
	}

	public enum BatchSeen { ABSENT, RUNNING, FINISHED }

	public enum Action {
		/** Nothing to do: wait for events (or for the player). */
		NONE,
		/** Re-read the design group and feed it to the pipeline. */
		REREAD_GROUP,
		/** The batch is still queued or placing: keep waiting for it. */
		WAIT_FOR_BATCH,
		/** A batch of this build is running though its id was never saved: adopt it. */
		ADOPT_RUNNING_BATCH,
		/** The batch finished and Architect still has it: finish from it. */
		FINISH_FROM_BATCH,
		/** The batch finished and is gone: count this build's placed sites and finish. */
		FINISH_FROM_SITES,
		/** Placement never reached Architect: fit and queue again. */
		REQUEUE,
		/** An id the build needs was never saved (the restart came between a request and its ack): the player cancels and starts again. */
		INTERRUPTED
	}

	/**
	 * @param batch the saved batch as Architect has it now (ABSENT when no batch id was saved, or Architect no longer has it)
	 * @param ownerBatchRunning a running batch of this build exists (looked for when no batch id was saved)
	 * @param stages the states of the build's site-group stages (empty when the group is unknown)
	 * @param placedSites sites this build placed (tagged with its build id)
	 */
	public static Action decide(Pipeline.Phase phase, boolean hasBibleJob, boolean hasGroup, boolean hasBatch, BatchSeen batch, boolean ownerBatchRunning,
		List<StageSeen> stages, int placedSites) {
		return switch (phase) {
			case BIBLE_RUNNING -> hasBibleJob ? Action.NONE : Action.INTERRUPTED;
			case GROUP_RUNNING, AWAITING_MASSING_APPROVAL -> hasGroup ? Action.REREAD_GROUP : Action.INTERRUPTED;
			case READY_TO_PLACE -> {
				if (ownerBatchRunning) yield Action.ADOPT_RUNNING_BATCH;
				// the queue call got through and the batch finished before the restart: never place a second copy
				if (placedSites > 0) yield Action.FINISH_FROM_SITES;
				yield Action.REQUEUE;
			}
			case AWAITING_PLACEMENT_APPROVAL, PLACING -> {
				if (batch == BatchSeen.RUNNING) yield Action.WAIT_FOR_BATCH;
				if (batch == BatchSeen.FINISHED) yield Action.FINISH_FROM_BATCH;
				if (!hasBatch) yield ownerBatchRunning ? Action.ADOPT_RUNNING_BATCH : Action.INTERRUPTED;
				// the batch is gone: done only when every stage of the build is finished (or no stage is known and sites were placed)
				if (!stages.isEmpty()) yield stages.stream().allMatch(StageSeen::terminal) ? Action.FINISH_FROM_SITES : Action.INTERRUPTED;
				yield placedSites > 0 ? Action.FINISH_FROM_SITES : Action.INTERRUPTED;
			}
			default -> Action.NONE;
		};
	}

	/** A stage's state as Steward needs it: finished or not. */
	public record StageSeen(String name, boolean terminal) {}
}
