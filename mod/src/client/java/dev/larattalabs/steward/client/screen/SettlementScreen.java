package dev.larattalabs.steward.client.screen;

import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import dev.larattalabs.steward.net.StewardNet;
import dev.larattalabs.steward.view.SettlementView;
import dev.larattalabs.steward.view.SettlementView.Building;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * The settlement screen (docs/PLAN.md "Interface", phase 3a): tabs for its buildings (a list beside a top-down map of the claim; a building opens its
 * panel), its history (the change log as operations, newest first), its claim (size, Expand) and its settings. Read-only apart from Expand; the card and
 * the inbox are a key away. Client thread.
 */
public final class SettlementScreen extends KitScreen {
	enum Tab { BUILDINGS, HISTORY, CLAIM, SETTINGS }

	private static final int MAX_W = 500;
	private static final int ROW_H = 22;
	private final SettlementView view;
	private Tab tab = Tab.BUILDINGS;
	private int scroll;
	private @Nullable String hovered;
	private final List<String> rowIds = new ArrayList<>();
	private final List<int[]> rowRects = new ArrayList<>();
	private final List<int[]> mapRects = new ArrayList<>();
	private final List<String> mapIds = new ArrayList<>();

	public SettlementScreen(SettlementView view) {
		super(view.name());
		this.view = view;
	}

	private void send(String action, String lot, String what) {
		ClientPlayNetworking.send(new StewardNet.Decide(view.id(), action, lot, "", 0));
		say(what + "...", false);
	}

	private void open(String siteId) {
		Building b = view.building(siteId);
		if (b != null && minecraft != null) minecraft.gui.setScreen(new BuildingScreen(view, b, this));
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Kit.Padding pad = Kit.padding("panel_paper");
		int w = Math.min(MAX_W, width - 16);
		int inner = w - pad.left() - pad.right();
		int h = Math.min(height - 16, 300);
		int x = (width - w) / 2;
		int y = Math.max(8, (height - h) / 2);
		Panels.panel(g, x, y, w, h);
		int cx = x + pad.left();
		int top = y + pad.top();
		Panels.header(g, font, view.name(), cx - 2, top - 2, inner + 4);
		String sub = UiBits.plural(view.buildings().size(), "building", "buildings") + " · " + view.claim().side() + " x " + view.claim().side()
			+ (view.busy() ? " · building now" : "");
		g.text(font, sub, cx + inner - font.width(sub), top, UiBits.muted(), false);
		// tabs
		int ty = top + 16;
		int tx = cx;
		for (Tab t : Tab.values()) {
			String label = switch (t) {
				case BUILDINGS -> "Buildings";
				case HISTORY -> "History";
				case CLAIM -> "Claim";
				case SETTINGS -> "Settings";
			};
			tx += chip(g, label, tx, ty, tab == t, true, mouseX, mouseY, () -> {
				tab = t;
				scroll = 0;
			}) + 3;
		}
		int bodyTop = ty + CHIP_H + 6;
		int footerY = y + h - pad.bottom() - 10;
		int actionsY = footerY - 10 - 20;
		int bodyH = actionsY - 4 - bodyTop;
		rowIds.clear();
		rowRects.clear();
		mapRects.clear();
		mapIds.clear();
		switch (tab) {
			case BUILDINGS -> drawBuildings(g, cx, bodyTop, inner, bodyH, mouseX, mouseY);
			case HISTORY -> drawHistory(g, cx, bodyTop, inner, bodyH);
			case CLAIM -> drawClaim(g, cx, bodyTop, inner, bodyH);
			case SETTINGS -> drawSettings(g, cx, bodyTop, inner, bodyH);
		}
		int bx = cx;
		if (tab == Tab.CLAIM) bx += button(g, "Expand the claim", 1, true, false, !view.busy(), bx, actionsY, mouseX, mouseY, () -> send("expand", "settlement", "Expanding")) + 6;
		bx += button(g, "Card", 2, false, false, view.described(), bx, actionsY, mouseX, mouseY, () -> send("card", "", "Opening the card")) + 6;
		button(g, "Inbox", 3, false, false, true, bx, actionsY, mouseX, mouseY, () -> {
			if (minecraft != null) minecraft.gui.setScreen(new InboxScreen(view.id()));
		});
		if (status != null) g.text(font, TextUtil.ellipsize(font, status, inner), cx, footerY - 2, statusError ? UiBits.errorText() : UiBits.muted(), false);
		else UiBits.hints(g, font, cx, footerY - 4, false, "1-3", "act", "click", "a building", "Esc", "close");
	}

