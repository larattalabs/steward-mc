package dev.larattalabs.steward.model;

import java.util.Collection;
import java.util.List;

/**
 * How big a settlement's claim is (docs/PLAN.md "Claims and growth"): the card's size sets it when the settlement is described, and the player can grow it a
 * step at a time. A claim only ever grows (sites may stand anywhere in it), never past XL, and never onto another settlement's claim; it takes the largest
 * size that fits and says why it stopped. Pure.
 */
public final class ClaimRules {
	/** The sizes, as radii around the Founding Stone: S 97, M 129, L 193, XL 257 blocks across. */
	public static final List<String> SIZES = List.of("S", "M", "L", "XL");
	public static final int[] RADII = {48, 64, 96, 128};

	private ClaimRules() {
	}

	public static int radiusFor(String size) {
		int i = SIZES.indexOf(size == null ? "M" : size);
		return RADII[i < 0 ? 1 : i];
	}

	/** The size name of a radius, or "custom" when it is not one of the steps. */
	public static String sizeOf(int radius) {
		for (int i = 0; i < RADII.length; i++) if (RADII[i] == radius) return SIZES.get(i);
		return "custom";
	}

	public static int side(int radius) {
		return radius * 2 + 1;
	}

	/**
	 * @param radius the radius the claim ends with
	 * @param note what to tell the player ("" when it got exactly what was asked)
	 */
	public record Outcome(int radius, String note) {
		public boolean changed(Claim before) {
			return radius != before.radius();
		}
	}

	/**
	 * Grows {@code claim} towards {@code target} radius: the largest step up to the target that overlaps none of {@code others} (the other settlements'
	 * claims), never smaller than it is now.
	 */
	public static Outcome growTo(Claim claim, int target, Collection<Claim> others) {
		if (target <= claim.radius()) return new Outcome(claim.radius(), "");
		String firstBlocker = null;
		for (int r = target; r > claim.radius(); r = stepDown(r, claim.radius())) {
			Claim bigger = withRadius(claim, r);
			String blocker = others.stream().filter(bigger::overlaps).map(o -> "the settlement at " + o.centerX() + "," + o.centerZ()).findFirst().orElse(null);
			if (blocker == null) {
				return new Outcome(r, r == target ? "" : "The claim is " + side(r) + " x " + side(r) + " (" + sizeOf(r) + "): any larger would overlap " + firstBlocker + ".");
			}
			if (firstBlocker == null) firstBlocker = blocker;
		}
		return new Outcome(claim.radius(), "The claim stays " + side(claim.radius()) + " x " + side(claim.radius()) + ": a larger one would overlap " + firstBlocker + ".");
	}

	/** One step larger than now (to the next size), refused past XL. */
	public static Outcome expand(Claim claim, Collection<Claim> others) {
		int next = Integer.MAX_VALUE;
		for (int r : RADII) if (r > claim.radius()) next = Math.min(next, r);
		if (next == Integer.MAX_VALUE) return new Outcome(claim.radius(), "The claim is already the largest size (XL, " + side(claim.radius()) + " x " + side(claim.radius())
			+ "). Larger settlements grow by districts.");
		Outcome o = growTo(claim, next, others);
		return o.radius() == next ? new Outcome(next, "") : o;
	}

	/** The next smaller step between {@code r} and {@code floor} (the steps, or the floor itself). */
	private static int stepDown(int r, int floor) {
		int best = floor;
		for (int s : RADII) if (s < r && s > best) best = s;
		return best;
	}

	public static Claim withRadius(Claim c, int radius) {
		return new Claim(c.dimension(), c.centerX(), c.centerZ(), radius, c.minY(), c.maxY());
	}
}
