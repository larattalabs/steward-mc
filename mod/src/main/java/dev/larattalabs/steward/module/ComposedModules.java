package dev.larattalabs.steward.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Module lots of a composed plan (docs/MODULES.md "M5 design", slice M5): the composer's vocabulary (which modules it may name and how), the lots a
 * plan names, the order each gets (or why not, in words), the lot as Steward builds it, its geometry from the approved artifact, and its share of a
 * quote. Pure: the caller reads the catalog, the gate and the world.
 */
public final class ComposedModules {
	private ComposedModules() {}

	/** The plan lot's ext key the composer writes ({@code {id, params, for}}); Steward's built lot holds the full {@link ModuleRef} under it. */
	public static final String EXT = ModuleRef.EXT;

	/** A module the composer may name: its kind and the catalog entry the gate offers now. */
	public record Offered(ModuleKind kind, ModuleCatalog.Entry entry) {}

	/**
	 * The hints' {@code modules}: each offered module's id, lot type, role, title, whether it is watered and needs a warm biome, its sizes (the size
	 * params and the lot's {@code [W, D]}) and its one free choice (the crop, the bulk chests), from the catalog's measured branches. Empty when
	 * nothing is offered. Pure.
	 */
	public static JsonArray vocabulary(List<Offered> offered) {
		JsonArray out = new JsonArray();
		for (Offered o : offered) {
			ModuleKind k = o.kind();
			JsonObject m = new JsonObject();
			m.addProperty("id", k.id());
			m.addProperty("lotType", k.lotType());
			m.addProperty("role", k.role());
			m.addProperty("title", k.title());
			m.addProperty("irrigate", k.irrigate());
			m.addProperty("warmOnly", k.warmOnly());
			m.addProperty("does", does(k, o.entry()));
			List<JsonElement> choices = o.entry().envelope().branches().getOrDefault(k.cropParam(), List.of());
			String first = choices.isEmpty() ? null : choices.get(0).getAsString();
			JsonArray sizes = new JsonArray();
			for (JsonObject s : composedSizes(k, o.entry())) {
				int[] f;
				try {
					f = k.frame(first == null ? s : k.params(s, first));
				} catch (RuntimeException e) {
					continue;
				}
				JsonObject size = new JsonObject();
				size.add("params", Canon.sorted(s));
				JsonArray wd = new JsonArray();
				wd.add(f[0]);
				wd.add(f[1]);
				size.add("size", wd);
				sizes.add(size);
			}
			m.add("sizes", sizes);
			JsonObject choose = new JsonObject();
			choose.addProperty("param", k.cropParam());
			JsonArray values = new JsonArray();
			for (JsonElement v : choices) values.add(v.deepCopy());
			choose.add("values", values);
			m.add("choose", choose);
			// (M5b) a sorting hall's filter items: one per slot (item1 .. itemN for N slots), distinct, from the measured domain
			if (k.sign() == ModuleKind.Sign.STORES && sorts(o.entry())) {
				JsonObject items = new JsonObject();
				items.addProperty("params", "item1 .. itemN, one for each of the size's N slots, all different; the hall sorts each slot's item into its own chest");
				JsonArray domain = new JsonArray();
				for (String it : StorageHallLot.FILTERABLE) domain.add(it);
				items.add("values", domain);
				m.add("items", items);
			}
			out.add(m);
		}
		return out;
	}

	/**
	 * Whether a composed town's hall may be a sorting hall (slice M5b): its sizes are the catalog version's measured slot branches
	 * ({@link #composedSizes}), its filter items are the plan's when they keep the rule, else chosen ({@link #items}), and its cargo follows its fill
	 * (HallCargo reads the site's own params: a committed F holding its declaration is not cargo). Off, a composed hall is the plain hall, said.
	 */
	public static final boolean COMPOSED_HALL_SORTS = true;

	/**
	 * (M5b) The sizes a composed lot of this kind may take, largest first (the last is the smallest): a storage hall's are the entry's measured
	 * {@code slots} branches (16, 12, 8, 4, then the plain hall's 0) while {@link #COMPOSED_HALL_SORTS} holds; every other kind's are its own. The
	 * vocabulary and {@link #decide} both read this, so what the composer is offered is what Steward builds. Pure.
	 */
	public static List<JsonObject> composedSizes(ModuleKind k, ModuleCatalog.Entry e) {
		if (k.sign() != ModuleKind.Sign.STORES || !COMPOSED_HALL_SORTS) return k.sizes();
		List<Integer> slots = new ArrayList<>();
		for (JsonElement v : e.envelope().branches().getOrDefault("slots", List.of())) {
			Integer n = ModuleRef.exactInt(v);
			if (n != null && StorageHallLot.SLOTS.contains(n) && !slots.contains(n)) slots.add(n);
		}
		if (slots.isEmpty()) return k.sizes();
		slots.sort(java.util.Comparator.reverseOrder());
		List<JsonObject> out = new ArrayList<>();
		for (int n : slots) {
			JsonObject o = new JsonObject();
			o.addProperty("slots", n);
			out.add(o);
		}
		return List.copyOf(out);
	}

	/** Whether the entry measured a sorting branch (slots above 0) a composed hall may take now. */
	static boolean sorts(ModuleCatalog.Entry e) {
		if (!COMPOSED_HALL_SORTS) return false;
		for (JsonElement v : e.envelope().branches().getOrDefault("slots", List.of())) {
			Integer n = ModuleRef.exactInt(v);
			if (n != null && n > 0 && StorageHallLot.SLOTS.contains(n)) return true;
		}
		return false;
	}

	/** The words of a plain hall, as the composer and the player read them. */
	static final String PLAIN_HALL = "a storage hall with an input chest by the door and double chests along its back wall; it does not sort";

