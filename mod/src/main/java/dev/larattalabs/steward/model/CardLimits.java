package dev.larattalabs.steward.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What Steward cannot do yet for a card, said on the card before anything is spent (docs/PLAN.md phase 3, Noah 2026-10-09: "show known limitations on the
 * card"). Read from the card's own words and templates; each line says what happens instead. Pure.
 */
public final class CardLimits {
	private static final Pattern OVER_WATER = Pattern.compile("\\b(stilts?|swamps?|marsh(es|y)?|bogs?|fens?|lagoons?|floating|(over|on|above|across) (the |a )?(water|lake|sea|river|"
		+ "lagoon|swamp|marsh|bay))\\b");
	private static final Pattern STEEP = Pattern.compile("\\b(cliffs?|cliffside|cliff face|mountainside|hillside|crags?|canyon walls?|ledges?|steep)\\b");
	private static final List<String> FORMS = List.of("crater", "rift", "sky_city", "underground", "island", "cliff_face");
	/** About as many buildings as one street holds. */
	public static final int ONE_STREET = 16;

	private CardLimits() {
	}

	public static List<String> of(ConceptCard c) {
		List<String> out = new ArrayList<>();
		String words = (c.site().text() + " " + c.style().text() + " " + c.purpose().text() + " " + (c.story() == null || c.story().text() == null ? "" : c.story().text()))
			.toLowerCase(Locale.ROOT);
		String site = c.site().template() == null ? "" : c.site().template();
		boolean form = "sculpt".equals(c.site().terrain()) || FORMS.contains(site);
		if (form) out.add("Large custom forms (a crater, a rift, a sky city, an island, under ground) come with Architect's region programs: for now this is built as a"
			+ " village on ordinary ground.");
		if (OVER_WATER.matcher(words).find()) out.add("Buildings over water wait for Architect: for now they stand on dry ground beside the water.");
		if (!form && (STEEP.matcher(words).find() || "cliff_face".equals(site))) out.add("Buildings on cliffs and steep ground wait for Architect: the layout takes the flattest ground nearby.");
		if (c.hasProgram() && c.program().stream().mapToInt(ConceptCard.Building::count).sum() > ONE_STREET) {
			out.add("One street holds about " + ONE_STREET + " buildings: the rest can go to districts once the town stands.");
		}
		return out;
	}
}
