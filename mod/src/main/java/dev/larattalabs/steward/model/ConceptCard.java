package dev.larattalabs.steward.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The concept card (schema/concept-card.schema.json): what a prompt means, split into orthogonal fields. The player edits and
 * approves it before anything is generated. Parsed with Gson from the structured job's result.
 */
public record ConceptCard(
	String name,
	Field site,
	Field style,
	Field purpose,
	Story story,
	Constraints constraints,
	List<String> avoid,
	String interpretation,
	List<Contradiction> contradictions,
	List<String> assumptions,
	@Nullable List<Building> program
) {
	/** Lists a stored card lacks (written before they existed) read as empty: Gson calls this, so every card has them. */
	public ConceptCard {
		avoid = avoid == null ? List.of() : avoid;
		contradictions = contradictions == null ? List.of() : contradictions;
		assumptions = assumptions == null ? List.of() : assumptions;
		interpretation = interpretation == null ? "" : interpretation;
	}

	public record Field(String text, String template, String terrain, String size) {}
	public record Story(String text) {}
	public record Constraints(String near, String density, Double budgetUsd, String difficulty) {}
	public record Contradiction(String issue, String resolution) {}

	/**
	 * One entry of the building program: what the settlement needs built, in its own terms. {@code type} is an Architect preset or an open snake_case type;
	 * {@code footprint} is S, M, L or XL.
	 */
	public record Building(String role, String type, int count, String footprint, boolean landmark, @Nullable String notes, @Nullable String placement) {
		public Building(String role, String type, int count, String footprint, boolean landmark, @Nullable String notes) {
			this(role, type, count, footprint, landmark, notes, null);
		}
	}

	/** Where in the settlement a program entry wants to stand (the layout scores lots against the survey for it); null = anywhere. */
	public static final List<String> PLACEMENTS = List.of("near_water", "central", "edge", "high_ground");

	/** Architect's open-type rule (a preset name also matches it). */
	public static final Pattern TYPE_SLUG = Pattern.compile("[a-z][a-z0-9_]{0,39}");
	public static final List<String> FOOTPRINTS = List.of("S", "M", "L", "XL");

	private static final Gson GSON = new Gson();

	/** Parses and sanity-checks a card. Throws {@link JsonParseException} when a required part is missing. */
	public static ConceptCard parse(JsonObject json) {
		ConceptCard c = GSON.fromJson(json, ConceptCard.class);
		if (c == null || c.site == null || c.style == null || c.purpose == null || c.constraints == null) {
			throw new JsonParseException("concept card needs site, style, purpose and constraints");
		}
		if (blank(c.site.text) || blank(c.style.text) || blank(c.purpose.text)) {
			throw new JsonParseException("concept card fields need text");
		}
		if (c.site.terrain == null || !List.of("find", "sculpt", "flat").contains(c.site.terrain)) {
			throw new JsonParseException("site.terrain must be find, sculpt or flat");
		}
		// structured output usually obeys the schema, but the lists are read without trusting it
		return new ConceptCard(c.name, c.site, c.style, c.purpose, c.story, c.constraints, orEmpty(c.avoid), c.interpretation == null ? "" : c.interpretation,
			orEmpty(c.contradictions), orEmpty(c.assumptions), sanitize(c.program));
	}

	/** Whether the card says what to build (cards made before programs existed do not). */
	public boolean hasProgram() {
		return program != null && !program.isEmpty();
	}

	/** Clamps what the parser may get wrong: count 1..8, an unknown footprint is M, a type that is not a slug becomes {@code custom}, a blank role takes the type. */
	static @Nullable List<Building> sanitize(@Nullable List<Building> in) {
		if (in == null) return null;
		List<Building> out = new ArrayList<>();
		for (Building b : in) {
			if (b == null) continue;
			String type = b.type != null && TYPE_SLUG.matcher(b.type).matches() ? b.type : "custom";
			String role = blank(b.role) ? type.replace('_', ' ') : b.role.strip();
			String fp = FOOTPRINTS.contains(b.footprint) ? b.footprint : "M";
			out.add(new Building(role, type, Math.max(1, Math.min(8, b.count)), fp, b.landmark, blank(b.notes) ? null : b.notes.strip(),
				b.placement != null && PLACEMENTS.contains(b.placement) ? b.placement : null));
		}
		return out.isEmpty() ? null : List.copyOf(out);
	}

	private static <T> List<T> orEmpty(@Nullable List<T> l) {
		return l == null ? List.of() : l;
	}

	/** The card's difficulty, or {@code fallback} when it names none. */
	public Difficulty difficultyOr(Difficulty fallback) {
		return constraints.difficulty == null ? fallback : Difficulty.parse(constraints.difficulty);
	}

	private static boolean blank(@Nullable String s) {
		return s == null || s.isBlank();
	}
}
