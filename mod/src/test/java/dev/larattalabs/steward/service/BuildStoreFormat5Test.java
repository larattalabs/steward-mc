package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.module.Canon;
import dev.larattalabs.steward.module.ModuleBinding;
import dev.larattalabs.steward.module.ModuleInstalls;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** BuildStore format 5 (docs/MODULES.md 4.2 step 4, slice M1): module lots' bindings saved with their build, exactly as asked. */
class BuildStoreFormat5Test {
	static final String SHA = "ab".repeat(32);

	static Map<String, ModuleBinding> bindings() {
		Map<String, ModuleBinding> m = new LinkedHashMap<>();
		var geo = JsonParser.parseString("{\"box\":[0,64,0,10,66,11],\"irrigation\":[]}");
		m.put("lot_3", new ModuleBinding("fixture_plot", 1, SHA, JsonParser.parseString("{\"side\":9,\"crop\":\"wheat\"}").getAsJsonObject(), "fixture_plot_kv_2",
			"fortress", 0, Canon.sha(geo), ModuleInstalls.lotKey("b1700000000000", "lot_3", 2), "steward_mc:settlement/set_4"));
		m.put("lot_5", new ModuleBinding("fixture_plot", 1, SHA, JsonParser.parseString("{}").getAsJsonObject(), null, null, 0, Canon.sha(geo),
			ModuleInstalls.lotKey("b1700000000000", "lot_5", 1), null));
		return m;
	}

	@Test
	void bindingsRoundTripWithTheExactKeyAndOwnerAndAnUnansweredEntry(@TempDir Path dir) throws Exception {
		BuildStore st = new BuildStore();
		BuildStore.Saved b = BuildStoreTestAccess.saved().withModuleBindings(bindings());
		st.put(b);
		Path f = dir.resolve("steward-builds.json");
		st.save(f);
		String text = Files.readString(f);
		assertTrue(text.contains("\"format\": " + BuildStore.FORMAT));
		assertEquals(5, BuildStore.FORMAT);
		assertTrue(text.contains("steward.b1700000000000.module.lot_3.2"), "the key as sent (a re-plan's .2), never recomputed");
		BuildStore back = BuildStore.load(f);
		assertEquals(b, back.all().get(0));
		var lot3 = back.all().get(0).moduleBindings().get("lot_3");
		assertEquals("fixture_plot_kv_2", lot3.entryId());
		assertEquals("steward_mc:settlement/set_4", lot3.owner());
		assertEquals("{\"crop\":\"wheat\",\"side\":9}", lot3.params().toString());
		assertNull(back.all().get(0).moduleBindings().get("lot_5").entryId(), "saved before Architect answered: the key finds it after a restart");
		assertFalse(Files.exists(dir.resolve("steward-builds.json.v5.bak")), "a current file is not backed up");
	}

	@Test
	void anOlderStewardRefusesFormat5AndThisOneRefusesFormat6() {
		BuildStore st = new BuildStore();
		st.put(BuildStoreTestAccess.saved().withModuleBindings(bindings()));
		String now = st.toJson();
		// a format-4 Steward's rule: a format above its own is refused, the file untouched (it would drop the bindings and make a second variant)
		assertTrue(JsonParser.parseString(now).getAsJsonObject().get("format").getAsInt() > 4);
		String newer = now.replaceFirst("\"format\": 5", "\"format\": 6");
		var e = assertThrows(JsonParseException.class, () -> BuildStore.fromJson(newer));
		assertTrue(e.getMessage().contains("1..5"));
	}

	@Test
	void anIncompleteBindingRefusesTheFileNeverDropsIt() {
		BuildStore st = new BuildStore();
		st.put(BuildStoreTestAccess.saved().withModuleBindings(bindings()));
		for (String field : new String[] {"opKey", "geometrySha", "programSha", "moduleId"}) {
			var j = JsonParser.parseString(st.toJson()).getAsJsonObject();
			j.getAsJsonArray("builds").get(0).getAsJsonObject().getAsJsonObject("moduleBindings").getAsJsonObject("lot_3").remove(field);
			assertThrows(JsonParseException.class, () -> BuildStore.fromJson(j.toString()), field);
		}
		var j = JsonParser.parseString(st.toJson()).getAsJsonObject();
		j.getAsJsonArray("builds").get(0).getAsJsonObject().getAsJsonObject("moduleBindings").getAsJsonObject("lot_3").addProperty("version", 0);
		assertThrows(JsonParseException.class, () -> BuildStore.fromJson(j.toString()), "version 0");
	}

	@Test
	void aBuildWithoutBindingsReadsAndWritesNone() {
		BuildStore st = new BuildStore();
		st.put(BuildStoreTestAccess.saved());
		var j = JsonParser.parseString(st.toJson()).getAsJsonObject();
		assertEquals(0, j.getAsJsonArray("builds").get(0).getAsJsonObject().getAsJsonObject("moduleBindings").size());
		j.getAsJsonArray("builds").get(0).getAsJsonObject().remove("moduleBindings");
		assertEquals(Map.of(), BuildStore.fromJson(j.toString()).all().get(0).moduleBindings());
	}
}
