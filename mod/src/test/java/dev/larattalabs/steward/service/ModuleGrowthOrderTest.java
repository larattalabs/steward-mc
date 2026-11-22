package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectRefused;
import dev.larattalabs.architect.api.Quote;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.module.ModuleOrder;
import dev.larattalabs.steward.pipeline.Pipeline;
import dev.larattalabs.steward.util.DurableWrite;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (FENCE-TESTS-3; FENCE-TESTS' GPT P3 e) The non-composed module path, a module growth of a town from materials (slice M3, QD, QD-2), driven through
 * SettlementRunner's own steps against FakeArchitect: its quote ({@code quoteModules}: {@code Regions.quote}, the {@code maxBill} feature, the answer
 * handed to the server) and a realise refused at put-down ({@code realised}'s growth {@code BILL_CAP} branch). What each saves is on disk before the
 * player is asked, and before the next step goes to Architect; a refusal that can't be saved asks nothing again.
 *
 * <p>No server: the growth's realise itself (its mode checks read the world) isn't reached; the step after a decision is the growth plan's lookup by
 * key ({@code planByKey}, answered with none), and every check reads what happened before it.
 */
class ModuleGrowthOrderTest {
	@TempDir
	Path dir;
	FakeArchitect a;
	Path buildsFile;
	SettlementRunner r;
	BuildStore.Saved saved;
	final List<Throwable> tails = new ArrayList<>();

	static final String LOT = "farm_1", VPLAN = "vplan_g1", TOWN = "rg_town";
	static final Map<String, String> ENTRIES = Map.of(LOT, "e_farm");
	/** The farm's approved bound: 130 dirt. */
	static final ModuleOrder BOUNDED = ComposedCrashPointsTest.farm("{\"minecraft:dirt\": 130}");
	static final String HOLD_WORDS = "Approve to build it with this bill";

	@BeforeEach
	void setUp() {
		a = new FakeArchitect();
		a.inline = false;
		var f = new HashSet<>(a.features);
		f.add(SettlementRunner.MAX_BILL);
		a.features = Set.copyOf(f);
		a.install();
		buildsFile = dir.resolve("steward-builds.json");
		SettlementRunner.useBuildsFileForTest(buildsFile);
	}

	/** (GPT P3) Nothing was thrown past the runner's steps but what needs the server this test has none of (a notice to the player). */
	@AfterEach
	void onlyServerlessTails() {
		for (Throwable t : tails)
			assertTrue(t instanceof NullPointerException && String.valueOf(t.getMessage()).contains("\"server\" is null"), "unexpected: " + tails);
	}

	@AfterEach
	void tearDown() {
		SettlementRunner.clearForTest();
		SettlementRunner.useBuildsFileForTest(null);
		ArchitectAccess.reset();
		Settlements.resetForTest();
		DurableWrite.FAIL_NEXT.clear();
	}

	/** A module growth of town {@link #TOWN} (its plan {@link #VPLAN}, one module lot with this order) in {@code phase}, saved, this session's runner. */
	SettlementRunner runner(ModuleOrder order, Pipeline.Phase phase) {
		var b = BuildStoreTestAccess.saved();
		var st = Pipeline.step(b.state(), new Pipeline.GroupUpdate("grp_1", "done", Map.of(), List.of(), 3.1, null), Permission.PROPOSALS).next();
		assertEquals(Pipeline.Phase.READY_TO_PLACE, st.phase());
		if (phase == Pipeline.Phase.PLACING) st = Pipeline.step(st, new Pipeline.ModulePlace(), Permission.PROPOSALS).next();
		assertEquals(phase, st.phase());
		saved = new BuildStore.Saved(b.settlementId(), b.buildId(), b.playerId(), b.dimension(), b.permission(), b.landmarks(), b.settlement(), st, b.plan(),
			b.bibleJobId(), b.groupId(), b.batchId(), b.siteGroupId(), b.landmarkIds(), b.decided(), b.lastAwaiting(), false, null, null, true, VPLAN, null, null,
			TOWN, null, null, "vgrow", Map.of(), Map.of(LOT, order), null);
		SettlementStore store = new SettlementStore();
		store.put(saved.settlement());
		Settlements.useForTest(store, dir.resolve("steward-settlements.json"));
		r = SettlementRunner.restoredForTest(saved);
		assertTrue((boolean) call("persist", new Class<?>[0]), "the build's first save");
		return r;
	}

	Object call(String method, Class<?>[] types, Object... args) {
		try {
			Method m = SettlementRunner.class.getDeclaredMethod(method, types);
			m.setAccessible(true);
			return m.invoke(r, args);
		} catch (InvocationTargetException e) {
			tails.add(e.getCause());
			return null;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	void quoteModules() {
		call("quoteModules", new Class<?>[] { Map.class }, new TreeMap<>(ENTRIES));
		drain();
	}

	void refusedAtPutDown() {
		call("realised", new Class<?>[] { String.class, Throwable.class, int.class }, null,
			new ArchitectRefused(Reason.BILL_CAP, "the bill at put-down is past its maxBill"), Session.current());
		drain();
	}

	void drain() {
		for (int ran = 0; !a.serverQueue.isEmpty(); ran++) {
			if (ran > 100) throw new AssertionError("never settles: " + a.calls);
			try {
				a.serverQueue.remove(0).run();
			} catch (RuntimeException | LinkageError e) {
				tails.add(e);
			}
		}
	}

	Object field(String name) {
		try {
			Field f = SettlementRunner.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(r);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	ModuleOrder order() {
		@SuppressWarnings("unchecked")
		var o = (Map<String, ModuleOrder>) field("moduleOrders");
		return o.get(LOT);
	}

	Pipeline.Phase phase() {
		return ((Pipeline.State) field("state")).phase();
	}

	/** The order the build's file holds now (a crash's view). */
	ModuleOrder disk() {
		try {
			return BuildStore.load(buildsFile).all().stream().filter(b -> b.settlementId().equals(saved.settlementId())).findFirst().orElseThrow().moduleOrders().get(LOT);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static Quote quote(int dirt, boolean exact) {
		return new Quote(VPLAN, null, TOWN, true, ComposedRunnerOrderTest.json("{\"minecraft:dirt\": " + dirt + "}"), new JsonObject(), Map.of(), exact,
			exact ? List.of() : List.of("a lot may differ"));
	}

	// ----------------------------------------------------------------- the quote

	@Test
	void aBillPastTheBoundIsHeldAndPutToThePlayerOnlyWithItsHoldOnDisk() {
		runner(BOUNDED, Pipeline.Phase.READY_TO_PLACE);
		a.quote = quote(150, true);
		List<ModuleOrder> atPrompt = new ArrayList<>();
		try (LogCapture log = LogCapture.open()) {
			log.onLine = l -> { if (l.startsWith("settlement: ") && l.contains(HOLD_WORDS)) atPrompt.add(disk()); };
			quoteModules();
		}
		assertEquals(1, a.count("regions.quote " + VPLAN), a.calls.toString());
		assertEquals(1, atPrompt.size(), "put to the player once: " + a.calls + " " + tails);
		assertNotNull(atPrompt.get(0).held(), "asked before the hold was saved");
		assertEquals(150, atPrompt.get(0).held().get("minecraft:dirt").getAsInt(), "the bill shown is the one saved");
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
		assertEquals(0, a.count("regions.planByKey"), "went on past a hold: " + a.calls);
	}

	@Test
	void aClearedQuoteDropsItsHoldOnDiskBeforeTheRealiseGoesOn() {
		// held earlier, quoted again exactly within the bound: the hold dropped and saved, then the realise's own steps (the growth plan's lookup)
		runner(BOUNDED.holding(ComposedRunnerOrderTest.json("{\"minecraft:dirt\": 1}")), Pipeline.Phase.READY_TO_PLACE);
		a.quote = quote(120, true);
		List<ModuleOrder> atNext = new ArrayList<>();
		a.onCall = c -> { if (c.startsWith("regions.planByKey")) atNext.add(disk()); };
		quoteModules();
		assertEquals(1, atNext.size(), "went on to the realise: " + a.calls + " " + tails);
		assertNull(atNext.get(0).held(), "went on before the dropped hold was saved");
		assertNull(order().held());
		assertNotEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
	}

	@Test
	void anArchitectWithoutMaxBillIsNeverQuotedOrRealised() {
		var f = new HashSet<>(a.features);
		f.remove(SettlementRunner.MAX_BILL);
		a.features = Set.copyOf(f);
		runner(BOUNDED, Pipeline.Phase.READY_TO_PLACE);
		a.quote = quote(120, true);
		try (LogCapture log = LogCapture.open()) {
			quoteModules();
			assertTrue(ComposedRunnerOrderTest.said(log, "it has no maxBill"), log.lines.toString());
		}
		assertEquals(0, a.count("regions.quote"), a.calls.toString());
		assertEquals(0, a.count("regions.planByKey"));
	}

	@Test
	void theQuotesAnswerIsActedOnOnlyOnTheServer() {
		runner(BOUNDED, Pipeline.Phase.READY_TO_PLACE);
		a.quote = quote(150, true);
		call("quoteModules", new Class<?>[] { Map.class }, new TreeMap<>(ENTRIES));
		// FakeArchitect answers at once: the answer handed to the server, nothing acted on until it runs
		assertEquals(1, a.serverQueue.size(), "the answer acted on off the server's queue: " + a.calls);
		assertTrue((boolean) field("moduleBusy"));
		assertEquals(Pipeline.Phase.READY_TO_PLACE, phase());
		assertNull(order().held());
		drain();
		assertFalse((boolean) field("moduleBusy"));
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
	}

	// ----------------------------------------------------------------- a realise refused at put-down (QD-2)

	@Test
	void aPutDownRefusalIsSavedBeforeThePlayerIsToldAndBeforeItIsAskedAgain() {
		runner(BOUNDED, Pipeline.Phase.PLACING);
		List<ModuleOrder> atSaid = new ArrayList<>(), atNext = new ArrayList<>();
		a.onCall = c -> { if (c.startsWith("regions.planByKey")) atNext.add(disk()); };
		try (LogCapture log = LogCapture.open()) {
			log.onLine = l -> { if (l.startsWith("settlement: ") && l.contains("I'll ask you again")) atSaid.add(disk()); };
			refusedAtPutDown();
			assertTrue(log.has("refused at put-down"), log.lines.toString());
		}
		assertEquals(1, atSaid.size(), tails.toString());
		assertTrue(atSaid.get(0).refusedAtPutDown(), "told before the refusal was saved");
		assertEquals(1, atNext.size(), "asked again (the realise's own steps): " + a.calls + " " + tails);
		assertTrue(atNext.get(0).refusedAtPutDown(), "asked again before the refusal was saved");
		assertTrue(order().refusedAtPutDown());
		assertNull(field("quoteCleared"), "a cleared quote kept across the refusal");
	}

	@Test
	void aPutDownRefusalThatCantBeSavedAsksNothingAgainAndStops() {
		for (var fail : List.of(DurableWrite.TestFail.BEFORE_RENAME, DurableWrite.TestFail.AFTER_RENAME)) {
			runner(BOUNDED, Pipeline.Phase.PLACING);
			a.calls.clear();
			DurableWrite.FAIL_NEXT.add(fail);
			try (LogCapture log = LogCapture.open()) {
				refusedAtPutDown();
				assertTrue(ComposedRunnerOrderTest.said(log, "that could not be saved, so I won't ask again or build it"), fail + ": " + log.lines);
				assertFalse(ComposedRunnerOrderTest.said(log, "I'll ask you again"), fail + ": " + log.lines);
			}
			assertEquals(0, a.count("regions.planByKey"), fail + ": asked again on an unsaved refusal: " + a.calls);
			assertEquals(0, a.count("regions.quote"), fail + ": quoted again: " + a.calls);
			assertNotEquals(Pipeline.Phase.PLACING, phase(), fail + ": still placing");
			SettlementRunner.clearForTest();
		}
	}
}
