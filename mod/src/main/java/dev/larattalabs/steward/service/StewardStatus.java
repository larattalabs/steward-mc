package dev.larattalabs.steward.service;

import dev.larattalabs.steward.pipeline.Pipeline;

/**
 * What a settlement's steward shows on its nameplate: one short activity line and whether it needs the player (the pulsing "!"). From its build (the
 * phase, what waits), else its updates, else what it is doing ({@code idle}: resting, asleep, at work). Server thread.
 */
public final class StewardStatus {
	public record Status(String activity, boolean needsYou) {}

	private StewardStatus() {
	}

	/** {@code doing}: the steward's own state when no build or update speaks for it ("resting", "asleep", "looking it over", or ""). */
	public static Status of(String settlementId, String doing) {
		SettlementRunner r = SettlementRunner.active(settlementId);
		if (r != null && r.state() != null && !r.state().phase().terminal()) {
			var s = r.state();
			int waiting = (int) s.items().values().stream().filter("approval"::equals).count();
			return switch (Pipeline.awaiting(s)) {
				case BIBLE -> new Status("the style is ready for you", true);
				case MASSINGS -> new Status((waiting > 0 ? waiting : "the") + (waiting == 1 ? " massing waits" : " massings wait") + " for you", true);
				case BUDGET -> new Status("paused at its budget", true);
				case PLACEMENT -> new Status("ready to build", true);
				case NONE -> new Status(switch (s.phase()) {
					case AWAITING_CARD_APPROVAL -> "getting started";
					case BIBLE_RUNNING -> "drawing up the style";
					case GROUP_RUNNING -> "designing " + s.items().size() + " buildings";
					case READY_TO_PLACE -> "fitting the buildings";
					case PLACING -> "building";
					case CANCELLING -> "stopping the work";
					default -> doing;
				}, false);
			};
		}
		boolean update = Updates.pending(settlementId).stream().anyMatch(p -> p.action() == dev.larattalabs.steward.gateway.UpdatePlanner.Action.ASK);
		if (update) return new Status("an update waits for you", true);
		return new Status(doing, false);
	}
}
