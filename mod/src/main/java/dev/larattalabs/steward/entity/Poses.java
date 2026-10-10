// Ported from AgentCraft Worlds (AgentLife.pose/arms/sitLegs, the pose channels), MIT:
// Copyright (c) 2026 AgentCraft contributors; Copyright (c) 2026 Laratta Labs (AgentCraft Worlds changes). See assets-src/NOTICE.
package dev.larattalabs.steward.entity;

import java.util.Arrays;

/**
 * The steward's postures as pose channels (arm and leg rotations in radians, model space, a forward lean of the upper body around the hips, and the weights
 * that blend arms and legs over vanilla's walk). The server picks a {@link Posture}; the client eases its channels towards {@link #target} and the model applies
 * them. Pure.
 */
public final class Poses {
	public static final int P_RAX = 0, P_RAY = 1, P_RAZ = 2, P_LAX = 3, P_LAY = 4, P_LAZ = 5;
	public static final int P_RLX = 6, P_RLY = 7, P_RLZ = 8, P_LLX = 9, P_LLY = 10, P_LLZ = 11;
	public static final int P_LEAN = 12, P_ARMW = 13, P_LEGW = 14;
	public static final int N = 15;
	/** Pose smoothing per tick (the fraction of the remaining distance). */
	public static final float RATE = 0.28f;

	/** What the steward's body is doing. Appended only (synced by ordinal). */
	public enum Posture {
		/** Standing, arms easy. */
		IDLE,
		/** Walking: vanilla's swing, a slight lean. */
		WALK,
		/** At work: looking the building over, one hand forward. */
		REVIEW,
		/** A hand to the chin. */
		THINK,
		/** Waiting for the player: hands clasped in front. */
		WAIT,
		/** Talking: one hand out. */
		TALK,
		/** Sitting on a stair or slab. */
		SIT,
		/** Lying in a bed (the renderer lays the body down). */
		LIE;

		public static Posture of(int ordinal) {
			return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : IDLE;
		}
	}

	private Poses() {
	}

	/** The target channels of a posture. */
	public static float[] target(Posture p) {
		float[] t = new float[N];
		Arrays.fill(t, 0f);
		t[P_ARMW] = 1f;
		t[P_RAZ] = 0.05f;
		t[P_LAZ] = -0.05f;
		switch (p) {
			case WALK -> {
				t[P_ARMW] = 0f;
				t[P_LEAN] = 0.03f;
			}
			case IDLE -> {
			}
			case REVIEW -> {
				arms(t, -0.74f, -0.32f, 0f, -0.5f, 0.3f, 0f);
				t[P_LEAN] = 0.1f;
			}
			case THINK -> arms(t, -2.1f, -0.6f, 0f, -0.5f, 0.62f, 0f);
			case WAIT -> arms(t, -0.42f, -0.46f, 0f, -0.42f, 0.46f, 0f);
			case TALK -> arms(t, -0.7f, -0.22f, 0.08f, 0.02f, 0f, -0.05f);
			case SIT -> {
				arms(t, -0.55f, -0.16f, 0.05f, -0.55f, 0.16f, -0.05f);
				sitLegs(t);
			}
			case LIE -> arms(t, 0f, 0f, 0.08f, 0f, 0f, -0.08f);
		}
		return t;
	}

	/** One tick of easing {@code cur} towards {@code tgt}. */
	public static void ease(float[] cur, float[] tgt) {
		for (int i = 0; i < N; i++) cur[i] += (tgt[i] - cur[i]) * RATE;
	}

	private static void arms(float[] t, float rx, float ry, float rz, float lx, float ly, float lz) {
		t[P_RAX] = rx;
		t[P_RAY] = ry;
		t[P_RAZ] = rz;
		t[P_LAX] = lx;
		t[P_LAY] = ly;
		t[P_LAZ] = lz;
	}

	private static void sitLegs(float[] t) {
		t[P_RLX] = -1.34f;
		t[P_RLY] = 0.14f;
		t[P_RLZ] = 0.05f;
		t[P_LLX] = -1.34f;
		t[P_LLY] = -0.14f;
		t[P_LLZ] = -0.05f;
		t[P_LEGW] = 1f;
	}
}
