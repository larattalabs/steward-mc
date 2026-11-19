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
		String said = MaterialRequests.overCapWords("farm", "set_1", java.util.List.of(java.util.Map.entry("minecraft:dirt", 3)), 0, MaterialRequests.TakeUp.UNDO);
		assertEquals("The farm has stopped: finishing it would take 3 dirt more than you approved, so I take no more for it; filling the stockpile"
			+ " won't help. /steward undo set_1 takes it up and gives back what it drew; then ask for it again and I'll put the new bill to you.", said);
		// said once per wait and reason: a shortage that turns into a bill_cap wait keeps its waitingSince and is said again
		assertNotEquals(MaterialRequests.saidKey(starved), MaterialRequests.saidKey(capped));
		var more = new dev.larattalabs.architect.api.SiteWait("s1", true, 10, java.util.List.of(), 2, dev.larattalabs.architect.api.SiteWait.BILL_CAP, java.util.List.of());
		assertNotEquals(MaterialRequests.saidKey(capped), MaterialRequests.saidKey(more), "a new over-count is said again");
		assertEquals(MaterialRequests.saidKey(starved), MaterialRequests.saidKey(new dev.larattalabs.architect.api.SiteWait("s1", true, 10, java.util.List.of(), 5)));
		assertFalse(said.contains("They go in the stockpile"));
	}

	@Test
	void aWaitWhileTheStockpileIsOverCapacitySaysOnlyWhatTheBuildsNeedGoesIn() {
		// (slice OVF-S2) a waiting build is never paused, so what it misses still goes in over capacity (Architect 0.77.0 OVF); nothing else does
		assertEquals("The farm waits for materials: 3 dirt. They go in the stockpile.", MaterialRequests.waitWords("farm", "3 dirt", room(0, 5, 1), "set_1"));
		assertEquals("The farm waits for materials: 3 dirt. They go in the stockpile, though it is over capacity by 2 stacks: it takes only what your running"
			+ " builds still need, so bring just those.", MaterialRequests.waitWords("farm", "3 dirt", room(2, 0, 1), "set_1"));
		assertTrue(MaterialRequests.waitWords("farm", "3 dirt", room(1, 0, 1), "set_1").contains("over capacity by 1 stack:"));
		assertFalse(MaterialRequests.waitWords("farm", "3 dirt", room(4, 0, 9), "set_1").contains("take out"), "never 'take items out first': its own items go in");
		assertEquals("The farm waits for materials: 3 dirt. They go in the stockpile.", MaterialRequests.waitWords("farm", "3 dirt", MaterialRequests.Pile.UNREAD, "set_1"));
	}

	private static MaterialRequests.Pile room(int over, int free, int need) {
		return new MaterialRequests.Pile(over, free, need);
	}

	@Test
	void aPartLabelWithItsOwnArticleIsNamedWithItOnce() {
		// (WORDS-079) "The the great oak waits for materials": a plan label already has its article
		assertEquals("The great oak", MaterialRequests.theName("the great oak"));
		assertEquals("The great oak", MaterialRequests.theName("The great oak"));
		assertEquals("The great oak", MaterialRequests.theName("great oak"));
		assertEquals("The theatre", MaterialRequests.theName("theatre"), "only the article, never a word starting so");
		assertEquals("The great oak waits for materials: 3 dirt. They go in the stockpile.", MaterialRequests.waitWords("the great oak", "3 dirt", room(0, 5, 1), "s"));
		assertTrue(MaterialRequests.longWaitWords("the storage hall", "3 dirt", room(0, 5, 1), "s").startsWith("The storage hall has waited a while for materials: 3 dirt."));
		assertEquals("The town wall is held by another change for a moment: it carries on by itself.", MaterialRequests.heldWords("the town wall"));
		assertTrue(MaterialRequests.overCapWords("the great oak", "s", java.util.List.of(java.util.Map.entry("minecraft:dirt", 3)), 0, MaterialRequests.TakeUp.UNDO)
			.startsWith("The great oak has stopped:"));
		assertTrue(MaterialRequests.lotOverCapWords("the working crop farm", "s", java.util.List.of(java.util.Map.entry("minecraft:dirt", 3)), 0, MaterialRequests.TakeUp.UNDO, false)
			.startsWith("The working crop farm has stopped: finishing it would take 3 dirt more than its limit,"));
	}

	@Test
	void aWaitWithTheStockpileFullSaysSoAndHowToMakeRoom() {
		// (WORDS-079) exactly full (not over): within capacity Architect takes a delivery only into the room it has, so the plain line would mislead
		assertEquals("The farm waits for materials: 3 dirt, but the stockpile is full: take out what no build needs, or add a crate (/steward stockpile set_1 add).",
			MaterialRequests.waitWords("farm", "3 dirt", room(0, 0, 1), "set_1"));
		// room left, but too little for what the build misses (a run needs its whole bill in stock)
		assertEquals("The farm waits for materials: 3 dirt, but the stockpile is too full to take them: take out what no build needs, or add a crate"
			+ " (/steward stockpile set_1 add).", MaterialRequests.waitWords("farm", "3 dirt", room(0, 2, 3), "set_1"));
		// full, but what it misses fits in its items' partly filled stacks: they still go in
		assertEquals("The farm waits for materials: 3 dirt. They go in the stockpile.", MaterialRequests.waitWords("farm", "3 dirt", room(0, 0, 0), "set_1"));
		// over capacity keeps its own words (OVF-S2)
		assertTrue(MaterialRequests.waitWords("farm", "3 dirt", room(1, 0, 4), "set_1").contains("over capacity by 1 stack:"));
		// the long wait: the same reading, never "fill the stockpile" when it is full
		assertEquals("The farm has waited a while for materials: 3 dirt, but the stockpile is full: take out what no build needs, or add a crate"
			+ " (/steward stockpile set_1 add).", MaterialRequests.longWaitWords("farm", "3 dirt", room(0, 0, 1), "set_1"));
		assertEquals("The farm has waited a while for materials: 3 dirt. Fill the stockpile and I can carry on.", MaterialRequests.longWaitWords("farm", "3 dirt", room(0, 9, 1), "set_1"));
		assertTrue(MaterialRequests.longWaitWords("farm", "3 dirt", room(2, 0, 1), "set_1").contains("over capacity by 2 stacks"));
		// the stacks counted as Architect counts usedStacks: a partly filled stack is filled first
		assertEquals(0, MaterialRequests.newStacks(10, 54, 64));
		assertEquals(1, MaterialRequests.newStacks(10, 55, 64));
		assertEquals(2, MaterialRequests.newStacks(0, 65, 64));
		assertEquals(3, MaterialRequests.newStacks(0, 3, 1));
		assertEquals(0, MaterialRequests.newStacks(5, 0, 64));
	}

	@Test
	void aLongWaitThatNamesNoMaterialsIsNeverAnEmptyList() {
		// (WORDS-079) "The the storage hall has waited a while for materials: ."
		String said = MaterialRequests.longWaitWords("the storage hall", "", room(0, 0, 0), "set_1");
		assertEquals("The storage hall has waited a while, though not for any materials I can name.", said);
		assertFalse(said.contains(": ."));
	}
}
