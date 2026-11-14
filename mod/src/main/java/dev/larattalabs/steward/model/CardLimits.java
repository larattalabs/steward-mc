package dev.larattalabs.steward.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What Steward cannot do yet for a card, said on the card before anything is spent (docs/PLAN.md phase 3, Noah 2026-10-09: "show known limitations on the
 * card"). Read from the card's own words; each line says what happens instead. Pure. A composed plan's waits, in Claude's words, replace these lines.
 *
 * <p>Each rule stands for one row of the strange-asks gap map that is not built today ({@code fixtures/strange-asks/capabilities.json}, Architect 0.64.1's
 * catalogue), and its line says no more than that row's {@code nearest} text: a line never promises what isn't built. What Architect builds since 7c (craters,
 * floating islands, hollowed mountains, rift and sky-island programs, steep ground, elevated lots) gets no line. No line names a hidden room (S-7c-4).
 */
public final class CardLimits {
	/** About as many buildings as one street holds. */
	public static final int ONE_STREET = 16;

	/** One row of the gap map: the card's words that ask for it, and what the card says. */
	record Rule(String capability, Pattern words, String line) {
		Rule(String capability, String regex, String line) {
			this(capability, Pattern.compile("\\b(" + regex + ")\\b"), line);
		}
	}

	static final List<Rule> RULES = List.of(
		// a long climb only where the card is up in the sky: stairs and switchbacks up a hill or a cliff are built
		new Rule("raised_climbs", Pattern.compile("(?s)(?=.*\\b(floating|sky)\\b).*\\b((long|tall|steep|high) (stair(case|way)?s?|climb|steps)|(stair(case|way)?s?|steps|ladders?"
			+ "|climb) (up )?to)\\b"),
			"A long stair up to a floating island isn't built yet: no stair or switchback climbs that high, so the climb waits."),
		new Rule("over_water", "stilts?|swamps?|marsh(es|y)?|bogs?|fens?|lagoons?|docks?|piers?|jett(y|ies)|wharf|over (the |a )?(water|lake|sea|river|lagoon|swamp|marsh|bay)"
			+ "|above the water|on (the |a )?(water|lagoon|swamp|marsh)|on (a |the )?frozen (lake|pond|river)", "Buildings over water stand on dry ground beside it for now; ponds, quays, waterfalls and water gates are built."),
		new Rule("underwater", "under ?water|under the (waves|sea)|sea ?floor|seabed|ocean floor|sunken|submerged",
			"Building under water isn't planned: nothing is built below the surface."),
		new Rule("off_ground", "canopy|tree ?tops?|tree ?houses?|up in the trees|in the treetops|inside (the )?giant mushrooms?|homes? inside (the )?mushrooms?"
			+ "|hanging from|hangs from", "Buildings raised off the ground (up in trees, inside giant mushrooms, hanging from a ledge) aren't built yet: they wait,"
			+ " and the plan says which."),
		new Rule("carved_interiors", "carved (inside|into)|built into (the )?(cliff|rock|mountain)|into the cliff( face)?|inside (a |the )?(mountain|volcano|cliff)|dug into|under the mountain"
			+ "|caves?|caverns?|tunnels?", "Homes carved deep into rock aren't built yet: they stand beside the mountain or cliff. A hollowed mountain with a lit hall is built,"
			+ " and open tunnels under ground."),
		new Rule("other_dimensions", "nether|the end|end city|end island", "Settlements in the Nether or the End aren't planned: Steward builds in the Overworld."),
		new Rule("moving", "nomad(ic)?|packs? up|caravan|on (a|the) (giant )?ship|aboard", "A settlement that moves isn't planned: it is built in one place."),
		new Rule("megastructure", "arcology|single tower|one (giant|huge|single) (castle|structure|building|tower)|giant ship",
			"One giant structure as the whole settlement comes as a walled town round a keep for now."),
		new Rule("timed_changes", "festivals?|seasons?|seasonal|decorates?|holidays?",
			"Changes on a timetable (festivals, seasons) aren't planned: the town stays as it is built."),
		new Rule("decay_over_time", "falls? into ruin|decays? over time|crumbles? (away|over time)|unless i (visit|care)",
			"Decay and repair over time aren't built: the town stays as it is built."),
		new Rule("ruin_style", "ruins?|ruined|crumbling|half[- ]built|abandoned|(hundred|thousand) years old",
			"Ruins and decay come as a look only: whole buildings in a weathered style."),
		new Rule("hard_rules", "no right angles|upside[- ]down|(built|made) (only|entirely|solely) (of|from)|only of|nothing but|stages? of oxidation",
			"Rules over every building hold for its blocks (one palette over the settlement); shape and height rules are hints for now."),
		new Rule("variety_control", "no two (houses |buildings )?(alike|the same)|identical|all the same|every (house|building) different",
			"Variety is the steward's choice for now: like buildings may be copies of each other."),
		new Rule("growth_arc", "grows? (fast|in ?to)|becomes? a (town|city)|starts? as|boom ?town",
			"A planned growth arc isn't set: the steward grows the town street by street and proposes districts as it fills."),
		new Rule("working_modules", "mines?|mining|minecarts?|rails?|railway|mine ?shafts?|feeds? my base",
			"Working farms come as irrigated fields, farm ponds, channels and ditches; mines and rails aren't built yet."),
		new Rule("redstone", "redstone|automatic|sort(ing|ers?)|machines?",
			"Working redstone comes as gatehouse mechanisms (a portcullis, swing gates, a drawbridge); sorters and machines wait."),
		new Rule("economy", "every villager buys|villagers? (buy|sell|trade)s?|prices?|economy|bartering",
			"Trade and prices aren't built: shops and markets are buildings only."),
		new Rule("inhabitants", "cats?|librarians?|mages?|wizards?|every villager",
			"Inhabitants and animals that fit the story come in a later phase: the buildings are built for them."),
		new Rule("relations", "feud(s|ing)?|rivals?|rivalry|factions?|war|besieg\\w*|siege|conquer\\w*|take over|against the (\\w+ )?(settlement|town|village|castle|enemy|north|south|east|west)",
			"Feuds, factions and war between settlements aren't built: each part is built as its own place."));

	private CardLimits() {
	}

	public static List<String> of(ConceptCard c) {
		List<String> out = new ArrayList<>();
		String words = (c.site().text() + " " + c.style().text() + " " + c.purpose().text() + " " + (c.story() == null || c.story().text() == null ? "" : c.story().text()))
			.toLowerCase(Locale.ROOT);
		for (Rule r : RULES) if (r.words().matcher(words).find()) out.add(r.line());
		if (c.hasProgram() && c.program().stream().mapToInt(ConceptCard.Building::count).sum() > ONE_STREET) {
			out.add("One street holds about " + ONE_STREET + " buildings: the rest can go to districts once the town stands.");
		}
		return out;
	}
}
