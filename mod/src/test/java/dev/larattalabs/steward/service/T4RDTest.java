package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.ArchitectRefused;
import dev.larattalabs.architect.api.Busy;
import dev.larattalabs.architect.api.DeltaResult;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.architect.api.Refusal;
import dev.larattalabs.architect.api.RemovalStop;
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
 * (T4RD-S) Steward's side of Architect T4R-D: the typed busy refusal ({@code ArchitectRefused.busy()}, its detail token) read by one rule
 * ({@link SiteBusy}), said by kind in the steward's words ({@link RemovalStops}, {@link RefusalWords}), never a FAILED update or revert
 * ({@link Updates.BusyUpdates}, {@link Revert#entry}); the owed parts said only by the owner-and-refund-reason rule ({@link OwedParts}); the
 * RemovalFinished adapter's one match ({@link Undo#endedBy}) and the sweep's recovered result ({@code Sites.removalResult}), said once.
 */
class T4RDTest {
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
		return "set_t4rd" + (++serial);
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

	private static Busy busy(Busy.Kind k, String id, String kind, String owner) {
		return new Busy(k, id, kind, owner);
	}

	private static ArchitectRefused refused(Busy b) {
		return new ArchitectRefused(Reason.SITE_BUSY, "Not yet: SENTINEL-RAW over [1,2,3]", b);
	}

	// ------------------------------------------------------------------ one rule from a typed busy to what Steward does

	@Test
	void aTypedBusyIsReadByItsKindNeverItsText() {
		var w = SiteBusy.of(new CompletionException(refused(busy(Busy.Kind.REMOVAL_IN_PROGRESS, "grp_9", "group", "steward_mc:settlement/x"))));
		assertTrue(w.waits(), "another removal in the way: wait for its end");
		assertEquals("grp_9", w.blockingId());
		assertEquals("group", w.blockingKind());
		assertTrue(SiteBusy.of(refused(busy(Busy.Kind.WAITING_BEHIND, "rg_1", "region", null))).waits());
		assertTrue(SiteBusy.of(refused(busy(Busy.Kind.HELD_DECIDED, "st_1", "site", null))).decided(), "a pending member of a decided removal");
		assertTrue(SiteBusy.of(refused(busy(Busy.Kind.OPERATION_IN_PROGRESS, "tx-4", "crate.settle", null))).other(), "another operation: retried soon");
		assertTrue(SiteBusy.of(refused(busy(Busy.Kind.PLAYER_BLOCKS_RETURNING, "ext_1", "extraction", null))).other(), "a road's player blocks: retried soon");
		// an unknown kind is "retry soon", never a wait for an event (Architect: append-only)
		assertEquals(SiteBusy.Busy.RETRY_SOON, SiteBusy.busyOf("SOMETHING_NEW"));
		assertEquals(SiteBusy.Kind.OTHER, SiteBusy.kindOf(SiteBusy.busyOf("SOMETHING_NEW")));
		// the detail token where only a Refusal's detail reaches Steward (applyDelta, revert)
		var t = SiteBusy.of(new Refusal(Reason.SITE_BUSY, "SENTINEL-RAW", List.of(), "removal_in_progress:grp_3"));
		assertTrue(t.waits());
		assertEquals("grp_3", t.blockingId());
		assertTrue(SiteBusy.of(new Refusal(Reason.SITE_BUSY, "x", List.of(), "waiting_behind:rg_2")).waits());
		assertTrue(SiteBusy.of(new Refusal(Reason.SITE_BUSY, "x", List.of(), "operation_in_progress:tx-1")).other());
		assertTrue(SiteBusy.of(new Refusal(Reason.SITE_BUSY, "x", List.of(), "held(decided)")).decided());
		// today's untyped busy, another reason, an unknown token: not typed (the caller's own words)
		assertFalse(SiteBusy.of(new Refusal(Reason.SITE_BUSY, "x", List.of(), "")).busyAtAll());
		assertFalse(SiteBusy.of(new Refusal(Reason.SITE_BUSY, "x", List.of(), "frobnicate:x")).busyAtAll());
		assertFalse(SiteBusy.of(new Refusal(Reason.OVERLAP, "x", List.of(), "removal_in_progress:grp_3")).busyAtAll());
	}

	// ------------------------------------------------------------------ the words, by kind

	@Test
	void theBlockerIsNamedOnlyWhenStewardCanSayItInItsOwnWords() {
		String owner = "steward_mc:settlement/sea";
		java.util.function.Function<String, String> lots = id -> id.equals("st_1") ? "smithy" : null;
		java.util.function.Function<String, String> towns = id -> id.equals("hill") ? "Hillford" : null;
		var own = new SiteBusy.Held(SiteBusy.Kind.WAITS, null, null, false, SiteBusy.Busy.REMOVAL_IN_PROGRESS, "st_1", "site", owner);
		assertEquals("the old smithy", RemovalStops.blocker(own, owner, lots, towns));
		var ownGroup = new SiteBusy.Held(SiteBusy.Kind.WAITS, null, null, false, SiteBusy.Busy.REMOVAL_IN_PROGRESS, "grp_1", "group", owner);
		assertEquals("your old buildings", RemovalStops.blocker(ownGroup, owner, lots, towns));
		var other = new SiteBusy.Held(SiteBusy.Kind.WAITS, null, null, false, SiteBusy.Busy.WAITING_BEHIND, "rg_7", "region", "steward_mc:settlement/hill");
		assertEquals("Hillford's old buildings", RemovalStops.blocker(other, owner, lots, towns));
		// a player's, an extraction's, an unknown town's: never Architect's ids
		for (var h : List.of(new SiteBusy.Held(SiteBusy.Kind.WAITS, null, null, false, SiteBusy.Busy.REMOVAL_IN_PROGRESS, "SENTINEL-ID", "site", "uuid-1"),
			new SiteBusy.Held(SiteBusy.Kind.OTHER, null, null, false, SiteBusy.Busy.PLAYER_BLOCKS_RETURNING, "SENTINEL-ID", "extraction", null),
			new SiteBusy.Held(SiteBusy.Kind.WAITS, null, null, false, SiteBusy.Busy.REMOVAL_IN_PROGRESS, "SENTINEL-ID", "region", "steward_mc:settlement/gone"))) {
			String b = RemovalStops.blocker(h, owner, lots, towns);
			assertEquals(RemovalStops.A_BUILD_NEARBY, b);
			assertFalse(RemovalStops.undoBusy("set_1", h, b).contains("SENTINEL"));
		}
		// another operation is never said as a removal
		var op = new SiteBusy.Held(SiteBusy.Kind.OTHER, null, null, false, SiteBusy.Busy.OPERATION_IN_PROGRESS, "tx-3", "crate.settle", null);
		for (String line : List.of(RemovalStops.undoBusy("set_1", op, "x"), RemovalStops.busyReason(op), RemovalStops.cancelBusy("set_1", op, "x"),
			RemovalStops.growthBusy(op, "x"))) {
			assertFalse(line.contains("taken down next to") || line.contains("being taken down"), line);
			assertTrue(line.contains("in a moment"), line);
		}
		assertTrue(RemovalStops.undoBusy("set_1", own, "the old smithy").endsWith("Run /steward undo set_1 again once it's cleared."));
	}

	@Test
	void aWaitingBehindStopHasItsOwnWordsAndTail() {
		assertEquals("Undo stopped (another removal next to it took longer than 10 minutes to clear), nothing was taken down. Run /steward undo set_1 again once"
			+ " the removal next to it is cleared.", RemovalStops.undoStopped(RemovalStop.NOT_STARTED, RemovalStopCause.WAITING_BEHIND, "set_1"));
	}

	@Test
	void rows8To12SayABusyRefusalByKindEitherShape() {
		String w = RefusalWords.of(new CompletionException(refused(busy(Busy.Kind.REMOVAL_IN_PROGRESS, "grp_9", "group", null))));
		assertEquals("a build nearby is still being taken down next to it; it can be done once that's cleared", w);
		String t = RefusalWords.fit(List.of(new Refusal(Reason.SITE_BUSY, "SENTINEL-RAW", List.of(), "waiting_behind:rg_2")));
		assertEquals(w, t, "the delta's detail token says the same");
		String op = RefusalWords.fit(List.of(new Refusal(Reason.SITE_BUSY, "SENTINEL-RAW", List.of(), "operation_in_progress:tx-1")));
		assertTrue(op.endsWith("in a moment") && !op.contains("SENTINEL"), op);
	}

	// ------------------------------------------------------------------ undo rows 1-3

	@Test
	void aSiteBySiteUndoStoppedByABusySiteLogsWhatWentAndSaysWhyByKind() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = project(id, "grp_c1", "c1", "c2", "c3");
		a.siteAnswers.put("c2", Answer.BUSY);
		Undo.mark(s.id(), true);
		var e = placing(s);
		Undo.chainUndo(null, ArchitectAccess.api().sites(null), s, e, List.of("c3", "c2", "c1"), false, Map.of(), new RemoveOptions(false, s.owner()), said::add);
		String all = String.join("\n", said);
		assertTrue(all.contains("Undo stopped after 1 of 3 sites: a build nearby is still being taken down next to it. Run /steward undo " + id
			+ " again once it's cleared."), all);
		assertFalse(all.contains("SENTINEL") || all.contains(Undo.SITES_NOT_NOW), all);
		var after = now(id);
		assertEquals(1, count(after, Kind.PROJECT_REMOVED), "the site before it, logged once");
		assertEquals(List.of("c3"), after.log().stream().filter(x -> x.kind() == Kind.PROJECT_REMOVED).findFirst().orElseThrow().siteIds());
		assertTrue(after.log().stream().noneMatch(x -> x.outcome() == Settlement.Outcome.FAILED), "nothing logged as failed");
		assertTrue(after.log().stream().anyMatch(x -> x.kind() == Kind.NOTE && x.text().startsWith(Undo.STOPPED)), "the intent closed");
		assertTrue(Undo.interruptedEntries(after).isEmpty(), "no open undo left for a later removal to be taken for");
		assertFalse(Undo.busy(id));
		assertEquals(0, a.count("sites.remove c1"), "the chain stopped at the busy site");
	}

	@Test
	void aGroupUndoRefusedAsBusyTakesNothingForGone() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = project(id, "grp_g1", "g1", "g2");
		a.removeAnswer = Answer.BUSY;
		a.loadHealthy = true;
		Undo.mark(s.id(), true);
		var e = placing(s);
		// the sites read absent (as if already gone): a busy refusal began nothing, so absence is never read
		a.sites.remove("g1");
		Undo.groupUndo(null, ArchitectAccess.api().sites(null), s, e, e.siteGroup(), e.siteIds(), Map.of(), new RemoveOptions(false, s.owner()), said::add);
		String all = String.join("\n", said);
		assertTrue(all.contains("Undo stopped: a build nearby is still being taken down next to it, so nothing was taken down. Run /steward undo " + id
			+ " again once it's cleared."), all);
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED));
		assertFalse(Undo.busy(id));
	}

	@Test
	void aVillageUndoRefusedAsBusyIsNotedInTheStewardsWords() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = Settlement.founded(id, "Sea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed the village", List.of(new Settlement.SiteChange("v1", "hall", 0, 1)), "region:rg_b",
				Settlement.Outcome.DONE));
		a.region("rg_b", s.owner());
		a.site("v1", s.owner(), "rg_b");
		put(s);
		a.removeAnswer = Answer.BUSY;
		a.busy = busy(Busy.Kind.WAITING_BEHIND, "rg_x", "region", "uuid-player");
		var done = new AtomicReference<Boolean>();
		Undo.region(null, s, placing(s), "rg_b", Map.of(), said::add, done::set, true);
		assertEquals(Boolean.FALSE, done.get());
		String all = String.join("\n", said);
		assertTrue(all.contains("Undo stopped: a build nearby is still being taken down next to it, so nothing was taken down."), all);
		assertFalse(all.contains(Undo.NOT_NOW) || all.contains("SENTINEL"), all);
		var note = now(id).log().stream().filter(x -> x.kind() == Kind.NOTE && x.text().startsWith(Undo.STOPPED)).findFirst().orElseThrow();
		assertTrue(note.text().endsWith("a build nearby is still being taken down next to it"), note.text());
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED));
	}

	// ------------------------------------------------------------------ rows 8 and 9: never FAILED

	private static DeltaResult refusedDelta(Refusal r) {
		return new DeltaResult(false, "st_1", 1, 2, 0, List.of(), Map.of(), 0, List.of(r), List.of());
	}

	@Test
	void anUpdateRefusedAsBusyIsBusyInEitherShapeAndAnyOtherRefusalIsNot() {
		assertNotNull(Updates.BusyUpdates.busyOf(null, refusedDelta(new Refusal(Reason.SITE_BUSY, "x", List.of(), "removal_in_progress:g"))));
		assertNotNull(Updates.BusyUpdates.busyOf(null, refusedDelta(new Refusal(Reason.SITE_BUSY, "x"))), "the async path drops the detail: still busy");
		assertNotNull(Updates.BusyUpdates.busyOf(new CompletionException(refused(busy(Busy.Kind.OPERATION_IN_PROGRESS, "tx", "t", null))), null));
		assertNotNull(Updates.BusyUpdates.busyOf(new ArchitectRefused(Reason.SITE_BUSY, "plain"), null));
		assertNull(Updates.BusyUpdates.busyOf(null, refusedDelta(new Refusal(Reason.OVERLAP, "x"))));
		assertNull(Updates.BusyUpdates.busyOf(new ArchitectRefused(Reason.BILL_CAP, "x"), null));
		assertNull(Updates.BusyUpdates.busyOf(new IllegalStateException("x"), null));
	}

	@Test
	void aBusyUpdateWaitsIsSaidOnceAndFullAutonomyCanNotLoopWithoutBound() {
		var b = new Updates.BusyUpdates();
		var other = new SiteBusy.Held(SiteBusy.Kind.OTHER, null, null, false);
		var waits = new SiteBusy.Held(SiteBusy.Kind.WAITS, null, null, false, SiteBusy.Busy.REMOVAL_IN_PROGRESS, "g", "group", null);
		int timed = 0, sayings = 0;
		// Full autonomy: each release re-applies, and each re-apply is refused again
		for (int i = 0; i < 50; i++) {
			var step = b.refused("st_1@2", other, "why");
			if (step.say()) sayings++;
			assertTrue(b.waiting("st_1@2"), "waits, never failed");
			if (step.retryAfterMs() < 0) break;
			timed++;
			b.release("st_1@2");
		}
		assertEquals(Updates.BusyUpdates.MAX_TRIES, timed, "a bounded number of timed tries");
		assertEquals(1, sayings, "said once");
		assertTrue(b.waiting("st_1@2"), "past the bound it waits (asked in the inbox)");
		assertFalse(b.releaseWaits(), "another change's wait is not released by a removal's end");
		assertEquals(Updates.BusyUpdates.REMOVAL_MS, b.refused("st_2@3", waits, "why").retryAfterMs());
		assertTrue(b.releaseWaits());
		assertFalse(b.waiting("st_2@3"));
		b.forget("st_1@2");
		assertFalse(b.waiting("st_1@2"));
		assertEquals(Updates.BusyUpdates.SOON_MS, b.refused("st_1@2", other, "why").retryAfterMs(), "the player's ask counts from the start");
	}

	@Test
	void aRevertRefusedAsBusyIsANoteNeverAFailedRevert() {
		var busyEntry = Revert.entry(5L, "Not yet", "st_1", "smithy", 2, 1, false, true);
		assertEquals(Kind.NOTE, busyEntry.kind());
		assertTrue(busyEntry.changes().isEmpty(), "names no site change");
		assertNotEquals(Settlement.Outcome.FAILED, busyEntry.outcome());
		var failed = Revert.entry(5L, "Could not", "st_1", "smithy", 2, 1, false, false);
		assertEquals(Kind.REVERTED, failed.kind());
		assertEquals(Settlement.Outcome.FAILED, failed.outcome());
		assertEquals(Settlement.Outcome.DONE, Revert.entry(5L, "Reverted", "st_1", "smithy", 2, 1, true, false).outcome());
		assertTrue(Revert.busyLine("smithy", new SiteBusy.Held(SiteBusy.Kind.OTHER, null, null, false)).startsWith("Not yet: the smithy isn't reverted now"));
	}

	// ------------------------------------------------------------------ owed: one rule

	@Test
	void owedIsSaidOnlyForTheOwnersRefundKinds() {
		String owner = "steward_mc:settlement/sea";
		var parts = List.of(new OwedParts.Part<>(owner, "crate_settle", Map.of("oak", 3)), new OwedParts.Part<>(owner, "group_refund", Map.of("oak", 2, "glass", 1)),
			new OwedParts.Part<>(owner, "crate_putback", Map.of("stone", 4)),
			// never: the player's own blocks (even owed to the owner id), a container's contents, another recipient, unclaimed, a reason added later
			new OwedParts.Part<>(owner, "player_blocks", Map.of("sponge", 1)), new OwedParts.Part<>(owner, "cargo", Map.of("book", 1)),
			new OwedParts.Part<>("uuid-steve", "crate_settle", Map.of("oak", 9)), new OwedParts.Part<>("architect:unclaimed", "group_refund", Map.of("oak", 9)),
			new OwedParts.Part<>(owner, "future_kind", Map.of("oak", 9)), new OwedParts.Part<>(owner, "crate_settle", Map.of("dirt", 0)));
		assertEquals(Map.of("oak", 5, "glass", 1, "stone", 4), OwedParts.sum(owner, parts));
		assertEquals(Map.of(), OwedParts.sum(null, parts));
		assertEquals(Map.of(), OwedParts.sum(owner, null));
		assertTrue(OwedParts.owedTo(owner, (dev.larattalabs.architect.api.RemoveResult) null).isEmpty());
	}

	// ------------------------------------------------------------------ the RemovalFinished adapter's one match, and the sweep's recovered result

	@Test
	void anEventEndsOnlyTheEntryItTookDown() {
		var region = Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "v", List.of(new Settlement.SiteChange("v1", "hall", 0, 1)), "region:rg_r", Settlement.Outcome.DONE);
		var group = Settlement.LogEntry.of(3L, Kind.PROJECT_PLACED, "g", List.of(new Settlement.SiteChange("g1", "hall", 0, 1)), "grp_g", Settlement.Outcome.DONE);
		assertTrue(Undo.endedBy(region, "region", "rg_r"));
		assertFalse(Undo.endedBy(region, "region", "rg_other"));
		assertTrue(Undo.endedBy(group, "group", "grp_g"));
		assertTrue(Undo.endedBy(group, "site", "g1"));
		assertFalse(Undo.endedBy(group, "site", "g2"));
		assertFalse(Undo.endedBy(group, null, null));
		assertFalse(Undo.endedBy(group, "stage", "grp_g"));
	}

	@Test
	void theSweepReadsArchitectsSnapshotOnlyForWhatItLogsAndOnce() throws IOException {
		a.features = T4;
		String id = nextId();
		Settlement s = Settlement.founded(id, "Sea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed the village", List.of(new Settlement.SiteChange("v1", "hall", 0, 1)), "region:rg_s",
				Settlement.Outcome.DONE));
		a.region("rg_s", s.owner());
		a.site("v1", s.owner(), "rg_s");
		put(s);
		a.removeAnswer = Answer.HELD_DECIDED;
		Undo.region(null, s, placing(s), "rg_s", Map.of(), said::add, x -> { }, true, () -> { });
		a.complete();
		// the event names it: its own refund is said by the adapter, the snapshot never read
		var lines = new ArrayList<String>();
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("region", "rg_s", true, Map.of(), List.of()), (sid, line) -> lines.add(line));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(0, a.count("sites.removalResult"), "the event's own answer: no snapshot read");

		// a second town whose region the sweep logs with no event: the snapshot read once, for it
		String id2 = nextId();
		Settlement s2 = Settlement.founded(id2, "Bay", new Claim(OW, 1000, 1000, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed the bay", List.of(new Settlement.SiteChange("w1", "hall", 0, 1)), "region:rg_t",
				Settlement.Outcome.DONE));
		a.region("rg_t", s2.owner());
		a.site("w1", s2.owner(), "rg_t");
		put(s2);
		a.removeAnswer = Answer.HELD_DECIDED;
		Undo.region(null, s2, placing(s2), "rg_t", Map.of(), said::add, x -> { }, true, () -> { });
		a.complete();
		assertTrue(Undo.finishInterrupted(null, now(id2)));
		assertEquals(1, a.count("sites.removalResult region rg_t"));
		assertFalse(Undo.finishInterrupted(null, now(id2)), "logged once");
		// another region's event: logs nothing more and says no refund for it
		RemovalEnds.handle(null, new RemovalEnds.RemovalEnd("region", "rg_t", true, Map.of(), List.of()), (sid, line) -> lines.add(sid + line));
		assertEquals(1, a.count("sites.removalResult region rg_t"));
		assertEquals(1, count(now(id2), Kind.PROJECT_REMOVED));
		assertTrue(lines.stream().noneMatch(l -> l.startsWith(id2)), String.valueOf(lines));
		assertNull(Undo.recoveredLine(s.owner(), null), "no snapshot (an older build's removal, evicted): nothing said");
	}
}
