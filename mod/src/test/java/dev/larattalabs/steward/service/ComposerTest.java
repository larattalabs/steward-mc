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
}
