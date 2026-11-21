package dev.larattalabs.steward.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.larattalabs.steward.gateway.PPlan;
import dev.larattalabs.steward.layout.VillageLayout.LotSpec;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.ProposalRules;
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
	 * The lots without the buildings the plan's module lots stand for, and the words. {@code plan}: the kept plan (null: none); {@code types}: every
	 * module's type, offered now or not ({@link ComposedModules#readAll(List, Map)}, slice MODULE-TYPE-2); {@code offered}: the module ids the catalog
	 * offers this town (a bare lot of another is said as {@link ComposedModules#notOfferedWords}); {@code card}: the card {@code specs} were laid out from (null, or no
	 * program: {@code specs} are all there is); {@code why}: why the plan isn't used, without the module's tail.
	 * <p>
	 * (batch-076 review P2-4) Each module lot's building is found in the WHOLE card first, then only a lot laid out for that same card entry is taken
	 * from {@code specs}: a module whose entry the build count didn't select removes nothing, and an ordinary building that only shares its type stays.
	 */
	static Result leaveOut(@Nullable JsonObject plan, Map<String, String> types, Set<String> offered, @Nullable ConceptCard card, List<LotSpec> specs, String why,
		String laidOut) {
		if (plan == null) return new Result(List.copyOf(specs), List.of(), 0);
		List<JsonObject> lots = PPlan.lots(plan);
		var asked = ComposedModules.readAll(lots, types);
		if (asked.isEmpty()) return new Result(List.copyOf(specs), List.of(), 0);
		Map<String, JsonObject> byId = byId(lots);
		List<LotSpec> kept = new ArrayList<>(specs);
		int removed = 0;
		if (card != null && card.hasProgram()) {
			List<ConceptCard.Building> program = card.program();
			int[] taken = taken(asked, byId, program);
			for (int i = 0; i < program.size(); i++) {
				for (int k = 0; k < taken[i]; k++) {
					int at = laidOutFor(kept, program.get(i));
					if (at < 0) break;
					kept.remove(at);
					removed++;
				}
			}
		} else {
			for (var a : asked) {
				JsonObject lot = byId.get(a.lotId());
				int at = pick(stands(a, lot), names(lot), kept.size(), i -> kept.get(i).type(), i -> kept.get(i).role());
				if (at >= 0) {
					kept.remove(at);
					removed++;
				}
			}
		}
		List<String> said = new ArrayList<>();
		for (var a : asked) {
			if (!ComposedModules.marked(byId.get(a.lotId())) && !offered.contains(a.moduleId())) {
				said.add(ComposedModules.notOfferedWords(PPlan.role(byId.get(a.lotId())), a.moduleId()));
				continue;
			}
			String kind = ModuleKind.of(a.moduleId()).map(ModuleKind::role).orElse("module");
			said.add(ComposedModules.leftOutWords(PPlan.role(byId.get(a.lotId())), why + ", so the town is laid out " + laidOut + ", where the working " + kind
				+ " can't be placed yet"));
		}
		return new Result(List.copyOf(kept), List.copyOf(said), removed);
	}

	/**
	 * (slice DP) The card's buildings the town left out ({@code left}, from {@code card}, each with its card entry), less those the kept plan's module
	 * lots stand for: a module lot is never offered again as an ordinary building in a district of its own. Each module lot's building is found in the
	 * whole card first (GPT review: never against what is already missing, which could take an unrelated building), then subtracted from that same
	 * entry's missing count (batch-076 review P2-5: two entries of one role and type stay apart). Pure.
	 */
	static List<ConceptCard.Building> withoutModules(@Nullable JsonObject plan, Map<String, String> types, @Nullable ConceptCard card, List<ProposalRules.Missing> left) {
		List<ConceptCard.Building> all = left.stream().map(ProposalRules.Missing::building).toList();
		if (plan == null || card == null || !card.hasProgram() || left.isEmpty()) return all;
		List<JsonObject> lots = PPlan.lots(plan);
		var asked = ComposedModules.readAll(lots, types);
		if (asked.isEmpty()) return all;
		List<ConceptCard.Building> program = card.program();
		int[] taken = taken(asked, byId(lots), program);
		List<ConceptCard.Building> out = new ArrayList<>();
		for (var m : left) {
			var b = m.building();
			int n = m.entry() >= 0 && m.entry() < taken.length ? taken[m.entry()] : 0;
			int missing = b.count() - n;
			if (missing > 0) out.add(n == 0 ? b : new ConceptCard.Building(b.role(), b.type(), missing, b.footprint(), b.landmark(), b.notes(), b.placement()));
		}
		return List.copyOf(out);
	}

	/** Each card entry's count of buildings the module lots stand for, each matched against the whole card (an entry never past its count). */
	private static int[] taken(List<ComposedModules.Asked> asked, Map<String, JsonObject> byId, List<ConceptCard.Building> program) {
		int[] taken = new int[program.size()];
		for (var a : asked) {
			JsonObject lot = byId.get(a.lotId());
			int at = pick(stands(a, lot), names(lot), program.size(), i -> taken[i] < program.get(i).count() ? program.get(i).type() : null, i -> program.get(i).role());
			if (at >= 0) taken[at]++;
		}
		return taken;
	}

	/**
	 * The first lot laid out for card entry {@code b} ({@code ProgramPlanner} gives it the entry's role, type, notes and placement), or -1. An ordinary
	 * entry never takes a landmark's lot; a landmark entry takes its landmark lot first (batch-076 fix-check P2: two entries alike but for the landmark).
	 */
	private static int laidOutFor(List<LotSpec> specs, ConceptCard.Building b) {
		int ordinary = -1;
		for (int i = 0; i < specs.size(); i++) {
			LotSpec s = specs.get(i);
			if (!(s.role().equals(b.role()) && s.type().equals(b.type()) && java.util.Objects.equals(s.notes(), b.notes())
				&& java.util.Objects.equals(s.placement(), b.placement()))) continue;
			if (s.landmark() && b.landmark()) return i;
			if (!s.landmark() && ordinary < 0) ordinary = i;
		}
		return ordinary;
	}

	private static Map<String, JsonObject> byId(List<JsonObject> lots) {
		Map<String, JsonObject> byId = new HashMap<>();
		for (JsonObject l : lots) byId.putIfAbsent(l.get("id").getAsString(), l);
		return byId;
	}

	/**
	 * The index of the card building a module lot stands for, or -1: by type in order ({@code types}: its lot type, then its {@code for}), and among those of
	 * a type the one whose role is the lot's own (its label, role or id's words, {@code names}), else the first (GPT review: a "granary barn" module never
	 * takes the "cattle barn"). {@code type} answers null for an index already taken.
	 */
	private static int pick(List<String> types, List<Set<String>> names, int size, java.util.function.IntFunction<@Nullable String> type, java.util.function.IntFunction<String> role) {
		for (String t : types) {
			int first = -1;
			// its label or role first, then its id's words (GPT fix check: a generic id never outranks the exact label)
			for (Set<String> tier : names) for (int i = 0; i < size; i++) if (t.equals(type.apply(i)) && tier.contains(slug(role.apply(i)))) return i;
			for (int i = 0; i < size && first < 0; i++) if (t.equals(type.apply(i))) first = i;
			if (first >= 0) return first;
		}
		return -1;
	}

	/** A module lot's own names, as slugs, in tiers: its label and role, then its id without the number. */
	private static List<Set<String>> names(@Nullable JsonObject lot) {
		Set<String> own = new java.util.HashSet<>(), id = new java.util.HashSet<>();
		if (lot != null) {
			for (String k : List.of("label", "role")) if (lot.has(k) && lot.get(k).isJsonPrimitive()) own.add(slug(lot.get(k).getAsString().replaceFirst("(?i)^(the|a|an) ", "")));
			if (lot.has("id") && lot.get("id").isJsonPrimitive()) id.add(slug(lot.get("id").getAsString().replaceAll("_\\d+$", "")));
		}
		own.remove("");
		id.remove("");
		return List.of(own, id);
	}

	/** The card building types a module lot stands for, in order: its lot type, then the type it was written for. */
	private static List<String> stands(ComposedModules.Asked a, @Nullable JsonObject lot) {
		List<String> out = new ArrayList<>();
		if (lot != null && lot.has("type") && lot.get("type").isJsonPrimitive()) {
			String t = slug(lot.get("type").getAsString());
			if (!t.isEmpty()) out.add(t);
		}
		// (GPT review) the marker's for read from the lot itself: an unreadable marker (no module id) still says what it stands for
		String forType = a.forType();
		if (forType == null && lot != null && lot.has("ext") && lot.get("ext").isJsonObject() && lot.getAsJsonObject("ext").has(ComposedModules.EXT)
			&& lot.getAsJsonObject("ext").get(ComposedModules.EXT).isJsonObject()) {
			JsonObject m = lot.getAsJsonObject("ext").getAsJsonObject(ComposedModules.EXT);
			if (m.has("for") && m.get("for").isJsonPrimitive()) forType = slug(m.get("for").getAsString());
		}
		if (forType != null && !forType.isEmpty() && !out.contains(forType)) out.add(forType);
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
