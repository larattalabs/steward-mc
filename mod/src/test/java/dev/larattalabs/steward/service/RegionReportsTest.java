package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionReportsTest {
	@Test
	void aDrainedBodyIsSaidByNameAndASettledOneIsNot() {
		var ext = JsonParser.parseString("{\"architect_mc:fluids\":[{\"part\":\"town\",\"body\":\"farm_1_pond\",\"state\":\"drained\",\"reason\":\"it leaked\",\"at\":[12,64,-3]},"
			+ "{\"part\":\"town\",\"body\":\"well\",\"state\":\"settled\"}]}").getAsJsonObject();
		assertEquals(List.of("The water of farm 1 pond was drained: it leaked at 12, 64, -3. The rest stands."), RegionReports.drained(ext));
		assertTrue(RegionReports.drained(null).isEmpty());
		assertTrue(RegionReports.drained(JsonParser.parseString("{}").getAsJsonObject()).isEmpty());
	}
}
