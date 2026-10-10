package dev.larattalabs.steward.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where a district goes and how its road runs (docs/PLAN.md phase 3f). Pure.
 *
 * <p>A district is a square next to the village square (the square around the stone a street is laid out in), one each way. East and west, it is centred on
 * the town street's line, so its own street continues the town's and the road between them is straight. North and south, the road leaves the town street's
 * east end, runs along it to the district, and enters it along its west edge; the district sits so that edge takes the road. Every road runs where
 * layouts leave ground free: past a street's end, in the margin of a claim, or along a street's line between its end and the claim's edge.
 */
public final class DistrictRules {
	public static final List<String> DIRECTIONS = List.of("N", "E", "S", "W");
	/** District sizes: S 97, M 129 blocks across. */
	public static final List<String> SIZES = List.of("S", "M");
	public static final int[] RADII = {48, 64};
	/** The roads' width. */
	public static final int ROAD_WIDTH = 3;
	/** A district's layout margin: the road along its edge (inner cells 1..3) stays clear of its lots and their approaches. */
	public static final int MARGIN = 5;

	private DistrictRules() {
	}

	public static int radiusFor(String size) {
		int i = SIZES.indexOf(size == null ? "S" : size.toUpperCase(java.util.Locale.ROOT));
		return RADII[i < 0 ? 0 : i];
	}

	public static String directionName(String dir) {
		return switch (dir) {
			case "N" -> "north";
			case "E" -> "east";
			case "S" -> "south";
			default -> "west";
		};
	}

	/** The square a one-street town is laid out in: its claim, at most {@link ClaimRules#VILLAGE_RADIUS} round the stone. */
	public static Claim villageSquare(Claim town) {
		return ClaimRules.withRadius(town, Math.min(ClaimRules.VILLAGE_RADIUS, town.radius()));
	}

	/** The town street's centre line (z) and its ends (x); null before the town has a street. */
	public record Street(int z, int x0, int x1) {}

	/**
	 * Where a district goes, or why it cannot.
	 *
	 * @param claim the district's square (null when refused)
	 * @param road the founding road's corners, from the town to the district's edge
	 * @param streetZ the line the district's street keeps to (east and west: the town street's), or null when free
	 * @param note why it was refused ("" when placed)
	 */
	public record Placement(@Nullable Claim claim, List<Settlement.Pt> road, @Nullable Integer streetZ, String note) {
		public boolean ok() {
			return claim != null;
		}

		static Placement refused(String note) {
			return new Placement(null, List.of(), null, note);
		}
	}

	/**
	 * Places a district of {@code radius} towards {@code dir} from {@code town}, with its founding road.
	 *
	 * @param street the town street, or null (the road then starts beside the stone)
	 * @param own the settlement's districts
	 * @param others the other settlements' claims and districts
	 * @param areas the settlement's protected areas (the road may not cross one)
	 */
	public static Placement place(Claim town, @Nullable Street street, List<Settlement.District> own, Collection<Claim> others, List<Settlement.Area> areas,
		String dir, int radius) {
		if (!DIRECTIONS.contains(dir)) return Placement.refused("A district goes north, east, south or west (N, E, S, W).");
		String name = directionName(dir);
		if (own.stream().anyMatch(d -> d.direction().equals(dir))) return Placement.refused("There is already a district to the " + name + ".");
		Claim v = villageSquare(town);
		int cx = town.centerX(), cz = town.centerZ(), rv = v.radius();
		// before there is a street, the road starts beside the stone
		Street s = street != null ? street : new Street(cz, cx - 2, cx + 2);
		int dcx, dcz;
		List<Settlement.Pt> road = new ArrayList<>();
		Integer streetZ = null;
		switch (dir) {
			case "E" -> {
				dcx = cx + rv + 1 + radius;
				dcz = s.z();
				road.add(new Settlement.Pt(s.x1() + 1, s.z()));
				road.add(new Settlement.Pt(dcx - radius + 1, s.z()));
				streetZ = s.z();
			}
			case "W" -> {
				dcx = cx - rv - 1 - radius;
				dcz = s.z();
				road.add(new Settlement.Pt(s.x0() - 1, s.z()));
				road.add(new Settlement.Pt(dcx + radius - 1, s.z()));
				streetZ = s.z();
			}
			default -> {
				// north or south: up the east side of the street's end (inside the village square), into the district along its west edge
				int xr = Math.min(s.x1() + 2, cx + rv - 1);
				dcx = xr - 2 + radius;
				boolean north = dir.equals("N");
				dcz = north ? cz - rv - 1 - radius : cz + rv + 1 + radius;
				int ez = north ? dcz + radius - 2 : dcz - radius + 2;
				road.add(new Settlement.Pt(s.x1() + 1, s.z()));
				if (xr != s.x1() + 1) road.add(new Settlement.Pt(xr, s.z()));
				road.add(new Settlement.Pt(xr, ez));
			}
		}
		Claim d = new Claim(town.dimension(), dcx, dcz, radius, town.minY(), town.maxY());
		if (d.overlaps(v)) return Placement.refused("The " + name + " district would overlap the town.");
		for (Settlement.District o : own) {
			if (d.overlaps(o.claim())) return Placement.refused("The " + name + " district would overlap the " + o.name() + " district.");
		}
		for (Claim o : others) {
			if (d.overlaps(o)) return Placement.refused("The " + name + " district would overlap the settlement at " + o.centerX() + "," + o.centerZ() + ".");
		}
		List<Claim> land = new ArrayList<>(List.of(town, d));
		for (Settlement.District o : own) land.add(o.claim());
		String why = roadProblem(road, land, areas);
		if (why != null) return Placement.refused(why);
		return new Placement(d, List.copyOf(road), streetZ, "");
	}

