package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardLimitsTest {
	private static ConceptCard card(String fixture) throws Exception {
		try (InputStream in = CardLimitsTest.class.getClassLoader().getResourceAsStream(fixture)) {
			return ConceptCard.parse(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("card"));
		}
	}

	@Test
	void theFixturesSayWhatWaits() throws Exception {
		List<String> stilts = CardLimits.of(card("stilt_village.json"));
		assertTrue(stilts.stream().anyMatch(l -> l.contains("over water")), stilts.toString());
		List<String> crater = CardLimits.of(card("crater_works.json"));
		assertTrue(crater.stream().anyMatch(l -> l.contains("custom forms")), crater.toString());
		assertTrue(crater.stream().noneMatch(l -> l.contains("cliffs")), "a custom form says it once");
		assertTrue(CardLimits.of(card("contradiction.json")).stream().anyMatch(l -> l.contains("custom forms")), "inside a volcano");
		// a meadow village: nothing waits
		var c = card("crater_works.json");
		var meadow = new ConceptCard(c.name(), new ConceptCard.Field("a meadow by a stream", "village", "flat", "M"), new ConceptCard.Field("timber cottages", null, null, null),
			new ConceptCard.Field("a quiet hamlet", null, null, null), new ConceptCard.Story(""), c.constraints(), c.avoid(), c.interpretation(), c.contradictions(),
			c.assumptions(), List.of(new ConceptCard.Building("cottage", "cottage", 3, "S", false, null)));
		assertEquals(List.of(), CardLimits.of(meadow));
	}

	@Test
	void aBigProgramIsToldItNeedsDistricts() throws Exception {
		var c = card("contradiction.json");
		var big = new ConceptCard(c.name(), c.site(), c.style(), c.purpose(), c.story(), c.constraints(), c.avoid(), c.interpretation(), c.contradictions(),
			c.assumptions(), List.of(new ConceptCard.Building("house", "house", 8, "S", false, null), new ConceptCard.Building("barn", "barn", 8, "M", false, null),
				new ConceptCard.Building("shop", "shop", 2, "S", false, null)));
		assertTrue(CardLimits.of(big).stream().anyMatch(l -> l.contains("districts")));
	}
}
