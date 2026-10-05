package dev.larattalabs.steward.model;

/** What the steward may do without asking (docs/PLAN.md). Every level keeps the change log and undo. */
public enum Permission {
	OBSERVER, PROPOSALS, AUTONOMOUS, FULL;

	public enum Action { NEW_PROJECT, UPGRADE, NEW_DISTRICT, DEMOLISH, SPEND }

	/** Whether {@code action} needs the player's approval at this level. */
	public boolean needsApproval(Action action) {
		return switch (this) {
			case OBSERVER -> true;
			case PROPOSALS -> true;
			case AUTONOMOUS -> action == Action.DEMOLISH || action == Action.NEW_DISTRICT;
			case FULL -> false;
		};
	}
}