	/**
	 * The road linking a district's street to its edge (laid after the district's first build): from where the founding road ended, along the edge to the
	 * street's line, then along it to the street's nearer end.
	 */
	public static List<Settlement.Pt> link(Settlement.District d, Street street) {
		Settlement.Pt entry = d.roads().isEmpty() ? null : last(d.roads().get(0).points());
		if (entry == null) return List.of();
		List<Settlement.Pt> pts = new ArrayList<>();
		pts.add(entry);
		// the band ends against the street's first cell
		int endX = d.direction().equals("W") ? street.x1() + 2 : street.x0() - 2;
		if (entry.z() != street.z()) pts.add(new Settlement.Pt(entry.x(), street.z()));
		if (endX != pts.get(pts.size() - 1).x()) pts.add(new Settlement.Pt(endX, street.z()));
		return pts.size() < 2 ? List.of() : List.copyOf(pts);
	}

	/** Why a road along {@code pts} may not be laid (a cell outside the land, or on a protected area), or null. */
	public static @Nullable String roadProblem(List<Settlement.Pt> pts, List<Claim> land, List<Settlement.Area> areas) {
		String dim = land.get(0).dimension();
		for (int[] c : cells(pts, ROAD_WIDTH)) {
			if (land.stream().noneMatch(cl -> cl.contains(dim, c[0], cl.minY(), c[1]))) return "The road would leave the settlement's land at " + c[0] + "," + c[1] + ".";
			for (Settlement.Area a : areas) if (a.contains(dim, c[0], c[1])) return "The road would cross the protected area \"" + a.label() + "\".";
		}
		return null;
	}

	/** The cells of a road along axis-aligned legs between {@code pts}, {@code width} wide (centred; an even width adds a cell on one side). */
	public static List<int[]> cells(List<Settlement.Pt> pts, int width) {
		java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
		List<int[]> out = new ArrayList<>();
		int lo = -(width - 1) / 2, hi = width / 2;
		for (int i = 0; i + 1 < pts.size(); i++) {
			Settlement.Pt a = pts.get(i), b = pts.get(i + 1);
			int x = a.x(), z = a.z();
			while (true) {
				for (int dx = lo; dx <= hi; dx++) for (int dz = lo; dz <= hi; dz++) {
					long key = (((long) (x + dx)) << 32) ^ ((z + dz) & 0xffffffffL);
					if (seen.add(key)) out.add(new int[] {x + dx, z + dz});
				}
				if (x == b.x() && z == b.z()) break;
				if (x != b.x()) x += Integer.signum(b.x() - x);
				else z += Integer.signum(b.z() - z);
			}
		}
		return out;
	}

	private static Settlement.@Nullable Pt last(List<Settlement.Pt> pts) {
		return pts.isEmpty() ? null : pts.get(pts.size() - 1);
	}
}
