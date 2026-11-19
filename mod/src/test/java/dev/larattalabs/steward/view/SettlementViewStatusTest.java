package dev.larattalabs.steward.view;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** (slice 8bS) A building's row and map fill from Architect 8b's BuildStatus, in the steward's words; the map's shapes only in the screen's view. */
class SettlementViewStatusTest {
	private static SettlementView.Building b(String state, String status, String reason, int version, int head) {
		return new SettlementView.Building("s1", "hall", null, "e", version, head, 0, state, false, 0, 0, 1, 1, List.of(), 0, 0, 0, 0, null, status, reason, 50L);
	}

	@Test
	void everyStatusHasTheStewardsWords() {
		assertArrayEquals(new String[] {"working", "being placed"}, SettlementView.statusWords("placing", null));
		assertArrayEquals(new String[] {"waiting", "waiting for its lot to clear"}, SettlementView.statusWords("waiting", "occupied"));
		assertArrayEquals(new String[] {"waiting", "waiting for someone to step off its lot"}, SettlementView.statusWords("waiting", "player_in_box"));
		assertArrayEquals(new String[] {"waiting", "held at the bill you approved"}, SettlementView.statusWords("waiting", "bill_cap"));
		assertArrayEquals(new String[] {"idle", "planned"}, SettlementView.statusWords("planned", null));
		assertArrayEquals(new String[] {"error", "not built"}, SettlementView.statusWords("failed", "failed:FIT"));
		assertArrayEquals(new String[] {"waiting", "waiting"}, SettlementView.statusWords("waiting", null), "an untyped wait is never guessed");
		for (String st : List.of("planned", "placing", "building", "waiting", "paused", "built", "removing", "removed", "failed"))
			assertFalse(SettlementView.statusWords(st, "materials")[1].isBlank(), st);
	}

	@Test
	void theRowReadsItsStatusBeforeANewerVersion() {
		assertArrayEquals(new String[] {"waiting", "waiting for materials"}, b("building", "waiting", "materials", 1, 2).stateWords(),
			"a wait says why, before the update");
		assertArrayEquals(new String[] {"waiting", "update to v2"}, b("built", "built", null, 1, 2).stateWords());
		assertArrayEquals(new String[] {"done", "v3"}, b("built", "built", null, 3, 3).stateWords());
		assertArrayEquals(new String[] {"working", "being placed"}, b("placing", "placing", null, 1, 1).stateWords());
		// an Architect before 8b: state alone, as before
		assertArrayEquals(new String[] {"working", "under construction"}, b("building", null, null, 1, 1).stateWords());
		assertArrayEquals(new String[] {"done", "v1"}, b("built", null, null, 1, 1).stateWords());
		var updating = new SettlementView.Building("s1", "hall", null, "e", 1, 2, 0, "built", true, 0, 0, 1, 1, List.of(), 0, 0, 0, 0, null, "waiting", "materials", 9L);
		assertArrayEquals(new String[] {"working", "updating"}, updating.stateWords());
	}

	@Test
	void aSitesStatusReachesItsRowAndTheMapOnlyTheScreensView() {
		Settlement s = Settlement.founded("set_8", "Vale", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.SUPPLIED, 1L);
		var site = new SettlementView.Site("s1", "building", "k", "hall", "lot_1", "e", 1, 1, 0, "built", false, 0, 0, 4, 4, 0, 64, 70, "waiting", "occupied", 1234L);
		var v = SettlementView.of(s, false, List.of(site));
		var row = v.building("s1");
		assertEquals("waiting", row.status());
		assertEquals("occupied", row.reason());
		assertEquals(1234L, row.since());
		assertNull(v.map(), "the in-world view carries no map");
		assertFalse(v.toJson().contains("\"map\""), "nor in its JSON");
		var shape = new SettlementView.MapShape("lot_2", "lot", "lot_2", 10, 10, 20, 20, List.of(), null, "planned", null);
		var route = new SettlementView.MapShape("east_street", "route", null, 0, 0, 30, 2, List.of(0, 1, 15, 1, 30, 1), null, "building", null);
		var withMap = SettlementView.fromJson(v.withMap(List.of(shape, route)).toJson());
		assertEquals(List.of(shape, route), withMap.map());
		// (slice LYW) a wall's width, a wall lot's wall and where on it: through the JSON too
		var wall = new SettlementView.MapShape("town_wall", "wall", null, 0, 0, 40, 40, List.of(0, 0, 40, 0), null, "built", null, 2, null, null);
		var tower = new SettlementView.MapShape("gatehouse", "lot", "gatehouse", 16, 36, 24, 44, List.of(), "s4", "built", null, 0, "town_wall", "gate");
		assertEquals(List.of(wall, tower), SettlementView.fromJson(v.withMap(List.of(wall, tower)).toJson()).map());
		assertEquals(0, shape.width(), "a shape from before layoutWalls has no width");
		assertEquals("planned", new SettlementView.MapShape("p", "lot", null, 0, 0, 1, 1, null, null, null, null).status(), "a pad with no status is planned");
	}
}
