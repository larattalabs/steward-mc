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
		/** None: a north-south or Supplied street town, the legacy street path, a town in the wrong mode, or an Architect without growth. */
		NONE
	}

	/**
	 * What the steward sees of the town and the player.
	 *
	 * @param path how it would grow; {@code pathWhy} says why not when NONE
	 * @param built a town built from materials is fully built (it grows only then)
	 * @param percent how far it is built (for the words)
	 * @param district the next building would go to a district (districts don't grow by revisions yet)
	 * @param freezing the town's biome freezes water
	 * @param seeds seed item id -> count the player carries
	 * @param stock item id -> count the stockpile holds (a town built from materials: construction draws only from it)
	 */
	public record Town(Path path, @Nullable String pathWhy, boolean built, int percent, boolean district, boolean freezing, Map<String, Integer> seeds,
		Map<String, Integer> stock) {
		public Town {
			seeds = seeds == null ? Map.of() : Map.copyOf(seeds);
			stock = stock == null ? Map.of() : Map.copyOf(stock);
		}
	}

	/** The seeds a module's sign needs: 8 or more of one crop it grows (today's {@code farm} sign). */
	public static final int SIGN_SEEDS = 8;

	/** A decision: an order to offer ({@code why} null), or why not, in the steward's words. */
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
		if (town.district()) return Decision.no("the next building goes to a district, and districts don't grow by Architect's revisions yet");
		switch (town.path()) {
			case NONE -> {
				return Decision.no(town.pathWhy() == null ? "this town can't grow through Architect, and a working " + kind.role() + " is placed only that way" : town.pathWhy());
			}
			case REGION_MATERIALS -> {
				if (!town.built()) return Decision.no("the town is still being built from the stockpile (" + town.percent() + "%): a working " + kind.role() + " can go in once it's built");
			}
			case ADOPT -> {
				// Architect's IG refuses water in an adopted town (its one `adopted` stage has none between the pads and the lots): ARCHITECT-ASKS S-M5-8
				if (kind.irrigate()) return Decision.no("a street town can't take a watered field yet (Architect irrigates only towns it laid out)");
			}
			default -> { }
		}
		if (kind.warmOnly() && town.freezing()) return Decision.no("the water would freeze in this biome");
		boolean materials = town.path() == Path.REGION_MATERIALS;
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
		String crop = d.crop() == null ? "" : d.crop().replace('_', ' ');
		int[] f = kind.frame(o.params());
		parts.add("You carry " + crop + " seeds. I can lay " + kind.title() + " by the town (" + f[0] + " by " + f[1] + ")" + (o.rate() == null ? "" : ": " + o.rate()) + ".");
		if (materials) {
			parts.add("Planting it takes " + d.seedsNeeded() + " " + crop + " seeds from the stockpile" + (d.seedsHeld() < d.seedsNeeded() ? " (put " + (d.seedsNeeded() - d.seedsHeld())
				+ " more in it)" : "") + ". The whole bill is at most " + ModuleBound.bill(o.bound(), 6) + ".");
		} else parts.add("It's free, and planted as it's placed.");
		if (lampFallback) parts.add("It's lit by plain lanterns: your style's lamp is too dim.");
		return String.join(" ", parts);
	}
}
