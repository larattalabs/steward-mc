package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** docs/MODULES.md 4.4, 4.2 step 5 and Q4 (slice M3): the bound the player approves, and the quote held to it. */
class ModuleBoundTest {
	static JsonObject j(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void theMeasuredBoundIsTheOneForExactlyTheseParams() {
		var raw = j("{\"bounds\": [{\"params\": {\"side\": 5, \"crop\": \"wheat\"}, \"bom\": {\"minecraft:dirt\": 40, \"minecraft:wheat_seeds\": 25, \"minecraft:air\": 0}},"
			+ " {\"params\": {\"side\": 9, \"crop\": \"wheat\"}, \"bom\": {\"minecraft:dirt\": 100}}]}");
		var m = new ModuleCatalog.Measured("", "", "", "", List.of(), null, null, false, raw);
		assertEquals(j("{\"minecraft:dirt\": 40, \"minecraft:wheat_seeds\": 25}"), ModuleBound.of(m, j("{\"crop\": \"wheat\", \"side\": 5}")), "keys in any order; zero counts dropped");
		assertNull(ModuleBound.of(m, j("{\"crop\": \"beetroots\", \"side\": 5}")));
		assertNull(ModuleBound.of(null, j("{}")));
	}

	@Test
	void anUnreadableBillIsNoBill() {
		assertNull(ModuleBound.clean(j("{\"minecraft:dirt\": 1.5}")));
		assertNull(ModuleBound.clean(j("{\"minecraft:dirt\": -1}")));
		assertNull(ModuleBound.clean(j("{\"minecraft:dirt\": \"ten\"}")));
		assertNull(ModuleBound.clean(j("{\"minecraft:dirt\": 1e3}")));
		assertEquals(j("{\"a\": 1, \"b\": 2}"), ModuleBound.clean(j("{\"b\": 2, \"a\": 1}")));
	}

	@Test
	void aQuotePastTheBoundOrNamingAnotherItemIsOver() {
		var bound = j("{\"minecraft:dirt\": 40, \"minecraft:wheat_seeds\": 25}");
		assertTrue(ModuleBound.over(j("{\"minecraft:dirt\": 40, \"minecraft:wheat_seeds\": 20}"), bound).isEmpty());
		var over = ModuleBound.over(j("{\"minecraft:dirt\": 41, \"minecraft:lantern\": 2, \"minecraft:wheat_seeds\": 25}"), bound);
		assertEquals(List.of(new ModuleBound.Over("minecraft:dirt", 41, 40), new ModuleBound.Over("minecraft:lantern", 2, 0)), over);
		assertEquals("41 dirt (bound 40), 2 lantern (not in the bound)", ModuleBound.words(over, 4));
		// a count the quote can't state as a whole number is over, whatever the bound
		assertFalse(ModuleBound.over(j("{\"minecraft:dirt\": 1.5}"), bound).isEmpty());
	}

	@Test
	void autonomousAsksWhenTheBillTakesMoreThanHalfOfAnItem() {
		var bill = j("{\"minecraft:dirt\": 50, \"minecraft:wheat_seeds\": 25}");
		assertTrue(ModuleBound.heavy(bill, Map.of("minecraft:dirt", 100, "minecraft:wheat_seeds", 50)).isEmpty(), "exactly half is not more than half");
		var h = ModuleBound.heavy(bill, Map.of("minecraft:dirt", 99, "minecraft:wheat_seeds", 400));
		assertEquals(List.of(new ModuleBound.Heavy("minecraft:dirt", 50, 99)), h);
		assertEquals(2, ModuleBound.heavy(bill, Map.of()).size(), "an item the stockpile holds none of");
	}

	@Test
	void theBillInWords() {
		assertEquals("50 dirt, 25 wheat seeds", ModuleBound.bill(j("{\"minecraft:wheat_seeds\": 25, \"minecraft:dirt\": 50}"), 4));
		assertEquals("50 dirt and 1 more", ModuleBound.bill(j("{\"minecraft:wheat_seeds\": 25, \"minecraft:dirt\": 50}"), 1));
	}

	@Test
	void anOrderKeepsOnlyAReadableBound() {
		var o = new ModuleOrder("fixture_plot", 1, "a".repeat(64), j("{\"side\": 5, \"crop\": \"wheat\"}"), j("{\"minecraft:dirt\": 2}"), null, false, false);
		assertTrue(o.valid());
		assertEquals("{\"crop\":\"wheat\",\"side\":5}", Canon.text(o.params()), "params canonical");
		var held = o.accepted(true, "module.fixture_plot").holding(j("{\"minecraft:dirt\": 9}"));
		assertTrue(held.autonomous());
		assertEquals("module.fixture_plot", held.proposal());
		var ok = held.approvedHeld();
		assertEquals(j("{\"minecraft:dirt\": 9}"), ok.bound(), "the bill shown becomes the bound");
		assertNull(ok.held());
		assertFalse(ok.autonomous(), "the player's word now");
		assertTrue(ok.inexactApproved(), "an approved inexact bill is not asked about again");
		assertFalse(held.inexactApproved());
		assertFalse(new ModuleOrder("Bad Id", 1, "a".repeat(64), j("{}"), null, null, false, false).valid());
	}

	@Test
	void aTerrainItemIsAllowedTwoOrTwoPercentOfItsTerrainPartOverItsQuote() {
		assertEquals(2, ModuleBound.allowance(0), "a terrain item at 0 still gets its 2");
		assertEquals(2, ModuleBound.allowance(1));
		assertEquals(2, ModuleBound.allowance(100));
		assertEquals(3, ModuleBound.allowance(101), "ceil(2.02)");
		assertEquals(5, ModuleBound.allowance(209), "ceil(4.18)");
		assertEquals(2, ModuleBound.allowance(-4), "a negative part counts as 0");
	}

	@Test
	void theAllowedBillAddsTheAllowanceToTerrainItemsOnlyOverBomAndTerrainKeys() {
		// crop-farm-067: 209 dirt, 205 of it from the kept terrain cells; gravel's terrain cells all stand (0, absent from the bom); planks are lot material
		var bom = j("{\"minecraft:dirt\": 209, \"minecraft:oak_planks\": 40}");
		var allowed = ModuleBound.allowed(bom, Map.of("minecraft:dirt", 205, "minecraft:gravel", 0));
		assertEquals(j("{\"minecraft:dirt\": 214, \"minecraft:gravel\": 2, \"minecraft:oak_planks\": 40}"), allowed,
			"dirt 209 + ceil(4.1); gravel 0 + 2; planks exact");
		// the one-cell drift that re-asked before: within it, item by item
		assertTrue(ModuleBound.over(j("{\"minecraft:dirt\": 210, \"minecraft:oak_planks\": 40}"), allowed).isEmpty());
		assertTrue(ModuleBound.over(j("{\"minecraft:dirt\": 214, \"minecraft:gravel\": 2, \"minecraft:oak_planks\": 40}"), allowed).isEmpty());
		assertEquals(List.of(new ModuleBound.Over("minecraft:dirt", 215, 214)), ModuleBound.over(j("{\"minecraft:dirt\": 215, \"minecraft:oak_planks\": 40}"), allowed));
		assertEquals(List.of(new ModuleBound.Over("minecraft:oak_planks", 41, 40)), ModuleBound.over(j("{\"minecraft:dirt\": 209, \"minecraft:oak_planks\": 41}"), allowed),
			"lot material stays exact: one plank over is past it");
		// an Architect without quoteTerrain: every item exact
		assertEquals(bom, ModuleBound.allowed(bom, Map.of()));
		assertEquals(bom, ModuleBound.allowed(bom, null));
		assertNull(ModuleBound.allowed(j("{\"minecraft:dirt\": -1}"), Map.of()));
		var neg = new java.util.HashMap<String, Integer>();
		neg.put("minecraft:dirt", -1);
		assertNull(ModuleBound.allowed(bom, neg), "an unreadable terrain count refuses rather than guess");
		assertEquals(Map.of("minecraft:dirt", 214, "minecraft:gravel", 2, "minecraft:oak_planks", 40), ModuleBound.cap(allowed));
		// asked again after a put-down refusal: never less than what was approved, item by item
		assertEquals(j("{\"minecraft:dirt\": 220, \"minecraft:gravel\": 2, \"minecraft:oak_planks\": 40}"),
			ModuleBound.atLeast(j("{\"minecraft:dirt\": 220, \"minecraft:oak_planks\": 38}"), allowed));
		assertEquals(j("{\"minecraft:dirt\": 1}"), ModuleBound.atLeast(j("{\"minecraft:dirt\": 1}"), null));
		assertEquals(Map.of(), ModuleBound.cap(j("{}")), "an empty approved bill caps every item at 0");
	}

	@Test
	void theApprovedBillSaysUpToForTerrainItemsAndTheCountForTheRest() {
		var allowed = j("{\"minecraft:dirt\": 214, \"minecraft:gravel\": 2, \"minecraft:oak_planks\": 40}");
		assertEquals("up to 214 dirt, 40 oak planks, up to 2 gravel", ModuleBound.upTo(allowed, java.util.Set.of("minecraft:dirt", "minecraft:gravel"), 10));
		assertEquals("214 dirt and 2 more", ModuleBound.upTo(allowed, java.util.Set.of(), 1));
		assertEquals("3 dirt, 1 gravel and 4 units more besides", ModuleBound.overWords(List.of(Map.entry("minecraft:dirt", 3), Map.entry("minecraft:gravel", 1)), 4));
		assertEquals("1 dirt and 1 unit more besides", ModuleBound.overWords(List.of(Map.entry("minecraft:dirt", 1)), 1));
	}
}
