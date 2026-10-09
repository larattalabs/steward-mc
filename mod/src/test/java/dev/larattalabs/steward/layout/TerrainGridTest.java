package dev.larattalabs.steward.layout;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.Sample;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerrainGridTest {
	private static Sample sample(int res) {
		int w = 3, d = 2;
		int[] height = {64, 64, 70, 64, Sample.MISSING, 64};
		BitSet water = new BitSet();
		water.set(1);
		BitSet missing = new BitSet();
		missing.set(4);
		return new Sample(10, 20, 12, 21, res, w, d, height, new int[6], new int[6], List.of(), new int[6], water, new BitSet(), new BitSet(), missing, 1, 1,
			new int[1], List.of(), List.of(), 1);
	}

	@Test
	void heightsWaterAndMissingColumnsConvert() {
		Grid g = TerrainGrid.fromSample(sample(1));
		assertEquals(10, g.x0());
		assertEquals(20, g.z0());
		assertEquals(65, g.heightAt(10, 20));
		assertTrue(g.waterAt(11, 20));
		assertFalse(g.waterAt(10, 20));
		assertEquals(71, g.heightAt(12, 20));
		assertTrue(g.waterAt(11, 21), "a missing column is unusable");
	}

	@Test
	void coarseSurveysAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> TerrainGrid.fromSample(sample(4)));
	}
}
