package dev.larattalabs.steward.gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.larattalabs.architect.api.Cost;
import dev.larattalabs.architect.api.Job;
import dev.larattalabs.steward.model.ConceptCard;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** What a finished concept-card job means: a parsed card, or a reason it failed. Pure (no Minecraft calls). */
public record CardResult(@Nullable ConceptCard card, @Nullable String error, Cost cost) {
	public boolean ok() {
		return card != null;
	}

	public static CardResult interpret(Job job) {
		if (!"done".equals(job.status())) {
			String why = job.error().orElse(job.status());
			return new CardResult(null, "budget".equals(why) ? "the concept-card job hit its budget" : "the concept-card job did not finish: " + why, job.cost());
		}
		JsonElement r = job.result().orElse(null);
		if (r == null || !r.isJsonObject()) return new CardResult(null, "the concept-card job returned no card", job.cost());
		try {
			return new CardResult(ConceptCard.parse((JsonObject) r), null, job.cost());
		} catch (JsonParseException | IllegalStateException | ClassCastException e) {
			return new CardResult(null, "the concept card could not be read: " + e.getMessage(), job.cost());
		}
	}

	/** The card as chat lines for the player (the real card UI comes later): what Claude took each field to mean. */
	public static List<String> lines(ConceptCard c, Cost cost) {
		List<String> l = new ArrayList<>();
		l.add((c.name() == null || c.name().isBlank() ? "Settlement" : c.name()) + " (concept card)");
		l.add("Site: " + c.site().text() + templ(c.site().template()) + ", terrain " + c.site().terrain() + (c.site().size() == null ? "" : ", size " + c.site().size()));
		l.add("Style: " + c.style().text() + templ(c.style().template()));
		l.add("Purpose: " + c.purpose().text() + templ(c.purpose().template()));
		if (c.story() != null && c.story().text() != null && !c.story().text().isBlank()) l.add("Story: " + c.story().text());
		if (c.hasProgram()) l.add("Builds (" + ProgramPlanner.total(c) + "): " + programLine(c));
		if (!c.avoid().isEmpty()) l.add("Avoid: " + String.join(", ", c.avoid()));
		l.add(c.interpretation());
		for (ConceptCard.Contradiction x : c.contradictions()) l.add("Tension: " + x.issue() + " -> " + x.resolution());
		for (String a : c.assumptions()) l.add("Assumed: " + a);
		l.add(String.format("Cost: $%.3f", cost.usd()));
		return l;
	}

	/** "overseer's keep (landmark, L), ore crusher x2, worker barracks x4, ...". */
	static String programLine(ConceptCard c) {
		List<String> parts = new ArrayList<>();
		for (ConceptCard.Building b : c.program()) {
			String tags = b.landmark() ? " (landmark, " + b.footprint() + ")" : "";
			parts.add(b.role() + (b.count() > 1 ? " x" + b.count() : "") + tags);
		}
		return String.join(", ", parts);
	}

	private static String templ(@Nullable String t) {
		return t == null ? " (custom)" : " [" + t + "]";
	}
}
