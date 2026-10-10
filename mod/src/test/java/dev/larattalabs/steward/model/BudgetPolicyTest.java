package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BudgetPolicyTest {
	@Test
	void aTwelveBuildingSettlementEstimate() {
		BudgetPolicy.Estimate e = BudgetPolicy.estimate(12, 2);
		assertEquals(1.2 + 2 * 3.0 + 10 * 2.0 + 12 * 0.15, e.usdLow(), 0.01);
		assertEquals(2.0 + 2 * 4.5 + 10 * 4.6 + 12 * 0.25, e.usdHigh(), 0.01);
		assertEquals(29, e.minutesLow());
		assertEquals(61, e.minutesHigh());
	}

	@Test
	void theMeasuredRunsFitInsideTheEstimate() {
		// phase 1 gate run (2026-10-09): bible $1.16, 8 massings $1.51, details $28.35 (one landmark): $31.02
		BudgetPolicy.Estimate e = BudgetPolicy.estimate(8, 1);
		assertTrue(e.usdLow() <= 31.02 && 31.02 <= e.usdHigh(), e.toString());
		// the first live village (2026-10-09, no landmark): 4 buildings, $12.3 with its bible
		BudgetPolicy.Estimate v = BudgetPolicy.estimate(4, 0);
		assertTrue(v.usdLow() <= 12.3 && 12.3 <= v.usdHigh(), v.toString());
	}

	@Test
	void suggestedBudgetsGrowWithSizeAndAreRoundedToFiveDollars() {
		double s = BudgetPolicy.suggestedBudgetUsd("S");
		double m = BudgetPolicy.suggestedBudgetUsd("M");
		double l = BudgetPolicy.suggestedBudgetUsd("L");
		double xl = BudgetPolicy.suggestedBudgetUsd("XL");
		assertTrue(s < m && m < l && l < xl);
		for (double v : new double[] {s, m, l, xl}) assertEquals(0.0, v % 5.0, 1e-9);
		assertEquals(35.0, s, 1e-9);
		assertEquals(60.0, m, 1e-9);
		assertTrue(l >= 90.0, "an L settlement costs far more than the old $20 default");
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

	@Test
	void reportCritiquesAreInsideTheMeasuredDesignCost() {
		assertEquals(BudgetPolicy.estimate(12, 2), BudgetPolicy.estimateWithCritiqueReports(12, 2));
	}
}
