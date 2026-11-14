package dev.larattalabs.steward.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the steward proposes, from what it perceives (docs/PLAN.md phase 3e). A table of rules: a sign (a tier reached, seeds or saplings carried, animals
 * gathering) suggests a building the settlement lacks. Pure, so every rule and guardrail is tested; the Claude call that words a proposal for this
 * settlement's style comes later (with Architect's stub, so it is tested for free).
 *
 * <p>Guardrails (Noah, 2026-10-09): never a building the settlement has or one the player declined; at most {@link #MAX_OPEN} open at once; one new
 * proposal per {@link #COOLDOWN_MS}; the signs are read on a schedule (once a minute), not on every event.
 */
public final class ProposalRules {
	public static final int MAX_OPEN = 3;
	/** Buildings a street holds before new ones go to a district (one street holds 12-16). */
	public static final int STREET_FULL = 12;
	public static final long COOLDOWN_MS = 20 * 60_000L;

	/** What the steward sees: the player's tier and what they carry; animals gathering in the claim, by kind. */
	public record Signs(Progress.Tier tier, int seeds, int saplings, Map<String, Integer> animals, Map<String, Integer> seedItems) {
		public Signs {
			seedItems = seedItems == null ? Map.of() : Map.copyOf(seedItems);
		}

		/** As before module offers: the seeds' kinds not known. */
		public Signs(Progress.Tier tier, int seeds, int saplings, Map<String, Integer> animals) {
			this(tier, seeds, saplings, animals, Map.of());
		}
	}

	/**
	 * A module the catalog may offer (docs/MODULES.md 4.1), as the steward read it: its kind, whether one of the same id stands in the settlement
	 * (module identity, read from the lots' {@code steward_mc:module} ext), and its offer when it is eligible here ({@code offer} null: not, and
	 * {@code why} says so). A decorative building's type never suppresses a module.
	 */
	public record ModuleCandidate(dev.larattalabs.steward.module.ModuleKind kind, boolean standing, @org.jspecify.annotations.Nullable Proposal offer,
		@org.jspecify.annotations.Nullable String why) {}

	/** Whether a module's sign fires: the player carries {@link dev.larattalabs.steward.module.ModuleOffer#SIGN_SEEDS} or more seeds of one crop it grows. */
	public static boolean moduleFires(dev.larattalabs.steward.module.ModuleKind k, Signs s) {
		Map<String, Integer> byCrop = new java.util.HashMap<>();
		for (var e : k.seeds().entrySet()) byCrop.merge(e.getValue(), s.seedItems().getOrDefault(e.getKey(), 0), Integer::sum);
		return byCrop.values().stream().anyMatch(n -> n >= dev.larattalabs.steward.module.ModuleOffer.SIGN_SEEDS);
	}

	/**
	 * Whether a module offer is skipped (4.1 "Coalescing and declines"): its key was declined, an offer of the same module waits (by key or by
	 * module id: one asked in talk counts), or a module of the same id stands. Never by building type.
	 */
	public static boolean moduleSkipped(ModuleCandidate c, Set<String> declined, List<Proposal> open) {
		String id = c.kind().id();
		return declined.contains(c.kind().rule()) || c.standing()
			|| open.stream().anyMatch(p -> p.key().equals(c.kind().rule()) || (p.module() != null && id.equals(p.module().moduleId())));
	}

	/** Declining a module offer also retires its decorative rules ({@code farm}): the player has said no to farms. Declining {@code farm} retires nothing more. */
	public static List<String> declines(String key) {
		var k = dev.larattalabs.steward.module.ModuleKind.of(dev.larattalabs.steward.module.ModuleKind.idOf(key));
		List<String> out = new ArrayList<>();
		out.add(key);
		k.ifPresent(x -> x.decorative().stream().sorted().filter(d -> !out.contains(d)).forEach(out::add));
		return out;
	}

	/** One rule: when it fires, and the building it suggests. */
	record Rule(String key, String title, String why, String role, String type, String footprint, String notes) {}

	/** Signs about what the player does now first (seeds, saplings, animals), then the newest tier: the first proposal is the timeliest. */
	static final List<Rule> RULES = List.of(
		new Rule("farm", "A farm", "You carry seeds: a walled farm plot with a barn would keep the settlement fed.", "farm and barn", "farm", "L",
			"tilled plots with water channels, a small barn for tools and grain"),
		new Rule("orchard", "An orchard", "You carry saplings: an orchard would give the settlement wood and fruit.", "orchard", "orchard", "M",
			"rows of young trees, a fenced path, a tool shed"),
		new Rule("pens", "Animal pens", "Animals are gathering here: pens and a stable would keep them.", "animal pens", "stable", "L",
			"fenced pens by species, a stable with hay storage, a water trough"),
		new Rule("tower", "A launch tower", "You fly now: a tower to launch from would crown the settlement.", "launch tower", "tower", "S",
			"a tall stone tower with a flat roof and a lookout"),
		new Rule("gatehouse", "A portal gatehouse", "You have been to the Nether: a gatehouse would frame the settlement's portal.", "portal gatehouse", "gatehouse", "M",
			"a fortified house around a nether portal, blackstone and lanterns"),
		new Rule("vault", "A vault", "You have found diamonds: a vault would keep the settlement's treasures.", "vault", "vault", "S",
			"a stone strongroom with a heavy door, chests and item frames"),
		new Rule("smithy", "A smithy", "You smelt iron now: a smithy with a forge and an anvil would serve you.", "smithy", "smithy", "M",
			"a working forge with blast furnace, anvil, tool racks"),
		new Rule("workshop", "A workshop", "You are cutting stone now: a workshop with a stonecutter and a mason's bench would serve you.", "stone workshop", "workshop", "M",
			"a mason's workshop: stonecutter, workbench, stone storage"));

	private ProposalRules() {
	}

	/**
	 * The proposals worth making now, best first, within the guardrails.
	 *
	 * @param types the building types the settlement has or has planned (its card's program, accepted proposals)
	 * @param declined proposal keys the player declined
	 * @param open the proposals waiting for the player now
	 * @param lastAt when the last proposal was made (epoch ms; 0 none)
	 */
	public static List<Proposal> propose(Signs s, Set<String> types, Set<String> declined, List<Proposal> open, long lastAt, long now) {
		return propose(s, types, declined, open, lastAt, now, List.of());
	}

	/**
	 * As above, with the catalog's modules first (4.1): a module whose sign fires and that is not skipped is offered when eligible; when it isn't, the
	 * old decorative rule ({@code farm}) is offered as today, priced. A module that stands or waits also holds its decorative rule back.
	 */
	public static List<Proposal> propose(Signs s, Set<String> types, Set<String> declined, List<Proposal> open, long lastAt, long now, List<ModuleCandidate> modules) {
		if (!room(open, lastAt, now)) return List.of();
		Set<String> openKeys = new java.util.HashSet<>();
		for (Proposal p : open) openKeys.add(p.key());
		Set<String> heldBack = new java.util.HashSet<>();
		for (ModuleCandidate c : modules) {
			if (!moduleFires(c.kind(), s)) continue;
			if (moduleSkipped(c, declined, open)) {
				if (c.standing() || open.stream().anyMatch(p -> p.module() != null && c.kind().id().equals(p.module().moduleId()))) heldBack.addAll(c.kind().decorative());
				continue;
			}
			// one at a time: the next waits for the cooldown
			if (c.offer() != null) return List.of(c.offer());
		}
		List<Proposal> out = new ArrayList<>();
		for (Rule r : RULES) {
			if (!fires(r.key(), s) || declined.contains(r.key()) || openKeys.contains(r.key()) || types.contains(r.type()) || heldBack.contains(r.key())) continue;
			out.add(new Proposal(r.key(), r.title(), r.why(), new ConceptCard.Building(r.role(), r.type(), 1, r.footprint(), false, r.notes(), null), now, trigger(r.key())));
			// one at a time: the next waits for the cooldown
			break;
		}
		return out;
	}

	/** Whether a new proposal may be made now: fewer than {@link #MAX_OPEN} open, and the cooldown since the last one is over. */
	public static boolean room(List<Proposal> open, long lastAt, long now) {
		return open.size() < MAX_OPEN && !(lastAt > 0 && now - lastAt < COOLDOWN_MS);
	}

	/** The proposal a key stands for (made now), or null for an unknown key: a proposal given back after a build that never spent. */
	public static Proposal find(String key) {
		return RULES.stream().filter(r -> r.key().equals(key)).findFirst().map(r -> new Proposal(r.key(), r.title(), r.why(),
			new ConceptCard.Building(r.role(), r.type(), 1, r.footprint(), false, r.notes(), null), System.currentTimeMillis(), trigger(r.key()))).orElse(null);
	}

	/** The building type a proposal key builds (what an accepted proposal adds to the settlement), or null for an unknown key. */
	public static String typeOf(String key) {
		// asked_<id>_<type>: a building the player asked for in talk
		if (key.startsWith("asked_")) {
			int i = key.indexOf('_', 6);
			return i > 0 && i + 1 < key.length() ? key.substring(i + 1) : null;
		}
		return RULES.stream().filter(r -> r.key().equals(key)).map(Rule::type).findFirst().orElse(null);
	}

	static boolean fires(String key, Signs s) {
		return switch (key) {
			case "workshop" -> s.tier().ordinal() >= Progress.Tier.STONE.ordinal();
			case "smithy" -> s.tier().ordinal() >= Progress.Tier.IRON.ordinal();
			case "farm" -> s.seeds() >= 8;
			case "orchard" -> s.saplings() >= 4;
			case "pens" -> s.animals().values().stream().anyMatch(n -> n >= 4);
			case "vault" -> s.tier().ordinal() >= Progress.Tier.DIAMOND.ordinal();
			case "gatehouse" -> s.tier().ordinal() >= Progress.Tier.NETHER.ordinal();
			case "tower" -> s.tier().ordinal() >= Progress.Tier.ELYTRA.ordinal();
			default -> false;
		};
	}

	static String trigger(String key) {
		return switch (key) {
			case "farm" -> "seeds";
			case "orchard" -> "saplings";
			case "pens" -> "animals";
			default -> "tier";
		};
	}

	/**
	 * Where the next building goes (phase 3f): the town while its street has room, else the first district with room ({@code district} its id); when none
	 * has, {@code full}: the steward proposes a district instead. A street has room below {@link #STREET_FULL} buildings, unless an addition there lately
	 * found none.
	 *
	 * @param districts each district's building count, oldest first
	 */
	public record Where(@org.jspecify.annotations.Nullable String district, boolean full) {}

	public static Where where(int town, java.util.LinkedHashMap<String, Integer> districts) {
		return where(town, districts, Set.of());
	}

	/** As above; {@code full} names the lands where an addition lately found no room ("" the town, else district ids), whatever their count. */
	public static Where where(int town, java.util.LinkedHashMap<String, Integer> districts, Set<String> full) {
		if (town < STREET_FULL && !full.contains("")) return new Where(null, false);
		for (var e : districts.entrySet()) if (e.getValue() < STREET_FULL && !full.contains(e.getKey())) return new Where(e.getKey(), false);
		return new Where(null, true);
	}

	/**
	 * The card's buildings the town left out (slice DP: the steward's own district proposal): each program entry with fewer standing than its count, as
	 * many as are missing. A building stands as its role ({@code byRole}, lower case), else, when no building carries that role, by its type
	 * ({@code byType}). Landmarks count like any entry. Pure.
	 */
	public static List<ConceptCard.Building> leftOut(@org.jspecify.annotations.Nullable ConceptCard card, Map<String, Integer> byRole, Map<String, Integer> byType) {
		if (card == null || !card.hasProgram()) return List.of();
		List<ConceptCard.Building> out = new ArrayList<>();
		for (ConceptCard.Building b : card.program()) {
			String role = b.role().toLowerCase(java.util.Locale.ROOT);
			int standing = byRole.containsKey(role) ? byRole.get(role) : byType.getOrDefault(b.type(), 0);
			int missing = b.count() - standing;
			if (missing > 0) out.add(new ConceptCard.Building(b.role(), b.type(), missing, b.footprint(), b.landmark(), b.notes(), b.placement()));
		}
		return List.copyOf(out);
	}

	/** The left-out buildings in words, for the steward and the proposal ("the fish market and two net lofts"). */
	public static String leftOutWords(List<ConceptCard.Building> left) {
		List<String> parts = new ArrayList<>();
		for (var b : left) parts.add(b.count() == 1 ? "the " + b.role() : dev.larattalabs.steward.model.Words.count(b.count(), b.role(), plural(b.role())));
		if (parts.size() <= 1) return parts.isEmpty() ? "" : parts.get(0);
		return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
	}

	/** What the left-out buildings ask of the ground, in words for {@link DistrictSite#fromWords}: their roles, types, notes and placement. */
	public static String leftOutSiteWords(List<ConceptCard.Building> left) {
		StringBuilder b = new StringBuilder();
		for (var x : left) {
			b.append(' ').append(x.role()).append(' ').append(x.type().replace('_', ' '));
			if (x.notes() != null) b.append(' ').append(x.notes());
			if (x.placement() != null) b.append(' ').append(x.placement());
		}
		return b.toString().strip();
	}

	private static String plural(String role) {
		return role.endsWith("s") || role.endsWith("sh") || role.endsWith("ch") ? role + "es" : role.endsWith("y") && !role.matches(".*[aeiou]y$") ? role.substring(0, role.length() - 1) + "ies" : role + "s";
	}
}
