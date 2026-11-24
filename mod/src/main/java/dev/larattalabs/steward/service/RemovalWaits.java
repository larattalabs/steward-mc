package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.RemovalWait;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * (T4R-RETRY) A group's or village's removal that Architect admitted but holds on its FIFO ticket ({@code SiteGroup.removalWait()},
 * {@code RegionView.removalWait()}, T4R-D A7b): the undo's call stays unanswered meanwhile, so the steward reads the wait while the call is in flight
 * and says it once, in its own words, naming the blocker as {@link RemovalStops#blocker} does. Bounded: read every {@link #EVERY_MS} until the call
 * answers, the wait is said, or {@link #MAX_CHECKS} reads (Architect stops a ticket wait after 10 minutes, WAITING_BEHIND). Nothing is written: words
 * only. Server thread.
 */
public final class RemovalWaits {
	private RemovalWaits() {
	}

	/** How often a removal in flight is read for its wait. */
	static final long EVERY_MS = 5_000;
	/** At most this many reads per removal (12.5 minutes). */
	static final int MAX_CHECKS = 150;
	/** Runs a read later (tests run it themselves). */
	static java.util.function.BiConsumer<Long, Runnable> later = BusyRetries.DELAYED;

	/** The steward's line for a removal waiting behind {@code blocker} (its own words for it). Pure. */
	static String undoWaitsBehind(String blocker) {
		return "The undo waits for " + blocker + " to be taken down first, then carries on by itself. Nothing more to do.";
	}

	/** Architect's typed wait as Steward's busy hold (its blocker named as {@link SiteBusy} names one); null for none. Pure. */
	static SiteBusy.@Nullable Held held(@Nullable RemovalWait w) {
		if (w == null || w.blocking() == null) return null;
		var b = w.blocking();
		SiteBusy.Busy k = SiteBusy.busyOf(b.kind() == null ? null : b.kind().name());
		return new SiteBusy.Held(SiteBusy.kindOf(k), null, null, false, k, b.blockingId(), b.blockingKind(), b.blockingOwner(), b);
	}

	/**
	 * Watches {@code call} (a removal in flight): each read of {@code read} (the group's or region's wait; null when none or unreadable) that finds a wait
	 * says {@code words} of its blocker once and stops; the call answering, or {@link #MAX_CHECKS} reads, stop it silently.
	 */
	static void watch(net.minecraft.server.@Nullable MinecraftServer server, String owner, Supplier<@Nullable RemovalWait> read, CompletableFuture<?> call,
		Consumer<String> say) {
		int session = Session.current();
		schedule(server, owner, read, call, say, 0, session);
	}

	private static void schedule(net.minecraft.server.@Nullable MinecraftServer server, String owner, Supplier<@Nullable RemovalWait> read, CompletableFuture<?> call,
		Consumer<String> say, int checks, int session) {
		if (call.isDone() || checks >= MAX_CHECKS || !Session.is(session)) return;
		later.accept(EVERY_MS, () -> BusyRetries.onServer.accept(() -> {
			if (call.isDone() || !Session.is(session)) return;
			SiteBusy.Held h;
			try {
				h = held(read.get());
			} catch (RuntimeException | LinkageError e) {
				h = null; // an Architect without removalWait, or a read that failed: silent, as before
			}
			if (h != null) {
				say.accept(undoWaitsBehind(RemovalStops.blocker(server, h, owner)));
				return;
			}
			schedule(server, owner, read, call, say, checks + 1, session);
		}));
	}

	/** A site group's wait, or null. */
	static @Nullable RemovalWait ofGroup(dev.larattalabs.architect.api.Sites sites, String group) {
		return sites.group(group).map(dev.larattalabs.architect.api.SiteGroup::removalWait).orElse(null);
	}

	/** A region's wait, or null. */
	static @Nullable RemovalWait ofRegion(String region) {
		return ArchitectAccess.api().regions().get(region).map(dev.larattalabs.architect.api.RegionView::removalWait).orElse(null);
	}
}
