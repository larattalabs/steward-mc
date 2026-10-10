package dev.larattalabs.steward.service;

import dev.larattalabs.steward.net.StewardNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;

/**
 * What a settlement's steward says, as a speech bubble over it (docs/PLAN.md "Interface": its speech bubbles; chat keeps the log). Sent to every player: a
 * client shows it over the steward when it has that steward loaded, and drops it otherwise. Server thread.
 */
public final class StewardVoice {
	/** Longer lines are cut for the bubble (it wraps to three lines, the chat has the whole text). */
	static final int MAX = 300;

	private StewardVoice() {
	}

	public static void say(MinecraftServer server, String settlementId, String text) {
		if (settlementId == null || settlementId.isEmpty() || text == null || text.isBlank()) return;
		String t = text.length() > MAX ? text.substring(0, MAX) : text;
		var msg = new StewardNet.StewardSay(settlementId, t);
		server.getPlayerList().getPlayers().forEach(p -> ServerPlayNetworking.send(p, msg));
		// it turns to whoever is near and talks
		Settlements.store().get(settlementId).ifPresent(s -> {
			var level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
				net.minecraft.resources.Identifier.parse(s.claim().dimension())));
			if (level != null) level.getEntities(dev.larattalabs.steward.entity.StewardEntity.TYPE, e -> settlementId.equals(e.settlementId()))
				.forEach(dev.larattalabs.steward.entity.StewardEntity::speak);
		});
	}
}
