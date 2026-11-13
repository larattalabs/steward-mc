package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** docs/MODULES.md 4.2 (slice M3): how the fixture module is offered and asked of Architect; the id stays a parameter. */
class ModuleKindTest {
	static final ModuleKind K = ModuleKind.FixturePlot.INSTANCE;

	@Test
	void theRuleKeyNamesTheModule() {
		assertEquals("module.fixture_plot", K.rule());
		assertEquals("fixture_plot", ModuleKind.idOf("module.fixture_plot"));
		assertNull(ModuleKind.idOf("farm"));
		assertNull(ModuleKind.idOf("module."));
		assertSame(K, ModuleKind.of("fixture_plot").orElseThrow());
		assertTrue(ModuleKind.of("crop_farm").isEmpty(), "known once M2 merges (the switch); until then not offered");
	}

	@Test
	void theGrowLotIsTheExactFrame() {
		var p = JsonParser.parseString("{\"side\": 9, \"crop\": \"wheat\"}").getAsJsonObject();
		assertArrayEquals(new int[] {11, 12}, K.frame(p), "W = side + 2, D = side + 3");
		var lot = K.growLot("gb1_1", p);
		assertEquals("{\"id\":\"gb1_1\",\"size\":[11,12],\"type\":\"module_fixture\"}", Canon.text(lot));
		assertFalse(lot.has("irrigate"), "the fixture has no water");
		assertEquals(81, K.cells(p));
	}

	@Test
	void aSmallerSizeIsTheNextOfTheDiscreteSet() {
		var p = JsonParser.parseString("{\"side\": 9, \"crop\": \"beetroots\"}").getAsJsonObject();
		var s = K.smaller(p).orElseThrow();
		assertEquals(5, s.get("side").getAsInt());
		assertEquals("beetroots", s.get("crop").getAsString(), "the crop is kept");
		assertTrue(K.smaller(s).isEmpty(), "5 is the smallest");
		assertTrue(K.smaller(JsonParser.parseString("{\"side\": 7, \"crop\": \"wheat\"}").getAsJsonObject()).isEmpty());
	}
}
