package dev.larattalabs.steward.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * (slice MODULE-TYPE-3) The references a plan keeps to what {@link ComposedModules#apply} took out of it: left-out lots, and the districts and zones
 * left with no lot. Architect's checkPlan (kit/lib/plan/schema.mjs validatePlan, refsOf) refuses a plan naming an id it no longer holds (MALFORMED
 * {@code UNKNOWN_REF}, the whole plan when it is a {@code dependencies[].part}), so each kind of reference is settled here, as Architect's contract allows:
 * <ul>
 *   <li>{@code dependencies[].part} gone: the entry goes (nothing is ordered for a part not built). {@code dependencies[].after[i]} gone: it stands for
 *   that part's own {@code after}, transitively, so every order the plan wrote between the parts still built holds; an entry left with no {@code after}
 *   goes ({@code minItems} 1).</li>
 *   <li>An endpoint, a locator's target or feature naming a left-out lot (or, as a port, a lot or district) whose zone is still built: it names that
 *   zone instead (the route or the place still reaches the town, at its own default point). A {@code port} naming a gone lot or district is deleted.</li>
 *   <li>An endpoint {@code from}, {@code to} or {@code at} naming a gone zone (or a part dropped here): the connector goes; it led to what is not built.
 *   A {@code via} or {@code avoid} item: that item goes.</li>
 *   <li>A locator ({@code where}, a stairs_around's {@code around}) naming a gone place: a zone keeps its program and loses only its {@code where}
 *   (optional on every zone kind: the zone is placed as one with none); a form or connector goes, since many require {@code where} and a wall on the
 *   fields' edge has no other path form.</li>
 *   <li>A form's {@code around} or a rule's {@code scope}: the gone ids go; one left empty goes with its part, never written as absent (an absent
 *   scope binds the whole plan: a palette rule over the fields would bind the town).</li>
 *   <li>A hidden room's {@code host}, a gate's {@code wall} or {@code crossing} gone: the part goes (a gate that names no wall, once every wall is
 *   gone, too).</li>
 *   <li>A district's {@code near}: the gone districts go; an emptied list is deleted (it is a soft preference).</li>
 * </ul>
 * A part dropped here is gone too, so the pass repeats until nothing changes. Only zones hold lots, and a zone never goes here, so no lot the player
 * would get is lost to it. A plan naming none of the gone ids comes out unchanged. Pure; deterministic.
 */
public final class PlanPrune {
	private PlanPrune() {}

	/** The pruned plan and the parts this pass dropped (connectors, forms, rules), by id in plan order. */
	public record Result(JsonObject plan, List<String> dropped) {}

	public static final List<String> FAMILIES = List.of("zones", "connectors", "forms", "rules");

	/**
	 * Prunes {@code plan} (a copy is changed in place and returned) of its references to {@code gone} ids. {@code standIn}: a gone lot or district whose
	 * zone is still built, to that zone's id.
	 */
	static Result prune(JsonObject plan, Set<String> goneIds, Map<String, String> standIn) {
		JsonObject out = plan;
		Set<String> gone = new HashSet<>(goneIds);
		List<String> dropped = new ArrayList<>();
		if (gone.isEmpty()) return new Result(out, dropped);
		boolean wallsBefore = countWalls(out) > 0;
		boolean changed = true;
		while (changed) {
			changed = false;
			for (String family : FAMILIES) {
				if (!out.has(family) || !out.get(family).isJsonArray()) continue;
				JsonArray kept = new JsonArray();
				for (JsonElement e : out.getAsJsonArray(family)) {
					if (e.isJsonObject() && e.getAsJsonObject().has("id") && !keep(family, e.getAsJsonObject(), gone, standIn)) {
						String id = e.getAsJsonObject().get("id").getAsString();
						gone.add(id);
						dropped.add(id);
						changed = true;
					} else kept.add(e);
				}
				if (kept.size() != out.getAsJsonArray(family).size()) out.add(family, kept);
			}
			// a gate that names no wall stands in the plan's one wall: with every wall gone, it has none
			if (wallsBefore && countWalls(out) == 0 && out.has("connectors") && out.get("connectors").isJsonArray()) {
				JsonArray kept = new JsonArray();
				for (JsonElement e : out.getAsJsonArray("connectors")) {
					if (e.isJsonObject() && "gate".equals(str(e.getAsJsonObject(), "kind")) && !e.getAsJsonObject().has("wall") && e.getAsJsonObject().has("id")) {
						String id = e.getAsJsonObject().get("id").getAsString();
						gone.add(id);
						dropped.add(id);
						changed = true;
					} else kept.add(e);
				}
				if (kept.size() != out.getAsJsonArray("connectors").size()) out.add("connectors", kept);
			}
		}
		dependencies(out, gone);
		return new Result(out, dropped);
	}

	private static int countWalls(JsonObject plan) {
		int n = 0;
		if (plan.has("connectors") && plan.get("connectors").isJsonArray())
			for (JsonElement e : plan.getAsJsonArray("connectors")) if (e.isJsonObject() && "wall".equals(str(e.getAsJsonObject(), "kind"))) n++;
		return n;
	}

	/** Settles one part's references in place; false when the part itself must go. */
	private static boolean keep(String family, JsonObject part, Set<String> gone, Map<String, String> standIn) {
		String self = str(part, "id");
		// endpoints (refsOf reads them on a connector, or on any part with from or to)
		if (family.equals("connectors") || part.has("from") || part.has("to")) {
			boolean selfRouted = part.has("from") && part.get("from").equals(part.get("to"));
			for (String k : List.of("from", "to", "at")) if (part.has(k) && part.get(k).isJsonObject() && !endpoint(part.getAsJsonObject(k), gone, standIn)) return false;
			if (!items(part, "via", e -> !e.isJsonObject() || endpoint(e.getAsJsonObject(), gone, standIn))) return false;
			if (!items(part, "avoid", e -> !e.isJsonObject() || feature(e.getAsJsonObject(), gone, standIn))) return false;
			// a route the stand-in turned into one from a place to itself
			if (!selfRouted && part.has("from") && part.get("from").equals(part.get("to")) && !part.has("via")) return false;
			if ("stairs_around".equals(str(part, "kind")) && part.has("around") && part.get("around").isJsonObject()
				&& !feature(obj(part.getAsJsonObject("around"), "feature"), gone, standIn)) return false;
			if ("gate".equals(str(part, "kind"))) {
				if (gone.contains(str(part, "wall")) || gone.contains(str(part, "crossing"))) return false;
			}
		}
		// the locator
		if (part.has("where") && part.get("where").isJsonObject() && !where(part.getAsJsonObject("where"), gone, standIn)) {
			if (!family.equals("zones")) return false;
			part.remove("where");
		}
		if (family.equals("forms")) {
			if ("hidden_room".equals(str(part, "kind")) && gone.contains(str(part, "host"))) return false;
			if (!ids(part, "around", gone)) return false;
		}
		if (family.equals("rules") && !ids(part, "scope", gone)) return false;
		if (family.equals("zones") && part.has("program") && part.get("program").isJsonObject()) {
			JsonObject program = part.getAsJsonObject("program");
			if (program.has("districts") && program.get("districts").isJsonArray())
				for (JsonElement d : program.getAsJsonArray("districts")) if (d.isJsonObject()) near(d.getAsJsonObject(), gone);
		}
		return self != null;
	}

	/** An endpoint settled in place; false when it names a place that is gone with no stand-in. */
	private static boolean endpoint(JsonObject e, Set<String> gone, Map<String, String> standIn) {
		String p = str(e, "part");
		if (p != null && gone.contains(p)) {
			String zone = standIn.get(p);
			if (zone == null || gone.contains(zone)) return false;
			e.addProperty("part", zone);
			e.remove("port");
			return true;
		}
		if (p != null && gone.contains(str(e, "port"))) e.remove("port");
		if (e.has("feature") && e.get("feature").isJsonObject()) return feature(e.getAsJsonObject("feature"), gone, standIn);
		return true;
	}

	/** A feature or target ({@code {part}}) settled in place; false when its part is gone with no stand-in. A null one is kept. */
	private static boolean feature(@Nullable JsonObject f, Set<String> gone, Map<String, String> standIn) {
		if (f == null) return true;
		String p = str(f, "part");
		if (p == null || !gone.contains(p)) return true;
		String zone = standIn.get(p);
		if (zone == null || gone.contains(zone)) return false;
		f.addProperty("part", zone);
		return true;
	}

	/** A locator ({@code where}, or a wall's own path form) settled in place; false when the place it names is gone. */
	private static boolean where(JsonObject w, Set<String> gone, Map<String, String> standIn) {
		if (w.has("inside") && w.get("inside").isJsonObject() && !feature(w.getAsJsonObject("inside"), gone, standIn)) return false;
		if (w.has("adjacent") && w.get("adjacent").isJsonObject() && !feature(obj(w.getAsJsonObject("adjacent"), "to"), gone, standIn)) return false;
		for (String k : List.of("near", "along", "around"))
			if (w.has(k) && w.get(k).isJsonObject() && !feature(obj(w.getAsJsonObject(k), "feature"), gone, standIn)) return false;
		return true;
	}

	/** An id list ({@code around}, {@code scope}) less the gone ids; false when it had ids and none is left (its part goes). Absent or not a list: kept. */
	private static boolean ids(JsonObject part, String member, Set<String> gone) {
		if (!part.has(member) || !part.get(member).isJsonArray()) return true;
		JsonArray list = part.getAsJsonArray(member);
		JsonArray kept = new JsonArray();
		for (JsonElement e : list) if (!(e.isJsonPrimitive() && gone.contains(e.getAsString()))) kept.add(e);
		if (kept.size() == list.size()) return true;
		if (kept.isEmpty()) return false;
		part.add(member, kept);
		return true;
	}

	/** A district's {@code near} less the gone districts; deleted when emptied. */
	private static void near(JsonObject district, Set<String> gone) {
		if (!district.has("near") || !district.get("near").isJsonArray()) return;
		JsonArray list = district.getAsJsonArray("near");
		JsonArray kept = new JsonArray();
		for (JsonElement e : list) if (!(e.isJsonPrimitive() && gone.contains(e.getAsString()))) kept.add(e);
		if (kept.size() == list.size()) return;
		if (kept.isEmpty()) district.remove("near");
		else district.add("near", kept);
	}

	/** A list of items each settled by {@code ok} (false: the item goes); the key is deleted when emptied. Always true (an item never takes its part). */
	private static boolean items(JsonObject part, String member, java.util.function.Predicate<JsonElement> ok) {
		if (!part.has(member) || !part.get(member).isJsonArray()) return true;
		JsonArray list = part.getAsJsonArray(member);
		JsonArray kept = new JsonArray();
		for (JsonElement e : list) if (ok.test(e)) kept.add(e);
		if (kept.size() == list.size()) return true;
		if (kept.isEmpty()) part.remove(member);
		else part.add(member, kept);
		return true;
	}

	/** dependencies[]: a gone part's entry goes; a gone {@code after} stands for that part's own afters, transitively; an entry left with none goes. */
	private static void dependencies(JsonObject plan, Set<String> gone) {
		if (!plan.has("dependencies") || !plan.get("dependencies").isJsonArray()) return;
		JsonArray deps = plan.getAsJsonArray("dependencies");
		Map<String, List<String>> afterOf = new LinkedHashMap<>();
		boolean touches = false;
		for (JsonElement e : deps) {
			if (!e.isJsonObject()) continue;
			String p = str(e.getAsJsonObject(), "part");
			if (p != null && gone.contains(p)) touches = true;
			for (String a : afters(e.getAsJsonObject())) {
				if (gone.contains(a)) touches = true;
				if (p != null) afterOf.computeIfAbsent(p, k -> new ArrayList<>()).add(a);
			}
		}
		if (!touches) return;
		JsonArray next = new JsonArray();
		for (JsonElement e : deps) {
			if (!e.isJsonObject()) { next.add(e); continue; }
			JsonObject d = e.getAsJsonObject();
			String p = str(d, "part");
			if (p != null && gone.contains(p)) continue;
			List<String> own = afters(d);
			if (own.stream().noneMatch(gone::contains)) { next.add(d); continue; }
			Set<String> after = new LinkedHashSet<>();
			for (String a : own) expand(a, afterOf, gone, after, new HashSet<>());
			after.remove(p);
			if (after.isEmpty()) continue;
			// at most 16 afters an entry (dependency.after maxItems): more are written as further entries of the same part
			List<String> all = new ArrayList<>(after);
			for (int i = 0; i < all.size(); i += 16) {
				JsonObject c = d.deepCopy();
				JsonArray a = new JsonArray();
				for (String s : all.subList(i, Math.min(all.size(), i + 16))) a.add(s);
				c.add("after", a);
				next.add(c);
			}
		}
		plan.add("dependencies", next);
	}

	private static void expand(String a, Map<String, List<String>> afterOf, Set<String> gone, Set<String> into, Set<String> seen) {
		if (!seen.add(a)) return;
		if (!gone.contains(a)) { into.add(a); return; }
		for (String b : afterOf.getOrDefault(a, List.of())) expand(b, afterOf, gone, into, seen);
	}

	private static List<String> afters(JsonObject d) {
		List<String> out = new ArrayList<>();
		if (d.has("after") && d.get("after").isJsonArray()) for (JsonElement a : d.getAsJsonArray("after")) if (a.isJsonPrimitive()) out.add(a.getAsString());
		return out;
	}

	private static @Nullable String str(@Nullable JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}

	private static @Nullable JsonObject obj(@Nullable JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
	}
}
