package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.DistrictSite.Profile;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Slice DP: a district's place line (why it goes where it would), placing a waiting one again ("no, by the lake"), and the card's left-out buildings. */
class DistrictPlacementTest {
	private static final List<String> SIDES = List.of("N", "E", "S", "W");

	private static Profile ground(double water, double trees, double height) {
		return new Profile(1, water, trees, height, 1, Map.of());
	}

	/** Dry, flat ground every way but the south, where a lake lies. */
	private static final Map<String, Profile> LAKE_SOUTH = Map.of("N", ground(0, 0, 64), "E", ground(0, 0, 64), "S", ground(0.3, 0, 64), "W", ground(0, 0, 64));

	@Test
	void thePlaceLineSaysWhyAndWhatFor() {
		var harbour = DistrictSite.fromWords("the harbour");
		var c = harbour.choose(SIDES, LAKE_SOUTH, 64, null);
		assertEquals("S", c.way());
		assertEquals("by the water, for the harbour", DistrictSite.line(c.why(), "the harbour"));
		assertEquals("to the east, as you said, for the harbour", DistrictSite.line("to the east, as you said", "harbour"));
		assertEquals("where there is room", DistrictSite.line("", null));
		assertEquals("in the woods, for a lumber camp", DistrictSite.line("in the woods (40% trees)", "a lumber camp"));
	}

	@Test
	void noByTheLakeMovesAWaitingDistrictToTheWater() {
		String said = "no, by the lake";
		assertTrue(DistrictMove.awayFrom(said));
		var skip = DistrictMove.skip(Set.of(), "E", said, null);
		assertEquals(Set.of("E"), skip);
		var site = DistrictSite.fromWords(said);
		var c = site.choose(SIDES.stream().filter(d -> !skip.contains(d)).toList(), LAKE_SOUTH, 64, null);
		var o = DistrictMove.decide(site, c, "E", "the harbour", "Here");
		assertEquals("S", o.way());
		assertFalse(o.stays());
		String reply = DistrictMove.movedReply("Right, by the lake.", o.way(), "E", c.why());
		assertTrue(reply.contains("to the south (instead of to the east), by the water"), reply);
		assertEquals("by the water, for the harbour", DistrictSite.line(c.why(), "the harbour"));
	}

	@Test
	void withNoLakeTheDistrictStaysAndTheStewardSaysSo() {
		Map<String, Profile> dry = Map.of("N", ground(0, 0, 64), "S", ground(0, 0, 64), "W", ground(0, 0, 64));
		var site = DistrictSite.fromWords("no, by the lake");
		var c = site.choose(List.of("N", "S", "W"), dry, 64, null);
		assertEquals("", c.why());
		var o = DistrictMove.decide(site, c, "E", "the harbour", "Here");
		assertNull(o.way());
		assertTrue(o.stays());
		assertTrue(o.reply().startsWith("I see no water beside Here"), o.reply());
		assertTrue(o.reply().contains("the harbour stays to the east"), o.reply());
		// no other side with room: it stays too
		var none = DistrictMove.decide(site, new DistrictSite.Choice(null, ""), "E", null, "Here");
		assertTrue(none.stays() && none.reply().contains("the district stays to the east"), none.reply());
		// nothing waiting and no ground asked for: today's order, offered
		var first = DistrictMove.decide(DistrictSite.ANYWHERE, new DistrictSite.Choice("N", ""), null, null, "Here");
		assertEquals("N", first.way());
	}

	@Test
	void onlyWordsThatTurnAwayRuleOutTheWaitingSide() {
		assertFalse(DistrictMove.awayFrom("by the lake"));
		assertFalse(DistrictMove.awayFrom("I know a good spot by the river"));
		assertTrue(DistrictMove.awayFrom("somewhere else, by the river"));
		assertTrue(DistrictMove.awayFrom("not there, put it in the woods"));
		assertTrue(DistrictMove.awayFrom("Nope. On the hill."));
		// "by the lake" alone keeps the waiting side in the running (it may be the lake side); naming it keeps it too
		assertEquals(Set.of("W"), DistrictMove.skip(Set.of("W"), "E", "by the lake", null));
		assertEquals(Set.of(), DistrictMove.skip(Set.of(), "E", "no, the east", "E"));
	}

	private static ConceptCard stiltVillage() {
		var in = DistrictPlacementTest.class.getClassLoader().getResourceAsStream("stilt_village.json");
		return ConceptCard.parse(JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
	}

	@Test
	void theCardsLeftOutBuildingsAreWhatDoesNotStand() {
		// built at 5 of its 11: the smokehouse and four fisher's huts (by role), and nothing else
		var left = ProposalRules.leftOut(stiltVillage(), Map.of("smokehouse", 1, "fisher's hut", 4), Map.of());
		assertEquals(List.of("fisher's hut", "net loft", "bait shop", "lookout"), left.stream().map(ConceptCard.Building::role).toList());
		assertEquals(2, left.get(0).count());
		assertEquals("2 fisher's huts, the net loft, the bait shop and the lookout", ProposalRules.leftOutWords(left));
		// a building with no role recorded counts by its lot's type
		var byType = ProposalRules.leftOut(stiltVillage(), Map.of(), Map.of("smokehouse", 1, "cabin", 6, "barn", 1, "shop", 1, "tower", 1));
		assertTrue(byType.isEmpty(), byType.toString());
		assertTrue(ProposalRules.leftOut(null, Map.of(), Map.of()).isEmpty());
	}

	@Test
	void theLeftOutBuildingsChooseTheirGround() {
		var left = ProposalRules.leftOut(stiltVillage(), Map.of("smokehouse", 1, "fisher's hut", 6), Map.of());
		// the net loft keeps nets and boats: by the water
		var site = DistrictSite.fromWords(ProposalRules.leftOutSiteWords(left));
		assertEquals(DistrictSite.Pref.WANT, site.water());
		var c = site.choose(SIDES, LAKE_SOUTH, 64, null);
		assertEquals("S", c.way());
		assertEquals("by the water, for the net loft", DistrictSite.line(c.why(), left.get(0).role()));
	}

	@Test
	void thePlaceLineIsKeptAndOldProposalsHaveNone() {
		Settlement s = Settlement.founded("set_1", "Here", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		Proposal p = Proposal.district("S", "why", 5L).withPlace("by the water, for the harbour");
		assertEquals("by the water, for the harbour", p.place());
		assertEquals(p.place(), p.in("d1").place(), "moving it into a district keeps the line");
		assertNull(Proposal.district("S", "why", 5L).withPlace(" ").place());
		s = s.withProposals(new Settlement.Proposals(List.of(p, Proposal.district("N", "older", 4L)), List.of(), List.of(), 5L));
		SettlementStore st = new SettlementStore();
		st.put(s);
		var back = SettlementStore.fromJson(st.toJson()).get("set_1").orElseThrow().proposals().open();
		assertEquals("by the water, for the harbour", back.get(0).place());
		assertNull(back.get(1).place());
	}
}
