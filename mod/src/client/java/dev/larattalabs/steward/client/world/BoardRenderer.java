// toFace is ported from AgentCraft Worlds (StationRenderer.toFace), MIT:
// Copyright (c) 2026 AgentCraft contributors; Copyright (c) 2026 Laratta Labs (AgentCraft Worlds changes). See assets-src/NOTICE.
package dev.larattalabs.steward.client.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import dev.larattalabs.labui.client.ui.WorldUi;
import dev.larattalabs.steward.block.SettlementBoardBlock;
import dev.larattalabs.steward.block.SettlementBoardBlockEntity;
import dev.larattalabs.steward.view.SettlementView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The settlement board's slate (3 x 2 blocks, centred on its plank): a walnut slate with the settlement's name on a cream band, a line for what waits for the
 * player, one row per building (a status dot, its role, its version or what waits), and a footer saying what a right-click does. It shows the settlement
 * the player stands in when the board is in that claim (the client has its buildings), else asks the player to step into it. Full-bright. Client thread.
 */
public final class BoardRenderer implements BlockEntityRenderer<SettlementBoardBlockEntity, BoardRenderer.State> {
	public static final class State extends BlockEntityRenderState {
		Direction facing = Direction.NORTH;
		@Nullable SettlementView view;
	}

	/** Pixels per block on the slate: 3 x 2 blocks are 192 x 128 px. */
	static final float PPB = 64;
	static final int W = 192, H = 128, ROWS = 7;

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(SettlementBoardBlockEntity be, State s, float partial, Vec3 camera, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
		BlockEntityRenderState.extractBase(be, s, crumbling);
		var bs = be.getBlockState();
		s.facing = bs.hasProperty(SettlementBoardBlock.FACING) ? bs.getValue(SettlementBoardBlock.FACING) : Direction.NORTH;
		SettlementView v = SettlementWorld.near();
		var p = be.getBlockPos();
		s.view = v != null && Math.abs(p.getX() - v.claim().centerX()) <= v.claim().radius() && Math.abs(p.getZ() - v.claim().centerZ()) <= v.claim().radius() ? v : null;
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 64;
	}

	@Override
	public void submit(State s, PoseStack ps, SubmitNodeCollector c, CameraRenderState camera) {
		dev.larattalabs.labui.ui.Guard.run("steward.board", () -> draw(s, ps, c));
	}

	private static void draw(State s, PoseStack ps, SubmitNodeCollector c) {
		Font font = Minecraft.getInstance().font;
		int light = WorldUi.uiLight();
		ps.pushPose();
		// onto the plank's front (2 px thick at the back of the block), then one block left and one up: the slate is centred on the plank, its bottom at the plank's
		toFace(ps, s.facing, 14f / 16f - 0.01f, PPB);
		ps.translate(-PPB, -PPB, 0);
		// each layer a hair nearer the viewer (-z here), or coplanar fills fight: the slate, the cream band, then the text and dots
		WorldUi.submitFill(ps, c, 0, 0, W, H, UiStyle.WALNUT, light);
		ps.translate(0, 0, -0.01f);
		WorldUi.submitFill(ps, c, 2, 2, W - 2, 16, UiStyle.CREAM, light);
		ps.translate(0, 0, -0.01f);
		SettlementView v = s.view;
		String name = v == null ? "Settlement board" : v.name();
		WorldUi.submitText(ps, c, Component.literal(TextUtil.ellipsize(font, name, W - 12)).getVisualOrderText(), 6, 5, UiStyle.INK, light);
		int muted = UiStyle.color("ink_ui.activity", 0xFFC4BDB2);
		if (v == null) {
			WorldUi.submitText(ps, c, Component.literal("Stand in the settlement to see it here.").getVisualOrderText(), 6, 24, muted, light);
			ps.popPose();
			return;
		}
		long updates = v.buildings().stream().filter(SettlementView.Building::updateAvailable).count();
		String status = v.busy() ? "Being built: see the inbox (Y)" : updates > 0 ? updates + (updates == 1 ? " update waits" : " updates wait") + " for you"
			: v.buildings().size() + (v.buildings().size() == 1 ? " building" : " buildings") + ", nothing waits";
		WorldUi.submitText(ps, c, Component.literal(TextUtil.ellipsize(font, status, W - 12)).getVisualOrderText(), 6, 21, v.busy() || updates > 0 ? UiStyle.BRASS : muted, light);
		int y = 34;
		var rows = v.buildings();
		for (int i = 0; i < Math.min(ROWS, rows.size()); i++) {
			SettlementView.Building b = rows.get(i);
			String fam = b.updateAvailable() ? "waiting" : b.updating() || !"built".equals(b.state()) ? "working" : "done";
			WorldUi.submitSprite(ps, c, WorldUi.Layer.SOLID, Kit.dot(fam, false), 6, y + 1, 7, 7, 0f, 0xFFFFFFFF, light);
			String right = b.updateAvailable() ? "update ready" : "v" + b.version();
			int rw = font.width(right);
			WorldUi.submitText(ps, c, Component.literal(TextUtil.ellipsize(font, b.role(), W - 30 - rw)).getVisualOrderText(), 17, y, UiStyle.CREAM, light);
			WorldUi.submitText(ps, c, Component.literal(right).getVisualOrderText(), W - 6 - rw, y, muted, light);
			y += 11;
		}
		if (rows.size() > ROWS) WorldUi.submitText(ps, c, Component.literal("and " + (rows.size() - ROWS) + " more").getVisualOrderText(), 17, y, muted, light);
		WorldUi.submitText(ps, c, Component.literal("Right-click: the settlement").getVisualOrderText(), 6, H - 11, muted, light);
		ps.popPose();
	}

	/**
	 * Moves the pose (at the block origin) onto the block's front, {@code depth} blocks behind the front of the north-facing model, in 1/{@code ppb} block units,
	 * x right and y down as a viewer sees it, origin at the top-left of the block's face.
	 */
	static void toFace(PoseStack ps, Direction facing, float depth, float ppb) {
		ps.translate(0.5f, 0.5f, 0.5f);
		ps.rotateDegrees(Axis.YP, -switch (facing) {
			case EAST -> 90f;
			case SOUTH -> 180f;
			case WEST -> 270f;
			default -> 0f;
		});
		ps.translate(-0.5f, -0.5f, -0.5f);
		// the north-facing model: the viewer stands north (-z) looking south, so the viewer's right is -x
		ps.translate(1f, 1f, depth);
		ps.scale(-1f / ppb, -1f / ppb, 1f);
	}
}
