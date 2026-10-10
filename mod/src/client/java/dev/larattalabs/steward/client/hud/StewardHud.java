package dev.larattalabs.steward.client.hud;

import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.labui.client.ui.TextUtil;

import dev.larattalabs.steward.client.screen.ClientInbox;
import dev.larattalabs.steward.inbox.InboxModel;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The HUD line while a build waits for the player: "Steward: Stilt Swamp Fishing Village waits for you [Y]" top left (Architect's toasts are top right, its
 * placement and site lines at the bottom), "+ n more" when several do. Hidden with F1 and under any screen. Client thread.
 */
public final class StewardHud implements HudElement {
	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gui.hud.isHidden() || mc.gui.screen() != null) return;
		InboxModel.Entry first = ClientInbox.entries().stream().filter(InboxModel.Entry::waiting).findFirst().orElse(null);
		if (first == null) return;
		long more = ClientInbox.waiting() - 1;
		String tail = " waits for you" + (more > 0 ? " (+" + more + ")" : "");
		int room = (int) (g.guiWidth() * 0.6) - mc.font.width("Steward: " + tail) - 30;
		String text = "Steward: " + dev.larattalabs.labui.client.ui.TextUtil.ellipsize(mc.font, first.name(), Math.max(30, room)) + tail;
		int x = 6;
		int w = UiBits.dotPill(g, mc.font, "thinking", text, x, 6, UiBits.ink());
		UiBits.keycap(g, mc.font, Keys.label(Keys.inbox), x + w + 4, 6);
	}
}
