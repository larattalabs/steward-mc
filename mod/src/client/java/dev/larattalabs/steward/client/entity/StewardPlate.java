// Ported from AgentCraft Worlds (Nameplate, PlateStack), MIT:
// Copyright (c) 2026 AgentCraft contributors; Copyright (c) 2026 Laratta Labs (AgentCraft Worlds changes). See assets-src/NOTICE.
package dev.larattalabs.steward.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import dev.larattalabs.labui.client.ui.WorldUi;
import dev.larattalabs.labui.client.world.SpeechBubble;
import dev.larattalabs.labui.ui.TextDepth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The steward's nameplate stack, AgentCraft's look: an opaque ink pill above the head with a status dot, its name, and what it is doing under it; its
 * speech bubble right above the plate; above that a pulsing clay "!" while it needs the player. Billboarded, depth-tested, full-bright. One steward per
 * settlement, so there is no declutter. Render thread.
 */
final class StewardPlate {
	/** Bottom of the plate above the feet of a standing steward (blocks); sitting and lying lower it. */
	static final double HEIGHT = 2.12, SITTING = 1.5, LYING = 0.95;
	static final int MAX_ACTIVITY_PX = 116;
	/** Plates keep their world size up to this distance, then grow with it, up to {@link #SCALE_MAX}. */
	static final double SCALE_FROM = 12;
	static final float SCALE_MAX = 1.6f;
	/** Plates are drawn within this distance (blocks). */
	static final double DISTANCE = 40;
	private static final int DOT = 7, GAP = 3;
	/** The "!" marker: bar + dot, outlined, in plate px. */
	private static final float BAR_W = 4, BAR_H = 11, MARK_GAP = 2, MARK_DOT = 4, OUTLINE = 1.5f;

	private StewardPlate() {
	}

	static void submit(StewardRenderState s, PoseStack ps, SubmitNodeCollector c, CameraRenderState camera) {
		if (s.distanceToCameraSq > DISTANCE * DISTANCE) return;
		Font font = Minecraft.getInstance().font;
		Kit.Padding pad = Kit.padding("nameplate");
		String name = "Steward";
		// the activity wraps to a second line before it is ever cut
		String activity = "", activity2 = "";
		if (!s.activity.isEmpty()) {
			var wrapped = TextUtil.wrapPlain(font, s.activity, MAX_ACTIVITY_PX);
			activity = wrapped.get(0);
			if (wrapped.size() > 1) activity2 = TextUtil.ellipsize(font, String.join(" ", wrapped.subList(1, wrapped.size())), MAX_ACTIVITY_PX);
		}
		int row1 = DOT + GAP + font.width(name);
		int row2 = activity.isEmpty() ? 0 : font.width(activity);
		int row3 = activity2.isEmpty() ? 0 : font.width(activity2);
		int inner = Math.max(row1, Math.max(row2, row3));
		int w = inner + pad.left() + pad.right() + 2;
		int h = pad.top() + 9 + (activity.isEmpty() ? 0 : 10) + (activity2.isEmpty() ? 0 : 10) + pad.bottom() + 1;
		int light = WorldUi.uiLight();
		double dist = Math.sqrt(s.distanceToCameraSq);
		float scale = (float) Math.max(1.0, Math.min(SCALE_MAX, dist / SCALE_FROM));
		double base = switch (s.posture) {
			case SIT -> SITTING;
			case LIE -> LYING;
			default -> HEIGHT;
		};
		ps.pushPose();
		WorldUi.billboard(ps, camera, 0, base, 0, scale, 0f, s.x - camera.pos.x, s.y - camera.pos.y, s.z - camera.pos.z);
		float x0 = -w / 2f, y0 = -h;
		WorldUi.submitNineSlice(ps, c, WorldUi.Layer.SOLID, Kit.NAMEPLATE, x0, y0, w, h, 0xFFFFFFFF, light);
		float cx = x0 + pad.left() + 1 + (inner - row1) / 2f;
		float ty = y0 + pad.top() + 1;
		String family = s.needsYou ? "waiting" : s.activity.isEmpty() || "resting".equals(s.activity) || "asleep".equals(s.activity) ? "idle" : "working";
		if (s.needsYou) {
			float pulse = 0.5f + 0.5f * (float) Math.sin(s.ageInTicks / 20f * Math.PI * 2 / (UiStyle.metric("metrics.pulse_ms", 1200) / 1000.0));
			int a = (int) (90 + 165 * pulse);
			WorldUi.submitSprite(ps, c, Kit.dot("waiting", true), cx - 2, ty - 1, 11, 11, (a << 24) | 0xFFFFFF, light);
		}
		WorldUi.submitSprite(ps, c, WorldUi.Layer.OVERLAY, Kit.dot(family, false), cx, ty + 1, DOT, DOT, 0.15f, 0xFFFFFFFF, light);
		// the text a hair in front of the plate (a fraction of the camera distance: coplanar glyphs lose to the plate otherwise)
		ps.pushPose();
		float px = WorldUi.PX * scale;
		ps.translate(0f, 0f, px <= 0 ? 0f : (float) (TextDepth.FRACTION * dist / px));
		WorldUi.submitText(ps, c, Component.literal(name).getVisualOrderText(), cx + DOT + GAP, ty, UiStyle.BRASS, light);
		int actColor = UiStyle.color("ink_ui.activity", 0xFFC4BDB2);
		if (row2 > 0) WorldUi.submitText(ps, c, Component.literal(activity).getVisualOrderText(), x0 + pad.left() + 1 + (inner - row2) / 2f, ty + 10, actColor, light);
		if (row3 > 0) WorldUi.submitText(ps, c, Component.literal(activity2).getVisualOrderText(), x0 + pad.left() + 1 + (inner - row3) / 2f, ty + 20, actColor, light);
		ps.popPose();
		// the stack above the plate: the speech bubble, then the "!"
		float top = y0;
		if (s.bubble != null && s.bubbleVisibility > 0f) {
			SpeechBubble.submit(ps, c, s.bubble, top, s.bubbleVisibility, light);
			top -= SpeechBubble.stackHeight(s.bubble);
		}
		if (s.needsYou) exclaim(ps, c, top, s.ageInTicks / 20f, light);
		ps.popPose();
	}

