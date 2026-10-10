package dev.larattalabs.steward.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.steward.client.text.TextFieldView;
import dev.larattalabs.steward.client.text.TextModel;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import dev.larattalabs.steward.inbox.InboxModel;
import dev.larattalabs.steward.net.StewardNet;
import dev.larattalabs.steward.service.Actions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The steward's inbox, laid out like AgentCraft's hub inbox and Architect's set view: on the left every build in progress, grouped "Needs you" and
 * "Building" (a status dot, the name, what it waits for or is doing); on the right the selected build: a status pill and the spend, a progress bar, what it
 * waits for in words, and its buildings as two-line rows in an inset (a dot per stage, a star on landmarks, size and named parts). A waiting massing carries
 * its own Approve and Redirect chips; redirect notes open under the list. The actions that fit sit at the bottom: approve the style bible, approve every
 * massing, show or hide the ghosts, raise a paused budget (from the minimum that clears the pause), place, cancel (asks twice). The panel takes the height
 * its content needs. Decisions go to the server; the screen shows what it sent until the server's next inbox arrives. Client thread.
 */
public final class InboxScreen extends KitScreen {
	private static final int MAX_W = 480;
	private static final int LIST_W = 128;
	private static final int ROW_H = 22;
	private @Nullable String selectedId;
	private @Nullable String redirectLot;
	private boolean confirmCancel;
	private boolean ghosts = true;
	private long sentAtVersion = -1;
	private int lotScroll;
	private int[] listArea = new int[4];
	private final List<String> listIds = new ArrayList<>();
	private final List<int[]> listRows = new ArrayList<>();

	public InboxScreen(@Nullable String settlementId) {
		super("Steward");
		this.selectedId = settlementId == null || settlementId.isEmpty() ? null : settlementId;
	}

	private InboxModel.@Nullable Entry selected() {
		List<InboxModel.Entry> all = ClientInbox.entries();
		if (all.isEmpty()) return null;
		for (InboxModel.Entry e : all) if (e.key().equals(selectedId)) return e;
		// opened for a settlement: its build first, else its updates
		for (InboxModel.Entry e : all) if (e.settlementId().equals(selectedId)) return e;
		InboxModel.Entry pick = all.stream().filter(InboxModel.Entry::waiting).findFirst().orElse(all.get(0));
		selectedId = pick.key();
		return pick;
	}

	private void select(String id) {
		if (id.equals(selectedId)) return;
		selectedId = id;
		redirectLot = null;
		field = null;
		fieldFocused = false;
		confirmCancel = false;
		lotScroll = 0;
		status = null;
	}

	private void send(String action, String lot, String text, double amount, String what) {
		InboxModel.Entry e = selected();
		ClientPlayNetworking.send(new StewardNet.Decide(e == null ? selectedId : e.settlementId(), action, lot, text, amount));
		sentAtVersion = ClientInbox.version();
		say(what + "...", false);
		confirmCancel = false;
		redirectLot = null;
		field = null;
		fieldFocused = false;
	}

	private void startRedirect(String lot) {
		redirectLot = lot;
		field = new TextModel(Actions.MAX_NOTES);
		fieldFocused = true;
	}

	@Override
	protected void submitField() {
		InboxModel.Entry e = selected();
		if (e == null || field == null) return;
		String v = field.value().strip();
		if (redirectLot != null) {
			if (v.isEmpty()) {
				say("Say what should change.", true);
				return;
			}
			send("redirect", redirectLot, v, 0, "Sending " + redirectLot + " back");
		} else if ("BUDGET".equals(e.decision())) {
			try {
				double usd = Double.parseDouble(v.replace("$", ""));
				if (usd < e.minRaiseUsd()) {
					say(String.format("At least $%.0f, or it pauses again at once.", e.minRaiseUsd()), true);
					return;
				}
				send("raise", "", "", usd, String.format("Raising the budget to $%.0f", usd));
			} catch (NumberFormatException ex) {
				say("A number of dollars, please.", true);
			}
		}
	}

	// ------------------------------------------------------------------ layout

