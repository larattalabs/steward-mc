package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.OwedPart;
import dev.larattalabs.architect.api.RegionView;
import dev.larattalabs.architect.api.RemoveResult;
import dev.larattalabs.architect.api.RevisionView;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * (T4R-RETRY) A growth's take-up that finished by itself (a decided, held {@code undoRevision}, or one Architect resumed after a restart), logged and said
 * once by one path: a revision's {@code REMOVAL_FINISHED} (kind {@code group}, its stage and {@code revisionId}) and the growth sweep both call
 * {@link #finish}.
 * <ul>
 * <li>The record: the growth's completion ({@link Undo#completeNow}), appended once its revision reads UNDONE and nothing of Steward's owns it
 * ({@link VillageSweep#reconcileDue}).</li>
 * <li>The words ("Undone", then what came back) follow only an append this call made, so a replayed event, a second stage's event or the sweep after the
 * event says nothing more. A crash between the append and the words loses the words, never repeats them.</li>
 * <li>What came back is Architect's, from what was removed: each of the revision's stages' immutable snapshot ({@code Sites.removalResult("stage",
 * "<group>/<stage>")}; the event's own result for its stage), summed; the refund, and of the owed parts only the owner's refund kinds
 * ({@link OwedParts}). A stage with no snapshot (it took nothing down, or was evicted) adds nothing.</li>
 * </ul>
 * Server thread.
 */
public final class RevisionEnds {
	private RevisionEnds() {
	}

	/** A revision stage's event: every settlement whose growth that revision is, logged and said once its revision reads UNDONE ({@link #finish}). */
	static void revisionEnded(@Nullable MinecraftServer server, RemovalEnds.RemovalEnd e, BiConsumer<String, String> say) {
		if (!"group".equals(e.kind()) || e.revisionId() == null || e.id() == null) return;
		for (Settlement s : List.copyOf(Settlements.store().all())) {
			try {
				if (Undo.busy(s.id()) || BusyRetries.settlementBusy(s.id())) continue;
				for (String id : VillageSweep.growable(TownRegion.of(s).orElse(null), s.lands().districts())) {
					var town = ArchitectAccess.api().regions().get(id);
					if (town.isEmpty() || !e.id().equals(town.get().groupId())) continue;
					var rv = Growths.revision(town.get(), e.revisionId());
					String tag = Growths.tag(id, e.revisionId());
					var named = Undo.headEntry(s, tag);
					if (rv.isEmpty() || named.isEmpty() || !VillageSweep.reconcileDue(s, tag, rv.get().state(), false)) continue;
					finish(server, s, town.get(), rv.get(), named.get(), e, say);
				}
			} catch (RuntimeException | LinkageError x) {
				Steward.LOGGER.warn("{}: a growth's removal finished, but could not be logged (the sweep looks again)", s.id(), x);
			}
		}
	}

	/**
	 * Logs {@code entry}'s growth (its revision {@code rv} of {@code town}) taken up, once; when this call appended it, says so and what came back. {@code e}:
	 * the event that triggered it (its stage's own result counts for that stage), or null (the sweep). Returns what the append did.
	 */
	static Undo.Completion finish(@Nullable MinecraftServer server, Settlement s, RegionView town, RevisionView rv, Settlement.LogEntry entry,
		RemovalEnds.@Nullable RemovalEnd e, BiConsumer<String, String> say) {
		var done = Undo.completeNow(s.id(), entry, new Growths.Ref(town.id(), rv.rid()), Map.of());
		if (done != Undo.Completion.LOGGED_NOW) return done;
		Steward.LOGGER.info("{}: growth {} finished by itself: its removal logged", s.id(), Growths.tag(town.id(), rv.rid()));
		MaterialRequests.forgetIfBuilt(town.id());
		say.accept(s.id(), "Undone: \"" + entry.text() + "\" finished by itself, the land restored.");
		String line = refundLine(s.owner(), town.groupId(), rv.stages(), e, (kind, id) -> {
			try {
				return ArchitectAccess.api().sites(server).removalResult(kind, id);
			} catch (RuntimeException | LinkageError x) {
				return null;
			}
		});
		if (line != null) say.accept(s.id(), line);
		return done;
	}

	/** One stage's result as the sum reads it: its refund and its owed parts (any item key: tests use names). */
	record Part<T>(Map<T, Integer> refund, List<OwedParts.Part<T>> owed) {
	}

	/** What came back from a revision's take-up, summed over its stages: the refund, and the owed parts (each settlement says only its own). */
	record Back<T>(Map<T, Integer> refund, List<OwedParts.Part<T>> owed) {
	}

	/**
	 * The revision's {@code stages}' results summed, each stage once: {@code eventPart} for {@code eventStage} (the event's own result), each other stage's
	 * snapshot ({@code snapshot} of {@code <group>/<stage>}); a stage with neither adds nothing. Pure over {@code snapshot}.
	 */
	static <T> Back<T> back(@Nullable List<String> stages, @Nullable String eventStage, @Nullable Part<T> eventPart, java.util.function.Function<String, @Nullable Part<T>> snapshot) {
		Map<T, Integer> refund = new LinkedHashMap<>();
		List<OwedParts.Part<T>> owed = new ArrayList<>();
		if (stages == null) return new Back<>(refund, owed);
		for (String st : new java.util.LinkedHashSet<>(stages)) {
			if (st == null) continue;
			Part<T> p = eventPart != null && st.equals(eventStage) ? eventPart : snapshot.apply(st);
			if (p == null) continue;
			if (p.refund() != null) p.refund().forEach((item, n) -> { if (n != null && n > 0) refund.merge(item, n, Integer::sum); });
			if (p.owed() != null) p.owed().stream().filter(java.util.Objects::nonNull).forEach(owed::add);
		}
		return new Back<>(refund, owed);
	}

	/** {@link #back}'s line for {@code owner}: what came back, and what is owed back by {@link OwedParts}' rule, apart; null when nothing came back. Pure. */
	static <T> @Nullable String refundLine(String owner, Back<T> b, java.util.function.Function<Map<T, Integer>, String> items) {
		Map<T, Integer> owed = OwedParts.sum(owner, b.owed());
		if (b.refund().isEmpty() && owed.isEmpty()) return null;
		return Growths.refundLine(b.refund(), owed, items);
	}

	/** Architect's stage result as a {@link Part}. */
	static Part<Item> part(Map<Item, Integer> refund, @Nullable List<OwedPart> parts) {
		List<OwedParts.Part<Item>> owed = parts == null ? List.of()
			: parts.stream().filter(java.util.Objects::nonNull).map(o -> new OwedParts.Part<>(o.recipient(), o.reason(), o.items())).toList();
		return new Part<>(refund == null ? Map.of() : refund, owed);
	}

	/** The line {@link #finish} says: the revision's stages summed, the event's stage from the event; null when nothing came back. */
	static @Nullable String refundLine(String owner, @Nullable String group, @Nullable List<String> stages, RemovalEnds.@Nullable RemovalEnd e,
		BiFunction<String, String, @Nullable RemoveResult> snapshot) {
		if (group == null) return null;
		boolean own = e != null && group.equals(e.id());
		Back<Item> b = back(stages, own ? e.stage() : null, own ? part(e.refund(), e.owed()) : null, st -> {
			RemoveResult r = snapshot.apply("stage", group + "/" + st);
			if (r == null) return null;
			List<OwedPart> parts;
			try {
				parts = r.owedParts();
			} catch (LinkageError x) {
				parts = List.of();
			}
			return part(r.refund(), parts);
		});
		return refundLine(owner, b, m -> Stockpiles.items(m.entrySet().stream().map(x -> Map.entry(x.getKey(), x.getValue())).toList(), 8));
	}
}
