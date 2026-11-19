package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.RegionState;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (T4S-PRE) Removals Architect's transaction layer holds (T4), driven over {@link FakeArchitect} through the removal paths that exist before T4: a decided
 * removal held ({@code SITE_BUSY held(decided)}) is said as under way in the steward's words (never "stopped", never "could not", never Architect's
 * text), leaves its undo open (no closing note, no FAILED or PARTIAL entry), releases the busy mark, and is logged once when it finishes; one refused
 * {@code held(other)} (nothing changed) is asked again. These ran red on main f0e1858 before the fix (failing-first).
 */
class HeldRemovalTest {
	private static final String OW = "minecraft:overworld";
	private static final Claim TOWN = new Claim(OW, 0, 0, 64, -64, 320);
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
		return "set_t4h" + (++serial);
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

	/** Every line a player saw: never Architect's text, never a stop or a refusal. */
	private void underWay(String what) {
		String all = String.join("\n", said);
		assertTrue(all.contains(what), all);
		assertTrue(all.contains("chunk 3, -4"), "the place in the steward's words: " + all);
		assertTrue(all.contains("Nothing more to do"), all);
		assertFalse(all.contains("SENTINEL") || all.contains("tx-7") || all.contains("held("), "never Architect's text: " + all);
		assertFalse(all.contains("Undo stopped") || all.contains("could not") || all.contains("again once it is clear"), "never a stop: " + all);
	}

