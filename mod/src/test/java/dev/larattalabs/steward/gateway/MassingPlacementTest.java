package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.layout.VillageLayout.Front;
import dev.larattalabs.steward.layout.VillageLayout.Lot;
import org.junit.jupiter.api.Test;

class MassingPlacementTest {
	@Test
	void aMassingSitsCentredAndSetBackFromTheStreetFacingIt() {
		// lot 18 wide, 20 deep (15 + the approach margin), street to the south
		Lot south = new Lot("a_1", "a", 100, 200, 18, 20, Front.SOUTH, 70);
		MassingPlacement p = MassingPlacement.on(south, 14, 12);
		assertEquals("NONE", p.rotation());
		assertEquals(102, p.x(), "centred across the lot");
		assertEquals(70, p.y());
		assertEquals(south.maxZ() - LotBrief.APPROACH_MARGIN, p.z() + 12 - 1, "the front face stops the margin short of the street edge");

		Lot north = new Lot("b_1", "b", 100, 300, 18, 20, Front.NORTH, 64);
		MassingPlacement q = MassingPlacement.on(north, 14, 12);
		assertEquals("CLOCKWISE_180", q.rotation(), "turned so its front faces the street to the north");
		assertEquals(300 + LotBrief.APPROACH_MARGIN, q.z());
		assertTrue(q.z() + 12 - 1 <= north.maxZ(), "inside the lot");
	}

	@Test
	void aMassingLargerThanItsLotStaysAnchoredToTheLot() {
		Lot lot = new Lot("c_1", "c", 0, 0, 10, 12, Front.SOUTH, 60);
		MassingPlacement p = MassingPlacement.on(lot, 16, 20);
		assertEquals(0, p.x());
		assertEquals(0, p.z(), "depth is clipped to the lot minus the margin for the preview's anchor");
	}

	@Test
	void aLotFacingEastOrWestTurnsItsMassingAQuarter() {
		// lot 20 deep along x (15 + the margin), 18 wide along z, street to the east
		Lot east = new Lot("e_1", "e", 100, 200, 20, 18, Front.EAST, 70);
		MassingPlacement p = MassingPlacement.on(east, 14, 12);
		assertEquals("COUNTERCLOCKWISE_90", p.rotation(), "its south front turned to face east");
		assertEquals(east.maxX() - LotBrief.APPROACH_MARGIN, p.x() + 12 - 1, "the front face stops the margin short of the street edge");
		assertEquals(202, p.z(), "centred along the lot");
		Lot west = new Lot("w_1", "w", 100, 200, 20, 18, Front.WEST, 70);
		MassingPlacement q = MassingPlacement.on(west, 14, 12);
		assertEquals("CLOCKWISE_90", q.rotation());
		assertEquals(100 + LotBrief.APPROACH_MARGIN, q.x());
	}

	@Test
	void aLotsWidthAndDepthFollowItsStreet() {
		Lot east = new Lot("e_1", "e", 0, 0, 20, 14, Front.EAST, 64);
		assertEquals(14, east.along(), "along a north-south street: its z side");
		assertEquals(20, east.deep());
		Lot south = new Lot("s_1", "s", 0, 0, 14, 20, Front.SOUTH, 64);
		assertEquals(14, south.along());
		assertEquals(20, south.deep());
	}
}
