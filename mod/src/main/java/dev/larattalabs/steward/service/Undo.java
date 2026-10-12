package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.RemoveOptions;
import dev.larattalabs.architect.api.RemoveResult;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/**
 * Undoes a settlement's newest placed project: removes its sites newest first through Architect (which restores the terrain exactly, journal-backed), and logs
 * what was removed. Stops at the first site Architect refuses (the player's things in its box are never destroyed); what was removed stays removed and
 * the rest stays undoable. Server thread (Architect's futures complete there).
 */
public final class Undo {
	private Undo() {
	}

	/** Settlements an undo is running for: they count as busy ({@link SettlementRunner#busy}). */
	private static final java.util.Set<String> UNDOING = new java.util.HashSet<>();

	static {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> UNDOING.clear());
	}

	public static boolean busy(String settlementId) {
		return UNDOING.contains(settlementId);
	}

	/** Said when the undo happened but its history could not be saved. */
	static final String UNLOGGED = "The settlement's history could not be saved: the undo happened, but its record is missing (see the log).";

	/** The operation an undo logs: each removed site from the version it stood at ({@code now}; the placing entry's when unknown) to 0. */
	private static Settlement.LogEntry removed(Settlement.LogEntry placed, List<String> gone, boolean whole, java.util.Map<String, Integer> now) {
		java.util.Map<String, Settlement.SiteChange> before = new java.util.HashMap<>();
		for (Settlement.SiteChange c : placed.changes()) before.put(c.siteId(), c);
		List<Settlement.SiteChange> changes = gone.stream().map(id -> {
			var b = before.get(id);
			int from = now.getOrDefault(id, b == null ? -1 : b.to());
			return new Settlement.SiteChange(id, b == null ? null : b.lot(), from, 0);
		}).toList();
		return Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.PROJECT_REMOVED, "Undid " + gone.size() + " sites of \"" + placed.text() + "\"" + (placed.op() == null
			? "" : " (" + placed.op() + ")"), changes, null, whole ? Settlement.Outcome.DONE : Settlement.Outcome.PARTIAL);
	}

	/** Starts the undo; {@code say} gets progress and the result. Returns false (having said why) when there is nothing to undo. */
	public static boolean lastProject(MinecraftServer server, Settlement s, Consumer<String> sayTo) {
		// a refund that did not fit in the stockpile drops beside its crate: said with the undo's result, so it is picked up before it despawns
		int overflowBefore = overflow(server, s);
		Consumer<String> say = line -> {
			sayTo.accept(line);
			if (!line.startsWith("Undone")) return;
			int more = overflow(server, s) - overflowBefore;
			var sp = Stockpiles.of(server, s);
			if (more > 0 && sp.isPresent() && !sp.get().crates().isEmpty()) {
				var c = sp.get().crates().get(0);
				sayTo.accept(more + " refunded items did not fit in the stockpile and lie beside its crate at " + c.getX() + ", " + c.getY() + ", " + c.getZ()
					+ ": pick them up, or add a crate (/steward stockpile " + s.id() + " add) and put them in.");
			}
		};
		var entry = s.lastUndoable();
		if (entry.isEmpty()) {
			say.accept("Nothing to undo for " + s.name() + ".");
			return false;
		}
		List<String> ids = new ArrayList<>(entry.get().siteIds());
		// the steward never stands in what is removed (a named mob in a box stops Architect)
		var all = ArchitectApi.get().sites(server);
		for (String id : ids) all.get(id).ifPresent(v -> StewardMotion.clear(server, s, v.box()));
		// the versions they stand at now (updates may have moved them since they were placed): what the undo log records as "from"
		java.util.Map<String, Integer> now = new java.util.HashMap<>();
		for (String id : ids) all.get(id).ifPresent(v -> now.put(id, v.version()));
		java.util.Collections.reverse(ids); // newest first
		say.accept("Removing " + ids.size() + " sites of \"" + entry.get().text() + "\" and restoring the land...");
		var sites = ArchitectApi.get().sites(server);
		RemoveOptions as = new RemoveOptions(false, s.owner());
		UNDOING.add(s.id());
		// a whole project is one site group: removeGroup does the newest-first removal over ticks (stages, survival refunds, the group's bookkeeping), the
		// path Architect's gates prove exact. Only when the group holds nothing but this project's sites; else (a partial undo) site by site.
		// a flat village (Architect V) is one region: removed whole, its ground, street, lanes, square and props with its buildings
		String region = dev.larattalabs.steward.gateway.VPlan.regionOf(entry.get().siteGroup());
		if (region != null) {
			// the world closing mid-undo (null) is not its outcome: the undo can be run again after the restart
			return region(server, s, entry.get(), region, now, say, ok -> UNDOING.remove(s.id()));
		}
		String group = entry.get().siteGroup();
		var g = group == null ? java.util.Optional.<dev.larattalabs.architect.api.SiteGroup>empty() : sites.group(group);
		if (g.isPresent() && !g.get().sites().isEmpty() && entry.get().siteIds().containsAll(g.get().sites())) {
			List<String> standing = List.copyOf(g.get().sites());
			int session = Session.current();
			sites.removeGroup(group, as).whenComplete((r, err) -> {
				// the world closing mid-undo is not its outcome: what Architect finished is read again after the restart (the undo can be run again)
				if (!Session.is(session) || SettlementRunner.worldStopped(err)) { UNDOING.remove(s.id()); return; }
				List<String> gone = err == null && r.removed() ? standing : standing.stream().filter(id -> sites.get(id).isEmpty()).toList();
				if (!gone.isEmpty() && !Settlements.log(s.id(), removed(entry.get(), gone, gone.size() == standing.size(), now)).ok()) say.accept(UNLOGGED);
				UNDOING.remove(s.id());
			districts(server, s, say);
				if (err != null) say.accept("Undo stopped after " + gone.size() + " of " + standing.size() + " sites: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()));
				else if (!r.removed()) say.accept("Undo stopped after " + gone.size() + " of " + standing.size() + " sites: blocked by "
					+ (r.blockers().isEmpty() ? "something in a box" : String.join(", ", r.blockers())) + ". Clear it and run /steward undo " + s.id() + " again.");
				else say.accept("Undone: removed " + gone.size() + " sites, the land is restored.");
			});
			return true;
		}
		List<String> removed = new ArrayList<>();
		CompletableFuture<String> chain = CompletableFuture.completedFuture(null);
		for (String id : ids) {
			chain = chain.thenCompose(stop -> {
				if (stop != null) return CompletableFuture.completedFuture(stop);
				// a site removed by hand meanwhile is simply gone
				if (sites.get(id).isEmpty()) {
					removed.add(id);
					return CompletableFuture.completedFuture(null);
				}
				return sites.remove(id, as).thenApply(r -> {
					if (r.removed()) {
						removed.add(id);
						return null;
					}
					return blockedText(id, r);
				});
			});
		}
		int session = Session.current();
		chain.whenComplete((stop, err) -> {
			if (!Session.is(session) || SettlementRunner.worldStopped(err)) { UNDOING.remove(s.id()); return; }
			if (!removed.isEmpty() && !Settlements.log(s.id(), removed(entry.get(), List.copyOf(removed), removed.size() == ids.size(), now)).ok()) say.accept(UNLOGGED);
			UNDOING.remove(s.id());
			districts(server, s, say);
			if (err != null) {
				Steward.LOGGER.warn("undo of {} failed", s.id(), err);
				say.accept("Undo stopped after " + removed.size() + " of " + ids.size() + " sites: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()));
			} else if (stop != null) {
				say.accept("Undo stopped after " + removed.size() + " of " + ids.size() + " sites: " + stop + " Clear it and run /steward undo " + s.id() + " again.");
			} else {
				say.accept("Undone: removed " + removed.size() + " sites, the land is restored.");
			}
		});
		return true;
	}

	/** A district whose road an undo took, with nothing else standing in it, is given up. */
	/**
	 * Takes up a flat village (Architect V) whole, its ground, street, lanes, square and props with its buildings, for the history entry that placed it: an
	 * undo, and a cancel (which logs the village first, so a restart finds it by this entry). {@code done} gets true when it is taken up and the entry
	 * retired, false when it stopped (the entry stays as it was, region and all, so it can be run again), null when the world closed meanwhile.
	 * Returns false (having said why) when it is refused before anything is asked.
	 */
	public static boolean region(MinecraftServer server, Settlement s, Settlement.LogEntry entry, String region, java.util.Map<String, Integer> now,
		Consumer<String> say, Consumer<Boolean> whenDone) {
		// the settlement is busy while its land is rewritten (no build surveys it half restored), whoever asked: undo, a cancel, the sweep
		UNDOING.add(s.id());
		Consumer<Boolean> done = ok -> {
			UNDOING.remove(s.id());
			whenDone.accept(ok);
		};
		var regions = ArchitectApi.get().regions();
		List<String> regionSites = List.copyOf(entry.siteIds());
		// removed already (Architect's tombstone, 1.14 C22): its answer, or the save of it, was lost; the entry is retired now
		if (regions.get(region).filter(v -> v.state() == dev.larattalabs.architect.api.RegionState.REMOVED).isPresent()) {
			if (!Settlements.log(s.id(), removed(entry, regionSites, true, now)).ok()) say.accept(UNLOGGED);
			districts(server, s, say);
			say.accept("Undone: the village was taken up already, the land restored.");
			done.accept(true);
			return true;
		}
		// removed whole: only when everything in it is this entry's (a site another build put in it would go too)
		var others = ArchitectApi.get().sites(server).list(s.owner()).stream().filter(v -> region.equals(v.region())).map(dev.larattalabs.architect.api.SiteView::id)
			.filter(id -> !regionSites.contains(id)).toList();
		if (!others.isEmpty()) {
			say.accept("Undo stopped: the village also holds " + String.join(", ", others) + ", which \"" + entry.text() + "\" did not place.");
			done.accept(false);
			return false;
		}
		int session = Session.current();
		regions.remove(region, new RemoveOptions(false, s.owner())).whenComplete((r, err) -> server.execute(() -> {
			if (!Session.is(session) || SettlementRunner.worldStopped(err)) { done.accept(null); return; }
			// taken up only when Architect says so (1.14 C22: a retried removal finishes the restorations it left, or answers not removed)
			boolean ok = err == null && r.removed();
			if (ok && !Settlements.log(s.id(), removed(entry, regionSites, true, now)).ok()) say.accept(UNLOGGED);
			districts(server, s, say);
			if (!ok) say.accept("Undo stopped: " + (err != null ? (err.getCause() != null ? err.getCause().getMessage() : err.getMessage())
				: "blocked by " + String.join(", ", r.blockers())) + ". Clear it and run /steward undo " + s.id() + " again.");
			else say.accept("Undone: the village is taken up, the land restored.");
			done.accept(ok);
		}));
		return true;
	}

	/** The units of refunds that did not fit in the settlement's stockpile so far (0 without one). */
	private static int overflow(MinecraftServer server, Settlement s) {
		return Stockpiles.of(server, s).map(sp -> sp.refundOverflow().values().stream().mapToInt(Integer::intValue).sum()).orElse(0);
	}

	private static void districts(MinecraftServer server, Settlement s, Consumer<String> say) {
		if (s.lands().districts().isEmpty()) return;
		String more = Districts.afterUndo(server, s.id());
		if (!more.isEmpty()) say.accept(more);
	}

	private static String blockedText(String siteId, RemoveResult r) {
		return "site " + siteId + " is blocked by " + (r.blockers().isEmpty() ? "something in its box" : String.join(", ", r.blockers())) + ".";
	}
}
