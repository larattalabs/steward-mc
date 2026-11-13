package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.module.ModuleCatalog.Entry;
import dev.larattalabs.steward.module.ModuleGate.Env;
import dev.larattalabs.steward.module.ModuleGate.Why;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The module catalog (docs/MODULES.md 1, 2.1, 2.2; slice M1): its format, the fixture module, gating and the params envelope. */
class ModuleCatalogTest {
	static final Path FIXTURES = Path.of(System.getProperty("user.dir")).resolve("../fixtures/modules/catalog").normalize();
	static final Env HERE = new Env("26.3", "0.62.0", Set.of("givenPrograms"), true);

	static Entry fixture() {
		return ModuleCatalog.dir(FIXTURES, true).get("fixture_plot", 1).orElseThrow();
	}

	/** The fixture catalog's files as a map (rel path -> text), to tamper with. */
	static Map<String, String> files() throws Exception {
		Map<String, String> m = new HashMap<>();
		for (String f : List.of("catalog.json", "fixture_plot/1/program.mjs", "fixture_plot/1/module.json", "fixture_plot/1/measured.json")) {
			m.put(f, Files.readString(FIXTURES.resolve(f), StandardCharsets.UTF_8));
		}
		return m;
	}

	static ModuleCatalog of(Map<String, String> files) {
		return ModuleCatalog.load(rel -> Optional.ofNullable(files.get(rel)), true);
	}

