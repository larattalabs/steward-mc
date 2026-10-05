package dev.larattalabs.steward.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.List;

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
	List<String> assumptions
) {
	public record Field(String text, String template, String terrain, String size) {}
	public record Story(String text) {}
	public record Constraints(String near, String density, Double budgetUsd, String difficulty) {}
	public record Contradiction(String issue, String resolution) {}

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
		return c;
	}

	/** The card's difficulty, or {@code fallback} when it names none. */
	public Difficulty difficultyOr(Difficulty fallback) {
		return constraints.difficulty == null ? fallback : Difficulty.parse(constraints.difficulty);
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}
}
