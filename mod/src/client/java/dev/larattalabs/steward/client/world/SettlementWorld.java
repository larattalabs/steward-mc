package dev.larattalabs.steward.client.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import dev.larattalabs.labui.client.ui.WorldUi;
import dev.larattalabs.labui.ui.TextDepth;
import dev.larattalabs.steward.net.StewardNet;
import dev.larattalabs.steward.view.SettlementView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The settlement in the world (docs/PLAN.md "Interface", 3d): look at one of its buildings and it is outlined, with a label over it (its role and version, a
 * badge when an update waits); the inspect key opens its panel. Survey mode (a key) labels every building and draws the claim's border. The buildings come
 * from the server while the player stands in the claim ({@code SettlementNear}). Client thread.
 */
public final class SettlementWorld {
	private static @Nullable SettlementView near;
	private static @Nullable String hovered;
	private static boolean survey;
	/** Labels over ghost layers (the massings that wait for the player), by composite key. */
	private static final java.util.Map<String, java.util.List<StewardNet.Layer>> GHOSTS = new java.util.HashMap<>();

	public static void ghostLabels(String key, java.util.List<StewardNet.Layer> layers) {
		var labelled = layers.stream().filter(l -> !l.label().isEmpty()).toList();
		if (labelled.isEmpty()) GHOSTS.remove(key);
		else GHOSTS.put(key, labelled);
	}
	/** How far a building can be looked at, and how far survey mode labels (blocks). */
	static final double LOOK = 64, SURVEY = 96;

	private SettlementWorld() {
	}

	public static void set(String json) {
		near = json.isEmpty() ? null : SettlementView.fromJson(json);
		if (near == null) hovered = null;
	}

	public static void clear() {
		GHOSTS.clear();
		near = null;
		hovered = null;
		survey = false;
	}

	public static boolean toggleSurvey() {
		survey = !survey;
		return survey;
	}

	public static @Nullable SettlementView near() {
		return near;
	}

	/** Opens the looked-at building's panel; false when the crosshair is on none. */
	public static boolean inspect() {
		if (near == null || hovered == null) return false;
		ClientPlayNetworking.send(new StewardNet.Decide(near.id(), "building", hovered, "", 0));
		return true;
	}

	/** Which building the crosshair is on: the nearest box the view ray passes through. */
	public static void tick(Minecraft mc) {
		hovered = null;
		if (near == null || mc.player == null) return;
		Vec3 from = mc.player.getEyePosition();
		Vec3 to = from.add(mc.player.getViewVector(1f).scale(LOOK));
		double best = Double.MAX_VALUE;
		for (SettlementView.Building b : near.buildings()) {
			var hit = box(b).clip(from, to);
			if (hit.isEmpty()) continue;
			double d = hit.get().distanceToSqr(from);
			if (d < best) {
				best = d;
				hovered = b.siteId();
			}
		}
	}

