package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.service.Actions;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The card as Steward writes it (to the plan writer, a recorded fixture, a pending offer) is a card its schema accepts: absent fields left out, never null. */
class ConceptCardJsonTest {
	/** ring_city's card as the card writer wrote it on 2026-11-13: no size (written {@code null}), no template, no notes or budget. */
	private static final String RING_CITY = """
		{"name":"Crater Lake Ring City",
		 "site":{"text":"A ring city circling a crater lake, with every house facing the water","template":null,"terrain":"sculpt","size":null},
		 "style":{"text":"a warm medieval look in stone, timber and slate","template":"medieval"},
		 "purpose":{"text":"A residential lakeside town","template":"home_base"},
		 "story":{"text":""},
		 "constraints":{"near":"search","density":"med"},
		 "avoid":[],"interpretation":"A ring town on the rim of a crater lake.","contradictions":[],"assumptions":["Style was not given."],
		 "program":[{"role":"lakeside houses","type":"house","count":8,"footprint":"M","landmark":false},
		            {"role":"lake hall","type":"tavern","count":1,"footprint":"L","landmark":true,"notes":"On the rim.","placement":"near_water"}]}""";

	private static JsonObject schema() throws Exception {
		try (InputStream in = ConceptCardJsonTest.class.getClassLoader().getResourceAsStream("steward/schema/concept-card.schema.json")) {
			assertNotNull(in, "the card schema");
			return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	/** The schema's shape checks that matter here: no member it doesn't list, every required member present, null only where its type allows null. */
	private static void conforms(String at, JsonElement v, JsonObject s) {
		if (v.isJsonNull()) {
			var t = s.get("type");
			assertTrue(t != null && t.toString().contains("\"null\""), at + " is null, which the schema doesn't allow");
			return;
		}
		if (s.has("properties") && v.isJsonObject()) {
			var props = s.getAsJsonObject("properties");
			for (String k : v.getAsJsonObject().keySet()) {
				assertTrue(props.has(k), at + "." + k + " is not in the schema");
				conforms(at + "." + k, v.getAsJsonObject().get(k), props.getAsJsonObject(k));
			}
			if (s.has("required")) for (var r : s.getAsJsonArray("required")) assertTrue(v.getAsJsonObject().has(r.getAsString()), at + "." + r.getAsString() + " is missing");
		}
		if (s.has("items") && v.isJsonArray()) for (int i = 0; i < v.getAsJsonArray().size(); i++) conforms(at + "[" + i + "]", v.getAsJsonArray().get(i), s.getAsJsonObject("items"));
		if (s.has("enum")) assertTrue(s.getAsJsonArray("enum").contains(v), at + " = " + v + " is outside its enum");
	}

	@Test
	void ringCitysNullSizeIsLeftOut() throws Exception {
		ConceptCard c = ConceptCard.parse(JsonParser.parseString(RING_CITY).getAsJsonObject());
		assertNull(c.site().size());
		JsonObject out = JsonParser.parseString(Actions.cardJson(c)).getAsJsonObject();
		assertFalse(out.getAsJsonObject("site").has("size"), out.toString());
		assertTrue(out.getAsJsonObject("site").get("template").isJsonNull(), "a null template stays: the schema requires it");
		assertFalse(out.getAsJsonObject("style").has("terrain"), "style has no terrain or size");
		assertFalse(out.getAsJsonObject("constraints").has("budgetUsd"));
		assertFalse(out.getAsJsonArray("program").get(0).getAsJsonObject().has("notes"));
		conforms("card", out, schema());
	}

	@Test
	void aSizeOutsideTheEnumIsDroppedAndAGoodOneKept() throws Exception {
		JsonObject j = JsonParser.parseString(RING_CITY).getAsJsonObject();
		j.getAsJsonObject("site").addProperty("size", "huge");
		ConceptCard c = ConceptCard.parse(j);
		assertNull(c.site().size());
		conforms("card", JsonParser.parseString(Actions.cardJson(c)).getAsJsonObject(), schema());
		j.getAsJsonObject("site").addProperty("size", "L");
		assertEquals("L", ConceptCard.parse(j).site().size());
		assertEquals("L", JsonParser.parseString(Actions.cardJson(ConceptCard.parse(j))).getAsJsonObject().getAsJsonObject("site").get("size").getAsString());
	}

	@Test
	void theFixtureCardsWriteBackAsValidCards() throws Exception {
		for (String f : new String[] {"crater_works.json", "contradiction.json", "stilt_village.json", "sky_temple.json", "strange-asks/cards/ring_city.json"}) {
			try (InputStream in = ConceptCardJsonTest.class.getClassLoader().getResourceAsStream(f)) {
				var c = ConceptCard.parse(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("card"));
				conforms(f, JsonParser.parseString(Actions.cardJson(c)).getAsJsonObject(), schema());
			}
		}
	}
}
