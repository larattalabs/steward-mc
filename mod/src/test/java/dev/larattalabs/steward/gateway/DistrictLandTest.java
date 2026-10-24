package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.Land;
import java.util.List;
import org.junit.jupiter.api.Test;

class DistrictLandTest {
	private static JsonObject ext(String land) {
		JsonObject e = new JsonObject();
		e.add(DistrictLand.KEY, JsonParser.parseString(land));
		return e;
	}

	@Test
	void theOutsideRunsAreTheDistrictsAndAMalformedRunIsSkipped() {
		var e = ext("{\"v\": 1, \"standing\": \"rg3\", \"standingRemoved\": null, \"outside\": {\"runs\": [[65, 0, 70, 0], [66, 1, 68, 1], [1, 2, 3], [5, 3, 9, 4]],"
			+ " \"rect\": [65, 0, 70, 1], \"cells\": 9}, \"inside\": {\"runs\": [[60, 0, 64, 0]]}}");
		Land l = DistrictLand.outside(e, "minecraft:overworld").orElseThrow();
		assertEquals(List.of(new Land.Run(0, 65, 70), new Land.Run(1, 66, 68)), l.runs(), "inside's runs are town land, never read");
		assertNull(DistrictLand.standingRemoved(e));
	}

	@Test
	void aRemovedTownIsReadAndALaterLandVersionStillReads() {
		var e = ext("{\"v\": 2, \"standingRemoved\": 1790000000000, \"outside\": {\"runs\": [[0, 0, 0, 0]]}, \"parts\": [{\"kind\": \"grown\"}]}");
		assertEquals(1790000000000L, DistrictLand.standingRemoved(e));
		assertTrue(DistrictLand.outside(e, "minecraft:overworld").isPresent());
		assertTrue(DistrictLand.outside(new JsonObject(), "minecraft:overworld").isEmpty());
		assertTrue(DistrictLand.outside(null, "minecraft:overworld").isEmpty());
	}
}
