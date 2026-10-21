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
	void aSurvivalBillInWords() {
		var cost = JsonParser.parseString("{\"bom\":{\"minecraft:stone_bricks\":64,\"minecraft:oak_planks\":40,\"minecraft:lantern\":6,\"minecraft:glass\":12,\"minecraft:dirt\":0}}").getAsJsonObject();
		assertEquals("64 stone bricks, 40 oak planks, 12 glass and 1 more kind (122 items)", Composer.billLine(cost));
		assertEquals("3 lantern and 1 glass (4 items)", Composer.billLine(JsonParser.parseString("{\"bom\":{\"minecraft:lantern\":3,\"minecraft:glass\":1}}").getAsJsonObject()));
		assertNull(Composer.billLine(JsonParser.parseString("{\"bom\":{}}").getAsJsonObject()));
		assertNull(Composer.billLine(JsonParser.parseString("{\"bom\":null,\"bomNote\":\"regions are INSTANT only\"}").getAsJsonObject()), "a creative check has no bill");
		assertNull(Composer.billLine(null));
	}
}
