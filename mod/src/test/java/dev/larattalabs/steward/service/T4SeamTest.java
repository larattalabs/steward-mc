package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.ArchitectRefused;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.architect.api.RemovalStopCause;
import dev.larattalabs.architect.api.RemoveOptions;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.Settlement.Kind;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.service.FakeArchitect.Answer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (T4S-PRE) Steward's side of Architect T4, pre-built on 0.83.0: {@code siteBusy(err)} ({@link SiteBusy}), the held words ({@link RemovalStops}), the owed
 * lines (OVF-S3), the {@code loadHealthy()} guards where "gone" is read from absence, the group and site-by-site undo's intent note and sweep, and the
 * {@link RemovalEnds} seam {@code RemovalFinished} will reach (driven here as T4c will drive it, through FakeArchitect). With the feature
 * {@code removal_finished} absent (0.83.0) nothing changes.
 */
class T4SeamTest {
	private static final String OW = "minecraft:overworld";
	private static final Claim TOWN = new Claim(OW, 0, 0, 64, -64, 320);
	private static final Set<String> T4 = Set.of("regions", "growDistrict", "regionAdopt", "kitVersion", "sitesLoadHealthy", RemovalEnds.FEATURE);
	private static int serial;

	@TempDir
	Path dir;
	Path file;
	FakeArchitect a;
	final List<String> said = new ArrayList<>();

	@BeforeEach
	void setUp() {
		a = new FakeArchitect();
		a.install();
		file = dir.resolve("steward-settlements.json");
		Settlements.useForTest(new SettlementStore(), file);
		Undo.later = Runnable::run;
	}

	@AfterEach
	void tearDown() {
		Undo.later = Undo.LATER;
		ArchitectAccess.reset();
		Settlements.resetForTest();
	}

