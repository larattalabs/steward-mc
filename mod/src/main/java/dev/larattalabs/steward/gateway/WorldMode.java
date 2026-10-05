package dev.larattalabs.steward.gateway;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.Library;
import dev.larattalabs.architect.api.Mode;
import dev.larattalabs.architect.api.PlaceRequest;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;

/**
 * Whether this world is a survival-toggle world (Architect's per-world switch). Architect API 1.1.0 has no direct read; the agreed workaround
 * (Architect session, 2026-10-05) is a dry-run {@code Sites.check} with {@code Mode.AUTO}: {@code Verdict.construction()} is exactly the world's toggle.
 * Refusals do not matter for this read. Replace with {@code Sites.survival()} when Architect API 1.2.0 ships.
 */
public final class WorldMode {
	private WorldMode() {
	}

	/** True when the world is in survival mode; empty when it cannot be read (no library entry, API error). */
	public static Optional<Boolean> survival(MinecraftServer server, ServerLevel level) {
		try {
			ArchitectApi api = ArchitectApi.get();
			Optional<Library.Entry> any = api.library().list().stream().findFirst();
			if (any.isEmpty()) return Optional.empty();
			PlaceRequest r = new PlaceRequest(any.get().id(), level, BlockPos.ZERO, Rotation.NONE, Mode.AUTO, null, new JsonObject(), false, null);
			return Optional.of(api.sites(server).check(r).construction());
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
