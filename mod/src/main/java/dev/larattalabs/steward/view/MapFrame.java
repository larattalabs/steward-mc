package dev.larattalabs.steward.view;

/**
 * The settlement map's frame: the world area it draws (the layout's bounds, at least {@link #MIN_SPAN} blocks across) fitted into a pixel area, centred, one
 * scale for both axes (north up). Pure.
 *
 * @param cx the world x at the area's centre
 * @param cz the world z at the area's centre
 * @param scale pixels per block
 * @param ax the area's left pixel
 * @param ay the area's top pixel
 * @param aw the area's width in pixels
 * @param ah the area's height in pixels
 */
public record MapFrame(double cx, double cz, double scale, int ax, int ay, int aw, int ah) {
	/** The fewest blocks the frame spans, so a lone small building is not drawn wall to wall. */
	public static final int MIN_SPAN = 32;

	/** Fits the blocks {@code minX..maxX} by {@code minZ..maxZ} (inclusive) into the pixel area. */
	public static MapFrame fit(int minX, int minZ, int maxX, int maxZ, int ax, int ay, int aw, int ah) {
		double spanX = Math.max(MIN_SPAN, maxX + 1 - minX), spanZ = Math.max(MIN_SPAN, maxZ + 1 - minZ);
		double scale = Math.min(Math.max(1, aw) / spanX, Math.max(1, ah) / spanZ);
		return new MapFrame((minX + maxX + 1) / 2.0, (minZ + maxZ + 1) / 2.0, scale, ax, ay, aw, ah);
	}

	/** The pixel column of a world x (a block's west edge). */
	public int x(double wx) {
		return (int) Math.floor(ax + aw / 2.0 + (wx - cx) * scale);
	}

	/** The pixel row of a world z (a block's north edge). */
	public int z(double wz) {
		return (int) Math.floor(ay + ah / 2.0 + (wz - cz) * scale);
	}

	/** Whether a pixel rectangle lies wholly inside the area. */
	public boolean holds(int x0, int y0, int x1, int y1) {
		return x0 >= ax && y0 >= ay && x1 <= ax + aw && y1 <= ay + ah;
	}
}
