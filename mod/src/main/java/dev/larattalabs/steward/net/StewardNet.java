package dev.larattalabs.steward.net;

import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.inbox.InboxModel;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Steward's payloads. Server to client: {@code show_layers} (ghost layers under a key, drawn with Architect's {@code ArchitectClientApi.previewComposite}; no
 * layers clears the key), {@code inbox} (every build of the player), {@code open_inbox}, {@code open_describe}, {@code card}. Client to server: {@code describe},
 * {@code start}, {@code decide}; the server validates each (service/Actions) exactly like the chat commands.
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

	// ------------------------------------------------------------------ server to client

	/** The player's inbox: one entry per build in progress (replaces what the client had). */
	public record Inbox(List<InboxModel.Entry> entries) implements CustomPacketPayload {
		public static final Type<Inbox> TYPE = new Type<>(id("inbox"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Inbox> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeVarInt(p.entries.size());
			for (InboxModel.Entry e : p.entries) {
				buf.writeUtf(e.settlementId());
				buf.writeUtf(e.name());
				buf.writeUtf(e.decision());
				buf.writeUtf(e.headline());
				writeStrings(buf, e.lines());
				buf.writeVarInt(e.lots().size());
				for (InboxModel.Lot l : e.lots()) {
					buf.writeUtf(l.id());
					buf.writeUtf(l.role());
					buf.writeUtf(l.stage());
					buf.writeBoolean(l.landmark());
					buf.writeBoolean(l.waiting());
					buf.writeUtf(l.detail());
				}
				buf.writeDouble(e.budgetUsd());
				buf.writeDouble(e.spentUsd());
				buf.writeDouble(e.minRaiseUsd());
			}
		}, buf -> {
			int n = buf.readVarInt();
			List<InboxModel.Entry> out = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				String id = buf.readUtf(), name = buf.readUtf(), decision = buf.readUtf(), headline = buf.readUtf();
				List<String> lines = readStrings(buf);
				int m = buf.readVarInt();
				List<InboxModel.Lot> lots = new ArrayList<>(m);
				for (int j = 0; j < m; j++) lots.add(new InboxModel.Lot(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readBoolean(), buf.readUtf()));
				out.add(new InboxModel.Entry(id, name, decision, headline, lines, lots, buf.readDouble(), buf.readDouble(), buf.readDouble()));
			}
			return new Inbox(List.copyOf(out));
		});

		@Override
		public Type<Inbox> type() {
			return TYPE;
		}
	}

	/** Open the inbox ({@code settlementId} focused, or "" for the first entry). */
	public record OpenInbox(String settlementId) implements CustomPacketPayload {
		public static final Type<OpenInbox> TYPE = new Type<>(id("open_inbox"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenInbox> CODEC = StreamCodec.of((buf, p) -> buf.writeUtf(p.settlementId), buf -> new OpenInbox(buf.readUtf()));

		@Override
		public Type<OpenInbox> type() {
			return TYPE;
		}
	}

	/** Open the describe screen for a settlement ({@code previous}: the words it was described with, or ""). */
	public record OpenDescribe(String settlementId, String name, String previous) implements CustomPacketPayload {
		public static final Type<OpenDescribe> TYPE = new Type<>(id("open_describe"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenDescribe> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeUtf(p.settlementId);
			buf.writeUtf(p.name);
			buf.writeUtf(p.previous);
		}, buf -> new OpenDescribe(buf.readUtf(), buf.readUtf(), buf.readUtf()));

		@Override
		public Type<OpenDescribe> type() {
			return TYPE;
		}
	}

	/** A settlement's concept card to show (and start from), as the card's JSON (the screen parses it with {@code ConceptCard.parse}). {@code busy}: a build is running. */
	public record Card(String settlementId, String name, String cardJson, boolean busy) implements CustomPacketPayload {
		public static final Type<Card> TYPE = new Type<>(id("card"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Card> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeUtf(p.settlementId);
			buf.writeUtf(p.name);
			buf.writeUtf(p.cardJson, 32767);
			buf.writeBoolean(p.busy);
		}, buf -> new Card(buf.readUtf(), buf.readUtf(), buf.readUtf(32767), buf.readBoolean()));

		@Override
		public Type<Card> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------------------ client to server (validated in Actions)

	public record Describe(String settlementId, String text) implements CustomPacketPayload {
		public static final Type<Describe> TYPE = new Type<>(id("describe"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Describe> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeUtf(p.settlementId);
			buf.writeUtf(p.text, 4096);
		}, buf -> new Describe(buf.readUtf(), buf.readUtf(4096)));

		@Override
		public Type<Describe> type() {
			return TYPE;
		}
	}

	public record Start(String settlementId, int buildings, double budgetUsd) implements CustomPacketPayload {
		public static final Type<Start> TYPE = new Type<>(id("start"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Start> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeUtf(p.settlementId);
			buf.writeVarInt(p.buildings);
			buf.writeDouble(p.budgetUsd);
		}, buf -> new Start(buf.readUtf(), buf.readVarInt(), buf.readDouble()));

		@Override
		public Type<Start> type() {
			return TYPE;
		}
	}

	/** A decision on a build: {@code action} approve, redirect (lot, text), raise (amount), cancel, show, hide; also "card" (show the card again). */
	public record Decide(String settlementId, String action, String lot, String text, double amount) implements CustomPacketPayload {
		public static final Type<Decide> TYPE = new Type<>(id("decide"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Decide> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeUtf(p.settlementId);
			buf.writeUtf(p.action);
			buf.writeUtf(p.lot);
			buf.writeUtf(p.text, 4096);
			buf.writeDouble(p.amount);
		}, buf -> new Decide(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(4096), buf.readDouble()));

		@Override
		public Type<Decide> type() {
			return TYPE;
		}
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(Steward.MOD_ID, path);
	}

	private static void writeStrings(RegistryFriendlyByteBuf buf, List<String> l) {
		buf.writeVarInt(l.size());
		for (String x : l) buf.writeUtf(x, 4096);
	}

	private static List<String> readStrings(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<String> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(buf.readUtf(4096));
		return List.copyOf(out);
	}

	public static void init() {
		var s2c = PayloadTypeRegistry.clientboundPlay();
		s2c.register(ShowLayers.TYPE, ShowLayers.CODEC);
		s2c.register(Inbox.TYPE, Inbox.CODEC);
		s2c.register(OpenInbox.TYPE, OpenInbox.CODEC);
		s2c.register(OpenDescribe.TYPE, OpenDescribe.CODEC);
		s2c.register(Card.TYPE, Card.CODEC);
		var c2s = PayloadTypeRegistry.serverboundPlay();
		c2s.register(Describe.TYPE, Describe.CODEC);
		c2s.register(Start.TYPE, Start.CODEC);
		c2s.register(Decide.TYPE, Decide.CODEC);
	}
}