	private static String nextId() {
		return "set_t4s" + (++serial);
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

	// ------------------------------------------------------------------ siteBusy(err)

	@Test
	void siteBusyTellsDecidedFromOtherFromTheTypedDetailWhereverItIsInTheChain() {
		// T3a: detail() is the bare token, the place in the message
		var d = SiteBusy.of(new CompletionException(new ArchitectRefused(Reason.SITE_BUSY, "held(decided) tx-12: chunk -3, 17", "held(decided)")));
		assertTrue(d.decided());
		assertEquals(-3, d.chunkX());
		assertEquals(17, d.chunkZ());
		// T1's Refusal.detail form: the place after the detail's own colon
		var r = SiteBusy.of(new ArchitectRefused(Reason.SITE_BUSY, "SENTINEL", "held(decided) tx-2: chunk 4,5"));
		assertEquals(4, r.chunkX());
		assertEquals(5, r.chunkZ());
		// T4 change 7
		var records = SiteBusy.of(new ArchitectRefused(Reason.SITE_BUSY, "held(decided) tx-3: records unreadable", "held(decided)"));
		assertTrue(records.decided() && records.recordsUnreadable());
		assertNull(records.chunkX());
		assertTrue(SiteBusy.of(new RuntimeException(new ArchitectRefused(Reason.SITE_BUSY, "held(other) tx-9: crate 1,2,3", "held(other)"))).other());
		// a place it can't word is not named
		var player = SiteBusy.of(new ArchitectRefused(Reason.SITE_BUSY, "held(decided) tx-4: player Steve", "held(decided)"));
		assertTrue(player.decided() && player.chunkX() == null && !player.recordsUnreadable());
		// not held: today's busy, the txn journal (refused, nothing changed), another reason, no error
		assertFalse(SiteBusy.of(new ArchitectRefused(Reason.SITE_BUSY, "busy: undoing")).held());
		assertFalse(SiteBusy.of(new ArchitectRefused(Reason.JOURNAL_UNAVAILABLE, "txn.json: unreadable", "txn_journal")).held());
		assertFalse(SiteBusy.of(new ArchitectRefused(Reason.BILL_CAP, "x", "held(decided)")).held());
		assertFalse(SiteBusy.of((Throwable) null).held());
		assertTrue(SettlementRunner.siteBusy(new CompletionException(new ArchitectRefused(Reason.SITE_BUSY, "m", "held(decided)"))).decided());
	}

	@Test
	void heldWordsAreTheStewardsNeverAStopNeverArchitectsText() {
		var chunk = SiteBusy.of(new ArchitectRefused(Reason.SITE_BUSY, "held(decided) tx-7: chunk 3,-4 SENTINEL", "held(decided)"));
		assertEquals("Undo under way: it finishes by itself when chunk 3, -4 loads. Nothing more to do.", RemovalStops.underWay(chunk));
		var records = SiteBusy.of(new ArchitectRefused(Reason.SITE_BUSY, "held(decided) tx-7: records unreadable", "held(decided)"));
		assertTrue(RemovalStops.underWay(records).contains("Architect's records didn't load; it waits"), RemovalStops.underWay(records));
		for (String line : List.of(RemovalStops.underWay(chunk), RemovalStops.underWay(records), RemovalStops.growthUnderWay(chunk), RemovalStops.HELD_OTHER)) {
			assertFalse(line.startsWith("Undone") || line.startsWith("Undo stopped"), "the over-capacity line waits for the refund: " + line);
			assertFalse(line.contains("SENTINEL") || line.contains("tx-") || line.contains("held("), line);
		}
		assertEquals(RemovalStops.RECORDS_UNREADABLE, RefusalWords.of(new CompletionException(new ArchitectRefused(Reason.SITE_BUSY, "held(decided) tx-1: records unreadable",
			"held(decided)"))));
		// growthWaiting: a decided hold carries on by itself, never "when you ask again"
		String by = RemovalStops.growthWaiting(RemovalStopCause.PLAYER_IN_BOX, true);
		assertTrue(by.contains("carries on by itself") && !by.contains("ask again"), by);
		assertTrue(RemovalStops.growthWaiting(null, false).contains("when you ask again"), "without T4, as before");
	}

	// ------------------------------------------------------------------ OVF-S3: owed is listed apart, and "nothing came back" is never said then

	@Test
	void owedIsListedApartFromTheRefundAndNeverReadAsNothingCameBack() {
		java.util.function.Function<Map<String, Integer>, String> items = m -> String.join(", ", m.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).toList());
		Map<String, Integer> none = Map.of();
		Map<String, Integer> planks = new LinkedHashMap<>(Map.of("oak planks", 12));
		Map<String, Integer> glass = new LinkedHashMap<>(Map.of("glass", 3));
		assertEquals(Growths.NOTHING_BACK, Growths.refundLine(none, none, items));
		String owedOnly = Growths.refundLine(none, glass, items);
		assertTrue(owedOnly.startsWith("Owed to you: 3 glass"), owedOnly);
		assertFalse(owedOnly.contains("nothing came back"), owedOnly);
		assertEquals("Refunded: 12 oak planks.", Growths.refundLine(planks, none, items));
		String both = Growths.refundLine(planks, glass, items);
		assertTrue(both.startsWith("Refunded: 12 oak planks. Owed to you: 3 glass"), "owed never in the refund totals: " + both);
		// the empty take-up's completion note
		assertEquals(Growths.NOT_PLACED + " (cancelled)", Growths.notPlacedNote("cancelled", false, false));
		assertEquals(Growths.NOT_PLACED + " (cancelled; what it drew was refunded)", Growths.notPlacedNote("cancelled", true, false));
		assertTrue(Growths.notPlacedNote("cancelled", false, true).contains("owed to you"));
		assertTrue(Growths.notPlacedNote("x", false, true).startsWith(Growths.NOT_PLACED), "Growths.completed reads it");
	}

	// ------------------------------------------------------------------ loadHealthy() guards

