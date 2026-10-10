package dev.larattalabs.steward.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.larattalabs.steward.client.hud.UiBits;
import dev.larattalabs.steward.client.text.TextFieldView;
import dev.larattalabs.steward.client.text.TextModel;
import dev.larattalabs.steward.client.ui.Kit;
import dev.larattalabs.steward.client.ui.Panels;
import dev.larattalabs.steward.client.ui.TextUtil;
import dev.larattalabs.steward.model.BudgetPolicy;
import dev.larattalabs.steward.net.StewardNet;
import dev.larattalabs.steward.service.Actions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;

/**
 * A settlement's concept card: what the steward took the player's words to mean and the buildings it will design, then how many to build (left/right or
 * the -/+ buttons) and the budget, with the estimate the runner itself uses ({@link BudgetPolicy#landmarksFor}), and Start. "Describe again" goes back to
 * the words. While the settlement is being built, Start gives way to the inbox. Client thread.
 */
public final class CardScreen extends KitScreen {
	private final StewardNet.Card card;
	private int buildings;
	private boolean budgetEdited;
	private int scroll;
	private int maxScroll;
	private boolean sent;

	public CardScreen(StewardNet.Card card) {
		super(card.name());
		this.card = card;
		this.buildings = Math.max(Actions.MIN_BUILDINGS, Math.min(Actions.MAX_BUILDINGS, card.programTotal()));
		field = new TextModel(6);
		field.set(String.valueOf(suggestedBudget()));
		fieldFocused = false;
	}

