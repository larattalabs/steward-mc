package dev.larattalabs.steward.layout;

import dev.larattalabs.architect.api.Sample;

/** Converts Architect's survey {@link Sample} (1-block resolution) into the {@link Grid} the layout uses. Missing (unloaded) columns count as water, so no lot lands on them. */
public final class TerrainGrid {
	private TerrainGrid() {
	}

	public static Grid fromSample(Sample s) {
		if (s.resolution() != 1) throw new IllegalArgumentException("the layout needs a 1-block survey (got resolution " + s.resolution() + ")");
		int[] h = new int[s.width() * s.depth()];
		boolean[] w = new boolean[h.length];
		for (int j = 0; j < s.depth(); j++) {
			for (int i = 0; i < s.width(); i++) {
				int k = s.index(i, j);
				boolean unknown = s.isMissing(i, j) || s.height()[k] == Sample.MISSING;
				w[k] = unknown || s.water().get(k);
				// motion-blocking height is the top of the ground block; lots stand on the block above it
				h[k] = unknown ? 0 : s.height()[k] + 1;
			}
		}
		return new Grid(s.minX(), s.minZ(), s.width(), s.depth(), h, w);
	}
}
