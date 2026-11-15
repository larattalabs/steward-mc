package dev.larattalabs.steward.module;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Whether a module may be offered or accepted here, and with which params (docs/MODULES.md 4.1 "Eligibility", "Seeds and the stockpile", 4.3,
 * 4.4). Decided before anything is offered, and again at Accept (the town may have changed). Pure: the caller reads the world into a {@link Town}.
 */
public final class ModuleOffer {
	private ModuleOffer() {}

	/** How a module would be placed in this town (docs/MODULES.md 4.2). */
	public enum Path {
		/** A region town built at once: growth A. */
		REGION,
		/** A region town built from materials: growth A2, billed from the stockpile. */
		REGION_MATERIALS,
		/** A street town Architect can take on as one region at its first growth: slice B, then A. */
		ADOPT,
		/** (slice SST) A street town in a world that builds from materials, taken on as a survival region (Architect SAD), then grown as A2. */
		ADOPT_MATERIALS,
		/** None: a bent street town, the legacy street path, a town in the wrong mode, or an Architect without growth. */
		NONE;

		/** Whether a module placed this way is built from the stockpile and billed (a bound to approve). */
		public boolean materials() {
			return this == REGION_MATERIALS || this == ADOPT_MATERIALS;
		}
	}

	/**
	 * What the steward sees of the town and the player.
	 *
	 * @param path how it would grow; {@code pathWhy} says why not when NONE
	 * @param built a town built from materials is fully built (it grows only then)
	 * @param percent how far it is built (for the words)
	 * @param district the next building would go to a district that doesn't grow by revisions (a square, or one being built)
	 * @param freezing the town's biome freezes water
	 * @param seeds seed item id -> count the player carries
	 * @param stock item id -> count the stockpile holds (a town built from materials: construction draws only from it)
	 */
	public record Town(Path path, @Nullable String pathWhy, boolean built, int percent, boolean district, boolean freezing, Map<String, Integer> seeds,
		Map<String, Integer> stock, dev.larattalabs.steward.model.Progress.@Nullable Tier tier) {
		public Town {
			seeds = seeds == null ? Map.of() : Map.copyOf(seeds);
			stock = stock == null ? Map.of() : Map.copyOf(stock);
		}

		/** As before the sorting hall (M4b): the player's tier not known (a storage hall is then offered plain). */
		public Town(Path path, @Nullable String pathWhy, boolean built, int percent, boolean district, boolean freezing, Map<String, Integer> seeds,
			Map<String, Integer> stock) {
			this(path, pathWhy, built, percent, district, freezing, seeds, stock, null);
		}

		/** Whether the player has reached the Nether tier (a sorting hall's comparators need quartz: MODULES 3.2 "Tier"). */
		public boolean nether() {
			return tier != null && tier.ordinal() >= dev.larattalabs.steward.model.Progress.Tier.NETHER.ordinal();
		}
	}

	/** The seeds a module's sign needs: 8 or more of one crop it grows (today's {@code farm} sign). */
	public static final int SIGN_SEEDS = 8;

	/** The kinds of items a storage module's sign needs (docs/MODULES.md 4.1): the stockpile and the player's inventory hold 12 or more. */
	public static final int SIGN_KINDS = 12;

	/** The stacks a double chest holds. */
	static final int DOUBLE_CHEST_STACKS = 54;
	/** The fewest bulk double chests a storage hall is offered with. */
	static final int BULK_MIN = 2;

	/**
	 * A decision: an order to offer ({@code why} null), or why not, in the steward's words. For a module signed by seeds, {@code crop} and the seeds
	 * needed and held; for one signed by stores ({@link ModuleKind.Sign#STORES}), {@code crop} null, {@code seedsNeeded} the stacks held and
	 * {@code seedsHeld} the kinds of items held (the offer's words).
	 */
	public record Decision(@Nullable ModuleOrder order, @Nullable String why, @Nullable String crop, int seedsNeeded, int seedsHeld) {
		public boolean ok() {
			return why == null && order != null;
		}

		static Decision no(String why) {
			return new Decision(null, why, null, 0, 0);
		}
	}

	/**
	 * Whether the module is offered here: the catalog's verdict, the town's path, its biome and mode, and in survival a measured bound; with the crop
	 * the player carries most of and the largest size those seeds plant (a town built from materials counts its stockpile too, as construction draws
	 * only from it). {@code asked} (a module asked for in talk): params the player named, kept when they are a measured branch.
	 */
	public static Decision decide(ModuleKind kind, ModuleGate.Verdict verdict, Town town, @Nullable JsonObject asked) {
		return decide(kind, verdict, town, asked, (e, p) -> ModuleBound.of(e.measured(), p));
	}

