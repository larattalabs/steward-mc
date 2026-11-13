package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** A lot's {@code steward_mc:module} ext and its binding (docs/MODULES.md 1 and 4.2). */
class ModuleRefTest {
	static final String SHA = "f".repeat(64);

	static JsonObject j(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void theExtRoundTripsBesideTheLotsOtherExtAndMalformedReadsAsNone() {
		JsonObject ext = j("{\"steward_mc:settlement\":\"set_4\"}");
		ModuleRef r = new ModuleRef("fixture_plot", 1, SHA, j("{\"side\":9,\"crop\":\"wheat\"}"));
		JsonObject with = r.into(ext);
		assertFalse(ext.has(ModuleRef.EXT), "the given ext is not changed");
		assertEquals("set_4", with.get("steward_mc:settlement").getAsString());
		assertEquals(r, ModuleRef.of(with));
		assertEquals("{\"crop\":\"wheat\",\"side\":9}", ModuleRef.of(with).params().toString(), "params canonical");
		assertNull(ModuleRef.of(ext));
		assertNull(ModuleRef.of(null));
		assertNull(ModuleRef.of(j("{\"steward_mc:module\":{\"id\":\"x\",\"version\":0,\"programSha\":\"" + SHA + "\"}}")));
		assertNull(ModuleRef.of(j("{\"steward_mc:module\":{\"id\":\"x\",\"version\":1,\"programSha\":\"abc\"}}")));
		assertNull(ModuleRef.of(j("{\"steward_mc:module\":\"crop_farm\"}")));
	}

	@Test
	void aBindingKnowsItsGeometryWhateverTheKeyOrder() {
		JsonObject geo = j("{\"box\":[0,64,0,10,70,11],\"frame\":{\"front\":\"south\",\"size\":[11,12]},\"irrigation\":[[5,-1,5]]}");
		ModuleBinding b = new ModuleBinding("fixture_plot", 1, SHA, j("{\"side\":9,\"crop\":\"wheat\"}"), null, "fortress", 0, Canon.sha(geo),
			ModuleInstalls.lotKey("b1", "lot_3", 1), "steward_mc:settlement/set_4");
		assertTrue(b.fits(j("{\"irrigation\":[[5,-1,5]],\"frame\":{\"size\":[11,12],\"front\":\"south\"},\"box\":[0,64,0,10,70,11]}")));
		assertFalse(b.fits(j("{\"box\":[0,64,0,10,70,12],\"frame\":{\"front\":\"south\",\"size\":[11,12]},\"irrigation\":[[5,-1,5]]}")));
		assertEquals("fixture_plot_kv", b.withEntry("fixture_plot_kv").entryId());
		assertEquals(new ModuleRef("fixture_plot", 1, SHA, j("{\"crop\":\"wheat\",\"side\":9}")), b.ref());
	}
}
