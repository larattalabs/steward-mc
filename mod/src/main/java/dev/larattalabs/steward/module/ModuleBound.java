package dev.larattalabs.steward.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The survival bill's upper bound (docs/MODULES.md 4.4, 4.2 step 5, section 6). Pure.
 *
 * <ul>
 * <li><b>The bound the player approves</b> at Accept is the measured bound for the module's params: measured.json {@code bounds}, a list of
 * {@code {params, bom}}, where {@code bom} (item id -> count) covers the whole growth (the module's own blocks plus the growth's lanes, lamps and
 * irrigation shells), recorded from the measurement runs. A module with no bound for its params is not offered in survival: there is nothing to
 * show the player, nor to hold the build to.</li>
 * <li><b>The quote</b> (Architect's {@code Regions.quote}, S-M5-7) is compared with that bound before anything is consumed: any item over it, or
 * an item the bound does not name, holds the build and asks again ({@link #over}).</li>
 * <li><b>Q4</b> (Noah, 2026-11-12): an Autonomous build also asks first when the whole bill takes more than half of the stockpile's count of any
 * item ({@link #heavy}).</li>
 * </ul>
 */
public final class ModuleBound {
	private ModuleBound() {}

	/** The measured.json member that holds the bounds. */
	public static final String MEMBER = "bounds";

	/** The measured bound for these params (canonical), or null when the measurement recorded none for them. */
	public static @Nullable JsonObject of(ModuleCatalog.@Nullable Measured m, JsonObject params) {
		if (m == null || m.raw() == null || !m.raw().has(MEMBER) || !m.raw().get(MEMBER).isJsonArray()) return null;
		JsonElement want = Canon.sorted(params);
		for (JsonElement b : m.raw().getAsJsonArray(MEMBER)) {
			if (!b.isJsonObject()) continue;
			JsonObject o = b.getAsJsonObject();
			if (!o.has("params") || !o.has("bom") || !o.get("bom").isJsonObject()) continue;
			if (!Canon.sorted(o.get("params")).equals(want)) continue;
			JsonObject bom = clean(o.getAsJsonObject("bom"));
			return bom;
		}
		return null;
	}

	/** A bill as item -> count: only positive whole counts kept, sorted by id; anything else makes the whole bill unreadable (null). */
	public static @Nullable JsonObject clean(@Nullable JsonObject bom) {
		if (bom == null) return null;
		Map<String, Integer> out = new TreeMap<>();
		for (var e : bom.entrySet()) {
			Integer n = ModuleRef.exactInt(e.getValue());
			if (n == null || n < 0) return null;
			if (n > 0) out.put(e.getKey(), n);
		}
		JsonObject o = new JsonObject();
		out.forEach(o::addProperty);
		return o;
	}

	/** One item past its bound: how many the quote asks, and how many the bound allows (0: the bound does not name it). */
	public record Over(String item, int quoted, int bound) {}

	/** Every item the quote asks for past the approved bound (an item the bound leaves out counts as a bound of 0). Empty: within the bound. */
	public static List<Over> over(JsonObject quote, JsonObject bound) {
		List<Over> out = new ArrayList<>();
		for (var e : new TreeMap<>(quote.asMap()).entrySet()) {
			int q = count(e.getValue());
			int b = bound.has(e.getKey()) ? count(bound.get(e.getKey())) : 0;
			if (q > b) out.add(new Over(e.getKey(), q, b));
		}
		return out;
	}

	/** One item the bill takes more than half of: the bill's count and what the stockpile holds. */
	public record Heavy(String item, int bill, int stock) {}

	/** (Q4) Every item the bill takes more than half of the stockpile's count of (a stockpile holding none counts as 0). */
	public static List<Heavy> heavy(JsonObject bill, Map<String, Integer> stock) {
		List<Heavy> out = new ArrayList<>();
		for (var e : new TreeMap<>(bill.asMap()).entrySet()) {
			int n = count(e.getValue());
			int s = stock.getOrDefault(e.getKey(), 0);
			if (n > 0 && 2L * n > s) out.add(new Heavy(e.getKey(), n, s));
		}
		return out;
	}

	/** "34 dirt (bound 30), 2 lanterns (not in the bound)": the first {@code n}. */
	public static String words(List<Over> over, int n) {
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < Math.min(n, over.size()); i++) {
			Over o = over.get(i);
			parts.add(o.quoted() + " " + plain(o.item()) + (o.bound() == 0 ? " (not in the bound)" : " (bound " + o.bound() + ")"));
		}
		return String.join(", ", parts) + (over.size() > n ? " and " + (over.size() - n) + " more" : "");
	}

	/** "120 dirt, 81 wheat seeds and 4 more": a bill's first {@code n} items, most first. */
	public static String bill(JsonObject bom, int n) {
		List<Map.Entry<String, JsonElement>> l = new ArrayList<>(bom.entrySet());
		l.sort((a, b) -> Integer.compare(count(b.getValue()), count(a.getValue())));
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < Math.min(n, l.size()); i++) parts.add(count(l.get(i).getValue()) + " " + plain(l.get(i).getKey()));
		return parts.isEmpty() ? "nothing" : String.join(", ", parts) + (l.size() > n ? " and " + (l.size() - n) + " more" : "");
	}

	/** An item id in plain words ({@code minecraft:wheat_seeds} -> "wheat seeds"). */
	public static String plain(String item) {
		int i = item.indexOf(':');
		return (i >= 0 ? item.substring(i + 1) : item).replace('_', ' ');
	}

	private static int count(JsonElement e) {
		Integer n = ModuleRef.exactInt(e);
		return n == null ? Integer.MAX_VALUE : n;
	}
}
