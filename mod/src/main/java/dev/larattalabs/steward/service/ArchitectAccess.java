package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import java.util.concurrent.Executor;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * (slice GEA) Architect's API on the recovery paths unit tests drive (the reconcile, the growth sweep, growth undo, a district's land; TESTABLE-ARCH: the
 * composed hold, realise and put-down re-ask; CRASH-T: a restart's resume, the village found by its realise key, the region watched and its placement logged): {@link ArchitectApi#get()}
 * on every call, and the server's own executor, unless a test supplied its own. {@code ArchitectApi} is Architect's, so the seam is Steward's side of the call.
 * Production never calls {@link #useForTest}; with nothing supplied this is exactly {@code ArchitectApi.get()} and {@code server.execute}.
 */
final class ArchitectAccess {
	private static volatile @Nullable ArchitectApi testApi;
	private static volatile @Nullable Executor testServer;

	private ArchitectAccess() {
	}

	/** {@link ArchitectApi#get()}, read again on every call (never cached), unless a test supplied its own. */
	static ArchitectApi api() {
		ArchitectApi t = testApi;
		return t != null ? t : ArchitectApi.get();
	}

	/** {@code server.execute(r)}, unless a test supplied its own executor. */
	static void onServer(MinecraftServer server, Runnable r) {
		Executor t = testServer;
		if (t != null) t.execute(r);
		else server.execute(r);
	}

	/** Tests only: {@code api} in place of Architect's, {@code server} in place of the server's executor. Undone by {@link #reset()}. */
	static void useForTest(ArchitectApi api, Executor server) {
		testApi = api;
		testServer = server;
	}

	/** Tests only: back to Architect's API and the server's executor. */
	static void reset() {
		testApi = null;
		testServer = null;
	}
}
