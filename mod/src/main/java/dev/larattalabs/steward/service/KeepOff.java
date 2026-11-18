package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.steward.Steward;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * (slice 8aS, Architect 8a HB, S-8a-1) Where a settlement's steward does not walk: the boxes of its regions' parts still being built from the stockpile
 * (a part in state {@code building}: half-built terrain opens drops, fills leave steps). Read from Architect at most every {@link #EVERY_TICKS} per
 * settlement. Server thread.
 */
public final class KeepOff {
	/** How often a settlement's boxes are read again (game ticks). */
	static final int EVERY_TICKS = 40;

	private record Read(long at, List<BoundingBox> boxes) {}

	private static final Map<String, Read> CACHE = new java.util.HashMap<>();

	private KeepOff() {
	}

	/** The boxes {@code settlementId}'s steward keeps off now: empty when nothing of it is being built (or Architect can't be read). */
	public static List<BoundingBox> of(MinecraftServer server, String settlementId, long gameTime) {
		Read r = CACHE.get(settlementId);
		if (r != null && gameTime >= r.at() && gameTime - r.at() < EVERY_TICKS) return r.boxes();
		List<BoundingBox> boxes = new ArrayList<>();
		List<String> regions = MaterialRequests.buildingRegions(settlementId);
		if (!regions.isEmpty()) {
			try {
				var api = ArchitectApi.get();
				var sites = api.sites(server);
				for (String id : regions) {
					var v = api.regions().get(id);
					if (v.isEmpty()) continue;
					boxes.addAll(TerrainBuild.keepOff(v.get().build(), t -> {
						try {
							return sites.get(t).map(dev.larattalabs.architect.api.SiteView::box);
						} catch (RuntimeException e) {
							return Optional.empty();
						}
					}));
				}
			} catch (RuntimeException | LinkageError e) {
				// read again next time; the steward walks as before meanwhile
				Steward.LOGGER.debug("keep-off boxes of {} not read", settlementId, e);
			}
		}
		List<BoundingBox> out = List.copyOf(boxes);
		CACHE.put(settlementId, new Read(gameTime, out));
		return out;
	}

	/** At a world's stop: nothing is kept across worlds. */
	public static void clear() {
		CACHE.clear();
	}
}
