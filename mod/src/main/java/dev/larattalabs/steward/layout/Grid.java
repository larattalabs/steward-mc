package dev.larattalabs.steward.layout;

/**
 * A terrain sample at 1-block resolution: ground height and a water mask over a rectangle. Pure data (built from Architect's
 * Survey.Sample, or by hand in tests). Cells outside the rectangle are unknown and count as unusable.
 */
public record Grid(int x0, int z0, int width, int depth, int[] height, boolean[] water, boolean[] built) {
	public Grid {
		if (height.length != width * depth || water.length != width * depth || built.length != width * depth) throw new IllegalArgumentException("grid arrays must be width*depth");
	}

	/** A grid with nothing built on it. */
	public Grid(int x0, int z0, int width, int depth, int[] height, boolean[] water) {
		this(x0, z0, width, depth, height, water, new boolean[width * depth]);
	}

	/** Whether the top block is not natural terrain there: something built (the player's, or an earlier build's). Lots never land on it. */
	public boolean builtAt(int x, int z) {
		return built[(z - z0) * width + (x - x0)];
	}

	public void setBuilt(int x, int z, boolean b) {
		built[(z - z0) * width + (x - x0)] = b;
	}

	/** Each cell's distance (8-neighbour steps) to the nearest water cell, or {@link Integer#MAX_VALUE} when the grid has no water. */
	public int[] waterDistance() {
		int[] d = new int[width * depth];
		java.util.Arrays.fill(d, Integer.MAX_VALUE);
		java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
		for (int k = 0; k < d.length; k++) if (water[k]) { d[k] = 0; q.add(k); }
		while (!q.isEmpty()) {
			int k = q.poll(), i = k % width, j = k / width;
			for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
				int ii = i + di, jj = j + dj;
				if (ii < 0 || jj < 0 || ii >= width || jj >= depth) continue;
				int n = jj * width + ii;
				if (d[n] > d[k] + 1) { d[n] = d[k] + 1; q.add(n); }
			}
		}
		return d;
	}

	public static Grid flat(int x0, int z0, int width, int depth, int y) {
		int[] h = new int[width * depth];
		java.util.Arrays.fill(h, y);
		return new Grid(x0, z0, width, depth, h, new boolean[width * depth]);
	}

	public boolean has(int x, int z) {
		return x >= x0 && z >= z0 && x < x0 + width && z < z0 + depth;
	}

	public int heightAt(int x, int z) {
		return height[(z - z0) * width + (x - x0)];
	}

	public boolean waterAt(int x, int z) {
		return water[(z - z0) * width + (x - x0)];
	}

	public void set(int x, int z, int y, boolean isWater) {
		height[(z - z0) * width + (x - x0)] = y;
		water[(z - z0) * width + (x - x0)] = isWater;
	}
}
