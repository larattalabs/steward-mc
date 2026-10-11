package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.ProtectedArea;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

/**
 * The player's protected areas as Architect's write bounds (C17, Architect 0.15): every area of a settlement is also marked with {@code Sites.protect} under
 * the settlement's owner, so no placement, update, road or terrain op of it writes there, whatever its {@code force}; removing and undoing still work.
 * Steward's own list is the truth (its layouts and roads keep off the areas first); this keeps Architect's copy the same, after each change and when a
 * world starts (areas from before 0.15 are handed over then). Server thread.
 */
public final class Protection {
	private Protection() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			for (Settlement s : Settlements.store().all()) sync(server, s.id());
		});
	}

	/** Makes Architect's areas of the settlement exactly its own: missing ones marked, lifted ones lifted. Returns false when Architect refused one. */
	public static boolean sync(MinecraftServer server, String settlementId) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return true;
		var sites = ArchitectApi.get().sites(server);
		String owner = s.get().owner();
		boolean ok = true;
		Set<String> mine = new HashSet<>();
		for (Settlement.Area a : s.get().lands().protect()) {
			mine.add(a.id());
			try {
				ResourceKey<net.minecraft.world.level.Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(a.dimension()));
				sites.protect(new ProtectedArea(owner, a.id(), dim, a.x0(), a.z0(), a.x1(), a.z1(), a.label()));
			} catch (RuntimeException e) {
				Steward.LOGGER.warn("Architect would not protect {} of {}: {}", a.id(), settlementId, e.toString());
				ok = false;
			}
		}
		try {
			for (ProtectedArea p : sites.protectedAreas(owner)) if (!mine.contains(p.id())) sites.unprotect(owner, p.id());
		} catch (RuntimeException e) {
			Steward.LOGGER.warn("could not read Architect's protected areas of {}: {}", settlementId, e.toString());
			ok = false;
		}
		return ok;
	}
}
