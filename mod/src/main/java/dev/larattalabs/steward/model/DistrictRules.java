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
 * east end (its west end when the east is too near the claim's edge), runs past it to the district, and enters it along its west (east) edge; the district
 * sits so that edge takes the road. Every road runs where layouts leave ground free: past a street's end (lots never reach past it), in a district's wider
 * margin, or along a street's line between its end and the claim's edge. The roads have no lanterns, so what they write is the band {@link #cells} gives.
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

	/** How far a beside district's request may reach into the town region's claim (Architect D1/D3's seam). */
	public static final int SEAM = 16;

	/**
	 * A beside district's reservation (docs/DISTRICTS.md section 9), or why not.
	 *
	 * @param claim the side's square just beyond the settlement's claim: the land it owns (null when refused)
	 * @param request the claim Architect plans in ({@code x0, z0, x1, z1}): the reservation and the way back into the town region's claim, overlapping it by
	 *     {@link #SEAM} columns. A search frame, never owned.
	 * @param note why it was refused ("" when reserved)
	 */
	public record Reservation(@Nullable Claim claim, int[] request, String note) {
		public boolean ok() {
			return claim != null;
		}

		static Reservation refused(String why) {
			return new Reservation(null, new int[0], why);
		}
	}

	/**
	 * Where a beside district is reserved towards {@code dir} (east or west of a region town; north and south are square districts, Noah's Q3).
	 * {@code region} is the town region's claim ({@code x0, z0, x1, z1}), which may be smaller than the settlement's. Refused over another district, another
	 * settlement or a protected area. Pure.
	 */
	public static Reservation reserve(Claim town, int[] region, List<Settlement.District> own, List<Claim> others, List<Settlement.Area> areas, String dir,
		int radius, String name) {
		if (!dir.equals("E") && !dir.equals("W")) return Reservation.refused("A district north or south of this town is a square district.");
		boolean east = dir.equals("E");
		for (Settlement.District o : own) {
			if (o.direction().equals(dir)) return Reservation.refused("There is a district to the " + directionName(dir) + " already (" + o.name() + ").");
		}
		int cx = east ? town.centerX() + town.radius() + 1 + radius : town.centerX() - town.radius() - 1 - radius;
		Claim d = new Claim(town.dimension(), cx, town.centerZ(), radius, town.minY(), town.maxY());
		Land land = Land.of(d);
		int[] req = request(region, d, dir);
		// Architect may place the district anywhere in the request's claim: none of it may be another district's
		Land envelope = Land.ofRect(town.dimension(), req[0], req[1], req[2], req[3]);
		for (Settlement.District o : own) {
			if (o.land().overlaps(land) || o.land().overlaps(envelope)) return Reservation.refused("The " + name + " district would overlap the " + o.name() + " district.");
			// a beside district not built yet may still be placed anywhere in its own request's claim: the new one keeps out of that too
			if (Settlement.District.BESIDE.equals(o.kind()) && !Settlement.District.STANDING.equals(o.state())) {
				int[] theirs = request(region, o.claim(), o.direction());
				if (land.overlaps(Land.ofRect(town.dimension(), theirs[0], theirs[1], theirs[2], theirs[3]))) {
					return Reservation.refused("The " + name + " district would reach into the ground kept for " + o.name() + ".");
				}
			}
		}
		for (Claim o : others) {
			if (land.overlaps(o)) return Reservation.refused("The " + name + " district would overlap the settlement at " + o.centerX() + "," + o.centerZ() + ".");
		}
		for (Settlement.Area a : areas) {
			if (land.overlaps(Land.ofRect(a.dimension(), a.x0(), a.z0(), a.x1(), a.z1()))) {
				return Reservation.refused("The " + name + " district would cover the protected area \"" + a.label() + "\".");
			}
		}
		return new Reservation(d, req, "");
	}

	/** How far beyond the settlement's claim a quarter may be placed: an along district's band anywhere in it, an outpost its gap and its own ground. */
	public static final int ALONG_DEPTH = 96, OUTPOST_DEPTH = 112;

	/**
	 * Where a quarter towards {@code dir} may go (Noah's Q2; any side, its join may meet the town's street at its side): the strip {@code depth} deep beyond
	 * the settlement's claim along that whole edge. {@code claim} is the strip's bounding square, {@code request} the strip and the way back into the town
	 * region's claim by {@link #SEAM}, and {@code land} the strip, which the district holds while it is built (Architect's {@code land.outside} replaces
	 * it once it stands). Refused over another district's land, another settlement, or a protected area. Pure.
	 */
	public record QuarterPlace(@Nullable Claim claim, @Nullable Land land, int[] request, String note) {
		public boolean ok() {
			return claim != null;
		}
	}

	public static QuarterPlace quarter(Claim town, int[] region, List<Settlement.District> own, List<Claim> others, List<Settlement.Area> areas, String dir,
		int depth, String name) {
		for (Settlement.District o : own) {
			if (o.direction().equals(dir)) return new QuarterPlace(null, null, new int[0], "There is a district to the " + directionName(dir) + " already (" + o.name() + ").");
		}
		int x0 = town.centerX() - town.radius(), x1 = town.centerX() + town.radius(), z0 = town.centerZ() - town.radius(), z1 = town.centerZ() + town.radius();
		int[] strip;
		int[] req;
		switch (dir) {
			case "E" -> { strip = new int[] {x1 + 1, z0, x1 + depth, z1}; req = new int[] {region[2] - (SEAM - 1), z0, x1 + depth, z1}; }
			case "W" -> { strip = new int[] {x0 - depth, z0, x0 - 1, z1}; req = new int[] {x0 - depth, z0, region[0] + (SEAM - 1), z1}; }
			case "N" -> { strip = new int[] {x0, z0 - depth, x1, z0 - 1}; req = new int[] {x0, z0 - depth, x1, region[1] + (SEAM - 1)}; }
			default -> { strip = new int[] {x0, z1 + 1, x1, z1 + depth}; req = new int[] {x0, region[3] - (SEAM - 1), x1, z1 + depth}; }
		}
		Land land = Land.ofRect(town.dimension(), strip[0], strip[1], strip[2], strip[3]);
		for (Settlement.District o : own) {
			if (o.land().overlaps(land)) return new QuarterPlace(null, null, new int[0], "The " + name + " would overlap the " + o.name() + " district.");
		}
		for (Claim o : others) {
			if (land.overlaps(o)) return new QuarterPlace(null, null, new int[0], "The " + name + " would overlap the settlement at " + o.centerX() + "," + o.centerZ() + ".");
		}
		for (Settlement.Area a : areas) {
			if (land.overlaps(Land.ofRect(a.dimension(), a.x0(), a.z0(), a.x1(), a.z1()))) {
				return new QuarterPlace(null, null, new int[0], "The " + name + " would cover the protected area \"" + a.label() + "\".");
			}
		}
		int half = Math.max(strip[2] - strip[0], strip[3] - strip[1]) / 2;
		Claim c = new Claim(town.dimension(), (strip[0] + strip[2]) / 2, (strip[1] + strip[3]) / 2, Math.max(8, half + 1), town.minY(), town.maxY());
		return new QuarterPlace(c, land, req, "");
	}

	/** The plan request's claim for a reservation {@code d} towards {@code dir}: the reservation and the way back into the town region's claim by {@link #SEAM}. */
	public static int[] request(int[] region, Claim d, String dir) {
		boolean east = dir.equals("E");
		int x0 = east ? region[2] - (SEAM - 1) : d.centerX() - d.radius();
		int x1 = east ? d.centerX() + d.radius() : region[0] + (SEAM - 1);
		return new int[] {x0, d.centerZ() - d.radius(), x1, d.centerZ() + d.radius()};
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
				// north or south: past the street's east end (else its west end), inside the town's claim, then into the district along its west (east) edge.
				// Lots never reach past a street's ends, so the road clears them; it is never pulled in over a lot to fit
				boolean eastSide = s.x1() + 3 <= cx + town.radius();
				if (!eastSide && s.x0() - 3 < cx - town.radius()) return Placement.refused("The town street runs to both edges of the claim: there is no way round it to the "
					+ name + ". Expand the claim first.");
				int xr = eastSide ? s.x1() + 2 : s.x0() - 2;
				dcx = eastSide ? xr - 2 + radius : xr + 2 - radius;
				boolean north = dir.equals("N");
				dcz = north ? cz - rv - 1 - radius : cz + rv + 1 + radius;
				int ez = north ? dcz + radius - 2 : dcz - radius + 2;
				road.add(new Settlement.Pt(eastSide ? s.x1() + 1 : s.x0() - 1, s.z()));
				road.add(new Settlement.Pt(xr, s.z()));
				road.add(new Settlement.Pt(xr, ez));
			}
		}
		Claim d = new Claim(town.dimension(), dcx, dcz, radius, town.minY(), town.maxY());
		if (d.overlaps(v)) return Placement.refused("The " + name + " district would overlap the town.");
		for (Settlement.District o : own) {
			if (o.land().overlaps(d)) return Placement.refused("The " + name + " district would overlap the " + o.name() + " district.");
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
	 * street's line, then along it to the street's nearer end (the column next to it: Architect does not pave past a line's end).
	 */
	public static List<Settlement.Pt> link(Settlement.District d, Street street) {
		Settlement.Pt entry = d.founding().map(r -> last(r.points())).orElse(null);
		if (entry == null) return List.of();
		List<Settlement.Pt> pts = new ArrayList<>();
		pts.add(entry);
		// entered on its west side: the street's west end; on its east side: its east end
		int endX = entry.x() < d.claim().centerX() ? street.x0() - 1 : street.x1() + 1;
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
