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
		assertFalse(new ModuleOrder("Bad Id", 1, "a".repeat(64), j("{}"), null, null, false, false).valid());
	}
}
