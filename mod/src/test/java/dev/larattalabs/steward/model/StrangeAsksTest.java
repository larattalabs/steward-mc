package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * "The nearest version, honestly" over the strange asks (fixtures/strange-asks, docs/PLAN.md "Strange asks"): each ask's words as a card's, read by
 * {@link CardLimits}. An ask that is not built today should get a line saying what waits; one built today should get none. The ask's own words stand in for
 * the card Claude would write (a paid recording of real cards is a later step), so this measures the limits' reach, and is held at its baseline.
 */
class StrangeAsksTest {
	/** Asks not built today whose words make the card say what waits (raise it as CardLimits learns; never lower it). */
	static final int HONEST_BASELINE = 6;

	private static JsonObject json(String name) throws Exception {
		try (InputStream in = StrangeAsksTest.class.getClassLoader().getResourceAsStream("strange-asks/" + name)) {
			assertNotNull(in, name);
			return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static ConceptCard cardOf(String words) {
		return new ConceptCard("Ask", new ConceptCard.Field(words, null, "find", "M"), new ConceptCard.Field("", null, null, null), new ConceptCard.Field("", null, null, null),
			new ConceptCard.Story(""), new ConceptCard.Constraints("here", "med", null, null), List.of(), "", List.of(), List.of(), List.of());
	}

	@Test
	void theCardSaysWhatWaitsForTheStrangeAsks() throws Exception {
		var caps = json("capabilities.json").getAsJsonObject("capabilities");
		Map<String, Integer> rank = Map.of("now", 0, "nearest", 1, "waits", 2);
		int notBuilt = 0, honest = 0;
		List<String> silent = new ArrayList<>(), falseAlarm = new ArrayList<>();
		for (var e : json("asks.json").getAsJsonArray("asks")) {
			var a = e.getAsJsonObject();
			int worst = 0;
			for (var n : a.getAsJsonArray("needs")) worst = Math.max(worst, rank.get(caps.getAsJsonObject(n.getAsString()).get("status").getAsString()));
			List<String> limits = CardLimits.of(cardOf(a.get("ask").getAsString()));
			String id = a.get("id").getAsString();
			if (worst == 0) {
				if (!limits.isEmpty()) falseAlarm.add(id + ": " + limits);
			} else {
				notBuilt++;
				if (!limits.isEmpty()) honest++;
				else silent.add(id);
			}
		}
		System.out.println("strange asks: " + honest + " of " + notBuilt + " not built today say what waits; silent: " + silent);
		assertEquals(List.of(), falseAlarm, "an ask built today gets no limit");
		assertTrue(honest >= HONEST_BASELINE, honest + " of " + notBuilt + " say what waits, below the baseline " + HONEST_BASELINE + "; silent: " + silent);
	}
}
