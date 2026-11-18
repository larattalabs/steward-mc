package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.steward.model.SettlementStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (batch-077 review P1-1, P1-2) While settlements are disabled for the session, an answer that arrives late takes nothing up (no removal, no refund
 * with no history saved), and the first tick's resumes stop as soon as one of them disables settlements.
 */
class DisabledFenceTest {
	@TempDir
	Path dir;
	FakeArchitect a;

	@BeforeEach
	void setUp() {
		a = new FakeArchitect();
		a.install();
	}

	@AfterEach
	void tearDown() {
		SettlementRunner.clearForTest();
		SettlementRunner.useBuildsFileForTest(null);
		ArchitectAccess.reset();
		Settlements.resetForTest();
		dev.larattalabs.steward.util.DurableWrite.FAIL_NEXT.clear();
	}

	/**
	 * (FENCE-TESTS) Runs one of the runner's own steps, as its callbacks reach it, with the guard's line captured. Each of these guards stands behind
	 * another ({@code alive()}, the resume's, VillageSweep's), so they are reached here directly; past a missing guard the step goes on to Architect
	 * (no server, the real API) and may throw, which is caught so that the missing guard's line, not the throw, is what fails.
	 */
	private static List<String> step(SettlementRunner r, String method, Class<?>[] types, Object... args) {
		try (LogCapture log = LogCapture.open()) {
			try {
				var m = SettlementRunner.class.getDeclaredMethod(method, types);
				m.setAccessible(true);
				m.invoke(r, args);
			} catch (java.lang.reflect.InvocationTargetException e) {
				log.lines.add("threw: " + e.getCause());
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
			return List.copyOf(log.lines);
		}
	}

	private static boolean has(List<String> lines, String part) {
		return lines.stream().anyMatch(l -> l.contains(part));
	}

	/** The sample build's settlement in a store that is disabled (no file), as after a failed save not written back. */
	private SettlementRunner disabledWithItsSettlement() {
		var b = BuildStoreTestAccess.saved();
		SettlementStore store = new SettlementStore();
		store.put(b.settlement());
		Settlements.useForTest(store, dir.resolve("steward-settlements.json"));
		SettlementRunner r = SettlementRunner.restoredForTest(b);
		assertTrue(r.aliveForTest(), "acts before the disable");
		Settlements.useForTest(store, null);
		assertTrue(Settlements.disabled());
		return r;
	}

	@Test
	void aRegionIsNeverTakenUpWhileSettlementsAreDisabled() {
		var s = disabledWithItsSettlement();
		var owner = Settlements.store().get("set_4").orElseThrow().owner();
		a.region("rg_late", owner);
		a.site("st_1", owner, "rg_late");
		AtomicReference<Boolean> done = new AtomicReference<>();
		List<String> said = new ArrayList<>();
		VillageSweep.takeUp(null, Settlements.store().get("set_4").orElseThrow(), "rg_late", said::add, done::set);
		assertEquals(Boolean.FALSE, done.get(), "answered: not taken up");
		assertEquals(0, a.count("regions.remove"), a.calls.toString());
		assertEquals(0, a.count("regions.undoRevision"), a.calls.toString());
		assertEquals(0, a.count("sites.remove"), a.calls.toString());
		assertNotEquals(RegionState.REMOVED, a.regions.get("rg_late").state);
		assertNotNull(s);
		// a district road laid but not saved: it stands too, said, and the settlement is no longer held for it
		a.site("road_1", owner, null);
		boolean[] ran = { false };
		Districts.takeUpForTest(Settlements.store().get("set_4").orElseThrow(), "road_1", said::add, () -> ran[0] = true);
		assertTrue(ran[0]);
		assertEquals(0, a.count("sites.remove"), a.calls.toString());
		assertTrue(said.stream().anyMatch(m -> m.contains("stands until the next session")), said.toString());
	}

	@Test
	void aTakeUpWhoseOwnHistorySaveDisablesSettlementsLeavesTheRegionStanding() throws Exception {
		// (batch-077 fix-check P1) enabled on entry; the take-up's history save renames in, its folder force fails, and the write-back fails before its
		// rename: settlements are disabled by that save, and the region is not removed
		var b = BuildStoreTestAccess.saved();
		Path file = dir.resolve("steward-settlements.json");
		SettlementStore store = new SettlementStore();
		store.put(b.settlement());
		store.save(file);
		Settlements.useForTest(store, file);
		var s = Settlements.store().get("set_4").orElseThrow();
		a.region("rg_mid", s.owner());
		a.site("st_m", s.owner(), "rg_mid");
		dev.larattalabs.steward.util.DurableWrite.FAIL_NEXT.addAll(List.of(dev.larattalabs.steward.util.DurableWrite.TestFail.AFTER_RENAME,
			dev.larattalabs.steward.util.DurableWrite.TestFail.BEFORE_RENAME));
		AtomicReference<Boolean> done = new AtomicReference<>();
		try {
			VillageSweep.takeUp(null, s, "rg_mid", m -> {}, done::set);
		} finally {
			dev.larattalabs.steward.util.DurableWrite.FAIL_NEXT.clear();
		}
		assertTrue(Settlements.disabled(), "the failed save disabled settlements");
		assertEquals(Boolean.FALSE, done.get());
		assertEquals(0, a.count("regions.remove"), a.calls.toString());
		assertEquals(0, a.count("sites.remove"), a.calls.toString());
		assertNotEquals(RegionState.REMOVED, a.regions.get("rg_mid").state);
		// the settlement is not held: a later take-up (next session) is not refused as busy
		Settlements.useForTest(Settlements.store(), file);
		AtomicReference<Boolean> again = new AtomicReference<>();
		VillageSweep.takeUp(null, Settlements.store().get("set_4").orElseThrow(), "rg_mid", m -> {}, again::set);
		assertNotNull(again.get());
		assertTrue(a.count("regions.remove") + a.count("sites.remove") + a.count("regions.undoRevision") > 0, a.calls.toString());
	}

	@Test
	void aLateRealiseAnswerWhileDisabledLeavesItsRegionStanding() {
		var r = disabledWithItsSettlement();
		var owner = Settlements.store().get("set_4").orElseThrow().owner();
		a.region("rg_late", owner);
		a.site("st_1", owner, "rg_late");
		// the build's realise answered after the disable: alive() is false, which before the fix went to the orphan take-up
		r.realisedForTest("rg_late", null);
		assertTrue(a.calls.stream().noneMatch(c -> c.startsWith("regions.remove") || c.startsWith("sites.remove") || c.startsWith("regions.undoRevision")),
			a.calls.toString());
		assertNotEquals(RegionState.REMOVED, a.regions.get("rg_late").state);
		// the history is untouched (nothing could be saved anyway)
		assertEquals(BuildStoreTestAccess.saved().settlement().log().size(), Settlements.store().get("set_4").orElseThrow().log().size());
	}

	@Test
	void theFirstTickResumesStopOnceOneDisablesSettlements() {
		var b = BuildStoreTestAccess.saved();
		SettlementStore store = new SettlementStore();
		store.put(b.settlement());
		Settlements.useForTest(store, dir.resolve("steward-settlements.json"));
		SettlementRunner first = SettlementRunner.restoredForTest(b);
		// the snapshot taken before any resume, as the first tick takes it
		List<SettlementRunner> snapshot = List.of(first, first, first);
		List<SettlementRunner> resumed = new ArrayList<>();
		SettlementRunner.resumeEach(snapshot, x -> {
			resumed.add(x);
			// this resume's failed save could not be written back: settlements are disabled from here
			Settlements.useForTest(store, null);
		});
		assertEquals(1, resumed.size(), "the runners after it are not resumed");
		// and a resume reached anyway does nothing (no Architect call, nothing queued or said to a server)
		int before = a.calls.size();
		first.resyncForTest();
		assertEquals(before, a.calls.size(), a.calls.toString());
	}

	@Test
	void nothingIsSentToArchitectWhileSettlementsAreDisabled() {
		// (batch-077 review P1-2) execute: the mutation boundary every command goes through
		var r = disabledWithItsSettlement();
		var lines = step(r, "execute", new Class<?>[] { dev.larattalabs.steward.pipeline.Pipeline.Command.class },
			new dev.larattalabs.steward.pipeline.Pipeline.Notify("a line", false));
		assertTrue(has(lines, "Notify not sent: settlements are disabled this session"), lines.toString());
		assertFalse(has(lines, "settlement: a line"), "nothing said or done for the command: " + lines);
		assertFalse(has(lines, "threw:"), lines.toString());
	}

	@Test
	void nothingIsQueuedWhileSettlementsAreDisabled() {
		var r = disabledWithItsSettlement();
		var lines = step(r, "fitAndQueue", new Class<?>[] { boolean.class }, true);
		assertTrue(has(lines, "not queued: settlements are disabled this session"), lines.toString());
		assertFalse(has(lines, "threw:"), lines.toString());
		assertTrue(a.calls.isEmpty(), a.calls.toString());
	}

	@Test
	void nothingIsRealisedWhileSettlementsAreDisabled() {
		var r = disabledWithItsSettlement();
		var lines = step(r, "realiseVillage", new Class<?>[0]);
		assertTrue(has(lines, "not realised: settlements are disabled this session"), lines.toString());
		assertFalse(has(lines, "threw:"), lines.toString());
		assertTrue(a.calls.isEmpty(), a.calls.toString());
	}

	@Test
	void anOrphanRegionIsKeptForTheNextSessionWhileSettlementsAreDisabled() {
		// (batch-077 review P1-1) the runner's own guard: the region stands and the player is told; VillageSweep's guard (behind it) is never reached
		var r = disabledWithItsSettlement();
		var owner = Settlements.store().get("set_4").orElseThrow().owner();
		a.region("rg_orphan", owner);
		a.site("st_o", owner, "rg_orphan");
		var lines = step(r, "takeUpOrphan", new Class<?>[] { String.class }, "rg_orphan");
		assertTrue(has(lines, "was realised while settlements are disabled: left standing for the next session"), lines.toString());
		assertFalse(has(lines, "is not taken up: settlements are disabled"), "stopped by the runner's guard, not VillageSweep's: " + lines);
		assertFalse(has(lines, "threw:"), lines.toString());
		assertEquals(0, a.count("regions.remove") + a.count("sites.remove") + a.count("regions.undoRevision"), a.calls.toString());
		assertNotEquals(RegionState.REMOVED, a.regions.get("rg_orphan").state);
	}

	@Test
	void aPlacedPartNotRecordedStandsOnlyWhileSettlementsAreDisabled() {
		// (batch-077 review P1-1) PendingOffers.watch after its history save failed: left standing while disabled (never taken up with no history),
		// taken up again while settlements work. The call site needs a server and Architect's own API, so the decision is tested here.
		Settlements.useForTest(new SettlementStore(), dir.resolve("steward-settlements.json"));
		assertFalse(PendingOffers.standsUnrecorded(), "settlements work: taken up again");
		Settlements.useForTest(new SettlementStore(), null);
		assertTrue(Settlements.disabled());
		assertTrue(PendingOffers.standsUnrecorded(), "disabled: left standing for the next session");
	}

	@Test
	void aFailingResumeDoesNotStopTheOthersWhileSettlementsWork() {
		var b = BuildStoreTestAccess.saved();
		Settlements.useForTest(new SettlementStore(), dir.resolve("steward-settlements.json"));
		SettlementRunner r = SettlementRunner.restoredForTest(b);
		List<SettlementRunner> resumed = new ArrayList<>();
		SettlementRunner.resumeEach(List.of(r, r), x -> {
			resumed.add(x);
			if (resumed.size() == 1) throw new IllegalStateException("boom");
		});
		assertEquals(2, resumed.size());
	}
}