	static String with(String json, String key, String value) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		o.add(key, JsonParser.parseString(value));
		return o.toString();
	}

	@Test
	void theFixtureModuleLoadsWithItsProgramSchemaEnvelopeAndMeasurement() throws Exception {
		Entry e = fixture();
		assertTrue(e.valid(), () -> String.join("; ", e.problems()));
		assertTrue(e.fixture());
		assertEquals("fixture_plot@1", e.key());
		assertEquals(Canon.sha256(Files.readString(FIXTURES.resolve("fixture_plot/1/program.mjs"))), e.programSha(), "the identity is the exact source's sha256");
		assertEquals(List.of("side", "crop"), List.copyOf(e.params().keySet()));
		assertEquals(4, e.envelope().combinations().size(), "every branch: 2 sides x 2 crops");
		assertEquals(List.of("rustic", "fortress", "desert"), e.envelope().bibles());
		assertEquals(14, e.palette().lamp().minLight());
		assertEquals(List.of("path", "frame"), e.palette().noGravity());
		assertEquals("26.3", e.measured().minecraft());
		assertEquals(List.of("0.62.0"), e.measured().architect());
	}

	@Test
	void theLimitsAreArchitects() {
		assertEquals(dev.larattalabs.architect.api.Limits.PROGRAM_SOURCE_MAX, ModuleCatalog.PROGRAM_SOURCE_MAX);
		assertEquals(dev.larattalabs.architect.api.Limits.PROGRAM_PARAMS_MAX, ModuleCatalog.PROGRAM_PARAMS_MAX);
	}

	@Test
	void theLiveCatalogShipsInTheJarAndNeverHoldsAFixture() throws Exception {
		ModuleCatalog live = ModuleCatalog.live(getClass().getClassLoader());
		assertEquals(List.of(), live.problems(), "steward/modules/catalog.json is packaged and reads");
		assertTrue(live.entries().stream().noneMatch(Entry::fixture));
		// the fixture catalog read as the live one: its fixture is refused, not loaded
		ModuleCatalog asLive = ModuleCatalog.load(rel -> {
			try {
				return Optional.ofNullable(files().get(rel));
			} catch (Exception e) {
				return Optional.empty();
			}
		}, false);
		assertTrue(asLive.entries().isEmpty());
		assertTrue(asLive.problems().get(0).contains("fixture"));
	}

	@Test
	void aTamperedOrUnmatchedEntryIsKeptWithItsProblemsAndNeverOffered() throws Exception {
		var f = files();
		f.put("fixture_plot/1/program.mjs", f.get("fixture_plot/1/program.mjs").replace("RIPE = { wheat: 7", "RIPE = { wheat: 6"));
		Entry e = of(f).get("fixture_plot", 1).orElseThrow();
		assertFalse(e.valid());
		assertTrue(e.problems().stream().anyMatch(p -> p.contains("not the program module.json pins")));
		assertTrue(e.problems().stream().anyMatch(p -> p.contains("measured another program")));
		assertEquals(Why.INVALID, ModuleGate.offer(e, HERE).why());

		f = files();
		f.put("fixture_plot/1/program.mjs", f.get("fixture_plot/1/program.mjs").replace("\n", "\r\n"));
		assertFalse(of(f).get("fixture_plot", 1).orElseThrow().valid(), "line endings are part of the program");

		f = files();
		f.put("fixture_plot/1/module.json", with(f.get("fixture_plot/1/module.json"), "envelope", "{\"branches\":{\"side\":[5,11]},\"bibles\":[\"rustic\"]}"));
		assertTrue(of(f).get("fixture_plot", 1).orElseThrow().problems().stream().anyMatch(p -> p.contains("11 is outside")));

		f = files();
		f.put("fixture_plot/1/program.mjs", "x".repeat(ModuleCatalog.PROGRAM_SOURCE_MAX + 1));
		assertTrue(of(f).get("fixture_plot", 1).orElseThrow().problems().stream().anyMatch(p -> p.contains("at most")));

		f = files();
		f.put("catalog.json", "{\"format\":2,\"modules\":[]}");
		assertTrue(of(f).entries().isEmpty());
		assertTrue(of(f).problems().get(0).contains("format 2"));
	}

	@Test
	void noMeasurementNoOffer() throws Exception {
		var f = files();
		f.remove("fixture_plot/1/measured.json");
		Entry e = of(f).get("fixture_plot", 1).orElseThrow();
		assertFalse(e.valid());
		assertNull(e.measured());
		assertFalse(ModuleGate.offer(e, HERE).ok());
	}

	@Test
	void offeredOnlyOnTheVersionsItWasMeasuredOnWithGivenProgramsAndFixturesOnlyInDev() {
		Entry e = fixture();
		assertTrue(ModuleGate.offer(e, HERE).ok());
		assertEquals(Why.MINECRAFT, ModuleGate.offer(e, new Env("26.4", "0.62.0", Set.of("givenPrograms"), true)).why());
		assertEquals(Why.ARCHITECT, ModuleGate.offer(e, new Env("26.3", "0.63.0", Set.of("givenPrograms"), true)).why());
		assertEquals(Why.ARCHITECT, ModuleGate.offer(e, new Env("26.3", "0.61.0", Set.of("givenPrograms"), true)).why());
		assertEquals(Why.FEATURE, ModuleGate.offer(e, new Env("26.3", "0.62.0", Set.of(), true)).why());
		assertEquals(Why.FIXTURE, ModuleGate.offer(e, new Env("26.3", "0.62.0", Set.of("givenPrograms"), false)).why());
		assertEquals(Why.UNKNOWN, ModuleGate.offer(ModuleCatalog.dir(FIXTURES, true), "crop_farm", HERE).why());
	}

	@Test
	void theNewestVersionIsJudgedAndNeverFallsBackToAnOlderOne() throws Exception {
		var f = files();
		// a version 2 that was measured on another Minecraft: version 1 is measured here, but is not offered in its place
		f.put("catalog.json", "{\"format\":1,\"modules\":[{\"id\":\"fixture_plot\",\"version\":1},{\"id\":\"fixture_plot\",\"version\":2}]}");
		f.put("fixture_plot/2/program.mjs", f.get("fixture_plot/1/program.mjs"));
		f.put("fixture_plot/2/module.json", with(f.get("fixture_plot/1/module.json"), "version", "2"));
		f.put("fixture_plot/2/measured.json", with(with(f.get("fixture_plot/1/measured.json"), "version", "2"), "minecraft", "\"26.2\""));
		ModuleCatalog c = of(f);
		assertTrue(ModuleGate.offer(c.get("fixture_plot", 1).orElseThrow(), HERE).ok());
		var v = ModuleGate.offer(c, "fixture_plot", HERE);
		assertFalse(v.ok());
		assertEquals(Why.MINECRAFT, v.why());
		assertEquals(2, v.entry().version());
	}

	@Test
	void paramsStayInsideTheEnvelopeInCanonicalForm() {
		Entry e = fixture();
		var d = ModuleGate.params(e, null);
		assertTrue(d.ok());
		assertEquals("{\"crop\":\"wheat\",\"side\":5}", d.values().toString(), "defaults filled in, keys sorted");
		var b = ModuleGate.params(e, JsonParser.parseString("{\"side\":9,\"crop\":\"beetroots\"}").getAsJsonObject());
		assertEquals("{\"crop\":\"beetroots\",\"side\":9}", b.values().toString());
		assertFalse(ModuleGate.params(e, JsonParser.parseString("{\"side\":7}").getAsJsonObject()).ok(), "in the schema (5..9), but not a measured branch");
		assertFalse(ModuleGate.params(e, JsonParser.parseString("{\"side\":27}").getAsJsonObject()).ok());
		assertFalse(ModuleGate.params(e, JsonParser.parseString("{\"side\":5.5}").getAsJsonObject()).ok());
		assertFalse(ModuleGate.params(e, JsonParser.parseString("{\"crop\":\"carrots\"}").getAsJsonObject()).ok());
		assertFalse(ModuleGate.params(e, JsonParser.parseString("{\"rows\":3}").getAsJsonObject()).ok(), "an unknown param");
	}

	@Test
	void keysAreTheCatalogsAndTheLotsShapes() {
		Entry e = fixture();
		assertEquals("steward.catalog.fixture_plot@1." + e.programSha().substring(0, 12), ModuleInstalls.catalogKey(e));
		assertEquals("steward.b17.module.lot_3", ModuleInstalls.lotKey("b17", "lot_3", 1));
		assertEquals("steward.b17.module.lot_3.2", ModuleInstalls.lotKey("b17", "lot_3", 2));
	}
}
