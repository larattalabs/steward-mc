package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.gateway.ProgramPlanner;
import dev.larattalabs.steward.gateway.VPlan;
import dev.larattalabs.steward.layout.VillageLayout.LotSpec;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.module.ComposedModules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * (slice M5-SCULPT, docs/MODULES.md B3) A kept plan that names a working module, on a build that doesn't take the plan: the module lot is left out of the
 * card's street layout and said, never designed as an ordinary building. Fixture 1 of M5's paid half (a carved river, a sorting storage hall).
 */
class StreetModulesTest {
	static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	/** Fixture 1's kept plan (composed_modules_real_1.json). */
	static JsonObject realPlan() throws Exception {
		return json(Files.readString(Path.of("..", "fixtures", "real", "composed_modules_real_1.json"))).getAsJsonObject("answer").getAsJsonObject("plan");
	}

	/** Fixture 1's card as the paid run saved it ("Steward M5 Paid 1"): its site and program on the stilt village's other fields. */
	static ConceptCard realCard() throws Exception {
		JsonObject c = json(Files.readString(Path.of("..", "fixtures", "stilt_village.json"))).getAsJsonObject("card");
		c.add("site", json("""
			{"text": "A market town on a river. The survey shows only level plains and little or no water nearby, so the river is not in the land and would have to be a carved channel.",
			 "template": "waterfront", "terrain": "sculpt", "size": "M"}"""));
		c.add("program", JsonParser.parseString("""
			[{"role": "storage hall", "type": "storage_hall", "count": 1, "footprint": "XL", "landmark": true, "notes": "Big hall that sorts the town's goods.", "placement": "central"},
			 {"role": "market square stalls", "type": "shop", "count": 3, "footprint": "S", "landmark": false},
			 {"role": "tavern", "type": "tavern", "count": 1, "footprint": "L", "landmark": false, "placement": "central"},
			 {"role": "river quay and boathouse", "type": "shop", "count": 1, "footprint": "M", "landmark": false, "placement": "near_water"},
			 {"role": "townhouses", "type": "house", "count": 4, "footprint": "S", "landmark": false},
			 {"role": "granary barn", "type": "barn", "count": 1, "footprint": "M", "landmark": false, "placement": "edge"}]""").getAsJsonArray());
		return ConceptCard.parse(c);
	}

	static List<ConceptCard.Building> buildings(List<dev.larattalabs.steward.model.ProposalRules.Missing> left) {
		return left.stream().map(dev.larattalabs.steward.model.ProposalRules.Missing::building).toList();
	}

	/** The stilt village's card with {@code program} as its program. */
	static ConceptCard cardWith(String program) throws Exception {
		JsonObject c = json(Files.readString(Path.of("..", "fixtures", "stilt_village.json"))).getAsJsonObject("card");
		c.add("program", JsonParser.parseString(program).getAsJsonArray());
		return ConceptCard.parse(c);
	}

	static final String HALL_WORDS = "The plan's storage hall is left out of the town: the land is shaped (a carved river), so the town is laid out on Steward's streets,"
		+ " where the working storage hall can't be placed yet. It isn't designed as an ordinary building in its place.";