	private static AABB box(SettlementView.Building b) {
		return new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1);
	}

	public static void submit(LevelRenderContext ctx) {
		CameraRenderState camera = ctx.levelState().cameraRenderState;
		Vec3 cam = camera.pos;
		if (cam == null) return;
		SubmitNodeCollector c = ctx.submitNodeCollector();
		for (var layers : GHOSTS.values()) {
			for (StewardNet.Layer l : layers) {
				double d = Math.sqrt(cam.distanceToSqr(l.lx() + 0.5, l.ly(), l.lz() + 0.5));
				if (d < SURVEY) pill(c, camera, l.lx() + 0.5, l.ly(), l.lz() + 0.5, l.label(), "massing · approve or redirect in the inbox", "waiting", false, d);
			}
		}
		SettlementView v = near;
		if (v == null) return;
		for (SettlementView.Building b : v.buildings()) {
			boolean on = b.siteId().equals(hovered);
			double d = Math.sqrt(box(b).distanceToSqr(cam));
			if (!on && !(survey && d < SURVEY)) continue;
			outline(c, b, cam, on, (float) d);
			label(c, camera, b, on, d);
		}
		if (survey) border(c, v.claim(), cam);
	}

	/** The building's box: thin bars along its twelve edges, brass when looked at, cream in survey mode. */
	private static void outline(SubmitNodeCollector c, SettlementView.Building b, Vec3 cam, boolean on, float dist) {
		float x0 = (float) (b.minX() - cam.x), y0 = (float) (b.minY() - cam.y), z0 = (float) (b.minZ() - cam.z);
		float x1 = (float) (b.maxX() + 1 - cam.x), y1 = (float) (b.maxY() + 1 - cam.y), z1 = (float) (b.maxZ() + 1 - cam.z);
		float t = Math.max(0.03f, dist * 0.0025f);
		int col = UiStyle.withAlpha(on ? UiStyle.BRASS : UiStyle.CREAM, on ? 0xE0 : 0x90);
		c.submitCustomGeometry(new PoseStack(), RenderTypes.debugFilledBox(), (pose, vc) -> {
			for (float x : new float[] {x0, x1}) {
				for (float y : new float[] {y0, y1}) cube(pose, vc, x - t, y - t, z0 - t, x + t, y + t, z1 + t, col);
				for (float z : new float[] {z0, z1}) cube(pose, vc, x - t, y0 - t, z - t, x + t, y1 + t, z + t, col);
			}
			for (float y : new float[] {y0, y1}) for (float z : new float[] {z0, z1}) cube(pose, vc, x0 - t, y - t, z - t, x1 + t, y + t, z + t, col);
		});
	}

	/** The claim's border in survey mode: a low clay curtain along its edges, on the ground, where it is within reach. */
	private static void border(SubmitNodeCollector c, SettlementView.ClaimInfo claim, Vec3 cam) {
		var level = Minecraft.getInstance().level;
		if (level == null) return;
		int r = claim.radius();
		int x0 = claim.centerX() - r, x1 = claim.centerX() + r + 1, z0 = claim.centerZ() - r, z1 = claim.centerZ() + r + 1;
		int col = UiStyle.withAlpha(UiStyle.CLAY, 0x60);
		int reach = 160;
		c.submitCustomGeometry(new PoseStack(), RenderTypes.debugFilledBox(), (pose, vc) -> {
			for (int i = 0; i < 4; i++) {
				boolean alongX = i < 2;
				int fixed = switch (i) {
					case 0 -> z0;
					case 1 -> z1;
					case 2 -> x0;
					default -> x1;
				};
				int from = alongX ? x0 : z0, to = alongX ? x1 : z1;
				double camAlong = alongX ? cam.x : cam.z, camAcross = alongX ? cam.z : cam.x;
				if (Math.abs(camAcross - fixed) > reach) continue;
				int a0 = (int) Math.max(from, Math.floor(camAlong - reach)), a1 = (int) Math.min(to, Math.ceil(camAlong + reach));
				for (int a = a0; a < a1; a += 2) {
					int gx = alongX ? a : fixed, gz = alongX ? fixed : a;
					int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz);
					float y = (float) (g - cam.y);
					float s = (float) (a - camAlong), e = s + 2, f = (float) (fixed - camAcross);
					if (alongX) cube(pose, vc, s, y, f - 0.02f, e, y + 1.5f, f + 0.02f, col);
					else cube(pose, vc, f - 0.02f, y, s, f + 0.02f, y + 1.5f, e, col);
				}
			}
		});
	}

	/** A label over the building: an ink pill with its role and, under it, its version or what waits. */
	private static void label(SubmitNodeCollector c, CameraRenderState camera, SettlementView.Building b, boolean on, double dist) {
		String sub = b.updating() ? "updating" : b.updateAvailable() ? "update to v" + b.head() + " ready" : "v" + b.version() + (b.edits() > 0 ? " · " + b.edits() + " of your edits" : "");
		if (on) sub += " · I to inspect";
		String family = b.updateAvailable() ? "waiting" : b.updating() || !"built".equals(b.state()) ? "working" : "done";
		pill(c, camera, (b.minX() + b.maxX() + 1) / 2.0, b.maxY() + 2.4, (b.minZ() + b.maxZ() + 1) / 2.0, b.role(), sub, family, on, dist);
	}

	/** An ink pill at a world point, facing the camera: a status dot and a title, a second line under it. */
	private static void pill(SubmitNodeCollector c, CameraRenderState camera, double wx, double wy, double wz, String titleText, String sub, String family, boolean on,
		double dist) {
		Font font = Minecraft.getInstance().font;
		Kit.Padding pad = Kit.padding("nameplate");
		String title = TextUtil.ellipsize(font, titleText, 220);
		int inner = Math.max(font.width(title) + 10, font.width(sub));
		int w = inner + pad.left() + pad.right() + 2;
		int h = pad.top() + 19 + pad.bottom() + 1;
		int light = WorldUi.uiLight();
		// a building's label reads from across the street: twice a nameplate's size, growing with the distance
		float scale = (float) Math.max(2.0, Math.min(4.0, dist / 7));
		double x = wx - camera.pos.x, y = wy - camera.pos.y, z = wz - camera.pos.z;
		PoseStack ps = new PoseStack();
		WorldUi.billboard(ps, camera, x, y, z, scale, 0f, 0, 0, 0);
		float x0 = -w / 2f, y0 = -h;
		WorldUi.submitNineSlice(ps, c, WorldUi.Layer.SOLID, Kit.NAMEPLATE, x0, y0, w, h, 0xFFFFFFFF, light);
		float tx = x0 + pad.left() + 1, ty = y0 + pad.top() + 1;
		WorldUi.submitSprite(ps, c, WorldUi.Layer.OVERLAY, Kit.dot(family, false), tx + (inner - font.width(title) - 10) / 2f, ty + 1, 7, 7, 0.15f, 0xFFFFFFFF, light);
		ps.pushPose();
		double dd = Math.sqrt(x * x + y * y + z * z);
		float px = WorldUi.PX * scale;
		ps.translate(0f, 0f, (float) (TextDepth.FRACTION * dd / px));
		WorldUi.submitText(ps, c, Component.literal(title).getVisualOrderText(), tx + (inner - font.width(title) - 10) / 2f + 10, ty, on ? UiStyle.BRASS : UiStyle.CREAM, light);
		WorldUi.submitText(ps, c, Component.literal(sub).getVisualOrderText(), tx + (inner - font.width(sub)) / 2f, ty + 10, UiStyle.color("ink_ui.activity", 0xFFC4BDB2), light);
		ps.popPose();
	}

	private static void cube(PoseStack.Pose p, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
		quad(p, vc, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, argb);
		quad(p, vc, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, argb);
		quad(p, vc, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, argb);
		quad(p, vc, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, argb);
		quad(p, vc, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, argb);
		quad(p, vc, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, argb);
	}

	private static void quad(PoseStack.Pose p, VertexConsumer vc, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx,
		float dy, float dz, int argb) {
		vc.addVertex(p, ax, ay, az).setColor(argb);
		vc.addVertex(p, bx, by, bz).setColor(argb);
		vc.addVertex(p, cx, cy, cz).setColor(argb);
		vc.addVertex(p, dx, dy, dz).setColor(argb);
	}
}
