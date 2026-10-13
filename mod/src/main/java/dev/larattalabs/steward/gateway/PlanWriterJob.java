package dev.larattalabs.steward.gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.Job;
import dev.larattalabs.architect.api.JobSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The plan writer (docs/COMPOSITION.md, plan v0): a {@code structured} job that composes one settlement as Architect's plan/0 from its concept card, the
 * land round its stone and the anchors Steward supplies (prompts/plan-writer.md, schema/plan-writer.schema.json; Architect's schema/plan-0.json in the
 * system prompt, so it is cached). Sonnet, medium effort, at most $0.50 a call (Noah: about $1 a plan with repair).
 */
public final class PlanWriterJob {
	public static final String MODEL = ConceptCardJob.MODEL;
	public static final double BUDGET_USD = 0.50;
	public static final String TAG = "steward-plan";

	private PlanWriterJob() {
	}

	/** {@code card}: the concept card as JSON; {@code land}: the land line or null; {@code anchors}: the anchor names the caller will supply. */
	public static JobSpec build(String card, @Nullable String land, List<String> anchors, @Nullable String owner) {
		return new JobSpec("structured", userPrompt(card, land, anchors), systemPrompt(), MODEL, "medium", schema(), List.of(), BUDGET_USD, 4, owner, TAG, null,
			new JsonObject(), List.of());
	}

	static String userPrompt(String card, @Nullable String land, List<String> anchors) {
		StringBuilder b = new StringBuilder();
		b.append("The settlement's concept card:\n").append(card.strip()).append("\n\n");
		b.append("The land round the founding stone: ").append(land == null || land.isBlank() ? "not surveyed" : land).append("\n");
		b.append("Anchors you may name: ").append(String.join(", ", anchors)).append(" (and Architect's own: claim_center, claim_n, claim_e, claim_s, claim_w).\n");
		return b.toString();
	}

	/** The prompt's system section, then Architect's plan/0 schema in full. */
	public static String systemPrompt() {
		String md = resource("steward/prompts/plan-writer.md");
		int i = md.indexOf("## System prompt");
		if (i < 0) throw new IllegalStateException("plan-writer.md has no '## System prompt' section");
		// the schema compact (Architect's jobs take at most 50,000 characters of system prompt; pretty-printed it is over)
		String schema = new com.google.gson.Gson().toJson(JsonParser.parseString(resource("steward/schema/plan-0.json")));
		return md.substring(i + "## System prompt".length()).strip() + "\n\n## Architect's plan schema (plan/0)\n\n" + schema;
	}

	public static JsonObject schema() {
		JsonObject o = JsonParser.parseString(resource("steward/schema/plan-writer.schema.json")).getAsJsonObject();
		o.remove("$schema");
		o.remove("$id");
		return o;
	}

	/** The answer ({@code plan}, {@code waits}, {@code summary}) as JSON, or why there is none. */
	public record Result(@Nullable JsonObject answer, @Nullable String error, double usd) {}

	public static Result interpret(Job job) {
		double usd = job.cost() == null ? 0 : job.cost().usd();
		if (!"done".equals(job.status())) return new Result(null, "the plan job did not finish: " + job.error().orElse(job.status()), usd);
		JsonElement r = job.result().orElse(null);
		if (r == null || !r.isJsonObject() || !r.getAsJsonObject().has("plan")) return new Result(null, "the plan job returned no plan", usd);
		return new Result(r.getAsJsonObject(), null, usd);
	}

	private static String resource(String path) {
		try (InputStream in = PlanWriterJob.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("missing resource " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + path, e);
		}
	}
}