	@Test
	void theRealSculptPlansHallIsLeftOutOfTheStreetAndSaidInCreativeAndSurvival() throws Exception {
		ConceptCard card = realCard();
		JsonObject plan = realPlan();
		// the fallback is taken in both modes: a sculpt card takes neither the plan at once nor from materials
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, card.site().terrain(), false, 64, 64), "creative: the street");
		assertFalse(VPlan.appliesSupplied("set_1", Difficulty.SUPPLIED, true, true, true, false, null, card.site().terrain(), false, 64, 64), "survival: the street");
		int total = ProgramPlanner.total(card);
		assertEquals(11, total);
		var program = ProgramPlanner.lots(card, total, 2);
		assertTrue(program.specs().stream().anyMatch(s -> s.type().equals("storage_hall")), "the card asks for the hall as an ordinary building");
		for (boolean survival : List.of(false, true)) {
			String why = StreetModules.why(card.site().terrain(), plan, true, false, true);
			// no catalog offered (the unit test's) and the catalog offered: a marked lot is a module ask either way
			for (Set<String> offered : List.of(Set.<String>of(), ComposedModules.LOT_TYPES)) {
				var r = StreetModules.leaveOut(plan, offered, card, program.specs(), why, "on Steward's streets");
				String mode = (survival ? "survival" : "creative") + ", offered " + offered;
				assertEquals(1, r.removed(), mode);
				assertEquals(total - 1, r.specs().size(), mode + ": the count excludes the hall");
				assertTrue(r.specs().stream().noneMatch(s -> s.type().equals("storage_hall")), mode + ": no ordinary design is asked for the hall");
				assertEquals(List.of(HALL_WORDS), r.said(), mode);
				assertFalse(r.said().get(0).contains("measured catalog"), "never the bound words: nothing is bound on the street");
				assertFalse(r.said().get(0).contains("from materials yet"), "the street's reason, not the materials guard's");
				// every other building the card asks for is still laid out, in the card's order
				List<LotSpec> rest = program.specs().stream().filter(s -> !s.type().equals("storage_hall")).toList();
				assertEquals(rest, r.specs(), mode);
			}
		}
		assertTrue(ComposedModules.MATERIALS_WAIT_FOR_LOT_CAP, "the survival guard stays on");
	}

	@Test
	void aPlanWithoutModulesOrNoPlanLeavesTheCardsLotsAsTheyAre() throws Exception {
		var specs = ProgramPlanner.lots(realCard(), 11, 2).specs();
		var none = StreetModules.leaveOut(null, Set.of(), null, specs, "x", "on Steward's streets");
		assertEquals(specs, none.specs());
		assertTrue(none.said().isEmpty());
		JsonObject plain = json("{\"zones\": [{\"id\": \"t\", \"program\": {\"lots\": [{\"id\": \"storage_hall_1\", \"type\": \"storage_hall\"}]}}]}");
		var r = StreetModules.leaveOut(plain, Set.of(), null, specs, "x", "on Steward's streets");
		assertEquals(specs, r.specs(), "a bare storage_hall lot while no hall is offered is an ordinary lot, as before M5");
		var offered = StreetModules.leaveOut(plain, ComposedModules.LOT_TYPES, null, specs, "x", "on Steward's streets");
		assertEquals(specs.size() - 1, offered.specs().size(), "while the hall is offered, a bare lot of its type is a module ask (B3)");
	}

	@Test
	void eachModuleLotStandsForOneBuildingOfItsTypeElseItsFor() {
		// the e2e's plan (fixtures/plans/composed_modules.json): a crop farm for "farm", a hall for "warehouse"
		JsonObject plan = json("""
			{"zones": [{"id": "town", "program": {"template": "flat_village", "params": {"lots": [
			  {"id": "farm_1", "type": "crop_farm", "label": "the village field", "ext": {"steward_mc:module": {"id": "crop_farm", "params": {}, "for": "farm"}}},
			  {"id": "warehouse_1", "type": "storage_hall", "label": "the storage hall", "ext": {"steward_mc:module": {"id": "storage_hall", "params": {}, "for": "barn"}}},
			  {"id": "cottage_1", "type": "cottage"}]}}}]}""");
		List<LotSpec> specs = List.of(new LotSpec("farm_1", "farm", 14, 12), new LotSpec("farm_2", "farm", 14, 12), new LotSpec("storage_hall_1", "storage_hall", 18, 15),
			new LotSpec("barn_1", "barn", 14, 12), new LotSpec("cottage_1", "cottage", 11, 9));
		var r = StreetModules.leaveOut(plan, Set.of(), null, specs, "the ground isn't flat enough for its plan", "on Steward's streets");
		assertEquals(List.of("farm_2", "barn_1", "cottage_1"), r.specs().stream().map(LotSpec::id).toList(),
			"one farm for the crop farm (its for), the hall by its own type before its for (the barn stays)");
		assertEquals(2, r.said().size());
		assertTrue(r.said().get(0).startsWith("The plan's the village field is left out of the town: the ground isn't flat enough for its plan, so the town is laid out on"
			+ " Steward's streets, where the working crop farm can't be placed yet."), r.said().get(0));
		// a module lot whose building isn't in this count's lots: nothing removed, still said
		var only = StreetModules.leaveOut(plan, Set.of(), null, List.of(new LotSpec("cottage_1", "cottage", 11, 9)), "x", "on Steward's streets");
		assertEquals(0, only.removed());
		assertEquals(2, only.said().size());
	}

	@Test
	void theReasonIsTheFirstThatHolds() throws Exception {
		JsonObject plan = realPlan();
		assertEquals("the land is shaped (a carved river)", StreetModules.why("sculpt", plan, false, true, false));
		assertEquals("the land is shaped", StreetModules.why("sculpt", json("{}"), true, false, true));
		assertEquals("the land is shaped (crater)", StreetModules.why("sculpt", json("{\"forms\": [{\"kind\": \"crater\"}]}"), true, false, true));
		assertEquals("the town isn't in the Overworld, where its plan is built", StreetModules.why("flat", plan, false, true, false));
		assertEquals("something already stands in the claim", StreetModules.why("find", plan, true, true, false));
		assertEquals("the ground isn't flat enough for its plan", StreetModules.why("find", plan, true, false, false));
		assertEquals("its plan isn't built in this world's mode", StreetModules.why("flat", plan, true, false, true));
	}

	@Test
	void theDistrictForLeftOutBuildingsNeverOffersAModuleLotsBuilding() throws Exception {
		ConceptCard card = realCard();
		// nothing of the card stands but two townhouses: every other building is left out, the hall among them
		var left = dev.larattalabs.steward.model.ProposalRules.leftOutEntries(card, java.util.Map.of("townhouses", 2), java.util.Map.of());
		assertTrue(left.stream().anyMatch(m -> m.building().type().equals("storage_hall")));
		var offered = StreetModules.withoutModules(realPlan(), Set.of(), card, left);
		assertTrue(offered.stream().noneMatch(b -> b.type().equals("storage_hall")), "the hall is the plan's module: never offered as an ordinary building");
		assertEquals(left.size() - 1, offered.size());
		assertEquals(buildings(left).stream().filter(b -> !b.type().equals("storage_hall")).toList(), offered);
		assertEquals(buildings(left), StreetModules.withoutModules(null, Set.of(), card, left), "no plan: as before");
	}

	@Test
	void aModuleLotTakesTheBuildingOfItsOwnRoleAndAnUnreadableMarkerKeepsItsFor() {
		// (GPT review) two barns: the module "granary barn" (for barn) takes the granary, never the cattle barn
		JsonObject granary = json("""
			{"zones": [{"program": {"lots": [{"id": "granary_1", "type": "storage_hall", "label": "granary barn",
			  "ext": {"steward_mc:module": {"id": "storage_hall", "for": "barn"}}}]}}]}""");
		var r = StreetModules.leaveOut(granary, Set.of(), null, List.of(new LotSpec("barn_1", "barn", 14, 12, "cattle barn", null, false, null),
			new LotSpec("barn_2", "barn", 14, 12, "granary barn", null, false, null)), "x", "on Steward's streets");
		assertEquals(List.of("barn_1"), r.specs().stream().map(LotSpec::id).toList());
		// (GPT review) a marker with no module id is unreadable (refused), and still stands for its for: the ordinary farm is not laid out
		JsonObject bad = json("""
			{"zones": [{"program": {"lots": [{"id": "field", "type": "crop_farm", "ext": {"steward_mc:module": {"for": "farm"}}}]}}]}""");
		var b = StreetModules.leaveOut(bad, Set.of(), null, List.of(new LotSpec("farm_1", "farm", 14, 12), new LotSpec("cottage_1", "cottage", 11, 9)), "x", "on Steward's streets");
		assertEquals(List.of("cottage_1"), b.specs().stream().map(LotSpec::id).toList());
		assertEquals(1, b.said().size());
	}

	@Test
	void theDistrictMatchesTheModuleAgainstTheWholeCard() throws Exception {
		// (GPT review P2) the hall stands as a module (lot id storage_hall_1): a missing barn is still offered, never taken for the hall's for
		JsonObject plan = json("""
			{"zones": [{"program": {"lots": [{"id": "storage_hall_1", "type": "storage_hall", "ext": {"steward_mc:module": {"id": "storage_hall", "for": "barn"}}}]}}]}""");
		ConceptCard card = realCard();
		var left = dev.larattalabs.steward.model.ProposalRules.leftOutEntries(card, java.util.Map.of(), java.util.Map.of("storage_hall", 1, "shop", 4, "tavern", 1, "house", 4));
		assertEquals(List.of("barn"), buildings(left).stream().map(ConceptCard.Building::type).toList());
		assertEquals(buildings(left), StreetModules.withoutModules(plan, Set.of(), card, left));
	}

	@Test
	void theLabelOutranksTheIdAndEachLeftOutEntryIsTakenOnce() throws Exception {
		// (GPT fix check) id barn_1 but label "granary barn": the granary is taken, not the earlier plain barn
		JsonObject plan = json("""
			{"zones": [{"program": {"lots": [{"id": "barn_1", "type": "storage_hall", "label": "granary barn", "role": "granary_barn",
			  "ext": {"steward_mc:module": {"id": "storage_hall", "for": "barn"}}}]}}]}""");
		var r = StreetModules.leaveOut(plan, Set.of(), null, List.of(new LotSpec("barn_1", "barn", 14, 12, "barn", null, false, null),
			new LotSpec("barn_2", "barn", 14, 12, "granary barn", null, false, null)), "x", "on Steward's streets");
		assertEquals(List.of("barn_1"), r.specs().stream().map(LotSpec::id).toList());
		// two card entries with the same role and type: one module takes one of them, the other is still offered
		JsonObject c = json(Files.readString(Path.of("..", "fixtures", "stilt_village.json"))).getAsJsonObject("card");
		c.add("program", JsonParser.parseString("""
			[{"role": "granary barn", "type": "barn", "count": 1, "footprint": "M", "landmark": false},
			 {"role": "granary barn", "type": "barn", "count": 1, "footprint": "L", "landmark": false, "notes": "the big one"},
			 {"role": "house", "type": "house", "count": 1, "footprint": "S", "landmark": false}]""").getAsJsonArray());
		ConceptCard card = ConceptCard.parse(c);
		var left = dev.larattalabs.steward.model.ProposalRules.leftOutEntries(card, java.util.Map.of("house", 1), java.util.Map.of());
		assertEquals(2, left.size());
		var offered = StreetModules.withoutModules(plan, Set.of(), card, left);
		assertEquals(1, offered.size());
		assertEquals("L", offered.get(0).footprint());
	}

	@Test
	void aModuleWhoseCardEntryTheCountDidNotSelectRemovesNothing() throws Exception {
		// (batch-076 review P2-4) the module is the "granary barn"; this build's count selected only the "cattle barn" (the first entry): the cattle
		// barn is an ordinary building that only shares the type, and stays
		ConceptCard card = cardWith("""
			[{"role": "cattle barn", "type": "barn", "count": 1, "footprint": "M", "landmark": false},
			 {"role": "granary barn", "type": "barn", "count": 1, "footprint": "M", "landmark": false, "notes": "Keeps the grain"}]""");
		var specs = ProgramPlanner.lots(card, 1, 0).specs();
		assertEquals(List.of("cattle barn"), specs.stream().map(LotSpec::role).toList());
		JsonObject granary = json("""
			{"zones": [{"program": {"lots": [{"id": "granary_1", "type": "storage_hall", "label": "granary barn",
			  "ext": {"steward_mc:module": {"id": "storage_hall", "for": "barn"}}}]}}]}""");
		var r = StreetModules.leaveOut(granary, Set.of(), card, specs, "x", "on Steward's streets");
		assertEquals(0, r.removed());
		assertEquals(specs, r.specs(), "the cattle barn is laid out");
		assertEquals(1, r.said().size(), "the module lot is still said as left out");
		// both selected: the granary goes, the cattle barn stays
		var both = StreetModules.leaveOut(granary, Set.of(), card, ProgramPlanner.lots(card, 2, 0).specs(), "x", "on Steward's streets");
		assertEquals(List.of("cattle barn"), both.specs().stream().map(LotSpec::role).toList());
		assertEquals(1, both.removed());
	}

	@Test
	void duplicateEntriesSubtractTheModuleFromItsOwnEntry() throws Exception {
		// (batch-076 review P2-5) two entries of one role and type, counts 1 and 2; the module stands for the first; one barn already stands: the first
		// is whole (its one building the module), the second still misses one, and that one is offered
		ConceptCard card = cardWith("""
			[{"role": "barn", "type": "barn", "count": 1, "footprint": "M", "landmark": false},
			 {"role": "barn", "type": "barn", "count": 2, "footprint": "L", "landmark": false, "notes": "the big ones"},
			 {"role": "house", "type": "house", "count": 1, "footprint": "S", "landmark": false}]""");
		JsonObject plan = json("""
			{"zones": [{"program": {"lots": [{"id": "barn_1", "type": "storage_hall", "label": "barn",
			  "ext": {"steward_mc:module": {"id": "storage_hall", "for": "barn"}}}]}}]}""");
		var left = dev.larattalabs.steward.model.ProposalRules.leftOutEntries(card, java.util.Map.of("barn", 1, "house", 1), java.util.Map.of());
		assertEquals(1, left.size());
		assertEquals(1, left.get(0).entry(), "the second entry's missing barn");
		var offered = StreetModules.withoutModules(plan, Set.of(), card, left);
		assertEquals(1, offered.size(), "the missing barn is not taken for the module of the first entry");
		assertEquals("the big ones", offered.get(0).notes());
		assertEquals(1, offered.get(0).count());
	}

	@Test
	void anOrdinaryEntryNeverTakesTheLandmarkLotOfAnEntryAlikeButForIt() throws Exception {
		// (batch-076 fix-check P2) an ordinary barn first, a landmark barn second, otherwise alike: the module stands for the first (the ordinary one),
		// and the layout puts the landmark first: the ordinary lot goes, the landmark stays
		ConceptCard card = cardWith("""
			[{"role": "barn", "type": "barn", "count": 1, "footprint": "M", "landmark": false},
			 {"role": "barn", "type": "barn", "count": 1, "footprint": "L", "landmark": true}]""");
		var specs = ProgramPlanner.lots(card, 2, 1).specs();
		assertTrue(specs.get(0).landmark());
		JsonObject plan = json("""
			{"zones": [{"program": {"lots": [{"id": "barn_1", "type": "storage_hall", "label": "barn",
			  "ext": {"steward_mc:module": {"id": "storage_hall", "for": "barn"}}}]}}]}""");
		var r = StreetModules.leaveOut(plan, Set.of(), card, specs, "x", "on Steward's streets");
		assertEquals(1, r.removed());
		assertEquals(List.of(specs.get(0)), r.specs(), "the landmark barn stays");
	}
}
