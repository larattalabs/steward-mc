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

	@Test
	void listsAreSaidInWords() {
		// (CA2) the street round is Architect's now (CP, inside compose); its leftOut is said as "smithy 1 and chapel 1"
		assertEquals("smithy 1 and chapel 1", Composer.words(java.util.List.of("smithy 1", "chapel 1")));
		assertEquals("a, b and c", Composer.words(java.util.List.of("a", "b", "c")));
	}

	@Test
	void aComposeWithNoSummaryInClaudesWordsGetsThePlansOwnPlainOne() {
		var plan = JsonParser.parseString("{\"zones\":[{\"id\":\"town\",\"program\":{\"lots\":[{\"id\":\"cabin_1\",\"type\":\"cabin\",\"label\":\"hidden dwelling\"},"
			+ "{\"id\":\"cabin_2\",\"type\":\"cabin\",\"label\":\"hidden dwelling\"},{\"id\":\"tavern_1\",\"type\":\"tavern\"},{\"id\":\"house_1\",\"type\":\"house\"},"
			+ "{\"id\":\"barn_1\",\"type\":\"barn\"}]}}]}").getAsJsonObject();
		String s = Composer.summaryOf(plan);
		assertTrue(s.startsWith("A settlement of 5 buildings. Among them: 2 hidden dwelling"), s);
		assertTrue(s.endsWith("and 1 more."), s);
		assertEquals("", Composer.summaryOf(JsonParser.parseString("{\"zones\":[]}").getAsJsonObject()));
	}

	@Test
	void aWaitingPartsWordsAreItsOwnNotes() {
		var plan = JsonParser.parseString("{\"connectors\":[{\"id\":\"rail_1\",\"kind\":\"rail\",\"notes\":\" A railway to the mine, for when Architect can lay track. \"}],"
			+ "\"forms\":[{\"id\":\"pond\",\"kind\":\"pond\"}]}").getAsJsonObject();
		assertEquals(java.util.Map.of("rail_1", "A railway to the mine, for when Architect can lay track."), Composer.notes(plan));
	}
}
