package dev.larattalabs.steward.layout;

import dev.larattalabs.steward.model.Claim;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Phase 1 layout: lots on both sides of one straight east-west main street, inside a claim, on ground that is dry and flat enough.
 * Deterministic and pure (no Minecraft types), so it is unit-tested. Each lot is a separate placement (no covering terrain site until
 * Architect's journal-backed sites exist; docs/PLAN.md phase 1). The street runs along x; lots north of it face south, lots south face north.
 *
 * <p>Larger or stranger layouts (rings, terraces, districts) are region programs (A5b), not this class.
 */
public final class VillageLayout {
	public enum Front { NORTH, SOUTH }

	/**
	 * A building the plan needs: its id, building type and lot size (the design's size cap is the caller's business), plus what it is in the settlement's
	 * own terms ({@code role}, {@code notes}) and whether it is a landmark (both from the card's program; a spec without one is its type and not a landmark).
	 */
	public record LotSpec(String id, String type, int sizeX, int sizeZ, String role, @Nullable String notes, boolean landmark, @Nullable String placement) {
		public LotSpec {
			role = role == null ? type : role;
		}

		public LotSpec(String id, String type, int sizeX, int sizeZ) {
			this(id, type, sizeX, sizeZ, type, null, false, null);
		}

		public LotSpec(String id, String type, int sizeX, int sizeZ, String role, @Nullable String notes, boolean landmark) {
			this(id, type, sizeX, sizeZ, role, notes, landmark, null);
		}
	}

	public record Lot(String id, String type, int x, int z, int sizeX, int sizeZ, Front front, int groundY, String role, @Nullable String notes, boolean landmark,
		@Nullable String placement) {
		/** Saved lots without a role (written before roles existed) take their type. */
		public Lot {
			role = role == null ? type : role;
		}

		public Lot(String id, String type, int x, int z, int sizeX, int sizeZ, Front front, int groundY) {
			this(id, type, x, z, sizeX, sizeZ, front, groundY, type, null, false, null);
		}

		public Lot(String id, String type, int x, int z, int sizeX, int sizeZ, Front front, int groundY, String role, @Nullable String notes, boolean landmark) {
			this(id, type, x, z, sizeX, sizeZ, front, groundY, role, notes, landmark, null);
		}

		public int maxX() { return x + sizeX - 1; }
		public int maxZ() { return z + sizeZ - 1; }
	}

	public record Plan(List<Lot> lots, int streetZ, int streetX0, int streetX1, int streetY, List<LotSpec> unplaced) {}

	public record Rules(int streetWidth, int lotGap, int maxSlope, int claimMargin) {
		public static Rules defaults() {
			// street 5 wide in the layout (the road itself is 3: a one-block verge each side so lot approaches stop clear of it)
			return new Rules(5, 3, 3, 2);
		}
	}

	private VillageLayout() {
	}

	public static Plan plan(Claim claim, Grid grid, List<LotSpec> specs, Rules rules) {
		return plan(claim, grid, specs, rules, null);
	}

	/** As {@link #plan(Claim, Grid, List, Rules)}, on the given street ({@code streetZ}) when there is one: an addition faces the settlement's own street. */
	public static Plan plan(Claim claim, Grid grid, List<LotSpec> specs, Rules rules, @Nullable Integer streetZ) {
		return plan(claim, grid, specs, rules, streetZ, Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	/** As above, with lots kept between {@code xLo} and {@code xHi}: an addition stays along its street, which it does not extend. */
	public static Plan plan(Claim claim, Grid grid, List<LotSpec> specs, Rules rules, @Nullable Integer streetZ, int xLo, int xHi) {
		// placement hints order the specs: the street fills from its middle outward, so "central" goes first and "edge" last
		List<LotSpec> ordered = new ArrayList<>(specs);
		ordered.sort(Comparator.comparingInt(VillageLayout::orderOf));
		int[] waterDist = ordered.stream().anyMatch(sp -> "near_water".equals(sp.placement())) ? grid.waterDistance() : null;
		Plan best = null;
		long bestScore = Long.MIN_VALUE;
		int r = claim.radius() - rules.claimMargin();
		int dz0 = streetZ == null ? -r / 2 : streetZ - claim.centerZ(), dz1 = streetZ == null ? r / 2 : dz0;
		for (int dz = dz0; dz <= dz1; dz += 4) {
			for (boolean northFirst : new boolean[] {true, false}) {
				for (boolean eastFirst : new boolean[] {true, false}) {
					Plan p = tryStreetSkipping(claim, grid, ordered, rules, claim.centerZ() + dz, northFirst, eastFirst, xLo, xHi);
					// more lots first, then how well the lots meet their placement hints, then the least slope, then closer to the claim centre
					long score = p.lots().size() * 1_000_000L + hintScore(p, grid, claim, waterDist) - slopeTotal(p, grid) * 10L - Math.abs(dz);
					if (score > bestScore) {
						bestScore = score;
						best = p;
					}
				}
			}
		}
		return best;
	}

	private static int orderOf(LotSpec s) {
		if ("central".equals(s.placement())) return 0;
		if (s.landmark()) return 1;
		if ("edge".equals(s.placement())) return 3;
		return 2;
	}

	/**
	 * How well a plan's lots meet their placement hints (higher is better): near_water close to water (the survey's water mask), central close to the claim's
	 * centre, edge far from it, high_ground above the plan's mean ground. Lots without a hint add nothing.
	 */
	static long hintScore(Plan p, Grid g, Claim claim, int[] waterDist) {
		if (p.lots().isEmpty()) return 0;
		double meanY = p.lots().stream().mapToInt(Lot::groundY).average().orElse(0);
		long score = 0;
		for (Lot l : p.lots()) {
			if (l.placement() == null) continue;
			int cx = (l.x() + l.maxX()) / 2, cz = (l.z() + l.maxZ()) / 2;
			double toCentre = Math.hypot(cx - claim.centerX(), cz - claim.centerZ());
			switch (l.placement()) {
				case "near_water" -> {
					int best = Integer.MAX_VALUE;
					if (waterDist != null) {
						for (int x = l.x(); x <= l.maxX(); x++) for (int z = l.z(); z <= l.maxZ(); z++) {
							if (g.has(x, z)) best = Math.min(best, waterDist[(z - g.z0()) * g.width() + (x - g.x0())]);
						}
					}
					score -= best == Integer.MAX_VALUE ? 2000 : Math.min(best, 60) * 40L;
				}
				case "central" -> score -= (long) (toCentre * 20);
				case "edge" -> score += (long) (toCentre * 10);
				case "high_ground" -> score += (long) ((l.groundY() - meanY) * 60);
				default -> {
				}
			}
		}
		return score;
	}

	/**
	 * {@link #tryStreet}, and when a lot fits nowhere (the street fills in order, so it would hold up every lot after it), again without it: the lot is left
	 * out and the rest are laid out. Usually the first try places everything and nothing is repeated.
	 */
	/** How many lots in a row may be dropped without the street gaining one before skipping stops (each drop re-lays the street). */
	private static final int MAX_STALE_DROPS = 3;

	private static Plan tryStreetSkipping(Claim claim, Grid grid, List<LotSpec> specs, Rules rules, int streetZ, boolean northFirst, boolean eastFirst, int xLo, int xHi) {
		List<LotSpec> use = new ArrayList<>(specs);
		List<LotSpec> dropped = new ArrayList<>();
		Plan best = tryStreet(claim, grid, use, rules, streetZ, northFirst, eastFirst, xLo, xHi);
		Plan p = best;
		int stale = 0;
		while (!p.unplaced().isEmpty() && use.size() > 1) {
			use.remove(p.unplaced().get(0));
			dropped.add(p.unplaced().get(0));
			int before = p.lots().size();
			p = tryStreet(claim, grid, use, rules, streetZ, northFirst, eastFirst, xLo, xHi);
			// no better for several drops in a row: the street is simply full, not held up (one or two blockers in a row are skipped)
			if (p.lots().size() > before) stale = 0;
			else if (++stale >= MAX_STALE_DROPS) break;
			if (p.lots().size() > best.lots().size()) {
				List<LotSpec> out = new ArrayList<>(p.unplaced());
				out.addAll(dropped);
				best = new Plan(p.lots(), p.streetZ(), p.streetX0(), p.streetX1(), p.streetY(), List.copyOf(out));
			}
		}
		return best;
	}

	private static Plan tryStreet(Claim claim, Grid grid, List<LotSpec> specs, Rules rules, int streetZ, boolean northFirst, boolean eastFirst, int xLo, int xHi) {
		int m = rules.claimMargin();
		int xMin = Math.max(xLo, claim.centerX() - claim.radius() + m);
		int xMax = Math.min(xHi, claim.centerX() + claim.radius() - m);
		int zMin = claim.centerZ() - claim.radius() + m;
		int zMax = claim.centerZ() + claim.radius() - m;
		int halfStreet = rules.streetWidth() / 2;
		List<Lot> placed = new ArrayList<>();
		List<LotSpec> left = new ArrayList<>(specs);
		// sides alternate; on each side the cursor moves outward from the centre, left and right in turn
		// from the claim's centre, or the nearest point of the allowed stretch when the centre lies outside it (an addition's street)
		int start = Math.max(xMin, Math.min(xMax, claim.centerX()));
		int[] cursorNorth = {start, start - 1};
		int[] cursorSouth = {start, start - 1};
		boolean north = northFirst;
		boolean[] dir = {true, true};
		for (int guard = 0; guard < 4000 && !left.isEmpty(); guard++) {
			int[] cursor = north ? cursorNorth : cursorSouth;
			// try both directions on this side once before giving up on the side
			boolean placedOne = false;
			for (int t = 0; t < 2 && !placedOne; t++) {
				int idx = (guard + t + (eastFirst ? 0 : 1)) % 2;
				boolean east = idx == 0;
				LotSpec s = left.get(0);
				int x = east ? cursor[0] : cursor[1] - s.sizeX() + 1;
				int z = north ? streetZ - halfStreet - 1 - s.sizeZ() + 1 : streetZ + halfStreet + 1;
				if (x < xMin || x + s.sizeX() - 1 > xMax || z < zMin || z + s.sizeZ() - 1 > zMax) {
					continue;
				}
				Lot lot = fit(grid, rules, s, x, z, north ? Front.SOUTH : Front.NORTH, placed);
				if (lot != null) {
					placed.add(lot);
					left.remove(0);
					if (east) cursor[0] = x + s.sizeX() + rules.lotGap();
					else cursor[1] = x - rules.lotGap() - 1;
					placedOne = true;
				} else if (east) {
					cursor[0] += 2; // slide past an unusable spot
				} else {
					cursor[1] -= 2;
				}
			}
			north = !north;
			if (!anyRoom(cursorNorth, cursorSouth, xMin, xMax)) break;
		}
		int ground = placed.isEmpty() ? 0 : placed.get(0).groundY();
		int x0 = placed.stream().mapToInt(Lot::x).min().orElse(claim.centerX());
		int x1 = placed.stream().mapToInt(Lot::maxX).max().orElse(claim.centerX());
		return new Plan(List.copyOf(placed), streetZ, x0, x1, ground, List.copyOf(left));
	}

	private static boolean anyRoom(int[] n, int[] s, int xMin, int xMax) {
		return n[0] <= xMax || n[1] >= xMin || s[0] <= xMax || s[1] >= xMin;
	}

	/** A lot at (x, z) if its footprint is known, dry, flat enough and clear of the others; else null. */
	private static Lot fit(Grid g, Rules rules, LotSpec s, int x, int z, Front front, List<Lot> placed) {
		int lo = Integer.MAX_VALUE;
		int hi = Integer.MIN_VALUE;
		int sum = 0;
		for (int xx = x; xx < x + s.sizeX(); xx++) {
			for (int zz = z; zz < z + s.sizeZ(); zz++) {
				if (!g.has(xx, zz) || g.waterAt(xx, zz) || g.builtAt(xx, zz)) return null;
				int h = g.heightAt(xx, zz);
				lo = Math.min(lo, h);
				hi = Math.max(hi, h);
				sum += h;
			}
		}
		if (hi - lo > rules.maxSlope()) return null;
		int gap = rules.lotGap();
		for (Lot o : placed) {
			if (x <= o.maxX() + gap && x + s.sizeX() - 1 >= o.x() - gap && z <= o.maxZ() + gap && z + s.sizeZ() - 1 >= o.z() - gap) return null;
		}
		int[] hs = new int[s.sizeX() * s.sizeZ()];
		int i = 0;
		for (int xx = x; xx < x + s.sizeX(); xx++) for (int zz = z; zz < z + s.sizeZ(); zz++) hs[i++] = g.heightAt(xx, zz);
		java.util.Arrays.sort(hs);
		return new Lot(s.id(), s.type(), x, z, s.sizeX(), s.sizeZ(), front, hs[hs.length / 2], s.role(), s.notes(), s.landmark(), s.placement());
	}

	private static int slopeTotal(Plan p, Grid g) {
		int total = 0;
		for (Lot l : p.lots()) {
			int lo = Integer.MAX_VALUE;
			int hi = Integer.MIN_VALUE;
			for (int x = l.x(); x <= l.maxX(); x++) {
				for (int z = l.z(); z <= l.maxZ(); z++) {
					lo = Math.min(lo, g.heightAt(x, z));
					hi = Math.max(hi, g.heightAt(x, z));
				}
			}
			total += hi - lo;
		}
		return total;
	}

	/** The street's cells (x, z) from the first to the last lot, {@code width} wide, for road placement. */
	public static List<int[]> streetCells(Plan p, int width) {
		List<int[]> cells = new ArrayList<>();
		int half = width / 2;
		for (int x = p.streetX0(); x <= p.streetX1(); x++) {
			for (int dz = -half; dz <= half; dz++) cells.add(new int[] {x, p.streetZ() + dz});
		}
		cells.sort(Comparator.<int[]>comparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
		return cells;
	}
}
