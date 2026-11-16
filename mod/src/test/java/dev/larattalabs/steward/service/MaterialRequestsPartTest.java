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
	void anAdoptedSurvivalTownStillBuildingIsWatchedAfterARestart() {
		// (slice SST) before its first growth an adopted town's region is named only by its receipt
		assertTrue(MaterialRequests.seedsWatch(new dev.larattalabs.steward.model.Settlement.LogEntry(1L, dev.larattalabs.steward.model.Settlement.Kind.TOWN_ADOPTED, "Taken on",
			java.util.List.of("s1"), "region:rg1")));
		assertFalse(MaterialRequests.seedsWatch(new dev.larattalabs.steward.model.Settlement.LogEntry(1L, dev.larattalabs.steward.model.Settlement.Kind.TOWN_ADOPTED, "Taking on",
			java.util.List.of("s1"), "adopt:steward.b1.adopt")), "an intent names no region");
		assertFalse(MaterialRequests.seedsWatch(new dev.larattalabs.steward.model.Settlement.LogEntry(1L, dev.larattalabs.steward.model.Settlement.Kind.TOWN_RELEASED, "Let go",
			java.util.List.of("s1"), "region:rg1")));
		assertTrue(MaterialRequests.seedsWatch(new dev.larattalabs.steward.model.Settlement.LogEntry(1L, dev.larattalabs.steward.model.Settlement.Kind.PROJECT_PLACED, "Placed",
			java.util.List.of("s1"), "revision:rg1:1")));
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
		assertEquals("The farm has stopped: finishing it would take 3 dirt more than you approved, so I take no more for it; filling the stockpile"
			+ " won't help. /steward undo set_1 takes it up and gives back what it drew; then ask for it again and I'll put the new bill to you.", said);
		// said once per wait and reason: a shortage that turns into a bill_cap wait keeps its waitingSince and is said again
		assertNotEquals(MaterialRequests.saidKey(starved), MaterialRequests.saidKey(capped));
		var more = new dev.larattalabs.architect.api.SiteWait("s1", true, 10, java.util.List.of(), 2, dev.larattalabs.architect.api.SiteWait.BILL_CAP, java.util.List.of());
		assertNotEquals(MaterialRequests.saidKey(capped), MaterialRequests.saidKey(more), "a new over-count is said again");
		assertEquals(MaterialRequests.saidKey(starved), MaterialRequests.saidKey(new dev.larattalabs.architect.api.SiteWait("s1", true, 10, java.util.List.of(), 5)));
		assertFalse(said.contains("They go in the stockpile"));
	}
}
