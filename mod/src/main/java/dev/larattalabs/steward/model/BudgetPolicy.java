package dev.larattalabs.steward.model;

/**
 * Cost and time expectations for generating a settlement, from Architect's real 4b gate run (architect-mc artifacts/gate4b-real/REPORT.md,
 * seeds in its commit aa7837e). These are FALLBACK numbers only: once Architect's Java {@code Designs.estimate} / {@code Bibles.estimate}
 * exist (4b Java side), the UI uses those and this class just supplies defaults and the soft-budget threshold.
 *
 * <p>Seeds: a bible $1.2-2.0 and 5-8 min; an Opus design (landmark) $2.0-3.2 and 8-13 min; a Sonnet design $0.8-2.5 and 4-10 min, run in waves of 3.
 *
 * <p>Re-measured in Steward's phase 1 gate run (2026-10-09, a massing-first group with report critiques, "Stilt Swamp Fishing Village", 8 buildings): bibles $1.16-1.55,
 * massings $0.19 each, the landmark's detail $3.72, the ordinary details $2.50-4.60 (report critique included). The ranges below cover both measurements; the design
 * figures now include the report critique, so {@link #estimateWithCritiqueReports} adds nothing on top.
 */
public final class BudgetPolicy {
	public static final double BIBLE_LOW = 1.2, BIBLE_HIGH = 2.0;
	public static final double LANDMARK_LOW = 3.0, LANDMARK_HIGH = 4.5;
	public static final double ORDINARY_LOW = 2.0, ORDINARY_HIGH = 4.6;
	/** A massing per building (massing-first groups). */
	public static final double MASSING_LOW = 0.15, MASSING_HIGH = 0.25;
	public static final int BIBLE_MIN_LOW = 5, BIBLE_MIN_HIGH = 8;
	public static final int LANDMARK_MIN_LOW = 8, LANDMARK_MIN_HIGH = 13;
	public static final int ORDINARY_MIN_LOW = 4, ORDINARY_MIN_HIGH = 10;
	public static final int WAVE_SIZE = 3;
	/** A report-only critique per design (Architect 0.9.0 seeds: $0.05-0.15, about 0.5-2 min, run inside the design's slot). The revision loop is not planned for. */
	public static final double CRITIQUE_REPORT_LOW = 0.05, CRITIQUE_REPORT_HIGH = 0.15;
	/** Fraction of the budget at which the group pauses and asks (soft budget; the hard cap stays at 100%). */
	public static final double SOFT_FRACTION = 0.8;

	public record Estimate(double usdLow, double usdHigh, int minutesLow, int minutesHigh) {}

	private BudgetPolicy() {
	}

	/** One bible, {@code landmarks} Opus designs (one anchor wave), and the rest Sonnet in waves of {@link #WAVE_SIZE}. */
	public static Estimate estimate(int buildings, int landmarks) {
		if (buildings < 1) throw new IllegalArgumentException("buildings must be at least 1");
		int l = Math.max(0, Math.min(landmarks, buildings));
		int ordinary = buildings - l;
		int waves = (ordinary + WAVE_SIZE - 1) / WAVE_SIZE;
		double low = BIBLE_LOW + l * LANDMARK_LOW + ordinary * ORDINARY_LOW + buildings * MASSING_LOW;
		double high = BIBLE_HIGH + l * LANDMARK_HIGH + ordinary * ORDINARY_HIGH + buildings * MASSING_HIGH;
		int minLow = BIBLE_MIN_LOW + (l > 0 ? LANDMARK_MIN_LOW : 0) + waves * ORDINARY_MIN_LOW;
		int minHigh = BIBLE_MIN_HIGH + (l > 0 ? LANDMARK_MIN_HIGH : 0) + waves * ORDINARY_MIN_HIGH;
		return new Estimate(round1(low), round1(high), minLow, minHigh);
	}

	/** {@link #estimate(int, int)} with a report-only critique on every building: the same, since the measured design figures include the report (2026-10-09). */
	public static Estimate estimateWithCritiqueReports(int buildings, int landmarks) {
		return estimate(buildings, landmarks);
	}

	/** Typical building and landmark counts per settlement size (S, M, L, XL). */
	public static int typicalBuildings(String size) {
		return switch (size == null ? "M" : size) {
			case "S" -> 6;
			case "L" -> 20;
			case "XL" -> 35;
			default -> 12;
		};
	}

	public static int typicalLandmarks(String size) {
		return switch (size == null ? "M" : size) {
			case "S" -> 1;
			case "L" -> 3;
			case "XL" -> 4;
			default -> 2;
		};
	}

	/**
	 * How many landmarks a build of {@code buildings} gets (the dearer model): the program's landmark flags, at most the size's typical count and one per
	 * four buildings. One rule for the runner, the start hint and the card screen, so they never show different estimates.
	 */
	public static int landmarksFor(String size, int buildings, int flagged) {
		return Math.max(0, Math.min(flagged, maxLandmarks(size, buildings)));
	}

	/** The cap {@link #landmarksFor} applies: the size's typical count, at most one per four buildings (at least one). */
	public static int maxLandmarks(String size, int buildings) {
		return Math.min(typicalLandmarks(size), Math.max(1, buildings / 4));
	}

	/** A default hard budget for a size: the high estimate rounded up to the next $5. The player can change it. */
	public static double suggestedBudgetUsd(String size) {
		Estimate e = estimate(typicalBuildings(size), typicalLandmarks(size));
		return Math.ceil(e.usdHigh() / 5.0) * 5.0;
	}

	/** Whether a group that has spent {@code spent} of {@code budget} should pause for the player (soft budget). */
	public static boolean shouldPause(double spent, double budget) {
		return budget > 0 && spent >= budget * SOFT_FRACTION;
	}

	private static double round1(double v) {
		return Math.round(v * 10.0) / 10.0;
	}
}
