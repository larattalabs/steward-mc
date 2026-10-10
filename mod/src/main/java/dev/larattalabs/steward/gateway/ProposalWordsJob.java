package dev.larattalabs.steward.gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.Job;
import dev.larattalabs.architect.api.JobSpec;
import dev.larattalabs.steward.model.Proposal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code structured} job that words a rule's proposal in the settlement's own terms (prompts/proposal-words.md, schema/proposal-words.schema.json):
 * Sonnet, low effort, at most $0.03. The rules keep deciding what is proposed; this only names and describes it.
 */
public final class ProposalWordsJob {
	public static final String MODEL = ConceptCardJob.MODEL;
	public static final double BUDGET_USD = 0.03;
	public static final String TAG = "proposal-words";

	private ProposalWordsJob() {
	}

	/** The words, clipped to the schema's lengths. */
	public record Words(String title, String why, String role, @Nullable String notes) {}

	public static JobSpec build(String settlement, List<String> buildings, Proposal p, @Nullable String owner) {
		var b = p.building();
		String user = "The settlement: " + settlement + ".\nIts buildings: " + (buildings.isEmpty() ? "none yet" : String.join(", ", buildings)) + ".\n\n"
			+ "The idea to word: " + p.title() + ". Reason: " + p.why() + "\nThe building: " + b.role() + " (type " + b.type() + ", footprint " + b.footprint()
			+ (b.notes() == null ? "" : "; " + b.notes()) + ").\n";
		return new JobSpec("structured", user, systemPrompt(), MODEL, "low", schema(), List.of(), BUDGET_USD, 2, owner, TAG, null, SimAnswers.ext(SimAnswers.PROPOSAL),
			List.of());
	}

	public static String systemPrompt() {
		String md = resource("steward/prompts/proposal-words.md");
		int i = md.indexOf("## System prompt");
		if (i < 0) throw new IllegalStateException("proposal-words.md has no '## System prompt' section");
		return md.substring(i + "## System prompt".length()).strip();
	}

	public static JsonObject schema() {
		JsonObject o = JsonParser.parseString(resource("steward/schema/proposal-words.schema.json")).getAsJsonObject();
		o.remove("$schema");
		o.remove("$id");
		return o;
	}

	/** The words, or null when the job did not give usable ones (the rule's wording then stays). */
	public static @Nullable Words interpret(Job job) {
		if (!"done".equals(job.status())) return null;
		JsonElement r = job.result().orElse(null);
		if (r == null || !r.isJsonObject()) return null;
		try {
			return parse(r.getAsJsonObject());
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static @Nullable Words parse(JsonObject o) {
		String title = text(o, "title", 60), why = text(o, "why", 200), role = text(o, "role", 60), notes = text(o, "notes", 200);
		if (title == null || why == null || role == null) return null;
		return new Words(title, why, role, notes);
	}

	private static @Nullable String text(JsonObject o, String key, int max) {
		if (!o.has(key) || !o.get(key).isJsonPrimitive()) return null;
		String s = o.get(key).getAsString().replaceAll("\\s+", " ").strip();
		if (s.isEmpty()) return null;
		return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
	}

	private static String resource(String path) {
		try (InputStream in = ProposalWordsJob.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("missing resource " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + path, e);
		}
	}
}
