package dev.larattalabs.steward.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.DesignRequest;
import dev.larattalabs.architect.api.LotSpec;
import dev.larattalabs.steward.module.Canon;
import dev.larattalabs.steward.module.ModuleBinding;
import dev.larattalabs.steward.module.ModuleOrder;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The pure parts of placing a module lot on growth (docs/MODULES.md 4.2 steps 2-4, "A stale plan", "Recovery"): the lot's geometry as the
 * binding hashes it, its reserved cells from IRR's report, whether a saved binding still holds, and the key a re-plan's variant is made under.
 */
public final class ModulePlacement {
	private ModulePlacement() {}

	/** Architect's report of an irrigated lot (IG): {@code LotSpec.ext["architect_mc:lotIrrigation"]}. */
	public static final String IRRIGATION_EXT = "architect_mc:lotIrrigation";

	/**
	 * The lot's geometry: its box, floor and front, its frame, and IRR's irrigation cells (lotCells and world cells), canonical. A re-plan that
	 * changes any of it makes the variant again (4.2 "A stale plan"); one that moves nothing keeps the variant.
	 */
	public static JsonObject geometry(LotSpec l) {
		JsonObject g = new JsonObject();
		var b = l.lot();
		JsonArray box = new JsonArray();
		for (int v : new int[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}) box.add(v);
		g.add("box", box);
		g.addProperty("floorY", l.floorY());
		g.addProperty("front", front(l));
		JsonObject irr = irrigation(l);
		JsonArray frame = new JsonArray();
		if (irr != null && irr.has("frame") && irr.get("frame").isJsonArray()) frame = irr.getAsJsonArray("frame").deepCopy();
		else {
			frame.add(b.getXSpan());
			frame.add(b.getZSpan());
		}
		g.add("frame", frame);
		if (irr != null) {
			JsonObject cells = new JsonObject();
			if (irr.has("lotCells")) cells.add("lotCells", irr.get("lotCells").deepCopy());
			if (irr.has("cells")) cells.add("cells", irr.get("cells").deepCopy());
			g.add("irrigation", cells);
		}
		return Canon.sorted(g).getAsJsonObject();
	}

	/** The lot's front in Architect's words ({@code south}). */
	public static String front(LotSpec l) {
		return (l.front() == null ? net.minecraft.core.Direction.SOUTH : l.front()).getName();
	}

	/** IRR's record on the lot, or null (a lot without water). */
	public static @Nullable JsonObject irrigation(LotSpec l) {
		return l.ext() != null && l.ext().has(IRRIGATION_EXT) && l.ext().get(IRRIGATION_EXT).isJsonObject() ? l.ext().getAsJsonObject(IRRIGATION_EXT) : null;
	}

	/** The variant's reserved cells: IRR's {@code lotCells} in the lot's frame (the water and its bed stay open), or null when the lot has none. */
	public static DesignRequest.@Nullable Reserved reserved(LotSpec l) {
		JsonObject irr = irrigation(l);
		if (irr == null) return null;
		JsonObject a = new JsonObject();
		a.addProperty("id", l.id());
		a.addProperty("front", front(l));
		a.add("irrigation", irr);
		JsonArray lots = new JsonArray();
		lots.add(a);
		return dev.larattalabs.steward.gateway.PPlan.reserved(lots).get(l.id());
	}

	/** Whether a saved binding still holds for this order and geometry: the same module, version, program and params, and the same geometry. */
	public static boolean holds(@Nullable ModuleBinding b, ModuleOrder o, JsonObject geometry) {
		return b != null && b.moduleId().equals(o.moduleId()) && b.version() == o.version() && b.programSha().equals(o.programSha())
			&& Canon.sorted(b.params()).equals(Canon.sorted(o.params())) && b.fits(geometry);
	}

	/** The n of a lot key ({@code steward.<build>.module.<lot>} is 1, {@code ...<lot>.<n>} is n). */
	public static int keyIndex(String opKey, String buildId, String lotId) {
		String base = dev.larattalabs.steward.module.ModuleInstalls.lotKey(buildId, lotId, 1);
		if (opKey.equals(base)) return 1;
		if (opKey.startsWith(base + ".")) {
			try {
				return Integer.parseInt(opKey.substring(base.length() + 1));
			} catch (NumberFormatException e) {
				return 1;
			}
		}
		return 1;
	}

	/** The key of the variant made for this lot now: the same while the binding holds, else the next one after a re-plan. */
	public static String nextKey(@Nullable ModuleBinding b, String buildId, String lotId) {
		return dev.larattalabs.steward.module.ModuleInstalls.lotKey(buildId, lotId, b == null ? 1 : keyIndex(b.opKey(), buildId, lotId) + 1);
	}

	/** What a quote cleared: the plan and the exact entries (a different entry or plan is quoted again before it is realised). */
	public static String cleared(String planId, Map<String, String> entries) {
		StringBuilder b = new StringBuilder(planId);
		new TreeMap<>(entries).forEach((k, v) -> b.append('|').append(k).append('=').append(v));
		return b.toString();
	}
}
