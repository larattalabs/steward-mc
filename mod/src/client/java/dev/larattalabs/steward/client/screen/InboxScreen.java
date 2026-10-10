package dev.larattalabs.steward.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.larattalabs.steward.client.hud.UiBits;
import dev.larattalabs.steward.client.text.TextFieldView;
import dev.larattalabs.steward.client.text.TextModel;
import dev.larattalabs.steward.client.ui.Kit;
import dev.larattalabs.steward.client.ui.Panels;
import dev.larattalabs.steward.client.ui.TextUtil;
import dev.larattalabs.steward.inbox.InboxModel;
import dev.larattalabs.steward.net.StewardNet;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The steward's inbox: every build in progress (left/right walks them), what the selected one waits for in words, and the actions that fit: approve the
 * style bible; approve the massings, show or hide them as ghosts, or send one back with notes; raise a paused budget; approve placing; cancel the build
 * (asks twice). Decisions go to the server; the screen shows what it sent until the server's next inbox arrives. Opened with {@code Y}, by the steward, or
 * when the server asks. Client thread.
 */
public final class InboxScreen extends KitScreen {
	private static final int MAX_W = 440;
	private @Nullable String selectedId;
	private @Nullable String selectedLot;
	private boolean redirecting;
	private boolean confirmCancel;
	private boolean ghosts = true;
	private long sentAtVersion = -1;
	private int[] lotRows = new int[0];
	private int lotRowsY;

	public InboxScreen(@Nullable String settlementId) {
		super("Steward");
		this.selectedId = settlementId == null || settlementId.isEmpty() ? null : settlementId;
	}

	private InboxModel.@Nullable Entry selected() {
		List<InboxModel.Entry> all = ClientInbox.entries();
		if (all.isEmpty()) return null;
		for (InboxModel.Entry e : all) if (e.settlementId().equals(selectedId)) return e;
		// the first one waiting for the player, else the first
		InboxModel.Entry pick = all.stream().filter(InboxModel.Entry::waiting).findFirst().orElse(all.get(0));
		selectedId = pick.settlementId();
		return pick;
	}

	private void send(String action, String lot, String text, double amount, String what) {
		ClientPlayNetworking.send(new StewardNet.Decide(selectedId, action, lot, text, amount));
		sentAtVersion = ClientInbox.version();
		say(what + "...", false);
		confirmCancel = false;
		redirecting = false;
		field = null;
		fieldFocused = false;
	}

	private void startRedirect() {
		if (selectedLot == null) {
			say("Pick a massing first (click its row).", true);
			return;
		}
		redirecting = true;
		field = new TextModel(dev.larattalabs.steward.service.Actions.MAX_NOTES);
		fieldFocused = true;
	}

	private void startRaise(InboxModel.Entry e) {
		field = new TextModel(6);
		field.set(String.valueOf((int) e.minRaiseUsd()));
		// not focused: the arrows still walk the settlements; Enter or a click edits the amount
		fieldFocused = false;
	}

