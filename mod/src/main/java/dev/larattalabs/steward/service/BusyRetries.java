package dev.larattalabs.steward.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * (T4R-RETRY) The one rule for a call Architect refused as busy ({@link SiteBusy}) that Steward asks again by itself: a site-by-site undo (row 1), a
 * village's cancel (row 5), an unrecorded road's take-up (row 7) and a placement (row 12). Each waiting operation has one key ({@code undo:<settlement>},
 * {@code cancel:<settlement>:<region>}, ...), so one operation waits once, whoever refused it.
 * <ul>
 * <li>A wait behind a removal ({@link SiteBusy.Held#waits}) is released by that removal's {@code REMOVAL_FINISHED} ({@link SiteBusy.Held#finishedBy},
 * Architect's own match), or by its timer ({@link #REMOVAL_MS}); a wait behind another change by its timer alone ({@link #SOON_MS}).</li>
 * <li>A blocker that stops without finishing fires no event, so every wait has the timer, at most {@link #MAX_TRIES} times per operation. A release only
 * asks again (Architect refuses again while it is still busy): it never counts as finished.</li>
 * <li>Single flight: a release takes the wait out before it runs its retry, so an event and a timer that race run it once; a timer whose wait was replaced
 * or taken does nothing.</li>
 * <li>Past the bound the caller says once that it still waits ({@link Step#gaveUp}); a wait made with {@code keepForEvent} then stays for its event only
 * (no more timers).</li>
 * </ul>
 * Memory only, this world session (cleared at its stop): what decides "did it happen" is each operation's own record (an undo's intent note and its
 * {@code PROJECT_REMOVED}, a build's saved state, Architect's own sites). A retry writes its own intent before it asks Architect, as the first ask did.
 * Server thread.
 */
public final class BusyRetries {
	private BusyRetries() {
	}

	/** At most this many timed releases of one operation. */
	static final int MAX_TRIES = 10;
	/** How long a wait behind a removal waits for its end before it is asked again anyway. */
	static final long REMOVAL_MS = 60_000;
	/** How long a wait behind another change (no event follows it) waits before it is asked again. */
	static final long SOON_MS = 5_000;
	/** Runs a timed release later (tests run it themselves). */
	static final java.util.function.BiConsumer<Long, Runnable> DELAYED = (ms, r) -> java.util.concurrent.CompletableFuture.delayedExecutor(ms,
		java.util.concurrent.TimeUnit.MILLISECONDS).execute(r);
	static java.util.function.BiConsumer<Long, Runnable> later = DELAYED;
	/** The running world's server (set at its start, cleared at its stop): timed releases are posted to its thread. */
	private static volatile net.minecraft.server.@Nullable MinecraftServer server;
	/** Posts a timed release to the server thread; nothing without a running world (tests run it at once). */
	static final java.util.function.Consumer<Runnable> ON_SERVER = r -> {
		var srv = server;
		if (srv != null) ArchitectAccess.onServer(srv, r);
	};
	static java.util.function.Consumer<Runnable> onServer = ON_SERVER;

	/** Whether a settlement runs something a retry must not start beside (a build, an undo, a district step); tests read their own state. */
	static java.util.function.Predicate<String> busyCheck = SettlementRunner::busy;

	/** {@link #busyCheck}, and busy when it can't be told. */
	static boolean settlementBusy(String settlementId) {
		try {
			return busyCheck.test(settlementId);
		} catch (RuntimeException | LinkageError e) {
			return true;
		}
	}

	/** The running world's server, or null. */
	static net.minecraft.server.@Nullable MinecraftServer server() {
		return server;
	}

	/**
	 * What a busy refusal leads to: {@code first} (the first wait of this operation: said), {@code gaveUp} (past the bound: said once, no timer), and the
	 * timer's delay ({@code -1}: none).
	 */
	record Step(boolean first, boolean gaveUp, long retryAfterMs) {
	}

	private static final class Wait {
		final SiteBusy.Held held;
		final Runnable retry;
		final int session;

		Wait(SiteBusy.Held held, Runnable retry, int session) {
			this.held = held;
			this.retry = retry;
			this.session = session;
		}
	}

	private static final Map<String, Wait> WAITING = new HashMap<>();
	private static final Map<String, Integer> TRIES = new HashMap<>();

	/** Wires the server and the session's end (called once at mod init, from {@link RemovalEnds#init}, before any world starts). */
	static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			server = null;
			clear();
		});
	}

	/**
	 * {@code key}'s call was refused as busy ({@code h}): it waits, and {@code retry} runs once when it is released (its blocker's end, or the timer while
	 * tries are left). Past {@link #MAX_TRIES} it waits no more by time: with {@code keepForEvent} it stays for its blocker's end only (a wait behind a
	 * removal), else it is dropped. Server thread.
	 */
	static synchronized Step wait(String key, SiteBusy.Held h, Runnable retry, boolean keepForEvent) {
		int n = TRIES.merge(key, 1, Integer::sum);
		boolean gaveUp = n > MAX_TRIES;
		if (gaveUp) {
			if (keepForEvent && h.waits()) WAITING.put(key, new Wait(h, retry, Session.current()));
			else WAITING.remove(key);
			return new Step(false, n == MAX_TRIES + 1, -1);
		}
		Wait w = new Wait(h, retry, Session.current());
		WAITING.put(key, w);
		long after = h.waits() ? REMOVAL_MS : SOON_MS;
		later.accept(after, () -> onServer.accept(() -> release(key, w)));
		return new Step(n == 1, false, after);
	}

	/** The timer of {@code w}: runs its retry only when it is still {@code key}'s wait, in its own session. */
	private static void release(String key, Wait w) {
		synchronized (BusyRetries.class) {
			if (WAITING.get(key) != w || !Session.is(w.session)) return;
			WAITING.remove(key);
		}
		run(key, w);
	}

	/** A removal finished: every wait behind exactly that removal ({@code Busy.finishedBy}) is asked again, once. Whether any was. Server thread. */
	static boolean removalFinished(dev.larattalabs.architect.api.@Nullable RemovalFinished f) {
		if (f == null) return false;
		List<Map.Entry<String, Wait>> due = new ArrayList<>();
		synchronized (BusyRetries.class) {
			for (var it = WAITING.entrySet().iterator(); it.hasNext();) {
				var e = it.next();
				if (Session.is(e.getValue().session) && e.getValue().held.finishedBy(f)) {
					due.add(Map.entry(e.getKey(), e.getValue()));
					it.remove();
				}
			}
		}
		due.forEach(e -> run(e.getKey(), e.getValue()));
		return !due.isEmpty();
	}

	private static void run(String key, Wait w) {
		try {
			w.retry.run();
		} catch (RuntimeException e) {
			dev.larattalabs.steward.Steward.LOGGER.warn("the busy retry {} failed", key, e);
		}
	}

	/** Whether {@code key} waits to be asked again. */
	static synchronized boolean waiting(String key) {
		return WAITING.containsKey(key);
	}

	/** How many busy refusals {@code key} has had since it was last forgotten. */
	static synchronized int tries(String key) {
		return TRIES.getOrDefault(key, 0);
	}

	/**
	 * {@code key}'s operation is over or asked anew (it went through, it stopped for another reason, or the player asked again): its wait is dropped (a
	 * timer or event after this does nothing) and its count starts again.
	 */
	static synchronized void forget(String key) {
		WAITING.remove(key);
		TRIES.remove(key);
	}

	/** Every key starting with {@code prefix} forgotten (a settlement's waits). */
	static synchronized void forgetAll(String prefix) {
		WAITING.keySet().removeIf(k -> k.startsWith(prefix));
		TRIES.keySet().removeIf(k -> k.startsWith(prefix));
	}

	static synchronized void clear() {
		WAITING.clear();
		TRIES.clear();
	}
}
