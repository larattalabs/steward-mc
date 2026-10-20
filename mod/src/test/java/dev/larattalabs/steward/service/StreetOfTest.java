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
}
