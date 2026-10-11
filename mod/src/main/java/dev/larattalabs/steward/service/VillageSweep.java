package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.architect.api.RemoveOptions;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.BatchPlanner;
import dev.larattalabs.steward.gateway.VPlan;
import dev.larattalabs.steward.model.Settlement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/**
 * Flat villages (Architect V) no build and no history entry holds: a village realised for a build that was cancelled twice before Architect answered, or
 * one whose history could not be saved. At each world start, after the builds resume, each is logged under its region and taken up as an undo would; one
 * that cannot be taken up keeps its entry (undo reaches it), and one whose entry could not be saved is tried again at the next start. Server thread.
 */
public final class VillageSweep {
	/** The build tag of the dev spike's regions (taken up by {@code /steward dev v_spike all remove}, not here). */
	private static final String SPIKE = "spike";

	private VillageSweep() {
	}

	public static void sweep(MinecraftServer server) {
		var regions = ArchitectApi.get().regions();
		for (Settlement s : Settlements.store().all()) {
			for (var v : regions.list(s.owner())) {
				if (v.state() == RegionState.REMOVED) continue;
				String build = v.ext() != null && v.ext().has(BatchPlanner.BUILD_EXT) && v.ext().get(BatchPlanner.BUILD_EXT).isJsonPrimitive()
					? v.ext().get(BatchPlanner.BUILD_EXT).getAsString() : null;
				// not Steward's build, the dev spike, a build that holds it, or the history (undo takes it up)
				if (build == null || SPIKE.equals(build) || SettlementRunner.running(build) || entryOf(s, v.id()).isPresent()) continue;
				Steward.LOGGER.warn("village region {} of {} (build {}) is held by no build and no history entry: taking it up", v.id(), s.id(), build);
				takeUp(server, s, v.id(), m -> Steward.LOGGER.info("{}: {}", s.id(), m));
			}
		}
	}

	/**
	 * Takes up a village region of {@code s}: logged under its region first (a restart, or undo, finds it by that entry), then removed as an undo would.
	 * Without an entry (nothing of it shows, or the history could not be saved) its removal is asked for as it is; the next world start looks again.
	 */
	static void takeUp(MinecraftServer server, Settlement s, String regionId, Consumer<String> say) {
		var entry = entryOf(s, regionId);
		if (entry.isEmpty()) {
			List<String> ids = sitesOf(server, s.owner(), regionId);
			var sites = ArchitectApi.get().sites(server);
			if (!ids.isEmpty()) {
				List<Settlement.SiteChange> changes = ids.stream().map(id -> new Settlement.SiteChange(id, sites.get(id).map(VillageSweep::role).orElse(null), 0,
					sites.get(id).map(SiteView::version).orElse(1))).toList();
				var placed = Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.PROJECT_PLACED, "A village placed after its build ended (being taken up)",
					changes, VPlan.REGION_PREFIX + regionId, Settlement.Outcome.PARTIAL);
				if (Settlements.log(s.id(), placed).ok()) entry = Settlements.store().get(s.id()).flatMap(x -> entryOf(x, regionId));
			}
		}
		if (entry.isEmpty()) {
			ArchitectApi.get().regions().remove(regionId, new RemoveOptions(false, s.owner())).whenComplete((r, err) -> {
				if (err != null || !r.removed()) Steward.LOGGER.warn("village region {} of {} could not be taken up yet (tried again at the next start): {}", regionId, s.id(),
					err != null ? err.toString() : r.blockers());
			});
			return;
		}
		Undo.region(server, Settlements.store().get(s.id()).orElse(s), entry.get(), regionId, Map.of(), say, ok -> { });
	}

	/** The history entry that placed this region (its newest). */
	static Optional<Settlement.LogEntry> entryOf(Settlement s, String regionId) {
		String group = VPlan.REGION_PREFIX + regionId;
		var log = s.log();
		for (int i = log.size() - 1; i >= 0; i--) {
			var e = log.get(i);
			if (e.kind() == Settlement.Kind.PROJECT_PLACED && group.equals(e.siteGroup())) return Optional.of(e);
		}
		return Optional.empty();
	}

	/** A region's sites as Steward records them: its streets and buildings, or (when it left nothing else) the ground tiles it wrote. */
	static List<String> sitesOf(MinecraftServer server, String owner, String regionId) {
		var sites = ArchitectApi.get().sites(server);
		List<String> shown = sites.list(owner).stream().filter(x -> regionId.equals(x.region())).map(SiteView::id).toList();
		return !shown.isEmpty() ? shown : sites.list(owner, true).stream().filter(x -> regionId.equals(x.region())).map(SiteView::id).toList();
	}

	private static String role(SiteView v) {
		return v.ext() != null && v.ext().has("steward_mc:role") ? v.ext().get("steward_mc:role").getAsString() : null;
	}
}
