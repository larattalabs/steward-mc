package dev.larattalabs.steward.client.screen;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.steward.client.text.TextFieldView;
import dev.larattalabs.steward.client.text.TextKeys;
import dev.larattalabs.steward.client.text.TextModel;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import dev.larattalabs.steward.gateway.ProgramPlanner;
import dev.larattalabs.steward.model.BudgetPolicy;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.net.StewardNet;
import dev.larattalabs.steward.service.Actions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;

/**
 * A settlement's concept card, in two columns: on the left what the steward took the words to mean (Site, Style, Purpose as facts with their template as a
 * pill, the avoid list as pills, then the interpretation and assumptions in an inset well that scrolls); on the right the buildings it will design (a row per
 * program entry: a star on landmarks, the role and count, the footprint as a pill, the notes under it). Below, how many to build (-/+ or left/right), the
 * budget, the estimate the runner itself uses ({@link BudgetPolicy#landmarksFor}), and Start. The panel takes the height its content needs. Client thread.
 */
public final class CardScreen extends KitScreen {
	private static final int ROW_H = 22;
	private final StewardNet.Card payload;
	private final ConceptCard card;
	private final int total;
	private final int flagged;
	private final String size;
	private int buildings;
	private boolean budgetEdited;
	private boolean sent;
	private int wellScroll;
	private int programScroll;
	private int[] wellArea = new int[4];
	private int[] programArea = new int[4];

	public CardScreen(StewardNet.Card payload) {
		super(payload.name());
		this.payload = payload;
		this.card = ConceptCard.parse(JsonParser.parseString(payload.cardJson()).getAsJsonObject());
		this.total = ProgramPlanner.total(card);
		this.flagged = card.hasProgram() ? (int) card.program().stream().filter(ConceptCard.Building::landmark).count() : 0;
		this.size = card.site().size() == null ? "M" : card.site().size();
		this.buildings = Math.max(Actions.MIN_BUILDINGS, Math.min(Actions.MAX_BUILDINGS, total));
		field = new TextModel(6);
		field.set(String.valueOf(suggestedBudget()));
		fieldFocused = false;
	}

	private BudgetPolicy.Estimate estimate() {
		return BudgetPolicy.estimate(buildings, BudgetPolicy.landmarksFor(size, buildings, flagged));
	}

	private int suggestedBudget() {
		return (int) Math.min(Actions.MAX_BUDGET, Math.ceil(estimate().usdHigh() / 5.0) * 5);
	}

	private void setBuildings(int n) {
		buildings = Math.max(Actions.MIN_BUILDINGS, Math.min(Actions.MAX_BUILDINGS, n));
		if (!budgetEdited && field != null) field.set(String.valueOf(suggestedBudget()));
	}

	@Override
	protected void submitField() {
		start();
	}

	private void start() {
		if (sent || payload.busy() || field == null) return;
		double usd;
		try {
			usd = Double.parseDouble(field.value().strip().replace("$", ""));
		} catch (NumberFormatException e) {
			say("The budget is a number of dollars.", true);
			return;
		}
		if (usd < Actions.MIN_BUDGET || usd > Actions.MAX_BUDGET) {
			say(String.format("The budget must be $%.0f to $%.0f.", Actions.MIN_BUDGET, Actions.MAX_BUDGET), true);
			return;
		}
		ClientPlayNetworking.send(new StewardNet.Start(payload.settlementId(), buildings, usd));
		sent = true;
		say("Starting. The steward asks in the inbox (Y) when it needs you.", false);
	}

	// ------------------------------------------------------------------ layout

	/** The left column's well: interpretation, tensions, assumptions, wrapped, with their colours. */
	private List<int[]> wellColors = new ArrayList<>();

	private List<String> wellLines(int w) {
		List<String> out = new ArrayList<>();
		wellColors = new ArrayList<>();
		add(out, card.interpretation(), w, UiBits.ink());
		for (ConceptCard.Contradiction c : card.contradictions()) add(out, "Tension: " + c.issue() + " → " + c.resolution(), w, UiStyle.CLAY_DARK);
		for (String a : card.assumptions()) add(out, "Assumed: " + a, w, UiBits.muted());
		return out;
	}

	private void add(List<String> out, String text, int w, int color) {
		if (text == null || text.isBlank()) return;
		for (String l : TextUtil.wrapPlain(font, text, w)) {
			out.add(l);
			wellColors.add(new int[] {color});
		}
	}

