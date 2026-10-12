package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.DistrictSite.Profile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LandTest {
	@Test
	void theLandLineSaysWhatLiesWhere() {
		Map<String, Profile> sides = new LinkedHashMap<>();
		sides.put("north", new Profile(1, 0, 0.2, 80, 4, Map.of("plains", 0.9)));
		sides.put("east", new Profile(1, 0.02, 0.7, 66, 2, Map.of("jungle", 0.75, "sparse_jungle", 0.2)));
		sides.put("south", new Profile(1, 0.3, 0, 63, 1, Map.of("desert", 0.6, "river", 0.3)));
		sides.put("west", new Profile(0.1, 0, 0, 64, 1, Map.of("plains", 1.0)));
		String line = Land.summary(sides, new Profile(1, 0, 0, 66, 1, Map.of()));
		assertTrue(line.startsWith("north: plains 90%; east: jungle 75%, sparse jungle 20%; south: desert 60%, river 30%."), line);
		assertFalse(line.contains("west"), "a side mostly not read is left out");
		assertTrue(line.contains("Most water to the south (30% of that side)."), line);
		assertTrue(line.contains("rises to the north (14 above the stone) and falls to the south"), line);
	}

	@Test
	void nothingReadNothingSaid() {
		assertNull(Land.summary(Map.of("north", new Profile(0.1, 0, 0, 64, 1, Map.of("plains", 1.0))), null));
		String flat = Land.summary(Map.of("north", new Profile(1, 0, 0, 64, 1, Map.of("plains", 1.0))), new Profile(1, 0, 0, 64, 1, Map.of()));
		assertTrue(flat.contains("Little or no water nearby.") && flat.contains("fairly level"), flat);
	}
}
