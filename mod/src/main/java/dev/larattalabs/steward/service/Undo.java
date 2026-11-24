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

	// ------------------------------------------------------------------ (T4S-PRE) removals on Architect's transaction layer (T4)

	/**
	 * Whether Architect runs removals as transactions (T4c: feature {@link RemovalEnds#FEATURE}): a group or a site removal is then decided whole before
	 * anything is taken down (never "stopped after N of M" once decided), so its undo leaves an intent note first and is finished by a sweep or by
	 * {@code RemovalFinished}. False on an Architect without it (0.83.0), or when it can't be read: everything as before.
	 */
	static boolean txnRemovals() {
		try {
			return ArchitectAccess.api().features().contains(RemovalEnds.FEATURE);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/**
	 * Whether Architect's sites file loaded cleanly ({@code Sites.loadHealthy()}; T4 change 7: also false while a participant store is held because it
	 * failed to load). A site read as absent counts as gone only when it holds; can't tell (an older Architect, an error): not healthy.
	 */
	static boolean healthy(@org.jspecify.annotations.Nullable MinecraftServer server) {
		try {
			return ArchitectAccess.api().sites(server).loadHealthy();
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** (T4 change 5c) How many times a removal Architect refuses {@code held(other)} (another change holds what it needs; nothing changed) is asked. */
	static final int OTHER_TRIES = 5;
	/** Runs a held(other) retry a moment later (tests run it at once or queue it). */
	static final java.util.function.Consumer<Runnable> LATER = r -> CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS).execute(r);
	static java.util.function.Consumer<Runnable> later = LATER;

	/**
	 * {@code ask}'s removal, asked again a moment later while Architect answers SITE_BUSY {@code held(other)}, at most {@link #OTHER_TRIES} times and only
	 * in this world session; any other answer (held(decided) included) is the call's. On the server thread.
	 */
	static CompletableFuture<RemoveResult> asking(@org.jspecify.annotations.Nullable MinecraftServer server, java.util.function.Supplier<CompletableFuture<RemoveResult>> ask) {
		return asking(server, ask, OTHER_TRIES, Session.current());
	}

	private static CompletableFuture<RemoveResult> asking(@org.jspecify.annotations.Nullable MinecraftServer server, java.util.function.Supplier<CompletableFuture<RemoveResult>> ask,
		int left, int session) {
		CompletableFuture<RemoveResult> out = new CompletableFuture<>();
		ask.get().whenComplete((r, err) -> {
			if (err != null && left > 1 && Session.is(session) && SiteBusy.of(err).other()) {
				Steward.LOGGER.info("a removal is held by another change (held(other)); asked again ({} left)", left - 1);
				later.accept(() -> ArchitectAccess.onServer(server, () -> asking(server, ask, left - 1, session).whenComplete((r2, e2) -> {
					if (e2 != null) out.completeExceptionally(e2);
					else out.complete(r2);
				})));
				return;
			}
			if (err != null) out.completeExceptionally(err);
			else out.complete(r);
		});
		return out;
	}

	/** The text a held, decided undo's note starts with: still open (the sweep or {@code RemovalFinished} finishes it). */
	static final String HELD = "Undo under way";

	/** The note a held, decided undo leaves (tag {@code tag}: its region's or its entry's intent tag); never Architect's text. */
	static Settlement.LogEntry heldNote(String tag, String what, SiteBusy.Held h) {
		return new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, HELD + ": \"" + what + "\" (" + RemovalStops.waitsFor(h) + ")", List.of(), tag);
	}

	/** Saves the held note unless the newest note with {@code tag} is one already (a re-ask answered held again). */
	private static void noteHeld(Settlement s, String tag, String what, SiteBusy.Held h) {
		var fresh = Settlements.store().get(s.id()).orElse(s);
		for (int i = fresh.log().size() - 1; i >= 0; i--) {
			var e = fresh.log().get(i);
			if (e.kind() != Settlement.Kind.NOTE || !tag.equals(e.siteGroup())) continue;
			if (e.text().startsWith(HELD)) return;
			break;
		}
		if (!Settlements.log(s.id(), heldNote(tag, what, h)).ok()) Steward.LOGGER.warn("{}: the held undo ({}) left no note: /steward undo again finishes it", s.id(), tag);
	}

	/** The tag of the intent note for the undo of a placing entry by its group or site by site (T4S-PRE): {@code undo:entry:<at>}. */
	static String entryTag(Settlement.LogEntry placed) {
		return ENTRY_PREFIX + placed.at();
	}

	static final String ENTRY_PREFIX = "undo:entry:";

	/** The held note's tag for a site-by-site undo held at {@code siteId}: the entry's, with the site the sweep waits for. */
	static String entryTag(Settlement.LogEntry placed, String siteId) {
		return entryTag(placed) + "@" + siteId;
	}

	/** The note the undo of a placing entry by its group or site by site saves just before Architect is asked (only with {@link #txnRemovals}). */
	static Settlement.LogEntry entryIntentNote(Settlement.LogEntry placed) {
		return new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, INTENT + " \"" + placed.text() + "\": its sites are being removed (a stop meanwhile"
			+ " is put right by itself)", List.of(), entryTag(placed));
	}

	/** The note closing a stopped or refused undo of an entry: its intent no longer stands. */
	static Settlement.LogEntry entryClosedNote(Settlement.LogEntry placed, String why) {
		return new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, STOPPED + ": " + why, List.of(), entryTag(placed));
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
			if (!line.startsWith("Undone") && !line.startsWith("Undo stopped") && !line.startsWith(RemovalStops.UNDO_WAITS)) return;
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
		// (T4R-RETRY) the player's own ask is the undo now: a retry waiting for this settlement is dropped, so one undo runs, never two
		BusyRetries.forget(retryKey(s.id()));
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
			groupUndo(server, sites, s, entry.get(), group, List.copyOf(g.get().sites()), now, as, say);
			return true;
		}
		chainUndo(server, sites, s, entry.get(), ids, adopted, now, as, say);
		return true;
	}

	/**
	 * A whole project's undo by its site group ({@code removeGroup}, newest first over ticks), the settlement marked UNDOING by the caller and released
	 * here. (T4S-PRE) With {@link #txnRemovals}: an intent note first; a decided removal Architect holds ({@code held(decided)}) is said as under way, logs
	 * nothing (no FAILED, no PARTIAL), leaves its note open for the sweep or {@code RemovalFinished}; a stop took nothing down (T4 change 8), so nothing is
	 * read as gone from absence. Without it, as before, except that absence counts as gone only on a healthy sites read.
	 */
	static void groupUndo(@org.jspecify.annotations.Nullable MinecraftServer server, dev.larattalabs.architect.api.Sites sites, Settlement s, Settlement.LogEntry entry,
		String group, List<String> standing, java.util.Map<String, Integer> now, RemoveOptions as, Consumer<String> say) {
		boolean txn = txnRemovals();
		if (txn && !intent(s, entryIntentNote(entry))) {
			UNDOING.remove(s.id());
			say.accept(notStarted(s.id()));
			return;
		}
		int session = Session.current();
		var call = asking(server, () -> sites.removeGroup(group, as));
		// (T4R-RETRY) while it waits on its ticket behind another removal: said once, in the steward's words
		RemovalWaits.watch(server, s.owner(), () -> RemovalWaits.ofGroup(sites, group), call, say);
		call.whenComplete((r, err) -> {
			// the world closing mid-undo is not its outcome: what Architect finished is read again after the restart (the undo can be run again)
			if (!Session.is(session) || SettlementRunner.worldStopped(err)) { UNDOING.remove(s.id()); return; }
			var held = SiteBusy.of(err);
			if (held.decided()) {
				// decided and held: it finishes by itself; nothing logged as stopped, nothing asked again
				Steward.LOGGER.info("{}: the undo of group {} is decided and held: {}", s.id(), group, SettlementRunner.message(err));
				noteHeld(s, entryTag(entry), entry.text(), held);
				UNDOING.remove(s.id());
				say.accept(RemovalStops.underWay(held));
				return;
			}
			if (held.busyAtAll()) {
				// (T4RD-S) refused before it began (another removal or change in the way): nothing taken down, nothing read as gone, never a FAILED entry
				Steward.LOGGER.info("{}: the undo of group {} was refused as busy: {}", s.id(), group, SettlementRunner.message(err));
				String blocker = RemovalStops.blocker(server, held, s.owner());
				if (txn) closeEntry(s, entry, RemovalStops.busyWhy(held, blocker));
				UNDOING.remove(s.id());
				say.accept(RemovalStops.undoBusy(s.id(), held, blocker));
				return;
			}
			boolean done = err == null && r.removed();
			// gone read from absence only for an Architect that may stop part way, and only on a healthy sites read (a file that did not load lists nothing)
			List<String> gone = done ? standing : txn || !healthy(server) ? List.of() : standing.stream().filter(id -> sites.get(id).isEmpty()).toList();
			// logged once: a removal already logged since (the sweep, RemovalFinished) is not logged again
			List<String> fresh = notYetLogged(s, entry, gone);
			if (!fresh.isEmpty() && !Settlements.log(s.id(), removed(entry, fresh, gone.size() == standing.size(), now)).ok()) say.accept(UNLOGGED);
			UNDOING.remove(s.id());
			districts(server, s, say);
			// (raw-text) Architect's error is logged, never said
			if (err != null) {
				if (txn) closeEntry(s, entry, SITES_NOT_NOW);
				say.accept(stoppedByError(s.id(), gone.size(), standing.size(), err));
			}
			// (slice DRS, 8bS, undo-words) told by the group's typed stop cause (S-DRS-1) in the steward's words, never by the blocker's text (a
			// refused save's names chunk files); every site still standing took nothing down, some gone took part
			else if (!r.removed()) {
				Steward.LOGGER.info("{}: the undo of group {} stopped: {}", s.id(), group, r.blockers());
				// the group's typed stop when Architect types it, else what still stands; (T4 change 8) a txn removal stopped by blockers took nothing down
				var stop = txn ? dev.larattalabs.architect.api.RemovalStop.NOT_STARTED : RemovalStops.groupStop(sites, group);
				if (stop == null || stop == dev.larattalabs.architect.api.RemovalStop.NONE) stop = gone.isEmpty() ? dev.larattalabs.architect.api.RemovalStop.NOT_STARTED
					: dev.larattalabs.architect.api.RemovalStop.PARTIAL;
				var cause = RemovalStops.groupCause(sites, group);
				if (txn) closeEntry(s, entry, RemovalStops.noted(stop, cause));
				say.accept(RemovalStops.undoStopped(stop, cause, s.id(), gone.size() + " of " + Words.count(standing.size(), "site", "sites")));
			}
			else say.accept("Undone: removed " + Words.count(gone.size(), "site", "sites") + ", the land is restored.");
		});
	}

	/** The chain's stop when a removal is decided and held (the held site and its hold are kept beside it). */
	private static final String HELD_STOP = "\u0000held";
	/** (T4RD-S) The chain's stop when a site's removal is refused as busy (its hold kept beside it): nothing of that site began. */
	private static final String BUSY_STOP = "\u0000busy";
	/** The chain's stop when a site reads absent on a sites read that did not load cleanly: it can't be told gone. */
	static final String RECORDS_STOP = "Architect's records didn't load, so the rest can't be told apart";

	/**
	 * A partial undo, site by site, newest first, the settlement marked UNDOING by the caller and released here. Stops at the first site Architect
	 * refuses. (T4S-PRE) A site read as absent is gone only on a healthy sites read; a decided removal Architect holds stops the chain as under way (the
	 * sites before it are logged with it once it finishes: the sweep or {@code RemovalFinished}); with {@link #txnRemovals} an intent note first.
	 */
	static void chainUndo(@org.jspecify.annotations.Nullable MinecraftServer server, dev.larattalabs.architect.api.Sites sites, Settlement s, Settlement.LogEntry entry,
		List<String> ids, boolean adopted, java.util.Map<String, Integer> now, RemoveOptions as, Consumer<String> say) {
		boolean txn = txnRemovals();
		if (txn && !intent(s, entryIntentNote(entry))) {
			UNDOING.remove(s.id());
			say.accept(notStarted(s.id()));
			return;
		}
		List<String> removed = new ArrayList<>();
		String[] heldSite = new String[1];
		SiteBusy.Held[] heldHow = new SiteBusy.Held[1];
		CompletableFuture<String> chain = CompletableFuture.completedFuture(null);
		for (String id : ids) {
			chain = chain.thenCompose(stop -> {
				if (stop != null) return CompletableFuture.completedFuture(stop);
				// a site removed by hand meanwhile is simply gone; (T4S-PRE) only when Architect's sites loaded cleanly
				if (sites.get(id).isEmpty()) {
					if (!healthy(server)) return CompletableFuture.completedFuture(RECORDS_STOP);
					removed.add(id);
					return CompletableFuture.completedFuture(null);
				}
				return asking(server, () -> sites.remove(id, as)).handle((r, err) -> {
					if (err != null) {
						var h = SiteBusy.of(err);
						if (h.decided()) {
							heldSite[0] = id;
							heldHow[0] = h;
							Steward.LOGGER.info("{}: the undo of site {} is decided and held: {}", s.id(), id, SettlementRunner.message(err));
							return HELD_STOP;
						}
						if (h.busyAtAll()) {
							heldHow[0] = h;
							Steward.LOGGER.info("{}: the undo of site {} was refused as busy: {}", s.id(), id, SettlementRunner.message(err));
							return BUSY_STOP;
						}
						throw err instanceof java.util.concurrent.CompletionException ce ? ce : new java.util.concurrent.CompletionException(err);
					}
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
			// (T4R-RETRY) any outcome but a busy stop ends this operation's wait and its count
			if (!BUSY_STOP.equals(stop)) BusyRetries.forget(retryKey(s.id()));
			if (HELD_STOP.equals(stop)) {
				// decided and held: nothing logged now; the sites before it are logged with it once it finishes
				noteHeld(s, entryTag(entry, heldSite[0]), entry.text(), heldHow[0]);
				UNDOING.remove(s.id());
				say.accept(RemovalStops.underWay(heldHow[0]));
				return;
			}
			List<String> fresh = notYetLogged(s, entry, removed);
			if (!fresh.isEmpty() && !Settlements.log(s.id(), removed(entry, fresh, removed.size() == ids.size(), now)).ok()) say.accept(UNLOGGED);
			UNDOING.remove(s.id());
			districts(server, s, say);
			if (BUSY_STOP.equals(stop)) {
				// (T4RD-S) the sites before it are logged as removed; the rest stand, said by the busy cause in the steward's words
				String blocker = RemovalStops.blocker(server, heldHow[0], s.owner());
				// (batch review 2) the intent is closed: nothing is in flight at Architect (a busy refusal began nothing), so no later removal by something
				// else is taken for this undo; a retry saves its own intent before it asks again
				if (txn) closeEntry(s, entry, RemovalStops.busyWhy(heldHow[0], blocker));
				// (T4R-RETRY, row 1) asked again by itself once the blocker's removal finishes (or its bounded timer): said once, and once more when it
				// stops waiting
				var held = heldHow[0];
				var step = BusyRetries.wait(retryKey(s.id()), held, () -> retryChain(server, s.id(), entry, held), false);
				if (step.gaveUp()) say.accept(RemovalStops.undoBusy(s.id(), removed.size(), ids.size(), held, blocker));
				else if (step.first()) say.accept(RemovalStops.undoWaits(s.id(), removed.size(), ids.size(), held, blocker));
				return;
			}
			// (raw-text) Architect's error and blockers are logged, never said: a site's removal has no typed cause, so the generic words
			if (err != null) {
				if (txn) closeEntry(s, entry, SITES_NOT_NOW);
				say.accept(stoppedByError(s.id(), removed.size(), ids.size(), err));
			} else if (stop != null) {
				if (txn && removed.size() < ids.size()) closeEntry(s, entry, stop);
				say.accept(stoppedAfter(s.id(), removed.size(), ids.size(), stop));
			} else if (adopted) {
				say.accept("Undone: the steward no longer looks after it, and the build stands as it was.");
			} else {
				say.accept("Undone: removed " + Words.count(removed.size(), "site", "sites") + ", the land is restored.");
			}
		});
	}

	// ------------------------------------------------------------------ (T4R-RETRY) row 1: a site-by-site undo asked again by itself

	/** The key of a settlement's waiting undo ({@link BusyRetries}): one undo per settlement waits, the newest the player asked. */
	static String retryKey(String settlementId) {
		return "undo:" + settlementId;
	}

	/** Where a retried undo's lines go (the steward's voice once a world runs; tests capture them). */
	static final java.util.function.BiConsumer<String, String> VOICE = (id, line) -> {
		var srv = BusyRetries.server();
		if (srv != null) StewardVoice.say(srv, id, line);
	};
	static java.util.function.BiConsumer<String, String> retrySay = VOICE;

	/**
	 * (T4R-RETRY) A site-by-site undo that waited behind a busy site, asked again: only while {@code placed} is still the settlement's newest undoable,
	 * unretired entry (else nothing is taken down, and a newer entry is said); while the settlement is busy (a build, an undo, a district being built) it
	 * waits again, counted. The chain saves its own intent before it asks Architect (no intent, no removal); sites gone since read as removed and are
	 * never logged twice ({@code notYetLogged}). Server thread.
	 */
	static void retryChain(@org.jspecify.annotations.Nullable MinecraftServer server, String settlementId, Settlement.LogEntry placed, SiteBusy.Held held) {
		var found = Settlements.store().get(settlementId);
		if (found.isEmpty() || Settlements.disabled()) { BusyRetries.forget(retryKey(settlementId)); return; }
		Settlement s = found.get();
		java.util.function.Consumer<String> say = line -> retrySay.accept(settlementId, line);
		if (server != null) say = reportingOverCapacity(server, s, say);
		boolean runnerBusy = BusyRetries.settlementBusy(settlementId);
		if (retired(s, placed)) { BusyRetries.forget(retryKey(settlementId)); return; }
		if (busy(settlementId) || runnerBusy || districtBlocking(s, java.util.Optional.of(placed)).isPresent()) {
			var step = BusyRetries.wait(retryKey(settlementId), held, () -> retryChain(server, settlementId, placed, held), false);
			if (step.gaveUp()) say.accept(RemovalStops.undoBusy(settlementId, held, RemovalStops.blocker(server, held, s.owner())));
			return;
		}
		var head = headFirst(s, s.lastUndoable());
		if (head.isEmpty() || !sameEntry(head.get(), placed)) {
			BusyRetries.forget(retryKey(settlementId));
			say.accept(RETRY_NEWER + " (/steward undo " + settlementId + ").");
			return;
		}
		List<String> ids = new ArrayList<>(placed.siteIds());
		var sites = ArchitectAccess.api().sites(server);
		java.util.Map<String, Integer> now = new java.util.HashMap<>();
		for (String id : ids) sites.get(id).ifPresent(v -> now.put(id, v.version()));
		java.util.Collections.reverse(ids);
		Steward.LOGGER.info("{}: the undo of \"{}\" that waited is asked again", settlementId, placed.text());
		UNDOING.add(settlementId);
		chainUndo(server, sites, s, placed, ids, placed.kind() == Settlement.Kind.BUILD_ADOPTED, now, new RemoveOptions(false, s.owner()), say);
	}

	/** (T4R-RETRY) Said when a waiting undo isn't carried on because something newer was placed since. */
	static final String RETRY_NEWER = "The undo that waited isn't carried on: something newer was placed since, and undo goes newest first";

	/** The same placing entry (its time, kind and text). Pure. */
	static boolean sameEntry(Settlement.LogEntry a, Settlement.LogEntry b) {
		// its tag is not compared: the newest undoable after a partial undo is the same entry with its tag dropped (site by site from then on)
		return a.at() == b.at() && a.kind() == b.kind() && java.util.Objects.equals(a.text(), b.text());
	}

	/**
	 * Saves an undo's intent note before Architect is asked (T4RD-S, GPT P1-2: persist before mutate). False when it could not be saved: the caller asks
	 * nothing, releases its mark and says so (nothing was taken down), so no removal ever runs without the record the sweep finishes it from.
	 */
	private static boolean intent(Settlement s, Settlement.LogEntry note) {
		if (Settlements.log(s.id(), note).ok()) return true;
		Steward.LOGGER.warn("{}: the undo ({}) left no note: not started", s.id(), note.siteGroup());
		return false;
	}

	/** (T4RD-S) What an undo whose intent could not be saved says: nothing was taken down. Pure. */
	static String notStarted(String settlementId) {
		return "Undo not started: its record couldn't be saved, so nothing was taken down. Run /steward undo " + settlementId + " again.";
	}

	/** Closes an entry's intent note: the undo stopped or was refused, so a later removal by something else is never taken for it. */
	private static void closeEntry(Settlement s, Settlement.LogEntry entry, String why) {
		if (!Settlements.log(s.id(), entryClosedNote(entry, why)).ok()) Steward.LOGGER.warn("{}: the stopped undo of \"{}\" left its note open", s.id(), entry.text());
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
		return region(server, s, entry, region, now, say, whenDone, intent, null);
	}

	/**
	 * As above; {@code whenHeld} (T4S-PRE) runs instead of {@code whenDone} when Architect decided the removal and holds it ({@code held(decided)}): it
	 * finishes by itself (the region stays REMOVING; the sweep or {@code RemovalFinished} logs it), so a cancel waits for REMOVED rather than ending.
	 * Null: {@code whenDone(false)}, with nothing said about asking again.
	 */
	static boolean region(MinecraftServer server, Settlement s, Settlement.LogEntry entry, String region, java.util.Map<String, Integer> now,
		Consumer<String> say, Consumer<Boolean> whenDone, boolean intent, @org.jspecify.annotations.Nullable Runnable whenHeld) {
		// the settlement is busy while its land is rewritten (no build surveys it half restored), whoever asked: undo, a cancel, the sweep
		UNDOING.add(s.id());
		Consumer<Boolean> done = ok -> {
			UNDOING.remove(s.id());
			whenDone.accept(ok);
		};
		Runnable heldDone = () -> {
			UNDOING.remove(s.id());
			if (whenHeld != null) whenHeld.run();
			else whenDone.accept(false);
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
		var call = asking(server, () -> regions.remove(region, new RemoveOptions(false, s.owner())));
		// (T4R-RETRY) while it waits on its ticket behind another removal: said once, in the steward's words
		RemovalWaits.watch(server, s.owner(), () -> RemovalWaits.ofRegion(region), call, say);
		call.whenComplete((r, err) -> ArchitectAccess.onServer(server, () -> {
			if (!Session.is(session) || SettlementRunner.worldStopped(err)) { done.accept(null); return; }
			// (T4S-PRE, 8cT/T4) decided and held: under way, finishing by itself. No closing note (the sweep, SD2, or RemovalFinished logs it once it is
			// REMOVED), nothing logged as stopped, nothing asked again
			var hold = SiteBusy.of(err);
			if (hold.decided()) {
				Steward.LOGGER.info("{}: the undo of {} is decided and held: {}", s.id(), region, SettlementRunner.message(err));
				noteHeld(s, intentTag(region), entry.text(), hold);
				say.accept(RemovalStops.underWay(hold));
				heldDone.run();
				return;
			}
			// (T4RD-S) refused as busy before it began: said by its typed cause, noted, never as Architect's text
			String busyBlocker = hold.busyAtAll() ? RemovalStops.blocker(server, hold, s.owner()) : null;
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
				: busyBlocker != null ? RemovalStops.undoBusy(s.id(), hold, busyBlocker)
				: err != null ? "Undo stopped: " + NOT_NOW + ". Run /steward undo " + s.id() + " again."
				: RemovalStops.undoStopped(stop, cause, s.id());
			// (batch review 2) a refused undo's note is closed: a later removal by something else is never taken for this undo
			String note = held != null ? held : busyBlocker != null ? RemovalStops.busyWhy(hold, busyBlocker) : err != null ? NOT_NOW : RemovalStops.noted(stop, cause);
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
			// (T4S-PRE) a held, decided removal's note keeps it open too
			if (e.text().startsWith(INTENT) || e.text().startsWith(HELD)) open.put(region, i);
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
		return !finish(server, given, null, null, server == null ? (id, line) -> { } : (id, line) -> StewardVoice.say(server, id, line)).isEmpty();
	}

	/**
	 * (SD2; T4S-PRE) The open undos of {@code given} finished: each region an undo's note names, once Architect holds its tombstone (or {@code doneKind}
	 * "region" names it: {@code RemovalFinished}'s positive answer), and (T4S-PRE) each entry whose group or site-by-site undo left an intent or a held
	 * note, once its sites read absent on a healthy sites read (or {@code doneKind} "group"/"site" names it). Each logged once (on a fresh read), said
	 * through {@code say} (settlement id, line). Returns the placing entries it logged. Server thread.
	 */
	static List<Settlement.LogEntry> finish(@org.jspecify.annotations.Nullable MinecraftServer server, Settlement given, @org.jspecify.annotations.Nullable String doneKind,
		@org.jspecify.annotations.Nullable String doneId, java.util.function.BiConsumer<String, String> say) {
		var s = Settlements.store().get(given.id()).orElse(given);
		List<Settlement.LogEntry> logged = new ArrayList<>();
		for (String region : interrupted(s)) {
			dev.larattalabs.architect.api.RegionState state;
			try {
				state = ArchitectAccess.api().regions().get(region).map(dev.larattalabs.architect.api.RegionView::state).orElse(null);
			} catch (RuntimeException | LinkageError e) {
				state = null;
			}
			var fresh = Settlements.store().get(s.id()).orElse(s);
			// the event's answer counts only for a held removal: an unheld one (RemovalFinished fires for it too) is its own answer's to log
			boolean said = "region".equals(doneKind) && region.equals(doneId) && heldOpen(fresh, intentTag(region));
			if (!said && resume(state) != Resume.LOG) continue;
			var placed = VillageSweep.entryOf(fresh, region);
			if (placed.isEmpty() || retired(fresh, placed.get())) continue;
			boolean wasHeld = heldOpen(fresh, intentTag(region));
			if (!Settlements.log(s.id(), removed(placed.get(), placed.get().siteIds(), true, java.util.Map.of())).ok()) {
				Steward.LOGGER.warn("{}: the undo of {} finished after a restart, but its record could not be saved (tried again later)", s.id(), region);
				continue;
			}
			logged.add(placed.get());
			Steward.LOGGER.info("{}: the undo of {} finished {}: logged", s.id(), region, wasHeld ? "by itself" : "after a restart");
			// (batch review 1) only this region's own district, on its tombstone with its entries retired and a healthy sites read (giveUpRemoved);
			// never the settlement-wide pass, which takes a sibling whose record isn't loaded yet for removed
			String more = "";
			var after = Settlements.store().get(s.id()).orElse(s);
			var own = after.lands().districts().stream().filter(d -> !d.square() && region.equals(d.region())).findFirst();
			if (own.isPresent()) {
				Districts.giveUpRemoved(server, after, own.get());
				if (Settlements.store().get(s.id()).flatMap(x -> x.lands().district(own.get().id())).isEmpty()) more = " " + own.get().name() + " is given up: its land is free again.";
			}
			say.accept(s.id(), "Undone: \"" + placed.get().text() + "\" " + (wasHeld ? "finished by itself" : "was taken up while the world was closed") + ", the land restored." + more);
			if (!endedBy(placed.get(), doneKind, doneId)) recovered(server, s, "region", region, say);
		}
		logged.addAll(finishEntries(server, s, doneKind, doneId, say));
		// a district region removed and logged, its give-up not saved (a stop between them)
		var now = Settlements.store().get(s.id()).orElse(s);
		for (Settlement.District d : now.lands().districts()) if (!d.square() && d.region() != null) Districts.giveUpRemoved(server, now, d);
		return logged;
	}

	/**
	 * (T4RD-S) Whether a removal's end ({@code kind}, {@code id}: {@code RemovalFinished}'s) is the one that took {@code placed} down: its region, its site
	 * group, or one of its sites. The one match from an event to a logged entry. Pure.
	 */
	static boolean endedBy(Settlement.LogEntry placed, @org.jspecify.annotations.Nullable String kind, @org.jspecify.annotations.Nullable String id) {
		if (kind == null || id == null) return false;
		return switch (kind) {
			case "region" -> id.equals(dev.larattalabs.steward.gateway.VPlan.regionOf(placed.siteGroup()));
			case "group" -> id.equals(placed.siteGroup());
			case "site" -> placed.siteIds().contains(id);
			default -> false;
		};
	}

	/**
	 * (T4RD-S, Q8) What came back from a removal the sweep logged without hearing its answer, said once (only as the sweep logs it): Architect's immutable
	 * snapshot ({@code Sites.removalResult}), the refund and what is owed back to the settlement ({@link OwedParts}). Nothing when Architect keeps none
	 * (an older build's removal, evicted) or nothing came back.
	 */
	static void recovered(@org.jspecify.annotations.Nullable MinecraftServer server, Settlement s, String kind, String id, java.util.function.BiConsumer<String, String> say) {
		dev.larattalabs.architect.api.RemoveResult r;
		try {
			r = ArchitectAccess.api().sites(server).removalResult(kind, id);
		} catch (RuntimeException | LinkageError e) {
			return;
		}
		String line = recoveredLine(s.owner(), r);
		if (line != null) say.accept(s.id(), line);
	}

	/** {@link #recovered}'s line for a snapshot: null when there is none or nothing came back. Pure. */
	static @org.jspecify.annotations.Nullable String recoveredLine(String owner, dev.larattalabs.architect.api.@org.jspecify.annotations.Nullable RemoveResult r) {
		if (r == null) return null;
		var owed = OwedParts.owedTo(owner, r);
		if (r.refund().isEmpty() && owed.isEmpty()) return null;
		return Growths.refundLine(r.refund(), owed);
	}

	/** Whether the newest note with {@code tag} is a held, decided removal's (T4S-PRE). Pure. */
	static boolean heldOpen(Settlement s, String tag) {
		for (int i = s.log().size() - 1; i >= 0; i--) {
			var e = s.log().get(i);
			if (e.kind() == Settlement.Kind.NOTE && tag.equals(e.siteGroup())) return e.text().startsWith(HELD);
		}
		return false;
	}

	/**
	 * (T4S-PRE) An entry's undo by its group or site by site left open: its placing entry, the site a held chain waits for (null: all of the entry's), and
	 * whether it is held (decided, finishing by itself) or only asked (a stop came before its answer).
	 */
	record EntryIntent(Settlement.LogEntry placed, @org.jspecify.annotations.Nullable String waitsFor, boolean held) {
	}

	/**
	 * (T4S-PRE) The entries whose group or site-by-site undo left an intent or held note (tag {@code undo:entry:<at>[@site]}) with no closing note after
	 * it, and whose placing entry is not retired and older than the note. Pure over the history.
	 */
	static List<EntryIntent> interruptedEntries(Settlement s) {
		java.util.LinkedHashMap<Long, Integer> open = new java.util.LinkedHashMap<>();
		java.util.Map<Long, String> waits = new java.util.HashMap<>();
		var log = s.log();
		for (int i = 0; i < log.size(); i++) {
			var e = log.get(i);
			if (e.kind() != Settlement.Kind.NOTE || e.siteGroup() == null || !e.siteGroup().startsWith(ENTRY_PREFIX)) continue;
			String rest = e.siteGroup().substring(ENTRY_PREFIX.length());
			int atSign = rest.indexOf('@');
			long at;
			try {
				at = Long.parseLong(atSign < 0 ? rest : rest.substring(0, atSign));
			} catch (NumberFormatException x) {
				continue;
			}
			if (e.text().startsWith(INTENT) || e.text().startsWith(HELD)) {
				open.put(at, i);
				if (atSign >= 0) waits.put(at, rest.substring(atSign + 1));
				else waits.remove(at);
			} else if (e.text().startsWith(STOPPED)) open.remove(at);
		}
		List<EntryIntent> out = new ArrayList<>();
		for (var o : open.entrySet()) {
			Settlement.LogEntry placed = null;
			for (int i = o.getValue() - 1; i >= 0; i--) {
				var e = log.get(i);
				if (e.at() == o.getKey() && (e.kind() == Settlement.Kind.PROJECT_PLACED || e.kind() == Settlement.Kind.BUILD_ADOPTED)) { placed = e; break; }
			}
			if (placed == null || retired(s, placed)) continue;
			out.add(new EntryIntent(placed, waits.get(o.getKey()), log.get(o.getValue()).text().startsWith(HELD)));
		}
		return List.copyOf(out);
	}

	/**
	 * (T4S-PRE) What an open entry undo's sites tell now: each of {@code sites} (the entry's) gone when {@code done} names it (Architect's positive answer)
	 * or it reads absent on a healthy read; the undo is finished once the site it waits for (all of them when none) is gone. Null: not finished (wait).
	 * Pure over the reads.
	 */
	static @org.jspecify.annotations.Nullable List<String> finished(List<String> sites, @org.jspecify.annotations.Nullable String waitsFor, java.util.Set<String> done,
		boolean healthy, java.util.function.Predicate<String> absent) {
		java.util.function.Predicate<String> gone = id -> done.contains(id) || healthy && absent.test(id);
		boolean end = waitsFor != null ? gone.test(waitsFor) : sites.stream().allMatch(gone);
		return end ? sites.stream().filter(gone).toList() : null;
	}

	private static List<Settlement.LogEntry> finishEntries(@org.jspecify.annotations.Nullable MinecraftServer server, Settlement s, @org.jspecify.annotations.Nullable String doneKind,
		@org.jspecify.annotations.Nullable String doneId, java.util.function.BiConsumer<String, String> say) {
		List<Settlement.LogEntry> logged = new ArrayList<>();
		var open = interruptedEntries(s);
		if (open.isEmpty()) return logged;
		boolean healthy = healthy(server);
		dev.larattalabs.architect.api.Sites sites;
		try {
			sites = ArchitectAccess.api().sites(server);
		} catch (RuntimeException | LinkageError e) {
			return logged;
		}
		for (EntryIntent it : open) {
			var placed = it.placed();
			java.util.Set<String> done = new java.util.HashSet<>();
			// the event's answer counts only for a held removal (an unheld one is its own answer's to log)
			String kind = it.held() ? doneKind : null;
			if ("group".equals(kind) && doneId != null && doneId.equals(placed.siteGroup())) done.addAll(placed.siteIds());
			if ("site".equals(kind) && doneId != null && placed.siteIds().contains(doneId)) done.add(doneId);
			// sites named by an earlier removal of this entry are not logged again
			var fresh = Settlements.store().get(s.id()).orElse(s);
			java.util.Set<String> already = removedSince(fresh, placed);
			List<String> left = placed.siteIds().stream().filter(id -> !already.contains(id)).toList();
			List<String> gone;
			try {
				gone = finished(left, it.waitsFor(), done, healthy, id -> sites.get(id).isEmpty());
			} catch (RuntimeException | LinkageError e) {
				gone = null;
			}
			if (gone == null) continue;
			boolean whole = gone.size() == left.size();
			if (!gone.isEmpty() && !Settlements.log(s.id(), removed(placed, gone, whole && already.isEmpty(), java.util.Map.of())).ok()) {
				Steward.LOGGER.warn("{}: the undo of \"{}\" finished, but its record could not be saved (tried again later)", s.id(), placed.text());
				continue;
			}
			// part of it (a held site-by-site undo): the rest stands, and the note is closed so it is never logged twice
			if (!whole) closeEntry(s, placed, "the rest stands");
			logged.add(placed);
			Steward.LOGGER.info("{}: the undo of \"{}\" finished {}: logged {} of {}", s.id(), placed.text(), it.held() ? "by itself" : "after a restart", gone.size(), left.size());
			String how = it.held() ? "finished by itself" : "was finished while the world was closed";
			say.accept(s.id(), whole ? "Undone: \"" + placed.text() + "\" " + how + ", the land is restored."
				: "Undone in part: " + Words.count(gone.size(), "site", "sites") + " of \"" + placed.text() + "\" " + how + "; the rest stands (/steward undo " + s.id() + " again).");
			if (whole && placed.siteGroup() != null && !endedBy(placed, doneKind, doneId)) recovered(server, s, "group", placed.siteGroup(), say);
		}
		return logged;
	}

	/** Of {@code gone}, the sites no {@code PROJECT_REMOVED} after {@code placed} names yet, on a fresh read. */
	private static List<String> notYetLogged(Settlement s, Settlement.LogEntry placed, List<String> gone) {
		var already = removedSince(Settlements.store().get(s.id()).orElse(s), placed);
		return gone.stream().filter(id -> !already.contains(id)).toList();
	}

	/** The sites a {@code PROJECT_REMOVED} after {@code placed} names already. Pure. */
	private static java.util.Set<String> removedSince(Settlement s, Settlement.LogEntry placed) {
		java.util.Set<String> out = new java.util.HashSet<>();
		boolean after = false;
		for (var e : s.log()) {
			if (!after && e.kind() == placed.kind() && e.at() == placed.at() && java.util.Objects.equals(e.siteGroup(), placed.siteGroup())) { after = true; continue; }
			if (after && e.kind() == Settlement.Kind.PROJECT_REMOVED) out.addAll(e.siteIds());
		}
		return out;
	}

	/**
	 * Takes up a growth of a region town (its revision, by rid) for the history entry that placed it: an undo, and a cancel or an orphan's take-up, which log
	 * the growth first. {@code done} as {@link #region}'s. The town itself is never removed here.
	 */
	public static boolean growth(MinecraftServer server, Settlement s, Settlement.LogEntry entry, Growths.Ref ref, java.util.Map<String, Integer> now,
		Consumer<String> say, Consumer<Boolean> whenDone) {
		return growth(server, s, entry, ref, now, say, whenDone, null);
	}

	/**
	 * As above; {@code whenHeld} (T4S-PRE) runs instead of {@code whenDone} when Architect decided the take-up and holds it ({@code held(decided)},
	 * said by {@link Growths#undo}): the busy mark is released, nothing is said about asking again, and the growth sweep logs it once its revision reads
	 * UNDONE. Null: {@code whenDone(false)}.
	 */
	public static boolean growth(MinecraftServer server, Settlement s, Settlement.LogEntry entry, Growths.Ref ref, java.util.Map<String, Integer> now,
		Consumer<String> say, Consumer<Boolean> whenDone, @org.jspecify.annotations.Nullable Runnable whenHeld) {
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
				// (T4R-RETRY) its refund is said only after a completion this answer appended (one already logged, by the event or the sweep, said its own)
				var logged = completeNow(s.id(), entry, ref, now);
				if (logged == Completion.FAILED) say.accept(UNLOGGED);
				// (slice DG) a district region's growth taken up: the district's land read again (the growth's land is let go)
				Districts.refreshLand(s.id(), ref.region());
				districts(server, s, say);
				// (slice A2L review 6) the town's build watch is dropped when only the growth kept it unbuilt: never "built" for a town that lost its addition
				MaterialRequests.forgetIfBuilt(ref.region());
				if (logged == Completion.LOGGED_NOW && Growths.reportsRefund(s, answer.get())) say.accept(Growths.refundLine(s, answer.get()));
				say.accept("Undone: " + whose + " change is taken up, the land restored.");
			} else say.accept("Run /steward undo " + s.id() + " again once it is clear.");
			whenDone.accept(ok);
		}, answer::set, () -> {
			UNDOING.remove(s.id());
			if (whenHeld != null) whenHeld.run();
			else whenDone.accept(false);
		});
		return true;
	}

	/**
	 * (slice A2L) A growth's take-up logged as complete, once: on a fresh read, nothing appended when its completion ({@link Growths#completed}) or an
	 * older untagged removal of every site of the entry ({@link #retired}) is there already. The removal names the entry's sites and every site
	 * Architect's revision holds (a cancel's batch may have placed more after the entry). False only when the append failed.
	 */
	static boolean complete(String settlementId, Settlement.LogEntry entry, Growths.Ref ref, java.util.Map<String, Integer> now) {
		return completeNow(settlementId, entry, ref, now) != Completion.FAILED;
	}

	/** (T4R-RETRY) What {@link #completeNow} did: appended the completion now, found it there already, or failed to append. */
	enum Completion { LOGGED_NOW, ALREADY, FAILED }

	/**
	 * (T4R-RETRY) {@link #complete}, saying whether this call appended the completion: the words that follow a growth's take-up (its refund) are said only
	 * after an append this pass made, so a replayed event or a second reader never says them twice.
	 */
	static Completion completeNow(String settlementId, Settlement.LogEntry entry, Growths.Ref ref, java.util.Map<String, Integer> now) {
		var fresh = Settlements.store().get(settlementId);
		if (fresh.isEmpty()) return Completion.FAILED;
		if (Growths.completed(fresh.get(), ref.tag()) || retired(fresh.get(), entry)) return Completion.ALREADY;
		List<String> revSites = List.of();
		try {
			revSites = ArchitectAccess.api().regions().get(ref.region()).flatMap(v -> Growths.revision(v, ref.rid())).map(r -> r.sites() == null ? List.<String>of() : r.sites())
				.orElse(List.of());
		} catch (RuntimeException | LinkageError e) {
			// the entry's own sites then
		}
		return Settlements.log(settlementId, removedGrowth(entry, removalSites(entry.siteIds(), revSites), ref.tag(), now)).ok() ? Completion.LOGGED_NOW
			: Completion.FAILED;
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
