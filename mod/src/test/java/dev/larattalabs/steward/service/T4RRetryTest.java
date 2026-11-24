package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.ArchitectRefused;
import dev.larattalabs.architect.api.Busy;
import dev.larattalabs.architect.api.DeltaVerdict;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.architect.api.Refusal;
import dev.larattalabs.architect.api.RemovalFinished;
import dev.larattalabs.architect.api.RemovalStopCause;
import dev.larattalabs.architect.api.RemovalWait;
import dev.larattalabs.architect.api.RemoveOptions;
import dev.larattalabs.architect.api.RemoveResult;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.Settlement.Kind;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.util.DurableWrite;
import dev.larattalabs.steward.service.FakeArchitect.Answer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (T4R-RETRY) The calls Architect refuses as busy that Steward asks again by itself ({@link BusyRetries}: rows 1, 7, and the registry rows 5 and 12
 * share), a group's or village's ticket wait said once ({@link RemovalWaits}), a growth's revision that finished by itself logged and said once with its
 * refund ({@link RevisionEnds}), the unrecorded road said in its own words, and the update preview's busy words through {@link Updates#refresh}.
 */
class T4RRetryTest {
	private static final String OW = "minecraft:overworld";
	private static final Claim TOWN = new Claim(OW, 0, 0, 64, -64, 320);
	private static final Set<String> T4 = Set.of("regions", "growDistrict", "regionAdopt", "kitVersion", "sitesLoadHealthy", RemovalEnds.FEATURE,
		"removalStopCause");
	private static int serial;

	@TempDir
	Path dir;
	Path file;
	FakeArchitect a;
	final List<String> said = new ArrayList<>();
	final List<String> later = new ArrayList<>();
	/** Timed releases, in order (run by the test, as their delay passing). */
	final List<Runnable> timers = new ArrayList<>();
	final List<Long> delays = new ArrayList<>();

	@BeforeEach
	void setUp() {
		a = new FakeArchitect();
		a.install();
		file = dir.resolve("steward-settlements.json");
		Settlements.useForTest(new SettlementStore(), file);
		Undo.later = Runnable::run;
		BusyRetries.clear();
		BusyRetries.later = (ms, r) -> { delays.add(ms); timers.add(r); };
		BusyRetries.onServer = Runnable::run;
		Undo.retrySay = (id, line) -> later.add(line);
		Districts.retrySay = (id, line) -> later.add(line);
		RemovalWaits.later = (ms, r) -> timers.add(r);
		BusyRetries.busyCheck = Undo::busy;
	}

	@AfterEach
	void tearDown() {
		Undo.later = Undo.LATER;
		BusyRetries.clear();
		BusyRetries.later = BusyRetries.DELAYED;
		BusyRetries.onServer = BusyRetries.ON_SERVER;
		Undo.retrySay = Undo.VOICE;
		Districts.retrySay = Undo.VOICE;
		RemovalWaits.later = BusyRetries.DELAYED;
		DurableWrite.FAIL_NEXT.clear();
		BusyRetries.busyCheck = SettlementRunner::busy;
		ArchitectAccess.reset();
		Settlements.resetForTest();
	}

	private static String nextId() {
		return "set_t4rr" + (++serial);
	}

	private Settlement put(Settlement s) throws IOException {
		Settlements.store().put(s);
		Settlements.store().save(file);
		return s;
	}

	private Settlement now(String id) {
		return Settlements.store().get(id).orElseThrow();
	}

	private static long count(Settlement s, Kind k) {
		return s.log().stream().filter(e -> e.kind() == k).count();
	}

