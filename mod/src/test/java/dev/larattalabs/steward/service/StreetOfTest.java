package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.DistrictRules;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreetOfTest {
	private static final Claim TOWN = new Claim("minecraft:overworld", 0, 0, 64, -64, 320);
	private static final Settlement S = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);

	private static Districts.Piece region(String key, int minX, int maxX, int minZ, int maxZ) {
		return new Districts.Piece("road", key, false, minX, maxX, minZ, maxZ, null, null, null);
	}

	@Test
	void aComposedPlansDistrictStreetIsTheTownsStreet() {
		// a D1 plan's district street (road:<district>_street_w), its box holding lanterns a cell either side of its line
		var st = Districts.streetOfPieces(List.of(region("road:harbour_street_w", -40, -4, 9, 13), region("road:harbour_street_e", 4, 40, 9, 13)), S, null);
		assertEquals(new DistrictRules.Street(11, -40, 40), st, "both halves, split at the square, are one line");
	}

	@Test
	void aNorthSouthStreetIsNotReadAsAnEastWestLine() {
		var ns = region("road:upper_street_w", -2, 2, -40, -4);
		assertNull(Districts.streetOfPieces(List.of(ns), S, null), "additions know an east-west line: a fresh street rather than a wrong one");
		var ew = region("road:lower_street_e", 4, 40, 20, 24);
		assertEquals(new DistrictRules.Street(22, 4, 40), Districts.streetOfPieces(List.of(ns, ew), S, null), "the east-west one is the town's street");
	}

	@Test
	void theRegionsStreetFlagCountsWhateverTheKey() {
		var flagged = new Districts.Piece("road", "road:avenue_a1", true, 0, 30, -1, 1, null, null, null);
		assertEquals(new DistrictRules.Street(0, 0, 30), Districts.streetOfPieces(List.of(flagged), S, null));
		var lane = new Districts.Piece("road", "road:lane_3", false, 0, 30, -1, 1, null, null, null);
		assertNull(Districts.streetOfPieces(List.of(lane), S, null), "a lane is not a street");
	}

	@Test
	void stewardsOwnStreetKeepsItsRecordedLine() {
		var own = new Districts.Piece("road", "street", false, -30, 30, -3, 3, 1, -28, 28);
		assertEquals(new DistrictRules.Street(1, -28, 28), Districts.streetOfPieces(List.of(own), S, null));
	}

	@Test
	void aSiteWithoutTheRecordedLineReadsNull() {
		var ext = new com.google.gson.JsonObject();
		assertNull(Districts.intExt(ext, "steward_mc:street_z"), "a region's street records no line: null, not a crash");
		assertNull(Districts.intExt(null, "steward_mc:street_z"));
		ext.addProperty("steward_mc:street_z", 7);
		assertEquals(7, Districts.intExt(ext, "steward_mc:street_z"));
	}
	// ------------------------------------------------------------------ (slice NS2) additions on the legacy north-south path

	/** Steward's own north-south street piece as BatchPlanner records it: its line an x, its ends z. */
	private static Districts.Piece ns(String key, int x, int z0, int z1) {
		return new Districts.Piece("road", key, false, x - 3, x + 3, Math.min(z0, z1), Math.max(z0, z1), null, null, null, x, z0, z1);
	}

	@Test
	void theLegacyPathReadsTheTownsStreetOnEitherAxis() {
		var own = new Districts.Piece("road", "street", false, -30, 30, -3, 3, 1, -28, 28);
		assertEquals(new DistrictRules.Street(1, -28, 28), Districts.streetEitherAxisOfPieces(List.of(own), S, null), "east-west: as streetOf");
		var a = ns("street", 4, -28, 28);
		var b = ns("street#2", 4, 40, 29);
		assertEquals(new DistrictRules.Street(4, -28, 40, true), Districts.streetEitherAxisOfPieces(List.of(a, b), S, null), "north-south: its x, its ends z");
		assertNull(Districts.streetOfPieces(List.of(a, b), S, null), "streetOf's callers still read east-west only");
		// a bent street keeps its east-west line, as before (it grows along it); no street: none
		assertEquals(new DistrictRules.Street(1, -28, 28), Districts.streetEitherAxisOfPieces(List.of(own, a), S, null));
		assertNull(Districts.streetEitherAxisOfPieces(List.of(), S, null));
	}

	@Test
	void anAdditionsStreetIsExtendedAlongItsOwnAxis() {
		var street = new DistrictRules.Street(4, -28, 40, true);
		var plan = new dev.larattalabs.steward.layout.VillageLayout.Plan(List.of(), 4, -40, 52, 64, List.of(), true);
		var spans = SettlementRunner.extensions(street, plan);
		assertEquals(2, spans.size());
		assertArrayEquals(new int[] {-40, -29}, spans.get(0), "along z, past the north end");
		assertArrayEquals(new int[] {41, 52}, spans.get(1));
		assertArrayEquals(new int[] {1, 41, 7, 52}, plan.spanBox(41, 52, 3), "a north-south span's box runs along z");
		assertTrue(SettlementRunner.extensions(street, new dev.larattalabs.steward.layout.VillageLayout.Plan(List.of(), 4, -40, 52, 64, List.of())).isEmpty(),
			"an east-west plan never extends a north-south street");
		assertTrue(SettlementRunner.extensions(street, new dev.larattalabs.steward.layout.VillageLayout.Plan(List.of(), 9, -40, 52, 64, List.of(), true)).isEmpty(),
			"another line");
		assertTrue(SettlementRunner.extensions(null, plan).isEmpty());
		var ew = new DistrictRules.Street(1, -28, 28);
		assertEquals(1, SettlementRunner.extensions(ew, new dev.larattalabs.steward.layout.VillageLayout.Plan(List.of(), 1, -28, 40, 64, List.of())).size(), "east-west: as before");
	}

	@Test
	void aCrateKeepsOffAStreetOnItsAxis() {
		var ns = new DistrictRules.Street(4, -28, 40, true);
		assertTrue(ns.near(6, 30, 3));
		assertFalse(ns.near(8, 30, 3), "5 across a north-south street");
		assertFalse(ns.near(4, 44, 3), "past its end");
		var ew = new DistrictRules.Street(1, -28, 28);
		assertTrue(ew.near(30, 3, 3));
		assertFalse(ew.near(30, 5, 3));
	}
}