	@Override
	protected void submitField() {
		InboxModel.Entry e = selected();
		if (e == null || field == null) return;
		String v = field.value().strip();
		if (redirecting) {
			if (v.isEmpty()) {
				say("Say what should change.", true);
				return;
			}
			send("redirect", selectedLot, v, 0, "Sending " + selectedLot + " back");
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

	@Override
	protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (sentAtVersion >= 0 && ClientInbox.version() > sentAtVersion) {
			// the server answered: what it says now is the truth
			sentAtVersion = -1;
			status = null;
		}
		List<InboxModel.Entry> all = ClientInbox.entries();
		InboxModel.Entry e = selected();
		int w = Math.min(MAX_W, width - 16);
		Kit.Padding pad = Kit.padding("panel_paper");
		int inner = w - pad.left() - pad.right();
		int h = Math.min(height - 16, 320);
		int x = (width - w) / 2;
		int y = Math.max(8, (height - h) / 2);
		Panels.panel(g, x, y, w, h);
		int cx = x + pad.left();
		int cy = y + pad.top();
		Panels.header(g, font, e == null ? "Steward" : "Steward: " + e.name(), cx - 2, cy - 2, inner + 4);
		if (all.size() > 1) {
			String nav = (all.indexOf(e) + 1) + " of " + all.size();
			g.text(font, nav, cx + inner - font.width(nav), cy + 2, UiBits.muted(), false);
		}
		cy += 18;
		if (e == null) {
			g.text(font, "Nothing is being built.", cx, cy, UiBits.ink(), false);
			cy += 12;
			for (String l : TextUtil.wrapPlain(font, "Claim land with a Founding Stone and describe what you want; the steward asks here when it needs you.", inner)) {
				g.text(font, l, cx, cy, UiBits.muted(), false);
				cy += 10;
			}
			drawFooter(g, cx, y + h - pad.bottom() - 10, inner, false);
			return;
		}
		for (String l : TextUtil.wrapPlain(font, e.headline(), inner)) {
			g.text(font, l, cx, cy, e.waiting() ? UiBits.ink() : UiBits.muted(), false);
			cy += 10;
		}
		cy += 2;
		for (String l : e.lines()) {
			g.text(font, TextUtil.ellipsize(font, l, inner), cx, cy, UiBits.muted(), false);
			cy += 10;
		}
		cy += 6;
		int bottom = y + h - pad.bottom() - 24 - 14;
		if ("MASSINGS".equals(e.decision())) {
			if (selectedLot == null && !e.lots().isEmpty()) selectedLot = e.lots().get(0).id();
			lotRowsY = cy;
			lotRows = new int[e.lots().size()];
			for (int i = 0; i < e.lots().size() && cy + 11 < bottom - (redirecting ? 30 : 0); i++) {
				InboxModel.Lot lot = e.lots().get(i);
				boolean sel = lot.id().equals(selectedLot);
				boolean hov = mouseX >= cx && mouseX < cx + inner && mouseY >= cy - 1 && mouseY < cy + 10;
				if (sel || hov) g.fill(cx - 2, cy - 1, cx + inner + 2, cy + 10, sel ? 0x40B4553A : 0x20000000);
				String row = lot.id() + "  " + lot.role() + ": " + lot.detail();
				g.text(font, TextUtil.ellipsize(font, row, inner), cx, cy, sel ? UiBits.ink() : UiBits.muted(), false);
				lotRows[i] = cy;
				cy += 11;
			}
			cy += 4;
		}
		if (field != null) {
			TextFieldView.Style st = redirecting
				? new TextFieldView.Style(null, 0, "what should change in " + selectedLot + ", e.g. taller stilts, a wraparound porch", null, field.length() + "/"
					+ dev.larattalabs.steward.service.Actions.MAX_NOTES, UiBits.muted(), 3)
				: new TextFieldView.Style("$ ", UiBits.muted(), "new budget in USD", null, null, UiBits.muted(), 1);
			cy += drawField(g, cx, cy, inner, st) + 6;
		}
		// actions
		int by = y + h - pad.bottom() - 24 - 12;
		int bx = cx;
		boolean idle = sentAtVersion < 0;
		switch (e.decision()) {
			case "BIBLE" -> bx += button(g, "Approve the style", 1, true, false, idle, bx, by, mouseX, mouseY, () -> send("approve", "", "", 0, "Approving the style bible")) + 6;
			case "MASSINGS" -> {
				if (redirecting) {
					bx += button(g, "Send back", 1, true, false, idle, bx, by, mouseX, mouseY, this::submitField) + 6;
					bx += button(g, "Back", 2, false, false, true, bx, by, mouseX, mouseY, () -> {
						redirecting = false;
						field = null;
					}) + 6;
				} else {
					bx += button(g, "Approve all", 1, true, false, idle, bx, by, mouseX, mouseY, () -> send("approve", "", "", 0, "Approving " + e.lots().size() + " massings")) + 6;
					bx += button(g, "Redirect " + (selectedLot == null ? "" : selectedLot), 2, false, false, idle && selectedLot != null, bx, by, mouseX, mouseY,
						this::startRedirect) + 6;
					bx += button(g, ghosts ? "Hide ghosts" : "Show ghosts", 3, false, false, true, bx, by, mouseX, mouseY, () -> {
						ghosts = !ghosts;
						send(ghosts ? "show" : "hide", "", "", 0, ghosts ? "Showing the massings" : "Hiding the massings");
					}) + 6;
				}
			}
			case "BUDGET" -> {
				if (field == null) startRaise(e);
				bx += button(g, "Raise the budget", 1, true, false, idle, bx, by, mouseX, mouseY, this::submitField) + 6;
			}
			case "PLACEMENT" -> bx += button(g, "Place it", 1, true, false, idle, bx, by, mouseX, mouseY, () -> send("approve", "", "", 0, "Placing " + e.name())) + 6;
			default -> {
			}
		}
		String cancel = confirmCancel ? "Really cancel?" : "Cancel build";
		int cw = buttonWidth(cancel, 9);
		button(g, cancel, 9, false, true, idle, cx + inner - cw, by, mouseX, mouseY, () -> {
			if (confirmCancel) send("cancel", "", "", 0, "Cancelling");
			else confirmCancel = true;
		});
		drawFooter(g, cx, y + h - pad.bottom() - 10, inner, all.size() > 1);
	}

	private void drawFooter(GuiGraphicsExtractor g, int cx, int fy, int inner, boolean several) {
		if (status != null) {
			g.text(font, TextUtil.ellipsize(font, status, inner), cx, fy - 2, statusError ? UiBits.errorText() : UiBits.muted(), false);
			return;
		}
		String[] hints = several ? new String[] {"1-9", "act", "←→", "settlement", "Esc", "close"} : new String[] {"1-9", "act", "Esc", "close"};
		if (UiBits.hintsWidth(font, hints) <= inner) UiBits.hints(g, font, cx, fy - 4, false, hints);
	}

	@Override
	protected boolean keyOther(KeyEvent e) {
		List<InboxModel.Entry> all = ClientInbox.entries();
		if (all.size() < 2) return false;
		int i = Math.max(0, all.indexOf(selected()));
		if (e.key() == InputConstants.KEY_RIGHT) i = (i + 1) % all.size();
		else if (e.key() == InputConstants.KEY_LEFT) i = (i - 1 + all.size()) % all.size();
		else return false;
		selectedId = all.get(i).settlementId();
		selectedLot = null;
		redirecting = false;
		field = null;
		confirmCancel = false;
		return true;
	}

	@Override
	protected boolean clickOther(double mx, double my) {
		InboxModel.Entry e = selected();
		if (e == null || !"MASSINGS".equals(e.decision())) return false;
		for (int i = 0; i < lotRows.length && i < e.lots().size(); i++) {
			if (lotRows[i] > 0 && my >= lotRows[i] - 1 && my < lotRows[i] + 10) {
				selectedLot = e.lots().get(i).id();
				return true;
			}
		}
		return false;
	}

	/** DevBridge: pick a massing row by lot id. */
	public void selectLot(String lot) {
		selectedLot = lot;
	}
}
