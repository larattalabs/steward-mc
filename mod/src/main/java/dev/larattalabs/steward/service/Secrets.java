package dev.larattalabs.steward.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A plan's secrets (Architect 7c HR, S-7c-4; Steward's promise): its hidden rooms ({@code form.hidden_room}) and every part in the artifact's
 * {@code $.secrets[]}. Steward never names one to the player, on a screen, in chat, in the inbox or in the history: each place that shows a plan part
 * asks {@link #hides} first, and Claude's own words about the plan (its summary, what waits, a lot's brief) pass through {@link #scrub}, which drops
 * every sentence naming a secret, or saying "hidden" or "secret". The steward may hint ({@link #HINT}). Where a secret is (the entrance, the room's
 * box) is never read here: Steward shows no coordinates of it. Pure.
 */
public final class Secrets {
	/** What the steward may say of a plan with a secret built: there's more to it, never what or where. */
	public static final String HINT = "There's more to it than it looks.";
	/** No secrets: a plan without a hidden room (every plan before 7c). */
	public static final Secrets NONE = new Secrets(Set.of(), List.of());
	/** Words that give a secret away whatever it is called. */
	private static final List<String> TELLS = List.of("hidden", "secret", "secretly", "concealed");
	/** Words of a label too common to give anything away ("the", "room" alone would drop every sentence about a building). */
	private static final Set<String> COMMON = Set.of("the", "and", "with", "under", "behind", "room", "rooms", "little", "small", "large", "old", "new", "its",
		"their", "from", "into", "beneath", "below", "door");
	private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?;])\\s+");

	private final Set<String> parts;
	/** Lower-case words and phrases that name a secret (its id in words, its label, the label's own words), and the tells. */
	private final List<Pattern> names;

	private Secrets(Set<String> parts, List<String> words) {
		this.parts = Set.copyOf(parts);
		List<Pattern> p = new ArrayList<>();
		for (String w : words) p.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
		this.names = List.copyOf(p);
	}

	/** The secrets of a kept plan (as JSON); none when it can't be read. */
	public static Secrets of(@Nullable String planJson) {
		if (planJson == null || planJson.isBlank()) return NONE;
		try {
			JsonElement e = JsonParser.parseString(planJson);
			return e.isJsonObject() ? of(e.getAsJsonObject(), null) : NONE;
		} catch (RuntimeException ex) {
			return NONE;
		}
	}

	/** The secrets of the settlement's kept plan. */
	public static Secrets of(PlanStore.@Nullable Entry entry) {
		return entry == null ? NONE : of(entry.plan());
	}

	/** The secrets of the settlement {@code settlementId}'s kept plan. */
	public static Secrets ofSettlement(String settlementId) {
		return of(PlanStore.get(settlementId).orElse(null));
	}

	/**
	 * The secrets of {@code plan}: its {@code form.hidden_room} parts, and (with the artifact's body) each part its {@code $.secrets[]} names. With none,
	 * {@link #NONE}: nothing is scrubbed, so a plan without a hidden room reads as it always has.
	 */
	public static Secrets of(JsonObject plan, @Nullable JsonObject artifactBody) {
		Set<String> ids = new LinkedHashSet<>();
		List<String> words = new ArrayList<>();
		for (String list : List.of("zones", "connectors", "forms", "rules")) {
			if (!plan.has(list) || !plan.get(list).isJsonArray()) continue;
			for (JsonElement e : plan.getAsJsonArray(list)) {
				if (!e.isJsonObject()) continue;
				JsonObject p = e.getAsJsonObject();
				if (!"hidden_room".equals(str(p, "kind")) || str(p, "id").isEmpty()) continue;
				add(ids, words, p);
			}
		}
		if (artifactBody != null && artifactBody.has("secrets") && artifactBody.get("secrets").isJsonArray()) {
			for (JsonElement e : artifactBody.getAsJsonArray("secrets")) {
				if (!e.isJsonObject() || str(e.getAsJsonObject(), "part").isEmpty()) continue;
				String id = str(e.getAsJsonObject(), "part");
				if (ids.contains(id)) continue;
				JsonObject p = PendingOffers.partOf(plan, id).orElseGet(() -> {
					JsonObject o = new JsonObject();
					o.addProperty("id", id);
					return o;
				});
				add(ids, words, p);
			}
		}
		if (ids.isEmpty()) return NONE;
		words.addAll(TELLS);
		return new Secrets(ids, words.stream().distinct().toList());
	}

	private static void add(Set<String> ids, List<String> words, JsonObject p) {
		String id = str(p, "id");
		ids.add(id);
		words.add(id.toLowerCase(Locale.ROOT));
		words.add(id.replace('_', ' ').toLowerCase(Locale.ROOT));
		String label = str(p, "label").strip().toLowerCase(Locale.ROOT).replaceFirst("^(the|a|an) ", "");
		if (!label.isEmpty()) words.add(label);
		for (String w : (id.replace('_', ' ') + " " + label).split("[^\\p{L}\\p{N}']+")) {
			String t = w.replaceAll("'s$", "");
			if (t.length() >= 4 && !COMMON.contains(t) && !t.matches("\\d+")) words.add(t);
		}
	}

	public boolean any() {
		return !parts.isEmpty();
	}

	/** The plan's secret parts, by id. */
	public Set<String> parts() {
		return parts;
	}

	/**
	 * Whether {@code part} is a secret: a plan part's id, a region part of one (Architect writes {@code f_<id>} and {@code f_<id>_door}), its survival
	 * group ({@code <id>:entrance}) or its revision ({@code f_<id>@2}).
	 */
	public boolean hides(@Nullable String part) {
		if (part == null || parts.isEmpty()) return false;
		String p = part.replaceFirst("@\\d+$", "");
		int colon = p.indexOf(':');
		if (colon > 0) p = p.substring(0, colon);
		if (parts.contains(p)) return true;
		if (p.startsWith("f_")) {
			String q = p.substring(2);
			if (parts.contains(q)) return true;
			if (q.endsWith("_door") && parts.contains(q.substring(0, q.length() - 5))) return true;
		}
		return false;
	}

	/** Whether {@code text} names a secret, or says "hidden" or "secret" (only of a plan that has one). */
	public boolean names(@Nullable String text) {
		if (text == null || names.isEmpty()) return false;
		for (Pattern p : names) if (p.matcher(text).find()) return true;
		return false;
	}

	/**
	 * {@code text} without the sentences that name a secret (Claude's words about the plan); the text as it is when the plan has none. A text that is all
	 * secret comes back empty.
	 */
	public String scrub(@Nullable String text) {
		if (text == null) return "";
		if (names.isEmpty() || !names(text)) return text;
		List<String> kept = new ArrayList<>();
		for (String s : SENTENCE.split(text.strip())) if (!names(s)) kept.add(s);
		return String.join(" ", kept);
	}

	/** {@code lines} without those that name a secret. */
	public List<String> keep(List<String> lines) {
		if (names.isEmpty()) return lines;
		return lines.stream().filter(l -> !names(l)).toList();
	}

	/** A copy of {@code plan} whose lots' briefs leave out every sentence naming a secret (what the designs are asked to build); the plan itself without any. */
	public JsonObject scrubBriefs(JsonObject plan) {
		if (!any()) return plan;
		JsonObject out = plan.deepCopy();
		for (JsonObject lot : dev.larattalabs.steward.gateway.PPlan.lots(out)) {
			if (lot.has("brief") && lot.get("brief").isJsonPrimitive()) lot.addProperty("brief", scrub(lot.get("brief").getAsString()));
		}
		return out;
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}
}
