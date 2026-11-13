package dev.larattalabs.steward.gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.Job;
import dev.larattalabs.architect.api.JobSpec;
import dev.larattalabs.steward.model.TalkAnswer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code structured} job that reads what the player said to the steward (prompts/steward-talk.md, schema/steward-talk.schema.json): Sonnet, low
 * effort, a few cents. No tools: the settlement comes in as a short block of text.
 */
public final class TalkJob {
	public static final String MODEL = ConceptCardJob.MODEL;
	public static final double BUDGET_USD = 0.05;
	public static final String TAG = "steward-talk";

	private TalkJob() {
	}

	/** What the steward knows of its settlement when it reads the player's words. */
	public record Context(String name, String site, String style, String purpose, List<String> town, List<String> districts, List<String> proposals,
		String permission, boolean townFull, List<String> modules) {
		public Context {
			modules = modules == null ? List.of() : List.copyOf(modules);
		}

		/** The modules' ids ({@code "<id>: ..."}). */
		public List<String> moduleIds() {
			return modules.stream().map(m -> m.contains(":") ? m.substring(0, m.indexOf(':')) : m).toList();
		}

		/** As before modules: none listed. */
		public Context(String name, String site, String style, String purpose, List<String> town, List<String> districts, List<String> proposals,
			String permission, boolean townFull) {
			this(name, site, style, purpose, town, districts, proposals, permission, townFull, List.of());
		}
	}

	public static JobSpec build(String said, Context c, @Nullable String owner) {
		return build(said, c, owner, new JsonObject());
	}

	/** {@code ext}: e.g. Architect's sim answer for the $0 checks. */
	public static JobSpec build(String said, Context c, @Nullable String owner, JsonObject ext) {
		return new JobSpec("structured", userPrompt(said, c), systemPrompt(), MODEL, "low", schema(c.moduleIds()), List.of(), BUDGET_USD, 2, owner, TAG, null, ext, List.of());
	}

	static String userPrompt(String said, Context c) {
		StringBuilder b = new StringBuilder();
		b.append("The player said:\n").append(said.strip()).append("\n\n");
		b.append("The settlement: ").append(c.name()).append(" (").append(c.site()).append("; ").append(c.style()).append("; for ").append(c.purpose()).append(").\n");
		b.append("Buildings in the town: ").append(c.town().isEmpty() ? "none yet" : String.join(", ", c.town())).append(".\n");
		b.append("Districts: ").append(c.districts().isEmpty() ? "none" : String.join("; ", c.districts())).append(".\n");
		b.append("Ideas waiting in the inbox: ").append(c.proposals().isEmpty() ? "none" : String.join(", ", c.proposals())).append(".\n");
		b.append("Permission: ").append(c.permission()).append(". The town's street ").append(c.townFull() ? "is full" : "has room").append(".\n");
		if (!c.modules().isEmpty()) b.append("Working modules: ").append(String.join("; ", c.modules())).append(".\n");
		return b.toString();
	}

	public static String systemPrompt() {
		String md = resource("steward/prompts/steward-talk.md");
		int i = md.indexOf("## System prompt");
		if (i < 0) throw new IllegalStateException("steward-talk.md has no '## System prompt' section");
		return md.substring(i + "## System prompt".length()).strip();
	}

	public static JsonObject schema() {
		return schema(List.of());
	}

	/**
	 * The schema as sent: {@code module.id} takes only the catalog's offerable ids (docs/MODULES.md 4.1, "its schema takes the module ids from the live
	 * catalog"); with none, the member is left out.
	 */
	public static JsonObject schema(List<String> moduleIds) {
		JsonObject o = JsonParser.parseString(resource("steward/schema/steward-talk.schema.json")).getAsJsonObject();
		o.remove("$schema");
		o.remove("$id");
		JsonObject props = o.getAsJsonObject("properties");
		if (moduleIds.isEmpty()) props.remove("module");
		else {
			com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
			moduleIds.forEach(ids::add);
			props.getAsJsonObject("module").getAsJsonObject("properties").getAsJsonObject("id").add("enum", ids);
		}
		return o;
	}

	/** The answer, or why there is none. */
	public record Result(@Nullable TalkAnswer answer, @Nullable String error) {}

	public static Result interpret(Job job) {
		if (!"done".equals(job.status())) return new Result(null, "the talk job did not finish: " + job.error().orElse(job.status()));
		JsonElement r = job.result().orElse(null);
		if (r == null || !r.isJsonObject()) return new Result(null, "the talk job returned nothing");
		try {
			return new Result(TalkAnswer.parse(r.getAsJsonObject()), null);
		} catch (RuntimeException e) {
			// whatever came back, a bad answer is a failed answer, never an exception on the server thread
			return new Result(null, "the answer could not be read: " + e);
		}
	}

	private static String resource(String path) {
		try (InputStream in = TalkJob.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("missing resource " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + path, e);
		}
	}
}
