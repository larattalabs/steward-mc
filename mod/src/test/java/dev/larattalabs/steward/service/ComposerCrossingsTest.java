package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComposerCrossingsTest {
	@Test
	void aPlansBridgesAndTunnelsAreSaidAsArchitectResolvedThem() {
		var preview = JsonParser.parseString("{\"siteplan\":{\"edges\":[{\"id\":\"r1\",\"crossing\":{\"type\":\"bridge\",\"form\":\"arch\",\"over\":\"water\",\"span\":14,"
			+ "\"lands\":\"east_gate\"}},{\"id\":\"r2\"},{\"id\":\"r3\",\"crossing\":{\"type\":\"tunnel\",\"through\":\"rock\",\"length\":22}}]}}").getAsJsonObject();
		assertEquals(List.of("an arch bridge over water, 14 long, to east gate", "a tunnel through rock, 22 long"), Composer.crossings(preview));
		assertTrue(Composer.crossings(null).isEmpty());
		assertTrue(Composer.crossings(JsonParser.parseString("{}").getAsJsonObject()).isEmpty());
	}
}
