package dev.larattalabs.steward.model;

/** How materials are paid for. Maps onto Architect's placement modes (docs/PLAN.md "Permission levels and difficulty"). */
public enum Difficulty {
	/** Free, instant builds. Only where the world allows it (never bypasses a survival world's toggle). */
	PATRON,
	/** Builds from a stockpile; the steward requests materials (Architect construction sites). */
	SUPPLIED,
	/** The player gathers everything. */
	HARDCORE,
	/** Resources are spent to unlock modules and tiers. */
	ECONOMY;

	public static Difficulty parse(String s) {
		return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
	}

	/**
	 * The difficulty a settlement gets in a world whose survival toggle is {@code worldSurvival}: in survival nothing is built free, so Patron becomes Supplied
	 * (docs/PLAN.md phase 4, Noah 2026-10-11); elsewhere what was asked.
	 */
	public static Difficulty forWorld(Difficulty asked, boolean worldSurvival) {
		return worldSurvival && asked == PATRON ? SUPPLIED : asked;
	}

	/** Whether this difficulty may use instant placement in a world whose survival toggle is {@code worldSurvival}. */
	public boolean instantAllowed(boolean worldSurvival) {
		return this == PATRON && !worldSurvival;
	}
}
