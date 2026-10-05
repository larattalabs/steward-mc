package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BudgetPolicyTest {
	@Test
	void aTwelveBuildingSettlementMatchesArchitectsMeasuredRange() {
		BudgetPolicy.Estimate e = BudgetPolicy.estimate(12, 2);
		assertEquals(13.2, e.usdLow(), 0.01);
		assertEquals(33.4, e.usdHigh(), 0.01);
		assertEquals(29, e.minutesLow());
		assertEquals(61, e.minutesHigh());
	}

	@Test
	void theGateRunSetFitsInsideTheEstimate() {
		// the measured set: a bible $1.40, one Opus anchor $3.06, two Sonnet designs $2.45 and $1.17: total $8.08 (without the bible's job overhead)
		BudgetPolicy.Estimate e = BudgetPolicy.estimate(3, 1);
		assertTrue(e.usdLow() <= 8.08 && 8.08 <= e.usdHigh(), e.toString());
	}

	@Test
	void suggestedBudgetsGrowWithSizeAndAreRoundedToFiveDollars() {
		double s = BudgetPolicy.suggestedBudgetUsd("S");
		double m = BudgetPolicy.suggestedBudgetUsd("M");
		double l = BudgetPolicy.suggestedBudgetUsd("L");
		double xl = BudgetPolicy.suggestedBudgetUsd("XL");
		assertTrue(s < m && m < l && l < xl);
		for (double v : new double[] {s, m, l, xl}) assertEquals(0.0, v % 5.0, 1e-9);
		assertEquals(20.0, s, 1e-9);
		assertEquals(35.0, m, 1e-9);
		assertTrue(l >= 50.0, "an L settlement needs more than the old $20 default");
	}

	@Test
	void unknownOrMissingSizeIsMedium() {
		assertEquals(BudgetPolicy.suggestedBudgetUsd("M"), BudgetPolicy.suggestedBudgetUsd(null));
		assertEquals(BudgetPolicy.suggestedBudgetUsd("M"), BudgetPolicy.suggestedBudgetUsd("huge"));
	}

	@Test
	void softBudgetPausesAtEightyPercent() {
		assertFalse(BudgetPolicy.shouldPause(15.9, 20));
		assertTrue(BudgetPolicy.shouldPause(16.0, 20));
		assertFalse(BudgetPolicy.shouldPause(5, 0), "no budget means no pause");
	}

	@Test
	void landmarksAreClampedAndBuildingsMustBePositive() {
		assertEquals(BudgetPolicy.estimate(3, 3), BudgetPolicy.estimate(3, 9));
		assertThrows(IllegalArgumentException.class, () -> BudgetPolicy.estimate(0, 0));
	}
}
