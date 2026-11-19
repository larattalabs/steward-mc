package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.ApprovedRealise;
import dev.larattalabs.architect.api.Quote;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.module.ComposedModules;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (TESTABLE-ARCH; FENCE-TESTS' leftover, batch-077's gate-verifier) SettlementRunner's own composed steps, in its own call order, against FakeArchitect
 * through {@link ArchitectAccess}: the hold, the realise under its lot caps and the put-down re-ask are each saved to the build's file before they are
 * put to the player or sent to Architect, and a save that fails sends and asks nothing and puts the orders back. ComposedRunnerCrashTest drives
 * {@link ComposedSteps} alone; these drive the runner ({@code quoteComposed}, {@code realiseArtifact}, the realise's answer), so reversing an order at
 * the runner's call site fails here. Every "nothing went on" check also checks that the step was reached (its own line, or Architect's call), so a
 * test can't pass by stopping early.
 *
 * <p>The runner has no server here: the callbacks run on FakeArchitect's queue ({@link FakeArchitect#runServer}), and the steps after a decision that
 * need the server (the inbox, the steward's walk, a cancel's {@code CancelBatch} on Architect's real API) throw; {@link #drain} keeps those throws, and
 * every check is on what happened before them.
 */
class ComposedRunnerOrderTest {
	@TempDir
	Path dir;
	FakeArchitect a;
	Path buildsFile;
	SettlementRunner r;
	BuildStore.Saved saved;
	final List<Throwable> tails = new ArrayList<>();

	static final String SHA = "a".repeat(64);
	static final JsonObject PARTS = ComposedCrashPointsTest.PARTS;
	/** The realise's lot entries: the two module lots and one designed cottage. */
	static final Map<String, String> ENTRIES = Map.of("farm_1", "e_farm", "hall_1", "e_hall", "cottage_1", "e_cottage");
	/** The farm's bound and the hall's measured one: each lot's cap. */
	static final Map<String, ModuleOrder> BOUNDED = ComposedCrashPointsTest.orders(ComposedCrashPointsTest.farm("{\"minecraft:dirt\": 130}"),
		ComposedCrashPointsTest.hall(ComposedCrashPointsTest.HALL_BOUND));
	static final String HOLD_WORDS = "Approve to build it with these bills";

	static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@BeforeEach
	void setUp() {
		a = new FakeArchitect();
		a.inline = false;
		var f = new HashSet<>(a.features);
		f.add(ComposedModules.LOT_CAP_FEATURE);
		a.features = Set.copyOf(f);
		// approved in survival: built from the stockpile, so its module lots are capped
		a.artifacts.put(SHA, json("{\"survival\": true}"));
		a.install();
		buildsFile = dir.resolve("steward-builds.json");
		SettlementRunner.useBuildsFileForTest(buildsFile);
	}

	@AfterEach
	void tearDown() throws ReflectiveOperationException {
		SettlementRunner.clearForTest();
		SettlementRunner.useBuildsFileForTest(null);
		ArchitectAccess.reset();
		Settlements.resetForTest();
		DurableWrite.FAIL_NEXT.clear();
		Field cap = SettlementRunner.class.getDeclaredField("DEV_CAP_SHORT");
		cap.setAccessible(true);
		((java.util.concurrent.atomic.AtomicReference<?>) cap.get(null)).set(null);
	}

	/** A composed town ready to place (plan v1, approved artifact {@link #SHA} in survival) with these module orders, restored as this session's runner. */
	SettlementRunner runner(Map<String, ModuleOrder> orders) {
		var b = BuildStoreTestAccess.saved();
		var st = Pipeline.step(b.state(), new Pipeline.GroupUpdate("grp_1", "done", Map.of(), List.of(), 3.1, null), Permission.PROPOSALS).next();
		assertEquals(Pipeline.Phase.READY_TO_PLACE, st.phase());
		saved = new BuildStore.Saved(b.settlementId(), b.buildId(), b.playerId(), b.dimension(), b.permission(), b.landmarks(), b.settlement(), st, b.plan(),
			b.bibleJobId(), b.groupId(), b.batchId(), b.siteGroupId(), b.landmarkIds(), b.decided(), b.lastAwaiting(), false, null, null, true,
			dev.larattalabs.steward.gateway.PPlan.ARTIFACT_PREFIX + SHA, null, null, null, null, null, null, Map.of(), orders, null);
		SettlementStore store = new SettlementStore();
		store.put(saved.settlement());
		Settlements.useForTest(store, dir.resolve("steward-settlements.json"));
		r = SettlementRunner.restoredForTest(saved);
		assertTrue(r.aliveForTest());
		return r;
	}

	/** One of the runner's own steps, as its callbacks reach it. */
	void step(String method, Class<?>[] types, Object... args) {
		try {
			Method m = SettlementRunner.class.getDeclaredMethod(method, types);
			m.setAccessible(true);
			m.invoke(r, args);
		} catch (InvocationTargetException e) {
			tails.add(e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	void quoteComposed() {
		step("quoteComposed", new Class<?>[] { String.class, Map.class }, SHA, new java.util.TreeMap<>(ENTRIES));
	}

	void realiseArtifact() {
		step("realiseArtifact", new Class<?>[] { String.class, Map.class, net.minecraft.server.level.ServerPlayer.class, JsonObject.class, boolean.class, int.class },
			SHA, new LinkedHashMap<>(ENTRIES), null, new JsonObject(), true, Session.current());
	}

	/** Runs what was handed to the server, each to its end or its throw (kept in {@link #tails}). */
	void drain() {
		while (!a.serverQueue.isEmpty()) {
			try {
				a.serverQueue.remove(0).run();
			} catch (RuntimeException | LinkageError e) {
				tails.add(e);
			}
		}
	}

	@SuppressWarnings("unchecked")
	Map<String, ModuleOrder> orders() {
		return (Map<String, ModuleOrder>) field("moduleOrders");
	}

	Pipeline.Phase phase() {
		return ((Pipeline.State) field("state")).phase();
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

	/** The orders the build's file holds now (a crash's view). */
	Map<String, ModuleOrder> disk() {
		try {
			return BuildStore.load(buildsFile).all().stream().filter(b -> b.settlementId().equals(saved.settlementId())).findFirst().orElseThrow().moduleOrders();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static Quote quote(boolean exact, List<String> notes) {
		return new Quote("plan_c", SHA, null, true, new JsonObject(), PARTS.deepCopy(), Map.of(), exact, notes);
	}

	/** A cleared quote taken through the runner: the caps are this session's, and the cleared orders saved. */
	void cleared(Map<String, ModuleOrder> orders) {
		runner(orders);
		a.quote = quote(true, List.of());
		quoteComposed();
		drain();
		assertEquals(1, a.count("designs.group"), "cleared: went on to the realise " + a.calls + " " + tails);
	}

	static boolean said(LogCapture log, String part) {
		return log.lines.stream().anyMatch(l -> l.startsWith("settlement: ") && l.contains(part));
	}

	// ----------------------------------------------------------------- the hold

	@Test
	void theHoldIsPutToThePlayerWithItsOrdersOnDisk() {
		runner(BOUNDED);
		a.quote = quote(false, List.of("a lot may differ"));
		try (LogCapture log = LogCapture.open()) {
			quoteComposed();
			assertEquals(1, a.count("regions.quoteApproved"), a.calls.toString());
			drain();
			assertTrue(said(log, HOLD_WORDS), log.lines.toString());
		}
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
		var back = disk();
		assertTrue(back.values().stream().allMatch(o -> o.held() != null), "a crash finds the hold: " + back);
		assertEquals(orders(), back);
		assertEquals(0, a.count("designs.group"), "nothing realised while held");
		assertTrue(a.approved.isEmpty());
	}

	@Test
	void aHoldWhoseSaveFailsIsNeverPutToThePlayer() {
		runner(BOUNDED);
		a.quote = quote(false, List.of("a lot may differ"));
		try (LogCapture log = LogCapture.open()) {
			quoteComposed();
			assertEquals(1, a.count("regions.quoteApproved"), "the quote was asked for " + a.calls);
			var before = orders();
			// the hold's save fails (the quote's answer arrives as the disk fills)
			DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME);
			drain();
			assertTrue(said(log, "could not be saved with its working modules' bills"), "reached the hold's save: " + log.lines);
			assertFalse(said(log, HOLD_WORDS), "the hold was put to the player unsaved: " + log.lines);
			assertEquals(before, orders(), "the held orders are put back");
		}
		assertNotEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
		assertTrue(disk().values().stream().allMatch(o -> o.held() == null), "no hold on disk");
		assertEquals(0, a.count("designs.group"));
		assertTrue(a.approved.isEmpty());
	}

	// ----------------------------------------------------------------- the realise

	@Test
	void theRealiseIsSentOnlyOnceItsLotCapsAreOnDisk() throws IOException {
		// orders saved with an earlier hold (held bills), quoted again exactly within their bounds: cleared, the hold dropped
		var wasHeld = new LinkedHashMap<String, ModuleOrder>();
		BOUNDED.forEach((k, v) -> wasHeld.put(k, v.holding(json("{\"minecraft:dirt\": 1}"))));
		runner(wasHeld);
		List<Map<String, ModuleOrder>> atRealise = new ArrayList<>();
		a.onCall = c -> { if (c.startsWith("designs.group")) atRealise.add(disk()); };
		a.quote = quote(true, List.of());
		quoteComposed();
		drain();
		assertEquals(1, atRealise.size(), a.calls + " " + tails);
		// (quoteComposed) the cleared orders were on disk when it went on to the realise
		assertTrue(atRealise.get(0).values().stream().allMatch(o -> o.held() == null), "went on before the cleared orders were saved: " + atRealise.get(0));
		assertEquals(orders(), atRealise.get(0));

		// (realiseArtifact) the file overwritten out of band with other bounds: the realise must save its own orders before it is sent
		var stale = new LinkedHashMap<String, ModuleOrder>();
		BOUNDED.forEach((k, v) -> stale.put(k, v.resized(v.params(), json("{\"minecraft:dirt\": 1}"))));
		BuildStore other = new BuildStore();
		other.put(saved.withModuleOrders(stale));
		other.save(buildsFile);
		List<Map<String, ModuleOrder>> atSend = new ArrayList<>();
		a.onCall = c -> { if (c.startsWith("regions.realiseApproved")) atSend.add(disk()); };
		realiseArtifact();
		assertEquals(1, a.approved.size(), a.calls + " " + tails);
		ApprovedRealise sent = a.approved.get(0);
		assertNotNull(sent.maxBillPerLot(), "sent capped per lot");
		assertEquals(Map.of("minecraft:dirt", 130), sent.maxBillPerLot().get("farm_1"));
		assertEquals(Set.of("farm_1", "hall_1"), sent.maxBillPerLot().keySet());
		assertNull(sent.maxBill(), "no region-wide cap");
		assertEquals(dev.larattalabs.architect.api.Mode.CONSTRUCTION, sent.mode());
		assertEquals(1, atSend.size());
		assertEquals(sent.maxBillPerLot(), ComposedModules.caps(PARTS, atSend.get(0), ENTRIES.keySet()).perLot(), "sent before its lot caps were on disk");
	}

	@Test
	void aRealiseWhoseSaveFailsIsNeverSent() {
		cleared(BOUNDED);
		try (LogCapture log = LogCapture.open()) {
			DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME);
			realiseArtifact();
			assertTrue(said(log, "the build could not be saved before placing"), "reached the realise's save: " + log.lines + " " + tails);
		}
		assertEquals(0, a.count("regions.realiseApproved"), a.calls.toString());
		assertTrue(a.approved.isEmpty());
	}

	@Test
	void aClearedQuoteWhoseSaveFailsGoesNoFurtherAndPutsTheOrdersBack() {
		var wasHeld = new LinkedHashMap<String, ModuleOrder>();
		BOUNDED.forEach((k, v) -> wasHeld.put(k, v.holding(json("{\"minecraft:dirt\": 1}"))));
		runner(wasHeld);
		a.quote = quote(true, List.of());
		try (LogCapture log = LogCapture.open()) {
			quoteComposed();
			assertEquals(1, a.count("regions.quoteApproved"), a.calls.toString());
			var before = orders();
			DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME);
			drain();
			assertTrue(said(log, "could not be saved with its working modules' bills"), "reached the cleared save: " + log.lines);
			assertEquals(before, orders(), "the cleared orders are not kept");
		}
		assertEquals(0, a.count("designs.group"), "went on to the realise unsaved: " + a.calls);
		assertTrue(a.approved.isEmpty());
		assertNull(field("composedCaps"), "no caps kept for a realise");
	}

	@Test
	void aRealiseArchitectRefusesIsSaidInTheStewardsWordsNeverItsText() {
		// (place-words) a refusal at the realise other than BILL_CAP: the steward's words for its typed reason, Architect's text to the log only
		cleared(BOUNDED);
		a.realiseFailure = new dev.larattalabs.architect.api.ArchitectRefused(dev.larattalabs.architect.api.Reason.PROTECTED, "SENTINEL-RAW area 'Grandma's garden' r.0.0.mca");
		try (LogCapture log = LogCapture.open()) {
			realiseArtifact();
			assertEquals(1, a.approved.size(), a.calls + " " + tails);
			drain();
			assertTrue(said(log, "Architect could not place the village: it is in a protected area."), log.lines.toString());
			assertTrue(log.lines.stream().noneMatch(l -> l.startsWith("settlement: ") && l.contains("SENTINEL")), log.lines.toString());
			assertTrue(log.has("SENTINEL-RAW"), "the raw text is logged: " + log.lines);
		}
	}

	// ----------------------------------------------------------------- the put-down re-ask

	@Test
	void aPutDownRefusalIsSavedBeforeItIsAskedAgain() {
		cleared(BOUNDED);
		a.realiseAnswer = FakeArchitect.Answer.REFUSE;
		a.capDetail = "lot:hall_1";
		List<Map<String, ModuleOrder>> atReask = new ArrayList<>();
		a.onCall = c -> { if (c.startsWith("designs.group")) atReask.add(disk()); };
		try (LogCapture log = LogCapture.open()) {
			realiseArtifact();
			assertEquals(1, a.approved.size(), a.calls + " " + tails);
			drain();
			assertTrue(log.has("realise refused at put-down"), "reached the refusal: " + log.lines);
		}
		assertEquals(1, atReask.size(), "asked again: " + a.calls + " " + tails);
		assertTrue(atReask.get(0).values().stream().allMatch(ModuleOrder::refusedAtPutDown), "asked again before the refusal was saved: " + atReask.get(0));
		assertTrue(orders().values().stream().allMatch(ModuleOrder::refusedAtPutDown));
		assertNull(field("quoteCleared"), "quoted again before any realise");
	}

	@Test
	void aPutDownRefusalThatCantBeSavedStopsAndPutsTheOrdersBack() {
		cleared(BOUNDED);
		var before = orders();
		a.realiseAnswer = FakeArchitect.Answer.REFUSE;
		a.capDetail = "lot:farm_1";
		int groups = (int) a.count("designs.group");
		// the refusal's save is the next write after the realise is sent
		a.onCall = c -> { if (c.startsWith("regions.realiseApproved")) DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME); };
		try (LogCapture log = LogCapture.open()) {
			realiseArtifact();
			assertEquals(1, a.approved.size(), a.calls + " " + tails);
			drain();
			assertTrue(log.has("realise refused at put-down"), "reached the refusal: " + log.lines);
			assertTrue(said(log, "that could not be saved, so I won't ask again or build it"), log.lines.toString());
		}
		assertEquals(groups, a.count("designs.group"), "asked again on an unsaved refusal: " + a.calls);
		assertEquals(before, orders(), "the orders are put back as saved");
		assertTrue(disk().values().stream().noneMatch(ModuleOrder::refusedAtPutDown));
		assertEquals(1, a.approved.size(), "nothing more sent");
	}
}