	/** A project of {@code ids}, each built from the stockpile (2 oak each, drawn), so a removal's refund can be summed. */
	private Settlement project(String id, String group, String... ids) throws IOException {
		List<Settlement.SiteChange> changes = new ArrayList<>();
		for (String sid : ids) changes.add(new Settlement.SiteChange(sid, "house", 0, 1));
		Settlement s = Settlement.founded(id, "Sea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed 3 houses", changes, group, Settlement.Outcome.DONE));
		for (String sid : ids) {
			a.site(sid, s.owner(), null);
			a.ledger.bills.put(sid, Map.of("oak", 2));
			a.ledger.add("oak", 2);
			assertTrue(a.ledger.build(a, sid));
		}
		a.siteGroups.put(group, new ArrayList<>(List.of(ids)));
		return put(s);
	}

	private static Settlement.LogEntry placing(Settlement s) {
		return s.log().stream().filter(e -> e.kind() == Kind.PROJECT_PLACED).reduce((x, y) -> y).orElseThrow();
	}

	private static RemovalFinished finished(String kind, String id, String stage) {
		return new RemovalFinished(kind, id, stage, null, new RemoveResult(true, List.of(), Map.of()));
	}

	/** Items in == items out: the stockpile plus what still stands is what was drawn at the start, and nothing was refunded twice. */
	private void conserved(int total) {
		int standing = 0;
		for (var s : a.sites.values()) {
			var d = a.ledger.drawn.get(FakeArchitect.Ledger.key(s));
			if (d != null && !a.ledger.refunded.contains(FakeArchitect.Ledger.key(s))) standing += d.getOrDefault("oak", 0);
		}
		assertEquals(total, a.ledger.stock.getOrDefault("oak", 0) + standing, "stockpile + world");
		assertEquals(a.ledger.paid - a.ledger.returned, standing, "ledger: paid - returned == what stands");
	}

	private void chain(Settlement s) {
		Undo.mark(s.id(), true);
		var e = placing(s);
		List<String> ids = new ArrayList<>(e.siteIds());
		java.util.Collections.reverse(ids);
		Undo.chainUndo(null, ArchitectAccess.api().sites(null), s, e, ids, false, Map.of(), new RemoveOptions(false, s.owner()), said::add);
	}

	// ------------------------------------------------------------------ the registry

	@Test
	void aWaitIsReleasedOnceByItsOwnBlockersEndOrItsTimerNeverBoth() {
		int[] ran = new int[1];
		var h = SiteBusy.of(new ArchitectRefused(Reason.SITE_BUSY, "x", new Busy(Busy.Kind.REMOVAL_IN_PROGRESS, "rg_1", "region", null)));
		var step = BusyRetries.wait("k", h, () -> ran[0]++, false);
		assertTrue(step.first());
		assertEquals(BusyRetries.REMOVAL_MS, step.retryAfterMs());
		assertFalse(BusyRetries.removalFinished(finished("region", "rg_2", null)), "another removal's end");
		assertFalse(BusyRetries.removalFinished(finished("group", "rg_1", null)), "a group's end with the region's id");
		assertEquals(0, ran[0]);
		assertTrue(BusyRetries.removalFinished(finished("region", "rg_1", null)));
		assertEquals(1, ran[0]);
		assertFalse(BusyRetries.removalFinished(finished("region", "rg_1", null)), "a replayed event runs nothing more");
		timers.forEach(Runnable::run);
		assertEquals(1, ran[0], "its timer after the event runs nothing (single flight)");
		// a wait behind another change: its timer only, never an event
		var op = SiteBusy.of(new ArchitectRefused(Reason.SITE_BUSY, "x", new Busy(Busy.Kind.OPERATION_IN_PROGRESS, "tx-1", "crate.settle", null)));
		assertEquals(BusyRetries.SOON_MS, BusyRetries.wait("o", op, () -> ran[0]++, false).retryAfterMs());
		assertFalse(BusyRetries.removalFinished(finished("site", "tx-1", null)));
		timers.get(timers.size() - 1).run();
		assertEquals(2, ran[0]);
		// forgotten (the player asked anew): its timer does nothing
		BusyRetries.wait("f", h, () -> ran[0]++, false);
		BusyRetries.forget("f");
		timers.get(timers.size() - 1).run();
		assertFalse(BusyRetries.removalFinished(finished("region", "rg_1", null)));
		assertEquals(2, ran[0]);
	}

	@Test
	void timedTriesAreBoundedThenSaidOnceAndOnlyAWaitBehindARemovalKeepsItsEvent() {
		var h = SiteBusy.of(new ArchitectRefused(Reason.SITE_BUSY, "x", new Busy(Busy.Kind.REMOVAL_IN_PROGRESS, "rg_1", "region", null)));
		int timed = 0, gaveUp = 0, first = 0;
		for (int i = 0; i < 40; i++) {
			var step = BusyRetries.wait("k", h, () -> { }, true);
			if (step.first()) first++;
			if (step.gaveUp()) gaveUp++;
			if (step.retryAfterMs() >= 0) timed++;
		}
		assertEquals(BusyRetries.MAX_TRIES, timed, "a bounded number of timed tries");
		assertEquals(1, gaveUp, "said once past the bound");
		assertEquals(1, first);
		assertTrue(BusyRetries.waiting("k"), "kept for its blocker's end");
		var op = SiteBusy.of(new ArchitectRefused(Reason.SITE_BUSY, "x", new Busy(Busy.Kind.OPERATION_IN_PROGRESS, "tx", "t", null)));
		for (int i = 0; i <= BusyRetries.MAX_TRIES; i++) BusyRetries.wait("o", op, () -> { }, true);
		assertFalse(BusyRetries.waiting("o"), "no event follows another change: dropped past the bound");
		for (int i = 0; i <= BusyRetries.MAX_TRIES; i++) BusyRetries.wait("u", h, () -> { }, false);
		assertFalse(BusyRetries.waiting("u"), "without keepForEvent: dropped");
	}

	@Test
	void aWaitFromAnEarlierWorldSessionIsNeverRun() {
		int[] ran = new int[1];
		var h = SiteBusy.of(new ArchitectRefused(Reason.SITE_BUSY, "x", new Busy(Busy.Kind.REMOVAL_IN_PROGRESS, "rg_1", "region", null)));
		BusyRetries.wait("k", h, () -> ran[0]++, false);
		Session.nextForTest();
		assertFalse(BusyRetries.removalFinished(finished("region", "rg_1", null)));
		timers.forEach(Runnable::run);
		assertEquals(0, ran[0]);
	}

	// ------------------------------------------------------------------ row 1: a site-by-site undo

	@Test
	void aBusySiteBySiteUndoWaitsAndCarriesOnOnceItsBlockerFinishesRefundingOnce() throws IOException {
		a.features = T4;
		a.loadHealthy = true;
		String id = nextId();
		Settlement s = project(id, "grp_r1", "c1", "c2", "c3");
		a.siteAnswers.put("c2", Answer.BUSY);
		chain(s);
		String all = String.join("\n", said);
		assertTrue(all.contains("Undo waits after 1 of 3 sites: a build nearby is still being taken down next to it. It carries on by itself once it's cleared"
			+ " (if the world closes first, run /steward undo " + id + " again)."), all);
		assertFalse(all.contains("SENTINEL") || all.contains("Undo stopped"), all);
		assertEquals(List.of("c3"), now(id).log().stream().filter(x -> x.kind() == Kind.PROJECT_REMOVED).findFirst().orElseThrow().siteIds());
		assertTrue(Undo.interruptedEntries(now(id)).isEmpty(), "the intent is closed while it waits: nothing in flight");
		assertTrue(BusyRetries.waiting(Undo.retryKey(id)));
		assertFalse(Undo.busy(id), "not marked busy while it waits");
		conserved(6);

		// the blocker (grp_other, the fake's busy) finishes: asked again, by itself
		a.siteAnswers.remove("c2");
		a.removeAnswer = Answer.APPLY;
		assertTrue(BusyRetries.removalFinished(finished("group", "grp_other", null)));
		var after = now(id);
		assertEquals(2, count(after, Kind.PROJECT_REMOVED), String.valueOf(later));
		assertTrue(Undo.retired(after, placing(after)), "the whole project taken down");
		assertTrue(String.join("\n", later).contains("Undone: removed"), String.valueOf(later));
		assertEquals(1, a.count("sites.remove c1"));
		assertFalse(BusyRetries.waiting(Undo.retryKey(id)));
		conserved(6);
		assertEquals(6, a.ledger.returned, "refunded once");
		// the event replayed: nothing more
		assertFalse(BusyRetries.removalFinished(finished("group", "grp_other", null)));
		assertEquals(1, a.count("sites.remove c1"));
		assertEquals(2, count(now(id), Kind.PROJECT_REMOVED));
		conserved(6);
	}

	@Test
	void aBlockerThatStopsWithoutFinishingIsAskedAgainByTimeThenSaidOnce() throws IOException {
		a.features = T4;
		a.loadHealthy = true;
		String id = nextId();
		Settlement s = project(id, "grp_r2", "d1", "d2");
		a.siteAnswers.put("d2", Answer.BUSY);
		chain(s);
		assertEquals(1, said.size(), String.valueOf(said));
		// the blocker stops: no event; each timer asks again, Architect refuses again, nothing more said until the bound
		for (int i = 0; i < 30 && !timers.isEmpty(); i++) timers.remove(0).run();
		assertEquals(BusyRetries.MAX_TRIES, delays.size(), "bounded timed tries");
		assertEquals(BusyRetries.MAX_TRIES + 1, a.count("sites.remove d2"));
		assertEquals(1, later.size(), "said once more, past the bound: " + later);
		assertTrue(later.get(0).startsWith("Undo stopped after 0 of 2 sites: a build nearby is still being taken down next to it. Run /steward undo " + id),
			later.get(0));
		assertFalse(BusyRetries.waiting(Undo.retryKey(id)), "no wait left: the player asks again");
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED));
		assertTrue(Undo.interruptedEntries(now(id)).isEmpty());
		conserved(4);
	}

	@Test
	void aRetryWhileTheSettlementIsBusyWaitsAgainAndOneAfterSomethingNewerTakesNothing() throws IOException {
		a.features = T4;
		a.loadHealthy = true;
		String id = nextId();
		Settlement s = project(id, "grp_r3", "e1", "e2");
		a.siteAnswers.put("e2", Answer.BUSY);
		chain(s);
		a.siteAnswers.remove("e2");
		// the player's own undo runs meanwhile: the retry never starts a second chain
		Undo.mark(id, true);
		BusyRetries.removalFinished(finished("group", "grp_other", null));
		assertEquals(1, a.count("sites.remove e2"), "no second chain while an undo runs");
		assertTrue(BusyRetries.waiting(Undo.retryKey(id)), "waits again, counted");
		Undo.mark(id, false);
		// something newer is placed before it is asked again: undo goes newest first, so nothing is taken down
		put(now(id).withLog(Settlement.LogEntry.of(9L, Kind.PROJECT_PLACED, "Placed a well", List.of(new Settlement.SiteChange("w1", "well", 0, 1)), "grp_w",
			Settlement.Outcome.DONE)));
		a.site("w1", s.owner(), null);
		timers.get(timers.size() - 1).run();
		assertEquals(1, a.count("sites.remove e2"));
		assertEquals(0, a.count("sites.remove w1"));
		assertTrue(later.stream().anyMatch(l -> l.startsWith(Undo.RETRY_NEWER)), String.valueOf(later));
		assertFalse(BusyRetries.waiting(Undo.retryKey(id)));
		conserved(4);
	}

	@Test
	void aRetryWhoseIntentCanNotBeSavedAsksArchitectNothing() throws IOException {
		a.features = T4;
		a.loadHealthy = true;
		String id = nextId();
		Settlement s = project(id, "grp_r4", "f1", "f2");
		a.siteAnswers.put("f2", Answer.BUSY);
		chain(s);
		a.siteAnswers.remove("f2");
		// the retry's intent save fails: persist before mutate
		DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME);
		BusyRetries.removalFinished(finished("group", "grp_other", null));
		assertEquals(1, a.count("sites.remove f2"), "no removal without its intent");
		assertTrue(later.contains(Undo.notStarted(id)), String.valueOf(later));
		assertTrue(a.sites.containsKey("f1") && a.sites.containsKey("f2"));
		conserved(4);
	}

	// ------------------------------------------------------------------ crash points of a retry

	@Test
	void aCrashWhileItWaitsLeavesNothingOpenAndTheNextUndoTakesTheRestOnce() throws IOException {
		a.features = T4;
		a.loadHealthy = true;
		String id = nextId();
		Settlement s = project(id, "grp_c1", "g1", "g2", "g3");
		a.siteAnswers.put("g2", Answer.BUSY);
		chain(s);
		// crash: the world stops (the wait is this session's memory) and starts again from the file
		Session.nextForTest();
		BusyRetries.clear();
		Settlements.useForTest(SettlementStore.load(file), file);
		a.siteAnswers.remove("g2");
		assertFalse(BusyRetries.removalFinished(finished("group", "grp_other", null)), "nothing waits after the restart");
		assertFalse(Undo.finishInterrupted(null, now(id)), "no open intent for the sweep to take a later removal for");
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		conserved(6);
		// the player asks again: the rest, logged once
		chain(now(id));
		assertEquals(2, count(now(id), Kind.PROJECT_REMOVED));
		assertTrue(Undo.retired(now(id), placing(now(id))));
		assertEquals(1, a.count("sites.remove g3"), "the site taken first is never asked again");
		conserved(6);
		assertEquals(6, a.ledger.returned);
	}

	@Test
	void aCrashAfterTheRetrysIntentIsFinishedOnceByTheSweepAndASecondCrashChangesNothing() throws IOException {
		a.features = T4;
		a.loadHealthy = true;
		String id = nextId();
		Settlement s = project(id, "grp_c2", "h1", "h2");
		a.siteAnswers.put("h2", Answer.BUSY);
		chain(s);
		// the retry's removal is in flight when the world stops (its intent saved first)
		a.siteAnswers.put("h2", Answer.HOLD);
		BusyRetries.removalFinished(finished("group", "grp_other", null));
		assertEquals(1, Undo.interruptedEntries(now(id)).size(), "the retry's intent is on disk before Architect was asked");
		Session.nextForTest();
		BusyRetries.clear();
		Undo.mark(id, false);
		Settlements.useForTest(SettlementStore.load(file), file);
		// Architect finishes it after the restart; then the sites read gone
		a.complete();
		a.siteAnswers.remove("h2");
		a.sites.remove("h1");
		a.ledger.refund(new FakeArchitect.Site("h1", s.owner()));
		assertTrue(Undo.finishInterrupted(null, now(id)), "the sweep logs what the retry took down");
		assertTrue(Undo.retired(now(id), placing(now(id))));
		long removed = count(now(id), Kind.PROJECT_REMOVED);
		// a second crash, the sweep again: nothing more
		Session.nextForTest();
		Settlements.useForTest(SettlementStore.load(file), file);
		assertFalse(Undo.finishInterrupted(null, now(id)));
		assertEquals(removed, count(now(id), Kind.PROJECT_REMOVED));
		conserved(4);
		assertEquals(4, a.ledger.returned, "refunded once each");
	}

	// ------------------------------------------------------------------ row 7: an unrecorded road

	@Test
	void anUnrecordedRoadIsSaidInItsOwnWordsAndTakenUpByItselfOnceItsBlockerFinishes() throws IOException {
		a.features = T4;
		a.loadHealthy = true;
		String id = nextId();
		Settlement s = put(Settlement.founded(id, "Sea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L));
		a.site("rd_SENTINEL7", s.owner(), null).ext(Districts.INFRA, Districts.FOUNDING_ROLE).kind = "road";
		a.siteAnswers.put("rd_SENTINEL7", Answer.BUSY);
		Districts.takeUpAllForTest(s, List.of("rd_SENTINEL7"), said::add);
		String all = String.join("\n", said);
		assertEquals(1, said.size(), all);
		assertTrue(all.startsWith("A road out to a new district was laid but never recorded: it is taken up once a build nearby next to it is cleared"), all);
		assertFalse(all.contains("SENTINEL"), "never the road's id: " + all);
		a.siteAnswers.remove("rd_SENTINEL7");
		a.removeAnswer = Answer.APPLY;
		assertTrue(BusyRetries.removalFinished(finished("group", "grp_other", null)));
		assertFalse(a.sites.containsKey("rd_SENTINEL7"));
		assertEquals(List.of("A road out to a new district was laid but never recorded: the road was taken up again."), later);
		assertFalse(BusyRetries.waiting(Districts.roadRetryKey(id, "rd_SENTINEL7")));
		// replayed: the road is gone, nothing asked
		BusyRetries.removalFinished(finished("group", "grp_other", null));
		assertEquals(2, a.count("sites.remove rd_SENTINEL7"));
	}

	@Test
	void aRoadSomethingElseRecordedMeanwhileIsNotTakenUp() throws IOException {
		a.features = T4;
		a.loadHealthy = true;
		String id = nextId();
		Settlement s = put(Settlement.founded(id, "Sea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L));
		a.site("rd_2", s.owner(), null).ext(Districts.INFRA, "something else").kind = "road";
		a.siteAnswers.put("rd_2", Answer.BUSY);
		Districts.takeUpAllForTest(s, List.of("rd_2"), said::add);
		assertTrue(said.get(0).startsWith("A district road was laid but never recorded"), said.get(0));
		a.siteAnswers.remove("rd_2");
		BusyRetries.removalFinished(finished("group", "grp_other", null));
		assertEquals(1, a.count("sites.remove rd_2"), "not a steward road: the retry asks nothing");
	}

	@Test
	void theRoadsWordsNeverNameItsId() {
		for (String role : new String[] { Districts.FOUNDING_ROLE, Districts.LINK_ROLE, null, "x" }) {
			String w = Districts.unrecordedRoad(role);
			assertTrue(w.endsWith("was laid but never recorded") && !w.contains("("), w);
		}
	}

	// ------------------------------------------------------------------ a group's or village's ticket wait, said once

	@Test
	void aGroupUndoWaitingOnItsTicketIsSaidOnceInTheStewardsWords() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = project(id, "grp_w1", "k1", "k2");
		a.removeAnswer = Answer.HOLD;
		Undo.mark(id, true);
		var e = placing(s);
		Undo.groupUndo(null, ArchitectAccess.api().sites(null), s, e, e.siteGroup(), e.siteIds(), Map.of(), new RemoveOptions(false, s.owner()), said::add);
		int before = said.size();
		// no wait yet: read again, silent
		timers.remove(0).run();
		assertEquals(before, said.size());
		a.groupWaits.put("grp_w1", new RemovalWait(RemovalStopCause.WAITING_BEHIND, new Busy(Busy.Kind.WAITING_BEHIND, "rg_hill", "region",
			"steward_mc:settlement/SENTINEL-gone"), 5L));
		timers.remove(0).run();
		assertEquals(before + 1, said.size());
		assertEquals("The undo waits for a build nearby to be taken down first, then carries on by itself. Nothing more to do.", said.get(said.size() - 1));
		assertTrue(timers.isEmpty(), "said once: no more reads");
		assertFalse(said.get(said.size() - 1).startsWith(Undo.HELD), "never the held note's prefix");
		a.complete();
		assertTrue(said.get(said.size() - 1).startsWith("Undone"), String.valueOf(said));
	}

	@Test
	void aRegionsTicketWaitIsReadABoundedNumberOfTimesAndNeverAfterItsAnswer() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = Settlement.founded(id, "Sea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed the village", List.of(new Settlement.SiteChange("v1", "hall", 0, 1)), "region:rg_w",
				Settlement.Outcome.DONE));
		a.region("rg_w", s.owner());
		a.site("v1", s.owner(), "rg_w");
		put(s);
		a.removeAnswer = Answer.HOLD;
		Undo.region(null, s, placing(s), "rg_w", Map.of(), said::add, x -> { }, true);
		int reads = 0;
		while (!timers.isEmpty() && reads < 1000) { timers.remove(0).run(); reads++; }
		assertEquals(RemovalWaits.MAX_CHECKS, reads, "bounded");
		// a second undo: its answer comes before the first read: never read after it
		String id2 = nextId();
		Settlement s2 = Settlement.founded(id2, "Bay", new Claim(OW, 1000, 1000, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed the bay", List.of(new Settlement.SiteChange("w1", "hall", 0, 1)), "region:rg_w2",
				Settlement.Outcome.DONE));
		a.region("rg_w2", s2.owner()).removalWait = new RemovalWait(RemovalStopCause.WAITING_BEHIND, new Busy(Busy.Kind.WAITING_BEHIND, "x", "region", null), 1L);
		a.site("w1", s2.owner(), "rg_w2");
		put(s2);
		a.removeAnswer = Answer.HOLD;
		said.clear();
		Undo.region(null, s2, placing(s2), "rg_w2", Map.of(), said::add, x -> { }, true);
		assertEquals(1, timers.size(), "one read scheduled");
		a.complete();
		timers.forEach(Runnable::run);
		assertTrue(said.stream().noneMatch(l -> l.startsWith("The undo waits")), "a read due after the answer says nothing: " + said);
	}

	// ------------------------------------------------------------------ a growth's revision that finished by itself

	@Test
	void aRevisionsRefundIsItsStagesSummedEventFirstAndTheOwnersRefundKindsOnly() {
		String owner = "steward_mc:settlement/sea";
		var ev = new RevisionEnds.Part<>(Map.of("oak", 3), List.of(new OwedParts.Part<>(owner, "group_refund", Map.of("glass", 1))));
		Map<String, RevisionEnds.Part<String>> snaps = Map.of(
			"ground", new RevisionEnds.Part<>(Map.of("dirt", 5), List.of(new OwedParts.Part<>(owner, "player_blocks", Map.of("sponge", 1)))),
			// the event's own stage: its snapshot never counted beside the event's result
			"houses", new RevisionEnds.Part<>(Map.of("oak", 99), List.of()));
		var b = RevisionEnds.back(List.of("ground", "houses", "houses", "evicted"), "houses", ev, snaps::get);
		assertEquals(Map.of("oak", 3, "dirt", 5), b.refund());
		String line = RevisionEnds.refundLine(owner, b, m -> new TreeMap<>(m).toString());
		assertEquals("Refunded: {dirt=5, oak=3}. Owed to you: {glass=1} (take them from the stockpile's Take, or /architect collect; they wait for you).", line);
		assertNull(RevisionEnds.refundLine(owner, RevisionEnds.back(List.of("evicted"), null, null, snaps::get), m -> m.toString()), "nothing came back");
		assertNull(RevisionEnds.refundLine(owner, RevisionEnds.back(null, null, null, snaps::get), m -> m.toString()));
	}

	/** A town whose growth (rid 2, stages east and west) was taken up by a held undo: its entry standing, the revision UNDONE. */
	private Settlement heldGrowth(String id) throws IOException {
		String tag = Growths.tag("rg_g", 2);
		Settlement s = Settlement.founded(id, "Sea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.TOWN_ADOPTED, "Took on the town", List.of(), "region:rg_g", Settlement.Outcome.DONE))
			.withLog(Settlement.LogEntry.of(3L, Kind.PROJECT_PLACED, "Added a market", List.of(new Settlement.SiteChange("m1", "market", 0, 1)), tag,
					Settlement.Outcome.DONE));
		var town = a.region("rg_g", s.owner());
		town.rev(2, 2, "UNDONE", "m1").stages.addAll(List.of("east", "west"));
		return put(s);
	}

	@Test
	void aRevisionsEventLogsItsGrowthOnceAndSaysItOnceWhateverReplaysOrTheSweepDo() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = heldGrowth(id);
		assertEquals(java.util.Optional.of("rg_g"), TownRegion.of(s), "the fixture's town");
		var lines = new ArrayList<String>();
		var e = new RemovalEnds.RemovalEnd("group", "grp_rg_g", "west", 2, true, Map.of(), List.of());
		RemovalEnds.handle(null, e, (sid, line) -> lines.add(line));
		assertEquals(1, now(id).log().stream().filter(x -> x.kind() == Kind.PROJECT_REMOVED && Growths.tag("rg_g", 2).equals(x.siteGroup())).count(),
			"logged once: " + lines);
		assertEquals(List.of("Undone: \"Added a market\" finished by itself, the land restored."), lines);
		assertEquals(1, a.count("sites.removalResult stage grp_rg_g/east"), "the other stage read from its snapshot");
		assertEquals(0, a.count("sites.removalResult stage grp_rg_g/west"), "the event's own stage from the event");
		// replayed (after a restart, until delivered), the other stage's event, the completion read again: nothing more said or logged
		RemovalEnds.handle(null, e, (sid, line) -> lines.add(line));
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("group", "grp_rg_g", "east", 2, true, Map.of(), List.of()), (sid, line) -> lines.add(line));
		assertEquals(Undo.Completion.ALREADY, Undo.completeNow(id, placing(now(id)), new Growths.Ref("rg_g", 2), Map.of()));
		// a second reader past its own check (the sweep and the event): the words follow only the append it made
		var town = ArchitectAccess.api().regions().get("rg_g").orElseThrow();
		assertEquals(Undo.Completion.ALREADY, RevisionEnds.finish(null, now(id), town, Growths.revision(town, 2).orElseThrow(),
			now(id).log().stream().filter(x -> x.kind() == Kind.PROJECT_PLACED).findFirst().orElseThrow(), e, (sid, line) -> lines.add(line)));
		assertEquals(1, lines.size(), String.valueOf(lines));
		assertEquals(1, now(id).log().stream().filter(x -> x.kind() == Kind.PROJECT_REMOVED).count());
	}

	@Test
	void aRevisionsEventIsLeftToWhatOwnsTheTakeUp() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = heldGrowth(id);
		assertEquals(java.util.Optional.of("rg_g"), TownRegion.of(s), "the fixture's town");
		Undo.mark(id, true);
		var lines = new ArrayList<String>();
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("group", "grp_rg_g", "west", 2, true, Map.of(), List.of()), (sid, line) -> lines.add(line));
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), "an undo answering itself logs it");
		assertTrue(lines.isEmpty());
		Undo.mark(id, false);
		// another revision's event, another group's: nothing
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("group", "grp_rg_g", "west", 7, true, Map.of(), List.of()), (sid, line) -> lines.add(line));
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("group", "grp_other", "west", 2, true, Map.of(), List.of()), (sid, line) -> lines.add(line));
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED));
		assertTrue(lines.isEmpty());
	}

	// ------------------------------------------------------------------ the update preview's busy words, through Updates.refresh

	@Test
	void aBusyPreviewIsAskedInTheStewardsWordsThroughTheScan() throws IOException {
		String id = nextId();
		Settlement s = put(Settlement.founded(id, "Sea", TOWN, Permission.AUTONOMOUS, Difficulty.PATRON, 1L));
		var site = a.site("st_u1", s.owner(), null).ext("steward_mc:role", "smithy");
		site.dimension = net.minecraft.resources.ResourceKey.create(net.minecraft.resources.ResourceKey.createRegistryKey(net.minecraft.resources.Identifier
			.withDefaultNamespace("dimension")), net.minecraft.resources.Identifier.withDefaultNamespace("overworld"));
		a.outdated.add(new dev.larattalabs.architect.api.OutdatedSite("st_u1", "bp_st_u1", 1, 2));
		a.deltaVerdict = new DeltaVerdict(false, List.of(new Refusal(Reason.SITE_BUSY, "Not yet: SENTINEL-RAW is still in progress over [1,2,3]", List.of(),
			"removal_in_progress:grp_9")), 0, 0, 0, Map.of(), List.of(), List.of(), Map.of(), Map.of(), null, dev.larattalabs.architect.api.Mode.INSTANT, List.of());
		Updates.refresh(null, s);
		var p = Updates.pending(id);
		assertEquals(1, p.size(), String.valueOf(p));
		assertEquals(dev.larattalabs.steward.gateway.UpdatePlanner.Action.ASK, p.get(0).action());
		assertEquals("smithy waits: a build nearby is still being taken down next to it; it can be done once that's cleared.", p.get(0).text());
		assertFalse(p.get(0).text().contains("SENTINEL"));
	}
}
