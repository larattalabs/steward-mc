package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.gateway.PPlan;
import dev.larattalabs.steward.module.ModuleOrder;
import dev.larattalabs.steward.module.ModuleRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** docs/MODULES.md "M5 design" 2 and 9 (slice M5a): the plan a composed build checks and builds with its module orders, and their save. */
class ComposedModulesBuildTest {
	static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	static JsonObject plan() {
		return json("""
			{"schemaVersion": "plan/0", "planId": "oakvale", "zones": [{"id": "town", "required": true, "program": {"layout": "street", "lots": [
			  {"id": "cottage_1", "type": "cottage", "size": [9, 9]},
			  {"id": "farm_1", "type": "crop_farm", "label": "the village field", "size": [11, 12], "irrigate": true,
			   "ext": {"steward_mc:module": {"id": "crop_farm", "params": {"width": 9, "depth": 9, "crop": "wheat"}, "for": "farm"}}},
			  {"id": "hall_1", "type": "storage_hall", "size": [11, 7], "ext": {"steward_mc:module": {"id": "storage_hall", "params": {"slots": 0, "bulk": 2}}}}
			]}}]}""");
	}

	static ModuleOrder farm() {
		return new ModuleOrder("crop_farm", 1, "c".repeat(64), json("{\"crop\": \"wheat\", \"depth\": 9, \"width\": 9}"), null, null, false, false);
	}

	static ModuleOrder hall() {
		return new ModuleOrder("storage_hall", 1, "d".repeat(64), json("{\"bulk\": 2, \"slots\": 0}"), null, null, false, false);
	}

	@Test
	void thePlanAsBuiltHoldsEachOrderedModuleAndLeavesOutTheRest() {
		JsonObject tagged = PPlan.forBuild(plan(), Set.of(), "set_1", "b_1");
		JsonObject built = SettlementRunner.withModules(tagged, Map.of("farm_1", farm()));
		var lots = PPlan.lots(built);
		assertEquals(2, lots.size(), "the hall with no order is left out");
		assertEquals("cottage_1", lots.get(0).get("id").getAsString());
		JsonObject f = lots.get(1);
		assertEquals(farm().ref(), ModuleRef.of(f.getAsJsonObject("ext")));
		assertTrue(f.has("field"));
		assertEquals("set_1", f.getAsJsonObject("ext").get("steward_mc:settlement").getAsString(), "Steward's own tags stay on the lot");
		assertEquals("farm_1", f.getAsJsonObject("ext").get("steward_mc:lot").getAsString());
		// deterministic: a stale re-check checks the same plan
		assertEquals(built, SettlementRunner.withModules(PPlan.forBuild(plan(), Set.of(), "set_1", "b_1"), Map.of("farm_1", farm())));
		// no orders: every module lot is left out (never built as an ordinary lot from what the composer wrote)
		assertEquals(1, PPlan.lots(SettlementRunner.withModules(tagged, Map.of())).size());
		// a plan with no module lot is untouched
		JsonObject plain = json("{\"zones\": [{\"id\": \"t\", \"program\": {\"lots\": [{\"id\": \"cottage_1\", \"type\": \"cottage\"}]}}]}");
		assertEquals(plain, SettlementRunner.withModules(plain, Map.of()));
	}

	@Test
	void twoOrdersRoundTripInPlanOrder(@TempDir Path dir) throws Exception {
		BuildStore st = new BuildStore();
		Map<String, ModuleOrder> orders = new LinkedHashMap<>();
		orders.put("hall_1", hall());
		orders.put("farm_1", farm().holding(json("{\"minecraft:dirt\": 120}")));
		var b = BuildStoreTestAccess.saved().withModuleOrders(orders);
		st.put(b);
		Path f = dir.resolve("steward-builds.json");
		st.save(f);
		var back = BuildStore.load(f).all().get(0);
		assertEquals(b, back);
		assertEquals(java.util.List.of("hall_1", "farm_1"), java.util.List.copyOf(back.moduleOrders().keySet()));
		assertNull(back.moduleOrders().get("hall_1").bound(), "a bound not set yet stays unset");
		assertEquals(json("{\"minecraft:dirt\": 120}"), back.moduleOrders().get("farm_1").held());
		assertTrue(Files.readString(f).contains("moduleOrders"));
	}
}
