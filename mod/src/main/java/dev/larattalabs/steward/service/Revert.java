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

	/** The version a site stood at before the one it stands at now (its history is oldest first), or 0 when it was placed at this one. */
	public static int previousVersion(Sites sites, String siteId) {
		List<SiteVersion> h = sites.history(siteId);
		return h.size() < 2 ? 0 : h.get(h.size() - 2).version();
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
			Settlements.log(s.id(), Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.REVERTED, msg, List.of(new Settlement.SiteChange(siteId, lot, from,
				ok ? r.toVersion() : toVersion)), null, ok ? Settlement.Outcome.DONE : Settlement.Outcome.FAILED));
			if (ok) sites.get(siteId).ifPresent(v -> StewardMotion.visit(server, s, v.box()));
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
