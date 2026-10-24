package dev.larattalabs.steward.model;

import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Columns of ground in one dimension, as row runs: each run is the columns {@code x0..x1} of row {@code z} (docs/DISTRICTS.md section 9). A square
 * district's land is its claim; a district region's is what Architect reports it took outside the town ({@code architect_mc:land.outside}) and the
 * reservation it was founded on. Steward's own geometry, never Architect's land object, so Architect's land format can change without a save format change.
 * Runs are kept sorted by row, then column, with no two in a row touching or overlapping. Pure.
 */
public record Land(String dimension, List<Run> runs) {
	/** Columns {@code x0..x1} (inclusive) of row {@code z}. */
	public record Run(int z, int x0, int x1) {
		public Run {
			if (x1 < x0) throw new JsonParseException("a land run ends before it starts (" + x0 + ".." + x1 + " at z " + z + ")");
			if (Math.abs(z) > WORLD || Math.abs(x0) > WORLD || Math.abs(x1) > WORLD) throw new JsonParseException("a land run lies outside the world (" + x0 + ".." + x1 + " at z " + z + ")");
			if ((long) x1 - x0 + 1 > MAX_SPAN) throw new JsonParseException("a land run is " + ((long) x1 - x0 + 1) + " columns wide (at most " + MAX_SPAN + ")");
		}
	}

	/** The bounding rectangle, inclusive. */
	public record Rect(int x0, int z0, int x1, int z1) {}

	/** A run list the store keeps from growing without bound (a district's land is at most a few hundred rows). */
	public static final int MAX_RUNS = 20_000;
	/** The widest run and the most columns land may hold (a claim is at most 4097 across), and the world's border. */
	public static final int MAX_SPAN = 8192;
	public static final long MAX_CELLS = 20_000_000L;
	static final int WORLD = 30_000_000;

	public Land {
		if (dimension == null || dimension.isBlank()) throw new JsonParseException("land needs a dimension");
		if (runs == null || runs.isEmpty()) throw new JsonParseException("land needs at least one run");
		if (runs.size() > MAX_RUNS) throw new JsonParseException("land has " + runs.size() + " runs (at most " + MAX_RUNS + ")");
		runs = normalise(runs);
		long n = 0;
		for (Run r : runs) n += (long) r.x1() - r.x0() + 1;
		if (n > MAX_CELLS) throw new JsonParseException("land holds " + n + " columns (at most " + MAX_CELLS + ")");
	}

	/** The columns of a claim. */
	public static Land of(Claim c) {
		int x0 = c.centerX() - c.radius(), x1 = c.centerX() + c.radius();
		List<Run> l = new ArrayList<>();
		for (int z = c.centerZ() - c.radius(); z <= c.centerZ() + c.radius(); z++) l.add(new Run(z, x0, x1));
		return new Land(c.dimension(), l);
	}

	/** The columns of a rectangle (inclusive, either corner order). */
	public static Land ofRect(String dimension, int ax, int az, int bx, int bz) {
		int x0 = Math.min(ax, bx), x1 = Math.max(ax, bx);
		if ((long) Math.max(az, bz) - Math.min(az, bz) + 1 > MAX_SPAN) throw new IllegalArgumentException("a rectangle " + ((long) Math.max(az, bz) - Math.min(az, bz) + 1) + " rows deep");
		List<Run> l = new ArrayList<>();
		for (int z = Math.min(az, bz); z <= Math.max(az, bz); z++) l.add(new Run(z, x0, x1));
		return new Land(dimension, l);
	}

	public Rect rect() {
		int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
		for (Run r : runs) {
			x0 = Math.min(x0, r.x0());
			x1 = Math.max(x1, r.x1());
		}
		return new Rect(x0, runs.get(0).z(), x1, runs.get(runs.size() - 1).z());
	}

	public long cells() {
		long n = 0;
		for (Run r : runs) n += (long) r.x1() - r.x0() + 1;
		return n;
	}

