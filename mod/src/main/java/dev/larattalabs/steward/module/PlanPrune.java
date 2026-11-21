package dev.larattalabs.steward.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 *   <li>dependencies: an entry of a gone part goes, a gone {@code after} goes (an entry left with none goes). Every order the plan held through a gone
 *   part, written or through a reference (R3), is kept: a part still built that stood after a gone one is written after what that part stood after,
 *   transitively, unless it already stands after it.</li>
 *   <li>An endpoint or a locator's target or feature naming a left-out lot whose zone is still built names that zone instead (the route or the place
 *   still reaches the town). A {@code port} naming a gone lot or district is deleted. An {@code avoid} item naming a gone lot or place goes (never
 *   widened to the zone).</li>
 *   <li>An endpoint {@code from}, {@code to} or {@code at} naming a gone zone (or a part dropped here): the connector goes; it led to what is not built.
 *   A {@code via} item: that item goes.</li>
 *   <li>A locator ({@code where}, a stairs_around's {@code around}) naming a gone place: a form or connector goes, since many require {@code where} and
 *   a wall on the fields' edge has no other path form. A zone would lose its {@code where}: never done here ({@link Pruned#unsafe}); the caller keeps
 *   the left-out lots instead (a town hanging from an island needs the island).</li>
 *   <li>A form's {@code around} or a rule's {@code scope}: the gone ids go; one left empty goes with its part, never written as absent (an absent
 *   scope binds the whole plan: a palette rule over the fields would bind the town).</li>
 *   <li>A hidden room's {@code host}, a gate's {@code wall} or {@code crossing} gone: the part goes (a gate that names no wall, once every wall is
 *   gone, too).</li>
 *   <li>A district's {@code near}: the gone districts go; an emptied list is deleted (it is a soft preference).</li>
 * </ul>
 * A part dropped here is gone too, so the pass repeats until nothing changes. A plan naming none of the gone ids comes out unchanged. Pure.
 */
public final class PlanPrune {
	private PlanPrune() {}

	/**
	 * The pruned plan; the parts this pass dropped (connectors, forms, rules), by id in plan order; and the left-out lots kept in the plan after all
	 * (as ordinary lots), because leaving them out could not be done safely ({@link ComposedModules#applied}).
	 */
	public record Result(JsonObject plan, List<String> dropped, List<String> kept) {
		public Result(JsonObject plan, List<String> dropped) {
			this(plan, dropped, List.of());
		}
	}

	/** A prune, and whether it is unsafe: a zone still built would lose its locator, or the dependencies would pass Architect's 128. */
	record Pruned(Result result, boolean unsafe) {}

	public static final List<String> FAMILIES = List.of("zones", "connectors", "forms", "rules");

	/** dependencies[] maxItems, dependency.after maxItems (plan/0). */
	static final int MAX_DEPENDENCIES = 128, MAX_AFTER = 16;

	/**
	 * Prunes {@code plan} (changed in place and returned) of its references to {@code gone} ids. {@code original}: the plan as written (its orders are
	 * kept through the gone parts). {@code standIn}: a gone lot whose zone is still built, to that zone's id.
	 */
	static Pruned prune(JsonObject original, JsonObject plan, Set<String> goneIds, Map<String, String> standIn) {
		JsonObject out = plan;
		Set<String> gone = new HashSet<>(goneIds);
		List<String> dropped = new ArrayList<>();
		if (gone.isEmpty()) return new Pruned(new Result(out, dropped), false);
		boolean wallsBefore = countWalls(out) > 0;
		Set<String> unplaced = new HashSet<>();
		boolean changed = true;
		while (changed) {
			changed = false;
			for (String family : FAMILIES) {
				if (!out.has(family) || !out.get(family).isJsonArray()) continue;
				JsonArray kept = new JsonArray();
				for (JsonElement e : out.getAsJsonArray(family)) {
					if (e.isJsonObject() && e.getAsJsonObject().has("id") && !keep(family, e.getAsJsonObject(), gone, standIn, unplaced)) {
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
		int deps = dependencies(original, out, gone);
		return new Pruned(new Result(out, dropped), !unplaced.isEmpty() || deps > MAX_DEPENDENCIES);
	}

	private static int countWalls(JsonObject plan) {
		int n = 0;
		if (plan.has("connectors") && plan.get("connectors").isJsonArray())
			for (JsonElement e : plan.getAsJsonArray("connectors")) if (e.isJsonObject() && "wall".equals(str(e.getAsJsonObject(), "kind"))) n++;
		return n;
	}

	/** Settles one part's references in place; false when the part itself must go. A zone whose locator names a gone place goes in {@code unplaced}. */
	private static boolean keep(String family, JsonObject part, Set<String> gone, Map<String, String> standIn, Set<String> unplaced) {
		String self = str(part, "id");
		// endpoints (refsOf reads them on a connector, or on any part with from or to)
		if (family.equals("connectors") || part.has("from") || part.has("to")) {
			boolean selfRouted = part.has("from") && part.get("from").equals(part.get("to"));
			for (String k : List.of("from", "to", "at")) if (part.has(k) && part.get(k).isJsonObject() && !endpoint(part.getAsJsonObject(k), gone, standIn)) return false;
			if (!items(part, "via", e -> !e.isJsonObject() || endpoint(e.getAsJsonObject(), gone, standIn))) return false;
			// (fix round, GPT P1) an avoided lot or place that is gone is no longer there to avoid: the item goes, never widened to its zone
			if (!items(part, "avoid", e -> !e.isJsonObject() || !gone.contains(str(e.getAsJsonObject(), "part")))) return false;
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
			// (fix round, GPT P1) a zone placed by a gone place: its lots may need that place; it is never unplaced here
			if (self != null) unplaced.add(self);
			return true;
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

	// ------------------------------------------------------------------ the build order (R3)

	/**
	 * dependencies[] settled in place: an entry of a gone part goes, a gone after goes (an entry left with none goes); then (fix round, GPT P1) each
	 * part still built that stood after a gone part, written or through a reference, is written after what that part stood after, transitively, unless
	 * it already stands after it. (batch 091 review P2) An order on a part still built that only a removed reference held (an avoid item naming a
	 * left-out lot stood for its zone) is written out the same way. The entries' count.
	 */
	private static int dependencies(JsonObject original, JsonObject plan, Set<String> gone) {
		Map<String, Set<String>> before = order(original);
		Map<String, Set<String>> now = order(plan);
		JsonArray deps = plan.has("dependencies") && plan.get("dependencies").isJsonArray() ? plan.getAsJsonArray("dependencies") : new JsonArray();
		JsonArray next = new JsonArray();
		boolean changed = false;
		for (JsonElement e : deps) {
			if (!e.isJsonObject()) { next.add(e); continue; }
			JsonObject d = e.getAsJsonObject();
			String p = str(d, "part");
			if (p != null && gone.contains(p)) { changed = true; continue; }
			List<String> own = afters(d);
			if (own.stream().noneMatch(gone::contains)) { next.add(d); continue; }
			changed = true;
			JsonArray a = new JsonArray();
			for (String s : own) if (!gone.contains(s)) a.add(s);
			if (a.isEmpty()) continue;
			JsonObject c = d.deepCopy();
			c.add("after", a);
			next.add(c);
		}
		// the orders through the gone parts, for the parts still built (in plan order)
		for (String family : FAMILIES) {
			if (!plan.has(family) || !plan.get(family).isJsonArray()) continue;
			for (JsonElement e : plan.getAsJsonArray(family)) {
				String p = e.isJsonObject() ? str(e.getAsJsonObject(), "id") : null;
				if (p == null) continue;
				Set<String> need = new LinkedHashSet<>();
				for (String g : before.getOrDefault(p, Set.of())) {
					if (gone.contains(g)) expand(g, before, gone, need, new HashSet<>());
					// (batch 091 review P2) a part still built that this one stood after only through a reference that went (an avoided left-out lot,
					// read as its zone): the order is written out
					else need.add(g);
				}
				need.remove(p);
				need.removeAll(now.getOrDefault(p, Set.of()));
				if (need.isEmpty()) continue;
				changed = true;
				List<String> all = new ArrayList<>(need);
				for (int i = 0; i < all.size(); i += MAX_AFTER) {
					JsonObject c = new JsonObject();
					c.addProperty("part", p);
					JsonArray a = new JsonArray();
					for (String s : all.subList(i, Math.min(all.size(), i + MAX_AFTER))) a.add(s);
					c.add("after", a);
					next.add(c);
				}
			}
		}
		if (changed) plan.add("dependencies", next);
		return next.size();
	}

	private static void expand(String g, Map<String, Set<String>> before, Set<String> gone, Set<String> into, Set<String> seen) {
		if (!seen.add(g)) return;
		for (String x : before.getOrDefault(g, Set.of())) {
			if (gone.contains(x)) expand(x, before, gone, into, seen);
			else into.add(x);
		}
	}

	/**
	 * What each part of {@code plan} stands after: its dependencies[] entries and every part it names (a lot named stands for its zone), as Architect's
	 * validatePlan builds its graph from refsOf.
	 */
	static Map<String, Set<String>> order(JsonObject plan) {
		Set<String> parts = new HashSet<>();
		Map<String, String> lotZone = new HashMap<>();
		for (String family : FAMILIES) if (plan.has(family) && plan.get(family).isJsonArray()) for (JsonElement e : plan.getAsJsonArray(family)) {
			String id = e.isJsonObject() ? str(e.getAsJsonObject(), "id") : null;
			if (id == null) continue;
			parts.add(id);
			if (family.equals("zones")) for (String lot : lotIds(e.getAsJsonObject())) lotZone.putIfAbsent(lot, id);
		}
		Map<String, Set<String>> out = new HashMap<>();
		for (String family : FAMILIES) if (plan.has(family) && plan.get(family).isJsonArray()) for (JsonElement e : plan.getAsJsonArray(family)) {
			String id = e.isJsonObject() ? str(e.getAsJsonObject(), "id") : null;
			if (id == null) continue;
			Set<String> to = out.computeIfAbsent(id, k -> new LinkedHashSet<>());
			for (String r : refs(family, e.getAsJsonObject())) {
				String t = parts.contains(r) ? r : lotZone.get(r);
				if (t != null && !t.equals(id)) to.add(t);
			}
		}
		if (plan.has("dependencies") && plan.get("dependencies").isJsonArray()) for (JsonElement e : plan.getAsJsonArray("dependencies")) {
			String p = e.isJsonObject() ? str(e.getAsJsonObject(), "part") : null;
			if (p == null || !parts.contains(p)) continue;
			for (String a : afters(e.getAsJsonObject())) if (parts.contains(a) && !a.equals(p)) out.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(a);
		}
		return out;
	}

	/** The lot ids a zone holds (program.lots, a template's params.lots, its districts' lots). */
	static List<String> lotIds(JsonObject zone) {
		List<String> out = new ArrayList<>();
		JsonObject program = obj(zone, "program");
		if (program == null) return out;
		List<JsonArray> lists = new ArrayList<>();
		JsonObject params = obj(program, "params");
		if (program.has("districts") && program.get("districts").isJsonArray()) {
			for (JsonElement d : program.getAsJsonArray("districts"))
				if (d.isJsonObject() && d.getAsJsonObject().has("lots") && d.getAsJsonObject().get("lots").isJsonArray()) lists.add(d.getAsJsonObject().getAsJsonArray("lots"));
		} else if (program.has("lots") && program.get("lots").isJsonArray()) lists.add(program.getAsJsonArray("lots"));
		else if (params != null && params.has("lots") && params.get("lots").isJsonArray()) lists.add(params.getAsJsonArray("lots"));
		for (JsonArray l : lists) for (JsonElement e : l) if (e.isJsonObject() && str(e.getAsJsonObject(), "id") != null) out.add(str(e.getAsJsonObject(), "id"));
		return out;
	}

	/**
	 * The ids a part names (kit/lib/plan/schema.mjs refsOf): locator targets and features, endpoints, avoid, a spiral's host, a gate's wall and
	 * crossing, a hidden room's host, a form's around, a rule's scope.
	 */
	static List<String> refs(String family, JsonObject part) {
		List<String> out = new ArrayList<>();
		java.util.function.Consumer<@Nullable JsonObject> feature = f -> {
			String p = str(f, "part");
			if (p != null) out.add(p);
		};
		java.util.function.Consumer<@Nullable JsonObject> endpoint = e -> {
			if (e == null) return;
			String p = str(e, "part");
			if (p != null) out.add(p);
			feature.accept(obj(e, "feature"));
		};
		JsonObject w = obj(part, "where");
		if (w != null) {
			feature.accept(obj(w, "inside"));
			feature.accept(obj(obj(w, "adjacent"), "to"));
			for (String k : List.of("near", "along", "around")) feature.accept(obj(obj(w, k), "feature"));
		}
		if (family.equals("connectors") || part.has("from") || part.has("to")) {
			endpoint.accept(obj(part, "from"));
			endpoint.accept(obj(part, "to"));
			endpoint.accept(obj(part, "at"));
			for (String k : List.of("via", "avoid"))
				if (part.has(k) && part.get(k).isJsonArray()) for (JsonElement e : part.getAsJsonArray(k)) if (e.isJsonObject()) endpoint.accept(e.getAsJsonObject());
			if ("stairs_around".equals(str(part, "kind"))) feature.accept(obj(obj(part, "around"), "feature"));
			if ("gate".equals(str(part, "kind"))) for (String k : List.of("wall", "crossing")) {
				String p = str(part, k);
				if (p != null) out.add(p);
			}
		}
		String host = str(part, "host");
		if (family.equals("forms") && "hidden_room".equals(str(part, "kind")) && host != null && !host.contains("#")) out.add(host);
		String list = family.equals("forms") ? "around" : family.equals("rules") ? "scope" : null;
		if (list != null && part.has(list) && part.get(list).isJsonArray()) for (JsonElement e : part.getAsJsonArray(list)) if (e.isJsonPrimitive()) out.add(e.getAsString());
		return out;
	}

	private static List<String> afters(JsonObject d) {
		List<String> out = new ArrayList<>();
		if (d.has("after") && d.get("after").isJsonArray()) for (JsonElement a : d.getAsJsonArray("after")) if (a.isJsonPrimitive()) out.add(a.getAsString());
		return out;
	}

	static @Nullable String str(@Nullable JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}

	private static @Nullable JsonObject obj(@Nullable JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
	}
}
