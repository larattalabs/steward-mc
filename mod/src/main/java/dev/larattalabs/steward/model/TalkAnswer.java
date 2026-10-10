package dev.larattalabs.steward.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the steward made of something the player said (schema/steward-talk.schema.json, docs/PLAN.md phase 3b "conversation"): its reply, the intent, and
 * what the intent needs (the building asked for, the way and name of a district, the change). Read leniently: what does not fit its intent is dropped,
 * and an intent that misses what it needs reads as chat. Pure.
 */
public record TalkAnswer(String reply, Intent intent, ConceptCard.@Nullable Building building, @Nullable String way, @Nullable String district,
	@Nullable String changeTarget, @Nullable String changeRequest) {
	public enum Intent { CHAT, BUILDING, DISTRICT, CHANGE, UNDO, UNCLEAR }

	public static final int MAX_REPLY = 240;
	private static final Gson GSON = new Gson();
	private static final Set<String> WAYS = Set.of("N", "E", "S", "W");

	private record Shape(String reply, String intent, ConceptCard.Building building, String way, String district, Change change) {}

	private record Change(String target, String request) {}

	public static TalkAnswer parse(JsonObject json) {
		Shape s = GSON.fromJson(json, Shape.class);
		if (s == null || s.reply() == null || s.reply().isBlank()) throw new JsonParseException("an answer needs a reply");
		String reply = clip(s.reply().strip(), MAX_REPLY);
		Intent intent;
		try {
			intent = s.intent() == null ? Intent.UNCLEAR : Intent.valueOf(s.intent().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			intent = Intent.UNCLEAR;
		}
		ConceptCard.Building b = null;
		var sb = s.building();
		if (intent == Intent.BUILDING && sb != null && sb.role() != null && sb.type() != null && sb.footprint() != null) {
			var clean = ConceptCard.sanitize(List.of(s.building()));
			if (clean != null) {
				var x = clean.get(0);
				b = new ConceptCard.Building(clip(x.role(), 60), x.type(), 1, x.footprint(), x.landmark(), x.notes() == null ? null : clip(x.notes(), 200), x.placement());
			}
		}
		String way = s.way() != null && WAYS.contains(s.way().toUpperCase(java.util.Locale.ROOT)) ? s.way().toUpperCase(java.util.Locale.ROOT) : null;
		String district = s.district() == null || s.district().isBlank() ? null : clip(s.district().strip(), 40);
		String target = null, request = null;
		if (intent == Intent.CHANGE && s.change() != null && s.change().request() != null && !s.change().request().isBlank()) {
			target = s.change().target() == null || s.change().target().isBlank() ? "settlement" : clip(s.change().target().strip(), 60);
			request = clip(s.change().request().strip(), 240);
		}
		// an intent without what it needs is only talk
		if ((intent == Intent.BUILDING && b == null) || (intent == Intent.CHANGE && request == null)) intent = Intent.CHAT;
		return new TalkAnswer(reply, intent, b, intent == Intent.DISTRICT ? way : null, intent == Intent.DISTRICT || intent == Intent.BUILDING ? district : null,
			target, request);
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
	}
}
