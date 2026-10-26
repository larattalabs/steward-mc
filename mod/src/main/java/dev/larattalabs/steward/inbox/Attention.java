package dev.larattalabs.steward.inbox;

import dev.larattalabs.steward.pipeline.Pipeline;
import org.jspecify.annotations.Nullable;

/**
 * What a settlement asks of the player now (docs/PLAN.md, the shared Jobs & spend panel, J2): the one answer the inbox entry, the steward's nameplate and
 * the settlement board all show, so they never disagree. {@link Kind#NEEDS_YOU} only when something is blocked on the player (the nameplate's "!"); a
 * steward's idea never is (ideas show in the inbox and the shared panel). {@code limitResetsAt}: when the player's usage limit resets (epoch ms), while
 * the build is held by it, else 0. Pure.
 */
public record Attention(Kind kind, String line, long limitResetsAt) {
	public enum Kind {
		/** Nothing under way. */
		IDLE,
		/** A build is working, and nothing waits for the player. */
		WORKING,
		/** A build is held by the player's usage limit until {@code limitResetsAt}. */
		HELD,
		/** Something is blocked on the player: a decision of a build, a stage Architect holds, an update to approve. */
		NEEDS_YOU
	}

	public boolean needsYou() {
		return kind == Kind.NEEDS_YOU;
	}

	/**
	 * @param build the settlement's build, or null when none runs (an ended build counts as none)
	 * @param heldUntil when the build's usage-limit hold ends (epoch ms; 0 when not held)
	 * @param heldStage a stage of the build's region Architect holds for the player's word, or null
	 * @param updateAsks whether an update waits for the player's approval
	 * @param doing the steward's own state when nothing else speaks ("resting", "asleep", or "")
	 */
	public static Attention of(Pipeline.@Nullable State build, long heldUntil, @Nullable String heldStage, boolean updateAsks, String doing, long now) {
		if (build != null && !build.phase().terminal()) {
			int waiting = (int) build.items().values().stream().filter("approval"::equals).count();
			switch (Pipeline.awaiting(build)) {
				case BIBLE -> { return new Attention(Kind.NEEDS_YOU, "the style is ready for you", 0); }
				case MASSINGS -> { return new Attention(Kind.NEEDS_YOU, (waiting > 0 ? waiting : "the") + (waiting == 1 ? " massing waits" : " massings wait") + " for you", 0); }
				case BUDGET -> { return new Attention(Kind.NEEDS_YOU, "paused at its budget", 0); }
				case PLACEMENT -> { return new Attention(Kind.NEEDS_YOU, "ready to build", 0); }
				case NONE -> {
				}
			}
			if (heldStage != null) return new Attention(Kind.NEEDS_YOU, "Architect holds the " + heldStage + " stage for you", 0);
			if (heldUntil > now) return new Attention(Kind.HELD, "waiting for your usage limit", heldUntil);
			return new Attention(Kind.WORKING, switch (build.phase()) {
				case AWAITING_CARD_APPROVAL -> "getting started";
				case BIBLE_RUNNING -> "drawing up the style";
				case GROUP_RUNNING -> "designing " + build.items().size() + " buildings";
				case READY_TO_PLACE -> "fitting the buildings";
				case PLACING -> "building";
				case CANCELLING -> "stopping the work";
				default -> "at work";
			}, 0);
		}
		if (updateAsks) return new Attention(Kind.NEEDS_YOU, "an update waits for you", 0);
		return new Attention(Kind.IDLE, doing, 0);
	}
}
