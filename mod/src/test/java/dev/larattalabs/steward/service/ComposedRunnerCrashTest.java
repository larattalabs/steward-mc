package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.module.ComposedModules;
import dev.larattalabs.steward.module.ModuleBound;
import dev.larattalabs.steward.module.ModuleOrder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (slice LIFT-S; batch 075's gate-verifier and GPT review, LEFTOVERS M5-HARD) The composed hold, realise and re-ask over a real build file, through the
 * steps the runner takes ({@link ComposedSteps}): each save lands on disk (BuildStore.save, temp file and atomic rename) before anything is asked of
 * the player or Architect, a save that fails (its folder is a file: a full disk, a dying world) sends and asks nothing, and a crash is the build read
 * back from its file alone. Every check that something went on reads the file from inside that step, so a step taken before its save (the order
 * reversed) fails here, which ComposedCrashPointsTest's in-memory round trips could not catch.
 */
class ComposedRunnerCrashTest {
	@TempDir
	Path dir;
	Path file;
	/** The runner's orders now (what {@code persist} writes). */
	Map<String, ModuleOrder> orders;

	static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	static final JsonObject PARTS = ComposedCrashPointsTest.PARTS;
	static final Set<String> REALISED = Set.of("farm_1", "hall_1", "cottage_1");

	@BeforeEach
	void setUp() {
		file = dir.resolve("steward-builds.json");
		orders = ComposedCrashPointsTest.orders(ComposedCrashPointsTest.farm("{\"minecraft:dirt\": 130}"), ComposedCrashPointsTest.hall(ComposedCrashPointsTest.HALL_BOUND));
	}

	/** {@code persist}: the build with the runner's orders written to its file; false when the write fails. */
	boolean persist() {
		BuildStore st = new BuildStore();
		st.put(BuildStoreTestAccess.saved().withModuleOrders(orders));
		try {
			st.save(file);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** Saves fail from now on: the build file's folder is a file. What was saved before stays on disk. */
	void savesFail() throws IOException {
		Path blocker = dir.resolve("full-disk");
		Files.writeString(blocker, "x");
		Path before = file;
		file = blocker.resolve("steward-builds.json");
		crashFile = before;
	}

	/** Where a crash reads from (the last file that saved). */
	Path crashFile;

	/** A crash: the orders the file holds, read back by a fresh store; nothing kept in memory. */
	Map<String, ModuleOrder> crash() {
		try {
			return BuildStore.load(crashFile != null ? crashFile : file).all().get(0).moduleOrders();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The cleared quote's caps, from these orders. */
	static ComposedModules.Caps caps(Map<String, ModuleOrder> o) {
		return ComposedModules.caps(PARTS, o, REALISED);
	}

	@Test
	void theRealiseIsSentOnlyOnceItsLotCapsAreOnDisk() {
		// the orders saved before the bounds were set (the decide's save): the file holds no bound
		var decided = new LinkedHashMap<String, ModuleOrder>();
		orders.forEach((k, v) -> decided.put(k, v.resized(v.params(), null)));
		var bounded = orders;
		orders = decided;
		assertTrue(persist());
		orders = bounded;
		var cleared = ComposedModules.held(PARTS, orders, null);
		assertNull(cleared.ask());
		orders = cleared.orders();
		var sending = caps(orders);
		assertNull(sending.refused(), sending.refused());
		List<ComposedModules.Caps> sent = new ArrayList<>();
		assertTrue(ComposedSteps.saveThen(this::persist, () -> {
			// at the moment it is sent, a crash finds the orders whose bounds are these lot caps: the restart quotes again and sends the same ones
			var disk = crash();
			assertEquals(orders, disk, "sent before its orders were saved");
			assertEquals(sending.perLot(), caps(disk).perLot());
			sent.add(sending);
		}));
		assertEquals(1, sent.size());
		assertEquals(Map.of("minecraft:dirt", 130), sent.get(0).perLot().get("farm_1"));
	}

	@Test
	void aRealiseWhoseSaveFailsIsNeverSent() throws IOException {
		var held = ComposedModules.held(PARTS, orders, "a lot may differ");
		orders = held.orders();
		assertTrue(persist());
		// approved: the bounds the held bills; its save fails (the world dies before it lands)
		var approved = new LinkedHashMap<String, ModuleOrder>();
		orders.forEach((k, v) -> approved.put(k, v.approvedHeld()));
		orders = approved;
		savesFail();
		List<String> sent = new ArrayList<>();
		assertFalse(ComposedSteps.saveThen(this::persist, () -> sent.add("realise")));
		assertEquals(List.of(), sent, "nothing is sent unsaved");
		// the crash finds the hold as it was saved: asked again, never realised on its own
		var back = crash();
		assertTrue(back.values().stream().allMatch(o -> o.held() != null));
		assertNotNull(ComposedModules.held(PARTS, back, "a lot may differ").ask());
	}

	@Test
	void theHoldIsPutToThePlayerOnlyOnceItIsOnDisk() throws IOException {
		assertTrue(persist());
		var held = ComposedModules.held(PARTS, orders, "a lot may differ");
		assertNotNull(held.ask());
		orders = held.orders();
		List<String> asked = new ArrayList<>();
		assertTrue(ComposedSteps.saveThen(this::persist, () -> {
			assertTrue(crash().values().stream().allMatch(o -> o.held() != null), "asked before the hold was saved");
			asked.add(held.ask());
		}));
		assertEquals(1, asked.size());
		// a hold whose save fails is never put to the player (the runner stops the build instead)
		savesFail();
		assertFalse(ComposedSteps.saveThen(this::persist, () -> asked.add("again")));
		assertEquals(1, asked.size());
	}

	@Test
	void aPutDownRefusalIsSavedBeforeItIsAskedAgainAndKeptAcrossACrash() throws IOException {
		// approved and saved (the bounds the approved bills), its realise refused at put-down: lot hall_1 past its cap
		var approved = new LinkedHashMap<String, ModuleOrder>();
		ComposedModules.held(PARTS, orders, "a lot may differ").orders().forEach((k, v) -> approved.put(k, v.approvedHeld()));
		orders = approved;
		assertTrue(persist());
		var refusal = ComposedModules.CapRefusal.of("lot:hall_1");
		var next = ComposedSteps.refusedAtPutDown(refusal, orders, o -> orders = o, this::persist);
		assertEquals(ComposedSteps.AfterRefusal.REASK, next);
		// the crash right after: every order refused, so the next quote is put to the player whatever it says, at least the approved bills
		var back = crash();
		assertTrue(back.values().stream().allMatch(ModuleOrder::refusedAtPutDown));
		var reask = ComposedModules.held(PARTS, back, null);
		assertTrue(reask.ask().startsWith(ModuleBound.PUT_DOWN_REFUSED), reask.ask());
		for (var e : reask.orders().entrySet()) for (var b : approved.get(e.getKey()).bound().entrySet())
			assertTrue(e.getValue().held().get(b.getKey()).getAsInt() >= b.getValue().getAsInt(), e.getKey() + " " + b.getKey());
		// the re-ask approved and saved: the caps sent next are the new bounds, never below the approved ones
		var ok = new LinkedHashMap<String, ModuleOrder>();
		reask.orders().forEach((k, v) -> ok.put(k, v.approvedHeld()));
		orders = ok;
		assertTrue(persist());
		var again = caps(crash());
		for (var e : again.perLot().entrySet()) for (var b : approved.get(e.getKey()).bound().entrySet())
			assertTrue(e.getValue().get(b.getKey()) >= b.getValue().getAsInt(), e.getKey() + " " + b.getKey());
	}

	@Test
	void aRefusalThatCantBeSavedStopsAndAnUnknownLotNeverAsksAgain() throws IOException {
		assertTrue(persist());
		var saved = orders;
		savesFail();
		assertEquals(ComposedSteps.AfterRefusal.STOP_UNSAVED, ComposedSteps.refusedAtPutDown(ComposedModules.CapRefusal.of("lot:farm_1"), orders, o -> orders = o, this::persist));
		// the file holds the orders as approved (the refusal never landed): the runner stopped the build rather than ask on an unsaved refusal, and
		// its orders are put back as saved
		assertEquals(saved, crash());
		assertEquals(saved, orders);
		// an unknown lot: Steward's error; nothing is changed, saved or asked
		List<String> kept = new ArrayList<>();
		assertEquals(ComposedSteps.AfterRefusal.STOP_UNKNOWN_LOT, ComposedSteps.refusedAtPutDown(ComposedModules.CapRefusal.of("unknown_lot:la"), orders,
			o -> kept.add("kept"), () -> { kept.add("saved"); return true; }));
		assertEquals(List.of(), kept);
	}

	// ------------------------------------------------------------------ the waits and the refusal's detail (LCAP-Q1)

	@Test
	void aWaitHeldByALotsCapNamesItsLimitAndTheRegionsKeepsItsWords() {
		assertEquals("farm_1", MaterialRequests.capLot("lot:farm_1"));
		assertEquals("farm_1", MaterialRequests.capLot("lot:farm_1,region"), "both: the lot's named");
		assertNull(MaterialRequests.capLot("region"));
		assertNull(MaterialRequests.capLot((String) null));
		var over = List.of(Map.entry("minecraft:dirt", 3));
		assertTrue(MaterialRequests.capBoth("lot:farm_1,region"));
		assertFalse(MaterialRequests.capBoth("lot:farm_1"));
		assertFalse(MaterialRequests.capBoth("region"));
		String both = MaterialRequests.lotOverCapWords("working crop farm", "set_1", over, 0, MaterialRequests.TakeUp.CANCEL, true);
		assertTrue(both.contains("3 dirt more than its limit or what you approved (whichever holds it)"), both);
		String lot = MaterialRequests.lotOverCapWords("working crop farm", "set_1", over, 0, MaterialRequests.TakeUp.CANCEL, false);
		assertTrue(lot.startsWith("The working crop farm has stopped: finishing it would take 3 dirt more than its limit, so I take no more for it;"), lot);
		assertFalse(lot.contains("you approved"), lot);
		assertTrue(lot.contains("/steward cancel set_1"), lot);
		String region = MaterialRequests.overCapWords("street", "set_1", over, 0, MaterialRequests.TakeUp.CANCEL);
		assertTrue(region.contains("3 dirt more than you approved"), region);
		// a gate mechanism's stage wait held by its lot's cap (waitsFor.cap)
		var ext = json("{\"architect_mc:waitsFor\": {\"kind\": \"bill_cap\", \"say\": \"Main gatehouse's portcullis waits bill_cap\", \"missing\": {\"minecraft:iron_ingot\": 2}, \"cap\": \"lot:gate_1\"}}");
		String stage = SettlementRunner.capStageWait(SettlementRunner.ItemWait.ofExt(ext), "Main gatehouse's portcullis", "set_1", MaterialRequests.TakeUp.CANCEL);
		assertTrue(stage.contains("2 iron ingot") && stage.contains("more than its limit"), stage);
		ext.getAsJsonObject("architect_mc:waitsFor").addProperty("cap", "region");
		assertTrue(SettlementRunner.capStageWait(SettlementRunner.ItemWait.ofExt(ext), "Main gatehouse's portcullis", "set_1", MaterialRequests.TakeUp.CANCEL).contains("more than you approved"));
	}

	@Test
	void aRefusalsDetailIsReadOffArchitectsBillCap() {
		var lot = new dev.larattalabs.architect.api.ArchitectRefused(dev.larattalabs.architect.api.Reason.BILL_CAP, "lot farm_1's bill at put-down is past its maxBillPerLot", "lot:farm_1");
		assertEquals("lot:farm_1", SettlementRunner.capDetail(lot));
		assertEquals("lot:farm_1", SettlementRunner.capDetail(new java.util.concurrent.CompletionException(lot)));
		assertTrue(SettlementRunner.billCapped(lot));
		assertNull(SettlementRunner.capDetail(new dev.larattalabs.architect.api.ArchitectRefused(dev.larattalabs.architect.api.Reason.OP_KEY_CONFLICT, "x")));
		assertNull(SettlementRunner.capDetail(null));
	}
}
