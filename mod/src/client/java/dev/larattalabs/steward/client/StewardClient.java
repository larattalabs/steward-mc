package dev.larattalabs.steward.client;

import dev.larattalabs.architect.api.ArchitectClientApi;
import dev.larattalabs.architect.api.PreviewLayer;
import dev.larattalabs.architect.api.PreviewStyle;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.net.StewardNet;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;

/** Client entrypoint: shows the ghost layers the server sends (massings waiting for approval) through Architect's client API. The card screen, inbox and HUD land here. */
public class StewardClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
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