	// ------------------------------------------------------------------ buildings

	/** A building's dot family and state word. */
	static String[] state(Building b) {
		if (b.updating()) return new String[] {"working", "updating"};
		if (b.updateAvailable()) return new String[] {"waiting", "update to v" + b.head()};
		return switch (b.state()) {
			case "built" -> new String[] {"done", "v" + b.version()};
			case "building" -> new String[] {"working", "under construction"};
			case "placing" -> new String[] {"working", "being placed"};
			default -> new String[] {"idle", b.state()};
		};
	}

	private void drawBuildings(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		if (view.buildings().isEmpty()) {
			Panels.inset(g, x, y, w, h);
			g.text(font, "Nothing is built here yet.", x + 8, y + 8, UiBits.muted(), false);
			return;
		}
		boolean map = w >= 360;
		int mapSize = map ? Math.min(h, w / 2 - 8) : 0;
		int lw = map ? w - mapSize - 8 : w;
		Panels.inset(g, x, y, lw, h);
		int fit = Math.max(1, (h - 6) / ROW_H);
		List<Building> all = view.buildings();
		scroll = Math.max(0, Math.min(scroll, all.size() - fit));
		hovered = null;
		for (int i = scroll; i < Math.min(all.size(), scroll + fit); i++) {
			Building b = all.get(i);
			int ry = y + 4 + (i - scroll) * ROW_H;
			boolean hov = mx >= x && mx < x + lw && my >= ry - 2 && my < ry + ROW_H - 2;
			if (hov) {
				hovered = b.siteId();
				g.fill(x + 2, ry - 2, x + lw - 2, ry + ROW_H - 3, 0x14000000);
			}
			String[] st = state(b);
			Panels.dot(g, st[0], x + 5, ry + 1, false);
			g.text(font, TextUtil.ellipsize(font, b.role(), lw - 20), x + 15, ry, UiBits.ink(), false);
			String detail = st[1] + (b.edits() > 0 ? " · " + b.edits() + " of your edits kept" : "") + " · " + b.siteId();
			g.text(font, TextUtil.ellipsize(font, detail, lw - 20), x + 15, ry + 10, UiBits.muted(), false);
			rowIds.add(b.siteId());
			rowRects.add(new int[] {x, ry - 2, lw, ROW_H});
		}
		if (all.size() > fit) {
			TextUtil.Scroll sc = new TextUtil.Scroll().update(all.size(), fit);
			sc.scrollBy(-1_000_000);
			sc.scrollBy(scroll);
			Panels.scrollbar(g, x + lw - 6, y + 1, h - 2, sc, false);
		}
		if (map) drawMap(g, x + lw + 8, y, mapSize, mx, my);
	}

