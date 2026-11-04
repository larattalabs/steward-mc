package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ComposerTest {
	@Test
	void onlyAPlanWithATunnelLoadsItsCorridor() {
		assertTrue(Composer.hasTunnel(JsonParser.parseString("{\"connectors\":[{\"id\":\"t\",\"kind\":\"tunnel\"}]}").getAsJsonObject()));
		assertFalse(Composer.hasTunnel(JsonParser.parseString("{\"connectors\":[{\"id\":\"w\",\"kind\":\"wall\"},{\"id\":\"a\",\"kind\":\"auto\"}]}").getAsJsonObject()));
		assertFalse(Composer.hasTunnel(JsonParser.parseString("{\"zones\":[]}").getAsJsonObject()));
	}

	@Test
	void theCatalogueSaysWhichPartsReadTheRock() {
		var flagged = java.util.Set.of("connector.tunnel", "form.roots", "form.stalactites");
		assertTrue(Composer.needsVolume(JsonParser.parseString("{\"forms\":[{\"id\":\"r\",\"kind\":\"roots\"}]}").getAsJsonObject(), flagged), "roots read the rock");
		assertTrue(Composer.needsVolume(JsonParser.parseString("{\"connectors\":[{\"id\":\"t\",\"kind\":\"tunnel\"}]}").getAsJsonObject(), flagged));
		assertFalse(Composer.needsVolume(JsonParser.parseString("{\"forms\":[{\"id\":\"o\",\"kind\":\"giant_tree\"},{\"id\":\"p\",\"kind\":\"pond\"}]}").getAsJsonObject(), flagged),
			"a giant tree and a pond read the surface only");
		// an Architect before F3 flags nothing: the tunnel rule
		assertTrue(Composer.needsVolume(JsonParser.parseString("{\"connectors\":[{\"id\":\"t\",\"kind\":\"tunnel\"}]}").getAsJsonObject(), java.util.Set.of()));
		assertFalse(Composer.needsVolume(JsonParser.parseString("{\"forms\":[{\"id\":\"r\",\"kind\":\"roots\"}]}").getAsJsonObject(), java.util.Set.of()));
	}

	@Test
	void aSurvivalBillInWords() {
		var cost = JsonParser.parseString("{\"bom\":{\"minecraft:stone_bricks\":64,\"minecraft:oak_planks\":40,\"minecraft:lantern\":6,\"minecraft:glass\":12,\"minecraft:dirt\":0}}").getAsJsonObject();
		assertEquals("64 stone bricks, 40 oak planks, 12 glass and 1 more kind (122 items)", Composer.billLine(cost));
		assertEquals("3 lantern and 1 glass (4 items)", Composer.billLine(JsonParser.parseString("{\"bom\":{\"minecraft:lantern\":3,\"minecraft:glass\":1}}").getAsJsonObject()));
		assertNull(Composer.billLine(JsonParser.parseString("{\"bom\":{}}").getAsJsonObject()));
		assertNull(Composer.billLine(JsonParser.parseString("{\"bom\":null,\"bomNote\":\"regions are INSTANT only\"}").getAsJsonObject()), "a creative check has no bill");
		assertNull(Composer.billLine(null));
	}

	private static dev.larattalabs.architect.api.PlanCheck checkOf(String... parts) {
		var list = java.util.Arrays.stream(parts).map(x -> JsonParser.parseString(x).getAsJsonObject()).toList();
		return new dev.larattalabs.architect.api.PlanCheck("c", "p", "s", "k", "plan/0", dev.larattalabs.architect.api.PlanCheck.Overall.REQUIRED_BLOCKED, list, null, null,
			null, null, java.util.List.of());
	}

	@Test
	void aFreeLayoutsStreetFragmentIsAppliedAsItIsWithTheLotsItLeavesOut() {
		var plan = JsonParser.parseString("{\"schemaVersion\":\"plan/0\",\"zones\":[{\"id\":\"town\",\"required\":true,\"program\":{\"layout\":\"grid\",\"lots\":"
			+ "[{\"id\":\"house_1\"},{\"id\":\"smithy_1\"},{\"id\":\"chapel_1\"}]}},{\"id\":\"farms\",\"program\":{\"layout\":\"ring\"}}],"
			+ "\"connectors\":[{\"id\":\"wall\",\"kind\":\"wall\"}]}").getAsJsonObject();
		var c = checkOf(
			"{\"part\":\"town\",\"class\":\"refused\",\"reasons\":[{\"code\":\"NO_ROOM\",\"message\":\"no grid layout fits the zone here; layout 'street' places 1 of 3 (drops smithy_1, chapel_1)\","
				+ "\"evidence\":{\"layout\":\"grid\",\"alt\":\"street\",\"altLots\":1,\"altDropped\":[\"smithy_1\",\"chapel_1\"]}}],"
				+ "\"wouldFit\":[{\"part\":\"town\",\"fragment\":{\"program\":{\"layout\":\"street\",\"lots\":[{\"id\":\"house_1\"}]}}}]}",
			// a refusal without the street hint (an orientation's wouldFit, say) is left to Claude's repair
			"{\"part\":\"farms\",\"class\":\"refused\",\"reasons\":[{\"code\":\"NO_ROOM\",\"evidence\":{\"orientation\":\"ew\"}}],"
				+ "\"wouldFit\":[{\"part\":\"farms\",\"fragment\":{\"program\":{\"orientation\":\"ns\"}}}]}",
			"{\"part\":\"wall\",\"class\":\"fits\"}");
		var fix = Composer.streetFixes(plan, c);
		assertNotNull(fix);
		assertEquals(java.util.List.of("town"), fix.parts());
		assertEquals(java.util.List.of("smithy 1", "chapel 1"), fix.leftOut());
		var town = fix.plan().getAsJsonArray("zones").get(0).getAsJsonObject();
		assertEquals("street", town.getAsJsonObject("program").get("layout").getAsString());
		assertEquals(1, town.getAsJsonObject("program").getAsJsonArray("lots").size());
		assertTrue(town.get("required").getAsBoolean(), "the zone's other fields are kept");
		assertEquals("ring", fix.plan().getAsJsonArray("zones").get(1).getAsJsonObject().getAsJsonObject("program").get("layout").getAsString());
		assertEquals("grid", plan.getAsJsonArray("zones").get(0).getAsJsonObject().getAsJsonObject("program").get("layout").getAsString(), "the plan itself is not changed");
		// nothing to apply: no part with the street hint
		assertNull(Composer.streetFixes(plan, checkOf("{\"part\":\"wall\",\"class\":\"fits\"}")));
		assertEquals("smithy 1 and chapel 1", Composer.words(fix.leftOut()));
		assertEquals("a, b and c", Composer.words(java.util.List.of("a", "b", "c")));
	}
}
