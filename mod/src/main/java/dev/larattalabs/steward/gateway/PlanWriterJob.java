package dev.larattalabs.steward.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.Job;
import dev.larattalabs.architect.api.JobSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The plan writer (docs/COMPOSITION.md, plan v0): a {@code structured} job that composes one settlement as Architect's plan/0 from its concept card, the
 * land round its stone and the anchors Steward supplies (prompts/plan-writer.md, schema/plan-writer.schema.json; Architect's plan/0 schema, by reference or
 * from the loaded jar's {@code Regions.planSchema()}, in the system prompt, so it is cached). Sonnet, medium effort, at most $0.50 a call (Noah: about $1 a plan with repair).
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

	/** As below, with no catalogue text for the repair. */
	public static JobSpec build(String card, @Nullable String land, List<String> anchors, List<String> builds, @Nullable String owner, @Nullable String previous,
		@Nullable String answers, boolean refs) {
		return build(card, land, anchors, builds, owner, previous, answers, refs, null);
	}

	/**
	 * As above; {@code refs}: the helper has {@code job.systemRefs} (Architect 1.25, C33), so the schema goes as {@link #SCHEMA_REF}, expanded by the helper
	 * from its own copy with the live catalogue, and the system text is only the instructions. {@code catalogue}: in a repair round, the long text of the
	 * failing parts' kinds ({@link #catalogueFor}), which {@code plan/0#compact} leaves out since Architect 0.62.0; null for none.
	 */
	public static JobSpec build(String card, @Nullable String land, List<String> anchors, List<String> builds, @Nullable String owner, @Nullable String previous,
		@Nullable String answers, boolean refs, @Nullable String catalogue) {
		String user = userPrompt(card, land, anchors, builds);
		if (previous != null && answers != null) {
			user += "\nYour previous plan:\n" + previous + "\n\nArchitect's check of it, part by part:\n" + answers + catalogueSection(catalogue)
				+ "\nRevise the plan: make the refused and malformed parts fit (use wouldFit where it is given), keep what fits as it is, and keep the unsupported parts"
				+ " as they are (they wait for Architect). Answer with the whole revised plan.\n";
		}
		JobSpec spec = new JobSpec("structured", user, refs ? instructions() : systemPrompt(), MODEL, "medium", schema(), List.of(), BUDGET_USD, 4, owner, TAG, null,
			SimAnswers.ext(SimAnswers.PLAN), List.of());
		return refs ? spec.systemRefs(List.of(SCHEMA_REF, VOCAB_REF)) : spec;
	}

	/** The name a part plan gives the town as it stands, swapped for the town's region id before the check ({@link #TOWN}). */
	public static final String TOWN = "$TOWN";

	/**
	 * A part of the kept plan that waited for Architect's {@code block}, now built (docs/COMPOSITION.md section 11): a plan holding just that part, placed
	 * against the town as it stands (one region, named {@code {"region": "$TOWN"}}), the player's ask kept. A repair round when {@code previous} and
	 * {@code answers} are given.
	 */
	public static JobSpec part(String card, String keptPlan, String partJson, String block, List<String> others, @Nullable String owner, @Nullable String previous,
		@Nullable String answers, boolean refs) {
		return part(card, keptPlan, partJson, block, others, owner, previous, answers, refs, null);
	}

	/** As above; {@code catalogue}: in a repair round, the long text of the part's kind ({@link #catalogueFor}), or null. */
	public static JobSpec part(String card, String keptPlan, String partJson, String block, List<String> others, @Nullable String owner, @Nullable String previous,
		@Nullable String answers, boolean refs, @Nullable String catalogue) {
		StringBuilder b = new StringBuilder();
		b.append("The settlement's concept card:\n").append(card.strip()).append("\n\n");
		b.append("Its plan as it was kept (the parts Architect could not build then waited):\n").append(keptPlan.strip()).append("\n\n");
		b.append("The town now stands as one region. Name it in a plan as {\"region\": \"").append(TOWN).append("\"}: a wall round it is where: {around: {feature: ")
			.append("{region: \"").append(TOWN).append("\"}}}, a road or bridge to it ends on {region: \"").append(TOWN).append("\"}.\n");
		if (!others.isEmpty()) b.append("Also standing outside the town's region (added since): fit the part round them too, or say in notes why not:\n")
			.append(String.join("\n", others)).append("\n");
		b.append("Architect can build ").append(block).append(" now. Write a plan (schemaVersion plan/0, no zones) whose only part is this one, as the player asked")
			.append(" for it, placed against the town as it stands; keep its id, label and required:\n").append(partJson.strip()).append("\n");
		b.append("Answer with the plan, waits (empty unless it still can't be built) and summary (one sentence for the player: what will be built).\n");
		String user = b.toString();
		if (previous != null && answers != null) {
			user += "\nYour previous plan:\n" + previous + "\n\nArchitect's check of it, part by part:\n" + answers + catalogueSection(catalogue)
				+ "\nRevise the plan: make the part fit (use wouldFit where it is given), and answer with the whole revised plan.\n";
		}
		JobSpec spec = new JobSpec("structured", user, refs ? instructions() : systemPrompt(), MODEL, "medium", schema(), List.of(), BUDGET_USD, 4, owner, TAG, null,
			SimAnswers.ext(SimAnswers.PART), List.of());
		return refs ? spec.systemRefs(List.of(SCHEMA_REF, VOCAB_REF)) : spec;
	}

	/** The kinds a repair round looks up: each refused or malformed part's catalogue id (its answer's {@code block}), sorted, once each. */
	public static java.util.SortedSet<String> repairKinds(List<JsonObject> parts) {
		java.util.SortedSet<String> out = new java.util.TreeSet<>();
		for (JsonObject p : parts) {
			String cls = p.has("class") && p.get("class").isJsonPrimitive() ? p.get("class").getAsString() : "";
			if ((cls.equals("refused") || cls.equals("malformed")) && p.has("block") && p.get("block").isJsonPrimitive() && !p.get("block").getAsString().isBlank())
				out.add(p.get("block").getAsString());
		}
		return out;
	}

	/** At most this much catalogue text in a repair prompt (a full kind's entry is about 1,300 characters; more kinds than fit are named only). */
	static final int CATALOGUE_CHARS = 20_000;

	/**
	 * The long text of {@code kinds} from Architect's {@code Regions.planCatalogue(kinds)} ({@code lookup}): each kind's entry (state, note, limits) with
	 * its plan/0 def and every description, one line a kind. Null when there are no kinds, or the lookup throws (an Architect before 0.62.0) or has no entries:
	 * the repair round goes without it.
	 */
	public static @Nullable String catalogueFor(java.util.Set<String> kinds, java.util.function.Function<java.util.Set<String>, @Nullable JsonObject> lookup) {
		if (kinds.isEmpty()) return null;
		JsonObject cat;
		try {
			cat = lookup.apply(kinds);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
		if (cat == null || !cat.has("entries") || !cat.get("entries").isJsonArray() || cat.getAsJsonArray("entries").isEmpty()) return null;
		StringBuilder b = new StringBuilder();
		List<String> over = new java.util.ArrayList<>();
		for (JsonElement e : cat.getAsJsonArray("entries")) {
			if (!e.isJsonObject()) continue;
			String line = e.toString();
			String id = e.getAsJsonObject().has("id") && e.getAsJsonObject().get("id").isJsonPrimitive() ? e.getAsJsonObject().get("id").getAsString() : "?";
			if (b.length() + line.length() > CATALOGUE_CHARS) over.add(id);
			else b.append(line).append('\n');
		}
		if (!over.isEmpty()) b.append("(left out for length: ").append(String.join(", ", over)).append(")\n");
		return b.length() == 0 ? null : b.toString();
	}

	private static String catalogueSection(@Nullable String catalogue) {
		return catalogue == null ? "" : "\nArchitect's catalogue for the kinds to repair (each kind's note, limits and its plan/0 def with every description, which the schema"
			+ " you were given leaves out):\n" + catalogue;
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
	/** The block vocabulary (families, groups, tiers, colours; VOC), by reference: what a palette rule's family names. */
	static final String VOCAB_REF = "vocab/1#compact";

	/** The prompt's system section alone (the schema comes by reference, ahead of it). */
	static String instructions() {
		String md = resource("steward/prompts/plan-writer.md");
		int i = md.indexOf("## System prompt");
		if (i < 0) throw new IllegalStateException("plan-writer.md has no '## System prompt' section");
		return md.substring(i + "## System prompt".length()).strip();
	}

	/** The prompt's system section, then the loaded Architect's plan/0 schema ({@code Regions.planSchema()}, compact) for a helper without systemRefs. */
	public static String systemPrompt() {
		String md = resource("steward/prompts/plan-writer.md");
		int i = md.indexOf("## System prompt");
		if (i < 0) throw new IllegalStateException("plan-writer.md has no '## System prompt' section");
		// the schema compact (Architect's jobs take at most 120,000 characters of system prompt; pretty-printed it is over)
		JsonObject plan0 = JsonParser.parseString(dev.larattalabs.architect.api.Regions.planSchema()).getAsJsonObject();
		slimCatalogue(plan0);
		String schema = new com.google.gson.Gson().toJson(plan0);
		return md.substring(i + "## System prompt".length()).strip() + "\n\n## Architect's plan schema (plan/0)\n\n" + schema;
	}

	/** The catalogue entry members {@code plan/0#compact} keeps (Architect's sidecar COMPACT.lineKeys): a kind's long note comes by kind on repair. */
	static final List<String> ENTRY_KEYS = List.of("id", "state", "until", "aliasOf", "replacedBy", "retiredInApi", "limits");

	/**
	 * The catalogue as {@code plan/0#compact} has it: each entry only its {@link #ENTRY_KEYS}, without the catalogue's api, kitVersion and schemaVersion,
	 * so the inline prompt stays inside Architect's system limit as the schema grows. The $defs are unchanged.
	 */
	static void slimCatalogue(JsonObject plan0) {
		if (!plan0.has("x-catalogue") || !plan0.getAsJsonObject("x-catalogue").has("entries")) return;
		JsonObject cat = plan0.getAsJsonObject("x-catalogue");
		for (String k : List.of("api", "kitVersion", "schemaVersion")) cat.remove(k);
		for (var e : cat.getAsJsonArray("entries")) {
			JsonObject o = e.getAsJsonObject();
			for (String k : List.copyOf(o.keySet())) if (!ENTRY_KEYS.contains(k)) o.remove(k);
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
		JsonObject answer = r.getAsJsonObject().deepCopy();
		if (answer.get("plan").isJsonObject()) withGateMechanisms(answer.getAsJsonObject("plan"));
		return new Result(answer, null, usd);
	}

	/** Architect GM: a gatehouse's mechanism fits a lot at least this wide along its wall and this deep through it (CONTRACT GM §14, 13 x 7 x 10). */
	static final int MECH_ALONG = 13, MECH_THROUGH = 7;

	/**
	 * Every gatehouse a wall names gets a portcullis (Noah, 2026-11-03, once Architect's GM-a2 bills it in survival). On a wall with a {@code lots} member,
	 * its {@code at: gate} entries get {@code mech}, but only when the lot's planned size fits the mechanism: an explicit {@code mech} on a required wall
	 * that can't hold it is refused NO_ROOM. On a wall without one, the lenient {@code params.mechanism}, which a too-small gatehouse answers with a plain
	 * passage. A mechanism the plan already names, or declines on the wall ({@code params.mechanism: "none"}), is kept. Applied to the writer's answer, never to a kept plan, so a saved plan stays as approved.
	 */
	static void withGateMechanisms(JsonObject plan) {
		if (!plan.has("connectors") || !plan.get("connectors").isJsonArray()) return;
		Map<String, JsonObject> lots = new java.util.HashMap<>();
		if (plan.has("zones") && plan.get("zones").isJsonArray()) for (JsonElement z : plan.getAsJsonArray("zones")) {
			JsonObject prog = z.isJsonObject() && z.getAsJsonObject().has("program") && z.getAsJsonObject().get("program").isJsonObject() ? z.getAsJsonObject().getAsJsonObject("program") : null;
			if (prog == null || !prog.has("lots") || !prog.get("lots").isJsonArray()) continue;
			for (JsonElement l : prog.getAsJsonArray("lots"))
				if (l.isJsonObject() && l.getAsJsonObject().has("id") && l.getAsJsonObject().get("id").isJsonPrimitive()) lots.put(l.getAsJsonObject().get("id").getAsString(), l.getAsJsonObject());
		}
		for (JsonElement e : plan.getAsJsonArray("connectors")) {
			if (!e.isJsonObject()) continue;
			JsonObject w = e.getAsJsonObject();
			if (!w.has("kind") || !w.get("kind").isJsonPrimitive() || !"wall".equals(w.get("kind").getAsString())) continue;
			JsonObject params = w.has("params") && w.get("params").isJsonObject() ? w.getAsJsonObject("params") : null;
			if (w.has("lots") && w.get("lots").isJsonArray()) {
				// the card asked for open gates: the writer says so on the wall (lots entries have no "none")
				if (params != null && declines(params.get("mechanism"))) continue;
				for (JsonElement q : w.getAsJsonArray("lots")) {
					if (!q.isJsonObject()) continue;
					JsonObject entry = q.getAsJsonObject();
					String lot = entry.has("lot") && entry.get("lot").isJsonPrimitive() ? entry.get("lot").getAsString() : null;
					JsonObject spec = lot == null ? null : lots.get(lot);
					boolean gate = entry.has("at") && entry.get("at").isJsonPrimitive() ? "gate".equals(entry.get("at").getAsString())
						: spec != null && spec.has("type") && "gatehouse".equals(spec.get("type").getAsString());
					if (!gate || entry.has("mech") || !fitsMechanism(spec)) continue;
					entry.addProperty("mech", "portcullis");
				}
				continue;
			}
			boolean namesGate = params != null && (params.has("gate_lot") || (params.has("gate") && params.get("gate").isJsonPrimitive()) || (params.has("gates") && params.get("gates").isJsonArray()));
			if (!namesGate || params.has("mechanism") || params.has("gate_mech")) continue;
			params.addProperty("mechanism", "portcullis");
		}
	}

	private static boolean declines(@Nullable JsonElement v) {
		if (v == null || !v.isJsonPrimitive()) return false;
		return v.getAsJsonPrimitive().isBoolean() ? !v.getAsBoolean() : java.util.Set.of("none", "no", "false", "open").contains(v.getAsString().toLowerCase(java.util.Locale.ROOT));
	}

	private static boolean fitsMechanism(@Nullable JsonObject spec) {
		if (spec == null || !spec.has("size") || !spec.get("size").isJsonArray() || spec.getAsJsonArray("size").size() < 2) return false;
		JsonArray size = spec.getAsJsonArray("size");
		return size.get(0).isJsonPrimitive() && size.get(1).isJsonPrimitive() && size.get(0).getAsInt() >= MECH_ALONG && size.get(1).getAsInt() >= MECH_THROUGH;
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
