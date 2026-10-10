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
	public static boolean lastProject(MinecraftServer server, Settlement s, Consumer<String> say) {
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
		// a whole project is one site group: removeGroup does the newest-first removal over ticks (stages, survival refunds, the group's bookkeeping), the
		// path Architect's gates prove exact. Only when the group holds nothing but this project's sites; else (a partial undo) site by site.
		String group = entry.get().siteGroup();
		var g = group == null ? java.util.Optional.<dev.larattalabs.architect.api.SiteGroup>empty() : sites.group(group);
		if (g.isPresent() && !g.get().sites().isEmpty() && entry.get().siteIds().containsAll(g.get().sites())) {
			List<String> standing = List.copyOf(g.get().sites());
			sites.removeGroup(group, as).whenComplete((r, err) -> {
				List<String> gone = err == null && r.removed() ? standing : standing.stream().filter(id -> sites.get(id).isEmpty()).toList();
				if (!gone.isEmpty() && !Settlements.log(s.id(), removed(entry.get(), gone, gone.size() == standing.size(), now)).ok()) say.accept(UNLOGGED);
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
		chain.whenComplete((stop, err) -> {
			if (!removed.isEmpty() && !Settlements.log(s.id(), removed(entry.get(), List.copyOf(removed), removed.size() == ids.size(), now)).ok()) say.accept(UNLOGGED);
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

	private static String blockedText(String siteId, RemoveResult r) {
		return "site " + siteId + " is blocked by " + (r.blockers().isEmpty() ? "something in its box" : String.join(", ", r.blockers())) + ".";
	}
}
