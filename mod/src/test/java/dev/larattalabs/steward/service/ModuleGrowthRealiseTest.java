package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.LotSpec;
import dev.larattalabs.architect.api.Mode;
import dev.larattalabs.architect.api.Quote;
import dev.larattalabs.architect.api.RegionPlan;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.module.Canon;
import dev.larattalabs.steward.module.ModuleBinding;
import dev.larattalabs.steward.module.ModuleInstalls;
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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (FENCE-TESTS-4) A module growth of a town from materials (slice M3, QD, QD-2) driven through SettlementRunner's own steps to its realise
 * ({@code Regions.realise}, reached with no server through ArchitectAccess's world seam): the growth plan found by its key, the module lot's saved
 * binding kept, its quote, the hold put to the player and approved, the realise sent under the approved bound as {@code maxBill}, a put-down refusal
 * saved and asked again, and a realise that throws on the call. A crash at each save point resumes from the files alone and never draws twice or past
 * the bound; a save that fails (before or after its rename) goes no further and leaves the runner and the shared store as saved. After every
 * scenario the stockpile and the world balance (items in == items out), and nothing drew past the {@code maxBill} it was sent or the bound saved.
 */
class ModuleGrowthRealiseTest {
	@TempDir
	Path dir;
	FakeArchitect a;
	Path buildsFile, settlementsFile;
	SettlementRunner r;
	BuildStore.Saved saved;
	final List<Throwable> tails = new ArrayList<>();
	final Map<String, Integer> stock0 = new TreeMap<>();

	static final String LOT = ModuleGrowthOrderTest.LOT, VPLAN = ModuleGrowthOrderTest.VPLAN, TOWN = ModuleGrowthOrderTest.TOWN, ENTRY = "e_farm";
	/** The farm's approved bound: 130 dirt. */
	static final ModuleOrder BOUNDED = ModuleGrowthOrderTest.BOUNDED;
	static final String HOLD_WORDS = ModuleGrowthOrderTest.HOLD_WORDS;
	/** The growth plan's module lot, as Architect's plan holds it (found by the growth's plan key). */
	static final LotSpec SPEC = new LotSpec(LOT, "s1", new BoundingBox(0, 64, 0, 8, 90, 8), 64, null, "crop farm", null, new JsonObject());

	@BeforeEach
	void setUp() {
		a = new FakeArchitect();
		a.inline = false;
		var f = new HashSet<>(a.features);
		f.add(SettlementRunner.MAX_BILL);
		a.features = Set.copyOf(f);
		// the town, built from materials and fully built: it may grow from its stockpile
		var town = a.region(TOWN, "owner");
		town.fromMaterials = true;
		town.built = true;
		a.install();
		// no server: a world that builds from materials, the town's stockpile standing, no player online
		ArchitectAccess.useWorldForTest(true, true);
		ArchitectAccess.usePlayersForTest(id -> null);
		buildsFile = dir.resolve("steward-builds.json");
		settlementsFile = dir.resolve("steward-settlements.json");
		SettlementRunner.useBuildsFileForTest(buildsFile);
		ComposedPlacingCrashTest.setBuilds(new BuildStore());
		stock0.put("minecraft:dirt", 1000);
		stock0.forEach(a.ledger::add);
		// what Architect takes at put-down for the farm's lot
		a.putDown.put(LOT, Map.of("minecraft:dirt", 120));
	}

	/** Nothing was thrown past the runner's steps but what needs the server this test has none of (the inbox's send, the steward's walk). */
	@AfterEach
	void onlyServerlessTails() {
		for (Throwable t : tails)
			assertTrue(t instanceof NullPointerException && String.valueOf(t.getMessage()).contains("\"server\" is null"), "unexpected: " + tails());
	}

	@AfterEach
	void tearDown() {
		SettlementRunner.clearForTest();
		SettlementRunner.useBuildsFileForTest(null);
		ArchitectAccess.reset();
		Settlements.resetForTest();
		DurableWrite.FAIL_NEXT.clear();
		ComposedPlacingCrashTest.setBuilds(new BuildStore());
	}

	// ----------------------------------------------------------------- the build, its world and a crash

	/** A module growth of town {@link #TOWN} with this order, its lot bound (as saved at its first bind), in {@code phase}: saved to both files. */
	SettlementRunner runner(ModuleOrder order, Pipeline.Phase phase) throws IOException {
		var b = BuildStoreTestAccess.saved();
		var st = Pipeline.step(b.state(), new Pipeline.GroupUpdate("grp_1", "done", Map.of(), List.of(), 3.1, null), Permission.PROPOSALS).next();
		if (phase == Pipeline.Phase.PLACING) st = Pipeline.step(st, new Pipeline.ModulePlace(), Permission.PROPOSALS).next();
		assertEquals(phase, st.phase());
		var plan = new VillageLayout.Plan(List.of(new VillageLayout.Lot(LOT, "crop_farm", 0, 0, 9, 9, VillageLayout.Front.SOUTH, 64, "crop farm", null, false,
			null, 0, false)), 20, 0, 40, 64, List.of());
		var binding = new ModuleBinding(order.moduleId(), order.version(), order.programSha(), order.params(), ENTRY, null, 0,
			Canon.sha(ModulePlacement.geometry(SPEC)), ModuleInstalls.lotKey(b.buildId(), LOT, 1), b.settlement().owner());
		saved = new BuildStore.Saved(b.settlementId(), b.buildId(), b.playerId(), b.dimension(), b.permission(), b.landmarks(), b.settlement(), st, plan,
			b.bibleJobId(), null, b.batchId(), b.siteGroupId(), b.landmarkIds(), b.decided(), b.lastAwaiting(), false, null, null, true, VPLAN, null, null,
			TOWN, null, null, "vgrow", Map.of(LOT, binding), Map.of(LOT, order), null);
		SettlementStore store = new SettlementStore();
		store.put(saved.settlement());
		store.save(settlementsFile);
		Settlements.useForTest(store, settlementsFile);
		a.realiseOwner = saved.settlement().owner();
		// the growth plan, found by its key
		a.plansByKey.put("steward." + b.buildId() + ".vgrow", new RegionPlan(VPLAN, "prog", "psha", "ir", "survey", 1L, List.of(SPEC), List.of("s1"), Map.of(),
			null, List.of()));
		r = SettlementRunner.restoredForTest(saved);
		assertTrue((boolean) call("persist", new Class<?>[0]), "the build's first save");
		return r;
	}

	/** The world stops and starts again: a new session, no runner, the queue gone, the build read back from its files alone and restored. */
	void crash() throws IOException {
		SettlementRunner.clearForTest();
		Session.nextForTest();
		a.serverQueue.clear();
		a.held.clear();
		DurableWrite.FAIL_NEXT.clear();
		ComposedPlacingCrashTest.durableWriteRestart();
		BuildStore disk = BuildStore.load(buildsFile);
		ComposedPlacingCrashTest.setBuilds(disk);
		Settlements.useForTest(SettlementStore.load(settlementsFile), settlementsFile);
		saved = disk.all().stream().filter(x -> x.settlementId().equals(saved.settlementId())).findFirst().orElseThrow(() -> new AssertionError("the build is not on disk"));
		r = SettlementRunner.restoredForTest(saved);
		assertTrue(r.aliveForTest());
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

	/** The runner's own placing step ({@code realiseVillage}): the plan by key, the binding, the quote, and when it clears, the realise. */
	void place() {
		call("realiseVillage", new Class<?>[0]);
		drain();
	}

	/** The player's approve: what it answered (the inbox's send, after it, needs the server). */
	String approve() {
		String said;
		try {
			said = r.approve();
		} catch (NullPointerException e) {
			tails.add(e);
			said = "Approved (the inbox's send needs a server)";
		}
		drain();
		return said;
	}

	void drain() {
		for (int ran = 0; !a.serverQueue.isEmpty(); ran++) {
			if (ran > 200) throw new AssertionError("never settles: " + a.calls.subList(Math.max(0, a.calls.size() - 12), a.calls.size()));
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

	/** The build as its file holds it now (what a crash would find). */
	BuildStore.Saved disk() {
		try {
			return BuildStore.load(buildsFile).all().stream().filter(b -> b.settlementId().equals(saved.settlementId())).findFirst().orElseThrow();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The order the shared build store holds now (what another build's save would write). */
	ModuleOrder shared() {
		try {
			Field f = SettlementRunner.class.getDeclaredField("builds");
			f.setAccessible(true);
			return ((BuildStore) f.get(null)).all().stream().filter(b -> b.settlementId().equals(saved.settlementId())).findFirst().orElseThrow().moduleOrders().get(LOT);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	static Quote quote(int dirt, boolean exact) {
		return ModuleGrowthOrderTest.quote(dirt, exact);
	}

	String tails() {
		var out = new StringBuilder();
		for (Throwable t : tails) {
			out.append(t).append(" at");
			java.util.Arrays.stream(t.getStackTrace()).filter(f -> f.getClassName().startsWith("dev.larattalabs.steward")).limit(4).forEach(f -> out.append(' ').append(f));
			out.append("; ");
		}
		return out.toString();
	}

	// ----------------------------------------------------------------- conservation

	/** What the growth's standing sites drew, item by item. */
	Map<String, Integer> world() {
		Map<String, Integer> w = new TreeMap<>();
		for (var s : a.sites.values()) {
			var d = a.ledger.drawn.get(FakeArchitect.Ledger.key(s));
			if (d != null && !a.ledger.refunded.contains(FakeArchitect.Ledger.key(s))) d.forEach((k, v) -> w.merge(k, v, Integer::sum));
		}
		return w;
	}

	/**
	 * Items in == items out: the stockpile plus what stands is what the stockpile started with, the ledger's paid less returned is what stands, at most
	 * one realise drew, and what stands drew no item past the {@code maxBill} its realise was sent with, nor past the bound saved for the lot.
	 */
	void conserved() {
		var w = world();
		for (var e : stock0.entrySet())
			assertEquals(e.getValue(), a.ledger.stock.getOrDefault(e.getKey(), 0) + w.getOrDefault(e.getKey(), 0), "stockpile + world for " + e.getKey());
		assertEquals(a.ledger.paid - a.ledger.returned, w.values().stream().mapToInt(Integer::intValue).sum(), "ledger: paid - returned == what stands");
		if (w.isEmpty()) return;
		// the realise that drew: the first sent within its maxBill (one asked again under its key answers the same revision, drawing nothing)
		var drew = a.realises.stream().filter(x -> x.mode() == Mode.CONSTRUCTION && x.maxBill() != null
			&& a.putDown.get(LOT).entrySet().stream().allMatch(e -> e.getValue() <= x.maxBill().getOrDefault(e.getKey(), 0))).toList();
		assertFalse(drew.isEmpty(), "drew under no realise held to a maxBill: " + a.realises);
		assertEquals(1, drew.stream().map(dev.larattalabs.architect.api.RealiseRequest::opKey).distinct().count(), "drew under two keys: " + a.realises);
		assertEquals(a.putDown.get(LOT), w, "drew once, its put-down bill");
		ModuleOrder bounded;
		try {
			bounded = BuildStore.load(buildsFile).all().stream().filter(b -> b.settlementId().equals(saved.settlementId())).findFirst().map(b -> b.moduleOrders().get(LOT))
				.orElse(saved.moduleOrders().get(LOT));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		for (var e : w.entrySet()) {
			assertTrue(e.getValue() <= drew.get(0).maxBill().getOrDefault(e.getKey(), 0), "drew " + e + " past the maxBill sent " + drew.get(0).maxBill());
			var bound = bounded.bound();
			assertTrue(bound != null && e.getValue() <= (bound.has(e.getKey()) ? bound.get(e.getKey()).getAsInt() : 0), "drew " + e + " past its saved bound " + bound);
		}
	}

	// ----------------------------------------------------------------- the realise under its approved bound (item 5: the growth's maxBill send)

	@Test
	void aModuleGrowthFromMaterialsIsRealisedUnderItsApprovedBoundAsMaxBillWithItsOrderOnDisk() throws IOException {
		runner(BOUNDED, Pipeline.Phase.PLACING);
		a.quote = quote(120, true);
		List<BuildStore.Saved> atSend = new ArrayList<>();
		a.onCall = c -> { if (c.startsWith("regions.realise ")) atSend.add(disk()); };
		place();
		assertEquals(1, a.realises.size(), "realised once: " + a.calls + " " + tails());
		var sent = a.realises.get(0);
		assertEquals(Mode.CONSTRUCTION, sent.mode(), "a growth of a town from materials is built from them");
		assertEquals(Map.of("minecraft:dirt", 130), sent.maxBill(), "the realise is held to the approved bound");
		assertEquals(Map.of(LOT, ENTRY), sent.lotEntries());
		assertEquals("steward." + saved.buildId() + ".vgrowrealise", sent.opKey());
		assertEquals(Pipeline.Phase.PLACING, atSend.get(0).state().phase(), "sent before the placing was saved");
		assertEquals(130, atSend.get(0).moduleOrders().get(LOT).bound().get("minecraft:dirt").getAsInt(), "the bound sent is the one saved");
		assertTrue((boolean) field("queueInFlight"), "the realise in flight");
		assertEquals(Map.of("minecraft:dirt", 120), world());
		conserved();
	}

	@Test
	void anArchitectThatLosesMaxBillBetweenTheQuoteAndTheRealiseIsNeverRealisedUncapped() throws IOException {
		runner(BOUNDED, Pipeline.Phase.PLACING);
		a.quote = quote(120, true);
		// the quote is asked with maxBill there; by the realise it is gone (an Architect swapped under the build)
		a.onCall = c -> {
			if (!c.startsWith("regions.quote ")) return;
			var f = new HashSet<>(a.features);
			f.remove(SettlementRunner.MAX_BILL);
			a.features = Set.copyOf(f);
		};
		try (LogCapture log = LogCapture.open()) {
			place();
			assertTrue(ComposedRunnerOrderTest.said(log, "the module's bill can't be held to what you approved"), log.lines + " " + tails());
		}
		assertEquals(0, a.realises.size(), "realised uncapped: " + a.calls);
		assertFalse((boolean) field("queueInFlight"), "left in flight");
		assertNotEquals(Pipeline.Phase.PLACING, phase());
		conserved();
	}

	// ----------------------------------------------------------------- a realise that throws on the call (item 3)

	static List<Throwable> syncThrows() {
		return List.of(new IllegalStateException("Architect broke on the call"), new NoSuchMethodError("Regions.realise"));
	}

	@Test
	void aGrowthRealiseThatThrowsOnTheCallIsAnsweredAsARefusalNeverLeftInFlight() throws IOException {
		// (FENCE-TESTS-3 leftover) realiseVillage called Regions.realise with nothing around it: a throw on the call escaped the step with the realise
		// marked in flight, so the build hung placing (a later realiseVillage returns on queueInFlight) and the player was never told
		for (Throwable t : syncThrows()) {
			runner(BOUNDED, Pipeline.Phase.PLACING);
			a.calls.clear();
			a.realises.clear();
			a.quote = quote(120, true);
			a.throwOn.put("regions.realise ", t);
			try (LogCapture log = LogCapture.open()) {
				place();
				assertTrue(ComposedRunnerOrderTest.said(log, "Architect could not place it in the town"), t + ": the player is told: " + log.lines + " " + tails());
			}
			assertEquals(1, a.realises.size(), t + ": the realise was asked " + a.calls);
			assertFalse((boolean) field("queueInFlight"), t + ": left in flight");
			assertNotEquals(Pipeline.Phase.PLACING, phase(), t + ": not stopped");
			assertTrue(world().isEmpty());
			conserved();
			// the crash: the build as saved never realises on its own
			crash();
			place();
			assertEquals(1, a.realises.size(), t + ": realised after the restart: " + a.calls);
			conserved();
			a.throwOn.clear();
			SettlementRunner.clearForTest();
		}
	}

	// ----------------------------------------------------------------- the quote's hold and clear whose save fails (item 1)

	@Test
	void aHoldWhoseWriteFailsIsNeverPutAndIsRolledBackInTheRunnerAndTheSharedStore() throws IOException {
		for (var fail : List.of(DurableWrite.TestFail.BEFORE_RENAME, DurableWrite.TestFail.AFTER_RENAME)) {
			runner(BOUNDED, Pipeline.Phase.READY_TO_PLACE);
			a.calls.clear();
			a.realises.clear();
			a.quote = quote(150, true);
			// the hold's save is the next write after the quote is asked; every write after it fails too (a full disk)
			a.onCall = c -> {
				if (!c.startsWith("regions.quote ")) return;
				DurableWrite.FAIL_NEXT.add(fail);
				for (int i = 0; i < 20; i++) DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME);
			};
			try (LogCapture log = LogCapture.open()) {
				place();
				assertFalse(ComposedRunnerOrderTest.said(log, HOLD_WORDS), fail + ": put to the player unsaved: " + log.lines);
				assertTrue(ComposedRunnerOrderTest.said(log, "could not be saved"), fail + ": " + log.lines + " " + tails());
			}
			a.onCall = c -> {};
			assertNull(order().held(), fail + ": the unsaved hold kept in the runner");
			assertNull(shared().held(), fail + ": the unsaved hold kept in the shared store (another build's save would write it)");
			assertNotEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), fail + ": waiting on an unsaved hold");
			assertEquals(0, a.realises.size(), fail + ": realised: " + a.calls);
			conserved();
			// the crash: whatever the file holds, nothing is realised without the player
			crash();
			a.quote = quote(150, true);
			place();
			assertEquals(0, a.realises.size(), fail + ": realised after the restart: " + a.calls);
			conserved();
			SettlementRunner.clearForTest();
		}
	}

	@Test
	void aClearedHoldWhoseWriteFailsGoesNoFurtherAndIsRolledBack() throws IOException {
		for (var fail : List.of(DurableWrite.TestFail.BEFORE_RENAME, DurableWrite.TestFail.AFTER_RENAME)) {
			// held earlier (approved since: the build placing), quoted again within the bound: the dropped hold's save fails
			runner(BOUNDED.holding(ComposedRunnerOrderTest.json("{\"minecraft:dirt\": 1}")), Pipeline.Phase.PLACING);
			a.calls.clear();
			a.realises.clear();
			a.quote = quote(120, true);
			a.onCall = c -> {
				if (!c.startsWith("regions.quote ")) return;
				DurableWrite.FAIL_NEXT.add(fail);
				for (int i = 0; i < 20; i++) DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME);
			};
			try (LogCapture log = LogCapture.open()) {
				place();
				assertTrue(ComposedRunnerOrderTest.said(log, "could not be saved"), fail + ": " + log.lines + " " + tails());
			}
			a.onCall = c -> {};
			assertEquals(0, a.realises.size(), fail + ": realised on an unsaved clear: " + a.calls);
			assertNull(field("quoteCleared"), fail + ": the quote counted as cleared");
			assertNotNull(order().held(), fail + ": the unsaved clear kept in the runner");
			assertNotNull(shared().held(), fail + ": the unsaved clear kept in the shared store");
			assertNotEquals(Pipeline.Phase.PLACING, phase(), fail + ": still placing");
			conserved();
			// the crash: realised at most once, within the bound, only after its own fresh quote clears
			crash();
			a.quote = quote(120, true);
			place();
			assertTrue(a.realises.size() <= 1, fail + ": " + a.calls);
			conserved();
			SettlementRunner.clearForTest();
			a.sites.clear();
			a.ledger.stock.clear();
			a.ledger.drawn.clear();
			a.ledger.paid = 0;
			a.realisedByKey.clear();
			stock0.forEach(a.ledger::add);
		}
	}

	// ----------------------------------------------------------------- a put-down refusal whose save fails (item 4)

	@Test
	void aPutDownRefusalThatCantBeSavedIsRolledBackInTheRunnerAndTheSharedStore() throws IOException {
		for (var fail : List.of(DurableWrite.TestFail.BEFORE_RENAME, DurableWrite.TestFail.AFTER_RENAME)) {
			runner(BOUNDED, Pipeline.Phase.PLACING);
			a.calls.clear();
			a.realises.clear();
			// Architect's bill at put-down (140 dirt) is past the approved bound (130): the realise is refused, nothing drawn; the refusal's save fails
			a.putDown.put(LOT, Map.of("minecraft:dirt", 140));
			a.quote = quote(120, true);
			a.onCall = c -> { if (c.startsWith("regions.realise ")) DurableWrite.FAIL_NEXT.add(fail); };
			try (LogCapture log = LogCapture.open()) {
				place();
				assertTrue(ComposedRunnerOrderTest.said(log, "that could not be saved, so I won't ask again or build it"), fail + ": " + log.lines + " " + tails());
			}
			a.onCall = c -> {};
			assertEquals(1, a.realises.size(), fail + " " + a.calls);
			assertFalse(order().refusedAtPutDown(), fail + ": the unsaved refusal kept in the runner");
			assertFalse(shared().refusedAtPutDown(), fail + ": the unsaved refusal kept in the shared store");
			assertEquals(1, a.count("regions.quote "), fail + ": asked again on an unsaved refusal: " + a.calls);
			assertNotEquals(Pipeline.Phase.PLACING, phase(), fail + ": still placing");
			conserved();
			// the crash: the build as its file holds it never realises on its own
			crash();
			place();
			assertEquals(1, a.realises.size(), fail + ": realised on its own after an unsaved refusal: " + a.calls);
			conserved();
			SettlementRunner.clearForTest();
			a.putDown.put(LOT, Map.of("minecraft:dirt", 120));
		}
	}

	// ----------------------------------------------------------------- a crash at each save point

	@Test
	void aCrashAtEachSavePointFromTheHoldToTheRealiseResumesFromItsFileAndDrawsOnceWithinTheBound() throws IOException {
		runner(BOUNDED, Pipeline.Phase.READY_TO_PLACE);
		// (1) the hold: the bill (125 dirt, inexact) saved held, then put to the player; the crash finds it held and waiting, nothing realised or drawn
		a.quote = quote(125, false);
		place();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), a.calls + " " + tails());
		crash();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
		assertEquals(125, order().held().get("minecraft:dirt").getAsInt(), "the hold survives the crash");
		assertEquals(0, a.realises.size());
		conserved();

		// (2) the approval: the held bill becomes the bound, saved before PLACING; the crash right after it finds PLACING under the 125 bound
		a.quoteHold = true;
		assertTrue(approve().startsWith("Approved"), tails());
		assertEquals(Pipeline.Phase.PLACING, disk().state().phase());
		assertEquals(125, disk().moduleOrders().get(LOT).bound().get("minecraft:dirt").getAsInt(), "the approved bill is the bound on disk");
		assertNull(disk().moduleOrders().get(LOT).held());
		crash();
		a.quoteHold = false;
		assertEquals(0, a.realises.size(), "realised before its own quote: " + a.calls);
		conserved();

		// (3) the resume quotes again (exact, within the new bound): cleared, then realised under the approved 125 as maxBill; its answer is lost to the
		// crash, so the resume finds the realise by its key and never sends a second that draws
		a.quote = quote(120, true);
		place();
		assertEquals(1, a.realises.size(), a.calls + " " + tails());
		assertEquals(Map.of("minecraft:dirt", 125), a.realises.get(0).maxBill());
		assertEquals(Map.of("minecraft:dirt", 120), world());
		conserved();
		crash();
		place();
		assertEquals(Map.of("minecraft:dirt", 120), world(), "drew twice after the restart: " + a.calls);
		conserved();
	}

	@Test
	void aPutDownRefusalSavedBeforeTheCrashIsPutToThePlayerAndNeverRealisedOnItsOwn() throws IOException {
		runner(BOUNDED, Pipeline.Phase.PLACING);
		a.putDown.put(LOT, Map.of("minecraft:dirt", 140));
		a.quote = quote(120, true);
		// the refusal is saved, then the quote is asked again: the world stops while it is in flight
		a.onCall = c -> { if (c.startsWith("regions.realise ")) a.quoteHold = true; };
		place();
		a.onCall = c -> {};
		a.quoteHold = false;
		assertEquals(1, a.realises.size(), a.calls + " " + tails());
		assertTrue(disk().moduleOrders().get(LOT).refusedAtPutDown(), "the refusal saved");
		assertEquals(Pipeline.Phase.PLACING, disk().state().phase());
		conserved();
		crash();
		place();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), "realised on its own after a refusal: " + a.calls + " " + tails());
		assertEquals(1, a.realises.size());
		conserved();
	}

	@Test
	void aPutDownRefusalWrittenThoughItsSaveFailedWithTheCancelUnsavedNeverRealisesOnItsOwn() throws IOException {
		// (item 2, the growth's side) the refusal lands in the file though its save said it failed (after its rename), and every write after it fails,
		// so the cancel's save never lands: the crash finds the refusal and PLACING, and puts the next quote to the player
		runner(BOUNDED, Pipeline.Phase.PLACING);
		a.putDown.put(LOT, Map.of("minecraft:dirt", 140));
		a.quote = quote(120, true);
		a.onCall = c -> {
			if (!c.startsWith("regions.realise ")) return;
			DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.AFTER_RENAME);
			for (int i = 0; i < 20; i++) DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME);
		};
		place();
		a.onCall = c -> {};
		assertTrue(DurableWrite.FAIL_NEXT.size() < 20, "a write after the refusal's was reached");
		assertEquals(Pipeline.Phase.PLACING, disk().state().phase(), "the cancel's save landed");
		assertTrue(disk().moduleOrders().get(LOT).refusedAtPutDown(), "the refusal is in the file");
		assertFalse(order().refusedAtPutDown(), "rolled back as saved");
		conserved();
		crash();
		place();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), "realised on its own: " + a.calls + " " + tails());
		assertEquals(1, a.realises.size());
		conserved();
	}
}
