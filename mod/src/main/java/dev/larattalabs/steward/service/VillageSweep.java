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
		// adoptions a crash or a lost answer left open are closed first (docs/GROWTH.md, slice B)
		for (Settlement s : Settlements.store().all()) {
			try {
				if (!Adoption.reconcile(server, s)) Steward.LOGGER.warn("{}: an adoption could not be closed (the history was not saved); tried again at the next start", s.id());
			} catch (RuntimeException | LinkageError e) {
				Steward.LOGGER.warn("{}: could not reconcile its adoption", s.id(), e);
			}
		}
		growths(server);
		for (Settlement s : Settlements.store().all()) {
			for (var v : regions.list(s.owner())) {
				if (v.state() == RegionState.REMOVED) continue;
				// a town Steward took on as a region is never taken up whole (docs/GROWTH.md, slice B)
				if (v.ext() != null && v.ext().has(Adoption.KEY_EXT)) continue;
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
	 * (docs/GROWTH.md, slice A2) Growths of a settlement's town that no build holds and no history entry names: realised for a build that ended first (an
	 * orphan whose revision was still being placed), or whose entry could not be saved. Once the revision has ended, it is logged under its tag (its sites,
	 * all known by then) and taken up as an undo would: one still built from the stockpile keeps its entry, so undo takes it up once it is built. At each
	 * world start and every few seconds (MaterialRequests' tick). A revision is Steward's when its sites carry the settlement's tag; one whose build (the
	 * sites' build tag) still holds it is left to that build.
	 */
	public static void growths(MinecraftServer server) {
		var regions = ArchitectApi.get().regions();
		var sites = ArchitectApi.get().sites(server);
		for (Settlement s : Settlements.store().all()) {
			try {
				var town = TownRegion.of(s).flatMap(regions::get);
				if (town.isEmpty() || town.get().revisions() == null || Undo.busy(s.id())) continue;
				for (var r : town.get().revisions()) {
					if (!Growths.ended(r.state()) || r.sites().isEmpty()) continue;
					String tag = Growths.tag(town.get().id(), r.rid());
					if (s.log().stream().anyMatch(e -> e.kind() == Settlement.Kind.PROJECT_PLACED && tag.equals(e.siteGroup()))) continue;
					var first = sites.get(r.sites().get(0));
					String settlement = first.map(x -> ext(x, "steward_mc:settlement")).orElse(null);
					String build = first.map(x -> ext(x, BatchPlanner.BUILD_EXT)).orElse(null);
					// a build's id (b<start time>), not one a dev call made up (the e2e's own revisions)
					if (!s.id().equals(settlement) || build == null || !build.matches("b\\d+") || SettlementRunner.holds(build)) continue;
					Steward.LOGGER.warn("growth {} of {} (build {}) is held by no build and no history entry: logged and taken up", tag, s.id(), build);
					List<Settlement.SiteChange> changes = r.sites().stream().map(id -> new Settlement.SiteChange(id, sites.get(id).map(VillageSweep::role).orElse(null), 0,
						sites.get(id).map(SiteView::version).orElse(1))).toList();
					var placed = Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.PROJECT_PLACED, "An addition placed after its build ended",
						changes, tag, Settlement.Outcome.PARTIAL);
					if (!Settlements.log(s.id(), placed).ok()) { Steward.LOGGER.warn("{}: growth {} could not be logged; tried again", s.id(), tag); continue; }
					var saved = Settlements.store().get(s.id()).orElse(s);
					var entry = saved.log().get(saved.log().size() - 1);
					var ref = new Growths.Ref(town.get().id(), r.rid());
					if (Growths.fromMaterials(town.get())) MaterialRequests.watchBuild(s.id(), town.get().id());
					Undo.growth(server, saved, entry, ref, Map.of(), m -> Steward.LOGGER.info("{}: {}", s.id(), m), ok -> { });
				}
			} catch (RuntimeException | LinkageError e) {
				Steward.LOGGER.warn("{}: could not look for growths without a build", s.id(), e);
			}
		}
	}

	private static String ext(SiteView v, String key) {
		return v.ext() != null && v.ext().has(key) && v.ext().get(key).isJsonPrimitive() ? v.ext().get(key).getAsString() : null;
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
