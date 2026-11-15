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

	@Test
	void aTargetHeldByItsApprovedBoundIsToldSoNotToFillTheStockpile() {
		// (QD, S-M5-9) a bill_cap wait: more stock would not help, so it is never "they go in the stockpile"
		var capped = new dev.larattalabs.architect.api.SiteWait("s1", true, 10, java.util.List.of(), 0, dev.larattalabs.architect.api.SiteWait.BILL_CAP, java.util.List.of());
		var starved = new dev.larattalabs.architect.api.SiteWait("s1", true, 10, java.util.List.of(), 0);
		assertTrue(MaterialRequests.capped(capped));
		assertFalse(MaterialRequests.capped(starved));
		assertFalse(MaterialRequests.capped(new dev.larattalabs.architect.api.SiteWait("s1", true, 10, java.util.List.of(), 0,
			dev.larattalabs.architect.api.SiteWait.FILL_BLOCKED, java.util.List.of())));
		String said = MaterialRequests.overCapWords("farm", "set_1", java.util.List.of(java.util.Map.entry("minecraft:dirt", 3)), 0);
		assertEquals("The farm has stopped: going on would take 3 dirt more than you approved, and I take nothing past that. Undo it (/steward undo set_1)"
			+ " to get back what it used; ask for it again and I'll put the new bill to you.", said);
		assertFalse(said.contains("stockpile."));
	}
}