	public boolean contains(String dim, int x, int z) {
		if (!dimension.equals(dim)) return false;
		int i = firstRow(z);
		for (; i < runs.size() && runs.get(i).z() == z; i++) {
			Run r = runs.get(i);
			if (x >= r.x0() && x <= r.x1()) return true;
		}
		return false;
	}

	/** Whether every column of the box lies in this land. */
	public boolean containsBox(String dim, int ax, int az, int bx, int bz) {
		if (!dimension.equals(dim)) return false;
		int x0 = Math.min(ax, bx), x1 = Math.max(ax, bx);
		for (int z = Math.min(az, bz); z <= Math.max(az, bz); z++) {
			boolean covered = false;
			for (int i = firstRow(z); i < runs.size() && runs.get(i).z() == z; i++) {
				// runs in a row never touch, so the box's span must sit inside one of them
				if (runs.get(i).x0() <= x0 && runs.get(i).x1() >= x1) covered = true;
			}
			if (!covered) return false;
		}
		return true;
	}

	/** Whether the two share a column. */
	public boolean overlaps(Land o) {
		if (!dimension.equals(o.dimension)) return false;
		Rect a = rect(), b = o.rect();
		if (a.x1() < b.x0() || b.x1() < a.x0() || a.z1() < b.z0() || b.z1() < a.z0()) return false;
		int i = 0, j = 0;
		while (i < runs.size() && j < o.runs.size()) {
			Run p = runs.get(i), q = o.runs.get(j);
			if (p.z() < q.z() || (p.z() == q.z() && p.x1() < q.x0())) {
				i++;
			} else if (q.z() < p.z() || q.x1() < p.x0()) {
				j++;
			} else {
				return true;
			}
		}
		return false;
	}

	public boolean overlaps(Claim c) {
		return overlaps(of(c));
	}

	/** Both lands' columns. */
	public Land union(Land o) {
		if (!dimension.equals(o.dimension)) throw new IllegalArgumentException("land in two dimensions");
		List<Run> l = new ArrayList<>(runs);
		l.addAll(o.runs);
		return new Land(dimension, l);
	}

	/** This land's columns that {@code o} doesn't hold; null when none are left. */
	public @org.jspecify.annotations.Nullable Land minus(Land o) {
		if (!overlaps(o)) return this;
		List<Run> out = new ArrayList<>();
		for (Run r : runs) {
			int from = r.x0();
			for (int i = o.firstRow(r.z()); i < o.runs.size() && o.runs.get(i).z() == r.z(); i++) {
				Run q = o.runs.get(i);
				if (q.x1() < from || q.x0() > r.x1()) continue;
				if (q.x0() > from) out.add(new Run(r.z(), from, q.x0() - 1));
				from = Math.max(from, q.x1() + 1);
			}
			if (from <= r.x1()) out.add(new Run(r.z(), from, r.x1()));
		}
		return out.isEmpty() ? null : new Land(dimension, out);
	}

	private int firstRow(int z) {
		int lo = 0, hi = runs.size();
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (runs.get(mid).z() < z) lo = mid + 1;
			else hi = mid;
		}
		return lo;
	}

	private static List<Run> normalise(List<Run> in) {
		List<Run> sorted = new ArrayList<>(in.size());
		for (Run r : in) {
			if (r == null) throw new JsonParseException("land has an empty run");
			sorted.add(r);
		}
		sorted.sort(Comparator.comparingInt(Run::z).thenComparingInt(Run::x0));
		List<Run> out = new ArrayList<>(sorted.size());
		for (Run r : sorted) {
			Run last = out.isEmpty() ? null : out.get(out.size() - 1);
			// same row, overlapping or touching: one run (long math: x1 may be Integer.MAX_VALUE)
			if (last != null && last.z() == r.z() && (long) r.x0() <= (long) last.x1() + 1) {
				out.set(out.size() - 1, new Run(last.z(), last.x0(), Math.max(last.x1(), r.x1())));
			} else {
				out.add(r);
			}
		}
		return List.copyOf(out);
	}
}