	/**
	 * What a module does, in the composer's words, so it is written only for what it does (GPT review 10): the crop farm is harvested by the player;
	 * a composed hall is a plain hall that does not sort, whatever its catalog version measured ({@link #COMPOSED_HALL_SORTS}). Pure.
	 */
	public static String does(ModuleKind k, ModuleCatalog.Entry e) {
		if (k.sign() == ModuleKind.Sign.STORES)
			return sorts(e) ? "a storage hall whose input chest feeds a sorting line: each of its slots sorts one item (its items) into the slot's own chest, the rest goes"
				+ " to an overflow chest, with double chests along its back wall; its size with 0 slots is a plain hall that does not sort" : PLAIN_HALL;
		if (k.irrigate()) return "a watered, lit field of one crop; the player harvests and replants it (no automatic harvest)";
		return k.title();
	}

	/**
	 * What the vocabulary names, for the compose key: each offered module's id, version and program sha, sorted by id. A catalog change under the same
	 * card is another compose (Architect refuses a key with another body). Empty string when nothing is offered. Pure.
	 */
	public static String identity(List<Offered> offered) {
		List<String> ids = new ArrayList<>();
		for (Offered o : offered) ids.add(o.entry().id() + "@" + o.entry().version() + ":" + o.entry().programSha());
		ids.sort(String::compareTo);
		return String.join(",", ids);
	}

	/** A module lot a plan names: its lot id, the module id, the params as written (may be empty), and the card type it stands for (or null). */
	public record Asked(String lotId, String moduleId, JsonObject params, @Nullable String forType) {}

	/** Every lot of {@code lots} whose ext names a module ({@code steward_mc:module} with an {@code id}), in order. Pure. */
	public static List<Asked> read(List<JsonObject> lots) {
		List<Asked> out = new ArrayList<>();
		for (JsonObject l : lots) {
			Asked a = asked(l);
			if (a != null) out.add(a);
		}
		return out;
	}

	/** The module id an ask names when its marker can't be read: such a lot is refused, never built as an ordinary (designed) lot. */
	public static final String UNREADABLE = "";

	/**
	 * The module a plan lot names, or null when it names none. Fails closed (GPT review 3): a lot whose ext has {@code steward_mc:module} at all, or
	 * whose type is a module's lot type ({@code lotTypes}), is a module ask; one whose id can't be read asks for {@link #UNREADABLE}, which
	 * {@link #decide} refuses, so it is left out rather than designed.
	 */
	public static @Nullable Asked asked(JsonObject lot, Set<String> lotTypes) {
		if (!lot.has("id") || !lot.get("id").isJsonPrimitive()) return null;
		JsonObject ext = lot.has("ext") && lot.get("ext").isJsonObject() ? lot.getAsJsonObject("ext") : null;
		boolean marked = ext != null && ext.has(EXT);
		String type = lot.has("type") && lot.get("type").isJsonPrimitive() ? lot.get("type").getAsString() : "";
		if (!marked) return lotTypes.contains(type) ? new Asked(lot.get("id").getAsString(), moduleOfType(type), new JsonObject(), null) : null;
		if (!ext.get(EXT).isJsonObject()) return new Asked(lot.get("id").getAsString(), UNREADABLE, new JsonObject(), null);
		Asked a = asked(lot);
		return a != null ? a : new Asked(lot.get("id").getAsString(), UNREADABLE, new JsonObject(), null);
	}

	/** The module id of a module lot type (the kinds Steward knows), or {@link #UNREADABLE}. */
	static String moduleOfType(String type) {
		for (String id : List.of(ModuleKind.CropFarm.ID, ModuleKind.StorageHall.ID)) if (ModuleKind.of(id).map(ModuleKind::lotType).filter(type::equals).isPresent()) return id;
		return UNREADABLE;
	}

	/** The lot types of the modules Steward knows ({@code crop_farm}, {@code storage_hall}): a lot of one of these types is read as a module ask. */
	public static final Set<String> LOT_TYPES = Set.of(ModuleKind.CropFarm.INSTANCE.lotType(), ModuleKind.StorageHall.INSTANCE.lotType());

	/** Every module ask of {@code lots}, failing closed ({@link #asked(JsonObject, Set)} over {@link #LOT_TYPES}). Pure. */
	public static List<Asked> readAll(List<JsonObject> lots) {
		return readAll(lots, LOT_TYPES);
	}

	/**
	 * Every module ask of {@code lots}: a lot with a module marker (readable or not), or a lot of one of {@code lotTypes} (the modules offered now: a bare
	 * {@code storage_hall} lot of a town whose catalog offers none is an ordinary lot, as before M5). Pure.
	 */
	public static List<Asked> readAll(List<JsonObject> lots, Set<String> lotTypes) {
		List<Asked> out = new ArrayList<>();
		for (JsonObject l : lots) {
			Asked a = asked(l, lotTypes);
			if (a != null) out.add(a);
		}
		return out;
	}

