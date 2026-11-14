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

	private static ConceptCard site(String words, String template, String terrain) throws Exception {
		var c = card("crater_works.json");
		return new ConceptCard(c.name(), new ConceptCard.Field(words, template, terrain, "M"), new ConceptCard.Field("timber cottages", null, null, null),
			new ConceptCard.Field("a quiet hamlet", null, null, null), new ConceptCard.Story(""), c.constraints(), c.avoid(), c.interpretation(), c.contradictions(),
			c.assumptions(), List.of(new ConceptCard.Building("cottage", "cottage", 3, "S", false, null)));
	}

	@Test
	void theFixturesSayWhatWaits() throws Exception {
		List<String> stilts = CardLimits.of(card("stilt_village.json"));
		assertTrue(stilts.stream().anyMatch(l -> l.startsWith("Buildings over water")), stilts.toString());
		// a crater is built since Architect 0.63.0 (BF): only the mining facility's mines wait
		List<String> crater = CardLimits.of(card("crater_works.json"));
		assertEquals(List.of("Working farms come as irrigated fields, farm ponds, channels and ditches; mines and rails aren't built yet."), crater);
		assertTrue(CardLimits.of(card("contradiction.json")).stream().anyMatch(l -> l.contains("carved deep into rock")), "inside a volcano");
		assertEquals(List.of(), CardLimits.of(site("a meadow by a stream", "village", "flat")), "a meadow village: nothing waits");
	}

	@Test
	void whatArchitectBuildsSince7cGetsNoLine() throws Exception {
		assertEquals(List.of(), CardLimits.of(card("sky_temple.json")), "a floating sky temple");
		for (String[] s : new String[][] {{"a ring city circling a crater lake, every house facing the water", null}, {"a giant meteor crater", "crater"},
			{"a rift through the plateau", "rift"}, {"a floating island in the sky", "island"}, {"a cliffside town with switchback streets", "cliff_face"},
			{"a hollowed mountain", null}}) {
			assertEquals(List.of(), CardLimits.of(site(s[0], s[1], "sculpt")), s[0]);
		}
	}

	@Test
	void aLongStairToAFloatingIslandWaits() throws Exception {
		List<String> sky = CardLimits.of(site("a floating island, reached by a long stair", "island", "sculpt"));
		assertEquals(1, sky.size(), sky.toString());
		assertTrue(sky.get(0).contains("long stair up to a floating island"));
		// a long stair up a hill is built
		assertEquals(List.of(), CardLimits.of(site("a hilltop chapel reached by a long stair", "hilltop", "find")));
	}

	@Test
	void everyRuleIsARowOfTheGapMapThatIsNotBuilt() throws Exception {
		try (InputStream in = CardLimitsTest.class.getClassLoader().getResourceAsStream("strange-asks/capabilities.json")) {
			var caps = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("capabilities");
			for (var r : CardLimits.RULES) {
				assertTrue(caps.has(r.capability()), r.capability());
				assertNotEquals("now", caps.getAsJsonObject(r.capability()).get("status").getAsString(), r.capability() + " is built: its line would be false");
			}
		}
	}

	@Test
	void noLineNamesAHiddenRoom() {
		for (var r : CardLimits.RULES) assertFalse(r.line().toLowerCase(java.util.Locale.ROOT).matches(".*\\b(hidden|secret|concealed)\\b.*"), r.line());
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