	/** A fact row: the label, the wrapped value, a template pill after it; returns its height. */
	private int fact(GuiGraphicsExtractor g, String label, String value, String template, int x, int y, int w, boolean draw) {
		int lw = 50;
		List<String> lines = TextUtil.wrapPlain(font, value, w - lw);
		if (draw) {
			g.text(font, label, x, y, UiStyle.CLAY_DARK, false);
			int ly = y;
			for (String l : lines) {
				g.text(font, l, x + lw, ly, UiBits.ink(), false);
				ly += 10;
			}
			if (template != null && !template.isBlank()) {
				String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
				int px = x + lw + font.width(last) + 4;
				if (px + font.width(template) + 10 > x + w) {
					px = x + lw;
					ly += 1;
				} else ly -= 10;
				Panels.pill(g, font, template, px, ly, UiBits.muted());
			}
		}
		return lines.size() * 10 + (template != null && !template.isBlank() && fitsAfter(lines, template, w - lw) ? 0 : template == null || template.isBlank() ? 0 : 12) + 4;
	}

	private boolean fitsAfter(List<String> lines, String template, int w) {
		String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
		return font.width(last) + 4 + font.width(template) + 10 <= w;
	}

	private int factsHeight(int w) {
		int h = fact(null, "Site", siteText(), card.site().template(), 0, 0, w, false);
		h += fact(null, "Style", card.style().text(), card.style().template(), 0, 0, w, false);
		h += fact(null, "Purpose", card.purpose().text(), card.purpose().template(), 0, 0, w, false);
		if (story() != null) h += fact(null, "Story", story(), null, 0, 0, w, false);
		if (!card.avoid().isEmpty()) h += 16;
		return h;
	}

	private String siteText() {
		return card.site().text();
	}

	/** The right column's tab: the buildings, or the interpretation (with tensions and assumptions). */
	private boolean showNotes;

