package dev.larattalabs.steward.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What Architect changed in a part so it fits, in plain words for the player's chat line: a check answer's {@code deviations} (plan/0's
 * {@code parts[].deviations}, {@code {what, value, ...}}) turned into a few short phrases, for example "moved 15 blocks and 2 of its lots left
 * out". Pure: no game state.
 *
 * <p>The kinds are plan.json's deviation enum (move, size, rotate, count, grade, detour, sketch, join, crossing, material, climb) and the ones
 * Architect's kit sends beyond it (width, height, mechanism, length, reach, outline, layout). A kind not known here, a value it can't read or no
 * deviation at all reads "its size or place": never the raw JSON.
 */
public final class ChangeWords {
	/** What a change reads as when it can't be told more plainly. */
	public static final String FALLBACK = "its size or place";
	/** At most this many phrases; more are "and more". */
	static final int MAX = 3;

	private ChangeWords() {}

	/** The deviations (a JSON array, or anything else) as a short phrase list. */
	public static String of(JsonElement deviations) {
		if (deviations == null || !deviations.isJsonArray()) return FALLBACK;
		// one phrase per kind, in the order Architect gave them; join entries (one per lot) are counted together
		Map<String, String> phrases = new LinkedHashMap<>();
		int joins = 0;
		for (JsonElement e : deviations.getAsJsonArray()) {
			if (!e.isJsonObject()) continue;
			JsonObject d = e.getAsJsonObject();
			String what = str(d, "what");
			if (what == null) continue;
			if (what.equals("join")) {
				joins++;
				phrases.putIfAbsent("join", "");
				continue;
			}
			if (phrases.containsKey(what)) continue;
			String p = phrase(what, d);
			if (p != null) phrases.put(what, p);
		}
		if (joins > 0) phrases.put("join", joins + (joins == 1 ? " lot" : " lots") + " set on the wall's line");
		List<String> out = new ArrayList<>(phrases.values());
		if (out.isEmpty()) return FALLBACK;
		boolean more = out.size() > MAX;
		if (more) out = out.subList(0, MAX);
		if (more) return String.join(", ", out) + " and more";
		if (out.size() == 1) return out.get(0);
		return String.join(", ", out.subList(0, out.size() - 1)) + " and " + out.get(out.size() - 1);
	}

	/** One deviation's phrase, or null when it says nothing (a zero value, a kind not known here). */
	static String phrase(String what, JsonObject d) {
		Double v = num(d, "value");
		return switch (what) {
			case "move" -> positive(v) ? "moved " + blocks(v) : null;
			case "size" -> positive(v) ? "resized by about " + whole(v) + "%" : null;
			case "rotate" -> positive(v) ? (Math.round(v) == 90 || Math.round(v) == 270 ? "turned a quarter turn" : Math.round(v) == 180 ? "turned round" : "turned") : null;
			case "count" -> positive(v) ? whole(v) + " of its lots left out" : null;
			case "grade" -> v != null && v > 0 ? "a steeper slope" : null;
			case "detour" -> v != null && v > 1 ? "a longer way round (about " + String.format(Locale.ROOT, "%.1f", v) + " times the straight line)" : null;
			case "sketch" -> positive(v) ? "set " + blocks(v) + " off from where it was drawn" : null;
			case "crossing" -> positive(v) ? whole(v) + (Math.round(v) == 1 ? " bridge" : " bridges") + " where it crosses" : null;
			case "material" -> material(d, v);
			case "climb" -> "a walkable slope instead of stairs";
			case "width" -> positive(v) ? "made " + whole(v) + " wide" : null;
			case "height" -> positive(v) ? "made " + whole(v) + " high" : null;
			case "mechanism" -> "a wooden door instead of the iron gate";
			case "length" -> positive(v) ? whole(v) + " long" + asked(d) : null;
			case "reach" -> positive(v) ? "reaching " + whole(v) + " out" + asked(d) : null;
			case "outline" -> "a different outline";
			case "layout" -> str(d, "got") != null ? "laid out as a " + block(str(d, "got")) : "a different layout";
			default -> null;
		};
	}

	private static String material(JsonObject d, Double v) {
		String from = str(d, "from"), to = str(d, "to");
		if (from == null) return null;
		String cells = positive(v) ? whole(v) + " " : "";
		return to != null ? cells + block(to) + " in place of " + block(from) : "less " + block(from);
	}

	private static String asked(JsonObject d) {
		Double a = num(d, "asked");
		return a != null ? " instead of " + whole(a) : "";
	}

	/** A block id or family as words: minecraft:oak_planks reads oak planks. */
	static String block(String id) {
		int colon = id.indexOf(':');
		return (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' ');
	}

	private static String blocks(double v) {
		long n = Math.round(Math.abs(v));
		return n + (n == 1 ? " block" : " blocks");
	}

	private static long whole(double v) {
		return Math.round(Math.abs(v));
	}

	private static boolean positive(Double v) {
		return v != null && Math.round(Math.abs(v)) > 0;
	}

	private static String str(JsonObject d, String k) {
		JsonElement e = d.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	private static Double num(JsonObject d, String k) {
		JsonElement e = d.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : null;
	}
}
