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
import java.util.List;
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

	/**
	 * Pixels per block on the slate: 3 x 2 blocks are 144 x 96 px. (MAP-P3B) 48, not 64: the same slate, its text a third larger, so it reads from
	 * a few blocks away; fewer rows fit, and the rest are counted ("and N more").
	 */
	static final float PPB = dev.larattalabs.steward.view.WorldText.BASE_PPB;
	static final int W = 3 * (int) PPB, H = 2 * (int) PPB;
	/** A row's height; the footer's top. */
	static final int ROW = 11, FOOT = H - 11;

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
		s.view = v != null && (Math.abs(p.getX() - v.claim().centerX()) <= v.claim().radius() && Math.abs(p.getZ() - v.claim().centerZ()) <= v.claim().radius()
			|| v.districts().stream().anyMatch(d -> Math.abs(p.getX() - d.centerX()) <= d.radius() && Math.abs(p.getZ() - d.centerZ()) <= d.radius())) ? v : null;
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
		// (UI-PR-S) the player's text size: the slate keeps its size in the world, its pixels (and so its text) grow; fewer rows fit
		var slate = dev.larattalabs.steward.view.WorldText.slate(dev.larattalabs.steward.view.WorldText.factor(dev.larattalabs.steward.client.screen.KitScreen.OWNER));
		final float PPB = slate.ppb();
		final int W = slate.w(), H = slate.h(), FOOT = slate.foot();
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
		// the settlement's attention, as the nameplate and the inbox say it; a board from before it had one keeps the old line
		var a = v.attention();
		String status;
		boolean loud;
		if (a != null && a.kind() != dev.larattalabs.steward.inbox.Attention.Kind.IDLE) {
			String line = Character.toUpperCase(a.line().charAt(0)) + a.line().substring(1);
			// the inbox's key, short enough to fit the slate's line
			status = a.needsYou() ? line + " (Y)" : line;
			loud = a.needsYou();
		} else {
			status = v.buildings().size() + (v.buildings().size() == 1 ? " building" : " buildings") + ", nothing waits";
			loud = false;
		}
		// (MAP-P3B) the status on up to two lines (the larger text fits fewer words on one), then as many rows as fit above the footer, the last slot
		// counting the rest when they don't all fit
		List<String> said = TextUtil.wrapPlain(font, status, W - 12);
		int y = 21;
		int lines = slate.statusLines();
		for (int i = 0; i < Math.min(lines, said.size()); i++, y += 10) {
			String line = lines == 1 && said.size() > 1 ? TextUtil.ellipsize(font, status, W - 12) : i == 1 && said.size() > 2 ? TextUtil.ellipsize(font, String.join(" ", said.subList(1, said.size())), W - 12) : said.get(i);
			WorldUi.submitText(ps, c, Component.literal(line).getVisualOrderText(), 6, y, loud ? UiStyle.BRASS : muted, light);
		}
		y += 3;
		var rows = v.buildings();
		int slots = slate.slots(y);
		int shown = dev.larattalabs.steward.view.WorldText.Slate.shown(rows.size(), slots);
		for (int i = 0; i < shown; i++) {
			SettlementView.Building b = rows.get(i);
			// (8bS) from Architect's BuildStatus when there is one
			String fam = b.status() != null ? b.stateWords()[0] : b.updateAvailable() ? "waiting" : b.updating() || !"built".equals(b.state()) ? "working" : "done";
			if (dev.larattalabs.labui.ui.GhostPalette.current() == dev.larattalabs.labui.ui.GhostPalette.Palette.OKABE_ITO) {
				// (UI-PR-S) the map's swatch in the Okabe-Ito palette (lab-ui's dots have no Okabe-Ito set yet: an ask)
				WorldUi.submitFill(ps, c, 6, y + 1, 13, y + 8, dev.larattalabs.steward.view.MapColors.fill(fam, dev.larattalabs.labui.ui.GhostPalette.Palette.OKABE_ITO,
					new dev.larattalabs.steward.view.MapColors.Standard(UiStyle.SAGE, UiStyle.BRASS, UiStyle.CLAY)), light);
			} else WorldUi.submitSprite(ps, c, WorldUi.Layer.SOLID, Kit.dot(fam, false), 6, y + 1, 7, 7, 0f, 0xFFFFFFFF, light);
			String right = b.updateAvailable() ? "update ready" : "v" + b.version();
			int rw = font.width(right);
			WorldUi.submitText(ps, c, Component.literal(TextUtil.ellipsize(font, b.role(), W - 30 - rw)).getVisualOrderText(), 17, y, UiStyle.CREAM, light);
			WorldUi.submitText(ps, c, Component.literal(right).getVisualOrderText(), W - 6 - rw, y, muted, light);
			y += ROW;
		}
		if (dev.larattalabs.steward.view.WorldText.Slate.more(rows.size(), shown, slots)) WorldUi.submitText(ps, c, Component.literal("and " + (rows.size() - shown) + " more").getVisualOrderText(), 17, y, muted, light);
		// (UI-PR-S) at a larger text size the footer's words may not fit the slate: the short form
		String foot = font.width("Right-click: the settlement") <= W - 12 ? "Right-click: the settlement" : "Right-click: open";
		WorldUi.submitText(ps, c, Component.literal(TextUtil.ellipsize(font, foot, W - 12)).getVisualOrderText(), 6, FOOT, muted, light);
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