	/** The pill family and label of a build's state. */
	private static String[] pill(InboxModel.Entry e) {
		return switch (e.decision()) {
			case "BIBLE" -> new String[] {"waiting", "style bible"};
			case "MASSINGS" -> new String[] {"waiting", "massings"};
			case "BUDGET" -> new String[] {"waiting", "budget paused"};
			case "PLACEMENT" -> new String[] {"waiting", "ready to place"};
			case "UPDATE" -> new String[] {"done", "update available"};
			default -> new String[] {"working", "working"};
		};
	}

	/** A building's dot family and stage word. */
	private static String[] stage(InboxModel.Lot l) {
		if ("update".equals(l.stage())) return l.waiting() ? new String[] {"done", "update"} : new String[] {"error", "cannot update now"};
		if (l.waiting()) return new String[] {"waiting", "massing ready"};
		return switch (l.stage()) {
			case "done" -> new String[] {"done", "designed"};
			case "failed" -> new String[] {"error", "failed"};
			case "detail" -> new String[] {"working", "detailing"};
			case "massing" -> new String[] {"working", "massing"};
			case "approval" -> new String[] {"waiting", "massing ready"};
			case "" -> new String[] {"idle", "not started"};
			default -> new String[] {"working", l.stage()};
		};
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (sentAtVersion >= 0 && ClientInbox.version() > sentAtVersion) {
			sentAtVersion = -1;
			status = null;
		}
		List<InboxModel.Entry> all = ClientInbox.entries();
		InboxModel.Entry e = selected();
		Kit.Padding pad = Kit.padding("panel_paper");
		boolean twoPane = all.size() > 1 && width >= 360;
		int w = Math.min(twoPane ? MAX_W : 400, width - 16);
		int inner = w - pad.left() - pad.right();
		int dw = twoPane ? inner - LIST_W - 8 : inner;
		// height: what the content needs, within the screen
		// the tallest build's height, so the panel does not jump when the player walks the list
		int contentH = 40;
		for (InboxModel.Entry en : all) contentH = Math.max(contentH, detailHeight(en, dw));
		if (twoPane) contentH = Math.max(contentH, listHeight(all));
		int h = Math.min(height - 16, pad.top() + 18 + contentH + 8 + 20 + 18 + pad.bottom());
		int x = (width - w) / 2;
		int y = Math.max(8, (height - h) / 2);
		Panels.panel(g, x, y, w, h);
		int cx = x + pad.left();
		int top = y + pad.top();
		Panels.header(g, font, "Steward", cx - 2, top - 2, inner + 4);
		String count = ClientInbox.waiting() > 0 ? ClientInbox.waiting() + " waiting for you" : all.isEmpty() ? "" : "nothing waits for you";
		g.text(font, count, cx + inner - font.width(count), top, UiBits.muted(), false);
		int bodyTop = top + 18;
		int footerY = y + h - pad.bottom() - 10;
		int actionsY = footerY - 10 - 20;
		if (e == null) {
			Panels.inset(g, cx, bodyTop, inner, actionsY - bodyTop - 4);
			Panels.dot(g, "idle", cx + 8, bodyTop + 9, false);
			g.text(font, "Nothing is being built.", cx + 20, bodyTop + 8, UiBits.ink(), false);
			int ly = bodyTop + 20;
			for (String l : TextUtil.wrapPlain(font, "Claim land with a Founding Stone and describe what you want; the steward asks here when it needs you.", inner - 28)) {
				g.text(font, l, cx + 20, ly, UiBits.muted(), false);
				ly += 10;
			}
			drawFooter(g, cx, footerY, inner, false);
			return;
		}
		int dx = cx;
		if (twoPane) {
			// the list takes the height of its rows, not the column's
			drawList(g, all, cx, bodyTop, LIST_W, Math.min(actionsY - bodyTop - 4, listHeight(all)), mouseX, mouseY);
			dx = cx + LIST_W + 8;
		}
		drawDetail(g, e, dx, bodyTop, dw, actionsY - 4, mouseX, mouseY);
		drawActions(g, e, cx, actionsY, inner, mouseX, mouseY);
		drawFooter(g, cx, footerY, inner, all.size() > 1);
	}

	private int listHeight(List<InboxModel.Entry> all) {
		long waiting = all.stream().filter(InboxModel.Entry::waiting).count();
		int groups = (waiting > 0 ? 1 : 0) + (waiting < all.size() ? 1 : 0);
		return groups * 14 + all.size() * ROW_H + 6;
	}

