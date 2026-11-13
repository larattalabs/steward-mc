package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.BlockSize;
import dev.larattalabs.architect.api.LotSpec;
import dev.larattalabs.steward.module.Canon;
import dev.larattalabs.steward.module.ModuleBinding;
import dev.larattalabs.steward.module.ModuleInstalls;
import dev.larattalabs.steward.module.ModuleOrder;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

/** docs/MODULES.md 4.2 steps 2-4, "A stale plan", "Recovery" (slice M3): geometry, bindings that hold, and the keys a re-plan uses. */
class ModulePlacementTest {
	static final String SHA = "f613be4e32994935130a72d7ac0cbeb191d5938ce3ebc1fcf66745da0bc0507e";
	static final JsonObject IRR = JsonParser.parseString("{\"sources\": [[5, 64, 6]], \"bodies\": [\"b\"], \"field\": [1, 1, 9, 9], \"cells\": [[105, 206, 63, 65]],"
		+ " \"frame\": [11, 12], \"lotCells\": [[5, 5, -1, 1]]}").getAsJsonObject();

	static LotSpec lot(int x, boolean irrigated) {
		JsonObject ext = new JsonObject();
		if (irrigated) ext.add(ModulePlacement.IRRIGATION_EXT, IRR.deepCopy());
		return new LotSpec("gb1_1", "lots", new BoundingBox(x, 64, 200, x + 10, 70, 211), 64, Direction.SOUTH, null, new BlockSize(11, 6, 12), ext);
	}

	static ModuleOrder order(int side) {
		return new ModuleOrder("fixture_plot", 1, SHA, JsonParser.parseString("{\"side\": " + side + ", \"crop\": \"wheat\"}").getAsJsonObject(), null, null, false, false);
	}

	static ModuleBinding binding(ModuleOrder o, LotSpec l, String key) {
		return new ModuleBinding(o.moduleId(), o.version(), o.programSha(), o.params(), "lib_1", "rustic", 1, Canon.sha(ModulePlacement.geometry(l)), key, "steward_mc:settlement/set_1");
	}

	@Test
	void theGeometryCoversTheBoxFrontFrameAndIrrigation() {
		var g = ModulePlacement.geometry(lot(100, true));
		assertEquals("[100,64,200,110,70,211]", g.get("box").toString());
		assertEquals("south", g.get("front").getAsString());
		assertEquals("[11,12]", g.get("frame").toString(), "IRR's frame");
		assertTrue(g.getAsJsonObject("irrigation").has("lotCells"));
		assertEquals("[11,12]", ModulePlacement.geometry(lot(100, false)).get("frame").toString(), "no water: the box's spans");
		assertFalse(ModulePlacement.geometry(lot(100, false)).has("irrigation"));
		assertEquals(Canon.sha(ModulePlacement.geometry(lot(100, true))), Canon.sha(ModulePlacement.geometry(lot(100, true))), "stable");
		assertNotEquals(Canon.sha(ModulePlacement.geometry(lot(100, true))), Canon.sha(ModulePlacement.geometry(lot(101, true))), "a moved lot is another geometry");
	}

	@Test
	void theReservedCellsAreIrrsLotCellsInTheFrame() {
		var r = ModulePlacement.reserved(lot(100, true));
		assertNotNull(r);
		assertEquals("south", r.front());
		assertEquals(11, r.width());
		assertEquals(12, r.depth());
		assertEquals(3, r.cells().size(), "u 5, v 5, y -1..1");
		assertEquals(new net.minecraft.core.BlockPos(5, -1, 5), r.cells().get(0));
		assertNull(ModulePlacement.reserved(lot(100, false)));
	}

	@Test
	void aBindingHoldsOnlyForTheSameOrderAndGeometry() {
		var l = lot(100, true);
		var b = binding(order(5), l, "steward.b1.module.gb1_1");
		assertTrue(ModulePlacement.holds(b, order(5), ModulePlacement.geometry(l)));
		assertFalse(ModulePlacement.holds(b, order(9), ModulePlacement.geometry(l)), "a smaller size after NO_ROOM is another variant");
		assertFalse(ModulePlacement.holds(b, order(5), ModulePlacement.geometry(lot(103, true))), "re-planned: made again");
		assertFalse(ModulePlacement.holds(null, order(5), ModulePlacement.geometry(l)));
		var other = new ModuleOrder("fixture_plot", 1, "b".repeat(64), order(5).params(), null, null, false, false);
		assertFalse(ModulePlacement.holds(b, other, ModulePlacement.geometry(l)), "another program");
	}

