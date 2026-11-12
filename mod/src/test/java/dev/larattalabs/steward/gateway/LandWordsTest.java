package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.LandReport;
import dev.larattalabs.architect.api.LotLand;
import dev.larattalabs.architect.api.PartLand;
import dev.larattalabs.steward.gateway.LandWords.Look;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LandWordsTest {
	private static final Map<String, String> NAMES = Map.of("site_7", "smithy", "site_8", "fisher's hut");

	private static PartLand part(String id, long changed, long removed, long placed, long vol, Map<String, Long> kinds) {
		return new PartLand(id, 100, changed, removed, placed, vol, kinds, List.of());
	}

	private static LotLand lot(String id, String site, long changed, long placed) {
		return new LotLand(id, site, "STANDING", 0.99, changed, placed);
	}

	private static LandReport report(List<PartLand> parts, List<LotLand> lots, long unloaded) {
		return new LandReport("rg_1", 0, parts, lots, 1000, unloaded, 12);
	}

	private static LandWords.Note note(boolean onDemand, Look... looks) {
		return LandWords.note("Hamlet", List.of(looks), NAMES::get, onDemand);
	}

	@Test
	void volatileChangeAloneIsNothing() {
		var r = report(List.of(part("verges", 0, 0, 0, 30, Map.of()), part("square", 0, 0, 0, 0, Map.of())), List.of(lot("cottage_1", "site_7", 0, 0)), 0);
		var n = note(true, Look.of("Hamlet", false, r));
		assertFalse(n.real());
		assertEquals("I found nothing broken or changed in what we built in Hamlet.", n.headline());
		assertEquals(List.of(), n.lines());
		assertEquals(0, n.damage());
	}

	@Test
	void aBuildingIsNamedByItsRoleAndCannotTellBrokenFromChanged() {
		var r = report(List.of(), List.of(lot("cottage_1", "site_7", 1, 1), lot("cottage_2", "site_9", 0, 0)), 0);
		var n = note(true, Look.of("Hamlet", false, r));
		assertTrue(n.real());
		assertEquals(List.of("The smithy: 1 block broken or changed, 1 placed by a player."), n.lines());
		assertEquals(1, n.damage());
		assertEquals(1, n.placed());
		assertEquals("I looked over Hamlet: some of what we built is broken or changed.", n.headline());
	}

	@Test
	void aLotWithoutABuildingNameIsCalledByItsLot() {
		var r = report(List.of(), List.of(lot("tower_1", "site_x", 2, 0)), 0);
		assertEquals(List.of("The tower 1: 2 blocks broken or changed."), note(true, Look.of("Hamlet", false, r)).lines());
	}

	@Test
	void partsSayGoneReplacedWithWhatIsThereNowAndPlayersBlocks() {
		var r = report(List.of(part("square", 6, 40, 0, 3, Map.of("minecraft:sponge", 6L)), part("lanes", 0, 0, 6, 0, Map.of())), List.of(), 0);
		var n = note(true, Look.of("Hamlet", false, r));
		assertEquals(List.of("The square: 40 blocks gone, 6 replaced (now sponge).", "The lanes: 6 placed by players."), n.lines());
		assertEquals(46, n.damage());
		assertEquals(6, n.placed());
	}

	@Test
	void kindsAreTheTwoCommonestBlocksNowThere() {
		assertEquals(" (now cobblestone, oak planks)", LandWords.nowKinds(Map.of("minecraft:dirt", 1L, "minecraft:oak_planks[axis=y]", 3L, "minecraft:cobblestone", 5L)));
		assertEquals("", LandWords.nowKinds(Map.of()));
		assertEquals("3 blocks replaced (now dirt)", LandWords.counts(0, 3, 0, Map.of("minecraft:dirt", 3L), false));
	}

	@Test
	void partNames() {
		assertEquals("other built areas", LandWords.partName("unattributed"));
		assertEquals("the gate of the gatehouse 1", LandWords.partName("mech:gatehouse_1"));
		assertEquals("the curtain wall", LandWords.partName("w_curtain_wall"));
		assertEquals("the curtain wall", LandWords.partName("wl_curtain_wall"));
		assertEquals("the spire", LandWords.partName("rv1_spire"));
		assertEquals("the well", LandWords.partName("props_well"));
		assertEquals("the street's east end", LandWords.partName("street_e"));
		assertEquals("the street's west end", LandWords.partName("rv2_street_w"));
		assertEquals("the square", LandWords.partName("square"));
	}

	@Test
	void onlyPlayersBlocksIsNoDamage() {
		var r = report(List.of(part("lanes", 0, 0, 4, 0, Map.of())), List.of(lot("cottage_1", "site_8", 0, 2)), 0);
		var n = note(false, Look.of("Hamlet", false, r));
		assertTrue(n.real());
		assertEquals(0, n.damage());
		assertEquals(6, n.placed());
		assertEquals("I looked over Hamlet: you have added to what we built.", n.headline());
		assertEquals("The fisher's hut: 2 placed by players.", n.lines().get(0));
	}

	@Test
	void districtsAreSaidUnderTheirName() {
		var town = report(List.of(), List.of(), 0);
		var ford = report(List.of(part("street_w", 0, 3, 0, 0, Map.of())), List.of(), 0);
		var n = note(true, Look.of("Hamlet", false, town), Look.of("Copper Ford", true, ford));
		assertEquals(List.of("In Copper Ford, the street's west end: 3 blocks gone."), n.lines());
	}

	@Test
	void unloadedCellsAreSaidOnlyWhenAsked() {
		var r = report(List.of(), List.of(), 120);
		var asked = note(true, Look.of("Hamlet", false, r));
		assertEquals("I found nothing broken or changed in what I could see of Hamlet.", asked.headline());
		assertEquals(List.of("I couldn't see 120 blocks of Hamlet from here: come closer and ask again."), asked.lines());
		var auto = note(false, Look.of("Hamlet", false, r));
		assertEquals(List.of(), auto.lines());
	}

	@Test
	void aRegionTooLargeOrRefusedIsSaidHonestly() {
		var n = note(true, Look.refused("Hamlet", false, "REGION_LIMIT", "ask per part"));
		assertFalse(n.real());
		assertEquals("I couldn't look over Hamlet.", n.headline());
		assertEquals(List.of("Hamlet is too large for me to look over at once."), n.lines());
		var other = note(true, Look.refused("Copper Ford", true, "JOURNAL_UNAVAILABLE", "no journal"));
		assertEquals(List.of("I couldn't look over Copper Ford: no journal"), other.lines());
		assertEquals(List.of(), note(false, Look.refused("Hamlet", false, "REGION_LIMIT", "x")).lines());
	}

	@Test
	void longNotesAreCut() {
		var lots = new java.util.ArrayList<LotLand>();
		for (int i = 0; i < 14; i++) lots.add(lot("cottage_" + (10 + i), "s" + i, 1, 0));
		var n = note(true, Look.of("Hamlet", false, report(List.of(), lots, 0)));
		assertEquals(LandWords.MAX_LINES, n.lines().size());
		assertEquals("And 5 more places.", n.lines().getLast());
		assertEquals(14, n.damage());
	}

	@Test
	void askedMatchesWholeQuestionsOnly() {
		for (String s : List.of("what changed?", "What has changed here?", "anything broken", "is anything damaged?", "check the town", "damage report",
			"has anything changed, steward?", "what's missing", "look over the village", "what changed since i left"))
			assertTrue(LandWords.asked(s), s);
		for (String s : List.of("change the tavern to stone", "what changed in the inn's design", "add a library", "check the tavern's roof", "what are you doing",
			"anything else?", "what changed? now rebuild the tavern", ""))
			assertFalse(LandWords.asked(s), s);
		assertFalse(LandWords.asked(null));
	}

	@Test
	void twoBuildingsOfOneRoleAreToldApartByTheirLot() {
		var names = Map.of("a", "fisher's hut", "b", "fisher's hut");
		var r = report(List.of(), List.of(lot("cabin_1", "a", 1, 0), lot("cabin_2", "b", 2, 0)), 0);
		var n = LandWords.note("Hamlet", List.of(Look.of("Hamlet", false, r)), names::get, true);
		assertEquals(List.of("The fisher's hut (cabin 2): 2 blocks broken or changed.", "The fisher's hut (cabin 1): 1 block broken or changed."), n.lines());
	}

	@Test
	void theSignatureIsPerRegionAndPlaceNotATotal() {
		var smithy = note(false, Look.of("Hamlet", false, report(List.of(), List.of(lot("l1", "site_7", 3, 0)), 0)));
		var chapel = note(false, Look.of("Hamlet", false, report(List.of(), List.of(lot("l2", "site_9", 3, 0)), 0)));
		assertEquals(smithy.damage(), chapel.damage());
		assertNotEquals(smithy.signature(), chapel.signature());
		assertEquals("rg_1|lot:site_7=3", smithy.signature());
		// the player's blocks and volatile change are not in it
		var decorated = note(false, Look.of("Hamlet", false, report(List.of(part("lanes", 0, 0, 5, 9, Map.of())), List.of(lot("l1", "site_7", 3, 4)), 0)));
		assertEquals(smithy.signature(), decorated.signature());
		assertTrue(smithy.complete());
		assertFalse(note(false, Look.of("Hamlet", false, report(List.of(), List.of(), 3))).complete());
		assertFalse(note(false, Look.refused("Hamlet", false, "REGION_LIMIT", "")).complete());
	}
}