	private void drawList(GuiGraphicsExtractor g, List<InboxModel.Entry> all, int x, int y, int w, int h, int mx, int my) {
		Panels.inset(g, x, y, w, h);
		listArea = new int[] {x, y, w, h};
		listIds.clear();
		listRows.clear();
		int ry = y + 4;
		for (boolean needs : new boolean[] {true, false}) {
			List<InboxModel.Entry> group = all.stream().filter(en -> en.waiting() == needs).toList();
			if (group.isEmpty()) continue;
			String label = needs ? "Needs you · " + group.size() : "Building";
			g.text(font, label, x + 5, ry + 1, UiStyle.CLAY_DARK, false);
			Panels.divider(g, x + 9 + font.width(label), ry + 5, Math.max(0, w - 18 - font.width(label)));
			ry += 14;
			for (InboxModel.Entry en : group) {
				if (ry + ROW_H > y + h) break;
				boolean sel = en == selected();
				boolean hov = mx >= x && mx < x + w && my >= ry - 2 && my < ry + ROW_H - 2;
				if (sel) g.fill(x + 2, ry - 2, x + w - 2, ry + ROW_H - 3, 0x30B4553A);
				else if (hov) g.fill(x + 2, ry - 2, x + w - 2, ry + ROW_H - 3, 0x14000000);
				Panels.dot(g, pill(en)[0], x + 6, ry + 1, false);
				g.text(font, TextUtil.ellipsize(font, en.name(), w - 22), x + 16, ry, needs ? UiBits.ink() : UiBits.muted(), false);
				g.text(font, TextUtil.ellipsize(font, pill(en)[1], w - 22), x + 16, ry + 10, UiBits.muted(), false);
				listIds.add(en.key());
				listRows.add(new int[] {ry - 2, ry + ROW_H - 2});
				ry += ROW_H;
			}
		}
	}

	private int detailHeight(InboxModel.Entry e, int w) {
		int h = 14 + 10; // pill row, progress
		h += TextUtil.wrapPlain(font, e.headline(), w).size() * 10 + 6;
		h += Math.min(e.lots().size(), 8) * ROW_H + 8;
		if (field != null) h += TextFieldView.BASE_H + 10 + (redirectLot != null ? 12 : 0);
		return h;
	}

