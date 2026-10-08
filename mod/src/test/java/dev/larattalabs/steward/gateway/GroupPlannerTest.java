package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.GroupRequest;
import dev.larattalabs.steward.layout.Grid;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.layout.VillageLayout.LotSpec;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroupPlannerTest {
	private static final Claim CLAIM = new Claim("minecraft:overworld", 0, 0, 96, -64, 320);

	private static Settlement settlement(String fixture) throws Exception {
		var in = GroupPlannerTest.class.getClassLoader().getResourceAsStream(fixture + ".json");
		ConceptCard c = ConceptCard.parse(JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
		return Settlement.found("set_1", c, CLAIM, Permission.PROPOSALS, Difficulty.PATRON, 1L);
	}

	private static VillageLayout.Plan plan(int n) {
		List<LotSpec> specs = new ArrayList<>();
		for (int i = 0; i < n; i++) specs.add(new LotSpec("lot_" + i, i == 0 ? "tavern" : "house", i == 0 ? 24 : 12 + i % 3, i == 0 ? 20 : 11));
		return VillageLayout.plan(CLAIM, Grid.flat(-96, -96, 193, 193, 70), specs, VillageLayout.Rules.defaults());
	}

	@Test
	void everyLotBecomesAnItemKeyedByTheLotId() throws Exception {
		Settlement s = settlement("stilt_village");
		VillageLayout.Plan p = plan(8);
		GroupPlanner.Built b = GroupPlanner.build(s, p, GroupPlanner.Options.forCard(s, "bib_x", 2));
		GroupRequest g = b.request();
		assertEquals(p.lots().size(), g.items().size());
		assertTrue(b.omittedLotIds().isEmpty());
		assertEquals("bib_x", g.bible());
		assertEquals(2, g.bibleVersion());
		for (var l : p.lots()) assertTrue(g.items().stream().anyMatch(i -> l.id().equals(i.itemKey())));
		assertEquals("steward_mc:settlement/set_1", g.owner());
	}

	@Test
	void massingFirstWithStewardOwningTheApproval() throws Exception {
		Settlement s = settlement("crater_works");
		GroupRequest g = GroupPlanner.build(s, plan(5), GroupPlanner.Options.forCard(s, "bib_x", null)).request();
		assertTrue(g.massingFirst());
		assertEquals(GroupRequest.ApprovalUi.OWNER, g.approvalUi());
		assertEquals(3, g.maxRedirects());
		assertNotNull(g.context());
	}

	@Test
	void theLargestLotIsTheOpusAnchorAndLandmarksComeBeforeOrdinaryItems() throws Exception {
		Settlement s = settlement("crater_works"); // size L: 3 landmarks
		GroupRequest g = GroupPlanner.build(s, plan(10), GroupPlanner.Options.forCard(s, "bib_x", null)).request();
		var anchors = g.items().stream().filter(GroupRequest.Item::anchor).toList();
		assertEquals(1, anchors.size());
		assertEquals("lot_0", anchors.get(0).itemKey(), "the tavern lot is the largest");
		assertEquals(GroupRequest.Role.LANDMARK, anchors.get(0).role());
		long landmarks = g.items().stream().filter(i -> i.role() == GroupRequest.Role.LANDMARK).count();
		assertEquals(3, landmarks);
		assertTrue(g.items().stream().filter(i -> i.role() == GroupRequest.Role.ORDINARY).allMatch(i -> i.wave() == 2));
		assertTrue(g.items().stream().filter(i -> i.role() == GroupRequest.Role.LANDMARK && !i.anchor()).allMatch(i -> i.wave() == 1));
	}

	@Test
	void heightsAndOwnershipPerItem() throws Exception {
		Settlement s = settlement("stilt_village");
		GroupRequest g = GroupPlanner.build(s, plan(4), GroupPlanner.Options.forCard(s, "bib_x", null)).request();
		for (var i : g.items()) {
			assertEquals(i.role() == GroupRequest.Role.LANDMARK ? 40 : 24, i.request().maxSize().y());
			assertEquals("steward_mc:settlement/set_1", i.request().owner());
			assertEquals(i.itemKey(), i.request().ext().get("steward_mc:lot").getAsString());
		}
	}

	@Test
	void theBudgetIsTheCardsOrTheSizeScaledSuggestion() throws Exception {
		Settlement s = settlement("crater_works"); // size L, no budget
		assertEquals(BudgetPolicy55(), GroupPlanner.Options.forCard(s, "b", null).budgetUsd(), 1e-9);
	}

	private static double BudgetPolicy55() {
		return dev.larattalabs.steward.model.BudgetPolicy.suggestedBudgetUsd("L");
	}

	@Test
	void moreThanTwentyFourLotsKeepsLandmarksAndReportsTheRest() throws Exception {
		Settlement s = settlement("crater_works");
		List<LotSpec> specs = new ArrayList<>();
		for (int i = 0; i < 40; i++) specs.add(new LotSpec("lot_" + i, "house", 8, 8));
		VillageLayout.Plan p = VillageLayout.plan(CLAIM, Grid.flat(-96, -96, 193, 193, 70), specs, VillageLayout.Rules.defaults());
		assertTrue(p.lots().size() > 24, "the claim fits more than 24 small lots: " + p.lots().size());
		GroupPlanner.Built b = GroupPlanner.build(s, p, GroupPlanner.Options.forCard(s, "b", null));
		assertEquals(24, b.request().items().size());
		assertEquals(p.lots().size() - 24, b.omittedLotIds().size());
		assertEquals(3, b.request().items().stream().filter(i -> i.role() == GroupRequest.Role.LANDMARK).count());
	}

	@Test
	void contextNamesTheSettlementStreetAndNeighbours() throws Exception {
		Settlement s = settlement("sky_temple");
		VillageLayout.Plan p = plan(3);
		var ctx = GroupPlanner.build(s, p, GroupPlanner.Options.forCard(s, "b", null)).request().context().getAsJsonObject();
		assertEquals("a home for monks", ctx.get("purpose").getAsString());
		assertEquals(p.streetZ(), ctx.getAsJsonObject("street").get("z").getAsInt());
		assertEquals(3, ctx.getAsJsonArray("lots").size());
		assertEquals("dark stone", ctx.getAsJsonArray("avoid").get(0).getAsString());
	}

	@Test
	void everyItemGetsAReportOnlyCritiqueWithLotSpecificCriteria() throws Exception {
		Settlement s = settlement("sky_temple");
		GroupRequest g = GroupPlanner.build(s, plan(3), GroupPlanner.Options.forCard(s, "b", null)).request();
		for (var i : g.items()) {
			assertNotNull(i.critique());
			assertEquals(dev.larattalabs.architect.api.CritiqueMode.REPORT, i.critique().mode());
			assertNull(i.critique().maxRevisions(), "no revision loop: it failed its gates and is experimental");
			String all = String.join(" | ", i.critique().extraCriteria());
			assertTrue(all.contains("entrance"));
			assertTrue(all.contains("Avoids: dark stone"));
			assertTrue(i.critique().extraCriteria().size() <= 3);
		}
	}

	@Test
	void critiqueCanBeSwitchedOff() throws Exception {
		Settlement s = settlement("crater_works");
		GroupRequest g = GroupPlanner.build(s, plan(3), GroupPlanner.Options.forCard(s, "b", null).withCritiqueReport(false)).request();
		for (var i : g.items()) assertNull(i.critique());
	}
}
