package dev.larattalabs.steward.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.larattalabs.steward.layout.VillageLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * (slice PV1, docs/GROWTH.md "Plan v1 growth") An addition to a town built from its composed plan takes its part from that plan: the plan's lot of the
 * asked type that the town region doesn't hold yet (it was left out at the first build, or its zone was), sent as the growth's lot under the plan's own
 * lot id and grown on the plan's zone's streets when the zone stands. Pure: the plan, the region's lots and the founding entry are read by the caller.
 */
public final class PlanGrowth {
	/** A region lot id (Architect M14): what a plan lot's own id must be to be sent as a growth's lot. */
	static final Pattern LOT_ID = Pattern.compile("[a-z][a-z0-9_]{0,47}");

	/**
	 * The plan's lot for the addition: its spec under the plan's own id, size, role and brief; the zone it is in; the plan district (D1) holding it, or
	 * null; whether the zone stands in the region (some lot of it does: the growth is asked on that zone's streets, else on the town's).
	 */
	public record Pick(VillageLayout.LotSpec spec, String zone, @Nullable String district, boolean zoneStands) {
		/** The zone the growth is asked on ({@code grow.zone}), or null for the town's streets. */
		public @Nullable String growZone() {
			return zoneStands ? zone : null;
		}
	}

	private PlanGrowth() {
	}

	/**
	 * Whether the kept plan is the one the town region was realised from: kept before the town's founding entry ({@code planAt <= foundedAt}), and
	 * naming at least one of the region's first lots (revision 0). A plan composed after the town stood (the card described again) never grows it.
	 */
	public static boolean approvedFor(long planAt, long foundedAt, Set<String> firstLots, JsonObject plan) {
		if (planAt > foundedAt || firstLots.isEmpty()) return false;
		for (Lot l : lots(plan)) if (firstLots.contains(l.id())) return true;
		return false;
	}

	/**
	 * The first lot in plan order whose type is {@code type} (else whose role or label is) that the region doesn't hold under any state: not in a part
	 * that waits ({@code waiting}), not a secret, not a module lot, and with an id Architect takes for a region lot. Empty: the addition takes today's path.
	 */
	public static Optional<Pick> pick(JsonObject plan, Set<String> waiting, Secrets secrets, Set<String> regionLots, String type, @Nullable String role) {
		List<Lot> all = lots(plan);
		String t = norm(type), r = norm(role);
		for (int pass = 0; pass < 2; pass++) {
			for (Lot l : all) {
				if (regionLots.contains(l.id()) || waiting.contains(l.zone()) || (l.district() != null && waiting.contains(l.district()))) continue;
				if (secrets.hides(l.id()) || secrets.hides(l.zone()) || !LOT_ID.matcher(l.id()).matches()) continue;
				if (l.lot().has("ext") && l.lot().get("ext").isJsonObject() && l.lot().getAsJsonObject("ext").has("steward_mc:module")) continue;
				String lt = norm(typeOf(l.lot()));
				boolean match = pass == 0 ? lt.equals(t)
					: r != null && !r.isEmpty() && (r.equals(norm(str(l.lot(), "role"))) || r.equals(norm(str(l.lot(), "label"))));
				if (!match) continue;
				boolean stands = all.stream().anyMatch(o -> o.zone().equals(l.zone()) && regionLots.contains(o.id()));
				return Optional.of(new Pick(spec(l.lot(), secrets), l.zone(), l.district(), stands));
			}
		}
		return Optional.empty();
	}

	/** A plan lot as a growth's lot spec: its own id, type, size (9 by 9 when it has none), role (its label first), brief, landmark and placement. */
	static VillageLayout.LotSpec spec(JsonObject lot, Secrets secrets) {
		String id = lot.get("id").getAsString();
		int sx = 9, sz = 9;
		if (lot.has("size") && lot.get("size").isJsonArray() && lot.getAsJsonArray("size").size() == 2) {
			sx = lot.getAsJsonArray("size").get(0).getAsInt();
			sz = lot.getAsJsonArray("size").get(1).getAsInt();
		}
		String brief = str(lot, "brief");
		if (brief != null) brief = secrets.scrub(brief);
		boolean landmark = lot.has("landmark") && lot.get("landmark").isJsonPrimitive() && lot.get("landmark").getAsBoolean();
		return new VillageLayout.LotSpec(id, typeOf(lot), sx, sz, dev.larattalabs.steward.gateway.PPlan.role(lot), brief == null || brief.isBlank() ? null : brief,
			landmark, str(lot, "placement"));
	}

	/** A plan lot with the zone it is in and its D1 district (null outside a districts program). */
	record Lot(String id, String zone, @Nullable String district, JsonObject lot) {}

	/** Every lot of every zone in plan order: a zone's {@code program.lots}, its template's {@code program.params.lots}, each district's lots (D1). */
	static List<Lot> lots(JsonObject plan) {
		List<Lot> out = new ArrayList<>();
		if (!plan.has("zones") || !plan.get("zones").isJsonArray()) return out;
		for (JsonElement ze : plan.getAsJsonArray("zones")) {
			if (!ze.isJsonObject()) continue;
			JsonObject z = ze.getAsJsonObject();
			String zone = str(z, "id");
			if (zone == null || !z.has("program") || !z.get("program").isJsonObject()) continue;
			JsonObject p = z.getAsJsonObject("program");
			if (p.has("districts") && p.get("districts").isJsonArray()) {
				for (JsonElement de : p.getAsJsonArray("districts")) {
					if (!de.isJsonObject() || !de.getAsJsonObject().has("lots") || !de.getAsJsonObject().get("lots").isJsonArray()) continue;
					String d = str(de.getAsJsonObject(), "id");
					for (JsonElement l : de.getAsJsonObject().getAsJsonArray("lots")) add(out, l, zone, d);
				}
				continue;
			}
			var lots = p.has("lots") && p.get("lots").isJsonArray() ? p.getAsJsonArray("lots")
				: p.has("params") && p.get("params").isJsonObject() && p.getAsJsonObject("params").has("lots") && p.getAsJsonObject("params").get("lots").isJsonArray()
					? p.getAsJsonObject("params").getAsJsonArray("lots") : null;
			if (lots != null) for (JsonElement l : lots) add(out, l, zone, null);
		}
		return out;
	}

	private static void add(List<Lot> out, JsonElement l, String zone, @Nullable String district) {
		if (l.isJsonObject() && l.getAsJsonObject().has("id") && l.getAsJsonObject().get("id").isJsonPrimitive())
			out.add(new Lot(l.getAsJsonObject().get("id").getAsString(), zone, district, l.getAsJsonObject()));
	}

	private static String typeOf(JsonObject lot) {
		String t = str(lot, "type");
		return t != null ? t : lot.get("id").getAsString().replaceFirst("_\\d+$", "");
	}

	private static @Nullable String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}

	private static @Nullable String norm(@Nullable String s) {
		return s == null ? null : s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replaceFirst("^(the|a|an)_", "");
	}
}
