package dev.larattalabs.steward.client.entity;

import dev.larattalabs.steward.entity.Poses;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/** The steward's render state: the humanoid one plus its posture and this frame's pose channels. */
public final class StewardRenderState extends HumanoidRenderState {
	public final float[] channels = new float[Poses.N];
	public Poses.Posture posture = Poses.Posture.IDLE;
	public String activity = "";
	public boolean needsYou;
	public dev.larattalabs.labui.client.world.SpeechBubble.@org.jspecify.annotations.Nullable Layout bubble;
	public float bubbleVisibility;
}
