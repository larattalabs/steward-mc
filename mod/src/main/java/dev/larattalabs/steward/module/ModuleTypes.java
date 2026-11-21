package dev.larattalabs.steward.module;

import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Proposal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * (slice MODULE-TYPE; Noah 2026-11-20, "Never as plain buildings") A catalog module's type ({@code crop_farm}, {@code storage_hall}, whatever the module
 * catalog lists) is never proposed or built as an ordinary, designed building: additions, districts, quarters and talk offer the module itself through
 * the module offer path when the town qualifies, else leave it out and say why. This reads a building type as a module's, failing closed: every kind
 * Steward knows (offered now or not) and every id the catalog lists. Pure.
 */
public final class ModuleTypes {
	private ModuleTypes() {}

	/** Building type -> module id, for the kinds Steward knows whatever the catalog offers ({@link ModuleKind#all()}). */
	public static final Map<String, String> KNOWN = of(ModuleKind.all(), List.of());

	/** Each kind's id and lot type, and each catalog id, as building types -> the module id they stand for. */
	public static Map<String, String> of(Collection<ModuleKind> kinds, Collection<String> catalogIds) {
		Map<String, String> out = new LinkedHashMap<>();
		for (ModuleKind k : ModuleKind.all()) {
			out.putIfAbsent(slug(k.id()), k.id());
			out.putIfAbsent(slug(k.lotType()), k.id());
		}
		for (ModuleKind k : kinds) {
			out.putIfAbsent(slug(k.id()), k.id());
			out.putIfAbsent(slug(k.lotType()), k.id());
		}
		for (String id : catalogIds) if (id != null && !slug(id).isEmpty()) out.putIfAbsent(slug(id), id);
		return Map.copyOf(out);
	}

	/** The module id a building type stands for, or null when it is an ordinary building's type ({@code farm}, the decorative one, is). */
	public static @Nullable String moduleOf(@Nullable String type, Map<String, String> types) {
		return type == null ? null : types.get(slug(type));
	}

	/** Whether {@code p} would build an ordinary, designed building of a module's type (a module offer itself never does: its lot is the module's). */
	public static boolean plain(Proposal p, Map<String, String> types) {
		return !p.offersModule() && p.building() != null && moduleOf(p.building().type(), types) != null;
	}

	/** The module id of a plain proposal ({@link #plain}), or null. */
	public static @Nullable String plainModule(Proposal p, Map<String, String> types) {
		return plain(p, types) ? moduleOf(p.building().type(), types) : null;
	}

	/** {@code buildings} split: the ordinary ones, and those of a module's type (left out, said). */
	public record Split(List<ConceptCard.Building> ordinary, List<ConceptCard.Building> modules) {}

	public static Split split(List<ConceptCard.Building> buildings, Map<String, String> types) {
		List<ConceptCard.Building> ordinary = new ArrayList<>(), modules = new ArrayList<>();
		for (var b : buildings) (moduleOf(b.type(), types) == null ? ordinary : modules).add(b);
		return new Split(List.copyOf(ordinary), List.copyOf(modules));
	}

	/** What the module is called in the steward's words ("storage hall"): its kind's role, else its id's words. */
	public static String role(String moduleId) {
		return ModuleKind.of(moduleId).map(ModuleKind::role).orElse(moduleId.replace('_', ' '));
	}

	/** The rule's words, said wherever a module's type is left out: a working one comes from the catalog, never designed as an ordinary building. */
	public static String rule(String moduleId) {
		return "a working " + role(moduleId) + " comes from the catalog as a module, offered on its own when the town qualifies; I never design one as an ordinary building";
	}

	/** Why a plain proposal of a module's type isn't built (an old save's, or one Accept refuses): what the steward says. */
	public static String refusal(String title, String moduleId) {
		return title + " isn't built: " + rule(moduleId) + ". It leaves the inbox (not declined).";
	}

	/** The module-type buildings a district or quarter leaves out, in the steward's words ("" when none). */
	public static String leftOutWords(List<ConceptCard.Building> modules, Map<String, String> types) {
		if (modules.isEmpty()) return "";
		List<String> ids = new ArrayList<>();
		for (var b : modules) {
			String id = moduleOf(b.type(), types);
			if (id != null && !ids.contains(id)) ids.add(id);
		}
		List<String> parts = new ArrayList<>();
		for (String id : ids) parts.add(rule(id));
		String names = modules.stream().map(b -> "the " + b.role()).distinct().reduce((a, b) -> a + " and " + b).orElse("");
		return "It leaves out " + names + ": " + String.join("; ", parts) + ".";
	}

	static String slug(String s) {
		return s.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
	}
}
