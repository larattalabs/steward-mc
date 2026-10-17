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
		return build(card, land, anchors, List.of(), owner, null, null);
	}

	/**
	 * A repair round when {@code previous} (the last plan) and {@code answers} (Architect's check of it, one line a part) are given: the plan is revised so
	 * the refused and malformed parts fit (their {@code wouldFit} where given), what fits is kept, and unsupported parts stay as they are (they wait).
	 */
	public static JobSpec build(String card, @Nullable String land, List<String> anchors, List<String> builds, @Nullable String owner, @Nullable String previous,
		@Nullable String answers) {
		return build(card, land, anchors, builds, owner, previous, answers, false);
	}

	/**
	 * As above; {@code refs}: the helper has {@code job.systemRefs} (Architect 1.25, C33), so the schema goes as {@link #SCHEMA_REF}, expanded by the helper
	 * from its own copy with the live catalogue, and the system text is only the instructions.
	 */
	public static JobSpec build(String card, @Nullable String land, List<String> anchors, List<String> builds, @Nullable String owner, @Nullable String previous,
		@Nullable String answers, boolean refs) {
		String user = userPrompt(card, land, anchors, builds);
		if (previous != null && answers != null) {
			user += "\nYour previous plan:\n" + previous + "\n\nArchitect's check of it, part by part:\n" + answers
				+ "\nRevise the plan: make the refused and malformed parts fit (use wouldFit where it is given), keep what fits as it is, and keep the unsupported parts"
				+ " as they are (they wait for Architect). Answer with the whole revised plan.\n";
		}
		JobSpec spec = new JobSpec("structured", user, refs ? instructions() : systemPrompt(), MODEL, "medium", schema(), List.of(), BUDGET_USD, 4, owner, TAG, null,
			SimAnswers.ext(SimAnswers.PLAN), List.of());
		return refs ? spec.systemRefs(List.of(SCHEMA_REF)) : spec;
	}

	/** The name a part plan gives the town as it stands, swapped for the town's region id before the check ({@link #TOWN}). */
	public static final String TOWN = "$TOWN";

	/**
	 * A part of the kept plan that waited for Architect's {@code block}, now built (docs/COMPOSITION.md section 11): a plan holding just that part, placed
	 * against the town as it stands (one region, named {@code {"region": "$TOWN"}}), the player's ask kept. A repair round when {@code previous} and
	 * {@code answers} are given.
	 */
	public static JobSpec part(String card, String keptPlan, String partJson, String block, @Nullable String owner, @Nullable String previous,
		@Nullable String answers, boolean refs) {
		StringBuilder b = new StringBuilder();
		b.append("The settlement's concept card:\n").append(card.strip()).append("\n\n");
		b.append("Its plan as it was kept (the parts Architect could not build then waited):\n").append(keptPlan.strip()).append("\n\n");
		b.append("The town now stands as one region. Name it in a plan as {\"region\": \"").append(TOWN).append("\"}: a wall round it is where: {around: {feature: ")
			.append("{region: \"").append(TOWN).append("\"}}}, a road or bridge to it ends on {region: \"").append(TOWN).append("\"}.\n");
		b.append("Architect can build ").append(block).append(" now. Write a plan (schemaVersion plan/0, no zones) whose only part is this one, as the player asked")
			.append(" for it, placed against the town as it stands; keep its id, label and required:\n").append(partJson.strip()).append("\n");
		b.append("Answer with the plan, waits (empty unless it still can't be built) and summary (one sentence for the player: what will be built).\n");
		String user = b.toString();
		if (previous != null && answers != null) {
			user += "\nYour previous plan:\n" + previous + "\n\nArchitect's check of it, part by part:\n" + answers
				+ "\nRevise the plan: make the part fit (use wouldFit where it is given), and answer with the whole revised plan.\n";
		}
		JobSpec spec = new JobSpec("structured", user, refs ? instructions() : systemPrompt(), MODEL, "medium", schema(), List.of(), BUDGET_USD, 4, owner, TAG, null,
			SimAnswers.ext(SimAnswers.PART), List.of());
		return refs ? spec.systemRefs(List.of(SCHEMA_REF)) : spec;
	}

	static String userPrompt(String card, @Nullable String land, List<String> anchors) {
		return userPrompt(card, land, anchors, List.of());
	}

	/** {@code builds}: one line a player's build in the claim ({@code PlayerBuilds.promptLines}), which every plan keeps and may build round. */
	static String userPrompt(String card, @Nullable String land, List<String> anchors, List<String> builds) {
		StringBuilder b = new StringBuilder();
		b.append("The settlement's concept card:\n").append(card.strip()).append("\n\n");
		b.append("The land round the founding stone: ").append(land == null || land.isBlank() ? "not surveyed" : land).append("\n");
		b.append("Anchors you may name: ").append(String.join(", ", anchors)).append(" (and Architect's own: claim_center, claim_n, claim_e, claim_s, claim_w).\n");
		if (!builds.isEmpty()) b.append("The player's own builds in the claim (kept as they are; name them as {build: id}):\n").append(String.join("\n", builds)).append("\n");
		return b.toString();
	}

	/** Architect's plan schema by reference (C33): its compact rendering, without the bookkeeping fields, with the live catalogue. */
	static final String SCHEMA_REF = "plan/0#compact";

	/** The prompt's system section alone (the schema comes by reference, ahead of it). */
	static String instructions() {
		String md = resource("steward/prompts/plan-writer.md");
		int i = md.indexOf("## System prompt");
		if (i < 0) throw new IllegalStateException("plan-writer.md has no '## System prompt' section");
		return md.substring(i + "## System prompt".length()).strip();
	}

	/** The prompt's system section, then Architect's plan/0 schema in full (a helper without systemRefs). */
	public static String systemPrompt() {
		String md = resource("steward/prompts/plan-writer.md");
		int i = md.indexOf("## System prompt");
		if (i < 0) throw new IllegalStateException("plan-writer.md has no '## System prompt' section");
		// the schema compact (Architect's jobs take at most 50,000 characters of system prompt; pretty-printed it is over)
		JsonObject plan0 = JsonParser.parseString(resource("steward/schema/plan-0.json")).getAsJsonObject();
		slimCatalogue(plan0);
		String schema = new com.google.gson.Gson().toJson(plan0);
		return md.substring(i + "## System prompt".length()).strip() + "\n\n## Architect's plan schema (plan/0)\n\n" + schema;
	}

	/**
	 * The catalogue entries without what only Architect's bookkeeping reads (sinceApi, schemaVersion, and a def that is just {@code #/$defs/<id>}), so the
	 * prompt stays inside Architect's 50,000-character system limit as the schema grows. The schema itself is unchanged.
	 */
	static void slimCatalogue(JsonObject plan0) {
		if (!plan0.has("x-catalogue") || !plan0.getAsJsonObject("x-catalogue").has("entries")) return;
		for (var e : plan0.getAsJsonObject("x-catalogue").getAsJsonArray("entries")) {
			JsonObject o = e.getAsJsonObject();
			o.remove("sinceApi");
			o.remove("schemaVersion");
			if (o.has("def") && o.has("id") && o.get("def").getAsString().equals("#/$defs/" + o.get("id").getAsString())) o.remove("def");
		}
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
