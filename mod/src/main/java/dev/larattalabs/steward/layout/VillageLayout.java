package dev.larattalabs.steward.layout;

import dev.larattalabs.steward.model.Claim;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase 1 layout: lots on both sides of one straight east-west main street, inside a claim, on ground that is dry and flat enough.
 * Deterministic and pure (no Minecraft types), so it is unit-tested. Each lot is a separate placement (no covering terrain site until
 * Architect's journal-backed sites exist; docs/PLAN.md phase 1). The street runs along x; lots north of it face south, lots south face north.
 *
 * <p>Larger or stranger layouts (rings, terraces, districts) are region programs (A5b), not this class.
 */
public final class VillageLayout {
	public enum Front { NORTH, SOUTH }

	/** A building the plan needs: its id, building type and footprint (the design's size cap is the caller's business). */
	public record LotSpec(String id, String type, int sizeX, int sizeZ) {}

	public record Lot(String id, String type, int x, int z, int sizeX, int sizeZ, Front front, int groundY) {
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
		Plan best = null;
		int bestScore = Integer.MIN_VALUE;
		int r = claim.radius() - rules.claimMargin();
		for (int dz = -r / 2; dz <= r / 2; dz += 4) {
			Plan p = tryStreet(claim, grid, specs, rules, claim.centerZ() + dz);
			// more lots first, then the least total slope across them (flatter is nicer), then closer to the claim centre
			int score = p.lots().size() * 100000 - slopeTotal(p, grid) * 10 - Math.abs(dz);
			if (score > bestScore) {
				bestScore = score;
				best = p;
			}
		}
		return best;
	}

	private static Plan tryStreet(Claim claim, Grid grid, List<LotSpec> specs, Rules rules, int streetZ) {
		int m = rules.claimMargin();
		int xMin = claim.centerX() - claim.radius() + m;
		int xMax = claim.centerX() + claim.radius() - m;
		int zMin = claim.centerZ() - claim.radius() + m;
		int zMax = claim.centerZ() + claim.radius() - m;
		int halfStreet = rules.streetWidth() / 2;
		List<Lot> placed = new ArrayList<>();
		List<LotSpec> left = new ArrayList<>(specs);
		// sides alternate; on each side the cursor moves outward from the centre, left and right in turn
		int[] cursorNorth = {claim.centerX(), claim.centerX() - 1};
		int[] cursorSouth = {claim.centerX(), claim.centerX() - 1};
		boolean north = true;
		boolean[] dir = {true, true};
		for (int guard = 0; guard < 4000 && !left.isEmpty(); guard++) {
			int[] cursor = north ? cursorNorth : cursorSouth;
			// try both directions on this side once before giving up on the side
			boolean placedOne = false;
			for (int t = 0; t < 2 && !placedOne; t++) {
				int idx = (guard + t) % 2;
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
				if (!g.has(xx, zz) || g.waterAt(xx, zz)) return null;
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
		return new Lot(s.id(), s.type(), x, z, s.sizeX(), s.sizeZ(), front, hs[hs.length / 2]);
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