	/** A flat village (region {@code region}) placed by one entry, its site {@code v1}. */
	private Settlement village(String id, String region) throws IOException {
		Settlement s = Settlement.founded(id, "Hel", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed the village", List.of(new Settlement.SiteChange("v1", "hall", 0, 1)),
				"region:" + region, Settlement.Outcome.DONE));
		a.region(region, s.owner());
		a.site("v1", s.owner(), region);
		return put(s);
	}

	private static Settlement.LogEntry placing(Settlement s) {
		return s.log().stream().filter(e -> e.kind() == Kind.PROJECT_PLACED).reduce((x, y) -> y).orElseThrow();
	}

	@Test
	void aRegionUndoDecidedAndHeldLeavesItsNoteOpenAndIsLoggedOnceWhenItFinishes() throws IOException {
		String id = nextId();
		Settlement s = village(id, "rg_h1");
		a.removeAnswer = Answer.HELD_DECIDED;
		var done = new AtomicReference<Boolean>();
		Undo.region(null, s, placing(s), "rg_h1", Map.of(), said::add, done::set, true);
		underWay("Undo under way");
		assertEquals(Boolean.FALSE, done.get(), "not taken up yet (a caller without a held path reads it as not done)");
		assertFalse(Undo.busy(id), "the busy mark is released: a hold never keeps the settlement busy all session");
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), "nothing logged as removed or stopped");
		assertEquals(List.of("rg_h1"), Undo.interrupted(now(id)), "no closing note: the sweep (SD2) still finishes it");
		// still removing: the sweep waits
		assertFalse(Undo.finishInterrupted(null, now(id)));
		// Architect's held removal finishes: logged once, never twice
		a.complete();
		assertEquals(RegionState.REMOVED, a.regions.get("rg_h1").state);
		assertTrue(Undo.finishInterrupted(null, now(id)));
		assertFalse(Undo.finishInterrupted(null, now(id)));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(1, a.count("regions.remove rg_h1"), "never asked again");
	}

	@Test
	void aRegionUndoRefusedHeldOtherIsAskedAgainAndTakenUp() throws IOException {
		String id = nextId();
		Settlement s = village(id, "rg_h2");
		a.removeAnswer = Answer.HELD_OTHER;
		a.otherTimes = 2;
		var done = new AtomicReference<Boolean>();
		Undo.region(null, s, placing(s), "rg_h2", Map.of(), said::add, done::set, true);
		assertEquals(Boolean.TRUE, done.get(), String.valueOf(said));
		assertEquals(3, a.count("regions.remove rg_h2"), "asked again while another change held it");
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertFalse(String.join("\n", said).contains("SENTINEL"));
	}

	/** A region town with one standing growth (rid 1 at position 1) of site {@code g1}, logged. */
	private Settlement town(String id, String region) throws IOException {
		Settlement s = Settlement.founded(id, "Hel", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed the village", List.of(new Settlement.SiteChange("t1", "hall", 0, 1)),
				"region:" + region, Settlement.Outcome.DONE))
			.withLog(Settlement.LogEntry.of(3L, Kind.PROJECT_PLACED, "Placed 1 building", List.of(new Settlement.SiteChange("g1", "house", 0, 1)),
				Growths.tag(region, 1), Settlement.Outcome.DONE));
		var r = a.region(region, s.owner());
		r.rev(1, 1, "PLACED", "g1");
		a.site("t1", s.owner(), region);
		a.site("g1", s.owner(), region);
		return put(s);
	}

	@Test
	void aGrowthTakeUpDecidedAndHeldIsUnderWayReleasesTheBusyMarkAndTheSweepLogsItOnce() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_h3");
		a.undoAnswer = Answer.HELD_DECIDED;
		var done = new AtomicReference<Boolean>();
		Undo.growth(null, s, placing(s), new Growths.Ref("rg_h3", 1), Map.of(), said::add, done::set);
		underWay("being taken up");
		assertFalse(Undo.busy(id), "released");
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals("UNDOING", a.regions.get("rg_h3").revisions.get(0).state);
		// it finishes: the growth sweep's reconcile (every 10 s, VillageSweep.reconcileDue) is then due, and any re-asker reads it UNDONE and logs it once
		a.complete();
		String tag = Growths.tag("rg_h3", 1);
		assertTrue(VillageSweep.reconcileDue(now(id), tag, a.regions.get("rg_h3").revisions.get(0).state, false), "the sweep logs it");
		var again = new AtomicReference<Boolean>();
		Undo.growth(null, now(id), placing(s), new Growths.Ref("rg_h3", 1), Map.of(), said::add, again::set);
		assertEquals(Boolean.TRUE, again.get());
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertFalse(VillageSweep.reconcileDue(now(id), tag, "UNDONE", false), "once");
		assertEquals(1, a.count("regions.undoRevision"), "never asked again");
	}

	@Test
	void aGrowthTakeUpRefusedHeldOtherIsAskedAgain() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_h4");
		a.undoAnswer = Answer.HELD_OTHER;
		var done = new AtomicReference<Boolean>();
		Undo.growth(null, s, placing(s), new Growths.Ref("rg_h4", 1), Map.of(), said::add, done::set);
		assertEquals(Boolean.TRUE, done.get(), String.valueOf(said));
		assertEquals(2, a.count("regions.undoRevision rg_h4 0"));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
	}

	@Test
	void aGrowthWhoseTownReadsAbsentFromAStoreThatDidNotLoadIsNeverLoggedAsTakenUp() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_h5");
		// Architect's records didn't load: no region, no site listed
		a.regions.get("rg_h5").present = false;
		a.loadHealthy = false;
		var done = new AtomicReference<Boolean>();
		Undo.growth(null, s, placing(s), new Growths.Ref("rg_h5", 1), Map.of(), said::add, done::set);
		assertEquals(Boolean.FALSE, done.get(), String.valueOf(said));
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), "absence on a store that did not load is no removal");
		assertTrue(String.join("\n", said).contains("Architect's records didn't load"), String.valueOf(said));
		// healthy again, still absent: gone (as before)
		a.loadHealthy = true;
		var again = new AtomicReference<Boolean>();
		Undo.growth(null, now(id), placing(s), new Growths.Ref("rg_h5", 1), Map.of(), said::add, again::set);
		assertEquals(Boolean.TRUE, again.get());
	}

	@Test
	void aDistrictRoadsTakeUpDecidedAndHeldIsSaidUnderWayNotFailed() throws IOException {
		String id = nextId();
		Settlement s = put(Settlement.founded(id, "Hel", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L));
		a.site("road_1", s.owner(), null);
		a.removeAnswer = Answer.HELD_DECIDED;
		var ran = new AtomicReference<Boolean>(false);
		Districts.takeUpForTest(s, "road_1", said::add, () -> ran.set(true));
		assertTrue(ran.get());
		String all = String.join("\n", said);
		assertTrue(all.contains("is being taken up") && all.contains("chunk 3, -4"), all);
		assertFalse(all.contains("could not") || all.contains("SENTINEL") || all.contains("held("), all);
	}
}
