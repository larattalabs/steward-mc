package dev.larattalabs.steward.net;

import dev.larattalabs.steward.Steward;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Steward's payloads, server to client. {@code steward_mc:show_layers}: show these ghost layers under {@code key} with Architect's
 * {@code ArchitectClientApi.previewComposite} (the client API is client-only, so the server sends the layers); no layers clears the key.
 */
public final class StewardNet {
	private StewardNet() {
	}

	/** One ghost layer: a massing or library id at an origin (the rotated box's minimum corner), a Minecraft Rotation name and an Architect PreviewStyle name. */
	public record Layer(String blueprintId, int x, int y, int z, String rotation, String style) {}

	public record ShowLayers(String key, List<Layer> layers) implements CustomPacketPayload {
		public static final Type<ShowLayers> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Steward.MOD_ID, "show_layers"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ShowLayers> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeUtf(p.key);
			buf.writeVarInt(p.layers.size());
			for (Layer l : p.layers) {
				buf.writeUtf(l.blueprintId());
				buf.writeVarInt(l.x());
				buf.writeVarInt(l.y());
				buf.writeVarInt(l.z());
				buf.writeUtf(l.rotation());
				buf.writeUtf(l.style());
			}
		}, buf -> {
			String key = buf.readUtf();
			int n = buf.readVarInt();
			List<Layer> layers = new ArrayList<>(n);
			for (int i = 0; i < n; i++) layers.add(new Layer(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(), buf.readUtf()));
			return new ShowLayers(key, List.copyOf(layers));
		});

		@Override
		public Type<ShowLayers> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(ShowLayers.TYPE, ShowLayers.CODEC);
	}
}
