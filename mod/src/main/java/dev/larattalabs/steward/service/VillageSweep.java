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
		// (slice VSR) one village take-up per settlement at a time: the next starts once the first has answered
		for (Settlement s : Settlements.store().all()) villages(server, s.id());
	}

	/** (slice VSR) A village region as the world-start sweep reads it: Architect's view cut to what it decides on. */
	record Village(String id, boolean removed, boolean removing, boolean adopted, @org.jspecify.annotations.Nullable String build) {
		static Village of(dev.larattalabs.architect.api.RegionView v) {
			String build = v.ext() != null && v.ext().has(BatchPlanner.BUILD_EXT) && v.ext().get(BatchPlanner.BUILD_EXT).isJsonPrimitive()
				? v.ext().get(BatchPlanner.BUILD_EXT).getAsString() : null;
			return new Village(v.id(), v.state() == RegionState.REMOVED, v.state() == RegionState.REMOVING, v.ext() != null && v.ext().has(Adoption.KEY_EXT), build);
		}
	}

	/**
	 * (slice VSR) The regions of the sweep's own take-ups whose entry still stands (not retired): a removal refused, or whose answer a restart lost. Architect's
	 * list leaves out a removed region (its tombstone is read by id), so these are read one by one. Pure.
	 */
	static List<String> standingTakeUps(Settlement s) {
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		for (var e : s.log()) {
			if (e.kind() != Settlement.Kind.PROJECT_PLACED || e.siteGroup() == null || !e.siteGroup().startsWith(VPlan.REGION_PREFIX)) continue;
			String id = e.siteGroup().substring(VPlan.REGION_PREFIX.length());
			var newest = entryOf(s, id);
			if (newest.isPresent() && newest.get().text().startsWith(TAKING_UP) && !Undo.retired(s, newest.get())) out.add(id);
		}
		return List.copyOf(out);
	}

	/** The text of the entry {@link #takeUp} logs for a village no entry held: the sweep's own take-up (a lost answer's is retired at the next start). */
	static final String TAKING_UP = "A village placed after its build ended (being taken up)";

	/**
	 * (slice VSR) The next village region of {@code s} the world-start sweep takes up, if any; {@code tried} the regions asked already this world start
	 * (each once: a refused one is looked at again at the next start). Nothing while a region of the settlement is being removed (a removal Architect resumed
	 * at this start: one in flight at a time). A standing region of Steward's build (its build tag) that no running build and no history entry holds; or the
	 * sweep's own take-up whose entry still stands (refused, or its answer lost at a restart): asked again, or, when Architect removed it already, retired
	 * through its tombstone with nothing asked of Architect. Never a town Steward took on, nor the dev spike. Pure.
	 */
	static Optional<String> nextVillage(Settlement s, List<Village> regions, java.util.function.Predicate<String> running, java.util.Set<String> tried) {
		if (regions.stream().anyMatch(Village::removing)) return Optional.empty();
		for (Village v : regions) {
			if (tried.contains(v.id())) continue;
			var entry = entryOf(s, v.id());
			boolean ours = entry.isPresent() && entry.get().text().startsWith(TAKING_UP) && !Undo.retired(s, entry.get());
			if (v.removed()) {
				if (ours) return Optional.of(v.id());
				continue;
			}
			// a town Steward took on as a region is never taken up whole (docs/GROWTH.md, slice B)
			if (v.adopted()) continue;
			if (ours) return Optional.of(v.id());
			// not Steward's build, the dev spike, a build that holds it, or the history (undo takes it up)
			if (v.build() == null || SPIKE.equals(v.build()) || running.test(v.build()) || entry.isPresent()) continue;
			return Optional.of(v.id());
		}
		return Optional.empty();
	}

	/**
	 * (slice VSR, manager) As {@link #nextVillage}, passing over a region a player stands in ({@code occupied}; {@code held} told of each): it is not
	 * marked tried, so it is chosen again once they have left (the 10 s tick asks again). Pure.
	 */
	static Optional<String> nextFree(Settlement s, List<Village> regions, java.util.function.Predicate<String> running, java.util.Set<String> tried,
		java.util.function.Predicate<String> occupied, Consumer<String> held) {
		java.util.Set<String> skip = new java.util.HashSet<>(tried);
		while (true) {
			var id = nextVillage(s, regions, running, skip);
			if (id.isEmpty() || !occupied.test(id.get())) return id;
			skip.add(id.get());
			held.accept(id.get());
		}
	}

	/** Whether a player stands in the region's claim (one block around it): Architect holds a removal while one does. */
	static boolean playerIn(MinecraftServer server, Settlement s, String regionId) {
		try {
			// a removed one (its tombstone retired with nothing asked of Architect) holds nothing
			var box = ArchitectApi.get().regions().get(regionId).filter(v -> v.state() != RegionState.REMOVED).map(dev.larattalabs.architect.api.RegionView::claim)
				.orElse(null);
			if (box == null) return false;
			List<double[]> at = new java.util.ArrayList<>();
			for (var p : server.getPlayerList().getPlayers()) {
				if (p.level().dimension().identifier().toString().equals(s.claim().dimension())) at.add(new double[] { p.getX(), p.getY(), p.getZ() });
			}
			return inside(new int[] { box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ() }, at);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Whether any of the positions lies in the box (min and max x, y, z, inclusive) or one block around it. Pure. */
	static boolean inside(int[] box, List<double[]> at) {
		for (double[] p : at) {
			if (p[0] >= box[0] - 1 && p[0] < box[3] + 2 && p[1] >= box[1] - 1 && p[1] < box[4] + 2 && p[2] >= box[2] - 1 && p[2] < box[5] + 2) return true;
		}
		return false;
	}

	/** (slice VSR, manager) Settlements with a village passed over because a player stood in it: the 10 s tick asks again. This world's session only. */
	private static final java.util.Set<String> HELD = new java.util.HashSet<>();

	/** Village regions asked of the sweep this world start (each once). This world's session only. */
	private static final java.util.Set<String> TRIED = new java.util.HashSet<>();
	/** Settlements with a village take-up by {@link #takeUp} in flight, whoever asked: one at a time per settlement. This world's session only. */
	private static final java.util.Set<String> REMOVING = new java.util.HashSet<>();

	/** The sweep's village take-ups of one settlement, one after the other: the next is chosen from fresh state once the one before has answered. */
	private static void villages(MinecraftServer server, String settlementId) {
		chain(settlementId, SWEEPING, () -> {
			var s = Settlements.store().get(settlementId);
			// (slice DG review round 2) a settlement whose growth take-up (or an undo) is running is left to it; (slice VSR review 3) so is one with a build
			// running (a restored build's cancel takes its own region up) or a region being removed: its orphans wait for a start where nothing holds it
			if (s.isEmpty() || Undo.busy(settlementId) || SettlementRunner.busy(settlementId)) return Optional.empty();
			try {
				var api = ArchitectApi.get().regions();
				List<Village> regions = new java.util.ArrayList<>(api.list(s.get().owner()).stream().map(Village::of).toList());
				// the sweep's own take-ups still standing in the history: a removed one is a tombstone the list leaves out
				for (String id : standingTakeUps(s.get())) {
					if (regions.stream().noneMatch(v -> v.id().equals(id))) api.get(id).map(Village::of).ifPresent(regions::add);
				}
				return nextFree(s.get(), regions, SettlementRunner::running, TRIED, id -> playerIn(server, s.get(), id), id -> {
					// (slice VSR, manager) never asked while a player stands in it: Architect would hold the removal (and the settlement) up to 10 minutes
					if (HELD.add(settlementId)) Steward.LOGGER.info("village region {} of {} waits: a player stands in it (asked again once they leave)", id, settlementId);
				}).map(id -> {
					Steward.LOGGER.warn("village region {} of {} is held by no build and no history entry: taking it up", id, settlementId);
					return id;
				});
			} catch (RuntimeException | LinkageError e) {
				Steward.LOGGER.warn("{}: could not look for villages without a build", settlementId, e);
				return Optional.empty();
			}
		}, TRIED, (id, done) -> {
			var s = Settlements.store().get(settlementId);
			if (s.isEmpty()) { done.accept(null); return; }
			takeUp(server, s.get(), id, m -> Steward.LOGGER.info("{}: {}", settlementId, m), done);
		});
	}

	/**
	 * (slice VSR) Runs a settlement's take-ups one at a time: {@code next} chooses one (from fresh state), it is marked tried and the settlement held in
	 * {@code holding} until its answer; true or false goes on to the next choice, null (the world closed) stops. A take-up that answers before it returns
	 * (refused at once) goes on the same way. Nothing starts while the settlement is held. Pure but for what it is handed.
	 */
	static void chain(String settlementId, java.util.Set<String> holding, java.util.function.Supplier<Optional<String>> next, java.util.Set<String> tried,
		java.util.function.BiConsumer<String, Consumer<Boolean>> takeUp) {
		if (holding.contains(settlementId)) return;
		var id = next.get();
		if (id.isEmpty()) return;
		tried.add(id.get());
		holding.add(settlementId);
		boolean[] answered = { false };
		takeUp.accept(id.get(), ok -> {
			if (answered[0]) return;
			answered[0] = true;
			holding.remove(settlementId);
			if (ok != null) chain(settlementId, holding, next, tried, takeUp);
		});
	}

	/** A history note naming a growth whose build ended while it was still being placed: it is logged or taken up once it has ended (its tag on the note). */
	static final String ORPHAN = "An addition realised after its build ended is still being placed";
	/** A history note closing an {@link #ORPHAN} note that left nothing to log (taken up, or standing with nothing placed). */
	static final String ORPHAN_CLOSED = "An addition realised after its build ended left nothing standing";
	/** Growths being taken up by the sweep (not asked twice while one is in flight). This world's session only: cleared when it stops. */
	private static final java.util.Set<String> TAKING = new java.util.HashSet<>();
	/** (slice DG) Settlements with a sweep take-up in flight: one at a time per settlement. */
	private static final java.util.Set<String> SWEEPING = new java.util.HashSet<>();
	/** Orphan notes whose save failed, by settlement: saved again by the sweep until they are (this session; see GROWTH.md for a restart). */
	private static final Map<String, List<Settlement.LogEntry>> UNSAVED = new java.util.HashMap<>();

	static {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(srv -> { TAKING.clear(); SWEEPING.clear(); UNSAVED.clear(); TRIED.clear(); REMOVING.clear(); HELD.clear(); });
	}

	/** An orphan note to save again on the sweep's next pass (its save failed). */
	static void retryNote(String settlementId, Settlement.LogEntry note) {
		UNSAVED.computeIfAbsent(settlementId, k -> new java.util.ArrayList<>()).add(note);
	}

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
		// (slice VSR, manager) the villages a player stood in are looked at again (one at a time per settlement, each once, as at the world start)
		for (String id : List.copyOf(HELD)) {
			HELD.remove(id);
			villages(server, id);
		}
	}

	public static void growths(MinecraftServer server, Settlement given) {
		var regions = ArchitectApi.get().regions();
		var sites = ArchitectApi.get().sites(server);
		// orphan notes whose save failed: saved first, so the obligation is in the history before anything is decided from it
		var unsaved = UNSAVED.get(given.id());
		if (unsaved != null) {
			for (var it = unsaved.iterator(); it.hasNext();) if (Settlements.log(given.id(), it.next()).ok()) it.remove();
			if (unsaved.isEmpty()) UNSAVED.remove(given.id());
		}
		final Settlement s = Settlements.store().get(given.id()).orElse(given);
		try {
			// (slice DG) each standing district's land read again from its settled region first, whatever the take-ups below do (a crash between a growth's
			// entry or its removal and the land's save is put right here; idempotent; a region being changed is left for a later sweep)
			for (Settlement.District d : s.lands().districts()) if (Districts.grows(d)) Districts.refreshLand(s.id(), d.region());
			// one take-up per settlement at a time (slice DG review 5: Undo's busy mark is per settlement); the next sweep goes on with fresh state
			if (Undo.busy(s.id()) || SWEEPING.contains(s.id())) return;
			// the town's region and (slice DG) every standing district region of the settlement: each one's revisions
			for (String id : growable(TownRegion.of(s).orElse(null), s.lands().districts())) {
				var town = regions.get(id);
				if (town.isEmpty() || town.get().revisions() == null) continue;
				if (sweep(server, s, town.get(), sites)) return;
			}
		} catch (RuntimeException | LinkageError e) {
			Steward.LOGGER.warn("{}: could not look for growths without a build", s.id(), e);
		}
	}

	/** (slice DG) The regions whose growths the sweep reads: the town's, then each standing district's own region, none twice. Pure. */
	static List<String> growable(@org.jspecify.annotations.Nullable String town, List<Settlement.District> districts) {
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		if (town != null) out.add(town);
		for (Settlement.District d : districts) if (Districts.grows(d)) out.add(d.region());
		return List.copyOf(out);
	}

	/** One region's growths held by no build and no entry; true when a take-up was started (the settlement is then left until it ends). */
	private static boolean sweep(MinecraftServer server, Settlement s, dev.larattalabs.architect.api.RegionView region, dev.larattalabs.architect.api.Sites sites) {
		var town = java.util.Optional.of(region);
		{
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
					// (slice DG review round 2) only a take-up that can go now holds the settlement: one refused (a newer change above it, or busy) leaves the
					// sweep going on to the newer head, the other regions and the land, and is asked again once that has changed
					var now0 = Growths.decide(java.util.Optional.of(town.get()), r.rid());
					if (now0.step() != Growths.Step.UNDO) {
						if (now0.step() == Growths.Step.UNKNOWN) close(s, tag, "Architect has no such revision");
						continue;
					}
					TAKING.add(tag);
					SWEEPING.add(s.id());
					Undo.mark(s.id(), true);
					Growths.undo(server, s.owner(), ref, m -> Steward.LOGGER.info("{}: {}", s.id(), m), ok -> {
						TAKING.remove(tag);
						SWEEPING.remove(s.id());
						Undo.mark(s.id(), false);
						if (ok == null) return;
						var now = Settlements.store().get(s.id()).orElse(s);
						if (ok) { close(now, tag, "taken up"); return; }
						// only Architect having no such revision closes it; a newer change above it (NEWER) keeps the note open, so it is taken up once that
						// is undone (an empty revision still holds the town's history)
						var d = Growths.decide(ArchitectApi.get().regions().get(ref.region()), ref.rid());
						if (d.step() == Growths.Step.UNKNOWN) close(now, tag, "Architect has no such revision");
					});
					return true;
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
				SWEEPING.add(s.id());
				Undo.growth(server, saved, entry, ref, Map.of(), m -> Steward.LOGGER.info("{}: {}", s.id(), m), ok -> { TAKING.remove(tag); SWEEPING.remove(s.id()); });
				return true;
			}
		}
		return false;
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
		takeUp(server, s, regionId, say, ok -> { });
	}

	/**
	 * As {@link #takeUp(MinecraftServer, Settlement, String, Consumer)}, with its answer on the server thread: true taken up, false stopped or refused (or
	 * the settlement busy with another take-up, an undo or a removal), null when the world closed meanwhile. One take-up per settlement at a time, whoever asks (slice VSR).
	 */
	static void takeUp(MinecraftServer server, Settlement s, String regionId, Consumer<String> say, Consumer<Boolean> whenDone) {
		// (slice VSR review 2) nor while an undo or cancel of the settlement runs, or Architect is removing one of its regions: SettlementRunner.busy's
		// removal half (the sweep's chain checks the same before it chooses)
		if (playerIn(server, s, regionId)) {
			// (slice VSR, manager) Architect would hold the removal while a player stands in it: not asked now; the 10 s tick's sweep takes it up once they
			// have left (not tried this start, so it is chosen again)
			Steward.LOGGER.info("village region {} of {} waits: a player stands in it (asked again once they leave)", regionId, s.id());
			TRIED.remove(regionId);
			HELD.add(s.id());
			whenDone.accept(false);
			return;
		}
		if (Undo.busy(s.id()) || removingAny(s) || !REMOVING.add(s.id())) {
			Steward.LOGGER.warn("village region {} of {} waits: the settlement is busy taking up or removing another (looked at again at the next start)", regionId, s.id());
			whenDone.accept(false);
			return;
		}
		Consumer<Boolean> done = ok -> {
			REMOVING.remove(s.id());
			whenDone.accept(ok);
		};
		var entry = entryOf(s, regionId);
		if (entry.isEmpty()) {
			List<String> ids = sitesOf(server, s.owner(), regionId);
			var sites = ArchitectApi.get().sites(server);
			if (!ids.isEmpty()) {
				List<Settlement.SiteChange> changes = ids.stream().map(id -> new Settlement.SiteChange(id, sites.get(id).map(VillageSweep::role).orElse(null), 0,
					sites.get(id).map(SiteView::version).orElse(1))).toList();
				var placed = Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.PROJECT_PLACED, TAKING_UP, changes, VPlan.REGION_PREFIX + regionId,
					Settlement.Outcome.PARTIAL);
				if (Settlements.log(s.id(), placed).ok()) entry = Settlements.store().get(s.id()).flatMap(x -> entryOf(x, regionId));
			}
		}
		if (entry.isEmpty()) {
			// the settlement is busy while the region is taken up (Undo.region marks it so too); its answer on the server thread, this world's only
			Undo.mark(s.id(), true);
			int session = Session.current();
			ArchitectApi.get().regions().remove(regionId, new RemoveOptions(false, s.owner())).whenComplete((r, err) -> server.execute(() -> {
				Undo.mark(s.id(), false);
				if (!Session.is(session) || SettlementRunner.worldStopped(err)) { done.accept(null); return; }
				boolean ok = err == null && r.removed();
				if (!ok) Steward.LOGGER.warn("village region {} of {} could not be taken up yet (tried again at the next start): {}", regionId, s.id(),
					err != null ? err.toString() : r.blockers());
				done.accept(ok);
			}));
			return;
		}
		Undo.region(server, Settlements.store().get(s.id()).orElse(s), entry.get(), regionId, Map.of(), say, done);
	}

	/** Whether Architect is removing a region of the settlement (a removal in flight, or one it resumed at the world start). */
	private static boolean removingAny(Settlement s) {
		try {
			return ArchitectApi.get().regions().list(s.owner()).stream().anyMatch(v -> v.state() == RegionState.REMOVING);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
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
