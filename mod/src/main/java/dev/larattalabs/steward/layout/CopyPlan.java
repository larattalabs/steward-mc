package dev.larattalabs.steward.layout;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which lots are $0 copies of another lot's design (Architect 0b, copies with a copyCap): lots of the same role, type and size, in order, form runs;
 * of each {@link #CAP} the first is an original (its archetype) and the rest copy it. Landmarks are never copied. The group request and the card's
 * estimate both use this, so they agree. Pure.
 */
public final class CopyPlan {
	/** Placements per design: an original and two copies (Architect's copyCap, Noah's default 2026-10-10). */
	public static final int CAP = 3;

	private CopyPlan() {
	}

	/** A lot as the rule reads it. */
	public record Spec(String id, String role, String type, int sizeX, int sizeZ, boolean landmark) {}

	/** Lot id to the lot id whose design it copies (only the copies are in the map). */
	public static Map<String, String> copies(List<Spec> lots) {
		Map<String, String> out = new LinkedHashMap<>();
		Map<String, Integer> seen = new HashMap<>();
		Map<String, String> archetype = new HashMap<>();
		for (Spec l : lots) {
			if (l.landmark()) continue;
			String key = l.role() + "|" + l.type() + "|" + l.sizeX() + "x" + l.sizeZ();
			int n = seen.merge(key, 1, Integer::sum) - 1;
			if (n % CAP == 0) archetype.put(key, l.id());
			else out.put(l.id(), archetype.get(key));
		}
		return out;
	}

	public static List<Spec> ofLots(List<VillageLayout.Lot> lots, java.util.Set<String> landmarkIds) {
		return lots.stream().map(l -> new Spec(l.id(), l.role(), l.type(), l.sizeX(), l.sizeZ(), l.landmark() || landmarkIds.contains(l.id()))).toList();
	}

	public static List<Spec> ofSpecs(List<VillageLayout.LotSpec> specs) {
		return specs.stream().map(l -> new Spec(l.id(), l.role(), l.type(), l.sizeX(), l.sizeZ(), l.landmark())).toList();
	}
}