	@Test
	void aReplanMakesTheVariantUnderTheNextKey() {
		assertEquals("steward.b1.module.gb1_1", ModulePlacement.nextKey(null, "b1", "gb1_1"));
		var l = lot(100, true);
		var b1 = binding(order(5), l, "steward.b1.module.gb1_1");
		assertEquals("steward.b1.module.gb1_1.2", ModulePlacement.nextKey(b1, "b1", "gb1_1"));
		var b2 = binding(order(5), l, "steward.b1.module.gb1_1.2");
		assertEquals("steward.b1.module.gb1_1.3", ModulePlacement.nextKey(b2, "b1", "gb1_1"));
		assertEquals(2, ModulePlacement.keyIndex("steward.b1.module.gb1_1.2", "b1", "gb1_1"));
		assertTrue(ModulePlacement.nextKey(b2, "b1", "gb1_1").matches(dev.larattalabs.steward.module.ModuleInstalls.KEY_RE));
	}

	@Test
	void anInexactQuoteNeverClearsTheBuildByItself() {
		assertEquals(new ModulePlacement.QuoteCheck(null, null), ModulePlacement.quoteCheck(true, java.util.List.of("anything")));
		var unbilled = ModulePlacement.quoteCheck(false, java.util.List.of("lot gb1_1 (e@1): no construction plan over the land now"));
		assertNotNull(unbilled.refused(), "a part left unbilled: refused, nothing consumed");
		assertNotNull(ModulePlacement.quoteCheck(false, java.util.List.of("road r1: its check refuses over the land now (x): not billed")).refused());
		var drift = ModulePlacement.quoteCheck(false, java.util.List.of("lot gb1_1 (e@1) may differ: drift"));
		assertNull(drift.refused());
		assertNotNull(drift.ask(), "may differ: the player decides");
		assertEquals(new ModulePlacement.QuoteCheck(null, null), ModulePlacement.quoteCheck(false,
			java.util.List.of("fluids: a lava bucket per lava source and an iron bars per grate cell of every body (an upper bound)", "the region's cells are the kit's bill")),
			"the fluids' upper bound is conservative");
		assertNotNull(ModulePlacement.quoteCheck(false, java.util.List.of()).ask(), "inexact for no reason given: asked");
		var masked = ModulePlacement.quoteCheck(false, java.util.List.of("fluids: a lava bucket per lava source (an upper bound)", "something new Architect says"));
		assertNotNull(masked.ask(), "the fluids' bound never masks an unknown reason");
	}

	@Test
	void aLostVariantAnswerIsAskedAgainButARefusalIsNot() {
		var timeout = new ModuleInstalls.Outcome(null, "error", "java.util.concurrent.TimeoutException", java.util.List.of(), null, null, null, false);
		assertTrue(SettlementRunner.lostAnswer(null, timeout));
		assertTrue(SettlementRunner.lostAnswer(new java.util.concurrent.CompletionException(new java.util.concurrent.TimeoutException()), null));
		var helperGone = new ModuleInstalls.Outcome(null, "sidecar_unavailable", "the helper is not connected", java.util.List.of(), null, null, null, false);
		assertTrue(SettlementRunner.lostAnswer(null, helperGone));
		var palette = new ModuleInstalls.Outcome(null, "palette", "fixture_plot@1 can't take the style", java.util.List.of(), null, null, null, false);
		assertFalse(SettlementRunner.lostAnswer(null, palette), "a refusal is definitive");
		var program = new ModuleInstalls.Outcome(null, "program", "palette: refuses", java.util.List.of("x: y"), null, null, null, false);
		assertFalse(SettlementRunner.lostAnswer(null, program));
	}

	@Test
	void aQuoteClearsExactlyItsPlanAndEntries() {
		assertEquals("p1|a=e1|b=e2", ModulePlacement.cleared("p1", Map.of("b", "e2", "a", "e1")));
		assertNotEquals(ModulePlacement.cleared("p1", Map.of("a", "e1")), ModulePlacement.cleared("p1", Map.of("a", "e3")));
		assertNotEquals(ModulePlacement.cleared("p1", Map.of("a", "e1")), ModulePlacement.cleared("p2", Map.of("a", "e1")));
	}
}
