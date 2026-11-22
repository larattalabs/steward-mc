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
 * The event reaches Steward through {@link RemovalEnd}, a Steward-side record ({@link #of} is the one place that reads Architect's), and {@link #handle}
 * is unit-tested through FakeArchitect. (T4RD-S) {@link #init} subscribes once, behind the feature and a linkage guard.
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
	 * A removal txn's end, as Steward reads it: {@code kind} {@code group | site | region}, {@code id} the group, site or region id, {@code stage} and
	 * {@code revisionId} (a stage's removal, and the revision whose undo removed it; else null), {@code removed} its result's, {@code refund} what was
	 * credited back (never owed), {@code owed} its immutable owed parts (T4R-D {@code owedParts}; each settlement says only its own, {@link OwedParts}).
	 */
	public record RemovalEnd(String kind, String id, @Nullable String stage, @Nullable Integer revisionId, boolean removed, Map<Item, Integer> refund,
		List<dev.larattalabs.architect.api.OwedPart> owed) {
		public RemovalEnd {
			refund = refund == null ? Map.of() : Map.copyOf(refund);
			owed = owed == null ? List.of() : List.copyOf(owed);
		}

		/** Without a stage or revision (a site, road, group or region). */
		public RemovalEnd(String kind, String id, boolean removed, Map<Item, Integer> refund, List<dev.larattalabs.architect.api.OwedPart> owed) {
			this(kind, id, null, null, removed, refund, owed);
		}
	}

	/** (T4RD-S) Architect's event as Steward's record: the one place that reads {@code RemovalFinished}. Pure. */
	static RemovalEnd of(dev.larattalabs.architect.api.RemovalFinished e) {
		var r = e.result();
		return new RemovalEnd(e.kind(), e.id(), e.stage(), e.revisionId(), r.removed(), r.refund(), r.owedParts());
	}

	/** Whether the event is subscribed (once per game, never again on a second world). */
	private static volatile boolean subscribed;

	/** The server of the running world (the event's listener posts to it). */
	private static volatile @Nullable MinecraftServer server;

	/**
	 * Wires the event (T4RD-S, the T4c adapter): {@code SiteEvents.REMOVAL_FINISHED} subscribed once, at mod init, so Architect's replay after a restart
	 * (re-fired until delivered, as its recovery runs) is never fired before Steward listens; each event is used only while a world runs and Architect
	 * advertises {@link #FEATURE}. An Architect without the event (a {@link LinkageError}) is logged and left to the sweep. Each event is posted to the
	 * server thread and {@link #handle}d, and updates that waited behind a removal are looked at again ({@link Updates#removalFinished}). Which held note
	 * an event ends (kind, id, stage, revision) is {@link Undo#finish}'s one match.
	 */
	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(srv -> server = srv);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(srv -> server = null);
		if (subscribed) return;
		try {
			dev.larattalabs.architect.api.SiteEvents.REMOVAL_FINISHED.register(e -> {
					MinecraftServer srv2 = server;
					if (srv2 == null || e == null || !enabled()) return;
					RemovalEnd end;
					try {
						end = of(e);
					} catch (RuntimeException | LinkageError x) {
						Steward.LOGGER.warn("a removal finished, but its result could not be read (the sweep looks again)", x);
						return;
					}
					srv2.execute(() -> {
						Updates.removalFinished(e);
						handle(srv2, end, voice(srv2));
					});
			});
			subscribed = true;
		} catch (LinkageError x) {
			Steward.LOGGER.warn("this Architect has no REMOVAL_FINISHED ({}): held removals finish by the sweep only", FEATURE, x);
		}
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
		Steward.LOGGER.info("removal {} {} finished (removed {}, refund {}, owed parts {})", e.kind(), e.id(), e.removed(), e.refund().size(), e.owed().size());
		if (!e.removed()) return;
		for (Settlement s : List.copyOf(Settlements.store().all())) {
			// an undo of it still answering in this session logs it itself (a held one has released its mark)
			if (Undo.busy(s.id())) continue;
			List<Settlement.LogEntry> logged;
			try {
				logged = Undo.finish(srv, s, doneKind(e), doneId(e), say);
			} catch (RuntimeException | LinkageError x) {
				Steward.LOGGER.warn("{}: removal {} {} finished, but could not be logged (the sweep looks again)", s.id(), e.kind(), e.id(), x);
				continue;
			}
			// its refund is said only when this event's own removal was logged now (the sweep says what it logs from Architect's snapshot)
			if (logged.stream().noneMatch(x -> Undo.endedBy(x, doneKind(e), doneId(e)))) continue;
			for (String line : said(e.refund(), OwedParts.owedTo(s.owner(), e.owed()), over(srv, s), s.id())) say.accept(s.id(), line);
		}
		SettlementRunner.removalFinished(e.kind(), e.id());
	}

	/**
	 * (T4RD-S, GPT P1-1) The kind the event is evidence for: a group's event naming a stage ({@code kind group}, {@code stage} set) is that stage's
	 * end only, never the whole group's ({@code "stage"}, which no entry's match takes as gone). Pure.
	 */
	static String doneKind(RemovalEnd e) {
		return "group".equals(e.kind()) && e.stage() != null ? "stage" : e.kind();
	}

	/** The id {@link #doneKind} names: {@code <group>/<stage>} for a stage. Pure. */
	static String doneId(RemovalEnd e) {
		return "group".equals(e.kind()) && e.stage() != null ? e.id() + "/" + e.stage() : e.id();
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
