package dev.larattalabs.steward.service;

import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.net.StewardNet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.jspecify.annotations.Nullable;

/**
 * Tells each player about the settlement they stand in, for the in-world interface (docs/PLAN.md "Interface", 3d: building labels on look-at, survey mode):
 * the settlement's view (its buildings with their boxes and versions), sent only when it changed, and "" when they left every claim. Viewing is open to
 * everyone. Server thread.
 *
 * <p>(slice 8bS) With Architect's {@code SETTLEMENT_CHANGED} (8b EV, feature {@code settlementChanged}) a settlement's view is rebuilt only when it is
 * dirty: Architect's signal for its owner, a saved change of the settlement ({@link Settlements}), its build's state ({@code SettlementRunner}). The
 * two-second round then only tracks which claim each player is in: a player who enters a claim gets its view. A full round every {@link #RESYNC} ticks
 * still rebuilds every view, for what nothing signals (the inbox's attention, the week's spend). Without the feature (an older Architect) every round
 * rebuilds, as before.
 */
public final class SettlementSync {
	/** Architect 8b's feature for {@code SiteEvents.SETTLEMENT_CHANGED}. */
	public static final String FEATURE = "settlementChanged";
	private static final Map<UUID, String> SENT = new HashMap<>();
	/** (8bS) The settlement each player's last view was for ("" for none). */
	private static final Map<UUID, String> AT = new HashMap<>();
	/** Settlements whose state changed since the last round: the next tick sends their views at once ({@link #dirty}). */
	private static final Set<String> DIRTY = new HashSet<>();
	static final int EVERY = 40;
	/** (8bS) With the signal: a full round this often (30 s), for what nothing signals. */
	static final int RESYNC = 600;
	/** (8bS) Whether this Architect signals settlement changes (read at server start). */
	private static boolean signalled;

	private SettlementSync() {
	}

	/** A settlement's state changed (a build's phase, what it waits for): its view goes out on the next tick rather than within two seconds. Server thread. */
	public static void dirty(String settlementId) {
		DIRTY.add(settlementId);
	}

	/** (8bS) The settlement an Architect owner names ({@code steward_mc:settlement/<id>}), or null for another owner. Pure. */
	static @Nullable String settlementOf(@Nullable String owner) {
		String prefix = "steward_mc:settlement/";
		return owner == null || !owner.startsWith(prefix) || owner.length() == prefix.length() ? null : owner.substring(prefix.length());
	}

	/**
	 * (8bS) The settlements whose views this round rebuilds: every one players stand in on a full round; else the dirty ones and the ones a player has
	 * just entered ({@code at}: each player's settlement now, "" for none; {@code was}: the one their last view was for). Pure.
	 */
	static Set<String> toRebuild(Map<UUID, String> at, Map<UUID, String> was, Set<String> dirty, boolean full) {
		Set<String> out = new HashSet<>();
		for (var e : at.entrySet()) {
			String id = e.getValue();
			if (id.isEmpty()) continue;
			if (full || dirty.contains(id) || !id.equals(was.get(e.getKey()))) out.add(id);
		}
		return out;
	}

	/** (8bS) Whether a player's view is looked at this round: their settlement is rebuilt, or they left every claim since their last view. Pure. */
	static boolean looked(String at, @Nullable String was, Set<String> rebuilt) {
		return at.isEmpty() ? !at.equals(was) : rebuilt.contains(at);
	}

	public static void init() {
		try {
			dev.larattalabs.architect.api.SiteEvents.SETTLEMENT_CHANGED.register((owner, region) -> {
				String id = settlementOf(owner);
				if (id != null) dirty(id);
			});
		} catch (LinkageError e) {
			Steward.LOGGER.info("this Architect does not signal settlement changes (it needs 0.81): the settlement views are read every two seconds");
		}
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			try {
				signalled = dev.larattalabs.architect.api.ArchitectApi.get().features().contains(FEATURE);
			} catch (RuntimeException | LinkageError e) {
				signalled = false;
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % EVERY != 0 && DIRTY.isEmpty()) return;
			boolean full = !signalled || server.getTickCount() % RESYNC == 0;
			Map<UUID, String> at = new HashMap<>();
			for (var p : server.getPlayerList().getPlayers()) at.put(p.getUUID(), Settlements.at(p.level(), p.blockPosition()).map(s -> s.id()).orElse(""));
			Set<String> rebuilt = toRebuild(at, AT, DIRTY, full);
			DIRTY.clear();
			// each settlement's view is built once a round, however many players stand in it
			Map<String, String> views = new HashMap<>();
			for (var p : server.getPlayerList().getPlayers()) {
				String id = at.getOrDefault(p.getUUID(), "");
				if (!looked(id, AT.get(p.getUUID()), rebuilt)) continue;
				String json = id.isEmpty() ? "" : views.computeIfAbsent(id, k -> Settlements.store().get(k).map(s -> Actions.view(server, s).toJson()).orElse(""));
				AT.put(p.getUUID(), id);
				if (json.equals(SENT.get(p.getUUID()))) continue;
				SENT.put(p.getUUID(), json);
				ServerPlayNetworking.send(p, new StewardNet.SettlementNear(json));
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			SENT.clear();
			AT.clear();
			DIRTY.clear();
			signalled = false;
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
			SENT.remove(h.getPlayer().getUUID());
			AT.remove(h.getPlayer().getUUID());
		});
	}
}
