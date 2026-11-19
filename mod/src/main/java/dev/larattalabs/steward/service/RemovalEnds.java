package dev.larattalabs.steward.service;

import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * (T4S-PRE) Steward's one path for a removal's end on Architect's transaction layer (T4 change 8): {@code SiteEvents.REMOVAL_FINISHED}, fired at least once
 * for every removal txn when it retires (at its EFFECTS_DONE for one never held, so there is one path), with the full {@code RemoveResult}, owed included.
 * Architect 0.83.0 has neither the event nor {@code RemoveResult.owed()}, so the event reaches Steward through {@link RemovalEnd}, a Steward-side record,
 * and {@link #handle} is unit-tested through FakeArchitect. The one-place adapter that subscribes at the pin is in {@link #init} (TODO(T4c)).
 * <p>
 * Idempotent on saved state, never on a seen-event set (Steward's TXN review, round 2): {@link #handle} logs only what {@link Undo#finish} finds open and
 * unretired on a fresh read, and says words only for what it logged, so a re-fire after recovery logs and says nothing more.
 */
public final class RemovalEnds {
	/** Architect's feature for {@code RemovalFinished} (T4 change 8; Since NEXT). */
	public static final String FEATURE = "removal_finished";

	private RemovalEnds() {
	}

	/**
	 * A removal txn's end, as Steward reads it: {@code kind} {@code group | site | region}, {@code id} the group, site or region id, {@code removed} its
	 * result's, {@code refund} what was credited back (never owed), {@code owed} what Architect owes (its {@code RemoveResult.owed()} keys resolved to
	 * items through {@code Sites.owed(recipient)}; listed apart, never in the refund).
	 */
	public record RemovalEnd(String kind, String id, boolean removed, Map<Item, Integer> refund, Map<Item, Integer> owed) {
	}

	/** The server of the running world (the event's listener posts to it). */
	private static volatile @Nullable MinecraftServer server;

	/**
	 * Wires the event. TODO(T4c): the one-place adapter. At the Architect pin carrying T4c ({@code SiteEvents.REMOVAL_FINISHED}, feature
	 * {@code "removal_finished"}, {@code RemoveResult.owed()}, {@code Sites.owed(recipient)}), add inside this method, gated on {@link #enabled()}:
	 * <pre>{@code
	 * dev.larattalabs.architect.api.SiteEvents.REMOVAL_FINISHED.register(e -> {
	 *     MinecraftServer srv = server;
	 *     if (srv == null || !enabled()) return;
	 *     srv.execute(() -> handle(srv, new RemovalEnd(e.kind(), e.id(), e.result().removed(), e.result().refund(),
	 *         owedItems(srv, e.result().owed())), voice(srv)));
	 * });
	 * }</pre>
	 * (the listener's exact shape follows the shipped {@code RemovalFinished(String kind, String id, RemoveResult result)}); {@code owedItems} sums the
	 * {@code Owed.items} of the entries whose key the result lists, over every settlement owner's {@code Sites.owed(owner)}.
	 */
	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(srv -> server = srv);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(srv -> server = null);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
			if (enabled()) Steward.LOGGER.warn("Architect advertises {}, but this Steward has no RemovalFinished adapter yet (T4c): held removals finish by"
				+ " the sweep only", FEATURE);
		});
	}

	/** Whether Architect fires {@code RemovalFinished} ({@link #FEATURE}); false when it can't be read. */
	static boolean enabled() {
		return Undo.txnRemovals();
	}

	/** Lines for a settlement, said by its steward. */
	static BiConsumer<String, String> voice(MinecraftServer srv) {
		return (id, line) -> StewardVoice.say(srv, id, line);
	}

	/**
	 * A removal's end. Not removed (an abort: compensated, nothing taken down): every held note it named is left for the next undo, nothing logged.
	 * Removed: each settlement's open undo it ends is logged once ({@link Undo#finish}: the region's tombstone, the group's or the site's positive
	 * answer), and only then said with what came back ({@link Growths#refundLine(Map, Map)}: owed listed apart) and the stockpile's state once the
	 * refund has landed (over capacity). A cancel waiting on it is nudged ({@link SettlementRunner#removalFinished}). Server thread.
	 */
	static void handle(@Nullable MinecraftServer srv, RemovalEnd e, BiConsumer<String, String> say) {
		if (e == null || e.kind() == null || e.id() == null) return;
		Steward.LOGGER.info("removal {} {} finished (removed {}, refund {}, owed {})", e.kind(), e.id(), e.removed(), e.refund().size(), e.owed().size());
		if (!e.removed()) return;
		for (Settlement s : List.copyOf(Settlements.store().all())) {
			List<Settlement.LogEntry> logged;
			try {
				logged = Undo.finish(srv, s, e.kind(), e.id(), say);
			} catch (RuntimeException | LinkageError x) {
				Steward.LOGGER.warn("{}: removal {} {} finished, but could not be logged (the sweep looks again)", s.id(), e.kind(), e.id(), x);
				continue;
			}
			if (logged.isEmpty()) continue;
			for (String line : said(e.refund(), e.owed(), over(srv, s), s.id())) say.accept(s.id(), line);
		}
		SettlementRunner.removalFinished(e.kind(), e.id());
	}

	/**
	 * What a finished, held removal's end says after its "Undone" line: what came back (refund and owed, apart) when anything did, then, when the refund
	 * took the stockpile past its capacity, by how much ({@code over}: used minus capacity in stacks, measured once the refund landed). Pure over
	 * {@code over}; item names are read from the game.
	 */
	static List<String> said(Map<Item, Integer> refund, Map<Item, Integer> owed, int over, String settlementId) {
		List<String> out = new java.util.ArrayList<>();
		if (!refund.isEmpty() || !owed.isEmpty()) out.add(Growths.refundLine(refund, owed));
		String cap = refund.isEmpty() ? null : Stockpiles.overCapacityWords(over, settlementId);
		if (cap != null) out.add(cap);
		return out;
	}

	/** The settlement's stockpile over its capacity in stacks now (0 when under, none, or unreadable). */
	private static int over(@Nullable MinecraftServer srv, Settlement s) {
		if (srv == null) return 0;
		try {
			return Stockpiles.of(srv, s).map(p -> p.usedStacks() - p.capacityStacks()).orElse(0);
		} catch (RuntimeException | LinkageError x) {
			return 0;
		}
	}
}
