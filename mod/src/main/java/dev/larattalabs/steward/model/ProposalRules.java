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
	public static final long COOLDOWN_MS = 20 * 60_000L;

	/** What the steward sees: the player's tier and what they carry; animals gathering in the claim, by kind. */
	public record Signs(Progress.Tier tier, int seeds, int saplings, Map<String, Integer> animals) {}

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
		if (open.size() >= MAX_OPEN || (lastAt > 0 && now - lastAt < COOLDOWN_MS)) return List.of();
		Set<String> openKeys = new java.util.HashSet<>();
		for (Proposal p : open) openKeys.add(p.key());
		List<Proposal> out = new ArrayList<>();
		for (Rule r : RULES) {
			if (!fires(r.key(), s) || declined.contains(r.key()) || openKeys.contains(r.key()) || types.contains(r.type())) continue;
			out.add(new Proposal(r.key(), r.title(), r.why(), new ConceptCard.Building(r.role(), r.type(), 1, r.footprint(), false, r.notes(), null), now, trigger(r.key())));
			// one at a time: the next waits for the cooldown
			break;
		}
		return out;
	}

	/** The building type a proposal key builds (what an accepted proposal adds to the settlement), or null for an unknown key. */
	public static String typeOf(String key) {
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
}
