package dev.larattalabs.steward.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.ComposeRequest;
import dev.larattalabs.architect.api.Design;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * A settlement's plan, composed by Architect's composer (docs/COMPOSITION.md section 13, slice CA; Architect 7c CO/AU, {@code Regions.compose}):
 * Architect owns the engine (plan/0, its catalogue, the composer's rules, the checks, the repair rounds, the oscillation stop). Steward sends the
 * concept card as the brief and the hints ({@link ComposeRequest#EXT_HINTS}: the card, its wanted buildings, the land line and the {@code stone} and
 * {@code player} anchors), its own rules ({@link ComposeRequest#EXT_SYSTEM}, prompts/compose-rules.md) and two repair rounds, under a $1.00 cap for the
 * whole compose. Since CA2 (Architect CP, API 1.63.0): no template pick ({@link ComposeRequest#EXT_SKIP_PICK}), the block families in the composer's
 * system ({@link ComposeRequest#EXT_VOCAB} {@code families}), the settlement's level and owner, and Architect's $0 street round inside the compose
 * (default on). A waiting part (COMPOSITION section 11) is composed the same way, one part a request ({@link #partRequest}). Pure but for the resource read.
 */
public final class ComposeJob {
	/** One compose's cap, the template pick and every round together (Noah: about $1 a plan with repair). Architect enforces it. */
	public static final double BUDGET_USD = 1.00;
	/**
	 * The cap a compose is sent with: {@link #BUDGET_USD}, or lower when {@code STEWARD_COMPOSE_CAP_USD} says so (a paid proof run holding a card job
	 * and a compose under one approved cap). It never raises the cap.
	 */
	public static double cap() {
		return capOf(System.getenv("STEWARD_COMPOSE_CAP_USD"));
	}

	static double capOf(@Nullable String env) {
		try {
			double v = env == null ? BUDGET_USD : Double.parseDouble(env.strip());
			return v > 0 && v < BUDGET_USD ? v : BUDGET_USD;
		} catch (NumberFormatException e) {
			return BUDGET_USD;
		}
	}

	/** Repair rounds after the first (Architect allows 0 to 2; it repairs only while a part is refused or malformed, AU). */
	public static final int REPAIR_ROUNDS = 2;
	/** Steward's own ext members on the request, read back from {@code Design.request().ext} when the design ends (after a restart too). */
	public static final String EXT_SETTLEMENT = "steward:settlement", EXT_CARD = "steward:card", EXT_PLAYER = "steward:player";
	/** Architect's sim-helper key ($0 checks only; the real helper never reads it): the composer's rounds, scripted. */
	static final String SIM_COMPOSE = "architect:simCompose";
	/**
	 * Architect's caps on the hints and the caller's rules (CONTRACT "Slice CO as built"; CP e: with {@code composeVocab} families the rules may be at most
	 * 28,000 characters).
	 */
	static final int HINTS_MAX = 16_000, SYSTEM_MAX = 28_000, BRIEF_MAX = 2_000;
	/** CP's block-families value for {@link ComposeRequest#EXT_VOCAB} (the only one Architect takes). */
	static final String VOCAB_FAMILIES = "families";
	/** The Architect API minor that ships CP (v0.71.0, API 1.63.0): skipPick, composeVocab, summary, waits[].say, leftOut, level and owner. */
	public static final int CP_MINOR = 63;

	/** Whether the loaded Architect's API ({@code apiVersion}) has CP: 1.63.0 or later in major 1. Pure. */
	public static boolean cp(@Nullable String apiVersion) {
		if (apiVersion == null) return false;
		try {
			String[] v = apiVersion.split("\\.");
			return Integer.parseInt(v[0]) == 1 && Integer.parseInt(v[1]) >= CP_MINOR;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private ComposeJob() {
	}

	/** The card's sha-256, first 12 hex digits: the request's key and the staleness check name the card by it. */
	public static String cardSha(String cardJson) {
		try {
			byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(cardJson.getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(d).substring(0, 12);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The compose's operation key: the same settlement and card adopt the first compose (Architect's opKey: same key and body, the same design), so a
	 * retry or a restart never pays twice. {@code [A-Za-z0-9_.:-]{1,128}}.
	 */
	public static String opKey(String settlementId, String cardSha) {
		String sid = settlementId.replaceAll("[^A-Za-z0-9_.:-]", "_");
		if (sid.length() > 90) sid = sid.substring(0, 90);
		return "steward.compose." + sid + "." + cardSha;
	}

	/** The brief: the card's name and its site, purpose and interpretation in its own words, one paragraph, at most {@link #BRIEF_MAX}. */
	static String brief(JsonObject card) {
		StringBuilder b = new StringBuilder();
		String name = str(card, "name");
		b.append(name.isEmpty() ? "A settlement" : name).append(". ");
		for (String f : List.of("site", "purpose")) {
			String t = card.has(f) && card.get(f).isJsonObject() ? str(card.getAsJsonObject(f), "text") : "";
			if (!t.isEmpty()) b.append(t.strip()).append(t.strip().endsWith(".") ? " " : ". ");
		}
		String i = str(card, "interpretation");
		if (!i.isEmpty()) b.append(i.strip());
		String out = b.toString().strip();
		return out.length() <= BRIEF_MAX ? out : out.substring(0, BRIEF_MAX - 1) + "…";
	}

	/** A card building the plan must hold: its type, count and role in the card's words. */
	public record Wanted(String type, int count, String role) {
	}

	/** The card's program as wanted buildings (type, count, role), in its order. */
	public static List<Wanted> wanted(JsonObject card) {
		List<Wanted> out = new ArrayList<>();
		if (!card.has("program") || !card.get("program").isJsonArray()) return out;
		for (JsonElement e : card.getAsJsonArray("program")) {
			if (!e.isJsonObject()) continue;
			JsonObject p = e.getAsJsonObject();
			String type = slug(str(p, "type"));
			if (type.isEmpty()) continue;
			int n = p.has("count") && p.get("count").isJsonPrimitive() && p.get("count").getAsJsonPrimitive().isNumber() ? Math.max(1, p.get("count").getAsInt()) : 1;
			out.add(new Wanted(type, n, str(p, "role").isEmpty() ? type.replace('_', ' ') : str(p, "role")));
		}
		return out;
	}

	/**
	 * The hints: {@code {card, wanted, land, anchors}}. The card is sent whole; when the hints would pass Architect's 16,000 characters its long text
	 * fields go first (assumptions, interpretation, story), then the program's notes, so the buildings always go.
	 */
	public static JsonObject hints(JsonObject card, @Nullable String land, Map<String, BlockPos> anchors) {
		JsonObject c = card.deepCopy();
		JsonObject h = build(c, land, anchors);
		List<Runnable> trims = List.of(() -> c.remove("assumptions"), () -> c.remove("interpretation"), () -> c.remove("story"), () -> c.remove("contradictions"),
			() -> {
				if (c.has("program") && c.get("program").isJsonArray()) for (JsonElement e : c.getAsJsonArray("program")) if (e.isJsonObject()) e.getAsJsonObject().remove("notes");
			});
		for (Runnable t : trims) {
			if (h.toString().length() <= HINTS_MAX) break;
			t.run();
			h = build(c, land, anchors);
		}
		return h;
	}

	private static JsonObject build(JsonObject card, @Nullable String land, Map<String, BlockPos> anchors) {
		JsonObject h = new JsonObject();
		h.add("card", card);
		JsonArray w = new JsonArray();
		for (Wanted x : wanted(card)) {
			JsonObject o = new JsonObject();
			o.addProperty("type", x.type());
			o.addProperty("count", x.count());
			o.addProperty("role", x.role());
			w.add(o);
		}
		h.add("wanted", w);
		h.addProperty("land", land == null || land.isBlank() ? "not surveyed" : land);
		JsonObject an = new JsonObject();
		for (var e : new java.util.TreeMap<>(anchors).entrySet()) {
			JsonArray p = new JsonArray();
			p.add(e.getValue().getX());
			p.add(e.getValue().getY());
			p.add(e.getValue().getZ());
			an.add(e.getKey(), p);
		}
		h.add("anchors", an);
		return h;
	}

	/** Steward's rules (prompts/compose-rules.md's {@code ## Rules} section), sent as the caller's rules. */
	public static String rules() {
		String md = resource("steward/prompts/compose-rules.md");
		int i = md.indexOf("## Rules");
		if (i < 0) throw new IllegalStateException("compose-rules.md has no '## Rules' section");
		return md.substring(i + "## Rules".length()).strip();
	}

	/**
	 * The ext: the hints, the rules, the repair rounds, CP's no-pick and block families, Steward's own members, and (on Architect's sim helper, $0 checks)
	 * a scripted plan answer ({@link SimAnswers#PLAN}, {@code {plan, summary}}) as the composer's rounds.
	 */
	public static JsonObject ext(String settlementId, String cardSha, @Nullable String player, JsonObject hints) {
		JsonObject ext = common(hints, rules());
		ext.addProperty(EXT_SETTLEMENT, settlementId);
		ext.addProperty(EXT_CARD, cardSha);
		if (player != null) ext.addProperty(EXT_PLAYER, player);
		simRounds(ext, SimAnswers.PLAN, null);
		return ext;
	}

	/** The members every Steward compose sends: the hints, the caller's rules, two repair rounds, no template pick (CP), the block families (CP). */
	private static JsonObject common(JsonObject hints, String rules) {
		JsonObject ext = new JsonObject();
		ext.add(ComposeRequest.EXT_HINTS, hints);
		ext.addProperty(ComposeRequest.EXT_SYSTEM, rules);
		ext.addProperty(ComposeRequest.EXT_REPAIR_ROUNDS, REPAIR_ROUNDS);
		ext.addProperty(ComposeRequest.EXT_SKIP_PICK, true);
		ext.addProperty(ComposeRequest.EXT_VOCAB, VOCAB_FAMILIES);
		return ext;
	}

	/**
	 * (Sim helper only) The scripted answer of {@code kind} ({@code {plan, summary}}), when one is set, as the composer's one scripted round; {@code town}
	 * (a part's) stands for the {@link #TOWN} name in it.
	 */
	private static void simRounds(JsonObject ext, String kind, @Nullable String town) {
		JsonObject sim = SimAnswers.ext(kind);
		if (!sim.has("architect:simAnswer") || !sim.get("architect:simAnswer").isJsonObject() || !sim.getAsJsonObject("architect:simAnswer").has("plan")) return;
		JsonObject a = sim.getAsJsonObject("architect:simAnswer");
		JsonObject round = new JsonObject();
		JsonElement plan = a.get("plan").deepCopy();
		if (town != null) plan = JsonParser.parseString(plan.toString().replace("\"" + TOWN + "\"", "\"" + town + "\""));
		round.add("plan", plan);
		round.addProperty("reason", a.has("summary") && a.get("summary").isJsonPrimitive() ? a.get("summary").getAsString() : "");
		JsonArray rounds = new JsonArray();
		rounds.add(round);
		ext.add(SIM_COMPOSE, rounds);
	}

	/**
	 * The request for {@code claim} in {@code level} (null: the Overworld), for {@code owner} (the owner Steward's own check uses): the card as brief and
	 * hints, no Style yet (it is made after the plan), no planning images, the cap.
	 */
	public static ComposeRequest request(String settlementId, String cardJson, @Nullable String land, BoundingBox claim, Map<String, BlockPos> anchors,
		@Nullable String player, @Nullable ServerLevel level, @Nullable String owner) {
		JsonObject card = JsonParser.parseString(cardJson).getAsJsonObject();
		String sha = cardSha(cardJson);
		return new ComposeRequest(brief(card), claim, null, false, cap(), opKey(settlementId, sha), ext(settlementId, sha, player, hints(card, land, anchors)), level, owner);
	}

	/** A waiting part's compose names the town as it stands by this stand-in only in the sim's scripted answers (the real hints give its region id). */
	public static final String TOWN = "$TOWN";
	/** A waiting part's compose: the settlement and the part, on the request's ext (never {@link #EXT_SETTLEMENT}, so it is not taken as the town's plan). */
	public static final String EXT_PART_OF = "steward:partOf", EXT_PART = "steward:part";

	/** Steward's rules for a waiting part (prompts/compose-part-rules.md's {@code ## Rules} section), sent as the caller's rules. */
	public static String partRules() {
		String md = resource("steward/prompts/compose-part-rules.md");
		int i = md.indexOf("## Rules");
		if (i < 0) throw new IllegalStateException("compose-part-rules.md has no '## Rules' section");
		return md.substring(i + "## Rules".length()).strip();
	}

	/**
	 * (COMPOSITION section 11, slice CA2) A waiting part's compose: the brief is the part (its label, kind and own notes), the hints are the part as kept,
	 * its block, the kept plan, the town's region id ({@code town}) and what stands beside it ({@code others}); Steward's part rules; no template pick.
	 * The key holds a hash of all of it and of {@code townState} (the town's sites and versions), so the same accept again adopts the same compose (paid
	 * once) and a changed town is a new compose.
	 */
	public static ComposeRequest partRequest(String world, String settlementId, String part, JsonObject partJson, String block, String keptPlan, String town,
		String townState, List<String> others, BoundingBox claim, Map<String, BlockPos> anchors, @Nullable ServerLevel level, @Nullable String owner) {
		JsonObject hints = new JsonObject();
		hints.add("part", partJson.deepCopy());
		hints.addProperty("block", block);
		hints.addProperty("townRegion", town);
		JsonArray o = new JsonArray();
		for (String x : others) o.add(x);
		hints.add("standingBeside", o);
		JsonObject kept;
		try {
			kept = JsonParser.parseString(keptPlan).getAsJsonObject();
		} catch (RuntimeException e) {
			kept = new JsonObject();
		}
		hints.add("keptPlan", kept);
		// the kept plan goes first when the hints are too long: the part, its block and the town's region always go
		if (hints.toString().length() > HINTS_MAX) hints.add("keptPlan", new JsonObject());
		String label = str(partJson, "label").isEmpty() ? part.replace('_', ' ') : str(partJson, "label");
		String notes = str(partJson, "notes");
		String brief = ("Add " + label + " (" + block + ") to the settlement as it stands, as its only part." + (notes.isEmpty() ? "" : " " + notes)).strip();
		if (brief.length() > BRIEF_MAX) brief = brief.substring(0, BRIEF_MAX - 1) + "…";
		JsonObject ext = common(hints, partRules());
		ext.addProperty(EXT_PART_OF, settlementId);
		ext.addProperty(EXT_PART, part);
		simRounds(ext, SimAnswers.PART, town);
		// the town's sites and versions too: a grown town keeps its region id (GPT review, CA2)
		String sha = cardSha(brief + "\n" + hints + "\n" + townState + "\n" + claim + "\n" + owner);
		return new ComposeRequest(brief, claim, null, false, Math.min(cap(), PART_BUDGET_USD), partKey(world + "." + settlementId, part, sha), ext, level, owner);
	}

	/** A waiting part's cap (Noah: about $1 a plan with repair): the rounds of one part's compose. */
	public static final double PART_BUDGET_USD = 1.00;

	/** A waiting part's compose key: {@code steward.part.<world>.<settlement>.<part>.<sha>}, {@code [A-Za-z0-9_.:-]{1,128}}. */
	public static String partKey(String worldAndSettlement, String part, String sha) {
		String w = worldAndSettlement.replaceAll("[^A-Za-z0-9_.:-]", "_");
		String p = part.replaceAll("[^A-Za-z0-9_.:-]", "_");
		if (p.length() > 32) p = p.substring(0, 32);
		if (w.length() > 128 - 12 - "steward.part...".length() - p.length()) w = w.substring(0, 128 - 12 - "steward.part...".length() - p.length());
		return "steward.part." + w + "." + p + "." + sha;
	}

	/** What a settlement design ended with. */
	public enum Kind {
		/** a plan/0 (COMPOSED: the last round READY; NO_PLAN: the best round, which still waits or needs repair) */
		PLAN,
		/**
		 * a bundled template fitted (no plan/0: {@code program}, {@code params}). Steward asks for no pick since CA2, but a compose asked before it (CA,
		 * no {@code skipPick}) can still end, or be reconciled, after the update: it is still read.
		 */
		PICKED,
		/** nothing to keep ({@code error} says why) */
		NONE
	}

	/**
	 * {@code outcome}: Architect's word (COMPOSED, NO_PLAN, PICKED, or the failure); {@code reason}: the player's summary, CP's {@code result.summary}
	 * (the shown round's own words, on COMPOSED and NO_PLAN, and Architect's own line for a compose it refused at once), else COMPOSED's {@code reason}
	 * (a design from before CP), else empty (NO_PLAN's {@code reason} is Architect's diagnostic, never shown); {@code rounds}: the composer's rounds (a $0
	 * street round included); {@code stopped}: why its repairs stopped early ({@code oscillation}, {@code nothing_to_repair}) or null; {@code leftOut}: the
	 * lots Architect's street round dropped, in words; {@code says}: each waiting part's player line ({@code waits[].say}) by part id; {@code freeRounds}:
	 * of the rounds, Architect's $0 street rounds (history rows with {@code free}).
	 */
	public record Outcome(Kind kind, String outcome, @Nullable JsonObject plan, String reason, double usd, int rounds, @Nullable String stopped,
		@Nullable String program, @Nullable JsonObject params, @Nullable String error, List<String> leftOut, Map<String, String> says, int freeRounds) {
		/** The paid repair rounds (after the first paid round). */
		public int repairs() {
			return Math.max(0, rounds - freeRounds - 1);
		}
	}

	/**
	 * Whether {@code d} is a compose's own design (kind SETTLEMENT): its template pick is a REGION design that carries the same ext, and ends first.
	 * Pure.
	 */
	public static boolean isCompose(Design d) {
		try {
			return d.kind() == Design.Kind.SETTLEMENT;
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** The settlement design's result read as Steward keeps it. Pure. */
	public static Outcome interpret(Design d) {
		double usd = d.cost() == null ? 0 : d.cost().usd();
		JsonObject r = d.result().orElse(null);
		String outcome = r != null ? str(r, "outcome") : "";
		int rounds = r != null && r.has("rounds") && r.get("rounds").isJsonPrimitive() ? r.get("rounds").getAsInt() : 0;
		String stopped = r != null && !str(r, "stopped").isEmpty() ? str(r, "stopped") : null;
		if (r != null && r.has("cost") && r.get("cost").isJsonPrimitive() && r.get("cost").getAsJsonPrimitive().isNumber()) usd = Math.max(usd, r.get("cost").getAsDouble());
		JsonObject plan = r != null && r.has("plan") && r.get("plan").isJsonObject() && r.getAsJsonObject("plan").size() > 0 ? r.getAsJsonObject("plan").deepCopy() : null;
		List<String> leftOut = new ArrayList<>();
		if (r != null && r.has("leftOut") && r.get("leftOut").isJsonArray()) for (JsonElement e : r.getAsJsonArray("leftOut")) if (e.isJsonPrimitive()) leftOut.add(e.getAsString());
		Map<String, String> says = new LinkedHashMap<>();
		if (r != null && r.has("waits") && r.get("waits").isJsonArray()) for (JsonElement e : r.getAsJsonArray("waits")) {
			if (e.isJsonObject() && !str(e.getAsJsonObject(), "part").isEmpty() && !str(e.getAsJsonObject(), "say").isBlank()) says.put(str(e.getAsJsonObject(), "part"), str(e.getAsJsonObject(), "say").strip());
		}
		int free = 0;
		if (r != null && r.has("history") && r.get("history").isJsonArray()) for (JsonElement e : r.getAsJsonArray("history")) if (e.isJsonObject() && e.getAsJsonObject().has("free")) free++;
		// CP's player summary; a design from before CP has it only as COMPOSED's reason
		String summary = !str(r, "summary").isBlank() ? str(r, "summary").strip() : "COMPOSED".equals(outcome) ? str(r, "reason") : "";
		if (d.status() == Design.Status.DONE && "PICKED".equals(outcome) && !str(r, "program").isEmpty()) {
			JsonObject params = r.has("params") && r.get("params").isJsonObject() ? r.getAsJsonObject("params").deepCopy() : new JsonObject();
			return new Outcome(Kind.PICKED, outcome, null, str(r, "reason"), usd, 0, null, str(r, "program"), params, null, List.of(), Map.of(), 0);
		}
		// a design stopped by its budget keeps its best round's plan in its result: still worth keeping (nothing more is spent)
		if (plan != null && (d.status() == Design.Status.DONE || d.status() == Design.Status.FAILED)) {
			return new Outcome(Kind.PLAN, outcome.isEmpty() ? "FAILED" : outcome, plan, summary, usd, rounds, stopped, null, null,
				d.status() == Design.Status.FAILED ? d.error().orElse("failed") : null, List.copyOf(leftOut), Map.copyOf(says), free);
		}
		String why = d.error().orElse(d.status() == Design.Status.DONE ? "no plan in the answer" : String.valueOf(d.status()).toLowerCase(Locale.ROOT));
		// (CP) a compose Architect refused at once (another level than the Overworld) says why in its summary, at $0
		return new Outcome(Kind.NONE, outcome, null, summary, usd, rounds, stopped, null, null, why, List.copyOf(leftOut), Map.copyOf(says), free);
	}

	/** (A compose from before CA2, see {@link Kind#PICKED}) A PICKED template as a one-zone plan/0 Steward can check and keep: {@code {zones: [{id: town, program: {template, params}}]}}. Pure. */
	public static JsonObject pickedPlan(String settlementSlug, String program, JsonObject params) {
		JsonObject plan = new JsonObject();
		plan.addProperty("schemaVersion", "plan/0");
		plan.addProperty("planId", settlementSlug.isEmpty() ? "town" : settlementSlug);
		JsonObject zone = new JsonObject();
		zone.addProperty("id", "town");
		zone.addProperty("required", true);
		JsonObject prog = new JsonObject();
		prog.addProperty("template", program);
		prog.add("params", params.deepCopy());
		zone.add("program", prog);
		JsonArray zones = new JsonArray();
		zones.add(zone);
		plan.add("zones", zones);
		return plan;
	}

	/**
	 * (Never drop) The card's buildings the plan has no lot for: each wanted type's shortfall ({@code count} minus the plan's lots of that type, every
	 * zone, district and wall lot counted), as "2 cottage homes" in the card's words; empty when every building has its lot. A plan whose zones use a
	 * template without lots (a bundled program's own) is not counted. Pure.
	 */
	public static List<String> missing(JsonObject card, JsonObject plan) {
		Map<String, Integer> have = new LinkedHashMap<>();
		boolean counted = lots(plan, have);
		List<String> out = new ArrayList<>();
		if (!counted) return out;
		Map<String, Integer> asked = new LinkedHashMap<>();
		Map<String, String> role = new LinkedHashMap<>();
		for (Wanted w : wanted(card)) {
			asked.merge(w.type(), w.count(), Integer::sum);
			role.putIfAbsent(w.type(), w.role());
		}
		for (var e : asked.entrySet()) {
			int short_ = e.getValue() - have.getOrDefault(e.getKey(), 0);
			if (short_ > 0) out.add(short_ + " " + (short_ == 1 && e.getValue() > 1 ? "of the " + role.get(e.getKey()) : role.get(e.getKey())));
		}
		return out;
	}

	/** Counts the plan's lots by type into {@code have}; false when a zone's program is a template with no lots (not countable). */
	private static boolean lots(JsonObject plan, Map<String, Integer> have) {
		boolean countable = true;
		// a card "building" the plan writes as another part (a stone bridge as connector kind bridge, a well as a form): counted by its kind
		// (paid proof 11-14: "Not in the plan: 1 stone bridge" for a bridge the plan held)
		for (String sec : List.of("connectors", "forms", "rules")) {
			if (!plan.has(sec) || !plan.get(sec).isJsonArray()) continue;
			for (JsonElement p : plan.getAsJsonArray(sec)) if (p.isJsonObject() && !str(p.getAsJsonObject(), "kind").isEmpty()) have.merge(slug(str(p.getAsJsonObject(), "kind")), 1, Integer::sum);
		}
		if (!plan.has("zones") || !plan.get("zones").isJsonArray()) return true;
		for (JsonElement z : plan.getAsJsonArray("zones")) {
			if (!z.isJsonObject() || !z.getAsJsonObject().has("program") || !z.getAsJsonObject().get("program").isJsonObject()) continue;
			JsonObject prog = z.getAsJsonObject().getAsJsonObject("program");
			JsonArray ls = prog.has("lots") && prog.get("lots").isJsonArray() ? prog.getAsJsonArray("lots")
				: prog.has("params") && prog.get("params").isJsonObject() && prog.getAsJsonObject("params").has("lots") && prog.getAsJsonObject("params").get("lots").isJsonArray()
					? prog.getAsJsonObject("params").getAsJsonArray("lots") : null;
			if (ls != null) count(ls, have);
			if (prog.has("districts") && prog.get("districts").isJsonArray()) for (JsonElement d : prog.getAsJsonArray("districts")) {
				if (d.isJsonObject() && d.getAsJsonObject().has("lots") && d.getAsJsonObject().get("lots").isJsonArray()) count(d.getAsJsonObject().getAsJsonArray("lots"), have);
			}
			if (ls == null && !prog.has("districts") && prog.has("template")) countable = false;
		}
		return countable;
	}

	private static void count(JsonArray lots, Map<String, Integer> have) {
		for (JsonElement l : lots) if (l.isJsonObject() && !str(l.getAsJsonObject(), "type").isEmpty()) have.merge(slug(str(l.getAsJsonObject(), "type")), 1, Integer::sum);
	}

	/**
	 * (Never drop) Whether the card asks for something hidden the plan must write as a {@code form.hidden_room} (a hidden or secret room, cellar,
	 * passage or hideout; a place behind a waterfall): Steward's rules say so, and a plan without one is told as a part not in the plan. Pure.
	 */
	public static boolean asksHidden(JsonObject card) {
		String t = card.toString().toLowerCase(Locale.ROOT);
		return tells(t) || t.matches("(?s).*\\b(hideouts?|hideaways?)\\b.*") || t.matches("(?s).*behind (a|the) (waterfall|falls)\\b.*")
			|| t.matches("(?s).*inside (a|the) (hill|mountain|cliff)\\b.*");
	}

	/** Whether {@code text} says hidden, secret or concealed (a word that gives a secret away, as Secrets' tells). Pure. */
	public static boolean tells(String text) {
		return text.toLowerCase(Locale.ROOT).matches("(?s).*\\b(hidden|secret|secretly|concealed)\\b.*");
	}

	/** Whether {@code text} asks for something hidden in words {@link #asksHidden} reads (a tell, a hideout, behind a waterfall, inside a hill). Pure. */
	public static boolean secretish(String text) {
		JsonObject o = new JsonObject();
		o.addProperty("t", text);
		return asksHidden(o);
	}

	/** Whether the plan writes a hidden room ({@code form.hidden_room}, a form of kind {@code hidden_room}). Pure. */
	public static boolean hasHiddenRoom(JsonObject plan) {
		if (!plan.has("forms") || !plan.get("forms").isJsonArray()) return false;
		for (JsonElement f : plan.getAsJsonArray("forms")) if (f.isJsonObject() && "hidden_room".equals(str(f.getAsJsonObject(), "kind"))) return true;
		return false;
	}

	static String slug(String s) {
		return s.toLowerCase(Locale.ROOT).strip().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
	}

	public static String str(@Nullable JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}

	private static String resource(String path) {
		try (InputStream in = ComposeJob.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("missing resource " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + path, e);
		}
	}
}
