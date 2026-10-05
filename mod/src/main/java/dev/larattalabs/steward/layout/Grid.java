package dev.larattalabs.steward.layout;

/**
 * A terrain sample at 1-block resolution: ground height and a water mask over a rectangle. Pure data (built from Architect's
 * Survey.Sample, or by hand in tests). Cells outside the rectangle are unknown and count as unusable.
 */
public record Grid(int x0, int z0, int width, int depth, int[] height, boolean[] water) {
	public Grid {
		if (height.length != width * depth || water.length != width * depth) throw new IllegalArgumentException("grid arrays must be width*depth");
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
