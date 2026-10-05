package dev.larattalabs.steward.gateway;

import dev.larattalabs.architect.api.ArchitectApi;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;

/** Whether this world is a survival-toggle world (Architect's per-world switch), read with {@code Sites.survival()} (Architect API 1.2.0). */
public final class WorldMode {
	private WorldMode() {
	}

	/** True when construction sites are on; empty when it cannot be read (an older Architect without {@code survivalInfo}, or an API error). */
	public static Optional<Boolean> survival(MinecraftServer server) {
		try {
			return Optional.of(ArchitectApi.get().sites(server).survival().enabled());
		} catch (RuntimeException | LinkageError e) {
			return Optional.empty();
		}
	}
}
