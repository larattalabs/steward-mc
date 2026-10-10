package dev.larattalabs.steward.service;

import dev.larattalabs.steward.net.StewardNet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Tells each player about the settlement they stand in, for the in-world interface (docs/PLAN.md "Interface", 3d: building labels on look-at, survey mode):
 * every two seconds, the settlement's view (its buildings with their boxes and versions), sent only when it changed, and "" when they left every claim.
 * Viewing is open to everyone. Server thread.
 */
public final class SettlementSync {
	private static final Map<UUID, String> SENT = new HashMap<>();
	static final int EVERY = 40;

	private SettlementSync() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % EVERY != 0) return;
			// each settlement's view is built once a round, however many players stand in it
			Map<String, String> views = new HashMap<>();
			for (var p : server.getPlayerList().getPlayers()) {
				var s = Settlements.at(p.level(), p.blockPosition());
				String json = s.isEmpty() ? "" : views.computeIfAbsent(s.get().id(), k -> Actions.view(server, s.get()).toJson());
				if (json.equals(SENT.get(p.getUUID()))) continue;
				SENT.put(p.getUUID(), json);
				ServerPlayNetworking.send(p, new StewardNet.SettlementNear(json));
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> SENT.clear());
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> SENT.remove(h.getPlayer().getUUID()));
	}
}
