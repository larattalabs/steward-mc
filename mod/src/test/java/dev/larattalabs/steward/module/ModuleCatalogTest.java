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
	static final Env HERE = new Env("26.3", "0.62.0", Set.of("givenPrograms"), true, false);

	/** The fixture as committed (its measured.json may or may not certify it yet). */
	static Entry committed() {
		return ModuleCatalog.dir(FIXTURES, true).get("fixture_plot", 1).orElseThrow();
	}

	/** The fixture with a measured.json that certifies it (result pass, the current moduleSha, the pins). */
	static Entry fixture() throws Exception {
		return of(certify(files(), "fixture_plot/1")).get("fixture_plot", 1).orElseThrow();
	}

	/** files with {@code dir}'s measured.json made a passing certificate of its module.json. */
	static Map<String, String> certify(Map<String, String> f, String dir) {
		JsonObject mod = JsonParser.parseString(f.get(dir + "/module.json")).getAsJsonObject();
		String m = f.get(dir + "/measured.json");
		m = with(m, "result", "\"pass\"");
		m = with(m, "moduleSha", "\"" + ModuleCatalog.moduleSha(mod) + "\"");
		m = with(m, "programSha", "\"" + Canon.sha256(f.get(dir + "/program.mjs")) + "\"");
		f.put(dir + "/measured.json", m);
		return f;
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

	/**
	 * files with {@code dir}'s measured.json made a smoke certificate (speed rule 4) for Architect 0.62.0, carrying a full measurement on
	 * Architect 0.61.0 with kit and checker 0.56.0 (module.json then pinned them; nothing else differs).
	 */
	static Map<String, String> smoke(Map<String, String> f, String dir) {
		certify(f, dir);
		JsonObject mod = JsonParser.parseString(f.get(dir + "/module.json")).getAsJsonObject();
		JsonObject now = JsonParser.parseString(f.get(dir + "/measured.json")).getAsJsonObject();
		now.add("bounds", JsonParser.parseString("[{\"params\":{\"side\":5},\"style\":\"rustic\",\"bill\":{\"minecraft:dirt\":3}}]"));
		JsonObject carried = now.deepCopy();
		carried.remove("bounds");
		JsonObject old = mod.deepCopy();
		JsonObject pins = new JsonObject();
		pins.addProperty("kit", "0.56.0");
		pins.addProperty("checker", "0.56.0");
		old.add("pins", pins);
		carried.addProperty("basis", "full");
		carried.addProperty("moduleSha", ModuleCatalog.moduleSha(old));
		carried.addProperty("kit", "0.56.0");
		carried.addProperty("checker", "0.56.0");
		carried.add("architect", JsonParser.parseString("[\"0.61.0\"]"));
		carried.add("checks", JsonParser.parseString("{\"measure\":{\"field-wheat\":{\"passed\":62,\"of\":62}}}"));
		carried.addProperty("boundsSha", Canon.sha(now.get("bounds")));
		carried.addProperty("ratesSha", Canon.sha(now.get("rates")));
		now.addProperty("basis", "smoke");
		now.addProperty("from", "0.61.0");
		now.add("carried", carried);
		String stamp = "\"programSha\":\"" + now.get("programSha").getAsString() + "\",\"architect\":\"" + now.getAsJsonArray("architect").get(0).getAsString()
			+ "\",\"minecraft\":\"" + now.get("minecraft").getAsString() + "\"";
		String arch = now.getAsJsonArray("architect").get(0).getAsString();
		now.add("checks", JsonParser.parseString("{\"offline\":{\"architect\":\"" + arch + "\",\"branches\":[{\"ok\":true,\"blocks\":9}]},\"ingame\":{\"env\":{\"architect\":\""
			+ arch + "\"},\"install\":{\"ok\":true},\"variants\":[{\"ok\":true}]},\"smoke\":{\"survival\":{\"step\":\"survival\",\"passed\":63,\"of\":63," + stamp
			+ "},\"bounds\":{\"step\":\"bounds\",\"passed\":111,\"of\":111," + stamp + "},\"fields\":[{\"step\":\"field-wheat\",\"runs\":5,\"passed\":62,\"of\":62," + stamp + "}]}}"));
		f.put(dir + "/measured.json", now.toString());
		return f;
	}

	static JsonObject smokeOf(JsonObject m) {
		return m.getAsJsonObject("checks").getAsJsonObject("smoke");
	}

	/** {@code dir}'s measured.json with {@code edit} applied to it. */
	static Map<String, String> edit(Map<String, String> f, String dir, java.util.function.Consumer<JsonObject> edit) {
		JsonObject m = JsonParser.parseString(f.get(dir + "/measured.json")).getAsJsonObject();
		edit.accept(m);
		f.put(dir + "/measured.json", m.toString());
		return f;
	}

	static Entry fixtureOf(Map<String, String> f) {
		return of(f).get("fixture_plot", 1).orElseThrow();
	}

	@Test
	void aSmokeCertificateOffersOnItsVersionAndKeepsTheFullMeasurementItCarries() throws Exception {
		Entry e = fixtureOf(smoke(files(), "fixture_plot/1"));
		assertTrue(e.certified(), () -> String.join("; ", e.uncertified()));
		assertTrue(e.measured().smoke());
		assertEquals("0.61.0", e.measured().from());
		var v = ModuleGate.offer(e, HERE);
		assertTrue(v.ok(), v.message());
		assertTrue(v.message().contains("smoke check"), v.message());
		// only on the version it names: not on the fully measured one it carries, nor any other
		assertEquals(Why.ARCHITECT, ModuleGate.offer(e, new Env("26.3", "0.61.0", Set.of("givenPrograms"), true, false)).why());
		assertEquals(Why.ARCHITECT, ModuleGate.offer(e, new Env("26.3", "0.63.0", Set.of("givenPrograms"), true, false)).why());
		// the bound offered is the carried measurement's
		assertEquals(1, e.measured().raw().getAsJsonArray("bounds").size());
		assertTrue(e.measured().raw().getAsJsonObject("carried").getAsJsonObject("checks").has("measure"));
	}

	@Test
	void aSmokeCarryIsRefusedWhenTheProgramModuleJsonOrBoundsMovedOrAStepIsMissing() throws Exception {
		String d = "fixture_plot/1";
		// the program changed since the full measurement
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("carried").addProperty("programSha", "f".repeat(64))), "another program");
		// module.json changed in more than its pins (the full measurement had another envelope)
		var f = smoke(files(), d);
		JsonObject mod = JsonParser.parseString(f.get(d + "/module.json")).getAsJsonObject();
		mod.getAsJsonObject("envelope").getAsJsonObject("branches").getAsJsonArray("side").remove(0);
		mod.add("pins", JsonParser.parseString("{\"kit\":\"0.56.0\",\"checker\":\"0.56.0\"}"));
		String other = ModuleCatalog.moduleSha(mod);
		assertRefused(edit(f, d, m -> m.getAsJsonObject("carried").addProperty("moduleSha", other)), "more than its pins");
		// the bounds offered are not the carried ones
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonArray("bounds").get(0).getAsJsonObject().getAsJsonObject("bill").addProperty("minecraft:dirt", 2)),
			"not the carried measurement's");
		// a failed or smoke carried certificate, or one without its measurement
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("carried").addProperty("result", "fail")), "not a passing full");
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("carried").addProperty("basis", "smoke")), "not a passing full");
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("carried").getAsJsonObject("checks").remove("measure")), "checks.measure");
		// the smoke checks not recorded, the carry with no from or a from the carried one doesn't name, another Minecraft
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("checks").remove("smoke")), "smoke checks");
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("checks").remove("ingame")), "smoke checks");
		// a smoke step missing, failed in part, on another program or version, or a field round short of runs
		assertRefused(edit(smoke(files(), d), d, m -> smokeOf(m).remove("bounds")), "smoke step ? (bounds)");
		// a step that is another step's evidence, or a field round that names no step
		assertRefused(edit(smoke(files(), d), d, m -> smokeOf(m).add("bounds", smokeOf(m).get("survival").deepCopy())), "smoke step survival (bounds)");
		assertRefused(edit(smoke(files(), d), d, m -> smokeOf(m).getAsJsonArray("fields").set(0, smokeOf(m).get("survival").deepCopy())), "field round survival");
		assertRefused(edit(smoke(files(), d), d, m -> smokeOf(m).getAsJsonArray("fields").get(0).getAsJsonObject().remove("step")), "field round ?");
		// the fresh offline or ingame null, failed, or on another version
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("checks").add("offline", com.google.gson.JsonNull.INSTANCE)), "offline run");
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("checks").getAsJsonObject("offline").getAsJsonArray("branches").get(0).getAsJsonObject().addProperty("ok", false)), "offline run");
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("checks").getAsJsonObject("offline").addProperty("architect", "0.61.0")), "offline run");
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("checks").add("ingame", com.google.gson.JsonNull.INSTANCE)), "ingame run");
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("checks").getAsJsonObject("ingame").getAsJsonObject("install").addProperty("ok", false)), "ingame run");
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("checks").getAsJsonObject("ingame").add("variants", new com.google.gson.JsonArray())), "ingame run");
		assertRefused(edit(smoke(files(), d), d, m -> smokeOf(m).getAsJsonObject("survival").addProperty("passed", 62)), "smoke step survival (survival)");
		assertRefused(edit(smoke(files(), d), d, m -> smokeOf(m).getAsJsonObject("bounds").addProperty("of", 0)), "smoke step bounds (bounds)");
		assertRefused(edit(smoke(files(), d), d, m -> smokeOf(m).getAsJsonObject("survival").addProperty("architect", "0.61.0")), "smoke step survival (survival)");
		assertRefused(edit(smoke(files(), d), d, m -> smokeOf(m).getAsJsonObject("bounds").addProperty("programSha", "e".repeat(64))), "smoke step bounds (bounds)");
		assertRefused(edit(smoke(files(), d), d, m -> smokeOf(m).getAsJsonArray("fields").get(0).getAsJsonObject().addProperty("runs", 1)), "field round field-wheat");
		assertRefused(edit(smoke(files(), d), d, m -> smokeOf(m).add("fields", new com.google.gson.JsonArray())), "without a field round");
		// the certificate names another Architect version beside the one it ran on, or the fully measured one
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonArray("architect").add("9.0.0")), "exactly one Architect version");
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonArray("architect").add("0.61.0")), "exactly one Architect version");
		assertRefused(edit(smoke(files(), d), d, m -> m.remove("from")), "names no fully measured");
		assertRefused(edit(smoke(files(), d), d, m -> m.addProperty("from", "0.60.0")), "does not name Architect 0.60.0");
		assertRefused(edit(smoke(files(), d), d, m -> m.getAsJsonObject("carried").addProperty("minecraft", "26.2")), "Minecraft 26.2");
		assertRefused(edit(smoke(files(), d), d, m -> m.remove("carried")), "without its carried");
		// an unknown basis; a full certificate naming a carried one
		assertRefused(edit(certify(files(), d), d, m -> m.addProperty("basis", "guess")), "basis is 'guess'");
		assertRefused(edit(certify(files(), d), d, m -> m.addProperty("from", "0.61.0")), "names a carried one");
	}

	static void assertRefused(Map<String, String> f, String why) {
		Entry e = fixtureOf(f);
		assertTrue(e.valid());
		assertTrue(e.uncertified().stream().anyMatch(p -> p.contains(why)), () -> why + " in " + e.uncertified());
		assertEquals(Why.NOT_MEASURED, ModuleGate.offer(e, HERE).why());
	}

	@Test
	void theOldFullOnlyFormStillReads() throws Exception {
		// a certificate with no basis (every one before speed rule 4) is a full one, and offers as before
		Entry e = fixture();
		assertFalse(JsonParser.parseString(files().get("fixture_plot/1/measured.json")).getAsJsonObject().has("basis"));
		assertEquals(ModuleCatalog.BASIS_FULL, e.measured().basis());
		assertFalse(e.measured().smoke());
		assertNull(e.measured().from());
		assertTrue(ModuleGate.offer(e, HERE).ok());
		// and an explicit basis full the same
		Entry full = fixtureOf(edit(certify(files(), "fixture_plot/1"), "fixture_plot/1", m -> m.addProperty("basis", "full")));
		assertTrue(full.certified(), () -> String.join("; ", full.uncertified()));
	}

	@Test
	void theFixtureModuleLoadsWithItsProgramSchemaEnvelopeAndMeasurement() throws Exception {
		Entry e = fixture();
		assertTrue(e.valid(), () -> String.join("; ", e.problems()));
		assertTrue(e.certified(), () -> String.join("; ", e.uncertified()));
		assertTrue(committed().valid(), () -> String.join("; ", committed().problems()));
		assertTrue(committed().certified(), () -> "the committed measured.json certifies the committed files (tools/module-catalog.mjs record): "
			+ String.join("; ", committed().uncertified()));
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
	void theLiveCropFarmIsCertifiedAndOfferedOnItsMeasuredVersionsOnly() {
		// crop_farm@1 (slice M2): measured.json certifies these exact files on Minecraft 26.3 and Architect 0.68.0, by a smoke check carrying
		// the full measurement on 0.67.0 (speed rule 4; docs/MEASURE-M2.md)
		ModuleCatalog live = ModuleCatalog.live(getClass().getClassLoader());
		var env = new ModuleGate.Env("26.3", "0.68.0", java.util.Set.of(ModuleGate.FEATURE), false, false);
		var v = ModuleGate.offer(live, "crop_farm", env);
		assertTrue(v.ok(), v.message());
		assertEquals(List.of(), v.entry().uncertified());
		assertTrue(v.entry().measured().smoke());
		assertEquals("0.67.0", v.entry().measured().from());
		for (String other : List.of("0.67.0", "0.65.0"))
			assertFalse(ModuleGate.offer(live, "crop_farm", new ModuleGate.Env("26.3", other, java.util.Set.of(ModuleGate.FEATURE), false, false)).ok(), other);
		var p = new com.google.gson.JsonObject();
		p.addProperty("width", 27);
		p.addProperty("depth", 9);
		p.addProperty("crop", "potatoes");
		assertNotNull(ModuleGate.params(v.entry(), p).values());
		p.addProperty("width", 10);
		assertNull(ModuleGate.params(v.entry(), p).values(), "a field side outside the measured 9, 18 and 27 is refused");
		// M3's members: a bound per measured Style (rustic, fortress, desert) with its role parts, and a quoted rate, for every one of the 36 branches
		var raw = v.entry().measured().raw();
		assertEquals(36 * 3, raw.getAsJsonArray("bounds").size());
		for (var b : raw.getAsJsonArray("bounds")) assertNotNull(StyleBound.parts(b.getAsJsonObject()), b.toString());
		assertEquals(36, raw.getAsJsonArray("rates").size());
	}

	@Test
	void aTamperedOrUnmatchedEntryIsKeptWithItsProblemsAndNeverOffered() throws Exception {
		var f = certify(files(), "fixture_plot/1");
		f.put("fixture_plot/1/program.mjs", f.get("fixture_plot/1/program.mjs").replace("RIPE = { wheat: 7", "RIPE = { wheat: 6"));
		Entry e = of(f).get("fixture_plot", 1).orElseThrow();
		assertFalse(e.valid());
		assertTrue(e.problems().stream().anyMatch(p -> p.contains("not the program module.json pins")));
		assertTrue(e.uncertified().stream().anyMatch(p -> p.contains("measured another program")));
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
		assertTrue(e.valid(), "a well-formed entry, only not certified");
		assertFalse(e.certified());
		assertNull(e.measured());
		assertEquals(Why.NOT_MEASURED, ModuleGate.offer(e, HERE).why());
		// a dev measurement run may install it, to measure it; a player's game never
		assertTrue(ModuleGate.offer(e, new Env("26.3", "0.62.0", Set.of("givenPrograms"), true, true)).ok());
	}

	@Test
	void aFailedIncompleteOrStaleCertificateOffersNothing() throws Exception {
		// failed
		var f = certify(files(), "fixture_plot/1");
		f.put("fixture_plot/1/measured.json", with(f.get("fixture_plot/1/measured.json"), "result", "\"fail\""));
		assertEquals(Why.NOT_MEASURED, ModuleGate.offer(of(f).get("fixture_plot", 1).orElseThrow(), HERE).why());
		// no verdict at all (bars, but no result)
		f = certify(files(), "fixture_plot/1");
		var m = JsonParser.parseString(f.get("fixture_plot/1/measured.json")).getAsJsonObject();
		m.remove("result");
		f.put("fixture_plot/1/measured.json", m.toString());
		assertEquals(Why.NOT_MEASURED, ModuleGate.offer(of(f).get("fixture_plot", 1).orElseThrow(), HERE).why());
		// stale: an unmeasured branch added to the envelope after the measurement
		f = certify(files(), "fixture_plot/1");
		var mod = JsonParser.parseString(f.get("fixture_plot/1/module.json")).getAsJsonObject();
		mod.getAsJsonObject("envelope").getAsJsonObject("branches").getAsJsonArray("side").add(7);
		f.put("fixture_plot/1/module.json", mod.toString());
		Entry stale = of(f).get("fixture_plot", 1).orElseThrow();
		assertTrue(stale.valid());
		assertTrue(stale.uncertified().stream().anyMatch(p -> p.contains("certifies another")));
		assertEquals(Why.NOT_MEASURED, ModuleGate.offer(stale, HERE).why());
		// stale: the palette rules loosened after the measurement
		f = certify(files(), "fixture_plot/1");
		mod = JsonParser.parseString(f.get("fixture_plot/1/module.json")).getAsJsonObject();
		mod.getAsJsonObject("palette").getAsJsonObject("lamp").addProperty("minLight", 9);
		f.put("fixture_plot/1/module.json", mod.toString());
		assertEquals(Why.NOT_MEASURED, ModuleGate.offer(of(f).get("fixture_plot", 1).orElseThrow(), HERE).why());
		// measured with another checker than pinned
		f = certify(files(), "fixture_plot/1");
		f.put("fixture_plot/1/measured.json", with(f.get("fixture_plot/1/measured.json"), "checker", "\"0.56.0\""));
		assertEquals(Why.NOT_MEASURED, ModuleGate.offer(of(f).get("fixture_plot", 1).orElseThrow(), HERE).why());
		// the title is not certified content: changing it keeps the certificate
		f = certify(files(), "fixture_plot/1");
		f.put("fixture_plot/1/module.json", with(f.get("fixture_plot/1/module.json"), "title", "\"Another title\""));
		assertTrue(ModuleGate.offer(of(f).get("fixture_plot", 1).orElseThrow(), HERE).ok());
	}

	@Test
	void offeredOnlyOnTheVersionsItWasMeasuredOnWithGivenProgramsAndFixturesOnlyInDev() throws Exception {
		Entry e = fixture();
		assertTrue(ModuleGate.offer(e, HERE).ok());
		assertEquals(Why.MINECRAFT, ModuleGate.offer(e, new Env("26.4", "0.62.0", Set.of("givenPrograms"), true, false)).why());
		assertEquals(Why.ARCHITECT, ModuleGate.offer(e, new Env("26.3", "0.63.0", Set.of("givenPrograms"), true, false)).why());
		assertEquals(Why.ARCHITECT, ModuleGate.offer(e, new Env("26.3", "0.61.0", Set.of("givenPrograms"), true, false)).why());
		assertEquals(Why.FEATURE, ModuleGate.offer(e, new Env("26.3", "0.62.0", Set.of(), true, false)).why());
		assertEquals(Why.FEATURE, ModuleGate.offer(e, new Env("26.3", "0.62.0", Set.of(), true, true)).why(), "measuring needs given programs too");
		assertEquals(Why.FIXTURE, ModuleGate.offer(e, new Env("26.3", "0.62.0", Set.of("givenPrograms"))).why());
		assertEquals(Why.UNKNOWN, ModuleGate.offer(ModuleCatalog.dir(FIXTURES, true), "crop_farm", HERE).why());
	}

	@Test
	void theNewestVersionIsJudgedAndNeverFallsBackToAnOlderOne() throws Exception {
		var f = certify(files(), "fixture_plot/1");
		// a version 2 that was measured on another Minecraft: version 1 is measured here, but is not offered in its place
		f.put("catalog.json", "{\"format\":1,\"modules\":[{\"id\":\"fixture_plot\",\"version\":1},{\"id\":\"fixture_plot\",\"version\":2}]}");
		f.put("fixture_plot/2/program.mjs", f.get("fixture_plot/1/program.mjs"));
		f.put("fixture_plot/2/module.json", with(f.get("fixture_plot/1/module.json"), "version", "2"));
		f.put("fixture_plot/2/measured.json", with(f.get("fixture_plot/1/measured.json"), "version", "2"));
		certify(f, "fixture_plot/2");
		f.put("fixture_plot/2/measured.json", with(f.get("fixture_plot/2/measured.json"), "minecraft", "\"26.2\""));
		ModuleCatalog c = of(f);
		assertTrue(ModuleGate.offer(c.get("fixture_plot", 1).orElseThrow(), HERE).ok());
		var v = ModuleGate.offer(c, "fixture_plot", HERE);
		assertFalse(v.ok());
		assertEquals(Why.MINECRAFT, v.why());
		assertEquals(2, v.entry().version());
	}

	@Test
	void aNewestRowTheLiveLoaderDropsBlocksTheWholeModule() throws Exception {
		// version 1 an ordinary certified module, version 2 a fixture (or a duplicate row): the live loader drops the row, and must not then
		// offer version 1 in its place
		var f = certify(files(), "fixture_plot/1");
		f.put("fixture_plot/1/module.json", with(f.get("fixture_plot/1/module.json"), "fixture", "false"));
		f.put("fixture_plot/1/measured.json", with(f.get("fixture_plot/1/measured.json"), "fixture", "false"));
		certify(f, "fixture_plot/1");
		f.put("catalog.json", "{\"format\":1,\"modules\":[{\"id\":\"fixture_plot\",\"version\":1},{\"id\":\"fixture_plot\",\"version\":2}]}");
		f.put("fixture_plot/2/program.mjs", f.get("fixture_plot/1/program.mjs"));
		f.put("fixture_plot/2/module.json", with(with(f.get("fixture_plot/1/module.json"), "version", "2"), "fixture", "true"));
		f.put("fixture_plot/2/measured.json", with(with(f.get("fixture_plot/1/measured.json"), "version", "2"), "fixture", "true"));
		certify(f, "fixture_plot/2");
		var files = f;
		ModuleCatalog live = ModuleCatalog.load(rel -> Optional.ofNullable(files.get(rel)), false);
		Env player = new Env("26.3", "0.62.0", Set.of("givenPrograms"));
		assertTrue(ModuleGate.offer(live.get("fixture_plot", 1).orElseThrow(), player).ok(), "version 1 alone is offerable");
		assertTrue(live.blocked("fixture_plot"));
		assertEquals(Why.INVALID, ModuleGate.offer(live, "fixture_plot", player).why());
		// a bad version row blocks it the same way
		f.put("catalog.json", "{\"format\":1,\"modules\":[{\"id\":\"fixture_plot\",\"version\":1},{\"id\":\"fixture_plot\",\"version\":0}]}");
		assertEquals(Why.INVALID, ModuleGate.offer(ModuleCatalog.load(rel -> Optional.ofNullable(files.get(rel)), false), "fixture_plot", player).why());
		// a duplicate row blocks it the same way
		f.put("catalog.json", "{\"format\":1,\"modules\":[{\"id\":\"fixture_plot\",\"version\":1},{\"id\":\"fixture_plot\",\"version\":1}]}");
		assertEquals(Why.INVALID, ModuleGate.offer(ModuleCatalog.load(rel -> Optional.ofNullable(files.get(rel)), false), "fixture_plot", player).why());
	}

	@Test
	void paramsStayInsideTheEnvelopeInCanonicalForm() throws Exception {
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
	void keysAreTheCatalogsAndTheLotsShapes() throws Exception {
		Entry e = fixture();
		assertEquals("steward.catalog.fixture_plot.v1." + e.programSha().substring(0, 12), ModuleInstalls.catalogKey(e));
		assertTrue(ModuleInstalls.catalogKey(e).matches(ModuleInstalls.KEY_RE), "Architect's opKey alphabet");
		assertTrue(ModuleInstalls.lotKey("b1700000000000", "lot_3", 2).matches(ModuleInstalls.KEY_RE));
		assertEquals("steward.b17.module.lot_3", ModuleInstalls.lotKey("b17", "lot_3", 1));
		assertEquals("steward.b17.module.lot_3.2", ModuleInstalls.lotKey("b17", "lot_3", 2));
	}
}