	private void drawDetail(GuiGraphicsExtractor g, InboxModel.Entry e, int x, int y, int w, int bottom, int mx, int my) {
		// the pill, the name, the spend
		String[] p = pill(e);
		int pw = UiBits.dotPill(g, font, p[0], p[1], x, y - 1, UiBits.ink());
		String spend = "UPDATE".equals(e.decision()) ? "" : String.format("$%.2f of $%.0f", e.spentUsd(), e.budgetUsd());
		g.text(font, spend, x + w - font.width(spend), y + 1, e.spentUsd() >= e.budgetUsd() * 0.8 ? UiStyle.CLAY_DARK : UiBits.muted(), false);
		g.text(font, TextUtil.ellipsize(font, e.name(), w - pw - font.width(spend) - 14), x + pw + 6, y + 1, UiBits.ink(), false);
		y += 14;
		boolean update = "UPDATE".equals(e.decision());
		if (!update) {
			// progress: buildings designed
			int total = Math.max(1, e.lots().size());
			String prog = e.done() + " of " + e.lots().size() + " designed";
			Panels.progress(g, x, y + 1, w - font.width(prog) - 8, (double) e.done() / total, e.waiting() ? "clay" : "sage");
			g.text(font, prog, x + w - font.width(prog), y, UiBits.muted(), false);
			y += 12;
		}
		for (String l : TextUtil.wrapPlain(font, e.headline(), w)) {
			g.text(font, l, x, y, e.waiting() ? UiStyle.CLAY_DARK : UiBits.ink(), false);
			y += 10;
		}
		y += 6;
		// the buildings
		int fieldH = field != null ? TextFieldView.BASE_H + 10 + (redirectLot != null ? 12 : 0) : 0;
		int listH = Math.min(bottom - fieldH - y, e.lots().size() * ROW_H + 6);
		if (listH > 10 && !e.lots().isEmpty()) {
			Panels.inset(g, x, y, w, listH);
			int fit = Math.max(1, (listH - 6) / ROW_H);
			lotScroll = Math.max(0, Math.min(lotScroll, e.lots().size() - fit));
			boolean massings = "MASSINGS".equals(e.decision()) && sentAtVersion < 0;
			for (int i = lotScroll; i < Math.min(e.lots().size(), lotScroll + fit); i++) {
				InboxModel.Lot l = e.lots().get(i);
				int ry = y + 4 + (i - lotScroll) * ROW_H;
				String[] st = stage(l);
				Panels.dot(g, st[0], x + 5, ry + 1, false);
				int actionsW = 0;
				if ("UPDATE".equals(e.decision()) && sentAtVersion < 0) {
					int ax = x + w - 6 - (e.lots().size() > fit ? 6 : 0);
					int cy = ry + (20 - CHIP_H) / 2 - 1;
					String pv = "Preview";
					ax -= chipWidth(pv);
					chip(g, pv, ax, cy, false, true, mx, my, () -> send("update_preview", l.id(), "", 0, "Showing the change on " + l.role()));
					if (l.waiting()) {
						String up = "Update";
						ax -= chipWidth(up) + 3;
						chip(g, up, ax, cy, true, true, mx, my, () -> send("update_apply", l.id(), "", 0, "Updating " + l.role()));
					}
					actionsW = x + w - ax + 4;
				} else if (massings && l.waiting()) {
					// a fixed column on the right, the chips centred on the two-line row
					int ax = x + w - 6 - (e.lots().size() > fit ? 6 : 0);
					int cy = ry + (20 - CHIP_H) / 2 - 1;
					String rd = "Redirect…";
					ax -= chipWidth(rd);
					chip(g, rd, ax, cy, l.id().equals(redirectLot), true, mx, my, () -> startRedirect(l.id()));
					String ap = "Approve";
					ax -= chipWidth(ap) + 3;
					chip(g, ap, ax, cy, true, true, mx, my, () -> send("approve", l.id(), "", 0, "Approving " + l.id()));
					actionsW = x + w - ax + 4;
				}
				String title = l.role() + (l.landmark() ? " ★" : "");
				g.text(font, TextUtil.ellipsize(font, title, w - 18 - actionsW), x + 15, ry, UiBits.ink(), false);
				// short: the lot, the stage, the size (the named parts are in the redirect prompt)
				String size = l.detail().contains(" (") ? l.detail().substring(0, l.detail().indexOf(" (")) : l.detail();
				// with chips on the row its stage goes without saying
				String sub = "update".equals(l.stage()) ? l.detail() : l.id() + (actionsW > 0 ? "" : " · " + st[1]) + (size.isEmpty() ? "" : " · " + size.replace(", ", " · "));
				g.text(font, TextUtil.ellipsize(font, sub, w - 20 - actionsW), x + 15, ry + 10, UiBits.muted(), false);
			}
			if (e.lots().size() > fit) {
				TextUtil.Scroll sc = new TextUtil.Scroll().update(e.lots().size(), fit);
				sc.scrollBy(-1_000_000);
				sc.scrollBy(lotScroll);
				Panels.scrollbar(g, x + w - 6, y + 1, listH - 2, sc, false);
			}
			y += listH + 6;
		}
		// the field: redirect notes for one massing, or the new budget
		if (field != null) {
			if (redirectLot != null) {
				String parts = e.lots().stream().filter(l -> l.id().equals(redirectLot)).map(InboxModel.Lot::detail).findFirst().orElse("");
				g.text(font, TextUtil.ellipsize(font, "How should " + redirectLot + " change?" + (parts.isEmpty() ? "" : "  " + parts), w), x, y, UiBits.ink(), false);
				y += 12;
			}
			TextFieldView.Style st = redirectLot != null
				? new TextFieldView.Style(null, 0, "e.g. taller stilts, a wraparound porch; Enter sends", null, field.length() + "/" + Actions.MAX_NOTES, UiBits.muted(), 2)
				: new TextFieldView.Style("New budget  $ ", UiStyle.CLAY_DARK, "USD", null, String.format("at least $%.0f", e.minRaiseUsd()), UiBits.muted(), 1);
			drawField(g, x, y, w, st);
		}
	}