	/** The module a plan lot's marker names when it is readable, or null (no ext, no id, or not a string id). */
	public static @Nullable Asked asked(JsonObject lot) {
		if (!lot.has("id") || !lot.get("id").isJsonPrimitive()) return null;
		JsonObject ext = lot.has("ext") && lot.get("ext").isJsonObject() ? lot.getAsJsonObject("ext") : null;
		if (ext == null || !ext.has(EXT) || !ext.get(EXT).isJsonObject()) return null;
		JsonObject m = ext.getAsJsonObject(EXT);
		if (!m.has("id") || !m.get("id").isJsonPrimitive() || !m.get("id").getAsJsonPrimitive().isString()) return null;
		String id = m.get("id").getAsString().strip().toLowerCase(Locale.ROOT);
		if (id.isEmpty()) return null;
		JsonObject params = m.has("params") && m.get("params").isJsonObject() ? m.getAsJsonObject("params").deepCopy() : new JsonObject();
		String forType = m.has("for") && m.get("for").isJsonPrimitive() && !m.get("for").getAsString().isBlank()
			? m.get("for").getAsString().strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "") : null;
		return new Asked(lot.get("id").getAsString(), id, params, forType == null || forType.isEmpty() ? null : forType);
	}

	/**
	 * What the build sees of the town when it decides: the biome freezes water; it is built from materials; {@code held} the items the player
	 * carries and (from materials) the stockpile holds, item id -> count (a sorting hall's chosen items: the most held filter kinds first).
	 */
	public record Town(boolean freezing, boolean materials, Map<String, Integer> held, boolean lotCaps) {
		public Town {
			held = held == null ? Map.of() : Map.copyOf(held);
		}

		/** (LIFT-S) As before the per-lot cap: an Architect without {@link #LOT_CAP_FEATURE}, so from materials the guard is on. */
		public Town(boolean freezing, boolean materials, Map<String, Integer> held) {
			this(freezing, materials, held, false);
		}

		public Town(boolean freezing, boolean materials) {
			this(freezing, materials, Map.of(), false);
		}
	}

	/**
	 * A decision: an order ({@code why} null) with {@code changed} the words for params Steward chose over the plan's, or why not, in the steward's
	 * words (a whole sentence about the module, without the "left out" tail the caller adds).
	 */
	public record Decision(@Nullable ModuleOrder order, @Nullable String why, @Nullable String changed) {
		public boolean ok() {
			return why == null && order != null;
		}

		static Decision no(String why) {
			return new Decision(null, why, null);
		}
	}

	/**
	 * The order for an asked lot, or why it can't be built (docs/MODULES.md "M5 design" 2): a kind Steward knows, the gate's verdict now, one lot of each
	 * module ({@code taken}: the ids given an order already), a warm biome for a warm-only module, and from materials a measured bound for its params.
	 * The params: as written when they are a measured branch; else the measured size whose frame is the lot's size either way round
	 * ({@code lotSize}, null when the lot has none), else the smallest; its choice (crop, bulk) as written when measured, else the kind's default.
	 * {@code bounds}: the measured bound for an entry's params (as measured; the town's blocks are applied at the bind). Pure.
	 */
	public static Decision decide(Asked a, @Nullable ModuleKind kind, ModuleGate.Verdict verdict, Town town, Set<String> taken, int @Nullable [] lotSize,
		BiFunction<ModuleCatalog.Entry, JsonObject, @Nullable JsonObject> bounds) {
		return decide(a, kind, verdict, town, taken, lotSize, bounds, materialsGuard(town.lotCaps()));
	}

	/**
	 * (LIFT-S, Architect LCAP v0.78.0) Architect's feature for a cap scoped to one lot ({@code ApprovedRealise.maxBillPerLot}, {@code Quote.lotBill},
	 * {@code SiteWait.capScope}): with it, each composed module lot from materials is realised under its own measured bound.
	 */
	public static final String LOT_CAP_FEATURE = "maxBillPerLot";

	/**
	 * (batch review blocker, the manager's rule-13 call, 2026-11-15; docs/MODULES.md "M5 design", "The guard"; LIFT-S) The single guard: while it is on,
	 * a composed module lot in a town built from materials is left out of the town, said. It is on for an Architect without {@link #LOT_CAP_FEATURE}:
	 * its {@code realiseApproved} takes no cap scoped to a lot, so an inexact quote the player approved could consume past the module's measured bound
	 * while it builds, and the steward's "its measured limit" would not be enforced. It came off for an Architect with the per-lot cap (S-M5-16, slice
	 * LCAP, v0.78.0; PLAN 2026-11-16 "Wait for per-module cap"). A town built at once is unchanged. Pure.
	 */
	public static boolean materialsGuard(boolean lotCaps) {
		return !lotCaps;
	}

	/** As above, with the guard given (unit tests of the path behind it). */
	static Decision decide(Asked a, @Nullable ModuleKind kind, ModuleGate.Verdict verdict, Town town, Set<String> taken, int @Nullable [] lotSize,
		BiFunction<ModuleCatalog.Entry, JsonObject, @Nullable JsonObject> bounds, boolean materialsGuard) {
		String name = kind == null ? a.moduleId().replace('_', ' ') : kind.role();
		if (a.moduleId().equals(UNREADABLE)) return Decision.no("the plan wrote it as a working module Steward couldn't read");
		if (kind == null) return Decision.no("Steward has no working " + name + " in its catalog");
		if (!verdict.ok() || verdict.entry() == null) return Decision.no("a working " + name + " can't be built here: " + verdict.message());
		if (town.materials() && materialsGuard)
			return Decision.no("a working " + name + " can't be held to its measured limit in a town built from materials yet; it's left out for now");
		if (taken.contains(kind.id())) return Decision.no("the plan has a working " + name + " already, and a town gets one of each for now");
		if (kind.warmOnly() && town.freezing()) return Decision.no("a working " + name + " can't be built here: the water would freeze in this biome");
		var e = verdict.entry();
		List<String> notes = new ArrayList<>();
		// (batch-074 review) a hall asked to sort (slots other than 0, or filter items) while composed halls don't sort: the plain hall, said
		boolean hallSorts = kind.sign() == ModuleKind.Sign.STORES && sorts(e);
		if (kind.sign() == ModuleKind.Sign.STORES && !hallSorts && asksToSort(a.params()))
			notes.add("a planned town's storage hall doesn't sort yet, so a plain hall");
		List<JsonObject> sizes = composedSizes(kind, e);
		// the choice (crop or bulk): as written when it is a measured branch, else the default
		List<JsonElement> choices = e.envelope().branches().getOrDefault(kind.cropParam(), List.of());
		JsonElement want = a.params().get(kind.cropParam());
		JsonElement choice = want != null && choices.contains(want) ? want : null;
		if (choice == null) {
			var p = e.params().get(kind.cropParam());
			choice = p != null && p.def() != null && (choices.isEmpty() || choices.contains(p.def())) ? p.def() : choices.isEmpty() ? null : choices.get(0);
			if (want != null && choice != null) notes.add(kind.cropParam() + " " + plain(want) + " isn't measured, so " + plain(choice));
		}
		if (choice == null) return Decision.no("a working " + name + " has no measured " + kind.cropParam());
		// the size: as written, else the size whose frame is the lot's, else the smallest
		JsonObject size = null;
		boolean fromFrame = false;
		// (M5b) a hall's size is its slots alone (its items are chosen below); a slot count that isn't a whole number is not a size asked
		JsonObject asked = kind.sign() == ModuleKind.Sign.STORES ? hallSize(a.params()) : kind.sizeOf(a.params());
		for (JsonObject s : sizes) if (asked.size() > 0 && Canon.sorted(s).equals(Canon.sorted(asked))) size = s;
		// the lot's frame: the first size (the most slots) whose frame it is, either way round (4 and 8 slots share 11 by 13: 8, said below)
		if (size == null && lotSize != null) {
			for (JsonObject s : sizes) {
				int[] f = frameOf(kind, s, choice);
				if (f != null && ((f[0] == lotSize[0] && f[1] == lotSize[1]) || (f[0] == lotSize[1] && f[1] == lotSize[0]))) { size = s; fromFrame = true; break; }
			}
		}
		if (size == null) {
			List<JsonObject> all = sizes;
			if (all.isEmpty()) return Decision.no("a working " + name + " has no measured size");
			size = all.get(all.size() - 1);
			if (asked.size() > 0 || lotSize != null) notes.add("the size asked isn't measured, so the smallest");
		}
		JsonObject params = size.deepCopy();
		params.add(kind.cropParam(), choice.deepCopy());
		// (M5b) a sorting hall's filter items: as written where they keep the rule, else chosen, said
		if (kind.sign() == ModuleKind.Sign.STORES) {
			Integer n = ModuleRef.exactInt(params.get("slots"));
			if (n != null && n > 0) {
				var chosen = items(a.params(), n, town.held());
				for (int i = 0; i < n; i++) params.addProperty("item" + (i + 1), chosen.items().get(i));
				if (chosen.said() != null) notes.add(chosen.said());
				final JsonElement ch = choice;
				final int[] built = kind.frame(params);
				if (fromFrame && sizes.stream().filter(s -> sameFrame(kind, s, ch, built)).count() > 1)
					notes.add("its lot fits more than one hall, so the one with the most slots (" + n + ")");
			}
		}
		var p = ModuleGate.params(e, params);
		if (!p.ok()) return Decision.no("a working " + name + "'s size isn't one that was measured: " + p.refused());
		if (town.materials() && bounds.apply(e, p.values()) == null)
			return Decision.no("a working " + name + "'s bill from the stockpile wasn't measured for that size, so there is no bound to hold it to");
		var order = new ModuleOrder(e.id(), e.version(), e.programSha(), p.values(), null, ModuleOffer.rate(e.measured(), p.values()), false, false);
		return new Decision(order, null, notes.isEmpty() ? null : String.join("; ", notes));
	}

	/** A hall's size as the plan wrote it: {@code {slots: n}} when its slots are a whole number, else empty (not asked). */
	static JsonObject hallSize(JsonObject params) {
		JsonObject o = new JsonObject();
		Integer n = ModuleRef.exactInt(params.get("slots"));
		if (n != null) o.addProperty("slots", n);
		return o;
	}

	private static boolean sameFrame(ModuleKind kind, JsonObject size, JsonElement choice, int[] frame) {
		int[] f = frameOf(kind, size, choice);
		return f != null && f[0] == frame[0] && f[1] == frame[1];
	}

	/** A sorting hall's items (domain keys, slot order) and, when Steward chose any, the words. */
	public record Chosen(List<String> items, @Nullable String said) {}

	/**
	 * (M5b) A composed sorting hall's {@code n} filter items: each {@code item<i>} as written when it is a domain key not already taken; every other
	 * slot from the town's most held filter kinds ({@code ModuleOffer.filterKinds}), then the domain in its order, never repeating. Says which slots
	 * Steward chose. Deterministic; pure.
	 */
	public static Chosen items(JsonObject asked, int n, Map<String, Integer> held) {
		String[] out = new String[n];
		java.util.Set<String> used = new java.util.HashSet<>();
		for (int i = 0; i < n; i++) {
			JsonElement v = asked.get("item" + (i + 1));
			String k = v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString().strip().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "") : null;
			if (k != null && StorageHallLot.FILTERABLE.contains(k) && used.add(k)) out[i] = k;
		}
		List<String> pool = new ArrayList<>(ModuleOffer.filterKinds(held));
		for (String k : StorageHallLot.FILTERABLE) if (!pool.contains(k)) pool.add(k);
		List<Integer> chose = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (out[i] != null) continue;
			for (String k : pool) if (used.add(k)) { out[i] = k; break; }
			chose.add(i + 1);
		}
		List<String> items = List.of(out);
		if (chose.isEmpty()) return new Chosen(items, null);
		List<String> names = new ArrayList<>();
		for (int i : chose) names.add(out[i - 1].replace('_', ' '));
		String which = chose.size() == n ? "its " + n + " slots' items" : (chose.size() == 1 ? "slot " : "slots ") + chose.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")) + "'s items";
		// (GPT review P3) the stores' most held kinds first, then the domain's defaults, said apart
		java.util.Set<String> stored = new java.util.HashSet<>(ModuleOffer.filterKinds(held));
		boolean fromStores = chose.stream().allMatch(i -> stored.contains(out[i - 1])), noneStored = chose.stream().noneMatch(i -> stored.contains(out[i - 1]));
		String from = fromStores ? "from your stores, most held first" : noneStored ? "from the hall's defaults" : "from your stores, most held first, then the hall's defaults";
		return new Chosen(items, "Steward chose " + which + " " + from + ": " + String.join(", ", names));
	}

	/** Whether a hall's params as the plan wrote them ask for sorting: a slots member other than the whole number 0, or any filter item but none. */
	static boolean asksToSort(JsonObject params) {
		for (var en : params.entrySet()) {
			if (en.getKey().equals("slots")) {
				Integer n = ModuleRef.exactInt(en.getValue());
				if (n == null || n != 0) return true;
			} else if (en.getKey().matches("item\\d+") && !(en.getValue().isJsonPrimitive() && "none".equals(en.getValue().getAsString()))) return true;
		}
		return false;
	}

	private static int @Nullable [] frameOf(ModuleKind kind, JsonObject size, JsonElement choice) {
		try {
			JsonObject p = size.deepCopy();
			p.add(kind.cropParam(), choice.deepCopy());
			return kind.frame(p);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String plain(JsonElement v) {
		return v.isJsonPrimitive() ? v.getAsString().replace('_', ' ') : v.toString();
	}

	/** A plan lot's {@code size} {@code [W, D]}, or null when it has none (or not two integers). */
	public static int @Nullable [] size(JsonObject lot) {
		if (!lot.has("size") || !lot.get("size").isJsonArray() || lot.getAsJsonArray("size").size() != 2) return null;
		JsonArray s = lot.getAsJsonArray("size");
		Integer w = ModuleRef.exactInt(s.get(0)), d = ModuleRef.exactInt(s.get(1));
		return w == null || d == null ? null : new int[] {w, d};
	}

	/**
	 * The lot as the build builds it: the kind's own lot for the order ({@code ModuleKind.growLot}: type, exact size, {@code irrigate} and FC's
	 * {@code field} where the kind has them), the plan lot's id, role, label, brief, landmark and placement kept, its ext kept but for the module
	 * member, which is the order's full {@link ModuleRef}. Pure.
	 */
	public static JsonObject builtLot(JsonObject planLot, ModuleKind kind, ModuleOrder order) {
		String id = planLot.get("id").getAsString();
		JsonObject out = kind.growLot(id, order.params());
		for (String k : List.of("role", "label", "brief", "landmark", "placement")) if (planLot.has(k)) out.add(k, planLot.get(k).deepCopy());
		JsonObject ext = planLot.has("ext") && planLot.get("ext").isJsonObject() ? planLot.getAsJsonObject("ext").deepCopy() : new JsonObject();
		ext.remove(EXT);
		out.add("ext", order.ref().into(ext));
		return out;
	}

	/**
	 * The plan as built: each lot with an order replaced by {@link #builtLot}, each lot in {@code leftOut} removed from its zone (or district), a zone or
	 * district left with no lot keeping its other members. A copy; deterministic. Pure.
	 */
	public static JsonObject apply(JsonObject plan, Map<String, ModuleOrder> orders, Set<String> leftOut) {
		JsonObject out = plan.deepCopy();
		if (!out.has("zones") || !out.get("zones").isJsonArray()) return out;
		for (JsonElement z : out.getAsJsonArray("zones")) {
			if (!z.isJsonObject() || !z.getAsJsonObject().has("program") || !z.getAsJsonObject().get("program").isJsonObject()) continue;
			JsonObject program = z.getAsJsonObject().getAsJsonObject("program");
			if (program.has("districts") && program.get("districts").isJsonArray()) {
				for (JsonElement d : program.getAsJsonArray("districts")) if (d.isJsonObject()) rewrite(d.getAsJsonObject(), "lots", orders, leftOut);
				continue;
			}
			if (program.has("lots") && program.get("lots").isJsonArray()) rewrite(program, "lots", orders, leftOut);
			else if (program.has("params") && program.get("params").isJsonObject()) rewrite(program.getAsJsonObject("params"), "lots", orders, leftOut);
		}
		return out;
	}

	private static void rewrite(JsonObject holder, String member, Map<String, ModuleOrder> orders, Set<String> leftOut) {
		if (!holder.has(member) || !holder.get(member).isJsonArray()) return;
		JsonArray next = new JsonArray();
		for (JsonElement l : holder.getAsJsonArray(member)) {
			if (!l.isJsonObject() || !l.getAsJsonObject().has("id")) { next.add(l); continue; }
			JsonObject lot = l.getAsJsonObject();
			String id = lot.get("id").getAsString();
			if (leftOut.contains(id)) continue;
			ModuleOrder o = orders.get(id);
			var kind = o == null ? java.util.Optional.<ModuleKind>empty() : ModuleKind.of(o.moduleId());
			next.add(kind.isPresent() ? builtLot(lot, kind.get(), o) : lot);
		}
		holder.add(member, next);
	}

	/** An artifact lot's front ({@code south} when it names none). */
	public static String front(JsonObject artifactLot) {
		String f = artifactLot.has("front") && artifactLot.get("front").isJsonPrimitive() ? artifactLot.get("front").getAsString().toLowerCase(Locale.ROOT) : "south";
		return switch (f) {
			case "north", "east", "west" -> f;
			default -> "south";
		};
	}

	/** The artifact lot's box {@code [x0, y0, z0, x1, y1, z1]}, or null when it has none. */
	static int @Nullable [] box(JsonObject artifactLot) {
		if (!artifactLot.has("box") || !artifactLot.get("box").isJsonArray() || artifactLot.getAsJsonArray("box").size() != 6) return null;
		int[] b = new int[6];
		for (int i = 0; i < 6; i++) {
			Integer v = ModuleRef.exactInt(artifactLot.getAsJsonArray("box").get(i));
			if (v == null) return null;
			b[i] = v;
		}
		return b;
	}

	/** The artifact lot's frame {@code [W, D]} seen from its front (its front to the south): the box's spans, turned for an east or west front. */
	public static int @Nullable [] frame(JsonObject artifactLot) {
		int[] b = box(artifactLot);
		if (b == null) return null;
		int xs = b[3] - b[0] + 1, zs = b[5] - b[2] + 1;
		String f = front(artifactLot);
		return f.equals("east") || f.equals("west") ? new int[] {zs, xs} : new int[] {xs, zs};
	}

	/**
	 * Why the artifact lot can't hold this module's frame, or null: a box that isn't exactly the frame (W x D from its front), or an irrigation
	 * frame that differs from it (the variant is made for that frame; Architect's S-M5-14). Pure.
	 */
	public static @Nullable String frameProblem(JsonObject artifactLot, int[] moduleFrame) {
		int[] f = frame(artifactLot);
		if (f == null) return "the plan gave its lot no box";
		if (f[0] != moduleFrame[0] || f[1] != moduleFrame[1])
			return "its lot is " + f[0] + " by " + f[1] + ", and the module is made for exactly " + moduleFrame[0] + " by " + moduleFrame[1];
		JsonObject irr = irrigation(artifactLot);
		if (irr != null && irr.has("frame") && irr.get("frame").isJsonArray() && irr.getAsJsonArray("frame").size() == 2) {
			Integer w = ModuleRef.exactInt(irr.getAsJsonArray("frame").get(0)), d = ModuleRef.exactInt(irr.getAsJsonArray("frame").get(1));
			if (w == null || d == null || w != moduleFrame[0] || d != moduleFrame[1]) return "its water was laid for another frame than the module's";
		}
		return null;
	}

	/**
	 * Why the artifact lot can't hold this module, or null (GPT review 4): its frame ({@link #frameProblem}); a watered module's lot without IRR's
	 * water; a lot Architect sites on the land or on a wall's line (a module lot is a plain lot). Checked before any design is paid for, and again at the
	 * bind. Pure.
	 */
	public static @Nullable String lotProblem(JsonObject artifactLot, ModuleKind kind, ModuleOrder order) {
		if (artifactLot.has("site") || artifactLot.has("onWall")) return "Architect placed its lot on the land's own features or a wall, and a module lot is a plain lot";
		String f = frameProblem(artifactLot, kind.frame(order.params()));
		if (f != null) return f;
		JsonObject irr = irrigation(artifactLot);
		if (kind.irrigate() && (irr == null || !irr.has("lotCells") || !irr.get("lotCells").isJsonArray() || irr.getAsJsonArray("lotCells").isEmpty()))
			return "Architect couldn't water its field there";
		return null;
	}

	/** IRR's record on the artifact lot ({@code lots[].irrigation}), or null. */
	public static @Nullable JsonObject irrigation(JsonObject artifactLot) {
		return artifactLot.has("irrigation") && artifactLot.get("irrigation").isJsonObject() ? artifactLot.getAsJsonObject("irrigation") : null;
	}

	/**
	 * The artifact lot's geometry in M3's canonical shape ({@code ModulePlacement.geometry}): box, floor (the box's floor), front, frame (IRR's, else
	 * the box's from its front) and the irrigation cells. A re-check that moves any of it makes the variant again. Null when it has no box. Pure.
	 */
	public static @Nullable JsonObject geometry(JsonObject artifactLot) {
		int[] b = box(artifactLot);
		if (b == null) return null;
		JsonObject g = new JsonObject();
		JsonArray box = new JsonArray();
		for (int v : b) box.add(v);
		g.add("box", box);
		g.addProperty("floorY", b[1]);
		g.addProperty("front", front(artifactLot));
		JsonObject irr = irrigation(artifactLot);
		JsonArray frame = new JsonArray();
		if (irr != null && irr.has("frame") && irr.get("frame").isJsonArray()) frame = irr.getAsJsonArray("frame").deepCopy();
		else for (int v : frame(artifactLot)) frame.add(v);
		g.add("frame", frame);
		if (irr != null) {
			JsonObject cells = new JsonObject();
			if (irr.has("lotCells")) cells.add("lotCells", irr.get("lotCells").deepCopy());
			if (irr.has("cells")) cells.add("cells", irr.get("cells").deepCopy());
			g.add("irrigation", cells);
		}
		return Canon.sorted(g).getAsJsonObject();
	}

	/**
	 * (LIFT-S) The pieces of a quote that are one lot's own bill, as Architect's {@code Quote.LOT_PIECES} (LCAP): what a lot's {@code maxBillPerLot}
	 * is checked against at put-down and while it builds.
	 */
	public static final List<String> LOT_PIECES = List.of("lot:", "fill:", "mech:");

	/**
	 * A module lot's share of a quote: its own pieces of {@code bomParts} ({@link #LOT_PIECES}: {@code lot:<id>}, the bound entry's construction bill;
	 * {@code fill:<id>}, FK's filled containers; {@code mech:<id>}, a gate's mechanism), summed per item, as Architect's {@code Quote.lotBill}; empty
	 * when the quote has none. Null when a piece isn't a bill of items. Pure.
	 */
	public static @Nullable JsonObject share(JsonObject bomParts, String lotId) {
		Map<String, Integer> sum = new java.util.TreeMap<>();
		for (String p : LOT_PIECES) {
			String piece = p + lotId;
			if (!bomParts.has(piece)) continue;
			if (!bomParts.get(piece).isJsonObject()) return null;
			JsonObject bill = ModuleBound.clean(bomParts.getAsJsonObject(piece));
			if (bill == null) return null;
			for (var e : bill.entrySet()) sum.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum);
		}
		JsonObject out = new JsonObject();
		sum.forEach(out::addProperty);
		return out;
	}

	/** Whether the quote billed this lot at all (a {@code lot:<id>} piece): a module lot the quote doesn't name is not held to anything. Pure. */
	public static boolean billed(JsonObject bomParts, String lotId) {
		return bomParts.has("lot:" + lotId);
	}

	/**
	 * What a quote holds the module lots to: the orders (each holding its share when asked, cleared otherwise), and either why the quote can't hold
	 * them ({@code refused}), or the question for the player ({@code ask}), or neither (the realise goes on).
	 */
	public record Held(Map<String, ModuleOrder> orders, @Nullable String refused, @Nullable String ask, @Nullable String said) {}

	/**
	 * (docs/MODULES.md "M5 design" 6) Each module lot's share of a quote ({@link #share}) against its bound ({@link ModuleBound#over}, every item exact).
	 * A lot the quote doesn't bill, a share that isn't a bill or an order with no bound refuses. Past a bound, or a quote that may differ
	 * ({@code inexact}: {@code ModulePlacement.QuoteCheck.ask}) while some order hasn't had an inexact bill approved, every order holds its share for the
	 * player's word ({@link ModuleOrder#holding}; approving makes each share its bound). Pure.
	 */
	public static Held held(JsonObject bomParts, Map<String, ModuleOrder> orders, @Nullable String inexact) {
		Map<String, JsonObject> shares = new LinkedHashMap<>();
		List<String> over = new ArrayList<>();
		boolean unapproved = false;
		for (var e : orders.entrySet()) {
			String name = ModuleKind.of(e.getValue().moduleId()).map(ModuleKind::role).orElse(e.getKey().replace('_', ' '));
			if (!billed(bomParts, e.getKey())) return new Held(orders, "Architect's quote doesn't bill the working " + name + "'s lot", null, null);
			JsonObject share = share(bomParts, e.getKey());
			if (share == null) return new Held(orders, "Architect's quote of the working " + name + " is not a bill of items", null, null);
			if (e.getValue().bound() == null) return new Held(orders, "the working " + name + " has no bound to hold its bill to", null, null);
			shares.put(e.getKey(), share);
			var ov = ModuleBound.over(share, e.getValue().bound());
			if (!ov.isEmpty()) over.add("the " + name + "'s " + ModuleBound.words(ov, 100));
			if (!e.getValue().inexactApproved()) unapproved = true;
		}
		// (QD-2, M5b) a realise refused at put-down (BILL_CAP, saved on an order) is put to the player whatever the quote says, its bill never less
		// than what was approved, item by item, as the single-module path does (SettlementRunner.holdWhy)
		boolean refused = orders.values().stream().anyMatch(ModuleOrder::refusedAtPutDown);
		boolean ask = refused || !over.isEmpty() || (inexact != null && unapproved);
		Map<String, ModuleOrder> next = new LinkedHashMap<>();
		List<String> shown = new ArrayList<>();
		for (var e : orders.entrySet()) {
			JsonObject share = shares.get(e.getKey());
			JsonObject bill = ask && e.getValue().refusedAtPutDown() ? ModuleBound.atLeast(share, e.getValue().bound()) : share;
			next.put(e.getKey(), e.getValue().holding(ask ? bill : null));
			String name = ModuleKind.of(e.getValue().moduleId()).map(ModuleKind::role).orElse(e.getKey().replace('_', ' '));
			shown.add("the " + name + ": " + (bill.isEmpty() ? "nothing" : ModuleBound.bill(bill, 1000)));
		}
		if (!ask) return new Held(next, null, null, "From materials, " + String.join("; ", bounded(orders, shares)) + "; " + ModuleBound.CRASH_EXCEPTION + ".");
		String why = refused ? ModuleBound.PUT_DOWN_REFUSED
			: !over.isEmpty() ? "The working modules' bills came to more than their measured bounds: " + String.join("; ", over) + "."
			: "Architect can't quote the working modules' bills exactly (" + inexact + "), so I ask first.";
		return new Held(next, null, why + " Their bills: " + String.join("; ", shown) + ".", null);
	}

	/**
	 * (LIFT-S, Architect LCAP) What a composed realise from materials is sent with: each module lot's own cap ({@code maxBillPerLot}: its bound as
	 * saved, the measured one in the town's blocks or the bill the player approved since). No region-wide {@code maxBill} (the LIFT-S GPT review,
	 * P1): the rest of a composed town was never capped (it is billed as a Supplied plan v1 town's is), the player approves no bill for it, and a cap
	 * on it could hold the town on terrain past its allowance with no way to raise it, said as "more than you approved". Architect applies the lot caps
	 * alone then (LCAP: "lot caps with no maxBill cap only those lots"). {@code refused} says why it can't be sent capped (nothing is sent then).
	 */
	public record Caps(@Nullable Map<String, Map<String, Integer>> perLot, @Nullable String refused) {}

	/**
	 * (LIFT-S) The caps for a composed realise from materials of the quote's {@code bomParts} and the saved orders: only the lots this realise binds
	 * ({@code realised}: never an earlier revision's lot, which Architect refuses {@code unknown_lot}). An order with no readable bound, a lot share
	 * that isn't a bill, or a module lot this realise doesn't bind refuses. Deterministic (sorted). Pure.
	 */
	public static Caps caps(JsonObject bomParts, Map<String, ModuleOrder> orders, Set<String> realised) {
		Map<String, Map<String, Integer>> perLot = new java.util.TreeMap<>();
		for (var e : orders.entrySet()) {
			String name = ModuleKind.of(e.getValue().moduleId()).map(ModuleKind::role).orElse(e.getKey().replace('_', ' '));
			if (!realised.contains(e.getKey())) return new Caps(null, "the working " + name + "'s lot isn't one this realise builds");
			Map<String, Integer> cap = ModuleBound.cap(e.getValue().bound());
			if (cap == null) return new Caps(null, "the working " + name + " has no bound to hold its bill to");
			if (share(bomParts, e.getKey()) == null) return new Caps(null, "Architect's quote of the working " + name + " is not a bill of items");
			perLot.put(e.getKey(), cap);
		}
		return new Caps(java.util.Collections.unmodifiableMap(perLot), null);
	}

	/**
	 * (LIFT-S, Architect LCAP) A {@code BILL_CAP} refusal's detail: {@code lot:<id>} (that lot's own bill at put-down was past its cap), {@code
	 * unknown_lot:<id>} (a cap named a lot the realise doesn't bind: Steward's error, never asked again), or the region's ({@code ""}, anything else).
	 */
	public record CapRefusal(Scope scope, @Nullable String lot) {
		public enum Scope { LOT, UNKNOWN_LOT, REGION }

		public static CapRefusal of(@Nullable String detail) {
			String d = detail == null ? "" : detail.strip();
			if (d.startsWith("lot:") && d.length() > 4) return new CapRefusal(Scope.LOT, d.substring(4));
			if (d.startsWith("unknown_lot:") && d.length() > 12) return new CapRefusal(Scope.UNKNOWN_LOT, d.substring(12));
			return new CapRefusal(Scope.REGION, null);
		}
	}

	/** The name a module lot goes by in the steward's words: "working crop farm" (its kind's role), else its lot id in words. Pure. */
	public static String workingName(Map<String, ModuleOrder> orders, String lot) {
		ModuleOrder o = orders.get(lot);
		return "working " + (o == null ? lot.replace('_', ' ') : ModuleKind.of(o.moduleId()).map(ModuleKind::role).orElse(lot.replace('_', ' ')));
	}

	/**
	 * (LIFT-S) What the steward says when a composed realise is refused at put-down ({@code BILL_CAP}), nothing written or taken: a module lot past its
	 * measured limit is named; the region's keeps its words; an unknown lot is Steward's own error (the build stops). Pure.
	 */
	public static String putDownWords(CapRefusal r, Map<String, ModuleOrder> orders) {
		return switch (r.scope()) {
			case LOT -> "Not placed yet: when it was put down, the materials the " + workingName(orders, r.lot()) + " needed went past its limit. Nothing was"
				+ " taken from the stockpile; I'll ask you again.";
			case UNKNOWN_LOT -> "Not placed: Steward asked Architect to hold a lot the plan doesn't build (" + r.lot().replace('_', ' ') + ") to a bill. That's"
				+ " Steward's mistake, so nothing is built and I won't ask again. Nothing was taken from the stockpile; start it again.";
			case REGION -> "Not placed yet: the materials needed went past what you approved. Nothing was taken from the stockpile; I'll ask you again.";
		};
	}

	/** What an order's bound is called: "its approved limit" once the player approved a held bill as its bound, else "its measured limit". Pure. */
	static String limitWords(ModuleOrder o) {
		return o.inexactApproved() ? "its approved limit" : "its measured limit";
	}

	/** Each module's bill with its limit, for the words (Noah, 2026-11-15: the limit is shown with the quote). */
	private static List<String> bounded(Map<String, ModuleOrder> orders, Map<String, JsonObject> shares) {
		List<String> out = new ArrayList<>();
		for (var e : orders.entrySet()) {
			String name = ModuleKind.of(e.getValue().moduleId()).map(ModuleKind::role).orElse(e.getKey().replace('_', ' '));
			JsonObject share = shares.get(e.getKey());
			// (batch-077 review P3) a bound the player approved since (a held bill, ModuleOrder#approvedHeld) is no longer the measured one
			out.add("the working " + name + " takes " + (share == null || share.isEmpty() ? "nothing" : ModuleBound.bill(share, 1000)) + " (" + limitWords(e.getValue())
				+ ": " + ModuleBound.bill(e.getValue().bound(), 1000) + ")");
		}
		return out;
	}

	/** What the steward says of a module lot left out of the town ({@code name}: the lot's own words; {@code why}: {@link Decision#why}). Pure. */
	public static String leftOutWords(String name, String why) {
		String w = why.endsWith(".") ? why.substring(0, why.length() - 1) : why;
		return "The plan's " + name + " is left out of the town: " + w + ". It isn't designed as an ordinary building in its place.";
	}

	/** What the steward says of a module lot the town will build from the catalog, with the params it chose over the plan's, and in survival its bound's rule. Pure. */
	public static String boundWords(String name, ModuleKind kind, ModuleOrder o, @Nullable String changed, boolean materials) {
		int[] f = kind.frame(o.params());
		return "The plan's " + name + " is a working " + kind.role() + " from Steward's measured catalog (" + o.moduleId() + "@" + o.version() + ", " + f[0] + " by " + f[1]
			+ (o.rate() == null ? "" : ", " + o.rate()) + "): no design, $0." + (changed == null ? "" : " (" + changed + ".)")
			+ (kind.sign() == ModuleKind.Sign.STORES ? (sorts(o) ? " " + sortWords(o) : " It's a plain hall: it doesn't sort.") : "")
			+ (materials ? " From materials, its bill is held to its measured bound: if it would take more, I ask first; " + ModuleBound.CRASH_EXCEPTION + "." : "")
			+ (ModuleOffer.namesLateCargo(kind) ? " " + ModuleOffer.LATE_CARGO_WORDS : "");
	}

	/** (M5b) A composed sorting hall's words: its slots and their items, what it doesn't sort, and the filler it must never be given. */
	static String sortWords(ModuleOrder o) {
		List<String> names = new ArrayList<>();
		for (String it : StorageHallLot.items(o.params()).items()) names.add(ModuleBound.plain(it));
		return "It sorts: " + names.size() + " slots for " + String.join(", ", names) + "; anything else, and tools, armour, potions, renamed or enchanted items or"
			+ " anything that stacks to 16, goes to its overflow chest. Never put petrified oak slabs in it.";
	}

	/** Whether an order's hall sorts (its slots a whole number above 0). */
	private static boolean sorts(ModuleOrder o) {
		Integer n = ModuleRef.exactInt(o.params().get("slots"));
		return n != null && n > 0;
	}

	/** The orders keyed by lot id, in the plan's lot order (what is saved with the build). */
	public static Map<String, ModuleOrder> ordered(List<Asked> asked, Map<String, ModuleOrder> orders) {
		Map<String, ModuleOrder> out = new LinkedHashMap<>();
		for (Asked a : asked) if (orders.containsKey(a.lotId())) out.put(a.lotId(), orders.get(a.lotId()));
		return out;
	}
}