	private BudgetPolicy.Estimate estimate() {
		return BudgetPolicy.estimate(buildings, BudgetPolicy.landmarksFor(card.size(), buildings, card.flaggedLandmarks()));
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
		if (sent || card.busy() || field == null) return;
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
		ClientPlayNetworking.send(new StewardNet.Start(card.settlementId(), buildings, usd));
		sent = true;
		say("Starting. The steward asks in the inbox (Y) when it needs you.", false);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (fieldFocused && field != null && !field.value().equals(String.valueOf(suggestedBudget()))) budgetEdited = true;
		int w = Math.min(460, width - 16);
		Kit.Padding pad = Kit.padding("panel_paper");
		int inner = w - pad.left() - pad.right();
		int h = Math.min(height - 16, 340);
		int x = (width - w) / 2;
		int y = Math.max(8, (height - h) / 2);
		Panels.panel(g, x, y, w, h);
		int cx = x + pad.left();
		int top = y + pad.top();
		Panels.header(g, font, card.name() + " (concept card)", cx - 2, top - 2, inner + 4);
		// the card and the program, scrolled
		List<String[]> rows = new ArrayList<>();
		for (String l : card.lines().subList(1, card.lines().size())) for (String s : TextUtil.wrapPlain(font, l, inner)) rows.add(new String[] {s, "ink"});
		if (card.hasProgram()) {
			rows.add(new String[] {"", "ink"});
			rows.add(new String[] {"Builds (" + card.programTotal() + "):", "head"});
			for (String b : card.program()) for (String s : TextUtil.wrapPlain(font, "  " + b, inner)) rows.add(new String[] {s, "muted"});
		} else {
			rows.add(new String[] {"This card has no building program (described before programs existed): describe it again for buildings of its own.", "err"});
		}
		int areaTop = top + 18;
		int areaBottom = y + h - pad.bottom() - 20 - 8 - 18 - 14 - 12;
		int visible = Math.max(1, (areaBottom - areaTop) / 10);
		maxScroll = Math.max(0, rows.size() - visible);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		int cy = areaTop;
		for (int i = scroll; i < rows.size() && i < scroll + visible; i++) {
			String[] r = rows.get(i);
			int color = switch (r[1]) {
				case "muted" -> UiBits.muted();
				case "err" -> UiBits.errorText();
				default -> UiBits.ink();
			};
			g.text(font, TextUtil.ellipsize(font, r[0], inner - 6), cx, cy, color, false);
			cy += 10;
		}
		if (maxScroll > 0) g.text(font, scroll < maxScroll ? "▼ more" : "▲", cx + inner - font.width("▼ more"), areaBottom - 9, UiBits.muted(), false);
		// how many, the budget, the estimate
		int ry = areaBottom + 6;
		Panels.divider(g, cx, ry - 4, inner);
		g.text(font, "Buildings", cx, ry + 6, UiBits.ink(), false);
		int bx = cx + 60;
		bx += button(g, "-", 0, false, false, buildings > Actions.MIN_BUILDINGS && !sent, bx, ry, mouseX, mouseY, () -> setBuildings(buildings - 1)) + 4;
		String n = String.valueOf(buildings);
		g.text(font, n, bx + 6, ry + 6, UiBits.ink(), false);
		bx += font.width(n) + 16;
		bx += button(g, "+", 0, false, false, buildings < Actions.MAX_BUILDINGS && !sent, bx, ry, mouseX, mouseY, () -> setBuildings(buildings + 1)) + 14;
		g.text(font, "Budget", bx, ry + 6, UiBits.ink(), false);
		bx += 40;
		drawField(g, bx, ry + 1, Math.min(90, cx + inner - bx), new TextFieldView.Style("$ ", UiBits.muted(), "USD", null, null, UiBits.muted(), 1));
		var e = estimate();
		int lm = BudgetPolicy.landmarksFor(card.size(), buildings, card.flaggedLandmarks());
		String est = String.format("%d buildings%s: about $%.0f-%.0f and %d-%d minutes. The build pauses and asks at %d%% of the budget.", buildings,
			lm > 0 ? " (" + lm + " landmark" + (lm > 1 ? "s" : "") + ")" : "", e.usdLow(), e.usdHigh(), e.minutesLow(), e.minutesHigh(), (int) (BudgetPolicy.SOFT_FRACTION * 100));
		g.text(font, TextUtil.ellipsize(font, est, inner), cx, ry + 24, UiBits.muted(), false);
		// actions
		int by = y + h - pad.bottom() - 20 - 12;
		int ax = cx;
		if (card.busy()) {
			ax += button(g, "Open the inbox", 1, true, false, true, ax, by, mouseX, mouseY, () -> Minecraft.getInstance().gui.setScreen(new InboxScreen(card.settlementId()))) + 6;
		} else {
			ax += button(g, sent ? "Starting..." : "Start building", 1, true, false, !sent, ax, by, mouseX, mouseY, this::start) + 6;
		}
		button(g, "Describe again", 2, false, false, !sent && !card.busy(), ax, by, mouseX, mouseY, () -> Minecraft.getInstance().gui.setScreen(new DescribeScreen(card.settlementId(),
			card.name(), "")));
		int fy = y + h - pad.bottom() - 8;
		if (status != null) g.text(font, TextUtil.ellipsize(font, status, inner), cx, fy - 2, statusError ? UiBits.errorText() : UiBits.muted(), false);
		else {
			String[] hints = {"←→", "buildings", "Enter", "start", "Esc", "close"};
			if (UiBits.hintsWidth(font, hints) <= inner) UiBits.hints(g, font, cx, fy - 4, false, hints);
		}
	}

	@Override
	protected boolean keyOther(KeyEvent e) {
		if (sent) return false;
		if (e.key() == InputConstants.KEY_RIGHT) setBuildings(buildings + 1);
		else if (e.key() == InputConstants.KEY_LEFT) setBuildings(buildings - 1);
		else if (e.key() == InputConstants.KEY_DOWN) scroll = Math.min(maxScroll, scroll + 1);
		else if (e.key() == InputConstants.KEY_UP) scroll = Math.max(0, scroll - 1);
		else return false;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		// Enter outside the budget box starts (the base class would focus the box instead)
		if (!fieldFocused && dev.larattalabs.steward.client.text.TextKeys.isEnter(e)) {
			start();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(dy)));
		return true;
	}
}
