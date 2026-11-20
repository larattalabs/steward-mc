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

	// (MAP-P3B) the area takes the town's shape, so neither axis is left half empty

	@Test
	void aWideTownGetsAShortArea() {
		// 120 blocks across, 40 deep, in at most 164 x 67: the width sets the scale, the height shrinks to the town's
		int[] a = MapFrame.area(120, 40, 164, 67, 100);
		assertEquals(164, a[0]);
		assertEquals(55, a[1], "40 * 164/120 = 54.7, rounded up");
		var f = MapFrame.fit(0, 0, 119, 39, 10, 20, a[0], a[1]);
		assertEquals(10, f.x(0));
		assertEquals(10 + 164, f.x(120));
		assertTrue(f.z(0) >= 20 && f.z(0) <= 21, "the north edge at the top, within a pixel");
		assertTrue(f.holds(f.x(0), f.z(0), f.x(120), f.z(40)));
	}

	@Test
	void aSquareTownInAWideBoxGetsANarrowArea() {
		int[] a = MapFrame.area(64, 64, 164, 67, 40);
		assertEquals(67, a[0]);
		assertEquals(67, a[1]);
		var f = MapFrame.fit(0, 0, 63, 63, 0, 0, a[0], a[1]);
		assertTrue(f.holds(f.x(0), f.z(0), f.x(64), f.z(64)));
		assertTrue(f.x(0) <= 1 && f.x(64) >= 66, "fills the width");
	}

	@Test
	void theAreaIsAtLeastItsLeastWidth() {
		int[] a = MapFrame.area(64, 64, 164, 67, 104);
		assertEquals(104, a[0], "room for the hint line");
		assertEquals(67, a[1]);
		assertEquals(164, MapFrame.area(64, 64, 164, 67, 400)[0], "never past the box");
	}

	@Test
	void aSmallTownCountsAsTheLeastSpan() {
		int[] a = MapFrame.area(4, 4, 164, 67, 0);
		assertEquals(67, a[0]);
		assertEquals(67, a[1]);
	}

	@Test
	void aTallTownKeepsTheHeight() {
		int[] a = MapFrame.area(40, 160, 164, 80, 0);
		assertEquals(20, a[0]);
		assertEquals(80, a[1]);
	}
}
