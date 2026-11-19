package dev.larattalabs.steward.view;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** (8bS design review) The settlement map fits the layout's bounds, not the claim's: the town fills the map, centred, north up. */
class MapFrameTest {
	@Test
	void theLayoutFillsTheAreaCentred() {
		// a town 64 wide and 40 deep, off the claim's centre, in a 128 x 116 pixel area
		var f = MapFrame.fit(100, 200, 163, 239, 10, 20, 128, 116);
		assertEquals(2.0, f.scale(), 1e-9, "64 blocks across 128 pixels");
		assertEquals(10, f.x(100), "the west edge at the area's left");
		assertEquals(138, f.x(164), "the east edge at the area's right");
		assertEquals(20 + 58 - 40, f.z(200), "the depth centred in the area");
		assertEquals(20 + 58 + 40, f.z(240));
		assertTrue(f.holds(f.x(100), f.z(200), f.x(164), f.z(240)));
	}

	@Test
	void aLoneSmallBuildingIsNotDrawnWallToWall() {
		var f = MapFrame.fit(0, 0, 4, 4, 0, 0, 128, 128);
		assertEquals(128.0 / MapFrame.MIN_SPAN, f.scale(), 1e-9);
		assertEquals(64, f.x(2.5), "centred");
	}

	@Test
	void theTallerSideSetsTheScale() {
		var f = MapFrame.fit(0, 0, 49, 199, 0, 0, 100, 100);
		assertEquals(0.5, f.scale(), 1e-9);
		assertFalse(f.holds(-1, 0, 10, 10));
	}
}
