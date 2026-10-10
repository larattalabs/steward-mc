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

	/** Starts the undo; {@code say} gets progress and the result. Returns false (having said why) when there is nothing to undo. */
	public static boolean lastProject(MinecraftServer server, Settlement s, Consumer<String> say) {
		var entry = s.lastUndoable();
		if (entry.isEmpty()) {
			say.accept("Nothing to undo for " + s.name() + ".");
			return false;
		}
		List<String> ids = new ArrayList<>(entry.get().siteIds());
		java.util.Collections.reverse(ids); // newest first
		say.accept("Removing " + ids.size() + " sites of \"" + entry.get().text() + "\" and restoring the land...");
		var sites = ArchitectApi.get().sites(server);
		RemoveOptions as = new RemoveOptions(false, s.owner());
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
			if (!removed.isEmpty()) Settlements.log(s.id(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.PROJECT_REMOVED,
				"Undid " + removed.size() + " sites of \"" + entry.get().text() + "\"", List.copyOf(removed)));
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