	/** As above, with where the bound for an entry's params comes from (the measurement's; the dev source's for a fixture). */
	public static Decision decide(ModuleKind kind, ModuleGate.Verdict verdict, Town town, @Nullable JsonObject asked,
		java.util.function.BiFunction<ModuleCatalog.Entry, JsonObject, @Nullable JsonObject> bounds) {
		if (!verdict.ok() || verdict.entry() == null) return Decision.no("a working " + kind.role() + " can't be offered here: " + verdict.message());
		var e = verdict.entry();
		if (town.district()) return Decision.no("the next building goes to a district that doesn't grow by Architect's revisions");
		switch (town.path()) {
			case NONE -> {
				return Decision.no(town.pathWhy() == null ? "this town can't grow through Architect, and a working " + kind.role() + " is placed only that way" : town.pathWhy());
			}
			case REGION_MATERIALS, ADOPT_MATERIALS -> {
				if (!town.built()) return Decision.no("the town is still being built from the stockpile (" + town.percent() + "%): a working " + kind.role() + " can go in once it's built");
			}
			case ADOPT -> {
				// (slice A2L) Architect's IG-b (v0.66.0, S-M5-8) gives an adopted town's irrigated growth its own street, lots and fluids stages, so a
				// watered module grows there too. ADOPT is the creative adoption (the world builds at once, a Patron settlement); since SST, Architect SAD's
				// survival adoption (ADOPT_MATERIALS, above) grows from materials, watered included (the water is free)
			}
			default -> { }
		}
		if (kind.warmOnly() && town.freezing()) return Decision.no("the water would freeze in this biome");
		boolean materials = town.path().materials();
		if (kind.sign() == ModuleKind.Sign.STORES) return stores(kind, e, town, asked, materials, bounds);
		// the crop: the one named, else the one the player carries most of (stockpile included where construction draws from it)
		Map<String, Integer> held = new TreeMap<>();
		for (var s : kind.seeds().entrySet()) {
			int n = town.seeds().getOrDefault(s.getKey(), 0) + (materials ? town.stock().getOrDefault(s.getKey(), 0) : 0);
			held.merge(s.getValue(), n, Integer::sum);
		}
		String crop = asked != null && asked.has(kind.cropParam()) && asked.get(kind.cropParam()).isJsonPrimitive() && held.containsKey(asked.get(kind.cropParam()).getAsString())
			? asked.get(kind.cropParam()).getAsString() : null;
		if (crop == null) crop = held.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(java.util.Comparator.reverseOrder())))
			.map(Map.Entry::getKey).orElse(null);
		if (crop == null) return Decision.no("it grows nothing the steward knows");
		int have = held.getOrDefault(crop, 0);
		// the size: the one named when it is measured, else the largest those seeds plant (the smallest at least)
		List<JsonObject> sizes = kind.sizes();
		JsonObject size = null;
		if (asked != null) {
			JsonObject want = kind.sizeOf(asked);
			want.remove(kind.cropParam());
			if (want.size() > 0) for (JsonObject s : sizes) if (Canon.sorted(s).equals(Canon.sorted(want))) size = s;
		}
		if (size == null) {
			size = sizes.get(sizes.size() - 1);
			for (JsonObject s : sizes) if (kind.cells(kind.params(s, crop)) <= have) { size = s; break; }
		}
		var p = ModuleGate.params(e, kind.params(size, crop));
		if (!p.ok()) return Decision.no("its size isn't one that was measured: " + p.refused());
		JsonObject bound = null;
		if (materials) {
			bound = bounds.apply(e, p.values());
			if (bound == null) return Decision.no("its bill from the stockpile wasn't measured for that size, so there is no bound to approve");
		}
		return new Decision(new ModuleOrder(e.id(), e.version(), e.programSha(), p.values(), bound, rate(e.measured(), p.values()), false, false), null, crop,
			kind.cells(p.values()), have);
	}

	/** The items the player carries and the stockpile holds, by kind (what a storage module's sign and size read). */
	public static Map<String, Integer> stores(Town town) {
		Map<String, Integer> all = new TreeMap<>(town.seeds());
		town.stock().forEach((k, n) -> all.merge(k, n, Integer::sum));
		all.values().removeIf(n -> n == null || n <= 0);
		return all;
	}

	/** The stacks of 64 these stores fill (an estimate: a 16-stack item counts as a 64 one). */
	public static int stacks(Map<String, Integer> stores) {
		long n = 0;
		for (int c : stores.values()) n += (c + 63) / 64;
		return (int) Math.min(Integer.MAX_VALUE, n);
	}

	/**
	 * A storage hall (docs/MODULES.md 3.2, docs/MEASURE-M4.md 2): this version builds the plain hall ({@code slots 0}). Its bulk is the stacks held at
	 * 54 a double chest, rounded up, from {@link #BULK_MIN} to the most measured; a bulk asked for in talk is kept when it is a measured branch.
	 */
	static Decision stores(ModuleKind kind, ModuleCatalog.Entry e, Town town, @Nullable JsonObject asked, boolean materials,
		java.util.function.BiFunction<ModuleCatalog.Entry, JsonObject, @Nullable JsonObject> bounds) {
		Map<String, Integer> held = stores(town);
		int stacks = stacks(held);
		var branch = e.envelope().branches().get(kind.cropParam());
		int most = BULK_MIN;
		if (branch != null) for (var v : branch) if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) most = Math.max(most, v.getAsInt());
		int bulk = Math.max(BULK_MIN, Math.min(most, (stacks + DOUBLE_CHEST_STACKS - 1) / DOUBLE_CHEST_STACKS));
		if (asked != null && asked.has(kind.cropParam()) && asked.get(kind.cropParam()).isJsonPrimitive() && asked.get(kind.cropParam()).getAsJsonPrimitive().isNumber()) {
			var want = asked.get(kind.cropParam());
			if (branch != null && branch.contains(want)) bulk = want.getAsInt();
		}
		JsonObject want = kind.params(kind.sizes().get(0), Integer.toString(bulk));
		// (M4b) the sorting branches: from the Nether tier on, when the catalog's version measured them; the slots the settlement's distinct filter
		// items fill (most held first), at least the fewest measured; items named in talk are kept when they keep the rule
		List<Integer> sortSlots = new ArrayList<>();
		var sb = e.envelope().branches().get("slots");
		if (sb != null) for (var v : sb) if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsInt() > 0) sortSlots.add(v.getAsInt());
		java.util.Collections.sort(sortSlots);
		if (!sortSlots.isEmpty() && town.nether()) {
			if (asked != null && asked.has("slots") && asked.get("slots").isJsonPrimitive() && sortSlots.contains(asked.get("slots").getAsInt())) {
				JsonObject a = want.deepCopy();
				for (var en : asked.entrySet()) if (en.getKey().equals("slots") || en.getKey().matches("item\\d+")) a.add(en.getKey(), en.getValue().deepCopy());
				if (StorageHallLot.items(a).ok()) want = a;
			}
			if (!want.has("slots") || want.get("slots").getAsInt() == 0) {
				List<String> kinds = filterKinds(held);
				int slots = 0;
				for (int n : sortSlots) if (n <= kinds.size()) slots = n;
				if (slots > 0) {
					want.addProperty("slots", slots);
					for (int i = 0; i < slots; i++) want.addProperty("item" + (i + 1), kinds.get(i));
				}
			}
		}
		var p = ModuleGate.params(e, want);
		if (!p.ok()) return Decision.no("its size isn't one that was measured: " + p.refused());
		JsonObject bound = null;
		if (materials) {
			bound = bounds.apply(e, p.values());
			if (bound == null) return Decision.no("its bill from the stockpile wasn't measured for that size, so there is no bound to approve");
		}
		String rate = p.values().has("slots") && p.values().get("slots").getAsInt() > 0 ? hallRate(e.measured(), p.values().get("slots").getAsInt()) : rate(e.measured(), p.values());
		return new Decision(new ModuleOrder(e.id(), e.version(), e.programSha(), p.values(), bound, rate, false, false), null, null,
			stacks, held.size());
	}

	/** The filter domain's keys among these stores, most held first (ties by key): what a sorting hall's slots sort (MEASURE-M4 12.4). */
	public static List<String> filterKinds(Map<String, Integer> held) {
		List<Map.Entry<String, Integer>> es = new ArrayList<>();
		for (var en : held.entrySet()) {
			String id = en.getKey();
			if (!id.startsWith("minecraft:") || en.getValue() == null || en.getValue() <= 0) continue;
			if (StorageHallLot.FILTERABLE.contains(id.substring("minecraft:".length()))) es.add(en);
		}
		es.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
		List<String> out = new ArrayList<>();
		for (var en : es) out.add(en.getKey().substring("minecraft:".length()));
		return out;
	}

	/** A sorting hall's measured rate (measured.json {@code rates}: {@code [{params: {slots}, perSecond}]}), as the offer says it, or null. */
	public static @Nullable String hallRate(ModuleCatalog.@Nullable Measured m, int slots) {
		if (m == null || m.raw() == null || !m.raw().has("rates") || !m.raw().get("rates").isJsonArray()) return null;
		for (var r : m.raw().getAsJsonArray("rates")) {
			if (!r.isJsonObject()) continue;
			JsonObject o = r.getAsJsonObject();
			if (!o.has("params") || !o.get("params").isJsonObject() || !o.getAsJsonObject("params").has("slots") || o.getAsJsonObject("params").get("slots").getAsInt() != slots) continue;
			if (!o.has("perSecond") || !o.get("perSecond").isJsonPrimitive()) return null;
			double v = o.get("perSecond").getAsDouble();
			if (!(v > 0)) return null;
			return String.format(java.util.Locale.ROOT, "about %.1f items a second", Math.floor(v * 10) / 10);
		}
		return null;
	}

	/** The measured rate for these params as the offer says it, from measured.json {@code rates} ({@code [{params, perDay, item}]}), or null. */
	public static @Nullable String rate(ModuleCatalog.@Nullable Measured m, JsonObject params) {
		if (m == null || m.raw() == null || !m.raw().has("rates") || !m.raw().get("rates").isJsonArray()) return null;
		for (var r : m.raw().getAsJsonArray("rates")) {
			if (!r.isJsonObject()) continue;
			JsonObject o = r.getAsJsonObject();
			if (!o.has("params") || !Canon.sorted(o.get("params")).equals(Canon.sorted(params))) continue;
			Integer n = o.has("perDay") ? ModuleRef.exactInt(o.get("perDay")) : null;
			String item = o.has("item") && o.get("item").isJsonPrimitive() ? o.get("item").getAsString() : null;
			if (n == null || n < 1 || item == null) return null;
			return "about " + n + " " + ModuleBound.plain(item) + " a day once it's going";
		}
		return null;
	}

	/** What the offer says (4.1): what it is, its measured rate, the seeds, in survival the bound. */
	public static String why(ModuleKind kind, Decision d, boolean materials, boolean lampFallback) {
		List<String> parts = new ArrayList<>();
		ModuleOrder o = d.order();
		if (kind.sign() == ModuleKind.Sign.STORES && o.params().has("slots") && o.params().get("slots").getAsInt() > 0) {
			int[] f = kind.frame(o.params());
			int bulk = o.params().get(kind.cropParam()).getAsInt();
			int slots = o.params().get("slots").getAsInt();
			List<String> names = new ArrayList<>();
			for (String it : StorageHallLot.items(o.params()).items()) names.add(ModuleBound.plain(it));
			parts.add("Your stores are getting mixed: " + d.seedsHeld() + " kinds of items. I can raise a sorting hall by the town (" + f[0] + " by " + f[1] + "): " + slots
				+ " slots for " + String.join(", ", names) + ", an overflow chest, " + bulk + " double chest" + (bulk == 1 ? "" : "s") + " and an input chest at the line's head"
				+ (o.rate() == null ? "" : "; " + o.rate()) + ".");
			parts.add("It won't sort tools, armour, potions, renamed or enchanted items, or anything that stacks to 16: those go to overflow. Never put petrified oak slabs in it.");
			parts.add(materials ? dev.larattalabs.steward.inbox.InboxModel.BILL + ModuleBound.bill(o.bound(), Integer.MAX_VALUE) + "." : "It's free.");
			if (lampFallback) parts.add("It's lit by plain lanterns: your style's lamp is too dim.");
			return String.join(" ", parts);
		}
		if (kind.sign() == ModuleKind.Sign.STORES) {
			int[] f = kind.frame(o.params());
			int bulk = o.params().get(kind.cropParam()).getAsInt();
			parts.add("Your stores are getting mixed: " + d.seedsHeld() + " kinds of items. I can raise " + kind.title() + " by the town (" + f[0] + " by " + f[1] + "): "
				+ bulk + " double chest" + (bulk == 1 ? "" : "s") + " and an input chest by the door.");
			parts.add(materials ? dev.larattalabs.steward.inbox.InboxModel.BILL + ModuleBound.bill(o.bound(), Integer.MAX_VALUE) + "." : "It's free.");
			if (lampFallback) parts.add("It's lit by plain lanterns: your style's lamp is too dim.");
			return String.join(" ", parts);
		}
		String crop = d.crop() == null ? "" : d.crop().replace('_', ' ');
		int[] f = kind.frame(o.params());
		parts.add("You carry " + crop + " seeds. I can lay " + kind.title() + " by the town (" + f[0] + " by " + f[1] + ")" + (o.rate() == null ? "" : ": " + o.rate()) + ".");
		if (materials) {
			parts.add("Planting it takes " + d.seedsNeeded() + " " + crop + " seeds from the stockpile" + (d.seedsHeld() < d.seedsNeeded() ? " (put " + (d.seedsNeeded() - d.seedsHeld())
				+ " more in it)" : "") + ". " + dev.larattalabs.steward.inbox.InboxModel.BILL + ModuleBound.bill(o.bound(), Integer.MAX_VALUE) + ".");
		} else parts.add("It's free, and planted as it's placed.");
		if (lampFallback) parts.add("It's lit by plain lanterns: your style's lamp is too dim.");
		return String.join(" ", parts);
	}
}
