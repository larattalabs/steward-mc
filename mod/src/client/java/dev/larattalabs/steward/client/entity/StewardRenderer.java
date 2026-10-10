package dev.larattalabs.steward.client.entity;

import dev.larattalabs.steward.entity.StewardEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/**
 * The steward drawn as a player-shaped figure (the player model, so the skin's outer layer shows: the hat, the coat's hem) in its own skin, made by
 * assets-src (gen/chars/steward.py). Its name shows above it. Client thread.
 */
public final class StewardRenderer extends HumanoidMobRenderer<StewardEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
	private static final Identifier SKIN = dev.larattalabs.steward.Steward.id("textures/entity/steward.png");

	public StewardRenderer(EntityRendererProvider.Context ctx) {
		super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5F);
	}

	@Override
	public HumanoidRenderState createRenderState() {
		return new HumanoidRenderState();
	}

	@Override
	public Identifier getTextureLocation(HumanoidRenderState state) {
		return SKIN;
	}
}
