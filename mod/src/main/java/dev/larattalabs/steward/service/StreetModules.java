package dev.larattalabs.steward.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.larattalabs.steward.gateway.PPlan;
import dev.larattalabs.steward.layout.VillageLayout.LotSpec;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.module.ComposedModules;
import dev.larattalabs.steward.module.ModuleKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * (slice M5-SCULPT, docs/MODULES.md "M5 design" B3) A first whole build whose kept plan names working modules but doesn't take that plan (a shaped site,
 * uneven ground, something standing, another dimension or mode): the card's buildings are laid out without the plan, and a module is placed only from
 * a plan, so each module lot is left out of the town and said, never designed as an ordinary building in its place. Each module lot stands for one of
 * the card's buildings: the one of its lot type, else of the type it was written {@code for} (as {@code ComposeJob.missing} counts it). Pure.
 */
final class StreetModules {
	private StreetModules() {}

	/** The card's lots this build lays out (the module lots' buildings taken out) and what the steward says of each module lot left out. */
	record Result(List<LotSpec> specs, List<String> said, int removed) {}

	/**
	 * The lots without the buildings the plan's module lots stand for, and the words. {@code plan}: the kept plan (null: none); {@code lotTypes}: the
	 * module lot types offered now ({@link ComposedModules#readAll(List, Set)}); {@code why}: why the plan isn't used, without the module's tail.
	 */
	static Result leaveOut(@Nullable JsonObject plan, Set<String> lotTypes, List<LotSpec> specs, String why, String laidOut) {
		if (plan == null) return new Result(List.copyOf(specs), List.of(), 0);
		List<JsonObject> lots = PPlan.lots(plan);
		var asked = ComposedModules.readAll(lots, lotTypes);
		if (asked.isEmpty()) return new Result(List.copyOf(specs), List.of(), 0);
		Map<String, JsonObject> byId = new HashMap<>();
		for (JsonObject l : lots) byId.putIfAbsent(l.get("id").getAsString(), l);
		List<LotSpec> kept = new ArrayList<>(specs);
		List<String> said = new ArrayList<>();
		int removed = 0;
		for (var a : asked) {
			JsonObject lot = byId.get(a.lotId());
			for (String t : stands(a, lot)) {
				int at = -1;
				for (int i = 0; i < kept.size() && at < 0; i++) if (t.equals(kept.get(i).type())) at = i;
				if (at >= 0) {
					kept.remove(at);
					removed++;
					break;
				}
			}
			String kind = ModuleKind.of(a.moduleId()).map(ModuleKind::role).orElse("module");
			said.add(ComposedModules.leftOutWords(PPlan.role(lot), why + ", so the town is laid out " + laidOut + ", where the working " + kind + " can't be placed yet"));
		}
		return new Result(List.copyOf(kept), List.copyOf(said), removed);
	}

	/**
	 * (slice DP) The card's buildings the town left out, less those the kept plan's module lots stand for: a module lot is never offered again as an
	 * ordinary building in a district of its own. Pure.
	 */
	static List<ConceptCard.Building> withoutModules(@Nullable JsonObject plan, Set<String> lotTypes, List<ConceptCard.Building> left) {
		if (plan == null || left.isEmpty()) return left;
		List<JsonObject> lots = PPlan.lots(plan);
		var asked = ComposedModules.readAll(lots, lotTypes);
		if (asked.isEmpty()) return left;
		Map<String, JsonObject> byId = new HashMap<>();
		for (JsonObject l : lots) byId.putIfAbsent(l.get("id").getAsString(), l);
		List<ConceptCard.Building> out = new ArrayList<>(left);
		for (var a : asked) {
			for (String t : stands(a, byId.get(a.lotId()))) {
				int at = -1;
				for (int i = 0; i < out.size() && at < 0; i++) if (t.equals(out.get(i).type())) at = i;
				if (at < 0) continue;
				var b = out.get(at);
				if (b.count() <= 1) out.remove(at);
				else out.set(at, new ConceptCard.Building(b.role(), b.type(), b.count() - 1, b.footprint(), b.landmark(), b.notes(), b.placement()));
				break;
			}
		}
		return List.copyOf(out);
	}

	/** The card building types a module lot stands for, in order: its lot type, then the type it was written for. */
	private static List<String> stands(ComposedModules.Asked a, @Nullable JsonObject lot) {
		List<String> out = new ArrayList<>();
		if (lot != null && lot.has("type") && lot.get("type").isJsonPrimitive()) {
			String t = slug(lot.get("type").getAsString());
			if (!t.isEmpty()) out.add(t);
		}
		if (a.forType() != null && !out.contains(a.forType())) out.add(a.forType());
		return out;
	}

	private static String slug(String s) {
		return s.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
	}

	/**
	 * Why a first whole build doesn't take its plan, in the steward's words (the first that holds): the land is shaped (what the plan carves, when it
	 * says), the town isn't in the Overworld, something stands in the claim, the ground isn't flat, else the town's mode. Pure.
	 */
	static String why(String terrain, @Nullable JsonObject plan, boolean overworld, boolean somethingStands, boolean flat) {
		if ("sculpt".equals(terrain)) {
			String what = shaped(plan);
			return "the land is shaped" + (what == null ? "" : " (" + what + ")");
		}
		if (!overworld) return "the town isn't in the Overworld, where its plan is built";
		if (somethingStands) return "something already stands in the claim";
		if (!flat) return "the ground isn't flat enough for its plan";
		return "its plan isn't built in this world's mode";
	}

	/** What a plan shapes the land with, in words: a carved river for a channel, else its first form's or connector's label, or null. */
	static @Nullable String shaped(@Nullable JsonObject plan) {
		if (plan == null) return null;
		String first = null;
		for (String sec : List.of("connectors", "forms")) {
			if (!plan.has(sec) || !plan.get(sec).isJsonArray()) continue;
			for (JsonElement e : plan.getAsJsonArray(sec)) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				String kind = o.has("kind") && o.get("kind").isJsonPrimitive() ? o.get("kind").getAsString() : "";
				if ("channel".equals(kind)) return "a carved river";
				if (first == null && "forms".equals(sec)) first = o.has("label") && o.get("label").isJsonPrimitive() && !o.get("label").getAsString().isBlank()
					? o.get("label").getAsString().toLowerCase(Locale.ROOT) : kind.isBlank() ? null : kind.replace('_', ' ');
			}
		}
		return first;
	}
}
