package dev.larattalabs.steward.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How one catalog module is offered and placed (docs/MODULES.md 4.1, 4.2): the offer's rule key, the seeds that are its sign, its discrete sizes
 * (largest first, so a {@code NO_ROOM} tries the next smaller one as a new plan), the grow lot it asks Architect for, and what the town must offer
 * it (water: {@code irrigate}; not a freezing biome). The catalog says what the module <i>is</i>; this says how Steward offers it. Pure.
 *
 * <p>The module id is a parameter of every path (M3 builds and tests on M1's {@code fixture_plot} while M2 measures the real {@code crop_farm}):
 * {@link #of(String)} knows each module Steward can offer.
 */
public interface ModuleKind {
	/** The catalog id. */
	String id();

	/** The proposal rule's key, {@code module.<id>}. */
	default String rule() {
		return RULE_PREFIX + id();
	}

	/** The rule keys' prefix: a proposal whose key starts with it is a module offer. */
	String RULE_PREFIX = "module.";

	/** What the steward calls it ("a watered, lit field"). */
	String title();

	/** The lot's role in the settlement's terms ("crop farm"). */
	String role();

	/** The grow lot's type (Architect infers a farm from it: IRR irrigates only farm lots). */
	String lotType();

	/** Whether the grow lot asks for IRR's water ({@code irrigate: true}, S-M5-2). */
	boolean irrigate();

	/** Whether the module needs a biome where water does not freeze (the farm: IRR refuses a freezing biome). */
	boolean warmOnly();

	/** The decorative building types whose rules this module's decline retires ({@code farm}: the player said no to farms). */
	Set<String> decorative();

	/** Seed item id ({@code minecraft:wheat_seeds}) -> the crop param's value it plants. */
	Map<String, String> seeds();

	/** The param that names the crop. */
	String cropParam();

	/** The discrete sizes, largest first, each as the size params (the crop is set apart). */
	List<JsonObject> sizes();

	/** The seeds a field of these params takes to plant (one per field cell). */
	int cells(JsonObject params);

	/** The lot's frame {@code [W, D]} for these params (W = field + 2, D = field + 3: IRR's frame). */
	int[] frame(JsonObject params);

	/** The grow lot ({@code {id, type, size: [W, D], irrigate?, field?}}); Steward's ext is added by the caller. */
	JsonObject growLot(String lotId, JsonObject params);

	/** The params of a size with a crop: the size's params plus the crop. */
	default JsonObject params(JsonObject size, String crop) {
		JsonObject o = size.deepCopy();
		o.addProperty(cropParam(), crop);
		return o;
	}

	/** The size params of these params (the crop set apart), for comparing with {@link #sizes()}. */
	default JsonObject sizeOf(JsonObject params) {
		JsonObject o = params.deepCopy();
		o.remove(cropParam());
		return o;
	}

	/** The next smaller size after these params' size, or empty when they are the smallest (or not one of the sizes). */
	default Optional<JsonObject> smaller(JsonObject params) {
		JsonObject size = Canon.sorted(sizeOf(params)).getAsJsonObject();
		List<JsonObject> all = sizes();
		for (int i = 0; i < all.size(); i++) {
			if (Canon.sorted(all.get(i)).equals(size)) return i + 1 < all.size() ? Optional.of(params(all.get(i + 1), params.get(cropParam()).getAsString())) : Optional.empty();
		}
		return Optional.empty();
	}

	/** The module kinds Steward offers, by catalog id. */
	static Optional<ModuleKind> of(@Nullable String id) {
		if (id == null) return Optional.empty();
		return switch (id) {
			case FixturePlot.ID -> Optional.of(FixturePlot.INSTANCE);
			case CropFarm.ID -> Optional.of(CropFarm.INSTANCE);
			default -> Optional.empty();
		};
	}

	/** The catalog id a rule key offers ({@code module.crop_farm} -> {@code crop_farm}), or null for any other key. */
	static @Nullable String idOf(@Nullable String ruleKey) {
		return ruleKey != null && ruleKey.startsWith(RULE_PREFIX) && ruleKey.length() > RULE_PREFIX.length() ? ruleKey.substring(RULE_PREFIX.length()) : null;
	}

	/**
	 * {@code crop_farm@1} (docs/MODULES.md 3.1, slice M2): a watered, lit field, sides 9, 18 or 27 each way, wheat, carrots, potatoes or beetroots.
	 * Its lot is M2's {@link CropFarmLot#growLot} (type {@code crop_farm}, IRR's {@code irrigate}, FC's {@code field}); its cells are
	 * {@link CropFarmLot}'s own field (the sources, their posts and the port left out: a 9x9 plants 74). Warm biomes only (IRR refuses a freezing one).
	 */
	final class CropFarm implements ModuleKind {
		public static final String ID = "crop_farm";
		public static final CropFarm INSTANCE = new CropFarm();

		private CropFarm() {}

		@Override public String id() { return ID; }
		@Override public String title() { return "a watered, lit field"; }
		@Override public String role() { return "crop farm"; }
		@Override public String lotType() { return "crop_farm"; }
		@Override public boolean irrigate() { return true; }
		@Override public boolean warmOnly() { return true; }
		@Override public Set<String> decorative() { return Set.of("farm"); }
		@Override public Map<String, String> seeds() {
			return Map.of("minecraft:wheat_seeds", "wheat", "minecraft:carrot", "carrots", "minecraft:potato", "potatoes", "minecraft:beetroot_seeds", "beetroots");
		}
		@Override public String cropParam() { return "crop"; }

		/** Every width x depth of the measured sides, the most cells first (a tie: the wider first). */
		@Override
		public List<JsonObject> sizes() {
			List<int[]> wd = new java.util.ArrayList<>();
			for (int w : CropFarmLot.SIDES) for (int d : CropFarmLot.SIDES) wd.add(new int[] {w, d});
			wd.sort((a, b) -> a[0] * a[1] != b[0] * b[1] ? Integer.compare(b[0] * b[1], a[0] * a[1]) : Integer.compare(b[0], a[0]));
			List<JsonObject> out = new java.util.ArrayList<>();
			for (int[] x : wd) {
				JsonObject o = new JsonObject();
				o.addProperty("width", x[0]);
				o.addProperty("depth", x[1]);
				out.add(o);
			}
			return List.copyOf(out);
		}

		@Override public int cells(JsonObject params) { return CropFarmLot.layout(params.get("width").getAsInt(), params.get("depth").getAsInt()).field().size(); }

		@Override public int[] frame(JsonObject params) {
			var l = CropFarmLot.layout(params.get("width").getAsInt(), params.get("depth").getAsInt());
			return new int[] {l.w(), l.d()};
		}

		@Override public JsonObject growLot(String lotId, JsonObject params) {
			return CropFarmLot.growLot(lotId, params.get("width").getAsInt(), params.get("depth").getAsInt());
		}
	}

	/**
	 * M1's fixture module {@code fixture_plot@1} (fixtures/modules/catalog/fixture_plot/1): a fenced plot, sides 5 and 9, wheat or beetroot, W = side
	 * + 2 and D = side + 3 as crop_farm@1's geometry. No water (it is not a farm lot to Architect), so it also grows through an adopted street town
	 * (slice B), where IRR can't irrigate.
	 */
	final class FixturePlot implements ModuleKind {
		public static final String ID = "fixture_plot";
		public static final FixturePlot INSTANCE = new FixturePlot();

		private FixturePlot() {}

		@Override public String id() { return ID; }
		@Override public String title() { return "a fenced, lit plot"; }
		@Override public String role() { return "fenced plot"; }
		@Override public String lotType() { return "module_fixture"; }
		@Override public boolean irrigate() { return false; }
		@Override public boolean warmOnly() { return false; }
		@Override public Set<String> decorative() { return Set.of("farm"); }
		@Override public Map<String, String> seeds() { return Map.of("minecraft:wheat_seeds", "wheat", "minecraft:beetroot_seeds", "beetroots"); }
		@Override public String cropParam() { return "crop"; }

		@Override
		public List<JsonObject> sizes() {
			return List.of(side(9), side(5));
		}

		private static JsonObject side(int n) {
			JsonObject o = new JsonObject();
			o.addProperty("side", n);
			return o;
		}

		private static int sideOf(JsonObject params) {
			return params.get("side").getAsInt();
		}

		@Override public int cells(JsonObject params) { int s = sideOf(params); return s * s; }

		@Override public int[] frame(JsonObject params) { int s = sideOf(params); return new int[] {s + 2, s + 3}; }

		@Override
		public JsonObject growLot(String lotId, JsonObject params) {
			int[] f = frame(params);
			JsonObject lot = new JsonObject();
			lot.addProperty("id", lotId);
			lot.addProperty("type", lotType());
			JsonArray size = new JsonArray();
			size.add(f[0]);
			size.add(f[1]);
			lot.add("size", size);
			return lot;
		}
	}
}
