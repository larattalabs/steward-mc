package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.layout.VillageLayout.LotSpec;
import dev.larattalabs.steward.model.ConceptCard;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProgramPlannerTest {
	private static JsonObject json(String fixture) {
		var in = ProgramPlannerTest.class.getClassLoader().getResourceAsStream(fixture + ".json");
		return JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card");
	}

	private static ConceptCard card(String fixture) {
		return ConceptCard.parse(json(fixture));
	}

	private static Map<String, Long> byType(List<LotSpec> specs) {
		return specs.stream().collect(Collectors.groupingBy(LotSpec::type, Collectors.counting()));
	}

	@Test
	void theWholeProgramIsBuiltWhenTheSizeMatches() {
		ConceptCard c = card("crater_works"); // keep, foundry (landmarks), crusher x2, mine head, barracks x4, shrine = 10
		assertEquals(10, ProgramPlanner.total(c));
		var r = ProgramPlanner.lots(c, 10, 2);
		assertTrue(r.fromCard());
		assertEquals(10, r.specs().size());
		assertEquals(List.of("overseers_keep_1", "slag_foundry_1"), List.copyOf(r.landmarkIds()));
		assertEquals(4L, byType(r.specs()).get("barracks"));
		assertTrue(r.leftOut().isEmpty(), r.leftOut().toString());
		assertFalse(r.specs().stream().anyMatch(s -> s.type().equals("tavern") || s.type().equals("chapel")), "a hellish lair gets no generic village buildings");
	}

	@Test
	void aSmallerSettlementKeepsLandmarksAndTheProgramsMix() {
		var r = ProgramPlanner.lots(card("crater_works"), 6, 2);
		assertEquals(6, r.specs().size());
		assertEquals(2, r.landmarkIds().size(), "landmarks are never dropped");
		// one of each ordinary entry before a second of any: crusher, mine head, barracks, shrine
		assertEquals(Map.of("overseers_keep", 1L, "slag_foundry", 1L, "ore_crusher", 1L, "mine_head", 1L, "barracks", 1L, "shrine", 1L), byType(r.specs()));
		assertTrue(r.leftOut().contains("ore crusher (1 of 2)"), r.leftOut().toString());
		assertTrue(r.leftOut().contains("worker barracks (3 of 4)"), r.leftOut().toString());
	}

	@Test
	void landmarksAreCappedAndTheRestBecomeOrdinary() {
		var r = ProgramPlanner.lots(card("crater_works"), 10, 1);
		assertEquals(List.of("overseers_keep_1"), List.copyOf(r.landmarkIds()));
		assertEquals(1L, byType(r.specs()).get("slag_foundry"), "the second landmark is still built, as an ordinary building");
	}

	@Test
	void aLargerSettlementRepeatsOrdinaryEntriesNotLandmarks() {
		var r = ProgramPlanner.lots(card("sky_temple"), 9, 1); // temple (landmark), monk cell x3, refectory, garden = 6
		assertEquals(9, r.specs().size());
		assertEquals(1L, byType(r.specs()).get("chapel"));
		assertEquals(r.specs().stream().map(LotSpec::id).distinct().count(), r.specs().size(), "ids are unique");
	}

	@Test
	void lotDepthIncludesTheApproachMarginAndIdsAreReadable() {
		var r = ProgramPlanner.lots(card("stilt_village"), 10, 1);
		LotSpec smokehouse = r.specs().get(0);
		assertEquals("smokehouse_1", smokehouse.id());
		assertTrue(smokehouse.landmark());
		assertEquals(ProgramPlanner.FOOTPRINT.get("L")[0], smokehouse.sizeX());
		assertEquals(ProgramPlanner.FOOTPRINT.get("L")[1] + LotBrief.APPROACH_MARGIN, smokehouse.sizeZ());
		for (LotSpec s : r.specs()) assertTrue(s.id().matches("[a-z][a-z0-9_]*"), s.id());
	}

	@Test
	void aCardWithoutAProgramFallsBackToTheGenericMixAndSaysSo() {
		JsonObject j = json("crater_works");
		j.remove("program");
		var r = ProgramPlanner.lots(ConceptCard.parse(j), 4, 1);
		assertFalse(r.fromCard());
		assertEquals("tavern", r.specs().get(0).type());
		assertEquals(18 + LotBrief.APPROACH_MARGIN, r.specs().get(0).sizeZ());
	}

	@Test
	void theParserClampsWhatStructuredOutputGotWrong() {
		JsonObject j = json("crater_works");
		var p = new com.google.gson.JsonArray();
		JsonObject b = new JsonObject();
		b.addProperty("role", " ");
		b.addProperty("type", "Bad Type!");
		b.addProperty("count", 40);
		b.addProperty("footprint", "XXL");
		b.addProperty("landmark", false);
		p.add(b);
		j.add("program", p);
		j.remove("avoid");
		ConceptCard c = ConceptCard.parse(j);
		ConceptCard.Building x = c.program().get(0);
		assertEquals("custom", x.type());
		assertEquals("custom", x.role());
		assertEquals(8, x.count());
		assertEquals("M", x.footprint());
		assertEquals(List.of(), c.avoid(), "a missing list reads as empty");
	}
}
