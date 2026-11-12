package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MaterialRequestsPartTest {
	@Test
	void aRegionTargetIsNamedByItsPlanPart() {
		String plan = "{\"zones\":[{\"id\":\"town\",\"label\":\"the town\"}],\"connectors\":[{\"id\":\"town_wall\",\"kind\":\"wall\",\"label\":\"the town wall\"},"
			+ "{\"id\":\"north_road\",\"kind\":\"road\"}]}";
		assertEquals("the town wall", MaterialRequests.partName(plan, "town_wall"));
		assertEquals("north road", MaterialRequests.partName(plan, "north_road"), "no label: the id in words");
		assertEquals("street lamps", MaterialRequests.partName(plan, "__lamps__"));
		assertEquals("rg part", MaterialRequests.partName(null, "rg_part"));
		assertEquals("odd part", MaterialRequests.partName("not json", "odd_part"));
	}

	@Test
	void aTownBuildingAnAdditionFromMaterialsIsWatchedAfterARestart() {
		// (slice A2) seedBuilds reads a growth's entry as its town's region too
		assertEquals("r12", MaterialRequests.regionOfEntry("region:r12"));
		assertEquals("r12", MaterialRequests.regionOfEntry("revision:r12:3"));
		assertNull(MaterialRequests.regionOfEntry("s4"));
		assertNull(MaterialRequests.regionOfEntry(null));
		assertNull(MaterialRequests.regionOfEntry("revision:r12:x"));
	}
}
