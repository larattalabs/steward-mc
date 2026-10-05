package dev.larattalabs.steward.model;

/**
 * The area a settlement may touch: a cylinder-ish square around a centre (the Founding Stone). Everything the steward writes must
 * lie inside it (A5b rule M1, enforced at write time by the mod, not trusted to the checker).
 */
public record Claim(String dimension, int centerX, int centerZ, int radius, int minY, int maxY) {
	public Claim {
		if (radius < 8 || radius > 2048) throw new IllegalArgumentException("radius must be 8..2048: " + radius);
		if (minY >= maxY) throw new IllegalArgumentException("minY must be below maxY");
	}

	public boolean contains(String dim, int x, int y, int z) {
		return dimension.equals(dim) && y >= minY && y <= maxY && Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius;
	}

	/** Whether a whole box lies inside the claim. */
	public boolean containsBox(String dim, int x0, int y0, int z0, int x1, int y1, int z1) {
		return contains(dim, Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1)) && contains(dim, Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1));
	}

	public boolean overlaps(Claim o) {
		return dimension.equals(o.dimension) && Math.abs(centerX - o.centerX) <= radius + o.radius && Math.abs(centerZ - o.centerZ) <= radius + o.radius
			&& minY <= o.maxY && o.minY <= maxY;
	}
}
