package dev.larattalabs.steward.client.screen;

import dev.larattalabs.steward.client.hud.UiBits;
import dev.larattalabs.steward.client.text.TextFieldView;
import dev.larattalabs.steward.client.text.TextModel;
import dev.larattalabs.steward.client.ui.Kit;
import dev.larattalabs.steward.client.ui.Panels;
import dev.larattalabs.steward.client.ui.TextUtil;
import dev.larattalabs.steward.gateway.ConceptCardJob;
import dev.larattalabs.steward.net.StewardNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * "Describe your settlement": one box for the player's own words (site, style, purpose, story, what to avoid, all in one sentence or several), sent to the
 * server for the concept card (about 2 cents, a few seconds). The card screen replaces this one when the card arrives. Opened by the Founding Stone's claim
 * and by the steward of an undescribed settlement. Client thread.
 */
public final class DescribeScreen extends KitScreen {
	private static final String EXAMPLES = "e.g. \"a fishing village built on stilts over a swamp, mossy and crooked\", \"a quiet lakeside town of glassblowers and lantern"
		+ " makers, no walls\", \"repurposed giant meteor crater mining facility, hellish evil lair\"";
	private final String settlementId;
	private final String name;
	private boolean sent;

	public DescribeScreen(String settlementId, String name, String previous) {
		super("Describe " + name);
		this.settlementId = settlementId;
		this.name = name;
		field = new TextModel(ConceptCardJob.MAX_PROMPT);
		field.set(previous == null ? "" : previous);
		fieldFocused = true;
	}

	@Override
	protected void submitField() {
		if (field == null || sent) return;
		String text = field.value().strip();
		if (text.isEmpty()) {
			say("Say what to build first.", true);
			return;
		}
		ClientPlayNetworking.send(new StewardNet.Describe(settlementId, text));
		sent = true;
		fieldFocused = false;
		say("Reading your description (a few seconds, about 2 cents)...", false);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int w = Math.min(420, width - 16);
		Kit.Padding pad = Kit.padding("panel_paper");
		int inner = w - pad.left() - pad.right();
		TextFieldView.Style st = new TextFieldView.Style(null, 0, "what should this place be?", null, (field == null ? 0 : field.length()) + "/" + ConceptCardJob.MAX_PROMPT,
			UiBits.muted(), 6);
		java.util.List<String> help = TextUtil.wrapPlain(font, "Your own words: the place, its look, what it is for, any story, anything you don't want. The steward turns them into a"
			+ " concept card you can check before anything is built.", inner);
		java.util.List<String> ex = TextUtil.wrapPlain(font, EXAMPLES, inner);
		int fh = fieldHeight(inner, st);
		int h = pad.top() + 18 + help.size() * 10 + 6 + fh + 6 + ex.size() * 10 + 8 + 20 + 14 + pad.bottom();
		int x = (width - w) / 2;
		int y = Math.max(8, (height - h) / 2);
		Panels.panel(g, x, y, w, h);
		int cx = x + pad.left();
		int cy = y + pad.top();
		Panels.header(g, font, "Settlement".equals(name) ? "Describe your settlement" : "Describe " + name, cx - 2, cy - 2, inner + 4);
		cy += 18;
		for (String l : help) {
			g.text(font, l, cx, cy, UiBits.ink(), false);
			cy += 10;
		}
		cy += 6;
		cy += drawField(g, cx, cy, inner, st) + 6;
		for (String l : ex) {
			g.text(font, l, cx, cy, UiBits.muted(), false);
			cy += 10;
		}
		cy += 8;
		int bx = cx;
		bx += button(g, sent ? "Reading..." : "Make the card", 1, true, false, !sent && field != null && !field.value().isBlank(), bx, cy, mouseX, mouseY, this::submitField) + 6;
		button(g, "Later", 2, false, false, true, bx, cy, mouseX, mouseY, this::onClose);
		cy += 24;
		if (status != null) g.text(font, TextUtil.ellipsize(font, status, inner), cx, cy, statusError ? UiBits.errorText() : UiBits.muted(), false);
		else {
			String[] hints = {"Enter", "make the card", "Shift+Enter", "new line", "Tab", "leave the box"};
			if (UiBits.hintsWidth(font, hints) <= inner) UiBits.hints(g, font, cx, cy - 2, false, hints);
		}
	}
}
