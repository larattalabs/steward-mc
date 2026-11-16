package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * (pin-073 batch review, P1) A capped growth's words name {@code /steward undo} only when the operation undo would take is that growth's own: a
 * newer operation standing (a district started while the town was still building) is never taken up in its place.
 */
class CapTakeUpTest {
	private static final Claim TOWN = new Claim("minecraft:overworld", 0, 0, 64, -64, 320);
	private static final Settlement.LogEntry FARM = new Settlement.LogEntry(5L, Settlement.Kind.PROJECT_PLACED, "a crop farm", List.of("f1", "f2"), "revision:rg1:2");
	private static final Settlement.LogEntry DISTRICT = new Settlement.LogEntry(6L, Settlement.Kind.PROJECT_PLACED, "the east ward", List.of("d1"), "region:rg9");

	private static Settlement history() {
		return Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L)
			.withLog(new Settlement.LogEntry(2L, Settlement.Kind.PROJECT_PLACED, "town", List.of("t1"), "region:rg1")).withLog(FARM).withLog(DISTRICT);
	}

	/** What {@link Undo#lastProject} would take: the newest undoable entry, its region's head growth first (rid {@code head}). */
	private static java.util.Optional<Settlement.LogEntry> undoTakes(Settlement s, int head) {
		return Undo.headFirst(s, s.lastUndoable(), head);
	}

	private static String words(MaterialRequests.TakeUp how) {
		return MaterialRequests.overCapWords("crop farm", "set_1", List.of(Map.entry("minecraft:dirt", 3)), 0, how);
	}

	@Test
	void aNewerDistrictStandingIsNeverTakenUpForTheCappedFarm() {
		var s = history();
		var taken = undoTakes(s, 2);
		// undo would take the district, not the farm
		assertEquals("region:rg9", taken.get().siteGroup());
		var how = MaterialRequests.takeUp(false, taken, "rg1", 2, false);
		assertEquals(MaterialRequests.TakeUp.Way.NEWER, how.way());
		String said = words(how);
		assertFalse(said.contains("/steward undo") || said.contains("/steward cancel"), said);
		assertEquals("The crop farm has stopped: finishing it would take 3 dirt more than you approved, so I take no more for it; filling the stockpile"
			+ " won't help. An undo now would take up \"the east ward\" first, which came after it: to take this one up, undo what came after it, newest"
			+ " first, or leave it as it stands.", said);
		// the district undone: undo takes the farm's own revision, and the words name it
		var after = s.withLog(new Settlement.LogEntry(7L, Settlement.Kind.PROJECT_REMOVED, "undid", List.of("d1")));
		var own = undoTakes(after, 2);
		assertEquals("revision:rg1:2", own.get().siteGroup());
		var now = MaterialRequests.takeUp(false, own, "rg1", 2, false);
		assertEquals(MaterialRequests.TakeUp.UNDO, now);
		assertTrue(words(now).contains("/steward undo set_1 takes it up and gives back what it drew"));
		// refused for now (a build under way, a district's build unsettled): undo once it has ended
		var later = MaterialRequests.takeUp(false, own, "rg1", 2, true);
		assertEquals(MaterialRequests.TakeUp.UNDO_LATER, later);
		assertTrue(words(later).contains("Once the build under way has ended, /steward undo set_1 takes it up"));
	}

	@Test
	void aLaterGrowthOfTheSameTownIsNotTheCappedOne() {
		// the farm (rid 2) held by its cap; a plain growth (rid 3) of the same town stands above it: undo would take rid 3
		var s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L)
			.withLog(new Settlement.LogEntry(2L, Settlement.Kind.PROJECT_PLACED, "town", List.of("t1"), "region:rg1")).withLog(FARM)
			.withLog(new Settlement.LogEntry(6L, Settlement.Kind.PROJECT_PLACED, "a library", List.of("l1"), "revision:rg1:3"));
		var taken = undoTakes(s, 3);
		assertEquals("revision:rg1:3", taken.get().siteGroup());
		var how = MaterialRequests.takeUp(false, taken, "rg1", 2, false);
		assertEquals(new MaterialRequests.TakeUp(MaterialRequests.TakeUp.Way.NEWER, "a library"), how);
		assertFalse(words(how).contains("/steward undo"));
	}

	@Test
	void ownOpMatchesTheRevisionOrTheRegionsFirstRealise() {
		assertTrue(MaterialRequests.ownOp(FARM, "rg1", 2));
		assertFalse(MaterialRequests.ownOp(FARM, "rg1", 3));
		assertFalse(MaterialRequests.ownOp(FARM, "rg2", 2));
		assertFalse(MaterialRequests.ownOp(FARM, null, 2));
		// rid 0: the region's own placing entry, never a growth of it
		assertTrue(MaterialRequests.ownOp(DISTRICT, "rg9", 0));
		assertFalse(MaterialRequests.ownOp(DISTRICT, "rg9", 1));
		assertFalse(MaterialRequests.ownOp(DISTRICT, "rg1", 0));
		// nothing to undo: no command; a running growth: cancel
		assertEquals(MaterialRequests.TakeUp.NONE, MaterialRequests.takeUp(false, java.util.Optional.empty(), "rg1", 2, false));
		assertFalse(words(MaterialRequests.TakeUp.NONE).contains("/steward"));
		assertEquals(MaterialRequests.TakeUp.CANCEL, MaterialRequests.takeUp(true, java.util.Optional.of(DISTRICT), "rg1", 2, false));
		// the rid a target carries (Architect BC-d: ext architect_mc:revision.rid; none: 0)
		assertEquals(2, MaterialRequests.ridOf(JsonParser.parseString("{\"architect_mc:revision\": {\"region\": \"rg1\", \"rid\": 2}}").getAsJsonObject()));
		assertEquals(0, MaterialRequests.ridOf(JsonParser.parseString("{\"architect_mc:region\": \"rg1\"}").getAsJsonObject()));
		assertEquals(0, MaterialRequests.ridOf((com.google.gson.JsonObject) null));
	}
}
