package dev.larattalabs.steward.client.screen;

import dev.larattalabs.steward.client.hud.UiBits;
import dev.larattalabs.steward.client.ui.Kit;
import dev.larattalabs.steward.client.ui.Panels;
import dev.larattalabs.steward.client.ui.TextUtil;
import dev.larattalabs.steward.net.StewardNet;
import dev.larattalabs.steward.view.SettlementView;
import dev.larattalabs.steward.view.SettlementView.Building;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * One building's panel (docs/PLAN.md "Interface", phase 3a, read-only first): its role and state, the version of its design it stands at and the newest,
 * the player's edits that updates keep, where it stands, and the operations that touched it. Its actions arrive with the features behind them (a change
 * in your own words, revert, pin, make independent); today: preview an available update, back to the settlement. Client thread.
 */
public final class BuildingScreen extends KitScreen {
	private static final int ROW_H = 22;
	private final SettlementView view;
	private final Building b;
	private final SettlementScreen back;
	private int scroll;

	public BuildingScreen(SettlementView view, Building b, SettlementScreen back) {
		super(b.role());
		this.view = view;
		this.b = b;
		this.back = back;
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Kit.Padding pad = Kit.padding("panel_paper");
		int w = Math.min(420, width - 16);
		int inner = w - pad.left() - pad.right();
		int h = Math.min(height - 16, 280);
		int x = (width - w) / 2;
		int y = Math.max(8, (height - h) / 2);
		Panels.panel(g, x, y, w, h);
		int cx = x + pad.left();
		int top = y + pad.top();
		Panels.header(g, font, b.role(), cx - 2, top - 2, inner + 4);
		g.text(font, view.name(), cx + inner - font.width(view.name()), top, UiBits.muted(), false);
		int fy = top + 18;
		String[] st = SettlementScreen.state(b);
		UiBits.dotPill(g, font, st[0], st[1], cx, fy - 1, UiBits.ink());
		fy += 16;
		fy = fact(g, cx, fy, inner, "Design", b.entry() + ", version " + b.version() + (b.updateAvailable() ? " (version " + b.head() + " is ready)" : " (the newest)"));
		fy = fact(g, cx, fy, inner, "Your edits", b.edits() == 0 ? "none" : UiBits.plural(b.edits(), "block", "blocks")
			+ " you changed; updates keep them");
		fy = fact(g, cx, fy, inner, "Where", (b.maxX() - b.minX() + 1) + " x " + (b.maxZ() - b.minZ() + 1) + " at " + b.minX() + ", " + b.minZ()
			+ (b.lot() != null ? " (lot " + b.lot() + ")" : "") + " · site " + b.siteId());
		// its history
		int footerY = y + h - pad.bottom() - 10;
		int actionsY = footerY - 10 - 20;
		g.text(font, "History", cx, fy + 2, UiBits.muted(), false);
		fy += 14;
		int listH = actionsY - 4 - fy;
		List<SettlementView.Op> ops = view.opsOf(b.siteId());
		if (listH > 16) {
			Panels.inset(g, cx, fy, inner, listH);
			int fit = Math.max(1, (listH - 6) / ROW_H);
			scroll = Math.max(0, Math.min(scroll, ops.size() - fit));
			if (ops.isEmpty()) g.text(font, "Nothing recorded for it.", cx + 8, fy + 6, UiBits.muted(), false);
			for (int i = scroll; i < Math.min(ops.size(), scroll + fit); i++) {
				SettlementView.Op o = ops.get(i);
				int ry = fy + 4 + (i - scroll) * ROW_H;
				Panels.dot(g, SettlementScreen.outcomeFamily(o), cx + 5, ry + 1, false);
				String when = UiBits.ago(o.at());
				g.text(font, SettlementScreen.kindLabel(o.kind()), cx + 15, ry, UiBits.ink(), false);
				g.text(font, when, cx + inner - 8 - font.width(when), ry, UiBits.muted(), false);
				String change = o.changes().stream().filter(c -> c.siteId().equals(b.siteId())).findFirst().map(c -> c.from() < 0 ? " · to v" + c.to()
					: c.from() == 0 ? " · placed at v" + c.to() : c.to() == 0 ? " · removed" : " · v" + c.from() + " to v" + c.to()).orElse("");
				g.text(font, TextUtil.ellipsize(font, o.text() + change, inner - 22), cx + 15, ry + 10, UiBits.muted(), false);
			}
		}
		int bx = cx;
		bx += button(g, "Back", 1, false, false, true, bx, actionsY, mouseX, mouseY, () -> {
			if (minecraft != null) minecraft.gui.setScreen(back);
		}) + 6;
		if (b.updateAvailable()) {
			button(g, "Preview the update", 2, true, false, !b.updating(), bx, actionsY, mouseX, mouseY, () -> {
				ClientPlayNetworking.send(new StewardNet.Decide(view.id(), "update_preview", b.siteId(), "", 0));
				say("Showing what changes...", false);
			});
		}
		if (status != null) g.text(font, TextUtil.ellipsize(font, status, inner), cx, footerY - 2, statusError ? UiBits.errorText() : UiBits.muted(), false);
		else UiBits.hints(g, font, cx, footerY - 4, false, "1", "back", "Esc", "close");
	}

	private int fact(GuiGraphicsExtractor g, int x, int y, int w, String label, String value) {
		g.text(font, label, x, y, UiBits.muted(), false);
		int lw = 70;
		for (String l : TextUtil.wrapPlain(font, value, w - lw)) {
			g.text(font, l, x + lw, y, UiBits.ink(), false);
			y += 10;
		}
		return y + 3;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		scroll = Math.max(0, scroll - (int) Math.signum(dy));
		return true;
	}
}
