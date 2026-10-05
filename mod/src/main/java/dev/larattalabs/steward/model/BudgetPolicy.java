package dev.larattalabs.steward.model;

/**
 * Cost and time expectations for generating a settlement, from Architect's real 4b gate run (architect-mc artifacts/gate4b-real/REPORT.md,
 * seeds in its commit aa7837e). These are FALLBACK numbers only: once Architect's Java {@code Designs.estimate} / {@code Bibles.estimate}
 * exist (4b Java side), the UI uses those and this class just supplies defaults and the soft-budget threshold.
 *
 * <p>Seeds: a bible $1.2-2.0 and 5-8 min; an Opus design (landmark) $2.0-3.2 and 8-13 min; a Sonnet design $0.8-2.5 and 4-10 min, run in waves of 3.
 */
public final class BudgetPolicy {
	public static final double BIBLE_LOW = 1.2, BIBLE_HIGH = 2.0;
	public static final double LANDMARK_LOW = 2.0, LANDMARK_HIGH = 3.2;
	public static final double ORDINARY_LOW = 0.8, ORDINARY_HIGH = 2.5;
	public static final int BIBLE_MIN_LOW = 5, BIBLE_MIN_HIGH = 8;
	public static final int LANDMARK_MIN_LOW = 8, LANDMARK_MIN_HIGH = 13;
	public static final int ORDINARY_MIN_LOW = 4, ORDINARY_MIN_HIGH = 10;
	public static final int WAVE_SIZE = 3;
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
		double low = BIBLE_LOW + l * LANDMARK_LOW + ordinary * ORDINARY_LOW;
		double high = BIBLE_HIGH + l * LANDMARK_HIGH + ordinary * ORDINARY_HIGH;
		int minLow = BIBLE_MIN_LOW + (l > 0 ? LANDMARK_MIN_LOW : 0) + waves * ORDINARY_MIN_LOW;
		int minHigh = BIBLE_MIN_HIGH + (l > 0 ? LANDMARK_MIN_HIGH : 0) + waves * ORDINARY_MIN_HIGH;
		return new Estimate(round1(low), round1(high), minLow, minHigh);
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
