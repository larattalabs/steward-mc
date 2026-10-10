package dev.larattalabs.steward.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.JobSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code structured} job that turns the player's words into a concept card (docs/PLAN.md "Concept card",
 * prompts/concept-card.md). No tools and no world data: this call only interprets words, so it is cheap (Sonnet, low effort, a
 * budget of a few cents).
 */
public final class ConceptCardJob {
	public static final String MODEL = "claude-sonnet-5-5";
	public static final double BUDGET_USD = 0.10;
	public static final String TAG = "concept-card";
	/** The longest description a player may give (the card screen and the command both hold to it). */
	public static final int MAX_PROMPT = 1000;

	private ConceptCardJob() {
	}

	/** What the parser is told about the world besides the player's words. */
	public record Settings(boolean worldSurvival, String defaultDifficulty, int claimRadius) {}

	/**
	 * @param prompt the player's free text
	 * @param chips values the player filled in per field ({@code site}, {@code style}, {@code purpose}, {@code story}); they win over the text
	 * @param owner the Architect owner string of the settlement (or null before it exists)
	 */
	public static JobSpec build(String prompt, Map<String, String> chips, Settings settings, String owner) {
		if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("the prompt is empty");
		if (prompt.length() > MAX_PROMPT) throw new IllegalArgumentException("the description is longer than " + MAX_PROMPT + " characters");
		return new JobSpec("structured", userPrompt(prompt.strip(), chips, settings), systemPrompt(), MODEL, "low", schema(), List.of(),
			BUDGET_USD, 4, owner, TAG, null, new JsonObject(), List.of());
	}

	static String userPrompt(String prompt, Map<String, String> chips, Settings s) {
		StringBuilder b = new StringBuilder();
		b.append("Player's description:\n").append(prompt).append("\n");
		Map<String, String> filled = new LinkedHashMap<>();
		if (chips != null) {
			for (String k : List.of("site", "style", "purpose", "story")) {
				String v = chips.get(k);
				if (v != null && !v.isBlank()) filled.put(k, v.strip());
			}
		}
		if (!filled.isEmpty()) {
			b.append("\nFields the player filled in (these win over your reading of the description):\n");
			filled.forEach((k, v) -> b.append("- ").append(k).append(": ").append(v).append("\n"));
		}
		b.append("\nSettings: world is ").append(s.worldSurvival() ? "survival" : "creative")
			.append("; default difficulty ").append(s.defaultDifficulty())
			.append("; claim radius ").append(s.claimRadius()).append(" blocks.\n");
		return b.toString();
	}

	/** The system prompt: the "System prompt" section of prompts/concept-card.md. */
	public static String systemPrompt() {
		String md = resource("steward/prompts/concept-card.md");
		int i = md.indexOf("## System prompt");
		if (i < 0) throw new IllegalStateException("concept-card.md has no '## System prompt' section");
		return md.substring(i + "## System prompt".length()).strip();
	}

	/** The schema sent to the sidecar: the file's schema without the {@code $schema} and {@code $id} keywords (not needed, and not all runtimes accept them). */
	public static JsonObject schema() {
		JsonObject o = JsonParser.parseString(resource("steward/schema/concept-card.schema.json")).getAsJsonObject();
		o.remove("$schema");
		o.remove("$id");
		return o;
	}

	private static String resource(String path) {
		try (InputStream in = ConceptCardJob.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("missing resource " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
