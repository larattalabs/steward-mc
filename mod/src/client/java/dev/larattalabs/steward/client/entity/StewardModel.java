// Ported from AgentCraft Worlds (AgentModel), MIT:
// Copyright (c) 2026 AgentCraft contributors; Copyright (c) 2026 Laratta Labs (AgentCraft Worlds changes). See assets-src/NOTICE.
package dev.larattalabs.steward.client.entity;

import dev.larattalabs.steward.entity.Poses;
import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * The player-shaped model plus the steward's postures: after vanilla's setup (walk swing, head look) the pose channels replace the arm and leg rotations,
 * blended by their weights so walking keeps vanilla's swing, and lean the upper body forward around the hips. The skin's outer layer parts are children of
 * the base parts, so they follow.
 */
public final class StewardModel extends HumanoidModel<StewardRenderState> {
	public StewardModel(ModelPart root) {
		super(root);
	}

	@Override
	public void setupAnim(StewardRenderState s) {
		super.setupAnim(s);
		float[] p = s.channels;
		float aw = Mth.clamp(p[Poses.P_ARMW], 0f, 1f);
		if (aw > 0.001f) {
			rightArm.xRot = Mth.lerp(aw, rightArm.xRot, p[Poses.P_RAX]);
			rightArm.yRot = Mth.lerp(aw, rightArm.yRot, p[Poses.P_RAY]);
			rightArm.zRot = Mth.lerp(aw, rightArm.zRot, p[Poses.P_RAZ]);
			leftArm.xRot = Mth.lerp(aw, leftArm.xRot, p[Poses.P_LAX]);
			leftArm.yRot = Mth.lerp(aw, leftArm.yRot, p[Poses.P_LAY]);
			leftArm.zRot = Mth.lerp(aw, leftArm.zRot, p[Poses.P_LAZ]);
			// vanilla's gentle breathing sway on top of the posture
			AnimationUtils.bobModelPart(rightArm, s.ageInTicks, 1.0F);
			AnimationUtils.bobModelPart(leftArm, s.ageInTicks, -1.0F);
		}
		float lw = Mth.clamp(p[Poses.P_LEGW], 0f, 1f);
		if (lw > 0.001f) {
			rightLeg.xRot = Mth.lerp(lw, rightLeg.xRot, p[Poses.P_RLX]);
			rightLeg.yRot = Mth.lerp(lw, rightLeg.yRot, p[Poses.P_RLY]);
			rightLeg.zRot = Mth.lerp(lw, rightLeg.zRot, p[Poses.P_RLZ]);
			leftLeg.xRot = Mth.lerp(lw, leftLeg.xRot, p[Poses.P_LLX]);
			leftLeg.yRot = Mth.lerp(lw, leftLeg.yRot, p[Poses.P_LLY]);
			leftLeg.zRot = Mth.lerp(lw, leftLeg.zRot, p[Poses.P_LLZ]);
		}
		float lean = p[Poses.P_LEAN];
		if (Math.abs(lean) > 0.001f) lean(lean);
	}

	/** Rotates the upper body by {@code a} radians around the hip point (model px, -z forward): the torso tilts, the neck and shoulders move with it. */
	private void lean(float a) {
		float c = Mth.cos(a);
		float sn = Mth.sin(a);
		body.xRot += a;
		body.y += 12 - 12 * c;
		body.z += -12 * sn;
		head.y += 12 - 12 * c;
		head.z += -12 * sn;
		rightArm.y += 10 - 10 * c;
		rightArm.z += -10 * sn;
		rightArm.xRot += a * 0.6f;
		leftArm.y += 10 - 10 * c;
		leftArm.z += -10 * sn;
		leftArm.xRot += a * 0.6f;
	}
}
