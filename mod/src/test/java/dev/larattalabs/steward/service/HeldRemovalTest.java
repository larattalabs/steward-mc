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
		bounds.clear();
		Undo.boundLater = (ms, r) -> bounds.add(r);
		VillageSweep.testVoice = (sid, line) -> said.add(line);
	}

	/** (T4R-PIN fix round) The answer bounds Undo.asking armed, run when a test says the time is up. */
	final List<Runnable> bounds = new ArrayList<>();

	private void boundPasses() {
		var b = List.copyOf(bounds);
		bounds.clear();
		b.forEach(Runnable::run);
	}

	private void restart() throws IOException {
		Session.nextForTest();
		Settlements.useForTest(SettlementStore.load(file), file);
	}

	private long saidCount(String what) {
		return said.stream().filter(l -> l.contains(what)).count();
	}

	@AfterEach
	void tearDown() {
		Undo.later = Undo.LATER;
		Undo.boundLater = Undo.BOUND_LATER;
		VillageSweep.testVoice = null;
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

	/** (T4R-PIN fix round) A growth whose stage's paid blocks ({@code g1}: 5 planks drawn) come back once, keyed per target. */
	private void paid() {
		a.regions.values().forEach(r -> r.fromMaterials = true);
		a.ledger.drawPart(a, "g1", Map.of("minecraft:oak_planks", 5));
	}

	/** Under way, said once in the steward's words, never a stop or Architect's text; no place named (Steward's bound read it, not Architect). */
	private void growthUnderWay() {
		String all = String.join("\n", said);
		assertEquals(1, saidCount("The town's change is being taken up"), all);
		assertTrue(all.contains("it finishes by itself") && all.contains("Nothing more to do"), all);
		assertFalse(all.contains("tx-7") || all.contains("held(") || all.contains("ms: taken"), "never Architect's or Steward's own log text: " + all);
		assertFalse(all.contains("Undo stopped") || all.contains("could not") || all.contains("again once it is clear"), "never a stop: " + all);
	}

	/**
	 * (T4R-PIN fix round, batch-100 P1) Architect 0.91.0 leaves a held revision undo's future pending. Steward's bound reads it as under way: the held
	 * note saved, the busy mark released, said once. Architect's late answer is dropped (no flip, no entry, no line); the growth sweep logs it once and
	 * says it once; the refund came back once. Red before the fix: the settlement stays busy and nothing is said.
	 */
	@Test
	void aGrowthTakeUpDecidedAndHeldIsUnderWayReleasesTheBusyMarkAndTheSweepLogsItOnce() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_h3");
		paid();
		a.undoAnswer = Answer.HELD_DECIDED;
		var done = new AtomicReference<Boolean>();
		var held = new AtomicReference<Integer>(0);
		var ref = new Growths.Ref("rg_h3", 1);
		Undo.growth(null, s, placing(s), ref, Map.of(), said::add, done::set, () -> held.set(held.get() + 1));
		assertTrue(Undo.busy(id), "Architect has not answered yet");
		assertEquals(1, bounds.size(), "the one bound armed");
		boundPasses();
		growthUnderWay();
		assertFalse(Undo.busy(id), "released at the bound");
		assertEquals(1, held.get());
		assertNull(done.get(), "the held path, never done");
		assertTrue(Undo.heldOpen(now(id), Undo.growthHeldTag(ref)), "its held note saved before the release");
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals("UNDOING", a.regions.get("rg_h3").revisions.get(0).state);
		// the stage finishes: Architect's late answer is dropped
		a.complete();
		assertNull(done.get(), "no flip");
		assertEquals(1, held.get());
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), "the late answer logs nothing");
		assertEquals(0, saidCount("Undone"), String.valueOf(said));
		assertEquals(5, a.ledger.returned, "Architect refunded once");
		// the sweep logs it once and says it once
		VillageSweep.growths(null, now(id));
		VillageSweep.growths(null, now(id));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(1, saidCount("finished by itself"), String.valueOf(said));
		assertEquals(0, saidCount("Refunded"), "no second refund line: " + said);
		assertEquals(5, a.ledger.returned, "nothing more back");
		assertEquals(1, a.count("regions.undoRevision"), "never asked again");
	}

	/**
	 * (T4R-PIN fix round, crash point) A stop after the bound fired and before Architect's late completion: the restart (a new session, the store read
	 * from disk) finds the held note; the stage finishes after it; the sweep logs one removal, says it once, and the refund came back once (in = out).
	 */
	@Test
	void aStopAfterTheBoundAndBeforeTheLateCompletionLogsOneRemovalAndOneRefund() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_h6");
		paid();
		int stock0 = a.ledger.stock.getOrDefault("minecraft:oak_planks", 0);
		a.undoAnswer = Answer.HELD_DECIDED;
		var ref = new Growths.Ref("rg_h6", 1);
		Undo.growth(null, s, placing(s), ref, Map.of(), said::add, ok -> { }, () -> { });
		boundPasses();
		growthUnderWay();
		restart();
		assertTrue(Undo.heldOpen(now(id), Undo.growthHeldTag(ref)), "the held note is on disk");
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED));
		// still UNDOING after the restart: the sweep leaves it to Architect
		VillageSweep.growths(null, now(id));
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED));
		a.complete();
		VillageSweep.growths(null, now(id));
		restart();
		VillageSweep.growths(null, now(id));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED), "one removal across the crash");
		assertEquals(1, saidCount("finished by itself"), String.valueOf(said));
		assertEquals(5, a.ledger.returned, "refunded once");
		assertEquals(a.ledger.paid, a.ledger.returned, "conservation: what was drawn came back, once");
		assertEquals(stock0 + 5, a.ledger.stock.get("minecraft:oak_planks"));
	}

	/** (T4R-PIN fix round) A re-ask while the held undo still runs joins it (a new future, its own bound); finishing logs and refunds once. */
	@Test
	void aJoinedReAskOfAHeldUndoStillEndsOnce() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_h7");
		paid();
		a.undoAnswer = Answer.HELD_DECIDED;
		var ref = new Growths.Ref("rg_h7", 1);
		Undo.growth(null, s, placing(s), ref, Map.of(), said::add, ok -> { }, () -> { });
		boundPasses();
		var again = new AtomicReference<Boolean>();
		Undo.growth(null, now(id), placing(s), ref, Map.of(), said::add, again::set, () -> { });
		assertTrue(Undo.busy(id), "the re-ask waits on the joined undo");
		a.complete();
		assertEquals(Boolean.TRUE, again.get(), "the joined answer is the re-ask's own");
		assertTrue(bounds.size() == 1, "its own bound armed");
		boundPasses();
		assertEquals(Boolean.TRUE, again.get(), "a bound after the answer changes nothing");
		VillageSweep.growths(null, now(id));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(5, a.ledger.returned);
		assertEquals(1, saidCount("The town's change is being taken up"), String.valueOf(said));
	}

	/** (T4R-PIN fix round) A bound that fires after the world stopped does nothing (the stop already released everything). */
	@Test
	void aBoundFromAStoppedSessionDoesNothing() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_h8");
		a.undoAnswer = Answer.HELD_DECIDED;
		var held = new AtomicReference<Integer>(0);
		Undo.growth(null, s, placing(s), new Growths.Ref("rg_h8", 1), Map.of(), said::add, ok -> { }, () -> held.set(held.get() + 1));
		Session.nextForTest();
		boundPasses();
		assertEquals(0, held.get());
		assertEquals(0, saidCount("being taken up"));
		assertEquals(0, count(now(id), Kind.NOTE));
	}

	/**
	 * (T4R-PIN fix round, the one rule) Every removal Steward awaits through Undo.asking has the bound: a region undo Architect never answers is under
	 * way at the bound (its held note, released), and SD2's sweep logs it once when it finishes.
	 */
	@Test
	void aRegionUndoNeverAnsweredIsUnderWayAtTheBoundAndLoggedOnce() throws IOException {
		String id = nextId();
		Settlement s = village(id, "rg_h9");
		a.removeAnswer = Answer.HOLD;
		var done = new AtomicReference<Boolean>();
		var held = new AtomicReference<Integer>(0);
		Undo.region(null, s, placing(s), "rg_h9", Map.of(), said::add, done::set, true, () -> held.set(held.get() + 1));
		assertTrue(Undo.busy(id));
		boundPasses();
		assertFalse(Undo.busy(id));
		assertEquals(1, held.get());
		assertEquals(1, saidCount("Undo under way"), String.valueOf(said));
		assertEquals(List.of("rg_h9"), Undo.interrupted(now(id)));
		a.complete();
		assertNull(done.get(), "the late answer is dropped");
		assertTrue(Undo.finishInterrupted(null, now(id)));
		assertFalse(Undo.finishInterrupted(null, now(id)));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
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
