package dev.larattalabs.steward.client.screen;

import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import dev.larattalabs.steward.client.text.TextFieldView;
import dev.larattalabs.steward.client.text.TextModel;
import dev.larattalabs.steward.model.TalkRules;
import dev.larattalabs.steward.net.StewardNet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Talking to the steward (docs/PLAN.md phase 3b "conversation"), opened by a right-click on it: the last lines between you and it, a box for what you say,
 * and the way to the settlement screen, the card and the inbox. What it says back arrives as its speech bubble ({@link #heard}). Client thread.
 */
public final class TalkScreen extends KitScreen {
	/** Lines kept per settlement this session. */
	static final int KEEP = 12;
	private static final Map<String, ArrayDeque<Line>> LOG = new HashMap<>();
	/** When the player last said something the steward has not answered yet, per settlement (a "thinking" note, for at most 30 s). */
	private static final Map<String, Long> ASKED = new HashMap<>();

	private record Line(boolean you, String text) {}

	/** A drawn line: who (on a turn's first line, when the speaker changes), the text, whether it is the player's, whether a new turn starts here. */
	private record Row(String who, String text, boolean you, boolean turn) {}

	private final String settlementId;
	private final String name;

	public TalkScreen(String settlementId, String name) {
		super("Talk to the steward of " + name);
		this.settlementId = settlementId;
		this.name = name;
		field = new TextModel(TalkRules.MAX_SAID);
		fieldFocused = true;
	}

	/** The steward said something (its bubble): kept for the talk box. */
	public static void heard(String settlementId, String text) {
		add(settlementId, new Line(false, text));
		// "Let me think about that" is not the answer yet
		if (!text.startsWith("Let me think")) ASKED.remove(settlementId);
	}

	public static void clear() {
		LOG.clear();
		ASKED.clear();
	}

	private static void add(String settlementId, Line l) {
		var q = LOG.computeIfAbsent(settlementId, k -> new ArrayDeque<>());
		q.addLast(l);
		while (q.size() > KEEP) q.pollFirst();
	}

	@Override
	protected void submitField() {
		if (field == null) return;
		String text = field.value().strip();
		if (text.isEmpty()) return;
		add(settlementId, new Line(true, text));
		ASKED.put(settlementId, System.currentTimeMillis());
		ClientPlayNetworking.send(new StewardNet.Decide(settlementId, "talk", "", text, 0));
		field.set("");
		say(null, false);
	}

	private void open(String action, String what) {
		ClientPlayNetworking.send(new StewardNet.Decide(settlementId, action, "", "", 0));
		say(what + "...", false);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int w = Math.min(380, width - 16);
		Kit.Padding pad = Kit.padding("panel_paper");
		int inner = w - pad.left() - pad.right();
		TextFieldView.Style st = new TextFieldView.Style(null, 0, "ask for a building, a district, a change, or what it is doing", null,
			(field == null ? 0 : field.length()) + "/" + TalkRules.MAX_SAID, UiBits.muted(), 3);
		// the conversation, newest last: who said it once per turn, a gap between turns; as many lines as fit
		int col = Math.max(font.width("Steward"), font.width("You")) + 8;
		List<Row> rows = new ArrayList<>();
		Boolean last = null;
		for (Line l : LOG.getOrDefault(settlementId, new ArrayDeque<>())) {
			List<String> wrapped = TextUtil.wrapPlain(font, l.text(), inner - 12 - col);
			for (int i = 0; i < wrapped.size(); i++) {
				boolean first = i == 0;
				rows.add(new Row(first && !Boolean.valueOf(l.you()).equals(last) ? (l.you() ? "You" : "Steward") : "", wrapped.get(i), l.you(), first && last != null));
			}
			last = l.you();
		}
		int maxRows = Math.max(4, Math.min(12, (height - 170) / 10));
		List<Row> shown = new ArrayList<>(rows.size() > maxRows ? rows.subList(rows.size() - maxRows, rows.size()) : rows);
		// the top line names its speaker even when the line that did has scrolled away
		if (!shown.isEmpty() && shown.get(0).who().isEmpty()) {
			Row r = shown.get(0);
			shown.set(0, new Row(r.you() ? "You" : "Steward", r.text(), r.you(), r.turn()));
		}
		int gaps = (int) shown.stream().skip(1).filter(Row::turn).count();
		int fh = fieldHeight(inner, st);
		int listH = Math.max(4, shown.size()) * 10 + gaps * 4 + 8;
		int h = pad.top() + 18 + listH + 6 + fh + 8 + 20 + 18 + pad.bottom();
		int x = (width - w) / 2;
		int y = Math.max(8, (height - h) / 2);
		Panels.panel(g, x, y, w, h);
		int cx = x + pad.left();
		int cy = y + pad.top();
		Panels.header(g, font, "The steward of " + name, cx - 2, cy - 2, inner + 4);
		cy += 18;
		Panels.inset(g, cx, cy, inner, listH);
		int ly = cy + 4;
		if (shown.isEmpty()) g.text(font, "Say what " + name + " needs, or ask what I am doing.", cx + 6, ly, UiBits.muted(), false);
		for (int i = 0; i < shown.size(); i++) {
			Row r = shown.get(i);
			if (i > 0 && r.turn()) ly += 4;
			g.text(font, r.who(), cx + 6, ly, r.you() ? UiStyle.CLAY : UiBits.muted(), false);
			g.text(font, r.text(), cx + 6 + col, ly, r.you() ? UiStyle.WALNUT : UiBits.ink(), false);
			ly += 10;
		}
		cy += listH + 6;
		cy += drawField(g, cx, cy, inner, st) + 8;
		int bx = cx;
		bx += button(g, "Say it", 1, true, false, field != null && !field.value().isBlank(), bx, cy, mouseX, mouseY, this::submitField) + 6;
		bx += button(g, "Settlement", 2, false, false, true, bx, cy, mouseX, mouseY, () -> open("settlement", "Opening the settlement")) + 6;
		bx += button(g, "Card", 3, false, false, true, bx, cy, mouseX, mouseY, () -> open("card", "Opening the card")) + 6;
		button(g, "Inbox", 4, false, false, true, bx, cy, mouseX, mouseY, () -> {
			if (minecraft != null) minecraft.gui.setScreen(new InboxScreen(settlementId));
		});
		cy += 28;
		Long asked = ASKED.get(settlementId);
		boolean thinking = asked != null && System.currentTimeMillis() - asked < 30_000;
		if (status != null) g.text(font, TextUtil.ellipsize(font, status, inner), cx, cy, statusError ? UiBits.errorText() : UiBits.muted(), false);
		else if (thinking) g.text(font, "The steward is thinking...", cx, cy, UiBits.muted(), false);
		else {
			String[] hints = {"Enter", "say it", "Tab", "leave box", "Esc", "close"};
			if (UiBits.hintsWidth(font, hints) <= inner) UiBits.hints(g, font, cx, cy - 2, false, hints);
		}
	}
}