	private void drawActions(GuiGraphicsExtractor g, InboxModel.Entry e, int x, int y, int w, int mx, int my) {
		int bx = x;
		boolean idle = sentAtVersion < 0;
		switch (e.decision()) {
			case "BIBLE" -> bx += button(g, "Approve the style", 1, true, false, idle, bx, y, mx, my, () -> send("approve", "", "", 0, "Approving the style bible")) + 6;
			case "MASSINGS" -> {
				if (redirectLot != null) {
					bx += button(g, "Send back", 1, true, false, idle, bx, y, mx, my, this::submitField) + 6;
					bx += button(g, "Back", 2, false, false, true, bx, y, mx, my, () -> {
						redirectLot = null;
						field = null;
					}) + 6;
				} else {
					long n = e.lots().stream().filter(InboxModel.Lot::waiting).count();
					bx += button(g, "Approve all " + n, 1, true, false, idle, bx, y, mx, my, () -> send("approve", "", "", 0, "Approving " + n + " massings")) + 6;
					bx += button(g, ghosts ? "Hide ghosts" : "Show ghosts", 2, false, false, true, bx, y, mx, my, () -> {
						ghosts = !ghosts;
						send(ghosts ? "show" : "hide", "", "", 0, ghosts ? "Showing the massings" : "Hiding the massings");
					}) + 6;
				}
			}
			case "BUDGET" -> {
				if (field == null) {
					field = new TextModel(6);
					field.set(String.valueOf((int) e.minRaiseUsd()));
					fieldFocused = false;
				}
				bx += button(g, "Raise the budget", 1, true, false, idle, bx, y, mx, my, this::submitField) + 6;
			}
			case "PLACEMENT" -> bx += button(g, "Place it", 1, true, false, idle, bx, y, mx, my, () -> send("approve", "", "", 0, "Placing " + e.name())) + 6;
			case "UPDATE" -> {
				long n = e.lots().stream().filter(InboxModel.Lot::waiting).count();
				bx += button(g, "Update all " + n, 1, true, false, idle && n > 0, bx, y, mx, my, () -> send("update_apply", "", "", 0, "Updating " + n + " buildings")) + 6;
				bx += button(g, "Skip these versions", 2, false, false, idle, bx, y, mx, my, () -> send("update_skip", "", "", 0, "Skipping")) + 6;
				return;
			}
			default -> {
			}
		}
		String cancel = confirmCancel ? "Really cancel?" : "Cancel build";
		int cw = buttonWidth(cancel, 9);
		button(g, cancel, 9, false, true, idle, x + w - cw, y, mx, my, () -> {
			if (confirmCancel) send("cancel", "", "", 0, "Cancelling");
			else confirmCancel = true;
		});
	}

	private void drawFooter(GuiGraphicsExtractor g, int cx, int fy, int inner, boolean several) {
		if (status != null) {
			g.text(font, TextUtil.ellipsize(font, status, inner), cx, fy - 2, statusError ? UiBits.errorText() : UiBits.muted(), false);
			return;
		}
		String[] hints = several ? new String[] {"1-9", "act", "↑↓", "build", "Esc", "close"} : new String[] {"1-9", "act", "Esc", "close"};
		if (UiBits.hintsWidth(font, hints) <= inner) UiBits.hints(g, font, cx, fy - 4, false, hints);
	}

	@Override
	protected boolean keyOther(KeyEvent e) {
		List<InboxModel.Entry> all = ClientInbox.entries();
		if (all.size() < 2) return false;
		int i = Math.max(0, all.indexOf(selected()));
		if (e.key() == InputConstants.KEY_DOWN || e.key() == InputConstants.KEY_RIGHT) i = (i + 1) % all.size();
		else if (e.key() == InputConstants.KEY_UP || e.key() == InputConstants.KEY_LEFT) i = (i - 1 + all.size()) % all.size();
		else return false;
		select(all.get(i).key());
		return true;
	}

	@Override
	protected boolean clickOther(double mx, double my) {
		if (mx < listArea[0] || mx >= listArea[0] + listArea[2]) return false;
		for (int i = 0; i < listRows.size(); i++) {
			if (my >= listRows.get(i)[0] && my < listRows.get(i)[1]) {
				select(listIds.get(i));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		lotScroll = Math.max(0, lotScroll - (int) Math.signum(dy));
		return true;
	}

	/** DevBridge: start a redirect of one massing. */
	public void redirect(String lot) {
		startRedirect(lot);
	}
}
