package dev.larattalabs.steward.client;

import dev.larattalabs.architect.api.ArchitectClientApi;
import dev.larattalabs.architect.api.PreviewLayer;
import dev.larattalabs.architect.api.PreviewStyle;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.client.hud.Keys;
import dev.larattalabs.steward.client.hud.StewardHud;
import dev.larattalabs.steward.client.screen.ClientInbox;
import dev.larattalabs.steward.client.screen.InboxScreen;
import dev.larattalabs.steward.client.ui.GuardedHud;
import dev.larattalabs.steward.net.StewardNet;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;

/**
 * Client entrypoint: the inbox (state, screen, {@code Y}, HUD line), the screens the server opens, and the ghost layers the server sends (massings waiting
 * for approval) through Architect's client API. Payload handlers run on the client thread.
 */
public class StewardClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Keys.ensureRegistered();
		HudElementRegistry.addLast(Steward.id("hud/inbox"), GuardedHud.of("hud.inbox", new StewardHud()));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (Keys.inbox.consumeClick()) {
				if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new InboxScreen(null));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> ClientInbox.clear());
		ClientPlayNetworking.registerGlobalReceiver(StewardNet.Inbox.TYPE, (payload, ctx) -> ClientInbox.set(payload.entries()));
		ClientPlayNetworking.registerGlobalReceiver(StewardNet.OpenDescribe.TYPE, (payload, ctx) -> ctx.client().gui.setScreen(
			new dev.larattalabs.steward.client.screen.DescribeScreen(payload.settlementId(), payload.name(), payload.previous())));
		ClientPlayNetworking.registerGlobalReceiver(StewardNet.Card.TYPE, (payload, ctx) -> ctx.client().gui.setScreen(new dev.larattalabs.steward.client.screen.CardScreen(payload)));
		ClientPlayNetworking.registerGlobalReceiver(StewardNet.OpenInbox.TYPE, (payload, ctx) -> ctx.client().gui.setScreen(new InboxScreen(payload.settlementId())));
		ClientPlayNetworking.registerGlobalReceiver(StewardNet.ShowLayers.TYPE, (payload, ctx) -> {
			var api = ArchitectClientApi.get();
			if (payload.layers().isEmpty()) {
				api.clearComposite(payload.key());
				return;
			}
			try {
				List<PreviewLayer> layers = payload.layers().stream().map(l -> PreviewLayer.of(l.blueprintId(), new BlockPos(l.x(), l.y(), l.z()),
					Rotation.valueOf(l.rotation()), PreviewStyle.valueOf(l.style()))).toList();
				api.previewComposite(payload.key(), layers);
			} catch (IllegalArgumentException e) {
				Steward.LOGGER.warn("could not show {}: {}", payload.key(), e.getMessage());
				if (ctx.player() != null) ctx.player().sendSystemMessage(Component.literal("Steward: could not show the preview: " + e.getMessage()));
			}
		});
	}
}
