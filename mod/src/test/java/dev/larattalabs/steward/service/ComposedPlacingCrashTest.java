package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.Quote;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.module.ComposedModules;
import dev.larattalabs.steward.module.ModuleBound;
import dev.larattalabs.steward.module.ModuleOrder;
import dev.larattalabs.steward.pipeline.Pipeline;
import dev.larattalabs.steward.util.DurableWrite;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (CRASH-T; M5-HARD, batch-075's GPT review) A composed town from materials, driven through SettlementRunner's own steps over real files (the builds
 * file and the settlements file, each saved by DurableWrite), with Architect's realise applied and drawn from its stockpile ledger as it is sent
 * (FakeArchitect {@code materials}): the hold, the player's approval, the cleared quote, the realise under its lot caps, a put-down refusal and the
 * region taken as the build's. A crash at each of those save points is a restart from the files alone (a new session, the runner and its shared
 * build store read back, the old session's answers dead) and the restart's own resume ({@code resync}), PLACING included. A failed write (before or
 * after its rename) at a save point sends, draws and asks nothing, and rolls the step back in the runner and in the shared store, so another build's
 * save never writes it. After every scenario the stockpile, the world and the ledger balance: what the stockpile holds plus what the town's standing
 * sites drew is what it started with, no module lot drew past the cap it was sent or the bound saved for it, and an undo gives back exactly what was
 * drawn (items in == items out).
 *
 * <p>No server: the runner's callbacks run on FakeArchitect's queue ({@link #drain}); a step past what the fake models (the realise's design-group
 * probe) stops there, and each check reads what happened before it.
 */
class ComposedPlacingCrashTest {
	@TempDir
	Path dir;
	FakeArchitect a;
	Path buildsFile, settlementsFile;
	SettlementRunner r;
	BuildStore.Saved saved;
	final List<Throwable> tails = new ArrayList<>();
	final Map<String, Integer> stock0 = new TreeMap<>();

	static final String SHA = ComposedRunnerOrderTest.SHA;
	static final JsonObject PARTS = ComposedCrashPointsTest.PARTS;
	static final Map<String, String> ENTRIES = ComposedRunnerOrderTest.ENTRIES;
	/** The farm's bound (130 dirt) and the hall's measured one (100 planks, 41 of each fill item): each module lot's cap. */
	static final Map<String, ModuleOrder> BOUNDED = ComposedRunnerOrderTest.BOUNDED;
	static final String FARM = "farm_1", HALL = "hall_1", COTTAGE = "cottage_1";

	@BeforeEach
	void setUp() {
		a = new FakeArchitect();
		a.inline = false;
		var f = new HashSet<>(a.features);
		f.add(ComposedModules.LOT_CAP_FEATURE);
		a.features = Set.copyOf(f);
		// (CRASH-T2) one consistent composed town: the artifact's lots (each module lot the module's own frame, the farm's watered), the build's plan of
		// the same lots, each module lot's binding saved with the build, and the design group with the cottage's entry, so the runner's own steps
		// (the realise, composedReady, its quote and its send) run unaided, also after a restart
		a.artifacts.put(SHA, artifact());
		a.groups.put("grp_1", group());
		a.materials = true;
		a.realiseAnswer = FakeArchitect.Answer.APPLY;
		a.install();
		// no server: the realise's player lookup answers no player online (the realise is sent with no actor)
		ArchitectAccess.usePlayersForTest(id -> null);
		buildsFile = dir.resolve("steward-builds.json");
		settlementsFile = dir.resolve("steward-settlements.json");
		SettlementRunner.useBuildsFileForTest(buildsFile);
		// the town's stockpile: enough for everything below
		for (var item : List.of("minecraft:dirt", "minecraft:oak_planks", "minecraft:cobblestone", "minecraft:iron_ingot", "minecraft:wheat", "minecraft:coal"))
			stock0.put(item, 1000);
		stock0.forEach(a.ledger::add);
		// what Architect takes at put-down: the farm's lot, the hall's lot and fill (as quoted), and a designed cottage (never capped)
		a.putDown.put(FARM, Map.of("minecraft:dirt", 120));
		a.putDown.put(HALL, Map.of("minecraft:oak_planks", 90, "minecraft:cobblestone", 41, "minecraft:iron_ingot", 41, "minecraft:wheat", 41, "minecraft:coal", 41));
		a.putDown.put(COTTAGE, Map.of("minecraft:cobblestone", 30));
	}

	/**
	 * Nothing was thrown past the runner's steps but what needs the server this test has none of (the inbox's send, the steward's walk, a player's
	 * lookup): every check reads what happened before those, so anything else thrown is a step that broke, never a pass.
	 */
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
		setBuilds(new BuildStore());
	}

	// ------------------------------------------------------------------ the world, the runner and a crash

	/** A composed town ready to place (plan v1, artifact {@link #SHA} in survival) with these orders: saved to both files, this session's runner. */
	SettlementRunner runner(Map<String, ModuleOrder> orders) throws IOException {
		var b = BuildStoreTestAccess.saved();
		var st = Pipeline.step(b.state(), new Pipeline.GroupUpdate("grp_1", "done", Map.of(), List.of(), 3.1, null), Permission.PROPOSALS).next();
		assertEquals(Pipeline.Phase.READY_TO_PLACE, st.phase());
		saved = new BuildStore.Saved(b.settlementId(), b.buildId(), b.playerId(), b.dimension(), b.permission(), b.landmarks(), b.settlement(), st, b.plan(),
			b.bibleJobId(), b.groupId(), b.batchId(), b.siteGroupId(), b.landmarkIds(), b.decided(), b.lastAwaiting(), false, null, null, true,
			dev.larattalabs.steward.gateway.PPlan.ARTIFACT_PREFIX + SHA, null, null, null, null, null, null, bindings(b.buildId(), b.settlement().owner(), orders), orders, null);
		saved = new BuildStore.Saved(saved.settlementId(), saved.buildId(), saved.playerId(), saved.dimension(), saved.permission(), saved.landmarks(), saved.settlement(),
			saved.state(), plan(), saved.bibleJobId(), saved.groupId(), saved.batchId(), saved.siteGroupId(), saved.landmarkIds(), saved.decided(), saved.lastAwaiting(),
			false, null, null, true, saved.vPlan(), null, null, null, null, null, null, saved.moduleBindings(), orders, null);
		SettlementStore store = new SettlementStore();
		store.put(saved.settlement());
		store.save(settlementsFile);
		Settlements.useForTest(store, settlementsFile);
		a.realiseOwner = saved.settlement().owner();
		setBuilds(new BuildStore());
		r = SettlementRunner.restoredForTest(saved);
		assertTrue((boolean) call("persist", new Class<?>[0]), "the build's first save");
		return r;
	}

	// ------------------------------------------------------------------ the composed town's fixture (CRASH-T2)

	static final dev.larattalabs.steward.module.ModuleKind FARM_KIND = dev.larattalabs.steward.module.ModuleKind.of("crop_farm").orElseThrow(),
		HALL_KIND = dev.larattalabs.steward.module.ModuleKind.of("storage_hall").orElseThrow();
	static final int[] FARM_FRAME = FARM_KIND.frame(BOUNDED.get(FARM).params()), HALL_FRAME = HALL_KIND.frame(BOUNDED.get(HALL).params());

	/** The approved artifact's lot {@code id}, facing south at {@code x0}: a box of {@code w} by {@code d} (its frame seen from its front). */
	static JsonObject artifactLot(String id, int x0, int w, int d) {
		return ComposedRunnerOrderTest.json("{\"id\": \"" + id + "\", \"front\": \"south\", \"box\": [" + x0 + ", 64, 0, " + (x0 + w - 1) + ", 90, " + (d - 1) + "]}");
	}

	/** The farm's lot (watered by IRR for its frame), the hall's, and the cottage's, in an artifact checked in survival. */
	static JsonObject artifact() {
		var farm = artifactLot(FARM, 0, FARM_FRAME[0], FARM_FRAME[1]);
		farm.add("irrigation", ComposedRunnerOrderTest.json("{\"frame\": [" + FARM_FRAME[0] + ", " + FARM_FRAME[1] + "], \"lotCells\": [[5, 6, 0, 0]]}"));
		var lots = new com.google.gson.JsonArray();
		lots.add(farm);
		lots.add(artifactLot(HALL, 40, HALL_FRAME[0], HALL_FRAME[1]));
		lots.add(artifactLot(COTTAGE, 80, 9, 9));
		var body = ComposedRunnerOrderTest.json("{\"survival\": true}");
		body.add("lots", lots);
		return body;
	}

	/** The build's plan: the artifact's three lots (the two module lots and the designed cottage). */
	static dev.larattalabs.steward.layout.VillageLayout.Plan plan() {
		var south = dev.larattalabs.steward.layout.VillageLayout.Front.SOUTH;
		var lots = List.of(
			new dev.larattalabs.steward.layout.VillageLayout.Lot(FARM, "crop_farm", 0, 0, FARM_FRAME[0], FARM_FRAME[1], south, 64, "crop farm", null, false, null, 0, false),
			new dev.larattalabs.steward.layout.VillageLayout.Lot(HALL, "storage_hall", 40, 0, HALL_FRAME[0], HALL_FRAME[1], south, 64, "storage hall", null, false, null, 0, false),
			new dev.larattalabs.steward.layout.VillageLayout.Lot(COTTAGE, "cottage", 80, 0, 9, 9, south, 64, "cottage", null, false, null, 0, false));
		return new dev.larattalabs.steward.layout.VillageLayout.Plan(lots, 20, 0, 100, 64, List.of());
	}

	/** Each module lot bound (as the build saved it at its first bind) to its variant's entry, made for exactly the artifact lot's geometry. */
	static Map<String, dev.larattalabs.steward.module.ModuleBinding> bindings(String buildId, String owner, Map<String, ModuleOrder> orders) {
		Map<String, JsonObject> lots = new HashMap<>();
		for (var l : artifact().getAsJsonArray("lots")) lots.put(l.getAsJsonObject().get("id").getAsString(), l.getAsJsonObject());
		Map<String, dev.larattalabs.steward.module.ModuleBinding> out = new LinkedHashMap<>();
		orders.forEach((lot, o) -> out.put(lot, new dev.larattalabs.steward.module.ModuleBinding(o.moduleId(), o.version(), o.programSha(), o.params(), ENTRIES.get(lot),
			null, 0, dev.larattalabs.steward.module.Canon.sha(ComposedModules.geometry(lots.get(lot))), dev.larattalabs.steward.module.ModuleInstalls.lotKey(buildId, lot, 1),
			owner)));
		return out;
	}

	/** The town's design group, done: the cottage designed (its entry {@code e_cottage}); a module lot is never designed. */
	static dev.larattalabs.architect.api.Group group() {
		var none = dev.larattalabs.architect.api.Cost.NONE;
		var cottage = new dev.larattalabs.architect.api.Group.Item(COTTAGE, new JsonObject(), "d_cottage", java.util.Optional.of(ENTRIES.get(COTTAGE)),
			dev.larattalabs.architect.api.Design.Status.DONE, "done", none, 0, dev.larattalabs.architect.api.GroupRequest.Role.ORDINARY, "m", "cottage",
			java.util.Optional.empty(), java.util.Optional.empty());
		return new dev.larattalabs.architect.api.Group("grp_1", "Ashfall", null, java.util.Optional.empty(), new JsonObject(), 1, java.util.Optional.empty(), 0.8,
			dev.larattalabs.architect.api.Group.Status.DONE, java.util.Optional.empty(), List.of(cottage), 0, 1, 0, none, 0L, 0L, 0L);
	}

	/**
	 * The world stops and starts again: a new session (the old one's answers do nothing), no runner, the server's queue gone, and everything read back
	 * from the two files alone (the shared build store included); this build restored as the resume finds it, then resumed ({@code resync}).
	 */
	void crash() throws IOException {
		SettlementRunner.clearForTest();
		Session.nextForTest();
		a.serverQueue.clear();
		a.held.clear();
		DurableWrite.FAIL_NEXT.clear();
		durableWriteRestart();
		BuildStore disk = BuildStore.load(buildsFile);
		setBuilds(disk);
		Settlements.useForTest(SettlementStore.load(settlementsFile), settlementsFile);
		saved = disk.all().stream().filter(x -> x.settlementId().equals(saved.settlementId())).findFirst().orElseThrow(() -> new AssertionError("the build is not on disk"));
		r = SettlementRunner.restoredForTest(saved);
		assertTrue(r.aliveForTest());
		try {
			r.resyncForTest();
		} catch (RuntimeException | LinkageError e) {
			tails.add(e);
		}
		drain();
	}

	/** DurableWrite's process memory (the folders it owes a force, the ones it settled) forgotten, as a new process has it. */
	static void durableWriteRestart() {
		try {
			Method m = DurableWrite.class.getDeclaredMethod("restartForTest");
			m.setAccessible(true);
			m.invoke(null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	static void setBuilds(BuildStore s) {
		try {
			Field f = SettlementRunner.class.getDeclaredField("builds");
			f.setAccessible(true);
			f.set(null, s);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
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

	/**
	 * (CRASH-T2) The runner's own placing step ({@code realiseVillage}, as its pipeline's ApproveStages runs it): composedReady, its quote, and when
	 * that clears, the realise, each as the runner reaches it.
	 */
	void place() {
		call("realiseVillage", new Class<?>[0]);
		drain();
	}

	void realiseArtifact() {
		call("realiseArtifact", new Class<?>[] { String.class, Map.class, net.minecraft.server.level.ServerPlayer.class, JsonObject.class, boolean.class, int.class },
			SHA, new LinkedHashMap<>(ENTRIES), null, new JsonObject(), true, Session.current());
		drain();
	}

	/**
	 * The player's approve: what it answered, or "Approved" when it went on and then reached the inbox's send (which needs the server, so it throws
	 * there, after the approval was saved and its commands were run).
	 */
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

	/** The player's approve with nothing after it run: what it started (the quote) is in flight when the world stops. */
	String approveOnly() {
		try {
			return r.approve();
		} catch (NullPointerException e) {
			tails.add(e);
			return "Approved (the inbox's send needs a server)";
		}
	}

	/** The index of the first call that starts with {@code prefix}, or -1. */
	static int indexOf(List<String> calls, String prefix) {
		for (int i = 0; i < calls.size(); i++) if (calls.get(i).startsWith(prefix)) return i;
		return -1;
	}

	/** What was thrown past the steps (each with where it was thrown), for a failure's message. */
	String tails() {
		var out = new StringBuilder();
		for (Throwable t : tails) {
			out.append(t).append(" at");
			java.util.Arrays.stream(t.getStackTrace()).filter(f -> f.getClassName().startsWith("dev.larattalabs.steward")).limit(4).forEach(f -> out.append(' ').append(f));
			out.append("; ");
		}
		return out.toString();
	}

	void drain() {
		// (CRASH-T2) bounded: a step that loops on its own (refused, quoted, realised, refused again...) fails here instead of hanging the run
		for (int ran = 0; !a.serverQueue.isEmpty(); ran++) {
			if (ran > 500) throw new AssertionError("the runner's steps never settle (a loop): " + a.calls.subList(Math.max(0, a.calls.size() - 12), a.calls.size()));
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

	@SuppressWarnings("unchecked")
	Map<String, ModuleOrder> orders() {
		return (Map<String, ModuleOrder>) field("moduleOrders");
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

	static Quote quote(boolean exact, List<String> notes, JsonObject parts) {
		return new Quote("plan_c", SHA, null, true, new JsonObject(), parts.deepCopy(), Map.of(), exact, notes);
	}

	static Quote exact() {
		return quote(true, List.of(), PARTS);
	}

	static Quote inexact() {
		return quote(false, List.of("a lot may differ"), PARTS);
	}

	/** The quote with the hall's lot at {@code planks} planks (the rest as {@link #PARTS}). */
	static Quote hallPlanks(int planks) {
		var p = PARTS.deepCopy();
		p.getAsJsonObject("lot:" + HALL).addProperty("minecraft:oak_planks", planks);
		return quote(true, List.of(), p);
	}

	// ------------------------------------------------------------------ conservation

	/** What the town's standing sites drew, item by item (the world's share of the stockpile's items). */
	Map<String, Integer> world() {
		Map<String, Integer> w = new TreeMap<>();
		for (var s : a.sites.values()) {
			var d = a.ledger.drawn.get(FakeArchitect.Ledger.key(s));
			if (d != null && !a.ledger.refunded.contains(FakeArchitect.Ledger.key(s))) d.forEach((k, v) -> w.merge(k, v, Integer::sum));
		}
		return w;
	}

	/** What the site of {@code lot} drew (nothing when it was never put down). */
	Map<String, Integer> drawn(String lot) {
		var s = a.sites.get("s_" + lot);
		return s == null ? Map.of() : a.ledger.drawn.getOrDefault(FakeArchitect.Ledger.key(s), Map.of());
	}

	/**
	 * Items in == items out: every item the stockpile started with is in it or in a standing site, the ledger's paid less returned is what stands,
	 * and no module lot drew past the cap its realise was sent with, nor past the bound saved for it (what the player approved, or the measured one).
	 */
	void conserved() throws IOException {
		var w = world();
		for (var e : stock0.entrySet())
			assertEquals(e.getValue(), a.ledger.stock.getOrDefault(e.getKey(), 0) + w.getOrDefault(e.getKey(), 0), "stockpile + world for " + e.getKey());
		assertEquals(a.ledger.paid - a.ledger.returned, w.values().stream().mapToInt(Integer::intValue).sum(), "ledger: paid - returned == what stands");
		var bounds = BuildStore.load(buildsFile).all().stream().filter(b -> b.settlementId().equals(saved.settlementId())).findFirst().map(BuildStore.Saved::moduleOrders)
			.orElse(saved.moduleOrders());
		// (CRASH-T2, batch-086 review P2) a town that stands stands whole: every lot of the realise drew exactly its put-down bill, so a lot missing
		// from what was built (and its cap check with it) never passes as "drew nothing"
		boolean stands = a.sites.values().stream().anyMatch(x -> FakeArchitect.REALISED.equals(x.region));
		if (stands) {
			var built = a.sites.values().stream().filter(x -> FakeArchitect.REALISED.equals(x.region)).map(x -> x.id).collect(java.util.stream.Collectors.toSet());
			assertEquals(Set.of("s_" + FARM, "s_" + HALL, "s_" + COTTAGE), built, "the region's lots");
			for (String lot : List.of(FARM, HALL, COTTAGE)) assertEquals(a.putDown.getOrDefault(lot, Map.of()), drawn(lot), lot + " drew its put-down bill");
		}
		for (String lot : List.of(FARM, HALL)) {
			var d = drawn(lot);
			if (!stands) {
				assertTrue(d.isEmpty(), lot + " drew with no region standing: " + d);
				continue;
			}
			var sent = a.approved.stream().filter(x -> a.realisedByKey.containsKey(x.opKey())).reduce((x, y) -> y).orElseThrow().maxBillPerLot();
			assertNotNull(sent, "a module lot drew under a realise sent uncapped");
			for (var e : d.entrySet()) {
				assertTrue(e.getValue() <= sent.get(lot).getOrDefault(e.getKey(), 0), lot + " drew " + e + " past the cap sent " + sent.get(lot));
				var bound = bounds.get(lot).bound();
				assertTrue(bound != null && e.getValue() <= (bound.has(e.getKey()) ? bound.get(e.getKey()).getAsInt() : 0), lot + " drew " + e + " past its saved bound " + bound);
			}
		}
	}

	/**
	 * Architect finishes placing the region (its construction sites stand); the runner watches it, logs the placement to the settlement's history and
	 * ends the build. A log that can't be saved keeps the build open on disk (its recovery checkpoint), and the crash after it logs it once.
	 */
	void placedAndLogged(boolean logFails) throws IOException {
		a.regions.get(FakeArchitect.REALISED).state = dev.larattalabs.architect.api.RegionState.PLACED;
		if (logFails) {
			DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME);
			watch();
			assertEquals(0, placedEntries().size(), "logged unsaved");
			assertTrue(BuildStore.load(buildsFile).all().stream().anyMatch(b -> b.settlementId().equals(saved.settlementId())),
				"the build ended with its placement unlogged (no checkpoint, no undo record)");
			assertEquals(Pipeline.Phase.PLACING, disk().state().phase(), "the build stays open while its placement isn't logged");
			assertEquals(FakeArchitect.REALISED, disk().vRegion());
			crash();
		} else {
			watch();
		}
		placedThen();
	}

	/**
	 * (CRASH-T2, batch-086 review P2) Placed, and the log's save lands, but every write after it fails (a failure that persists, as a full disk): the
	 * files hold the placement logged and the build's checkpoint still open. The crash after it finds the entry and never logs it twice.
	 */
	void placedLoggedCheckpointStays() throws IOException {
		a.regions.get(FakeArchitect.REALISED).state = dev.larattalabs.architect.api.RegionState.PLACED;
		DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.NONE);
		for (int i = 0; i < 20; i++) DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME);
		watch();
		assertTrue(DurableWrite.FAIL_NEXT.size() < 20, "a write after the log's was reached");
		assertEquals(1, placedEntries().size(), "the log's save landed");
		assertTrue(BuildStore.load(buildsFile).all().stream().anyMatch(b -> b.settlementId().equals(saved.settlementId())), "the checkpoint stays on disk");
		assertEquals(FakeArchitect.REALISED, disk().vRegion());
		crash();
		placedThen();
	}

	/** Logged exactly once, the entry holding every site of the region, and the build ended on disk. */
	void placedThen() throws IOException {
		var placed = placedEntries();
		assertEquals(1, placed.size(), "logged once: " + placed + " " + tails());
		var realised = a.sites.values().stream().filter(x -> FakeArchitect.REALISED.equals(x.region)).map(x -> x.id).collect(java.util.stream.Collectors.toSet());
		assertEquals(realised, Set.copyOf(placed.get(0).siteIds()), "the entry holds every site of the region");
		assertTrue(BuildStore.load(buildsFile).all().stream().noneMatch(b -> b.settlementId().equals(saved.settlementId())), "the build ended once logged");
	}

	/** The runner's watch of its region (as its tick does), to its end or the inbox's send, which needs the server. */
	void watch() {
		try {
			r.watchVillage();
		} catch (NullPointerException e) {
			tails.add(e);
		}
		drain();
	}

	/** The settlement's PROJECT_PLACED entries for the realised region, as its file holds them. */
	List<Settlement.LogEntry> placedEntries() throws IOException {
		return SettlementStore.load(settlementsFile).get(saved.settlementId()).orElseThrow().log().stream()
			.filter(e -> e.kind() == Settlement.Kind.PROJECT_PLACED && ("region:" + FakeArchitect.REALISED).equals(e.siteGroup())).toList();
	}

	/** The town taken up by the player's undo (Steward's Undo.region over the entry the runner logged, read from the file): everything drawn back, once. */
	void undoneBalances() throws IOException {
		Settlements.useForTest(SettlementStore.load(settlementsFile), settlementsFile);
		var s = Settlements.store().get(saved.settlementId()).orElseThrow();
		var entry = placedEntries().get(0);
		AtomicReference<Boolean> done = new AtomicReference<>();
		List<String> said = new ArrayList<>();
		Undo.region(null, Settlements.store().get(s.id()).orElseThrow(), entry, FakeArchitect.REALISED, Map.of(), said::add, done::set);
		drain();
		assertEquals(Boolean.TRUE, done.get(), said.toString());
		assertEquals(a.ledger.paid, a.ledger.returned, "paid == returned");
		assertEquals(stock0, new TreeMap<>(a.ledger.stock), "the stockpile is whole again");
		assertTrue(world().isEmpty());
	}

	// ------------------------------------------------------------------ a crash at every save point

	@Test
	void aCrashAtEachSavePointFromTheHoldToThePlacedRegionResumesFromItsFileAndDrawsOnce() throws IOException {
		runner(BOUNDED);
		// (1) the hold: saved, then put to the player; the crash finds it held and waiting, nothing realised or drawn
		a.quote = inexact();
		place();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), tails());
		crash();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
		assertTrue(orders().values().stream().allMatch(o -> o.held() != null), "the hold survives the crash: " + orders());
		assertEquals(0, a.count("regions.realiseApproved"));
		assertEquals(0, a.ledger.paid);

		// (2) the player's approval after the restart: the held bills become the bounds, saved before PLACING; the runner goes on to its quote, whose
		// answer is lost (the world stops while it is in flight): the crash finds PLACING, approved, nothing realised
		var heldBills = new HashMap<String, JsonObject>();
		orders().forEach((lot, o) -> heldBills.put(lot, o.held()));
		long quotes = a.count("regions.quoteApproved");
		assertTrue(approveOnly().startsWith("Approved"), tails());
		assertEquals(Pipeline.Phase.PLACING, disk().state().phase(), "PLACING saved");
		assertEquals(quotes + 1, a.count("regions.quoteApproved"), "the approval went on to its quote: " + a.calls);
		assertEquals(0, a.count("regions.realiseApproved"));
		// (3) the restart's resume at PLACING: the realise looked for by its key (none), then the runner's own realise, which quotes again before any
		// send (no caps carried over a crash): the same uncertainty within the approved bills goes on, never asked again; (4) the realise saved, then
		// sent under the approved bounds, and applied by Architect; its answer never arrives (the world stops)
		a.quote = inexact();
		a.realiseAnswer = FakeArchitect.Answer.HOLD;
		List<BuildStore.Saved> atSend = new ArrayList<>();
		a.onCall = c -> { if (c.startsWith("regions.realiseApproved")) atSend.add(disk()); };
		int callsBefore = a.calls.size();
		try (LogCapture log = LogCapture.open()) {
			crash();
			assertFalse(log.has("The group disappeared"), "the resume stopped at the design group: " + log.lines);
		}
		a.onCall = c -> {};
		var resumed = a.calls.subList(callsBefore, a.calls.size());
		assertTrue(resumed.contains("regions.realiseByKey steward." + saved.buildId() + ".vrealise"), resumed.toString());
		assertTrue(resumed.contains("designs.group grp_1"), "the resume went on to the realise: " + resumed);
		int quoted = indexOf(resumed, "regions.quoteApproved"), sent = indexOf(resumed, "regions.realiseApproved");
		assertTrue(quoted >= 0, "the resume quoted the realise again: " + resumed + " " + tails());
		assertTrue(sent > quoted, "the resume sent the realise, after its own quote: " + resumed + " " + tails());
		assertEquals("regions.quoteApproved " + SHA + " " + new TreeMap<>(ENTRIES), resumed.get(quoted), "quoted with the realise's own entries");
		assertEquals(Pipeline.Phase.PLACING, phase(), "not asked again: " + tails());
		for (var e : orders().entrySet()) {
			assertNull(e.getValue().held());
			assertEquals(heldBills.get(e.getKey()), e.getValue().bound(), "the approved bill is the bound after the crash");
			assertTrue(e.getValue().inexactApproved());
		}
		assertEquals(1, a.approved.size(), a.calls + " " + tails());
		assertEquals(new TreeMap<>(ENTRIES), new TreeMap<>(a.approved.get(0).lotEntries()), "the realise binds the plan's lots, the cottage's designed entry included");
		assertEquals(1, atSend.size());
		assertEquals(Pipeline.Phase.PLACING, atSend.get(0).state().phase());
		assertEquals(a.approved.get(0).maxBillPerLot(), ComposedModules.caps(PARTS, atSend.get(0).moduleOrders(), ENTRIES.keySet()).perLot(),
			"sent with caps other than the saved bounds");
		conserved();
		assertFalse(world().isEmpty(), "Architect put it down");

		// (5) the crash with Architect's answer lost: the resume finds the region by the realise's key and takes it as the build's, never realised again
		crash();
		assertEquals(FakeArchitect.REALISED, field("vRegionId"), "the realise found by its key: " + a.calls + " " + tails());
		assertEquals(FakeArchitect.REALISED, disk().vRegion(), "the region saved with the build");
		assertEquals(1, a.approved.size(), "never sent again");
		assertEquals(1, a.realisedByKey.size());
		conserved();

		// (6) the crash after the region is saved: the resume watches it, nothing is quoted, sent or drawn again
		int paid = a.ledger.paid;
		crash();
		assertEquals(FakeArchitect.REALISED, field("vRegionId"));
		assertEquals(1, a.approved.size());
		assertEquals(paid, a.ledger.paid);
		conserved();
		// (7) placed: the log's save fails first (the build stays open), the crash after it logs it once and ends the build; the undo balances
		placedAndLogged(true);
		conserved();
		undoneBalances();
	}

	@Test
	void aPutDownRefusalSavedBeforeTheCrashIsAskedAgainWhateverTheNextQuoteSaysAndDrawsNothing() throws IOException {
		runner(BOUNDED);
		// Architect's put-down bill for the hall is past its measured bound (100 planks): refused, nothing written or drawn; the refusal saved, then the
		// runner quotes again (that answer in flight when the world stops)
		a.putDown.put(HALL, Map.of("minecraft:oak_planks", 105, "minecraft:cobblestone", 41, "minecraft:iron_ingot", 41, "minecraft:wheat", 41, "minecraft:coal", 41));
		a.quote = exact();
		a.onCall = c -> { if (c.startsWith("regions.realiseApproved")) a.quoteHold = true; };
		place();
		a.onCall = c -> {};
		a.quoteHold = false;
		assertEquals(1, a.approved.size(), a.calls + " " + tails());
		assertTrue(a.realisedByKey.isEmpty(), "refused at put-down");
		assertEquals(0, a.ledger.paid, "nothing drawn");
		assertEquals(2, a.count("regions.quoteApproved"), "quoted again after the refusal: " + a.calls);
		assertTrue(disk().moduleOrders().values().stream().allMatch(ModuleOrder::refusedAtPutDown), "the refusal saved before the re-quote: " + disk().moduleOrders());
		conserved();

		// the crash: the refusal read back (it came from READY_TO_PLACE: the resume puts the placement to the player first, nothing realised); their
		// approve goes on to the runner's quote, which is put to the player whatever it says, exact and within every bound (QD-2: the hold reads it)
		crash();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), "the resume asks before placing: " + a.calls);
		assertEquals(1, a.approved.size());
		assertTrue(orders().values().stream().allMatch(ModuleOrder::refusedAtPutDown), "an approve with nothing held keeps the refusal");
		long quotes = a.count("regions.quoteApproved");
		a.quote = exact();
		assertTrue(approve().startsWith("Approved"), tails());
		assertEquals(quotes + 1, a.count("regions.quoteApproved"), "the approve went on to its quote: " + a.calls);
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), "realised on its own after a put-down refusal: " + a.calls + " " + tails());
		assertTrue(((Pipeline.State) field("state")).heldNote().startsWith(ModuleBound.PUT_DOWN_REFUSED), ((Pipeline.State) field("state")).heldNote());
		assertTrue(disk().moduleOrders().values().stream().allMatch(ModuleOrder::refusedAtPutDown), "still refused on disk");
		assertEquals(1, a.approved.size());
		// its bill never less than what was approved, item by item
		for (var e : orders().entrySet()) for (var b : BOUNDED.get(e.getKey()).bound().entrySet())
			assertTrue(e.getValue().held().get(b.getKey()).getAsInt() >= b.getValue().getAsInt(), e.getKey() + " " + b.getKey());

		// the crash again while asked: still asked, never realised
		crash();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
		assertTrue(orders().values().stream().allMatch(ModuleOrder::refusedAtPutDown));

		// the player approves the bills shown (the bounds as they were); the crash while the approve's quote is in flight; the resume's own quote is
		// Architect's real bill (105 planks), past them: asked again, never realised
		a.quote = hallPlanks(105);
		assertTrue(approveOnly().startsWith("Approved"), tails());
		assertTrue(disk().moduleOrders().values().stream().noneMatch(ModuleOrder::refusedAtPutDown), "the approval clears the refusal");
		crash();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), "past the approved bound: asked: " + a.calls + " " + tails());
		assertEquals(1, a.approved.size());
		// approved, and the crash again before the quote's answer: the resume quotes and realises on its own, drawing exactly what was approved
		assertTrue(approveOnly().startsWith("Approved"), tails());
		int callsBefore = a.calls.size();
		crash();
		var resumed = a.calls.subList(callsBefore, a.calls.size());
		assertTrue(indexOf(resumed, "regions.realiseApproved") > indexOf(resumed, "regions.quoteApproved") && indexOf(resumed, "regions.quoteApproved") >= 0,
			"quoted, then realised: " + resumed + " " + tails());
		assertEquals(2, a.approved.size(), a.calls + " " + tails());
		assertEquals(105, drawn(HALL).get("minecraft:oak_planks"));
		assertEquals(FakeArchitect.REALISED, field("vRegionId"));
		conserved();
		placedAndLogged(false);
		undoneBalances();
	}

	/** Held, then the player's approve (PLACING, the held bills the bounds), its quote in flight when the world stops. */
	void heldApprovedUnanswered() throws IOException {
		runner(BOUNDED);
		a.quote = inexact();
		place();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), tails());
		assertTrue(approveOnly().startsWith("Approved"), tails());
		assertEquals(Pipeline.Phase.PLACING, disk().state().phase());
	}

	@Test
	void aRestartedBuildNeverRealisesWithoutItsOwnFreshQuote() throws IOException {
		// approved and placing, then the crash: no caps survive it, so a realise reached before the restart's own quote answers sends nothing
		heldApprovedUnanswered();
		a.quoteHold = true;
		crash();
		assertEquals(1, a.held.size(), "the resume's quote is in flight: " + a.calls);
		try (LogCapture log = LogCapture.open()) {
			realiseArtifact();
			assertTrue(log.has("weren't quoted for exactly this placing"), "reached the caps' check: " + log.lines);
		}
		assertEquals(0, a.count("regions.realiseApproved"), "sent with caps from before the crash");
		assertEquals(0, a.ledger.paid);
		conserved();
	}

	@Test
	void aPutDownRefusalWhilePlacingAnApprovedBillIsSavedAndAskedAgainAcrossACrash() throws IOException {
		heldApprovedUnanswered();
		// Architect's bill at put-down is past the hall's approved bound (90 planks); the resume quotes, realises, is refused (nothing drawn), saves
		// the refusal and quotes again; that answer is in flight when the world stops
		a.putDown.put(HALL, Map.of("minecraft:oak_planks", 95, "minecraft:cobblestone", 41, "minecraft:iron_ingot", 41, "minecraft:wheat", 41, "minecraft:coal", 41));
		a.quote = inexact();
		a.onCall = c -> { if (c.startsWith("regions.realiseApproved")) a.quoteHold = true; };
		crash();
		a.onCall = c -> {};
		a.quoteHold = false;
		assertEquals(1, a.approved.size(), a.calls + " " + tails());
		assertEquals(0, a.ledger.paid);
		assertEquals(Pipeline.Phase.PLACING, phase(), "still placing: asked again, never cancelled");
		assertEquals(1, a.held.size(), "quoted again after the refusal: " + a.calls);
		assertTrue(disk().moduleOrders().values().stream().allMatch(ModuleOrder::refusedAtPutDown), "the refusal saved: " + disk().moduleOrders());
		// the crash: the resume's own quote, within the approved bills, is put to the player (refused at put-down), never realised
		crash();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), "realised on its own after a refusal while placing: " + a.calls);
		assertTrue(((Pipeline.State) field("state")).heldNote().startsWith(ModuleBound.PUT_DOWN_REFUSED));
		assertEquals(1, a.approved.size());
		conserved();
	}

	@Test
	void aPutDownRefusalWhilePlacingWhoseWriteFailsStopsAndNeverRealisesOnItsOwn() throws IOException {
		for (var fail : List.of(DurableWrite.TestFail.BEFORE_RENAME, DurableWrite.TestFail.AFTER_RENAME)) {
			a.approved.clear();
			heldApprovedUnanswered();
			a.putDown.put(HALL, Map.of("minecraft:oak_planks", 95));
			// the refusal's save is the next write after the realise is sent (by the resume's own quote and realise)
			a.onCall = c -> { if (c.startsWith("regions.realiseApproved")) DurableWrite.FAIL_NEXT.add(fail); };
			try (LogCapture log = LogCapture.open()) {
				crash();
				assertTrue(ComposedRunnerOrderTest.said(log, "that could not be saved, so I won't ask again or build it"), fail + ": " + log.lines);
			}
			a.onCall = c -> {};
			assertEquals(1, a.approved.size(), fail + " " + a.calls);
			assertEquals(0, a.ledger.paid);
			assertTrue(orders().values().stream().noneMatch(ModuleOrder::refusedAtPutDown), fail + ": rolled back as saved");
			// the crash: the build as its file holds it never realises on its own
			crash();
			assertEquals(1, a.approved.size(), fail + ": realised on its own after an unsaved refusal: " + a.calls);
			assertEquals(0, a.ledger.paid);
			conserved();
			SettlementRunner.clearForTest();
			a.putDown.put(HALL, Map.of("minecraft:oak_planks", 90));
		}
	}

	// ------------------------------------------------------------------ a crash between a step's two saves (CRASH-T2)

	@Test
	void aCrashBetweenTheApprovalsTwoSavesFindsTheBillsApprovedAndStillAsks() throws IOException {
		// the approve saves twice: the approved bills (approveHeld), then PLACING. Every write after the first fails, so the files hold the first save
		// alone when the world stops: a crash between the two
		runner(BOUNDED);
		a.quote = inexact();
		place();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), tails());
		var held = new HashMap<String, JsonObject>();
		orders().forEach((lot, o) -> held.put(lot, o.held()));
		DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.NONE);
		for (int i = 0; i < 20; i++) DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME);
		approveOnly();
		assertTrue(DurableWrite.FAIL_NEXT.size() < 20, "the approve's second save was reached");
		assertEquals(0, a.count("regions.realiseApproved"), "nothing is realised on an approval whose phase wasn't saved");
		var d = disk();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, d.state().phase(), "the file holds the first save only");
		for (var e : d.moduleOrders().entrySet()) {
			assertNull(e.getValue().held(), "approved on disk: " + e);
			assertEquals(held.get(e.getKey()), e.getValue().bound());
		}
		// the crash: still waiting on the player with the approved bills; the resume sends nothing
		crash();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
		assertEquals(0, a.count("regions.realiseApproved"));
		assertEquals(0, a.ledger.paid);
		// the player's approve again: the runner quotes and realises under exactly the approved bills
		a.quote = inexact();
		assertTrue(approve().startsWith("Approved"), tails());
		assertEquals(1, a.approved.size(), a.calls + " " + tails());
		for (var e : a.approved.get(0).maxBillPerLot().entrySet())
			for (var c : e.getValue().entrySet()) assertEquals(held.get(e.getKey()).get(c.getKey()).getAsInt(), c.getValue(), e.getKey() + " " + c.getKey());
		assertEquals(FakeArchitect.REALISED, field("vRegionId"));
		conserved();
		placedAndLogged(false);
		undoneBalances();
	}

	// ------------------------------------------------------------------ a failed write at each save point

	@Test
	void aHoldWhoseWriteFailsIsNeverPutAndTheCrashFindsTheBuildAsItWas() throws IOException {
		for (var fail : List.of(DurableWrite.TestFail.BEFORE_RENAME, DurableWrite.TestFail.AFTER_RENAME)) {
			runner(BOUNDED);
			a.quote = inexact();
			// the bounds' save (already set: unchanged) lands; the hold's fails
			DurableWrite.FAIL_NEXT.addAll(List.of(DurableWrite.TestFail.NONE, fail));
			place();
			assertNotEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase(), fail + ": asked on an unsaved hold");
			assertTrue(orders().values().stream().allMatch(o -> o.held() == null), fail + ": the held orders are rolled back");
			assertEquals(0, a.count("regions.realiseApproved"));
			// whatever the file holds (the hold landed after its rename, or not at all), the restart never realises on its own
			a.quote = inexact();
			crash();
			place();
			assertEquals(0, a.count("regions.realiseApproved"), fail.toString());
			assertEquals(0, a.ledger.paid);
			conserved();
			SettlementRunner.clearForTest();
		}
	}

	@Test
	void anApprovalWhoseWriteFailsIsRolledBackSoAnotherBuildsSaveNeverWritesIt() throws IOException {
		runner(BOUNDED);
		a.quote = inexact();
		place();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
		var held = Map.copyOf(orders());
		DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME);
		assertTrue(approve().startsWith("Not approved"), tails());
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
		assertEquals(held, orders(), "rolled back in the runner");
		// another build of the world saves now (a second settlement's runner): the file must not get the approval the first one couldn't save
		var b = saved;
		var other = Settlement.founded("set_other", "Other", new dev.larattalabs.steward.model.Claim("minecraft:overworld", 5000, 5000, 64, -64, 320), Permission.PROPOSALS, dev.larattalabs.steward.model.Difficulty.PATRON, 1L);
		var otherSaved = new BuildStore.Saved("set_other", "b1700000000001", b.playerId(), b.dimension(), b.permission(), b.landmarks(), other, b.state(), b.plan(),
			b.bibleJobId(), b.groupId(), b.batchId(), b.siteGroupId(), b.landmarkIds(), b.decided(), b.lastAwaiting(), false, null, null, true, null, null, null,
			null, null, null, null, Map.of(), Map.of(), null);
		Settlements.store().put(other);
		var first = r;
		r = SettlementRunner.restoredForTest(otherSaved);
		assertTrue((boolean) call("persist", new Class<?>[0]), "the other build's save");
		r = first;
		assertEquals(held, disk().moduleOrders(), "another build's save wrote the rolled-back approval");
		assertTrue(disk().moduleOrders().values().stream().allMatch(o -> o.held() != null && !o.inexactApproved()));
		// the crash: still waiting on the player with the bills as shown; nothing realised or drawn
		crash();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
		assertEquals(held, orders());
		assertEquals(0, a.count("regions.realiseApproved"));
		conserved();
	}

	@Test
	void anApprovalWrittenThoughItsSaveReportedAFailureIsNeverRealisedWithoutTheQuote() throws IOException {
		// the approval's file lands but its folder's force fails (the save says failed): the runner says "not approved"; a crash then finds the approved
		// bills in a build still waiting, and the restart goes on only through the player's approve and the runner's fresh quote, under exactly those bounds
		runner(BOUNDED);
		a.quote = inexact();
		place();
		DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.AFTER_RENAME);
		assertTrue(approve().startsWith("Not approved"), tails());
		crash();
		assertEquals(Pipeline.Phase.AWAITING_PLACEMENT_APPROVAL, phase());
		assertEquals(0, a.count("regions.realiseApproved"), "realised without the player's word");
		a.quote = inexact();
		int callsBefore = a.calls.size();
		assertTrue(approve().startsWith("Approved"), tails());
		var after = a.calls.subList(callsBefore, a.calls.size());
		assertTrue(indexOf(after, "regions.quoteApproved") >= 0 && indexOf(after, "regions.realiseApproved") > indexOf(after, "regions.quoteApproved"),
			"quoted, then realised: " + after + " " + tails());
		assertEquals(1, a.approved.size(), a.calls + " " + tails());
		assertEquals(FakeArchitect.REALISED, field("vRegionId"));
		conserved();
		placedLoggedCheckpointStays();
		undoneBalances();
	}

	@Test
	void aRealiseWhoseWriteFailsIsNeverSentAndTheBuildStopsAsSaved() throws IOException {
		for (var fail : List.of(DurableWrite.TestFail.BEFORE_RENAME, DurableWrite.TestFail.AFTER_RENAME)) {
			runner(BOUNDED);
			a.approved.clear();
			a.realisedByKey.clear();
			a.quote = exact();
			// after the quote: the cleared orders' save lands, the realise's fails
			a.onCall = c -> { if (c.startsWith("regions.quoteApproved")) DurableWrite.FAIL_NEXT.addAll(List.of(DurableWrite.TestFail.NONE, fail)); };
			try (LogCapture log = LogCapture.open()) {
				place();
				assertTrue(ComposedRunnerOrderTest.said(log, "the build could not be saved before placing"), fail + ": reached the realise's save: " + log.lines);
			}
			a.onCall = c -> {};
			assertEquals(0, a.count("regions.realiseApproved"), fail + ": sent unsaved");
			assertEquals(0, a.ledger.paid);
			conserved();
			// the crash: the build as its file holds it (cancelled, or the cleared orders with no caps) never sends a realise on its own
			crash();
			place();
			assertEquals(0, a.count("regions.realiseApproved"), fail + ": sent after the restart");
			assertEquals(0, a.ledger.paid);
			conserved();
			SettlementRunner.clearForTest();
		}
	}

	@Test
	void aRefusalWhoseWriteFailsStopsTheBuildAndTheCrashNeverRealisesOnItsOwn() throws IOException {
		runner(BOUNDED);
		a.putDown.put(HALL, Map.of("minecraft:oak_planks", 105));
		a.quote = exact();
		// the refusal's save is the next write after the realise is sent
		a.onCall = c -> { if (c.startsWith("regions.realiseApproved")) DurableWrite.FAIL_NEXT.add(DurableWrite.TestFail.BEFORE_RENAME); };
		place();
		a.onCall = c -> {};
		assertEquals(1, a.approved.size(), a.calls + " " + tails());
		assertEquals(0, a.ledger.paid, "refused: nothing drawn");
		assertTrue(orders().values().stream().noneMatch(ModuleOrder::refusedAtPutDown), "rolled back as saved");
		conserved();
		// the file holds the cancel (or the cleared orders): no realise is sent on its own after the restart
		int sent = a.approved.size();
		crash();
		place();
		assertEquals(sent, a.approved.size(), "realised on its own after an unsaved refusal: " + a.calls);
		assertEquals(0, a.ledger.paid);
		conserved();
	}
}