	private String story() {
		return card.story() == null || card.story().text() == null || card.story().text().isBlank() ? null : card.story().text();
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (fieldFocused && field != null && !field.value().equals(String.valueOf(suggestedBudget()))) budgetEdited = true;
		Kit.Padding pad = Kit.padding("panel_paper");
		int w = Math.min(500, width - 16);
		int inner = w - pad.left() - pad.right();
		boolean twoCol = inner >= 380;
		int colW = twoCol ? (inner - 10) / 2 : inner;
		// heights: the left column (facts + well), the right (program), the controls
		int factsH = factsHeight(colW);
		List<String> well = wellLines(colW - 12);
		int programRows = card.hasProgram() ? card.program().size() : 1;
		int leftH = factsH;
		int rightH = 18 + Math.max(Math.min(programRows, 8) * ROW_H, showNotes ? Math.min(well.size(), 12) * 10 : 0) + 6;
		int bodyH = twoCol ? Math.max(leftH, rightH) : leftH + 8 + rightH;
		int controlsH = 8 + 20 + 12 + 6 + 20 + 18;
		int h = Math.min(height - 16, pad.top() + 18 + bodyH + controlsH + pad.bottom());
		int x = (width - w) / 2;
		int y = Math.max(8, (height - h) / 2);
		Panels.panel(g, x, y, w, h);
		int cx = x + pad.left();
		int top = y + pad.top();
		Panels.header(g, font, payload.name(), cx - 2, top - 2, inner + 4);
		int side = dev.larattalabs.steward.model.ClaimRules.side(payload.claimRadius());
		String meta = "claim " + side + "×" + side + " (" + dev.larattalabs.steward.model.ClaimRules.sizeOf(payload.claimRadius()) + ") · terrain " + card.site().terrain();
		g.text(font, meta, cx + inner - font.width(meta), top, UiBits.muted(), false);
		int bodyTop = top + 18;
		int controlsTop = y + h - pad.bottom() - controlsH;
		int bodyBottom = controlsTop;
		// left: facts, then the well
		int ly = bodyTop;
		ly += fact(g, "Site", siteText(), card.site().template(), cx, ly, colW, true);
		ly += fact(g, "Style", card.style().text(), card.style().template(), cx, ly, colW, true);
		ly += fact(g, "Purpose", card.purpose().text(), card.purpose().template(), cx, ly, colW, true);
		if (story() != null) ly += fact(g, "Story", story(), null, cx, ly, colW, true);
		if (!card.avoid().isEmpty()) {
			g.text(font, "Avoid", cx, ly + 2, UiStyle.CLAY_DARK, false);
			int px = cx + 50;
			for (String a : card.avoid()) {
				int pw = font.width(a) + 10;
				if (px + pw > cx + colW) break;
				px += Panels.pill(g, font, a, px, ly, UiStyle.CLAY_DARK) + 3;
			}
			ly += 16;
		}
		// right (or below): tabs for the buildings and the interpretation
		int rx = twoCol ? cx + colW + 10 : cx;
		int ry = twoCol ? bodyTop - 2 : ly + 4;
		String t1 = "Builds · " + total;
		String t2 = "Interpretation" + (card.assumptions().isEmpty() ? "" : " · " + card.assumptions().size() + " assumed");
		int tx = rx;
		tx += chip(g, t1, tx, ry, !showNotes, true, mouseX, mouseY, () -> showNotes = false) + 3;
		chip(g, t2, tx, ry, showNotes, true, mouseX, mouseY, () -> showNotes = true);
		ry += CHIP_H + 4;
		int listH = Math.max(ROW_H + 6, bodyBottom - 4 - ry);
		Panels.inset(g, rx, ry, colW, listH);
		programArea = new int[] {rx, ry, colW, listH};
		wellArea = programArea;
		if (showNotes) {
			int view = Math.max(1, (listH - 6) / 10);
			wellScroll = Math.max(0, Math.min(wellScroll, well.size() - view));
			int wy = ry + 4;
			for (int i = wellScroll; i < Math.min(well.size(), wellScroll + view); i++) {
				g.text(font, well.get(i), rx + 5, wy, wellColors.get(i)[0], false);
				wy += 10;
			}
			if (well.size() > view) {
				TextUtil.Scroll sc = new TextUtil.Scroll().update(well.size(), view);
				sc.scrollBy(-1_000_000);
				sc.scrollBy(wellScroll);
				Panels.scrollbar(g, rx + colW - 7, ry + 1, listH - 2, sc, false);
			}
		} else if (!card.hasProgram()) {
			int ey = ry + 4;
			for (String l : TextUtil.wrapPlain(font, "No building program (described before programs existed): describe it again for buildings of its own.", colW - 12)) {
				g.text(font, l, rx + 5, ey, UiBits.errorText(), false);
				ey += 10;
			}
		} else {
			int fit = Math.max(1, (listH - 6) / ROW_H);
			programScroll = Math.max(0, Math.min(programScroll, card.program().size() - fit));
			for (int i = programScroll; i < Math.min(card.program().size(), programScroll + fit); i++) {
				ConceptCard.Building b = card.program().get(i);
				int iy = ry + 4 + (i - programScroll) * ROW_H;
				Panels.dot(g, b.landmark() ? "thinking" : "idle", rx + 5, iy + 1, false);
				String fp = b.footprint();
				int fpw = font.width(fp) + 10;
				Panels.pill(g, font, fp, rx + colW - fpw - 6 - (card.program().size() > fit ? 6 : 0), iy - 1, UiBits.muted());
				String title = b.role() + (b.count() > 1 ? " ×" + b.count() : "") + (b.landmark() ? "  ★" : "");
				g.text(font, TextUtil.ellipsize(font, title, colW - fpw - 26), rx + 15, iy, UiBits.ink(), false);
				String where = b.placement() == null ? "" : switch (b.placement()) {
					case "near_water" -> "by the water · ";
					case "central" -> "at the heart · ";
					case "edge" -> "on the outskirts · ";
					case "high_ground" -> "on high ground · ";
					default -> "";
				};
				g.text(font, TextUtil.ellipsize(font, where + (b.notes() == null ? b.type() : b.notes()), colW - 24), rx + 15, iy + 10, UiBits.muted(), false);
			}
			if (card.program().size() > fit) {
				TextUtil.Scroll sc = new TextUtil.Scroll().update(card.program().size(), fit);
				sc.scrollBy(-1_000_000);
				sc.scrollBy(programScroll);
				Panels.scrollbar(g, rx + colW - 7, ry + 1, listH - 2, sc, false);
				int below = card.program().size() - programScroll - fit;
				if (below > 0) {
					String more = "+" + below + " more (scroll)";
					int mw = font.width(more) + 8;
					g.fill(rx + colW - mw - 10, ry + listH - 12, rx + colW - 10, ry + listH - 2, 0xE0E9E1D3);
					g.text(font, more, rx + colW - mw - 6, ry + listH - 11, UiStyle.CLAY_DARK, false);
				}
			}
		}
		// controls: how many, the budget, the estimate
		int cy = controlsTop + 4;
		Panels.divider(g, cx, cy - 2, inner);
		cy += 6;
		g.text(font, "Buildings", cx, cy + 3, UiBits.ink(), false);
		int bx = cx + font.width("Buildings") + 8;
		bx += chip(g, "−", bx, cy, false, buildings > Actions.MIN_BUILDINGS && !sent && !payload.busy(), mouseX, mouseY, () -> setBuildings(buildings - 1)) + 4;
		String n = String.valueOf(buildings);
		g.text(font, n, bx + 2, cy + 3, UiBits.ink(), false);
		bx += font.width("12") + 8;
		bx += chip(g, "+", bx, cy, false, buildings < Actions.MAX_BUILDINGS && !sent && !payload.busy(), mouseX, mouseY, () -> setBuildings(buildings + 1)) + 6;
		String of = buildings < total ? "of " + total : "";
		g.text(font, of, bx, cy + 3, UiBits.muted(), false);
		bx += font.width("of 12") + 14;
		g.text(font, "Budget", bx, cy + 3, UiBits.ink(), false);
		bx += font.width("Budget") + 6;
		drawField(g, bx, cy - 2, Math.min(80, cx + inner - bx), new TextFieldView.Style("$ ", UiBits.muted(), "USD", null, null, UiBits.muted(), 1));
		cy += 20;
		var e = estimate();
		int lm = BudgetPolicy.landmarksFor(size, buildings, flagged);
		String range = String.format("About $%.0f–%.0f", e.usdLow(), e.usdHigh());
		String rest = String.format(" · %d–%d min%s · asks again at %d%% of the budget", e.minutesLow(), e.minutesHigh(), lm > 0 ? " · " + lm + " landmark" + (lm > 1 ? "s" : "") : "",
			(int) (BudgetPolicy.SOFT_FRACTION * 100));
		g.text(font, range, cx, cy, UiBits.ink(), false);
		g.text(font, TextUtil.ellipsize(font, rest, inner - font.width(range)), cx + font.width(range), cy, UiBits.muted(), false);
		cy += 14;
		int ax = cx;
		if (payload.busy()) {
			ax += button(g, "Open the inbox", 1, true, false, true, ax, cy, mouseX, mouseY, () -> Minecraft.getInstance().gui.setScreen(new InboxScreen(payload.settlementId()))) + 6;
		} else {
			ax += button(g, sent ? "Starting..." : "Start building", 1, true, false, !sent, ax, cy, mouseX, mouseY, this::start) + 6;
		}
		ax += button(g, "Describe again", 2, false, false, !sent && !payload.busy(), ax, cy, mouseX, mouseY, () -> Minecraft.getInstance().gui.setScreen(new DescribeScreen(
			payload.settlementId(), payload.name(), ""))) + 6;
		// grow the claim a size step (the server answers with the card again, the new size in its header)
		boolean maxed = payload.claimRadius() >= dev.larattalabs.steward.model.ClaimRules.RADII[dev.larattalabs.steward.model.ClaimRules.RADII.length - 1];
		String ex = "Expand claim";
		if (ax + buttonWidth(ex, 3) <= cx + inner) button(g, ex, 3, false, false, !sent && !maxed, ax, cy, mouseX, mouseY, () -> {
			ClientPlayNetworking.send(new StewardNet.Decide(payload.settlementId(), "expand", "", "", 0));
			say("Growing the claim...", false);
		});
		int fy = y + h - pad.bottom() - 8;
		if (status != null) g.text(font, TextUtil.ellipsize(font, status, inner), cx, fy - 2, statusError ? UiBits.errorText() : UiBits.muted(), false);
		else {
			String[] hints = {"←→", "buildings", "Tab", "builds / interpretation", "Enter", "start", "Esc", "close"};
			if (UiBits.hintsWidth(font, hints) <= inner) UiBits.hints(g, font, cx, fy - 4, false, hints);
		}
	}

	@Override
	protected boolean keyOther(KeyEvent e) {
		if (sent) return false;
		if (e.key() == InputConstants.KEY_RIGHT) setBuildings(buildings + 1);
		else if (e.key() == InputConstants.KEY_LEFT) setBuildings(buildings - 1);
		else if (e.key() == InputConstants.KEY_DOWN) {
			if (showNotes) wellScroll++;
			else programScroll++;
		} else if (e.key() == InputConstants.KEY_UP) {
			if (showNotes) wellScroll = Math.max(0, wellScroll - 1);
			else programScroll = Math.max(0, programScroll - 1);
		}
		else return false;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		// Enter outside the budget box starts (the base class would focus the box instead)
		if (!fieldFocused && TextKeys.isEnter(e)) {
			start();
			return true;
		}
		// Tab outside the budget box switches between the buildings and the interpretation (the base class would focus the box)
		if (!fieldFocused && e.key() == InputConstants.KEY_TAB) {
			showNotes = !showNotes;
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		int d = -(int) Math.signum(dy);
		if (showNotes) wellScroll = Math.max(0, wellScroll + d);
		else programScroll = Math.max(0, programScroll + d);
		return true;
	}
}