	/** The claim from above: its square, the stone at the centre, each building's footprint in its state's colour; the hovered one outlined. */
	private void drawMap(GuiGraphicsExtractor g, int x, int y, int size, int mx, int my) {
		Panels.inset(g, x, y, size, size);
		var c = view.claim();
		// the area the buildings use, with a margin, at least the village's; the claim edge is drawn when it fits
		int minX = c.centerX(), maxX = c.centerX(), minZ = c.centerZ(), maxZ = c.centerZ();
		for (Building b : view.buildings()) {
			minX = Math.min(minX, b.minX());
			maxX = Math.max(maxX, b.maxX());
			minZ = Math.min(minZ, b.minZ());
			maxZ = Math.max(maxZ, b.maxZ());
		}
		int half = Math.max(Math.max(Math.abs(minX - c.centerX()), Math.abs(maxX - c.centerX())), Math.max(Math.abs(minZ - c.centerZ()), Math.abs(maxZ - c.centerZ()))) + 12;
		half = Math.min(Math.max(half, 32), c.radius());
		double scale = (size - 8) / (2.0 * half);
		int ox = x + size / 2, oy = y + size / 2;
		if (half >= c.radius()) {
			int r = (int) (c.radius() * scale);
			g.fill(ox - r, oy - r, ox + r, oy - r + 1, 0x60B4553A);
			g.fill(ox - r, oy + r - 1, ox + r, oy + r, 0x60B4553A);
			g.fill(ox - r, oy - r, ox - r + 1, oy + r, 0x60B4553A);
			g.fill(ox + r - 1, oy - r, ox + r, oy + r, 0x60B4553A);
		}
		for (Building b : view.buildings()) {
			int x0 = ox + (int) Math.floor((b.minX() - c.centerX()) * scale), x1 = ox + (int) Math.ceil((b.maxX() + 1 - c.centerX()) * scale);
			int z0 = oy + (int) Math.floor((b.minZ() - c.centerZ()) * scale), z1 = oy + (int) Math.ceil((b.maxZ() + 1 - c.centerZ()) * scale);
			x1 = Math.max(x1, x0 + 2);
			z1 = Math.max(z1, z0 + 2);
			String fam = state(b)[0];
			int col = switch (fam) {
				case "waiting" -> UiStyle.CLAY;
				case "working" -> UiStyle.BRASS;
				case "done" -> UiStyle.SAGE;
				default -> 0xFFA39B8E;
			};
			boolean hov = b.siteId().equals(hovered) || (mx >= x0 && mx < x1 && my >= z0 && my < z1);
			if (hov) hovered = b.siteId();
			g.fill(x0, z0, x1, z1, col);
			if (hov) {
				g.fill(x0 - 1, z0 - 1, x1 + 1, z0, UiStyle.INK);
				g.fill(x0 - 1, z1, x1 + 1, z1 + 1, UiStyle.INK);
				g.fill(x0 - 1, z0, x0, z1, UiStyle.INK);
				g.fill(x1, z0, x1 + 1, z1, UiStyle.INK);
			}
			mapRects.add(new int[] {x0, z0, x1 - x0, z1 - z0});
			mapIds.add(b.siteId());
		}
		// the stone (the steward stands by it)
		g.fill(ox - 2, oy - 2, ox + 2, oy + 2, UiStyle.WALNUT);
		String hint = hovered != null ? view.building(hovered).role() : "north is up";
		g.text(font, TextUtil.ellipsize(font, hint, size - 8), x + 4, y + size - 11, UiBits.muted(), false);
	}

	// ------------------------------------------------------------------ history

	static String kindLabel(String kind) {
		return switch (kind) {
			case "FOUNDED" -> "Founded";
			case "CARD_EDITED" -> "Described";
			case "PROJECT_PLACED" -> "Built";
			case "PROJECT_REMOVED" -> "Undone";
			case "UPDATED" -> "Updated";
			case "REVERTED" -> "Reverted";
			case "CLAIM_CHANGED" -> "Claim";
			case "RESKIN" -> "Restyled";
			case "PERMISSION_CHANGED" -> "Permission";
			default -> "Note";
		};
	}

	static String outcomeFamily(SettlementView.Op o) {
		return switch (o.outcome()) {
			case "FAILED" -> "error";
			case "PARTIAL" -> "waiting";
			default -> "done";
		};
	}

