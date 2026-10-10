package dev.larattalabs.steward.gateway;

import dev.larattalabs.steward.layout.VillageLayout.LotSpec;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.ConceptCard.Building;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the card's building program into the lots the layout places: what this settlement needs, in its own terms, rather than a generic village. Pure.
 *
 * <p>Rules: honoured landmarks come first and are never dropped (at most {@code maxLandmarks}; further landmark flags count as ordinary buildings, since
 * landmarks run on the dearer model). The ordinary buildings are taken round-robin across the program's entries, one of each before a second of any, so a
 * smaller settlement keeps the program's mix instead of the first entry's whole count; a larger one repeats the ordinary entries. Lot depth includes
 * {@link LotBrief#APPROACH_MARGIN} (LotBrief takes it off again for the design, so the building plus its approach fit the lot).
 */
public final class ProgramPlanner {
	/** Design footprints (x, z) per program footprint. */
	public static final Map<String, int[]> FOOTPRINT = Map.of("S", new int[] {11, 9}, "M", new int[] {14, 12}, "L", new int[] {18, 15}, "XL", new int[] {24, 20});

	/** The generic mix for a card without a program (made before programs existed): type, x, z. */
	static final String[][] FALLBACK = {{"tavern", "22", "18"}, {"house", "14", "13"}, {"shop", "14", "12"}, {"cottage", "13", "12"}, {"smithy", "15", "13"}, {"house", "13", "12"},
		{"cabin", "12", "11"}, {"house", "14", "12"}, {"chapel", "14", "18"}, {"cottage", "12", "12"}, {"shop", "13", "12"}, {"house", "13", "13"}};

	/**
	 * @param specs the lots, landmarks first
	 * @param fromCard false when the card had no program and the generic mix was used
	 * @param leftOut roles of the program not built at this size, for the player
	 */
	public record Result(List<LotSpec> specs, boolean fromCard, List<String> leftOut) {
		public Set<String> landmarkIds() {
			Set<String> out = new LinkedHashSet<>();
			for (LotSpec s : specs) if (s.landmark()) out.add(s.id());
			return out;
		}
	}

	private ProgramPlanner() {
	}

	/** The program's own size: every entry's count (what {@code /steward start} suggests). */
	public static int total(ConceptCard c) {
		return c.hasProgram() ? c.program().stream().mapToInt(Building::count).sum() : FALLBACK.length;
	}

	public static Result lots(ConceptCard c, int buildings, int maxLandmarks) {
		if (buildings < 1) throw new IllegalArgumentException("buildings must be at least 1");
		Ids ids = new Ids();
		List<LotSpec> out = new ArrayList<>();
		if (!c.hasProgram()) {
			for (int i = 0; i < buildings; i++) {
				String[] a = FALLBACK[i % FALLBACK.length];
				out.add(new LotSpec(ids.next(a[0]), a[0], Integer.parseInt(a[1]), Integer.parseInt(a[2]) + LotBrief.APPROACH_MARGIN));
			}
			return new Result(List.copyOf(out), false, List.of());
		}
		List<Building> ordinary = new ArrayList<>();
		int landmarks = 0;
		for (Building b : c.program()) {
			if (b.landmark() && landmarks < maxLandmarks) {
				// a landmark entry is one building; any further count of it joins the ordinary buildings
				out.add(spec(ids, b, true));
				landmarks++;
				if (b.count() > 1) ordinary.add(new Building(b.role(), b.type(), b.count() - 1, b.footprint(), false, b.notes(), b.placement()));
			} else {
				ordinary.add(b);
			}
		}
		int slots = Math.max(0, buildings - out.size());
		Map<Building, Integer> taken = new java.util.IdentityHashMap<>();
		boolean padding = false;
		while (slots > 0 && !ordinary.isEmpty()) {
			boolean any = false;
			for (Building b : ordinary) {
				if (slots == 0) break;
				int n = taken.getOrDefault(b, 0);
				if (!padding && n >= b.count()) continue;
				out.add(spec(ids, b, false));
				taken.put(b, n + 1);
				slots--;
				any = true;
			}
			// every entry is used up to its count: a larger settlement repeats the ordinary entries
			if (!any) padding = true;
		}
		List<String> leftOut = new ArrayList<>();
		for (Building b : ordinary) {
			int missing = b.count() - taken.getOrDefault(b, 0);
			if (missing > 0) leftOut.add(missing == b.count() ? b.role() : b.role() + " (" + missing + " of " + b.count() + ")");
		}
		return new Result(List.copyOf(out), true, List.copyOf(leftOut));
	}

	private static LotSpec spec(Ids ids, Building b, boolean landmark) {
		int[] f = FOOTPRINT.getOrDefault(b.footprint(), FOOTPRINT.get("M"));
		return new LotSpec(ids.next(b.type()), b.type(), f[0], f[1] + LotBrief.APPROACH_MARGIN, b.role(), b.notes(), landmark, b.placement());
	}

	/** Readable, unique lot ids from the type ({@code slag_foundry_1}): the player types them in {@code /steward redirect}. */
	private static final class Ids {
		private final Map<String, Integer> n = new HashMap<>();

		String next(String type) {
			int i = n.merge(type, 1, Integer::sum);
			return type + "_" + i;
		}
	}
}
