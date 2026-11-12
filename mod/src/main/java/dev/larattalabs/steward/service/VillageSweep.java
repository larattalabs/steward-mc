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

	/** A history note naming a growth whose build ended while it was still being placed: it is logged or taken up once it has ended (its tag on the note). */
	static final String ORPHAN = "An addition realised after its build ended is still being placed";
	/** A history note closing an {@link #ORPHAN} note that left nothing to log (taken up, or standing with nothing placed). */
	static final String ORPHAN_CLOSED = "An addition realised after its build ended left nothing standing";
	/** Growths being taken up by the sweep (not asked twice while one is in flight). */
	private static final java.util.Set<String> TAKING = new java.util.HashSet<>();

	/**
	 * (docs/GROWTH.md, slice A2) Growths of a settlement's town that no build holds and no history entry names, at each world start, every 10 s
	 * (MaterialRequests' tick), and before an addition chooses its path: realised for a build that ended first, or whose entry could not be saved.
	 * A revision is the settlement's when an {@link #ORPHAN} note names it (the build that ended said so), or when any of its standing sites carries the
	 * settlement's tag and a build id ({@code b<time>}) no live build holds. Once it has ended: with sites, logged under its tag and taken up as an undo
	 * would (one still built from the stockpile keeps its entry); with none, taken up (an empty revision still holds the town's history: an adoption
	 * can't be let go above it), then the note closed. Durable: the notes and the history are the state; a failed save is tried again.
	 */
	public static void growths(MinecraftServer server) {
		for (Settlement s : Settlements.store().all()) growths(server, s);
	}

	public static void growths(MinecraftServer server, Settlement s) {
		var regions = ArchitectApi.get().regions();
		var sites = ArchitectApi.get().sites(server);
		try {
			var town = TownRegion.of(s).flatMap(regions::get);
			if (town.isEmpty() || town.get().revisions() == null || Undo.busy(s.id())) return;
			for (var r : town.get().revisions()) {
				String tag = Growths.tag(town.get().id(), r.rid());
				if (TAKING.contains(tag) || s.log().stream().anyMatch(e -> e.kind() == Settlement.Kind.PROJECT_PLACED && tag.equals(e.siteGroup()))) continue;
				boolean noted = orphanOpen(s, tag);
				String st = r.state() == null ? "" : r.state().toUpperCase(java.util.Locale.ROOT);
				if (st.equals("UNDONE") || st.equals("STALE")) {
					if (noted) close(s, tag, "it was taken up");
					continue;
				}
				if (!Growths.ended(st)) continue;
				var ref = new Growths.Ref(town.get().id(), r.rid());
				if (r.sites().isEmpty()) {
					if (!noted) continue;
					TAKING.add(tag);
					Growths.undo(server, s.owner(), ref, m -> Steward.LOGGER.info("{}: {}", s.id(), m), ok -> {
						TAKING.remove(tag);
						if (ok == null) return;
						var now = Settlements.store().get(s.id()).orElse(s);
						if (ok) { close(now, tag, "taken up"); return; }
						var d = Growths.decide(ArchitectApi.get().regions().get(ref.region()), ref.rid());
						if (d.step() == Growths.Step.NEWER || d.step() == Growths.Step.UNKNOWN) close(now, tag, "it stands with nothing placed: " + d.why());
					});
					continue;
				}
				if (!noted && !tagged(s, r, sites)) continue;
				Steward.LOGGER.warn("growth {} of {} is held by no build and no history entry: logged and taken up", tag, s.id());
				List<Settlement.SiteChange> changes = r.sites().stream().map(id -> new Settlement.SiteChange(id, sites.get(id).map(VillageSweep::role).orElse(null), 0,
					sites.get(id).map(SiteView::version).orElse(1))).toList();
				var placed = Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.PROJECT_PLACED, "An addition placed after its build ended",
					changes, tag, Settlement.Outcome.PARTIAL);
				if (!Settlements.log(s.id(), placed).ok()) { Steward.LOGGER.warn("{}: growth {} could not be logged; tried again", s.id(), tag); continue; }
				var saved = Settlements.store().get(s.id()).orElse(s);
				var entry = saved.log().get(saved.log().size() - 1);
				if (Growths.fromMaterials(town.get())) MaterialRequests.watchBuild(s.id(), town.get().id());
				TAKING.add(tag);
				Undo.growth(server, saved, entry, ref, Map.of(), m -> Steward.LOGGER.info("{}: {}", s.id(), m), ok -> TAKING.remove(tag));
			}
		} catch (RuntimeException | LinkageError e) {
			Steward.LOGGER.warn("{}: could not look for growths without a build", s.id(), e);
		}
	}

	/** Whether a standing site of the revision carries the settlement's tag and a build id no live build holds (any of its sites: one may be gone). */
	private static boolean tagged(Settlement s, dev.larattalabs.architect.api.RevisionView r, dev.larattalabs.architect.api.Sites sites) {
		for (String id : r.sites()) {
			var v = sites.get(id);
			if (v.isEmpty()) continue;
			String settlement = ext(v.get(), "steward_mc:settlement");
			String build = ext(v.get(), BatchPlanner.BUILD_EXT);
			if (settlement == null && build == null) continue;
			// a build's id (b<start time>), not one a dev call made up (the e2e's own revisions)
			return s.id().equals(settlement) && build != null && build.matches("b\\d+") && !SettlementRunner.holds(build);
		}
		return false;
	}

	/** Whether an {@link #ORPHAN} note names the growth and no {@link #ORPHAN_CLOSED} note after it does. Pure. */
	static boolean orphanOpen(Settlement s, String tag) {
		boolean open = false;
		for (var e : s.log()) {
			if (e.kind() != Settlement.Kind.NOTE || !tag.equals(e.siteGroup())) continue;
			if (e.text().startsWith(ORPHAN_CLOSED)) open = false;
			else if (e.text().startsWith(ORPHAN)) open = true;
		}
		return open;
	}

	/** The note a growth whose build ended while it was placed leaves, so the sweep finds it whatever its sites say. */
	static Settlement.LogEntry orphanNote(String tag) {
		return new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, ORPHAN + " (" + tag + "): logged and taken up once it has ended", List.of(), tag);
	}

	private static void close(Settlement s, String tag, String why) {
		Settlements.log(s.id(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, ORPHAN_CLOSED + " (" + tag + "): " + why, List.of(), tag));
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