	private void drawHistory(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		Panels.inset(g, x, y, w, h);
		List<SettlementView.Op> ops = view.history();
		int fit = Math.max(1, (h - 6) / ROW_H);
		scroll = Math.max(0, Math.min(scroll, ops.size() - fit));
		for (int i = scroll; i < Math.min(ops.size(), scroll + fit); i++) {
			SettlementView.Op o = ops.get(i);
			int ry = y + 4 + (i - scroll) * ROW_H;
			Panels.dot(g, outcomeFamily(o), x + 5, ry + 1, false);
			String when = UiBits.ago(o.at());
			g.text(font, kindLabel(o.kind()), x + 15, ry, UiBits.ink(), false);
			g.text(font, when, x + w - 8 - font.width(when), ry, UiBits.muted(), false);
			String undo = switch (o.recovery()) {
				case "UNDO_PROJECT" -> " · undo removes it";
				case "REVERT_VERSION" -> " · revertible";
				default -> "";
			};
			g.text(font, TextUtil.ellipsize(font, o.text() + undo, w - 22), x + 15, ry + 10, UiBits.muted(), false);
		}
		if (ops.size() > fit) {
			TextUtil.Scroll sc = new TextUtil.Scroll().update(ops.size(), fit);
			sc.scrollBy(-1_000_000);
			sc.scrollBy(scroll);
			Panels.scrollbar(g, x + w - 6, y + 1, h - 2, sc, false);
		}
		if (view.historyTotal() > ops.size()) {
			String more = "the newest " + ops.size() + " of " + view.historyTotal();
			g.text(font, more, x + w - 8 - font.width(more), y + h - 11, UiBits.muted(), false);
		}
	}

	// ------------------------------------------------------------------ claim and settings

	private int fact(GuiGraphicsExtractor g, int x, int y, int w, String label, String value) {
		g.text(font, label, x, y, UiBits.muted(), false);
		int lw = 90;
		List<String> lines = TextUtil.wrapPlain(font, value, w - lw);
		for (String l : lines) {
			g.text(font, l, x + lw, y, UiBits.ink(), false);
			y += 10;
		}
		return y + 4;
	}

	private void drawClaim(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		Panels.inset(g, x, y, w, h);
		var c = view.claim();
		int fx = x + 8, fy = y + 8, fw = w - 16;
		fy = fact(g, fx, fy, fw, "Size", c.side() + " x " + c.side() + " blocks (" + c.size() + ")");
		fy = fact(g, fx, fy, fw, "Centre", c.centerX() + ", " + c.centerZ() + " in " + c.dimension().replace("minecraft:", ""));
		fy = fact(g, fx, fy, fw, "In use", UiBits.plural(view.buildings().size(), "building", "buildings") + (view.roads() > 0 ? ", "
			+ UiBits.plural(view.roads(), "road", "roads") : ""));
		fact(g, fx, fy, fw, "Growing", "Expand takes the next size, up to 2049 x 2049, where no other settlement is. A street of buildings stays within 257 x 257 of the stone;"
			+ " the rest of a big claim is room for districts.");
	}

	private void drawSettings(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		Panels.inset(g, x, y, w, h);
		int fx = x + 8, fy = y + 8, fw = w - 16;
		fy = fact(g, fx, fy, fw, "Permission", switch (view.permission()) {
			case "OBSERVER" -> "Observer: asks about everything";
			case "PROPOSALS" -> "Proposals: you approve each project";
			case "AUTONOMOUS" -> "Autonomous: asks only before demolitions and new districts";
			case "FULL" -> "Full: acts on its own; the history can undo it";
			default -> view.permission();
		});
		fy = fact(g, fx, fy, fw, "Difficulty", switch (view.difficulty()) {
			case "PATRON" -> "Patron: free, instant builds";
			case "SUPPLIED" -> "Supplied: builds from a stockpile";
			case "HARDCORE" -> "Hardcore: you gather everything";
			default -> view.difficulty();
		});
		fact(g, fx, fy, fw, "Changing", "These are set when the settlement is founded; changing them, and the steward's weekly spending allowance, come with its proposals.");
	}

	// ------------------------------------------------------------------ input

	@Override
	protected boolean clickOther(double mx, double my) {
		if (tab != Tab.BUILDINGS) return false;
		for (int i = 0; i < rowRects.size(); i++) {
			int[] r = rowRects.get(i);
			if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
				open(rowIds.get(i));
				return true;
			}
		}
		for (int i = 0; i < mapRects.size(); i++) {
			int[] r = mapRects.get(i);
			if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
				open(mapIds.get(i));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		scroll = Math.max(0, scroll - (int) Math.signum(dy));
		return true;
	}

	/** DevBridge: open a building's panel. */
	public void openBuilding(String siteId) {
		open(siteId);
	}

	public SettlementView view() {
		return view;
	}
}
