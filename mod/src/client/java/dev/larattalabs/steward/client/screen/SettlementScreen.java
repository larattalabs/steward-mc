package dev.larattalabs.steward.client.screen;

import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.labui.client.text.TextFieldView;
import dev.larattalabs.labui.client.text.TextModel;
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
 * The settlement screen (docs/PLAN.md "Interface", phase 3a): tabs for its buildings (a list beside a top-down map of the claim, with its legend; a building
 * opens its panel), its history (the change log as operations, newest first, with Undo for the newest undoable one), its claim (size, Expand, the land
 * check, the stockpile, the player's own builds, districts, quarters, protected ground) and its settings. Each button sends the action its command takes
 * ({@code Actions.decide}): the same path, refusals and answers. The keyboard: Tab between tabs, the arrows along the rows and across the map, Enter to
 * open, the number keys for the buttons. Client thread.
 */
public final class SettlementScreen extends KitScreen {
	enum Tab { BUILDINGS, HISTORY, CLAIM, SETTINGS }

	private static final int MAX_W = 500;
	private static final int ROW_H = 22;
	/** The reach of "Claim my build here" (the command's 1-15): a house's floor round the player. */
	static final int CLAIM_REACH = 8;
	private final SettlementView view;
	private Tab tab = Tab.BUILDINGS;
	private int scroll;
	private @Nullable String hovered;
	/** The building the keyboard is on (the arrows), drawn as selected in the list and on the map; null until an arrow is pressed. */
	private @Nullable String selected;
	/** Undo asks twice, as the building panel's revert does. */
	private boolean confirmUndo;
	/** The Claim tab's district name (optional; "" names it by its way), the command's {@code district found <way> <name>}. */
	private final TextModel districtName = new TextModel(40);
	private final List<String> rowIds = new ArrayList<>();
	private final List<int[]> rowRects = new ArrayList<>();
	private final List<int[]> mapRects = new ArrayList<>();
	private final List<String> mapIds = new ArrayList<>();

	public SettlementScreen(SettlementView view) {
		super(view.name());
		this.view = view;
	}

	/** A refreshed screen (the server's view after an action) keeps what the player was on: its tab, place, selection, typed name and status line. */
	public void keepFrom(SettlementScreen was) {
		setTab(was.tab);
		scroll = was.scroll;
		selected = was.selected != null && view.building(was.selected) != null ? was.selected : null;
		districtName.set(was.districtName.value());
		districtSize = was.districtSize;
		// the status line is not kept: "Undoing..." would outstay the undo (its lines are in the chat)
	}

	/** Shows this screen, or the panel of {@code siteId} on it when that building still stands (a refresh while the panel was open). */
	public void setScreenFor(net.minecraft.client.Minecraft mc, String siteId) {
		Building b = view.building(siteId);
		mc.gui.setScreen(b != null ? new BuildingScreen(view, b, this) : this);
	}

	private void setTab(Tab t) {
		tab = t;
		scroll = 0;
		confirmUndo = false;
		fieldFocused = false;
		field = t == Tab.CLAIM ? districtName : null;
		if (minecraft != null) minecraft.onTextInputFocusChange(this, field != null);
	}

	private void send(String action, String lot, String what) {
		send(action, lot, what, 0);
	}

	private void send(String action, String lot, String what, double amount) {
		sendText(action, lot, "", what, amount);
	}

	private void sendText(String action, String lot, String text, String what, double amount) {
		ClientPlayNetworking.send(new StewardNet.Decide(view.id(), action, lot, text, amount));
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
		String sub = UiBits.plural(view.buildings().size(), "building", "buildings") + " · " + view.claim().side() + " x " + view.claim().side()
			+ (view.busy() ? " · building now" : "");
		Panels.header(g, font, view.name(), sub, UiBits.muted(), cx - 2, top - 2, inner + 4);
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
			tx += chip(g, label, tx, ty, tab == t, true, mouseX, mouseY, () -> setTab(t)) + 3;
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
			case CLAIM -> drawClaim(g, cx, bodyTop, inner, bodyH, mouseX, mouseY);
			case SETTINGS -> drawSettings(g, cx, bodyTop, inner, bodyH, mouseX, mouseY);
		}
		drawActions(g, cx, actionsY, inner, mouseX, mouseY);
		if (status != null) g.text(font, TextUtil.ellipsize(font, status, inner), cx, footerY - 2, statusError ? UiBits.errorText() : UiBits.muted(), false);
		else {
			// the number keys drawn, and "click a building" only where there are buildings to click
			java.util.List<String> hs = new java.util.ArrayList<>();
			String keys = numberHint();
			if (keys != null) hs.addAll(java.util.List.of(keys, "act"));
			if (tab == Tab.BUILDINGS && !view.buildings().isEmpty()) hs.addAll(java.util.List.of("↑↓←→", "choose", "Enter", "open"));
			if (tab == Tab.CLAIM && !fieldFocused) hs.addAll(java.util.List.of("Enter", "name a district"));
			hs.addAll(java.util.List.of("Tab", "next tab", "Esc", "close"));
			UiBits.hints(g, font, cx, footerY - 4, false, hs.toArray(String[]::new));
		}
	}

	/**
	 * The tab's own buttons on the left (1, and 4 on the Claim tab), the Card (2) and Inbox (3) on the right, and between them why a button is off (the
	 * Card before the settlement is described, Undo with nothing to undo).
	 */
	private void drawActions(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
		List<String> why = new ArrayList<>();
		int bx = x;
		switch (tab) {
			case BUILDINGS -> bx += button(g, "Look for updates", 1, false, false, true, bx, y, mx, my, () -> send("updates", "settlement", "Looking for updates")) + 6;
			case HISTORY -> {
				var u = view.undo();
				boolean can = u != null && !view.busy();
				if (u == null) why.add("Undo: nothing to undo.");
				else if (view.busy()) why.add("Undo: once the build is done.");
				String label = confirmUndo && can ? "Really undo it?" : "Undo last project";
				bx += button(g, label, 1, false, confirmUndo && can, can, bx, y, mx, my, () -> {
					// asks twice: an undo takes the project up and restores the land (the command's own path once confirmed)
					if (!confirmUndo) {
						confirmUndo = true;
						say("Undo \"" + u.text() + "\" (the marked row)? Press 1 again.", false);
						return;
					}
					confirmUndo = false;
					send("undo", "settlement", "Undoing");
				}) + 6;
			}
			case CLAIM -> {
				bx += button(g, "Expand the claim", 1, true, false, !view.busy(), bx, y, mx, my, () -> send("expand", "settlement", "Expanding")) + 6;
				if (view.busy()) why.add("Expand: once the build is done.");
				bx += button(g, "Check the land", 4, false, false, true, bx, y, mx, my, () -> send("land_check", "settlement", "The steward looks over the land")) + 6;
			}
			case SETTINGS -> {
			}
		}
		if (!view.described()) why.add("Card: describe it first.");
		int inboxW = buttonWidth("Inbox", 3), cardW = buttonWidth("Card", 2);
		int ix = x + w - inboxW, cardX = ix - 6 - cardW;
		button(g, "Card", 2, false, false, view.described(), cardX, y, mx, my, () -> send("card", "", "Opening the card"));
		button(g, "Inbox", 3, false, false, true, ix, y, mx, my, () -> {
			if (minecraft != null) minecraft.gui.setScreen(new InboxScreen(view.id()));
		});
		int room = cardX - 8 - bx;
		// one line per button that is off, each named ("Card: describe it first.")
		if (!why.isEmpty() && room > 30) {
			int ly = why.size() > 1 ? y + 1 : y + 6;
			for (int i = 0; i < Math.min(2, why.size()); i++) g.text(font, TextUtil.ellipsize(font, why.get(i), room), bx + 2, ly + i * 10, UiBits.muted(), false);
		}
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
		// the map's square, and its legend under it
		int mapSize = map ? Math.min(h - LEGEND_H, w / 2 - 8) : 0;
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
			if (b.siteId().equals(selected)) Panels.selectedRow(g, x + 2, ry - 2, lw - 4, ROW_H - 1);
			else if (hov) Panels.hoverRow(g, x + 2, ry - 2, lw - 4, ROW_H - 1);
			if (hov) hovered = b.siteId();
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
		if (map) {
			drawMap(g, x + lw + 8, y, mapSize, mx, my);
			drawLegend(g, x + lw + 8, y + mapSize + 3, mapSize);
		}
	}

	static final int LEGEND_H = 35;

	/** A map footprint's fill for its state's family (each mark also gets a dark edge: at least 3:1 against the inset whatever its fill). */
	static int fill(String family) {
		return switch (family) {
			case "waiting" -> UiStyle.CLAY;
			case "working" -> UiStyle.BRASS;
			case "done" -> UiStyle.SAGE;
			default -> IDLE;
		};
	}

	/** An idle (planned, held) building's fill: a stone grey, darker than the disabled text it was (#A39B8E, about 2.1:1 on the inset). */
	static final int IDLE = 0xFF857B6E;
	/** The edge of every footprint, and the stone. */
	static final int MARK_EDGE = UiStyle.WALNUT;
	/** The claim's edge: opaque clay (3.76:1 on the inset, lab-ui's figure for CLAY_DARK). */
	static final int CLAIM_EDGE = UiStyle.CLAY_DARK;

	private void box(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb) {
		g.fill(x0, y0, x1, y0 + 1, argb);
		g.fill(x0, y1 - 1, x1, y1, argb);
		g.fill(x0, y0, x0 + 1, y1, argb);
		g.fill(x1 - 1, y0, x1, y1, argb);
	}

	/** What the map's marks mean, in two columns of three under it: the four states, the claim's edge and the stone. */
	private void drawLegend(GuiGraphicsExtractor g, int x, int y, int w) {
		String[][] items = {{"done", "built"}, {"working", "building"}, {"waiting", "update"}, {"idle", "idle"}, {"claim", "claim"}, {"stone", "stone"}};
		int col = w / 2;
		for (int i = 0; i < items.length; i++) {
			int ix = x + (i % 2) * col, iy = y + (i / 2) * 11;
			switch (items[i][0]) {
				case "claim" -> box(g, ix, iy, ix + 8, iy + 8, CLAIM_EDGE);
				case "stone" -> g.fill(ix + 2, iy + 2, ix + 6, iy + 6, MARK_EDGE);
				default -> {
					g.fill(ix, iy, ix + 8, iy + 8, fill(items[i][0]));
					box(g, ix, iy, ix + 8, iy + 8, MARK_EDGE);
				}
			}
			g.text(font, TextUtil.ellipsize(font, items[i][1], col - 12), ix + 11, iy, UiBits.muted(), false);
		}
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
			box(g, ox - r, oy - r, ox + r, oy + r, CLAIM_EDGE);
		}
		for (Building b : view.buildings()) {
			int x0 = ox + (int) Math.floor((b.minX() - c.centerX()) * scale), x1 = ox + (int) Math.ceil((b.maxX() + 1 - c.centerX()) * scale);
			int z0 = oy + (int) Math.floor((b.minZ() - c.centerZ()) * scale), z1 = oy + (int) Math.ceil((b.maxZ() + 1 - c.centerZ()) * scale);
			x1 = Math.max(x1, x0 + 3);
			z1 = Math.max(z1, z0 + 3);
			boolean hov = b.siteId().equals(hovered) || (mx >= x0 && mx < x1 && my >= z0 && my < z1);
			if (hov) hovered = b.siteId();
			g.fill(x0, z0, x1, z1, fill(state(b)[0]));
			box(g, x0, z0, x1, z1, MARK_EDGE);
			// the hovered or chosen one: a second, outer ring
			if (hov || b.siteId().equals(selected)) {
				box(g, x0 - 2, z0 - 2, x1 + 2, z1 + 2, UiStyle.INK);
				box(g, x0 - 1, z0 - 1, x1 + 1, z1 + 1, UiStyle.CREAM);
			}
			mapRects.add(new int[] {x0, z0, x1 - x0, z1 - z0});
			mapIds.add(b.siteId());
		}
		// the stone (the steward stands by it)
		g.fill(ox - 2, oy - 2, ox + 2, oy + 2, MARK_EDGE);
		String on = hovered != null ? hovered : selected;
		String hint = on != null && view.building(on) != null ? view.building(on).role() : "north is up";
		g.text(font, TextUtil.ellipsize(font, hint, size - 8), x + 4, y + size - 11, UiBits.muted(), false);
	}

	// ------------------------------------------------------------------ history

	static String kindLabel(String kind) {
		return SettlementView.kindLabel(kind);
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
			// the operation Undo takes up (as the server chose it): marked, and named on the button's first press
			boolean next = view.undo() != null && o.op() != null && o.op().equals(view.undo().op());
			if (next) Panels.selectedRow(g, x + 2, ry - 2, w - 4, ROW_H - 1);
			Panels.dot(g, outcomeFamily(o), x + 5, ry + 1, false);
			String when = UiBits.ago(o.at());
			g.text(font, kindLabel(o.kind()), x + 15, ry, UiBits.ink(), false);
			if (next) g.text(font, "Undo takes this", x + 15 + font.width(kindLabel(o.kind())) + 8, ry, attention(), false);
			g.text(font, when, x + w - 8 - font.width(when), ry, UiBits.muted(), false);
			String undo = next ? "" : switch (o.recovery()) {
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

	private int factHeight(int w, String value) {
		return TextUtil.wrapPlain(font, value, w - 90).size() * 10 + 4;
	}

	/** The size a district founded from the Claim tab takes (S or M). */
	private String districtSize = "S";

	/** One row of the Claim tab: its height, and how it draws at a y (rows that don't fit are not drawn, and their buttons not offered). */
	private record Row(int h, java.util.function.IntConsumer draw) {}

	/**
	 * The Claim tab: its facts and the actions on the land, as rows that scroll (the wheel, or the arrows) when they don't fit. Each button is its
	 * command's: {@code check}, {@code stockpile add}, {@code builds claim|adopt|release}, {@code district found <way> [name]}, {@code district quarter},
	 * {@code protect here|clear}.
	 */
	private void drawClaim(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		Panels.inset(g, x, y, w, h);
		var c = view.claim();
		int fx = x + 8, fw = w - 16, lx = fx + 90;
		List<Row> rows = new ArrayList<>();
		String size = c.side() + " x " + c.side() + " blocks (" + c.size() + "), centred on " + c.centerX() + ", " + c.centerZ() + " in " + c.dimension().replace("minecraft:", "");
		rows.add(new Row(factHeight(fw, size), ry -> fact(g, fx, ry, fw, "Size", size)));
		String use = UiBits.plural(view.buildings().size(), "building", "buildings") + (view.roads() > 0 ? ", " + UiBits.plural(view.roads(), "road", "roads") : "")
			+ ". A street holds about 12; past that, a district gives the next ones a street of their own.";
		rows.add(new Row(factHeight(fw, use), ry -> fact(g, fx, ry, fw, "In use", use)));
		// built from materials: what the stockpile holds and what the builds still need; a crate added where the player stands (/steward stockpile <id> add)
		if (!view.stockpile().isEmpty() || !"PATRON".equals(view.difficulty())) {
			String addLabel = "Add a crate here";
			int bw = optionWidth(addLabel);
			String sp = view.stockpile().isEmpty() ? "None yet: stand in the town and add a crate." : String.join(" ", view.stockpile()).replaceFirst("^Stockpile: ", "");
			int sh = Math.max(ROW_BUTTON_H + 2, factHeight(fw - bw - 6, sp));
			rows.add(new Row(sh, ry -> {
				fact(g, fx, ry, fw - bw - 6, "Stockpile", sp);
				option(g, addLabel, x + w - 8 - bw, ry - 4, false, true, mx, my, () -> send("stockpile_add", "", "Adding a crate where you stand"));
			}));
		}
		// what the player built by hand in the claim (Architect PT): the steward builds round it and never over it; taken on, claimed or let go here
		String claimLabel = "Claim my build here";
		int cbw = optionWidth(claimLabel);
		rows.add(new Row(ROW_BUTTON_H + 2, ry -> {
			g.text(font, "Hand-built", fx, ry + 5, UiBits.muted(), false);
			String what = view.playerBuilds().isEmpty() ? "None tracked. Built one before? Stand in it and claim it." : "Kept as built.";
			g.text(font, TextUtil.ellipsize(font, what, fw - 90 - cbw - 6), lx, ry + 5, UiBits.ink(), false);
			option(g, claimLabel, x + w - 8 - cbw, ry, false, true, mx, my, () -> send("build_claim", "", "Claiming the build you stand in", CLAIM_REACH));
		}));
		for (var b : view.playerBuilds()) {
			rows.add(new Row(ROW_BUTTON_H + 2, ry -> {
				String adopt = "Look after it", release = "Release";
				int aw = rowButtonWidth(adopt), rw = rowButtonWidth(release);
				int bx = x + w - 8 - rw;
				String line = what(b) + " by " + b.by() + (b.provisional() ? " (still going up)" : "") + " · " + b.id();
				g.text(font, TextUtil.ellipsize(font, line, fw - 90 - aw - rw - 12), lx, ry + 5, UiBits.ink(), false);
				// taken on as one of the settlement's buildings, unchanged (undone from the History tab); released: no longer kept from builds
				rowButton(g, adopt, false, !b.provisional() && !view.busy(), bx - 4 - aw, ry, mx, my, () -> send("build_adopt", b.id(), "Taking it on"));
				rowButton(g, release, false, true, bx, ry, mx, my, () -> send("build_release", b.id(), "Releasing it"));
			}));
		}
		// districts: one each way, founded with a road to the town ($0), named as the player types
		if (view.districts().isEmpty()) rows.add(new Row(12, ry -> {
			g.text(font, "Districts", fx, ry, UiBits.muted(), false);
			g.text(font, "None yet.", lx, ry, UiBits.ink(), false);
		}));
		for (int i = 0; i < view.districts().size(); i++) {
			var d = view.districts().get(i);
			boolean first = i == 0;
			// a region district says where it stands (reserved, being built) and anything the player should know (its road to the town gone)
			String where = "square".equals(d.kind()) ? "" : "reserved".equals(d.state()) ? " · reserved beside the town" : "building".equals(d.state()) ? " · being built" : " · beside the town";
			String line = d.name() + " · " + d.side() + " x " + d.side() + " · " + UiBits.plural(d.buildings(), "building", "buildings") + (d.roads() > 1 ? " · street linked" : "") + where;
			rows.add(new Row(d.note() != null ? 22 : 11, ry -> {
				if (first) g.text(font, "Districts", fx, ry, UiBits.muted(), false);
				g.text(font, TextUtil.ellipsize(font, line, fw - 90), lx, ry, UiBits.ink(), false);
				// what the player should know of it: its own line, so a long name never hides it
				if (d.note() != null) g.text(font, TextUtil.ellipsize(font, "  " + d.note(), fw - 90), lx, ry + 11, attention(), false);
			}));
		}
		TextFieldView.Style nameStyle = new TextFieldView.Style(null, 0, (fieldFocused ? " " : "") + "a name for the next district (optional)", null, null, UiBits.muted(), 1);
		rows.add(new Row(Math.max(ROW_BUTTON_H, fieldHeight(fw - 90, nameStyle)) + 4, ry -> {
			g.text(font, "Name", fx, ry + 5, UiBits.muted(), false);
			drawField(g, lx, ry, Math.min(220, fw - 90), nameStyle);
		}));
		rows.add(new Row(ROW_BUTTON_H + 4, ry -> {
			int bx = lx;
			g.text(font, "Found", fx, ry + 5, UiBits.muted(), false);
			for (String way : new String[] {"N", "E", "S", "W"}) {
				boolean taken = view.districts().stream().anyMatch(d -> d.direction().equals(way));
				String label = switch (way) {
					case "N" -> "North";
					case "E" -> "East";
					case "S" -> "South";
					default -> "West";
				};
				bx += option(g, label, bx, ry, false, !taken && !view.busy(), mx, my, () -> {
					sendText("district_found", way + ":" + districtSize, districtName.value().strip(), "Founding a district", 0);
					districtName.set("");
				}) + 3;
			}
			bx += 6;
			for (String sz : new String[] {"S", "M"}) bx += option(g, sz, bx, ry, districtSize.equals(sz), true, mx, my, () -> districtSize = sz) + 3;
		}));
		// quarters (Noah's Q2): offered in the inbox, built at once beside a town Architect laid out; the side that suits it is chosen for the player
		rows.add(new Row(ROW_BUTTON_H + 6, ry -> {
			int bx = lx;
			g.text(font, "Quarter", fx, ry + 5, UiBits.muted(), false);
			bx += option(g, "By the water", bx, ry, false, !view.busy(), mx, my, () -> sendText("district_quarter", "auto", "along shore", "Offering a quarter by the water", 0)) + 3;
			option(g, "Outpost", bx, ry, false, !view.busy(), mx, my, () -> sendText("district_quarter", "auto", "outpost", "Offering an outpost", 0));
		}));
		// protected areas: ground the steward never builds on
		rows.add(new Row(ROW_BUTTON_H + 4, ry -> {
			g.text(font, "Protected", fx, ry + 5, UiBits.muted(), false);
			int bx = lx;
			bx += option(g, "Protect 8 around me", bx, ry, false, true, mx, my, () -> send("protect", "", "Protecting the ground around you", 8)) + 3;
			option(g, "16", bx, ry, false, true, mx, my, () -> send("protect", "", "Protecting the ground around you", 16));
		}));
		for (var a : view.areas()) {
			rows.add(new Row(ROW_BUTTON_H + 2, ry -> {
				String line = a.label() + " · " + (a.x1() - a.x0() + 1) + " x " + (a.z1() - a.z0() + 1);
				int cw = optionWidth("Clear");
				g.text(font, TextUtil.ellipsize(font, line, fw - 90 - cw - 6), lx, ry + 5, UiBits.ink(), false);
				option(g, "Clear", x + w - 8 - cw, ry, false, true, mx, my, () -> send("unprotect", a.id(), "Clearing the protected area"));
			}));
		}
		// scrolled by whole rows: the first shown is `scroll`; as many as fit after it
		int top = y + 8, bottom = y + h - 4;
		int total = 0;
		for (Row r : rows) total += r.h();
		int fits = 0;
		for (int i = rows.size() - 1, used = 0; i >= 0 && used + rows.get(i).h() <= bottom - top; i--, fits++) used += rows.get(i).h();
		claimRows = rows.size();
		claimMaxScroll = total <= bottom - top ? 0 : rows.size() - fits;
		scroll = Math.max(0, Math.min(scroll, claimMaxScroll));
		int ry = top;
		int shown = 0;
		for (int i = scroll; i < rows.size(); i++) {
			Row r = rows.get(i);
			if (ry + r.h() > bottom) break;
			r.draw().accept(ry);
			ry += r.h();
			shown++;
		}
		if (claimMaxScroll > 0) {
			TextUtil.Scroll sc = new TextUtil.Scroll().update(rows.size(), shown);
			sc.scrollBy(-1_000_000);
			sc.scrollBy(scroll);
			Panels.scrollbar(g, x + w - 6, y + 1, h - 2, sc, false);
		}
	}

	private int claimRows, claimMaxScroll;

	private void drawSettings(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		Panels.inset(g, x, y, w, h);
		int fx = x + 8, fy = y + 8, fw = w - 16;
		// the permission level: a chip each, the current one lit (host only: the server refuses a guest)
		g.text(font, "Permission", fx, fy + 5, UiBits.muted(), false);
		int cx = fx + 90;
		for (String p : new String[] {"OBSERVER", "PROPOSALS", "AUTONOMOUS", "FULL"}) {
			String label = p.charAt(0) + p.substring(1).toLowerCase();
			cx += option(g, label, cx, fy, p.equals(view.permission()), true, mx, my, () -> send("permission", p, "Setting the permission")) + 3;
		}
		fy += ROW_BUTTON_H + 4;
		fy = fact(g, fx, fy, fw, "", switch (view.permission()) {
			case "OBSERVER" -> "Asks about everything.";
			case "PROPOSALS" -> "You approve each project; its ideas wait in the inbox.";
			case "AUTONOMOUS" -> "Builds its own ideas within the weekly allowance; asks before demolitions and new districts.";
			case "FULL" -> "Acts on its own within the weekly allowance; the history can undo it.";
			default -> view.permission();
		});
		// the weekly allowance: what it may spend on its own at Autonomous and Full
		g.text(font, "Allowance", fx, fy + 5, UiBits.muted(), false);
		String a = String.format("$%.0f a week", view.weeklyUsd());
		g.text(font, a, fx + 90, fy + 5, UiBits.ink(), false);
		int ax = fx + 90 + font.width(a) + 8;
		ax += option(g, "- $50", ax, fy, false, view.weeklyUsd() > 50, mx, my, () -> send("allowance", "", "Lowering the allowance", Math.max(dev.larattalabs.steward.service.Actions.MIN_ALLOWANCE,
			view.weeklyUsd() - 50))) + 3;
		option(g, "+ $50", ax, fy, false, view.weeklyUsd() < dev.larattalabs.steward.service.Actions.MAX_ALLOWANCE, mx, my, () -> send("allowance", "", "Raising the allowance",
			Math.min(dev.larattalabs.steward.service.Actions.MAX_ALLOWANCE, view.weeklyUsd() + 50)));
		fy += ROW_BUTTON_H + 4;
		fy = fact(g, fx, fy, fw, "", String.format("Spent on its own this week: $%.0f.", view.spentWeekUsd()));
		fact(g, fx, fy, fw, "Difficulty", switch (view.difficulty()) {
			case "PATRON" -> "Patron: free, instant builds";
			case "SUPPLIED" -> "Supplied: builds from a stockpile";
			case "HARDCORE" -> "Hardcore: you gather everything";
			default -> view.difficulty();
		});
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

	/**
	 * The arrows: on the Buildings tab Up and Down go along the list, Left and Right to the nearest building that way on the map, Enter or Space opens the
	 * chosen one; elsewhere Up and Down scroll the rows.
	 */
	@Override
	protected boolean keyOther(net.minecraft.client.input.KeyEvent e) {
		int k = e.key();
		boolean up = k == com.mojang.blaze3d.platform.InputConstants.KEY_UP, down = k == com.mojang.blaze3d.platform.InputConstants.KEY_DOWN;
		boolean left = k == com.mojang.blaze3d.platform.InputConstants.KEY_LEFT, right = k == com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT;
		if (tab != Tab.BUILDINGS) {
			if (up || down) {
				scroll = Math.max(0, scroll + (down ? 1 : -1));
				return true;
			}
			return false;
		}
		List<Building> all = view.buildings();
		if (all.isEmpty()) return false;
		if (up || down) {
			int at = indexOf(selected);
			selected = all.get(at < 0 ? (down ? 0 : all.size() - 1) : Math.floorMod(at + (down ? 1 : -1), all.size())).siteId();
			keepInView();
			return true;
		}
		if (left || right) {
			selected = nearest(selected, right ? 1 : -1);
			keepInView();
			return true;
		}
		if ((k == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || k == com.mojang.blaze3d.platform.InputConstants.KEY_SPACE || k == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER)
			&& selected != null) {
			open(selected);
			return true;
		}
		return false;
	}

	private int indexOf(@Nullable String siteId) {
		List<Building> all = view.buildings();
		for (int i = 0; i < all.size(); i++) if (all.get(i).siteId().equals(siteId)) return i;
		return -1;
	}

	/** The list scrolls to the chosen row (the rows that fit are counted as the frame drew them). */
	private void keepInView() {
		int at = indexOf(selected);
		int fit = Math.max(1, rowIds.size());
		if (at < scroll) scroll = at;
		else if (at >= scroll + fit) scroll = at - fit + 1;
	}

	/**
	 * The building whose centre is nearest {@code from}'s going east ({@code dir} 1) or west (-1) on the map, its north-south distance counting double; from
	 * none, the westmost or eastmost. Pure on the view.
	 */
	@Nullable String nearest(@Nullable String from, int dir) {
		List<Building> all = view.buildings();
		Building f = from == null ? null : view.building(from);
		if (f == null) return all.stream().min(java.util.Comparator.comparingInt(b -> dir * (b.minX() + b.maxX()))).map(Building::siteId).orElse(null);
		double fx = (f.minX() + f.maxX()) / 2.0, fz = (f.minZ() + f.maxZ()) / 2.0;
		Building best = null;
		double bestD = Double.MAX_VALUE;
		for (Building b : all) {
			double bx = (b.minX() + b.maxX()) / 2.0, bz = (b.minZ() + b.maxZ()) / 2.0;
			if ((bx - fx) * dir <= 0 || b == f) continue;
			double d = Math.abs(bx - fx) + 2 * Math.abs(bz - fz);
			if (d < bestD) {
				bestD = d;
				best = b;
			}
		}
		return best == null ? f.siteId() : best.siteId();
	}

	@Override
	protected void submitField() {
		// Enter in the name box: done typing (the name goes with the way chosen next)
		fieldFocused = false;
	}

	/** Under the "Hand-built" label: "House", or "A build" when Architect can't tell what it is. */
	private static String what(SettlementView.PlayerBuildInfo b) {
		String kind = b.title().replaceFirst("^Hand-built ?", "");
		return kind.isEmpty() ? "A build" : Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		// Tab (Shift+Tab back) moves between the tabs, as the card screen's Tab does, when no text box has the keys
		if (!fieldFocused && e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_TAB) {
			Tab[] all = Tab.values();
			setTab(all[Math.floorMod(tab.ordinal() + (e.hasShiftDown() ? -1 : 1), all.length)]);
			return true;
		}
		// Enter opens the chosen building (KitScreen would give it to a text box; the Buildings tab has none)
		if (tab == Tab.BUILDINGS && keyOther(e)) return true;
		return super.keyPressed(e);
	}

	/** DevBridge and the e2e check: show a tab by its name (buildings, history, claim, settings). */
	public void showTab(String name) {
		setTab(Tab.valueOf(name.toUpperCase(java.util.Locale.ROOT)));
	}

	/** DevBridge: open a building's panel. */
	public void openBuilding(String siteId) {
		open(siteId);
	}

	public SettlementView view() {
		return view;
	}
}