	/** A clay "!" with an ink outline, bobbing and pulsing gently. */
	private static void exclaim(PoseStack ps, SubmitNodeCollector c, float bottom, float time, int light) {
		float period = UiStyle.metric("metrics.pulse_ms", 1200) / 1000f;
		float pulse = 0.5f + 0.5f * Mth.sin(time * Mth.TWO_PI / period);
		float bob = 1.5f * Mth.sin(time * Mth.TWO_PI / (period * 2));
		float scale = 1f + 0.1f * pulse;
		float total = BAR_H + MARK_GAP + MARK_DOT;
		float cy = bottom - 3 - OUTLINE - total / 2f + bob;
		int ink = UiStyle.INK;
		int clay = UiStyle.status("waiting");
		int lit = mix(clay, UiStyle.CREAM, 0.35f + 0.25f * pulse);
		ps.pushPose();
		ps.translate(0, cy, 0);
		ps.scale(scale, scale, 1f);
		float x0 = -BAR_W / 2f;
		float barY0 = -total / 2f;
		float dotY0 = barY0 + BAR_H + MARK_GAP;
		WorldUi.submitFill(ps, c, x0 - OUTLINE, barY0 - OUTLINE, x0 + BAR_W + OUTLINE, barY0 + BAR_H + OUTLINE, ink, light);
		WorldUi.submitFill(ps, c, x0 - OUTLINE, dotY0 - OUTLINE, x0 + MARK_DOT + OUTLINE, dotY0 + MARK_DOT + OUTLINE, ink, light);
		ps.translate(0, 0, 0.35f);
		WorldUi.submitFill(ps, c, x0, barY0, x0 + BAR_W, barY0 + BAR_H, clay, light);
		WorldUi.submitFill(ps, c, x0, dotY0, x0 + MARK_DOT, dotY0 + MARK_DOT, clay, light);
		ps.translate(0, 0, 0.35f);
		WorldUi.submitFill(ps, c, x0, barY0, x0 + 1.5f, barY0 + BAR_H - 1, lit, light);
		WorldUi.submitFill(ps, c, x0, dotY0, x0 + 1.5f, dotY0 + MARK_DOT - 1, lit, light);
		ps.popPose();
	}

	private static int mix(int a, int b, float t) {
		int r = (int) Mth.lerp(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF);
		int g = (int) Mth.lerp(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF);
		int bl = (int) Mth.lerp(t, a & 0xFF, b & 0xFF);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}
}
