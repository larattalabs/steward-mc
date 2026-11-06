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
		// (Architect GM-c) a drawbridge: the bridge's crossing with deck trapdoor and movable, said as a drawbridge, not a beam bridge
		var draw = JsonParser.parseString("{\"siteplan\":{\"edges\":[{\"id\":\"d\",\"crossing\":{\"type\":\"bridge\",\"form\":\"beam\",\"over\":\"water\","
			+ "\"span\":11,\"deck\":\"trapdoor\",\"movable\":true}}]}}").getAsJsonObject();
		assertEquals(List.of("a drawbridge over water, 11 long"), Composer.crossings(draw));
		assertTrue(Composer.crossings(null).isEmpty());
		assertTrue(Composer.crossings(JsonParser.parseString("{}").getAsJsonObject()).isEmpty());
	}
}
