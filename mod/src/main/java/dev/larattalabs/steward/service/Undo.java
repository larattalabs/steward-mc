package dev.larattalabs.steward.service;

import dev.larattalabs.steward.model.Words;
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

	/** (slice DG) A take-up started outside this class (the growth sweep's empty revision) marks the settlement busy while it runs. */
	static void mark(String settlementId, boolean on) {
		if (on) UNDOING.add(settlementId);
		else UNDOING.remove(settlementId);
	}

	/** Said when the undo happened but its history could not be saved. */
	static final String UNLOGGED = "The settlement's history could not be saved: the undo happened, but its record is missing (see the log).";

	/** The operation an undo logs: each removed site from the version it stood at ({@code now}; the placing entry's when unknown) to 0. */
	static Settlement.LogEntry removed(Settlement.LogEntry placed, List<String> gone, boolean whole, java.util.Map<String, Integer> now) {
		java.util.Map<String, Settlement.SiteChange> before = new java.util.HashMap<>();
		for (Settlement.SiteChange c : placed.changes()) before.put(c.siteId(), c);
		List<Settlement.SiteChange> changes = gone.stream().map(id -> {
			var b = before.get(id);
			int from = now.getOrDefault(id, b == null ? -1 : b.to());
			return new Settlement.SiteChange(id, b == null ? null : b.lot(), from, 0);
		}).toList();
		return Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.PROJECT_REMOVED, "Undid " + Words.count(gone.size(), "site", "sites") + " of \"" + placed.text() + "\"" + (placed.op() == null
			? "" : " (" + placed.op() + ")"), changes, null, whole ? Settlement.Outcome.DONE : Settlement.Outcome.PARTIAL);
	}

	/**
	 * {@code sayTo}, and with an undo's end ("Undone ...", "Undo stopped ..."): when its refund took the stockpile past its capacity, by how much and
	 * what that means. For any undo or cancel that refunds (a build cancelled while built from materials too). (Slice OVF-S) Since Architect 0.77.0 (OVF)
	 * an undo's refund is keyed and never overflows: all of it enters the stock, past capacity if need be, so nothing lies beside the crate to be picked
	 * up (the refundOverflow delta this read before could only be some other build's unkeyed give-back, never this undo's).
	 */
	public static Consumer<String> reportingOverCapacity(MinecraftServer server, Settlement s, Consumer<String> sayTo) {
		int overBefore = Stockpiles.of(server, s).map(Undo::over).orElse(0);
		return line -> {
			sayTo.accept(line);
			// any end of the undo: done, or stopped part way (what it took up before it stopped refunded too)
			if (!line.startsWith("Undone") && !line.startsWith("Undo stopped")) return;
			int over = Stockpiles.of(server, s).map(Undo::over).orElse(0);
			String said = over > overBefore ? Stockpiles.overCapacityWords(over, s.id()) : null;
			if (said != null) sayTo.accept(said);
		};
	}

	private static int over(dev.larattalabs.architect.api.Stockpile sp) {
		return sp.usedStacks() - sp.capacityStacks();
	}

	/**
	 * A district being built (or one whose build ended unresolved) that refuses an undo of {@code entry} for now: the newest operation can't be told
	 * yet, unless {@code entry} is that district's own region, recorded by the world-start sweep after its build ended (taking it up is what puts the
	 * district right). Empty when nothing refuses it.
	 */
	static java.util.Optional<Settlement.District> districtBlocking(Settlement s, java.util.Optional<Settlement.LogEntry> entry) {
		var building = s.lands().districts().stream().filter(d -> Settlement.District.BUILDING.equals(d.state())).findFirst();
		if (building.isEmpty()) return building;
		var live = Districts.liveRegionOf(s.owner(), building.get().id());
		boolean itsRegion = live.isPresent() && entry.isPresent() && live.get().equals(dev.larattalabs.steward.gateway.VPlan.regionOf(entry.get().siteGroup()));
		return itsRegion ? java.util.Optional.empty() : building;
	}

	/** Starts the undo; {@code say} gets progress and the result. Returns false (having said why) when there is nothing to undo. */
	public static boolean lastProject(MinecraftServer server, Settlement given, Consumer<String> sayTo) {
		// an adoption a lost answer left open is closed first, so the undo reads the history as it is
		if (!Adoption.reconcile(server, given)) {
			sayTo.accept(UNLOGGED);
			return false;
		}
		final Settlement s = Settlements.store().get(given.id()).orElse(given);
		Consumer<String> say = reportingOverCapacity(server, s, sayTo);
		// a district region being built (or one whose build ended unresolved): the newest operation can't be told yet, unless it is that district's own
		// region, recorded by the world-start sweep after its build ended (taking it up is what puts the district right)
		var entry = headFirst(s, s.lastUndoable());
		var building = districtBlocking(s, entry);
		if (building.isPresent()) {
			say.accept(building.get().name() + " is being built, or its build is being put right: undo once that has settled.");
			return false;
		}
		if (entry.isEmpty()) {
			say.accept("Nothing to undo for " + s.name() + ".");
			return false;
		}
		// the town taken on as a region (docs/GROWTH.md, slice B): let go (Architect un-adopts), every building stays; its growths were undone first
		if (entry.get().kind() == Settlement.Kind.TOWN_ADOPTED) {
			UNDOING.add(s.id());
			return Adoption.release(server, s, entry.get(), say, ok -> UNDOING.remove(s.id()));
		}
		// a reservation: no site, nothing in the world; giving it up is the undo ($0)
		if (entry.get().siteIds().isEmpty() && entry.get().district() != null) {
			var r = Settlements.giveUpReservation(s.id(), entry.get().district(), System.currentTimeMillis());
			say.accept(r.ok() ? "Undone: " + entry.get().text().replaceFirst("^Reserved", "the reservation of") + " is given up; nothing was built there." : "Could not undo: " + r.error());
			return r.ok();
		}
		List<String> ids = new ArrayList<>(entry.get().siteIds());
		// the steward never stands in what is removed (a named mob in a box stops Architect)
		var all = ArchitectAccess.api().sites(server);
		for (String id : ids) all.get(id).ifPresent(v -> StewardMotion.clear(server, s, v.box()));
		// the versions they stand at now (updates may have moved them since they were placed): what the undo log records as "from"
		java.util.Map<String, Integer> now = new java.util.HashMap<>();
		for (String id : ids) all.get(id).ifPresent(v -> now.put(id, v.version()));
		java.util.Collections.reverse(ids); // newest first
		boolean adopted = entry.get().kind() == Settlement.Kind.BUILD_ADOPTED;
		// a growth says its own
		if (!Growths.isGrowth(entry.get().siteGroup())) say.accept(adopted ? "Letting go of \"" + entry.get().text() + "\": the build stays as it stands..."
			: "Removing " + Words.count(ids.size(), "site", "sites") + " of \"" + entry.get().text() + "\" and restoring the land...");
		var sites = ArchitectAccess.api().sites(server);
		RemoveOptions as = new RemoveOptions(false, s.owner());
		UNDOING.add(s.id());
		// a whole project is one site group: removeGroup does the newest-first removal over ticks (stages, survival refunds, the group's bookkeeping), the
		// path Architect's gates prove exact. Only when the group holds nothing but this project's sites; else (a partial undo) site by site.
		// a flat village (Architect V) is one region: removed whole, its ground, street, lanes, square and props with its buildings
		// a growth of a region town (docs/GROWTH.md): taken up by its revision (Architect's undoRevision), never by removing the town
		var growth = Growths.parse(entry.get().siteGroup());
		if (growth.isPresent()) return growth(server, s, entry.get(), growth.get(), now, say, ok -> { if (ok != null) UNDOING.remove(s.id()); });
		String region = dev.larattalabs.steward.gateway.VPlan.regionOf(entry.get().siteGroup());
		if (region != null) {
			// the world closing mid-undo (null) is not its outcome: (slice SD2) the note left before the removal lets the sweep finish it after the restart
			return region(server, s, entry.get(), region, now, say, ok -> UNDOING.remove(s.id()), true);
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
				// (raw-text) Architect's error is logged, never said
				if (err != null) say.accept(stoppedByError(s.id(), gone.size(), standing.size(), err));
				// (slice DRS, 8bS, undo-words) told by the group's typed stop cause (S-DRS-1) in the steward's words, never by the blocker's text (a
				// refused save's names chunk files); every site still standing took nothing down, some gone took part
				else if (!r.removed()) {
					Steward.LOGGER.info("{}: the undo of group {} stopped: {}", s.id(), group, r.blockers());
					// the group's typed stop when Architect types it, else what still stands
					var stop = RemovalStops.groupStop(sites, group);
					if (stop == null || stop == dev.larattalabs.architect.api.RemovalStop.NONE) stop = gone.isEmpty() ? dev.larattalabs.architect.api.RemovalStop.NOT_STARTED
						: dev.larattalabs.architect.api.RemovalStop.PARTIAL;
					say.accept(RemovalStops.undoStopped(stop, RemovalStops.groupCause(sites, group), s.id(),
						gone.size() + " of " + Words.count(standing.size(), "site", "sites")));
				}
				else say.accept("Undone: removed " + Words.count(gone.size(), "site", "sites") + ", the land is restored.");
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
			// (raw-text) Architect's error and blockers are logged, never said: a site's removal has no typed cause, so the generic words
			if (err != null) say.accept(stoppedByError(s.id(), removed.size(), ids.size(), err));
			else if (stop != null) say.accept(stoppedAfter(s.id(), removed.size(), ids.size(), stop)); else if (adopted) {
				say.accept("Undone: the steward no longer looks after it, and the build stands as it was.");
			} else {
				say.accept("Undone: removed " + Words.count(removed.size(), "site", "sites") + ", the land is restored.");
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
		return region(server, s, entry, region, now, say, whenDone, false);
	}

	/**
	 * As above; {@code intent} (slice SD2, the player's undo): an {@link #INTENT} note naming the region is saved just before Architect is asked, so a stop
	 * before the removal's answer is logged is finished by {@link #resumeInterrupted} once Architect's resumed removal ends, with no second undo.
	 */
	static boolean region(MinecraftServer server, Settlement s, Settlement.LogEntry entry, String region, java.util.Map<String, Integer> now,
		Consumer<String> say, Consumer<Boolean> whenDone, boolean intent) {
		// the settlement is busy while its land is rewritten (no build surveys it half restored), whoever asked: undo, a cancel, the sweep
		UNDOING.add(s.id());
		Consumer<Boolean> done = ok -> {
			UNDOING.remove(s.id());
			whenDone.accept(ok);
		};
		var regions = ArchitectAccess.api().regions();
		List<String> regionSites = List.copyOf(entry.siteIds());
		// removed already (Architect's tombstone, 1.14 C22): its answer, or the save of it, was lost; the entry is retired now
		if (regions.get(region).filter(v -> v.state() == dev.larattalabs.architect.api.RegionState.REMOVED).isPresent()) {
			// logged once (slice VSR): an entry a replay finds retired already is not logged again
			var fresh = Settlements.store().get(s.id()).orElse(s);
			boolean again = retired(fresh, entry);
			if (!again && !Settlements.log(s.id(), removed(entry, regionSites, true, now)).ok()) say.accept(UNLOGGED);
			// the districts put right whatever the history says (a crash after the entry and before the district's land was let go)
			districts(server, s, say);
			if (!again) say.accept("Undone: the village was taken up already, the land restored.");
			done.accept(true);
			return true;
		}
		// a town that grew (docs/GROWTH.md): never removed whole while a revision of it stands or is in flight, whatever its sites say (a growth may have
		// written only ground, or not yet its first site); its growths are undone first, newest first
		var live = regions.get(region);
		String blocked = live.map(v -> Growths.blocksTownRemoval(v, s, ArchitectAccess.api().sites(server))).orElse(null);
		if (blocked != null) {
			say.accept("Undo stopped: " + blocked + ".");
			done.accept(false);
			return false;
		}
		// removed whole: only when everything in it is this entry's (a site another build put in it would go too)
		var others = ArchitectAccess.api().sites(server).list(s.owner()).stream().filter(v -> region.equals(v.region())).map(dev.larattalabs.architect.api.SiteView::id)
			.filter(id -> !regionSites.contains(id)).toList();
		if (!others.isEmpty()) {
			say.accept("Undo stopped: the village also holds " + String.join(", ", others) + ", which \"" + entry.text() + "\" did not place.");
			done.accept(false);
			return false;
		}
		// (slice M5, M4's Q3) a storage hall of the town holding items: refused at once and said with its chests (Architect's removal would wait on the cargo
		// as a blocker)
		String hall = HallCargo.before(server, s.owner(), regionSites);
		if (hall != null) {
			say.accept("Undo stopped: " + hall + ".");
			done.accept(false);
			return false;
		}
		// (slice SD2) the undo's intent first: a note only (the history's own entries decide everything else), so a failed save still undoes
		if (intent && !Settlements.log(s.id(), intentNote(entry, region)).ok()) {
			// (batch review 3) said, not only logged; the undo goes on
			Steward.LOGGER.warn("{}: the undo of {} left no note (a restart mid-removal needs /steward undo again)", s.id(), region);
			say.accept("If the world closes before this finishes, use /steward undo " + s.id() + " again.");
		}
		int session = Session.current();
		regions.remove(region, new RemoveOptions(false, s.owner())).whenComplete((r, err) -> ArchitectAccess.onServer(server, () -> {
			if (!Session.is(session) || SettlementRunner.worldStopped(err)) { done.accept(null); return; }
			// taken up only when Architect says so (1.14 C22: a retried removal finishes the restorations it left, or answers not removed)
			boolean ok = err == null && r.removed();
			// logged once (slice VSR): two asks of one removal (Architect joins them) retire the entry once
			if (ok && !retired(Settlements.store().get(s.id()).orElse(s), entry) && !Settlements.log(s.id(), removed(entry, regionSites, true, now)).ok()) say.accept(UNLOGGED);
			districts(server, s, say);
			// Architect's text (its error, its blockers): read only to find a storage hall's chests and logged, never said or noted (undo-words)
			String why = err != null ? (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()) : "blocked by " + String.join(", ", r.blockers());
			if (!ok) Steward.LOGGER.info("{}: the undo of {} stopped: {}", s.id(), region, why);
			// (slice M5) refused for a storage hall holding items: said as the steward says it, with its own count and chests
			String held = !ok && HallCargo.cargoRefusal(why) ? HallCargo.words(server, s.owner(), region, why) : null;
			// (slice DRS, 8bS, undo-words) Architect's typed stop (feature removalStop: NOT_STARTED took nothing down, PARTIAL took part) and its typed
			// cause (S-DRS-1): said and noted in the steward's words for that cause (RemovalStops.causeWords), the generic line for OTHER or none
			var stop = ok || err != null ? null : RemovalStops.of(region);
			var cause = ok || err != null ? null : RemovalStops.cause(region);
			String line = ok ? null : held != null ? "Undo stopped: " + held + ". " + RemovalStops.again(null, "/steward undo " + s.id())
				: err != null ? "Undo stopped: " + NOT_NOW + ". Run /steward undo " + s.id() + " again."
				: RemovalStops.undoStopped(stop, cause, s.id());
			// (batch review 2) a refused undo's note is closed: a later removal by something else is never taken for this undo
			String note = held != null ? held : err != null ? NOT_NOW : RemovalStops.noted(stop, cause);
			if (!ok && intent && !Settlements.log(s.id(), closedNote(region, note)).ok()) Steward.LOGGER.warn("{}: the stopped undo of {} left its note open", s.id(), region);
			if (line != null) say.accept(line);
			else say.accept("Undone: the village is taken up, the land restored.");
			done.accept(ok);
		}));
		return true;
	}

	// ------------------------------------------------------------------ (slice SD2) a restart mid-undo, finished by itself

	/** The text an undo's intent note starts with. */
	static final String INTENT = "Undoing";

	/** The tag of the intent note for the undo of {@code region} (never a placing tag: {@code region:} and {@code revision:} readers pass it by). */
	static String intentTag(String region) {
		return "undo:" + dev.larattalabs.steward.gateway.VPlan.REGION_PREFIX + region;
	}

	/** (undo-words) What an undo whose removal Architect refused with an error is said as (its message is logged, never said). */
	static final String NOT_NOW = "the village couldn't be taken up just now";

	/** The text the note closing a stopped undo starts with. */
	static final String STOPPED = "Undo stopped";

	/** (batch review 2) The note closing an undo Architect refused or stopped: the intent before it no longer stands. */
	static Settlement.LogEntry closedNote(String region, String why) {
		return new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, STOPPED + " (its region " + region + "): " + why, List.of(), intentTag(region));
	}

	/** The note the player's undo of a region saves just before Architect is asked to remove it. */
	static Settlement.LogEntry intentNote(Settlement.LogEntry placed, String region) {
		return new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, INTENT + " \"" + placed.text() + "\": its region " + region
			+ " is being removed (a stop meanwhile is put right by itself)", List.of(), intentTag(region));
	}

	/**
	 * The regions whose undo the player asked (an intent note after the region's newest placing entry) and whose removal is not logged yet (that entry not
	 * retired): a stop came between the ask and the logged answer. Each once, oldest ask first. Pure over the history.
	 */
	static List<String> interrupted(Settlement s) {
		// each region's newest note: an intent still open (no closing note after it), by where it stands in the history
		java.util.LinkedHashMap<String, Integer> open = new java.util.LinkedHashMap<>();
		var log = s.log();
		String prefix = "undo:" + dev.larattalabs.steward.gateway.VPlan.REGION_PREFIX;
		for (int i = 0; i < log.size(); i++) {
			var e = log.get(i);
			if (e.kind() != Settlement.Kind.NOTE || e.siteGroup() == null || !e.siteGroup().startsWith(prefix)) continue;
			String region = e.siteGroup().substring(prefix.length());
			if (e.text().startsWith(INTENT)) open.put(region, i);
			else if (e.text().startsWith(STOPPED)) open.remove(region);
		}
		List<String> out = new ArrayList<>();
		for (var o : open.entrySet()) {
			var placed = VillageSweep.entryOf(s, o.getKey());
			if (placed.isEmpty() || retired(s, placed.get()) || log.indexOf(placed.get()) > o.getValue()) continue;
			out.add(o.getKey());
		}
		return List.copyOf(out);
	}

	/** (slice SD2) What an interrupted undo's region asks for now. */
	enum Resume { WAIT, LOG, LEAVE }

	/**
	 * (slice SD2) What follows an interrupted undo, by its region's state now: removed (Architect's tombstone): its removal is logged, once; still being
	 * removed, or unknown (no record, or none readable): waited for; anything else (the resumed removal stopped, PARTIAL): left as an ordinary stopped
	 * undo, for the player's next undo (never asked again unseen: what blocked it may be the player's). Pure.
	 */
	static Resume resume(dev.larattalabs.architect.api.@org.jspecify.annotations.Nullable RegionState state) {
		if (state == dev.larattalabs.architect.api.RegionState.REMOVED) return Resume.LOG;
		if (state == null || state == dev.larattalabs.architect.api.RegionState.REMOVING) return Resume.WAIT;
		return Resume.LEAVE;
	}

	/**
	 * (slice SD2) Finishes the player's undos a stop cut off: each region whose removal Architect finished after the restart (its tombstone) is logged
	 * removed once, and that region's own district given up ({@link Districts#giveUpRemoved}); one still removing waits for a later sweep. Also a
	 * district region whose removal was logged but whose give-up never saved is given up ({@link Districts#giveUpRemoved}). Nothing while the settlement
	 * is busy. Returns whether anything was logged. Server thread.
	 */
	static boolean resumeInterrupted(MinecraftServer server, Settlement given) {
		if (busy(given.id())) return false;
		try {
			if (SettlementRunner.busy(given.id())) return false;
		} catch (RuntimeException | LinkageError e) {
			// can't tell whether anything runs: a later sweep looks again
			return false;
		}
		return finishInterrupted(server, given);
	}

	/** {@link #resumeInterrupted} once the settlement is known not busy. */
	static boolean finishInterrupted(@org.jspecify.annotations.Nullable MinecraftServer server, Settlement given) {
		var s = Settlements.store().get(given.id()).orElse(given);
		boolean logged = false;
		for (String region : interrupted(s)) {
			dev.larattalabs.architect.api.RegionState state;
			try {
				state = ArchitectAccess.api().regions().get(region).map(dev.larattalabs.architect.api.RegionView::state).orElse(null);
			} catch (RuntimeException | LinkageError e) {
				state = null;
			}
			if (resume(state) != Resume.LOG) continue;
			var fresh = Settlements.store().get(s.id()).orElse(s);
			var placed = VillageSweep.entryOf(fresh, region);
			if (placed.isEmpty() || retired(fresh, placed.get())) continue;
			if (!Settlements.log(s.id(), removed(placed.get(), placed.get().siteIds(), true, java.util.Map.of())).ok()) {
				Steward.LOGGER.warn("{}: the undo of {} finished after a restart, but its record could not be saved (tried again later)", s.id(), region);
				continue;
			}
			logged = true;
			Steward.LOGGER.info("{}: the undo of {} finished after a restart: logged", s.id(), region);
			// (batch review 1) only this region's own district, on its tombstone with its entries retired and a healthy sites read (giveUpRemoved);
			// never the settlement-wide pass, which takes a sibling whose record isn't loaded yet for removed
			String more = "";
			var after = Settlements.store().get(s.id()).orElse(s);
			var own = after.lands().districts().stream().filter(d -> !d.square() && region.equals(d.region())).findFirst();
			if (own.isPresent()) {
				Districts.giveUpRemoved(server, after, own.get());
				if (Settlements.store().get(s.id()).flatMap(x -> x.lands().district(own.get().id())).isEmpty()) more = " " + own.get().name() + " is given up: its land is free again.";
			}
			if (server != null) StewardVoice.say(server, s.id(), "Undone: \"" + placed.get().text() + "\" was taken up while the world was closed, the land restored." + more);
		}
		// a district region removed and logged, its give-up not saved (a stop between them)
		var now = Settlements.store().get(s.id()).orElse(s);
		for (Settlement.District d : now.lands().districts()) if (!d.square() && d.region() != null) Districts.giveUpRemoved(server, now, d);
		return logged;
	}

	/**
	 * Takes up a growth of a region town (its revision, by rid) for the history entry that placed it: an undo, and a cancel or an orphan's take-up, which log
	 * the growth first. {@code done} as {@link #region}'s. The town itself is never removed here.
	 */
	public static boolean growth(MinecraftServer server, Settlement s, Settlement.LogEntry entry, Growths.Ref ref, java.util.Map<String, Integer> now,
		Consumer<String> say, Consumer<Boolean> whenDone) {
		// (slice A2L) no longer refused while its targets wait for materials: Architect's RU cancels and refunds them (a stray survival_building answer
		// is said in A2's words by Growths.undo)
		UNDOING.add(s.id());
		String whose = s.lands().districts().stream().filter(d -> ref.region().equals(d.region())).map(Settlement.District::name).findFirst().map(n -> n + "'s").orElse("the town's");
		say.accept("Taking up \"" + entry.text() + "\" (" + whose + " change " + ref.rid() + ") and restoring the land...");
		java.util.concurrent.atomic.AtomicReference<dev.larattalabs.architect.api.RemoveResult> answer = new java.util.concurrent.atomic.AtomicReference<>();
		Growths.undo(server, s.owner(), ref, say, ok -> {
			// the world closed meanwhile: the busy mark was cleared at its stop, and may be a new world's now (slice A2L review 5)
			if (ok == null) { whenDone.accept(null); return; }
			UNDOING.remove(s.id());
			if (ok) {
				if (!complete(s.id(), entry, ref, now)) say.accept(UNLOGGED);
				// (slice DG) a district region's growth taken up: the district's land read again (the growth's land is let go)
				Districts.refreshLand(s.id(), ref.region());
				districts(server, s, say);
				// (slice A2L review 6) the town's build watch is dropped when only the growth kept it unbuilt: never "built" for a town that lost its addition
				MaterialRequests.forgetIfBuilt(ref.region());
				if (Growths.reportsRefund(s, answer.get())) say.accept(Growths.refundLine(answer.get().refund()));
				say.accept("Undone: " + whose + " change is taken up, the land restored.");
			} else say.accept("Run /steward undo " + s.id() + " again once it is clear.");
			whenDone.accept(ok);
		}, answer::set);
		return true;
	}

	/**
	 * (slice A2L) A growth's take-up logged as complete, once: on a fresh read, nothing appended when its completion ({@link Growths#completed}) or an
	 * older untagged removal of every site of the entry ({@link #retired}) is there already. The removal names the entry's sites and every site
	 * Architect's revision holds (a cancel's batch may have placed more after the entry). False only when the append failed.
	 */
	static boolean complete(String settlementId, Settlement.LogEntry entry, Growths.Ref ref, java.util.Map<String, Integer> now) {
		var fresh = Settlements.store().get(settlementId);
		if (fresh.isEmpty()) return false;
		if (Growths.completed(fresh.get(), ref.tag()) || retired(fresh.get(), entry)) return true;
		List<String> revSites = List.of();
		try {
			revSites = ArchitectAccess.api().regions().get(ref.region()).flatMap(v -> Growths.revision(v, ref.rid())).map(r -> r.sites() == null ? List.<String>of() : r.sites())
				.orElse(List.of());
		} catch (RuntimeException | LinkageError e) {
			// the entry's own sites then
		}
		return Settlements.log(settlementId, removedGrowth(entry, removalSites(entry.siteIds(), revSites), ref.tag(), now)).ok();
	}

	/** (slice A2L) The sites a growth's removal names: the entry's, then any other the revision holds, each once, in that order. Pure. */
	static List<String> removalSites(List<String> entrySites, List<String> revisionSites) {
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(entrySites);
		out.addAll(revisionSites);
		return List.copyOf(out);
	}

	/** (slice A2L) A growth's removal: as {@link #removed}, whole, carrying the growth's tag (its completion). */
	static Settlement.LogEntry removedGrowth(Settlement.LogEntry placed, List<String> gone, String tag, java.util.Map<String, Integer> now) {
		var r = removed(placed, gone, true, now);
		return Settlement.LogEntry.of(r.at(), r.kind(), r.text(), r.changes(), tag, r.outcome());
	}

	/**
	 * (slice A2) A town's growths are undone in Architect's order (its head first), whatever order the history logged them in: a growth logged late (an
	 * orphan the sweep found after a newer growth was logged) would otherwise be the newest entry and be refused (a newer change stands on it) forever.
	 * When the newest undoable entry is a growth that is not its town's head, the standing growth entry of that town's head is undone instead.
	 */
	static java.util.Optional<Settlement.LogEntry> headFirst(Settlement s, java.util.Optional<Settlement.LogEntry> entry) {
		var g = entry.flatMap(e -> Growths.parse(e.siteGroup()));
		if (g.isEmpty()) return entry;
		try {
			var town = ArchitectAccess.api().regions().get(g.get().region());
			if (town.isEmpty() || town.get().revisions() == null) return entry;
			int head = town.get().position();
			var top = town.get().revisions().stream().filter(r -> r.position() == head && !"UNDONE".equalsIgnoreCase(r.state()) && !"STALE".equalsIgnoreCase(r.state()))
				.findFirst();
			return top.isEmpty() ? entry : headFirst(s, entry, top.get().rid());
		} catch (RuntimeException | LinkageError e) {
			return entry;
		}
	}

	/** Pure: {@code headRid} the town's head revision; the entry kept when it is the head's, or when the head's growth has no standing entry. */
	static java.util.Optional<Settlement.LogEntry> headFirst(Settlement s, java.util.Optional<Settlement.LogEntry> entry, int headRid) {
		var g = entry.flatMap(e -> Growths.parse(e.siteGroup()));
		if (g.isEmpty() || g.get().rid() == headRid) return entry;
		return headEntry(s, Growths.tag(g.get().region(), headRid)).or(() -> entry);
	}

	/** The standing (not retired) placing entry with a growth's tag, as {@code lastUndoable} hands it out (its sites not removed since). Pure. */
	static java.util.Optional<Settlement.LogEntry> headEntry(Settlement s, String tag) {
		var log = s.log();
		for (int i = log.size() - 1; i >= 0; i--) {
			var e = log.get(i);
			if (e.kind() == Settlement.Kind.PROJECT_PLACED && tag.equals(e.siteGroup())) return retired(s, e) ? java.util.Optional.empty() : java.util.Optional.of(e);
		}
		return java.util.Optional.empty();
	}

	/**
	 * Whether a placing entry is retired already: a later {@code PROJECT_REMOVED} names every one of its sites (an undo, or a cancel's take-up, logged before
	 * a crash). Pure over the history; the entry is found by its time, kind and tag.
	 */
	static boolean retired(Settlement s, Settlement.LogEntry placed) {
		var log = s.log();
		int at = -1;
		for (int i = log.size() - 1; i >= 0; i--) {
			var e = log.get(i);
			if (e.kind() == placed.kind() && e.at() == placed.at() && java.util.Objects.equals(e.siteGroup(), placed.siteGroup())) { at = i; break; }
		}
		if (at < 0) return false;
		java.util.Set<String> gone = new java.util.HashSet<>();
		for (int i = at + 1; i < log.size(); i++) if (log.get(i).kind() == Settlement.Kind.PROJECT_REMOVED) gone.addAll(log.get(i).siteIds());
		return !placed.siteIds().isEmpty() && gone.containsAll(placed.siteIds());
	}

	private static void districts(MinecraftServer server, Settlement s, Consumer<String> say) {
		if (s.lands().districts().isEmpty()) return;
		String more = Districts.afterUndo(server, s.id());
		if (!more.isEmpty()) say.accept(more);
	}

	/** (raw-text) What a site-by-site undo says a site's blocked removal as: Architect's blockers are logged, the steward's words returned. */
	static String blockedText(String siteId, RemoveResult r) {
		Steward.LOGGER.info("the undo of site {} stopped: {}", siteId, r.blockers());
		return RemovalStops.IN_THE_WAY;
	}

	/** (raw-text) What a group's or a site-by-site undo whose removal stopped after {@code removed} of {@code of} sites says, {@code why} the steward's words. Pure. */
	static String stoppedAfter(String settlementId, int removed, int of, String why) {
		return "Undo stopped after " + removed + " of " + Words.count(of, "site", "sites") + ": " + why + ". " + RemovalStops.again(null, "/steward undo " + settlementId);
	}

	/** (raw-text) What a group's or a site-by-site undo refused with an error says: the error is logged with it, never said. */
	static String stoppedByError(String settlementId, int removed, int of, Throwable err) {
		Steward.LOGGER.warn("{}: the undo stopped after {} of {} sites", settlementId, removed, of, err);
		return "Undo stopped after " + removed + " of " + Words.count(of, "site", "sites") + ": " + SITES_NOT_NOW + ". Run /steward undo " + settlementId + " again.";
	}

	/** (raw-text) What a group's or a site-by-site undo whose removal Architect refused with an error is said as. */
	static final String SITES_NOT_NOW = "the rest couldn't be taken down just now";
}
