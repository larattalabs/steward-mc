package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.module.ComposedModules;
import dev.larattalabs.steward.module.ModuleBound;
import dev.larattalabs.steward.module.ModuleOrder;
import dev.larattalabs.steward.module.ModuleRef;
import dev.larattalabs.steward.module.StorageHallLot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * (slice M5b) The composed realise's save points, each followed by a crash: the build is read back from its saved file only (nothing kept in
 * memory), and what the restart does next is what the saved orders say. A crash never realises a bill the player has not approved, never asks for
 * one twice once its hold's phase is saved, and never loses a put-down refusal. Also: the hall's fill keys (its items) survive every save, so the
 * cargo check (HallCargo, from the site's own params) follows them; and a composed realise from materials is never sent uncapped.
 */
class ComposedCrashPointsTest {
	static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	static final String SHA = "e".repeat(64);
	static final JsonObject HALL_PARAMS = json("""
		{"bulk": 2, "slots": 4, "item1": "cobblestone", "item2": "iron_ingot", "item3": "wheat", "item4": "coal", "item5": "none", "item6": "none", "item7": "none",
		 "item8": "none", "item9": "none", "item10": "none", "item11": "none", "item12": "none", "item13": "none", "item14": "none", "item15": "none", "item16": "none"}""");

	static ModuleOrder hall(String bound) {
		return new ModuleOrder("storage_hall", 2, SHA, HALL_PARAMS, bound == null ? null : json(bound), null, false, false);
	}

	static ModuleOrder farm(String bound) {
		return new ModuleOrder("crop_farm", 1, "c".repeat(64), json("{\"crop\": \"wheat\", \"depth\": 9, \"width\": 9}"), bound == null ? null : json(bound), null, false, false);
	}

	/** A crash: the orders as the file holds them, read back by a fresh store. */
	static Map<String, ModuleOrder> crash(Map<String, ModuleOrder> orders) {
		BuildStore st = new BuildStore();
		st.put(BuildStoreTestAccess.saved().withModuleOrders(orders));
		return BuildStore.fromJson(st.toJson()).all().get(0).moduleOrders();
	}

	/** The quote: the farm's lot within its bound, the hall's lot plus its fill (41 of each filter item). */
	static final JsonObject PARTS = json("""
		{"tiles:x": {"minecraft:dirt": 500}, "lot:farm_1": {"minecraft:dirt": 120}, "lot:hall_1": {"minecraft:oak_planks": 90},
		 "fill:hall_1": {"minecraft:cobblestone": 41, "minecraft:iron_ingot": 41, "minecraft:wheat": 41, "minecraft:coal": 41}}""");
	static final String HALL_BOUND = "{\"minecraft:oak_planks\": 100, \"minecraft:cobblestone\": 41, \"minecraft:iron_ingot\": 41, \"minecraft:wheat\": 41, \"minecraft:coal\": 41}";

	static Map<String, ModuleOrder> orders(ModuleOrder f, ModuleOrder h) {
		Map<String, ModuleOrder> o = new LinkedHashMap<>();
		o.put("farm_1", f);
		o.put("hall_1", h);
		return o;
	}

	@Test
	void eachSavePointOfTheComposedRealiseRestartsSafely() {
		// 1. decided (no bound yet), crash: the bounds are set again from the measurement before any quote (quoteComposed), nothing held
		var decided = crash(orders(farm(null), hall(null)));
		assertNull(decided.get("hall_1").bound());
		assertEquals(HALL_PARAMS, decided.get("hall_1").params(), "the hall's fill keys survive the save");
		assertNotNull(ComposedModules.held(PARTS, decided, null).refused(), "no bound: never quoted against nothing");
		// 2. bounds set, crash: the same quote within them goes on (exact), its limit said with the crash exception
		var bounded = crash(orders(farm("{\"minecraft:dirt\": 130}"), hall(HALL_BOUND)));
		var go = ComposedModules.held(PARTS, bounded, null);
		assertNull(go.ask());
		assertTrue(go.said().contains(ModuleBound.CRASH_EXCEPTION), go.said());
		// 3. an inexact quote held, the hold's orders saved, crash before its phase: the restart quotes again and asks again (not approved)
		var held = ComposedModules.held(PARTS, bounded, "a lot may differ");
		assertNotNull(held.ask());
		var heldBack = crash(held.orders());
		assertEquals(held.orders(), heldBack);
		assertNotNull(ComposedModules.held(PARTS, heldBack, "a lot may differ").ask(), "a crash between the hold's two saves asks again, never realises");
		// 4. approved and saved, crash: the approved bills are the bounds and the same inexact quote goes on, not asked twice
		Map<String, ModuleOrder> approved = new LinkedHashMap<>();
		heldBack.forEach((k, v) -> approved.put(k, v.approvedHeld()));
		var approvedBack = crash(approved);
		assertNull(ComposedModules.held(PARTS, approvedBack, "a lot may differ").ask());
		// 5. refused at put-down, saved before the quote: the restart asks whatever the quote, at least the approved bounds
		var refusedBack = crash(SettlementRunner.refusedAtPutDown(approvedBack));
		assertTrue(refusedBack.values().stream().allMatch(ModuleOrder::refusedAtPutDown));
		var reask = ComposedModules.held(PARTS, refusedBack, null);
		assertTrue(reask.ask().startsWith(ModuleBound.PUT_DOWN_REFUSED), reask.ask());
		// 6. that hold saved, crash before its phase: the refusal is kept, asked again; approved, it clears and the quote within goes on
		var reheldBack = crash(reask.orders());
		assertTrue(reheldBack.values().stream().allMatch(ModuleOrder::refusedAtPutDown));
		assertNotNull(ComposedModules.held(PARTS, reheldBack, null).ask());
		Map<String, ModuleOrder> ok = new LinkedHashMap<>();
		reheldBack.forEach((k, v) -> ok.put(k, v.approvedHeld()));
		var okBack = crash(ok);
		assertNull(ComposedModules.held(PARTS, okBack, null).ask());
		// the re-ask after a put-down refusal never shows less than the bill approved before it, item by item
		for (var e : okBack.entrySet()) for (var b : approvedBack.get(e.getKey()).bound().entrySet())
			assertTrue(e.getValue().bound().get(b.getKey()).getAsInt() >= b.getValue().getAsInt(), e.getKey() + " " + b.getKey());
	}

	@Test
	void theComposedHoldsApprovalWordsCarryTheCrashException() {
		String w = SettlementRunner.composedHoldWords("The working modules' bills came to more.");
		assertTrue(w.contains("(they become the bounds: " + ModuleBound.CRASH_EXCEPTION + ")"), w);
		assertTrue(w.contains("Nothing has been taken from the stockpile."), w);
	}

	@Test
	void aComposedRealiseFromMaterialsIsNeverSentUncapped() {
		var orders = orders(farm("{\"minecraft:dirt\": 130}"), hall(HALL_BOUND));
		assertTrue(SettlementRunner.composedMaterialsUncapped(true, orders), "no cap per lot in 0.74.0 (S-M5-16)");
		assertFalse(SettlementRunner.composedMaterialsUncapped(false, orders), "a town built at once bills nothing");
		assertFalse(SettlementRunner.composedMaterialsUncapped(true, Map.of()), "a town of designed lots only is billed as before");
		assertTrue(ComposedModules.MATERIALS_WAIT_FOR_LOT_CAP, "the guard stays on until S-M5-16");
		// and the single-module growth's cap is never sent for a composed town (more than one order)
		assertNull(SettlementRunner.realiseCap(true, true, orders, true));
	}

	/**
	 * Conservation of the hall's cargo count (FK-aware): a committed filter hopper holding exactly its declaration counts nothing; anything put in
	 * past it counts exactly what was put in (items in == counted), its own item over 41 and fillers over 4 included.
	 */
	@Test
	void theCargoCountIsWhatWasPutInPastTheFill() {
		String coal = "minecraft:coal";
		var fill = List.of(new HallCargo.Stack(coal, StorageHallLot.FILTER_COUNT, true), new HallCargo.Stack(StorageHallLot.FILLER, StorageHallLot.FILLERS, true));
		assertTrue(HallCargo.filterOurs(fill, coal));
		assertEquals(0, HallCargo.filterExcess(fill, coal));
		for (int put : new int[] {1, 7, 64}) {
			var more = new java.util.ArrayList<>(fill);
			more.add(new HallCargo.Stack(coal, put, true));
			assertFalse(HallCargo.filterOurs(more, coal));
			assertEquals(put, HallCargo.filterExcess(more, coal), "its own item past the declaration");
			var other = new java.util.ArrayList<>(fill);
			other.add(new HallCargo.Stack("minecraft:dirt", put, true));
			assertEquals(put, HallCargo.filterExcess(other, coal), "another item");
			var renamed = new java.util.ArrayList<>(fill);
			renamed.add(new HallCargo.Stack(coal, put, false));
			assertEquals(put, HallCargo.filterExcess(renamed, coal), "a renamed stack of its item");
		}
		// the filter item is the site's own param (a composed hall's ref keeps it), so another slot's item is cargo there
		assertEquals(List.of("minecraft:cobblestone", "minecraft:iron_ingot", "minecraft:wheat", "minecraft:coal"), StorageHallLot.items(new ModuleRef("storage_hall", 2, SHA, HALL_PARAMS).params()).items());
		assertEquals(StorageHallLot.FILTER_COUNT, HallCargo.filterExcess(List.of(new HallCargo.Stack("minecraft:cobblestone", StorageHallLot.FILTER_COUNT, true)), coal));
	}
}
