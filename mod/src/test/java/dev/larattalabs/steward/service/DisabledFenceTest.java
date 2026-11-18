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
