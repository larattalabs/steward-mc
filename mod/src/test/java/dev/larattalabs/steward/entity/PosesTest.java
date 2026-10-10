package dev.larattalabs.steward.entity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PosesTest {
	@Test
	void walkingLeavesTheArmsToVanillaAndSittingBendsTheLegs() {
		assertEquals(0f, Poses.target(Poses.Posture.WALK)[Poses.P_ARMW], "the walk keeps vanilla's swing");
		float[] sit = Poses.target(Poses.Posture.SIT);
		assertEquals(1f, sit[Poses.P_LEGW]);
		assertTrue(sit[Poses.P_RLX] < -1f && sit[Poses.P_LLX] < -1f, "legs forward");
		assertEquals(0f, Poses.target(Poses.Posture.IDLE)[Poses.P_LEGW], "standing legs are vanilla's");
		assertTrue(Poses.target(Poses.Posture.THINK)[Poses.P_RAX] < -2f, "a hand to the chin");
	}

	@Test
	void easingConvergesOnTheTarget() {
		float[] cur = new float[Poses.N];
		float[] t = Poses.target(Poses.Posture.REVIEW);
		for (int i = 0; i < 60; i++) Poses.ease(cur, t);
		for (int i = 0; i < Poses.N; i++) assertEquals(t[i], cur[i], 1e-3);
	}

	@Test
	void anUnknownPostureReadsAsIdle() {
		assertEquals(Poses.Posture.IDLE, Poses.Posture.of(-1));
		assertEquals(Poses.Posture.IDLE, Poses.Posture.of(99));
		assertEquals(Poses.Posture.LIE, Poses.Posture.of(Poses.Posture.LIE.ordinal()));
	}
}
