package dev.larattalabs.steward.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.larattalabs.steward.entity.Poses;
import dev.larattalabs.steward.entity.StewardEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;

/**
 * The steward drawn as a player-shaped figure (the player model, so the skin's outer layer shows: the hat, the coat's hem) in its own skin, made by
 * assets-src (gen/chars/steward.py), in its posture: the server picks it, the entity eases the pose channels, the model applies them. Sitting lowers it onto
 * the seat; lying lays it along the bed (vanilla's sleeping pose, render only: the bed block is not touched). Its name shows above it. Client thread.
 */
public final class StewardRenderer extends HumanoidMobRenderer<StewardEntity, StewardRenderState, StewardModel> {
	private static final Identifier SKIN = dev.larattalabs.steward.Steward.id("textures/entity/steward.png");
	/** How far a sitting steward sinks so its hips rest on the seat (blocks). */
	static final float SIT_DROP = 0.62f;

	public StewardRenderer(EntityRendererProvider.Context ctx) {
		super(ctx, new StewardModel(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5F);
	}

	@Override
	public StewardRenderState createRenderState() {
		return new StewardRenderState();
	}

	@Override
	public void extractRenderState(StewardEntity e, StewardRenderState s, float partial) {
		super.extractRenderState(e, s, partial);
		for (int i = 0; i < Poses.N; i++) s.channels[i] = Mth.lerp(partial, e.posePrev[i], e.pose[i]);
		s.posture = e.posture();
		s.activity = e.activity();
		s.needsYou = e.needsYou();
		s.bubble = StewardVoices.layout(e.settlementId());
		s.bubbleVisibility = s.bubble == null ? 0f : StewardVoices.visibility(e.settlementId(), partial);
		var bed = e.bedFacing();
		if (s.posture == Poses.Posture.LIE && bed != null) {
			s.pose = Pose.SLEEPING;
			// the body is laid from the entity's position (the foot half, see StewardEntity.settle) towards the bed's facing: the head on the pillow
			s.bedOrientation = bed;
			// vanilla moves a sleeper back along the bed by its eye height less 0.1: 0.4 back puts the head on the pillow, the boots at the foot (from above, e2e)
			s.eyeHeight = 0.5f;
		}
	}

	@Override
	protected void setupRotations(StewardRenderState s, PoseStack stack, float bodyRot, float scale) {
		super.setupRotations(s, stack, bodyRot, scale);
		if (s.posture == Poses.Posture.SIT) stack.translate(0, -SIT_DROP, 0);
	}

	/** Its own nameplate stack (plate, speech bubble, "!") instead of vanilla's name tag. */
	@Override
	protected void submitNameDisplay(StewardRenderState s, PoseStack stack, net.minecraft.client.renderer.SubmitNodeCollector c,
		net.minecraft.client.renderer.state.level.CameraRenderState camera) {
		StewardPlate.submit(s, stack, c, camera);
	}

	@Override
	public Identifier getTextureLocation(StewardRenderState state) {
		return SKIN;
	}
}
