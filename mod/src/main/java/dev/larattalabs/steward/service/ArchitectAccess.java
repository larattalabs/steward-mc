package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * (slice GEA) Architect's API on the recovery paths unit tests drive (the reconcile, the growth sweep, growth undo, a district's land; TESTABLE-ARCH: the
 * composed hold, realise and put-down re-ask; CRASH-T: a restart's resume, the village found by its realise key, the region watched and its placement logged): {@link ArchitectApi#get()}
 * on every call, and the server's own executor, unless a test supplied its own. {@code ArchitectApi} is Architect's, so the seam is Steward's side of the call.
 * Production never calls {@link #useForTest}; with nothing supplied this is exactly {@code ArchitectApi.get()} and {@code server.execute}.
 * (CRASH-T2) Also the realise's player lookup ({@link #player}): {@code server.getPlayerList().getPlayer(id)} unless a test supplied its own
 * ({@link #usePlayersForTest}), so a test with no server reaches the realise through the runner's own steps.
 * (FENCE-TESTS-4) And the world's mode and the town's stockpile as a growth's realise reads them ({@link #regionMaterials}, {@link #hasStockpile}):
 * {@code WorldMode} and the server's default game type, and {@link Stockpiles#of}, unless a test supplied its own world ({@link #useWorldForTest}).
 */
final class ArchitectAccess {
	private static volatile @Nullable ArchitectApi testApi;
	private static volatile @Nullable Executor testServer;
	private static volatile @Nullable Function<UUID, @Nullable ServerPlayer> testPlayers;
	private static volatile @Nullable Boolean testRegionMaterials, testStockpile;

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

	/** {@code server.getPlayerList().getPlayer(id)}, unless a test supplied its own lookup (then {@code server} is never read). */
	static @Nullable ServerPlayer player(MinecraftServer server, UUID id) {
		Function<UUID, @Nullable ServerPlayer> t = testPlayers;
		return t != null ? t.apply(id) : server.getPlayerList().getPlayer(id);
	}

	/**
	 * Whether this world builds regions from materials (Architect's RegionSurvival.on: the survival toggle on, the default game type not creative),
	 * unless a test supplied its own world (then {@code server} is never read).
	 */
	static boolean regionMaterials(MinecraftServer server) {
		Boolean t = testRegionMaterials;
		if (t != null) return t;
		return dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false)
			&& server.getDefaultGameType() != net.minecraft.world.level.GameType.CREATIVE;
	}

	/** Whether the settlement has a stockpile ({@link Stockpiles#of}), unless a test supplied its own world (then {@code server} is never read). */
	static boolean hasStockpile(MinecraftServer server, dev.larattalabs.steward.model.Settlement s) {
		Boolean t = testStockpile;
		if (t != null) return t;
		return Stockpiles.of(server, s).isPresent();
	}

	/** Tests only: a world that builds regions from materials or not, and a settlement stockpile or none. Undone by {@link #reset()}. */
	static void useWorldForTest(boolean regionMaterials, boolean stockpile) {
		testRegionMaterials = regionMaterials;
		testStockpile = stockpile;
	}

	/** Tests only: {@code players} in place of the server's player list. Undone by {@link #reset()}. */
	static void usePlayersForTest(Function<UUID, @Nullable ServerPlayer> players) {
		testPlayers = players;
	}

	/** Tests only: {@code api} in place of Architect's, {@code server} in place of the server's executor. Undone by {@link #reset()}. */
	static void useForTest(ArchitectApi api, Executor server) {
		testApi = api;
		testServer = server;
	}

	/** Tests only: back to Architect's API, the server's executor, its player list and its world. */
	static void reset() {
		testApi = null;
		testServer = null;
		testPlayers = null;
		testRegionMaterials = null;
		testStockpile = null;
	}
}