	@Test
	void aCancelsTakeUpNeverReadsAnAbsentRecordAsGoneFromAStoreThatDidNotLoad() {
		assertTrue(SettlementRunner.takenUp(true, null, false, false), "answered removed");
		assertTrue(SettlementRunner.takenUp(false, RegionState.REMOVED, true, false), "its tombstone");
		assertFalse(SettlementRunner.takenUp(false, RegionState.REMOVING, true, true));
		assertTrue(SettlementRunner.takenUp(false, null, false, true), "absent, on a healthy read: gone (as before)");
		assertFalse(SettlementRunner.takenUp(false, null, false, false), "absent from a store that did not load: not gone");
	}

	/** A project of sites {@code ids} in site group {@code group}, placed by one entry. */
	private Settlement project(String id, String group, String... ids) throws IOException {
		List<Settlement.SiteChange> changes = new ArrayList<>();
		for (String sid : ids) changes.add(new Settlement.SiteChange(sid, "house", 0, 1));
		Settlement s = Settlement.founded(id, "Sea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed 3 houses", changes, group, Settlement.Outcome.DONE));
		for (String sid : ids) a.site(sid, s.owner(), null);
		a.siteGroups.put(group, new ArrayList<>(List.of(ids)));
		return put(s);
	}

	private static Settlement.LogEntry placing(Settlement s) {
		return s.log().stream().filter(e -> e.kind() == Kind.PROJECT_PLACED).reduce((x, y) -> y).orElseThrow();
	}

	private void group(Settlement s) {
		Undo.mark(s.id(), true);
		var e = placing(s);
		Undo.groupUndo(null, ArchitectAccess.api().sites(null), s, e, e.siteGroup(), e.siteIds(), Map.of(), new RemoveOptions(false, s.owner()), said::add);
	}

	private void chain(Settlement s) {
		Undo.mark(s.id(), true);
		var e = placing(s);
		List<String> ids = new ArrayList<>(e.siteIds());
		java.util.Collections.reverse(ids);
		Undo.chainUndo(null, ArchitectAccess.api().sites(null), s, e, ids, false, Map.of(), new RemoveOptions(false, s.owner()), said::add);
	}

	@Test
	void aGroupUndoRefusedOnAStoreThatDidNotLoadLogsNothingAsGone() throws IOException {
		String id = nextId();
		Settlement s = project(id, "grp_a", "a1", "a2", "a3");
		a.loadHealthy = false;
		a.removeAnswer = Answer.REFUSE;
		group(s);
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), "a sites read that lists nothing is no proof anything is gone: " + said);
		assertTrue(String.join("\n", said).contains("Undo stopped after 0 of 3 sites"), String.valueOf(said));
		assertFalse(Undo.busy(id));
	}

	@Test
	void aSiteBySiteUndoStopsRatherThanTakeAnAbsentSiteForGoneOnAStoreThatDidNotLoad() throws IOException {
		String id = nextId();
		Settlement s = project(id, "grp_b", "b1", "b2");
		a.loadHealthy = false;
		chain(s);
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), String.valueOf(said));
		assertEquals(0, a.count("sites.remove"));
		assertTrue(String.join("\n", said).contains(Undo.RECORDS_STOP), String.valueOf(said));
	}

	@Test
	void withoutT4TheGroupUndoLeavesNoNoteAndLogsAsBefore() throws IOException {
		String id = nextId();
		Settlement s = project(id, "grp_c", "c1", "c2");
		int before = now(id).log().size();
		group(s);
		assertEquals(before + 1, now(id).log().size(), "only the removal (no intent note on 0.83.0)");
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(0, count(now(id), Kind.NOTE));
	}

	// ------------------------------------------------------------------ the group and site-by-site intent, held, and the sweep

	@Test
	void aGroupUndoDecidedAndHeldIsUnderWayAndTheSweepLogsItOnceWhenItFinishes() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = project(id, "grp_d", "d1", "d2", "d3");
		a.removeAnswer = Answer.HELD_DECIDED;
		group(s);
		String all = String.join("\n", said);
		assertTrue(all.contains("Undo under way: it finishes by itself when chunk 3, -4 loads"), all);
		assertFalse(all.contains("Undo stopped") || all.contains("SENTINEL"), all);
		assertFalse(Undo.busy(id), "released");
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), "no FAILED, no PARTIAL");
		var open = Undo.interruptedEntries(now(id));
		assertEquals(1, open.size());
		assertTrue(open.get(0).held());
		// still standing: the sweep waits
		assertFalse(Undo.finishInterrupted(null, now(id)));
		a.complete();
		assertTrue(Undo.finishInterrupted(null, now(id)));
		assertFalse(Undo.finishInterrupted(null, now(id)), "once");
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(Settlement.Outcome.DONE, now(id).log().stream().filter(e -> e.kind() == Kind.PROJECT_REMOVED).findFirst().orElseThrow().outcome());
		assertEquals(List.of(), Undo.interruptedEntries(now(id)));
		assertEquals(1, a.count("sites.removeGroup grp_d"), "never asked again");
	}

	@Test
	void aGroupUndoStoppedByBlockersOnT4TookNothingDownAndClosesItsNote() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = project(id, "grp_e", "e1", "e2");
		a.removeAnswer = Answer.BLOCKED;
		// a site gone meanwhile is never read as taken down by this undo: T4 decides blockers before it begins
		a.sites.remove("e1");
		group(s);
		String all = String.join("\n", said);
		assertTrue(all.contains("nothing was taken down"), all);
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(List.of(), Undo.interruptedEntries(now(id)), "closed: a later removal by something else is never taken for it");
	}

	@Test
	void aSiteBySiteUndoHeldPartWayLogsWhatWentOnceItFinishesAndClosesTheRest() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = project(id, "grp_f", "f1", "f2", "f3");
		// newest first: f3 removed, f2 decided and held, f1 never asked
		a.siteAnswers.put("f2", Answer.HELD_DECIDED);
		chain(s);
		assertTrue(String.join("\n", said).contains("Undo under way"), String.valueOf(said));
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), "nothing logged while held");
		assertEquals("f2", Undo.interruptedEntries(now(id)).get(0).waitsFor());
		assertFalse(Undo.finishInterrupted(null, now(id)), "f2 still stands");
		a.complete();
		said.clear();
		var lines = new ArrayList<String>();
		assertEquals(1, Undo.finish(null, now(id), null, null, (sid, line) -> lines.add(line)).size());
		var removed = now(id).log().stream().filter(e -> e.kind() == Kind.PROJECT_REMOVED).toList();
		assertEquals(1, removed.size());
		assertEquals(Set.of("f2", "f3"), new HashSet<>(removed.get(0).siteIds()));
		assertEquals(Settlement.Outcome.PARTIAL, removed.get(0).outcome());
		assertTrue(lines.get(0).startsWith("Undone in part: 2 sites"), String.valueOf(lines));
		assertEquals(List.of(), Undo.interruptedEntries(now(id)), "closed");
		assertTrue(Undo.finish(null, now(id), null, null, (sid, line) -> lines.add(line)).isEmpty(), "never logged twice");
	}

	@Test
	void aSweepWaitsForAStoreThatDidNotLoad() {
		assertNull(Undo.finished(List.of("x1", "x2"), null, Set.of(), false, sid -> true), "absent on an unhealthy read: wait");
		assertEquals(List.of("x1", "x2"), Undo.finished(List.of("x1", "x2"), null, Set.of(), true, sid -> true));
		assertEquals(List.of("x1", "x2"), Undo.finished(List.of("x1", "x2"), null, Set.of("x1", "x2"), false, sid -> false), "Architect's positive answer");
		assertNull(Undo.finished(List.of("x1", "x2"), "x2", Set.of(), true, sid -> sid.equals("x1")), "the held site still stands");
		assertEquals(List.of("x2"), Undo.finished(List.of("x1", "x2"), "x2", Set.of("x2"), true, sid -> false));
	}

	// ------------------------------------------------------------------ the RemovalFinished seam

	@Test
	void removalFinishedLogsAHeldRegionOnceAndSaysItOnce() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = Settlement.founded(id, "Sea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed the village", List.of(new Settlement.SiteChange("v1", "hall", 0, 1)), "region:rg_r",
				Settlement.Outcome.DONE));
		a.region("rg_r", s.owner());
		a.site("v1", s.owner(), "rg_r");
		put(s);
		a.removeAnswer = Answer.HELD_DECIDED;
		var done = new AtomicReference<Boolean>();
		var held = new AtomicReference<Boolean>(false);
		Undo.region(null, s, placing(s), "rg_r", Map.of(), said::add, done::set, false, () -> held.set(true));
		assertTrue(held.get(), "a cancel's held path, not its stopped one");
		assertNull(done.get());
		a.complete();
		var lines = new ArrayList<String>();
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("region", "rg_r", true, Map.of(), List.of()), (sid, line) -> lines.add(sid + ": " + line));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(1, lines.size(), String.valueOf(lines));
		assertTrue(lines.get(0).startsWith(id + ": Undone: \"Placed the village\" finished by itself"), lines.get(0));
		// it fires at least once: a re-fire after recovery logs and says nothing more
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("region", "rg_r", true, Map.of(), List.of()), (sid, line) -> lines.add(line));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(1, lines.size());
	}

	@Test
	void removalFinishedIsPositiveEvidenceWhereAbsenceIsNot() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = project(id, "grp_g", "g1", "g2");
		a.removeAnswer = Answer.HELD_DECIDED;
		group(s);
		a.complete();
		a.loadHealthy = false;
		assertFalse(Undo.finishInterrupted(null, now(id)), "the sweep can't tell from a store that did not load");
		var lines = new ArrayList<String>();
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("group", "grp_g", true, Map.of(), List.of()), (sid, line) -> lines.add(line));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED), "the event's own answer");
		// another group's end, or an aborted removal, logs nothing
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("group", "grp_other", true, Map.of(), List.of()), (sid, line) -> lines.add(line));
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("group", "grp_g", false, Map.of(), List.of()), (sid, line) -> lines.add(line));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(1, lines.size());
	}

	@Test
	void removalFinishedForAnUnheldRemovalLeavesItToItsOwnAnswerLoggedOnce() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = project(id, "grp_u", "u1", "u2");
		// T4 fires RemovalFinished for an unheld removal too, at its EFFECTS_DONE: it may come before the future's answer
		a.removeAnswer = Answer.HOLD;
		group(s);
		var lines = new ArrayList<String>();
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("group", "grp_u", true, Map.of(), List.of()), (sid, line) -> lines.add(line));
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), "not held: the undo's own answer logs it");
		a.complete();
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("group", "grp_u", true, Map.of(), List.of()), (sid, line) -> lines.add(line));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED), "once");
		assertEquals(List.of(), lines, "the event says nothing for it");
		assertEquals(1, said.stream().filter(l -> l.startsWith("Undone")).count(), String.valueOf(said));
	}

	@Test
	void aHeldOtherThatNeverClearsEndsAsTheCallsRefusal() throws IOException {
		String id = nextId();
		Settlement s = project(id, "grp_h", "h1");
		a.removeAnswer = Answer.HELD_OTHER;
		a.otherTimes = 99;
		group(s);
		assertEquals(Undo.OTHER_TRIES, a.count("sites.removeGroup grp_h"), "asked a bounded number of times");
		// (T4RD-S) said by its busy kind: another change holds it, nothing was taken down, ask again in a moment
		assertTrue(String.join("\n", said).contains("Undo stopped: Architect is still finishing another change there, so nothing was taken down. Run /steward undo "
			+ id + " again in a moment."), String.valueOf(said));
		assertFalse(String.join("\n", said).contains("SENTINEL"));
		assertFalse(Undo.busy(id));
	}
}
