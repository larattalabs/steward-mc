package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.SiteVersion;
import dev.larattalabs.architect.api.Sites;
import dev.larattalabs.steward.model.Settlement;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Takes one building back to an earlier version of its design (docs/PLAN.md phase 3c) through Architect's {@code Sites.revert}: in creative a journal undo
 * where it can, else a forward delta; in survival always a paid forward delta. The player's edits are kept. Logged as a REVERTED operation; the version it
 * left is not offered as an update again ({@link Settlement#revertedFrom}). Server thread.
 */
public final class Revert {
	private Revert() {
	}

	/**
	 * The version "back" goes to: the newest version older than the one it stands at, among those it has stood at (Architect's history, oldest first; a
	 * revert appends the version it went to, so 3 -> 2 reads [1, 2, 3, 2] or [1, 2, 2] and back is 1). 0 when it never stood at an older one.
	 */
	public static int previousVersion(Sites sites, String siteId) {
		return previousVersion(sites.history(siteId).stream().map(SiteVersion::version).toList());
	}

	static int previousVersion(List<Integer> history) {
		if (history.isEmpty()) return 0;
		int now = history.get(history.size() - 1);
		return history.stream().filter(v -> v < now).max(Integer::compare).orElse(0);
	}

	/** Starts the revert; the result comes as a chat line and a refreshed building panel. Returns what to tell the player now. */
	public static Actions.Result start(MinecraftServer server, Settlement s, String siteId, int toVersion, UUID player) {
		Sites sites = ArchitectApi.get().sites(server);
		var site = sites.get(siteId);
		if (site.isEmpty() || !s.owner().equals(site.get().owner())) return Actions.Result.fail("No building " + siteId + " in " + s.name() + ".");
		int from = site.get().version();
		if (toVersion < 1 || toVersion == from) return Actions.Result.fail("It stands at version " + from + " already.");
		String lot = Updates.lotOf(site.get());
		int session = Session.current();
		StewardMotion.clear(server, s, site.get().box());
		sites.revert(siteId, toVersion, null).whenComplete((r, err) -> {
			if (!Session.is(session)) return;
			boolean ok = err == null && r.applied();
			String why = err != null ? (err.getCause() != null ? err.getCause().getMessage() : err.getMessage())
				: r.refusals().stream().map(x -> x.message()).reduce((a, b) -> a + "; " + b).orElse("refused");
			String msg = ok ? "Reverted " + lot + " to version " + r.toVersion() + " (" + r.written() + " blocks" + (r.kept().isEmpty() ? "" : ", " + r.kept().size()
				+ " of your edits kept") + ")." : "Could not revert " + lot + ": " + why;
			var logged = Settlements.log(s.id(), Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.REVERTED, msg, List.of(new Settlement.SiteChange(siteId, lot,
				ok ? r.fromVersion() : from, ok ? r.toVersion() : toVersion)), null, ok ? Settlement.Outcome.DONE : Settlement.Outcome.FAILED));
			if (ok) {
				// the version it left is not offered again even if the history could not be saved (this session; the saved hold covers the next)
				Updates.hold(siteId, from);
				if (!logged.ok()) msg += " (Its history could not be saved: " + logged.error() + ")";
			}
			if (ok) sites.get(siteId).ifPresent(v -> StewardMotion.visit(server, s, v.box()));
			StewardVoice.say(server, s.id(), msg);
			var p = server.getPlayerList().getPlayer(player);
			if (p != null) {
				p.sendSystemMessage(Component.literal("Steward (" + s.name() + "): " + msg));
				Settlements.store().get(s.id()).ifPresent(x -> Actions.sendSettlement(p, x, siteId));
			}
			// the update offers change: the version it left is held back
			Updates.rescan();
		});
		return Actions.Result.ok("Reverting " + lot + " to version " + toVersion + "...");
	}
}
