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
		// A tree column's motion-blocking height is the top of its trunk, not the ground. Estimate the ground there from the nearest non-tree dry columns
		// (the placement code clears natural trees on a lot), so a forest is not read as a field of 8-block spikes.
		int[] ground = h.clone();
		for (int j = 0; j < s.depth(); j++) {
			for (int i = 0; i < s.width(); i++) {
				int k = s.index(i, j);
				if (w[k] || !s.tree().get(k)) continue;
				ground[k] = nearestGround(s, h, w, i, j, h[k]);
			}
		}
		return new Grid(s.minX(), s.minZ(), s.width(), s.depth(), ground, w);
	}

	/** The lowest height among non-tree, non-water columns within 4 of (i, j), or {@code fallback} when there are none. */
	private static int nearestGround(Sample s, int[] h, boolean[] w, int i, int j, int fallback) {
		int best = Integer.MAX_VALUE;
		for (int dj = -4; dj <= 4; dj++) {
			for (int di = -4; di <= 4; di++) {
				int ii = i + di, jj = j + dj;
				if (ii < 0 || jj < 0 || ii >= s.width() || jj >= s.depth()) continue;
				int k = s.index(ii, jj);
				if (w[k] || s.tree().get(k)) continue;
				best = Math.min(best, h[k]);
			}
		}
		return best == Integer.MAX_VALUE ? fallback : best;
	}
}
