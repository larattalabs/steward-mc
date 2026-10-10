package dev.larattalabs.steward.view;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.Settlement.Kind;
import dev.larattalabs.steward.model.Settlement.Outcome;
import dev.larattalabs.steward.model.Settlement.SiteChange;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettlementViewTest {
	private static Settlement settlement() {
		Settlement s = Settlement.founded("set_4", "Greywater", new Claim("minecraft:overworld", 100, 0, 96, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		s = s.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed 2 buildings", List.of(new SiteChange("s1", "smokehouse", 0, 1),
			new SiteChange("s2", "cottage", 0, 1), new SiteChange("s3", null, 0, 1)), "g1", Outcome.DONE));
		return s.withLog(Settlement.LogEntry.of(3L, Kind.UPDATED, "Updated cottage to version 2", List.of(new SiteChange("s2", "cottage", 1, 2)), null, Outcome.DONE));
	}

	private static List<SettlementView.Site> sites() {
		return List.of(new SettlementView.Site("s1", "building", "smokehouse_1", "smokehouse", "smokehouse_1", "gen_smokehouse", 1, 1, 0, "built", false, 90, -10, 104, 2),
			new SettlementView.Site("s2", "building", "cottage_1", null, "cottage_1", "gen_cottage", 2, 3, 4, "built", false, 110, -8, 118, 0),
			new SettlementView.Site("s3", "road", "street", null, null, "road", 1, 1, 0, "built", false, 80, -2, 130, 2));
	}

	@Test
	void buildingsAreListedWithVersionsEditsAndTheirOperationsRoadsCounted() {
		SettlementView v = SettlementView.of(settlement(), false, sites());
		assertEquals(2, v.buildings().size());
		assertEquals(1, v.roads());
		SettlementView.Building cottage = v.building("s2");
		assertEquals("cottage_1", cottage.role(), "no role recorded: the lot names it");
		assertTrue(cottage.updateAvailable());
		assertEquals(4, cottage.edits());
		assertEquals(List.of("op_2", "op_3"), cottage.ops());
		assertEquals(193, v.claim().side());
		assertEquals("L", v.claim().size());
	}

	@Test
	void theHistoryIsNewestFirstCappedAndSaysHowEachIsUndone() {
		Settlement s = settlement();
		for (int i = 0; i < SettlementView.MAX_HISTORY + 5; i++) s = s.withLog(new Settlement.LogEntry(10L + i, Kind.NOTE, "note " + i, List.of()));
		SettlementView v = SettlementView.of(s, false, sites());
		assertEquals(SettlementView.MAX_HISTORY, v.history().size());
		assertEquals(s.log().size(), v.historyTotal());
		assertEquals("note " + (SettlementView.MAX_HISTORY + 4), v.history().get(0).text());
		SettlementView small = SettlementView.of(settlement(), false, sites());
		assertEquals("UPDATED", small.history().get(0).kind());
		assertEquals("REVERT_VERSION", small.history().get(0).recovery());
		assertEquals("UNDO_PROJECT", small.history().get(1).recovery());
		assertEquals(List.of("UPDATED", "PROJECT_PLACED"), small.opsOf("s2").stream().map(SettlementView.Op::kind).toList());
	}

	@Test
	void itSurvivesTheTripAsJson() {
		SettlementView v = SettlementView.of(settlement(), true, sites());
		assertEquals(v, SettlementView.fromJson(v.toJson()));
	}
}
