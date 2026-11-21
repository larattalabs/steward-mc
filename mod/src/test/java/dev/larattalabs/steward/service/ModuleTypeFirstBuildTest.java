package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.gateway.PPlan;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Progress;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.ProposalRules;
import dev.larattalabs.steward.model.TalkAnswer;
import dev.larattalabs.steward.module.ComposedModules;
import dev.larattalabs.steward.module.ModuleCatalog;
import dev.larattalabs.steward.module.ModuleKind;
import dev.larattalabs.steward.module.ModuleTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (slice MODULE-TYPE-2; Noah 2026-11-20, "Leave it out and say why (Recommended)") On a town's first whole build, a plan lot whose type is a module's,
 * when the catalog doesn't offer that module for this town, is left out of the first build with a reason in the steward's words; the rest builds. Plus
 * MODULE-TYPE's final-pass P2s and P3, and batch 090's caller-level tests over the live catalog (a catalog id overlapping an ordinary rule).
 */
class ModuleTypeFirstBuildTest {
	@AfterEach
	void reset() {
		ModuleOffers.testCatalog(null);
		SettlementsTestAccess.reset();
		Proposals.stopped();
	}

	static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	/** A catalog listing {@code ids} (no files: malformed entries, never offered, but listed, as the live catalog's ids are read). */
	static ModuleCatalog catalog(String... ids) {
		StringBuilder rows = new StringBuilder();
		for (String id : ids) rows.append(rows.isEmpty() ? "" : ",").append("{\"id\":\"").append(id).append("\",\"version\":1}");
		String index = "{\"format\":" + ModuleCatalog.FORMAT + ",\"modules\":[" + rows + "]}";
		return ModuleCatalog.load(rel -> rel.equals("catalog.json") ? Optional.of(index) : Optional.empty(), false);
	}

	static final String PLAN = """
		{"schemaVersion":"plan/0","zones":[{"id":"town","required":true,"program":{"lots":[
		  {"id":"cottage_1","type":"cottage","size":[13,14]},
		  {"id":"crop_farm_1","type":"crop_farm","label":"crop field","size":[13,14]},
		  {"id":"mill_1","type":"windmill","label":"windmill","size":[9,9]},
		  {"id":"smithy_1","type":"smithy","size":[11,11]}]}}]}""";

	static List<String> ids(JsonObject plan) {
		return PPlan.lots(plan).stream().map(l -> l.get("id").getAsString()).toList();
	}

	/** The first build's plan as built: a bare lot of any module's type, offered or not, is never kept as an ordinary (designed, quoted) lot. */
	@Test
	void aBareModuleLotTheCatalogDoesntOfferIsLeftOutOfTheFirstBuild() {
		var types = ModuleTypes.of(List.of(), List.of("windmill"));
		var asked = ComposedModules.readAll(PPlan.lots(json(PLAN)), types);
		assertEquals(List.of("crop_farm_1", "mill_1"), asked.stream().map(ComposedModules.Asked::lotId).toList());
		assertEquals(List.of("crop_farm", "windmill"), asked.stream().map(ComposedModules.Asked::moduleId).toList(), "read as the module, never 'unreadable'");
		JsonObject built = SettlementRunner.withModules(json(PLAN), Map.of(), types, Set.of());
		assertEquals(List.of("cottage_1", "smithy_1"), ids(built), "the design group, the count and the quote come from the kept lots");
		// the default reading is the live catalog's (here none: the kinds Steward knows, failing closed)
		assertEquals(List.of("cottage_1", "mill_1", "smithy_1"), ids(SettlementRunner.withModules(json(PLAN), Map.of())));
		// a marked lot never counts as laid out: left out as before
		JsonObject marked = json(PLAN.replace("\"type\":\"cottage\"", "\"type\":\"cottage\",\"ext\":{\"steward_mc:module\":{}}"));
		assertFalse(ids(SettlementRunner.withModules(marked, Map.of(), types, Set.of("cottage_1"))).contains("cottage_1"));
	}

	/** The live catalog's ids are module types (batch 090's P2: the collection itself, through its caller). */
	@Test
	void theLiveCatalogsIdsAreReadAsModuleTypes() {
		ModuleOffers.testCatalog(catalog("windmill"));
		assertEquals("windmill", ModuleTypes.moduleOf("windmill", ModuleOffers.moduleTypes()));
		assertEquals(List.of("cottage_1", "smithy_1"), ids(SettlementRunner.withModules(json(PLAN), Map.of())), "the windmill lot is the catalog's");
	}

	@Test
	void theStewardSaysWhyInItsOwnWordsOnce() {
		String said = ComposedModules.notOfferedWords("crop field", "crop_farm");
		assertEquals("The plan's crop field is left out of this first build: a crop farm comes as a working module from the catalog once the town qualifies,"
			+ " and I never design one as an ordinary building. The rest of the town builds.", said);
		assertFalse(said.contains("$"));
	}

	/** A build saved before this rule (format 7), whose bare crop_farm lot was laid out as an ordinary lot, loads and finishes as it began. */
	@Test
	void anOldBuildThatLaidTheLotOutKeepsIt(@TempDir Path dir) throws Exception {
		Path fixture = Path.of(System.getProperty("user.dir")).resolve("../fixtures/saves/v7/bare-module-lot-build.json").normalize();
		assertEquals(7, json(Files.readString(fixture)).get("format").getAsInt(), "saved before this slice");
		Path f = dir.resolve("steward-builds.json");
		Files.writeString(f, Files.readString(fixture));
		var back = BuildStore.load(f).all().get(0);
		Set<String> laidOut = new HashSet<>();
		for (var l : back.plan().lots()) laidOut.add(l.id());
		assertTrue(laidOut.contains("crop_farm_1"));
		JsonObject kept = json(PLAN.replace("cottage_1", "overseers_keep_1"));
		var types = ModuleTypes.KNOWN;
		assertTrue(ids(SettlementRunner.withModules(kept, Map.of(), types, laidOut)).contains("crop_farm_1"), "a stale re-check checks the plan it was approved with");
		assertFalse(ids(SettlementRunner.withModules(kept, Map.of(), types, Set.of())).contains("crop_farm_1"), "a new build leaves it out");
	}

	/** On the street path (the plan not taken), the card's building a bare unoffered module lot stands for is left out too, with the same words. */
	@Test
	void theStreetPathLeavesItOutWithTheSameWords() throws Exception {
		var card = StreetModulesTest.cardWith("""
			[{"role": "crop field", "type": "crop_farm", "count": 1, "footprint": "L", "landmark": false},
			 {"role": "cottage", "type": "cottage", "count": 2, "footprint": "S", "landmark": false}]""");
		var specs = dev.larattalabs.steward.gateway.ProgramPlanner.lots(card, 3, 0).specs();
		var r = StreetModules.leaveOut(json(PLAN), ModuleTypes.KNOWN, Set.of(), card, specs, "x", "on Steward's streets");
		assertEquals(1, r.removed());
		assertTrue(r.specs().stream().noneMatch(s -> s.type().equals("crop_farm")));
		assertEquals(List.of(ComposedModules.notOfferedWords("crop field", "crop_farm")), r.said());
	}

	/** (GPT review P1) A bare module lot with no "for" stands for the card building of its own label or role, whatever that building's type. */
	@Test
	void aBareLotStandsForTheCardBuildingOfItsRole() throws Exception {
		var card = StreetModulesTest.cardWith("""
			[{"role": "farm", "type": "farm", "count": 1, "footprint": "L", "landmark": false},
			 {"role": "cottage", "type": "cottage", "count": 2, "footprint": "S", "landmark": false}]""");
		JsonObject plan = json("""
			{"zones":[{"id":"town","program":{"lots":[{"id":"farm_1","type":"crop_farm","role":"farm","size":[11,12]},{"id":"cottage_1","type":"cottage","role":"cottage"}]}}]}""");
		var specs = dev.larattalabs.steward.gateway.ProgramPlanner.lots(card, 3, 0).specs();
		var r = StreetModules.leaveOut(plan, ModuleTypes.KNOWN, Set.of(), card, specs, "x", "on Steward's streets");
		assertEquals(1, r.removed(), "the farm the bare crop_farm lot was written for is never designed beside it");
		assertTrue(r.specs().stream().noneMatch(s -> s.type().equals("farm")));
		var left = List.of(new ProposalRules.Missing(0, card.program().get(0)));
		assertTrue(StreetModules.withoutModules(plan, ModuleTypes.KNOWN, card, left).isEmpty(), "nor offered later as a left-out building");
		// an ordinary building of another role stays
		assertEquals(2, r.specs().size());
	}

	@Test
	void anAskedModuleGoesToTheTownNotTheDistrictWithRoom() {
		assertNull(Talk.moduleIn(null, new ProposalRules.Where("d_east", false)));
		assertEquals("d_named", Talk.moduleIn("d_named", null));
	}

	// ------------------------------------------------------------------ MODULE-TYPE's final-pass P2s, P3, the wording

	@Test
	void aModuleAskedForIsLookedAtBeforeTheTownsRoom() {
		assertEquals(List.of(Talk.Route.MODULE, Talk.Route.DISTRICT_FIRST), Talk.route(true, true), "a full town never puts a district before the module");
		assertEquals(List.of(Talk.Route.MODULE, Talk.Route.HERE), Talk.route(true, false));
		assertEquals(List.of(Talk.Route.DISTRICT_FIRST), Talk.route(false, true));
		assertEquals(List.of(Talk.Route.HERE), Talk.route(false, false));
		// an explicit module with a decorative building type is a module ask
		var farm = TalkAnswer.parse(json("""
			{"reply": "A farm.", "intent": "building", "building": {"role": "farm", "type": "farm", "footprint": "L", "landmark": false},
			 "module": {"id": "crop_farm", "params": {}}}"""));
		assertNotNull(Talk.moduleAsked(farm, ModuleTypes.KNOWN));
	}

	@Test
	void theWorldStoppingForgetsWhoWasToldTheirLeftOutsAreModules() {
		Proposals.SAID_MODULES_LEFT.add("set_1");
		Proposals.stopped();
		assertTrue(Proposals.SAID_MODULES_LEFT.isEmpty(), "the next world's set_1 is told again");
	}

	static final ProposalRules.Signs SAPLINGS_AND_ANIMALS = new ProposalRules.Signs(Progress.Tier.WOOD, 0, 4, Map.of("minecraft:cow", 4));

	/** The rule selector skips a rule whose type the live catalog took, so the next rule that fires is offered (batch 090's caller-level P2). */
	@Test
	void aCatalogIdEqualToARulesTypeNeverStarvesTheLaterRules() {
		assertEquals(List.of("orchard"), keys(Proposals.fresh(SAPLINGS_AND_ANIMALS, Set.of(), Set.of(), List.of(), 0, 1, List.of())), "no such module: the orchard");
		ModuleOffers.testCatalog(catalog("orchard"));
		assertEquals(List.of("pens"), keys(Proposals.fresh(SAPLINGS_AND_ANIMALS, Set.of(), Set.of(), List.of(), 0, 1, List.of())), "the next rule, never nothing");
		assertEquals(List.of("pens"), keys(ProposalRules.propose(SAPLINGS_AND_ANIMALS, Set.of(), Set.of(), List.of(), 0, 1, List.of(),
			ModuleTypes.of(List.of(), List.of("orchard")))));
	}

	/** The decorative substitute is never a rule whose type the live catalog took (batch 090's caller-level P2). */
	@Test
	void theDecorativeSubstituteIsNeverAModulesType() throws Exception {
		var s = ModuleTypeTest.oldSave().get("set_2").orElseThrow();
		s = s.withProposals(new dev.larattalabs.steward.model.Settlement.Proposals(List.of(), List.of(), List.of(), 0));
		assertEquals("farm", ModuleOffers.substituteRule(s, ModuleKind.CropFarm.INSTANCE).key(), "no such module: the decorative farm");
		ModuleOffers.testCatalog(catalog("farm"));
		assertNull(ModuleOffers.substituteRule(s, ModuleKind.CropFarm.INSTANCE), "the catalog took the farm's type");
	}

	/** P3: the refusal says it left the inbox only when its withdrawal was saved. */
	@Test
	void theRefusalSaysTheTruthWhenTheWithdrawalWasntSaved() throws Exception {
		SettlementsTestAccess.use(ModuleTypeTest.oldSave());
		var s = Settlements.store().get("set_2").orElseThrow();
		var r = Actions.acceptProposal(null, s, "asked_mhx0a1_storage_hall", false);
		assertFalse(r.ok());
		assertFalse(r.message().contains("It leaves the inbox"), r.message());
		assertTrue(r.message().contains("still shows there"), r.message());
		assertTrue(Settlements.store().get("set_2").orElseThrow().proposals().accepted().stream().noneMatch(k -> k.equals("asked_mhx0a1_storage_hall")));
	}

	@Test
	void theTalkRefusalSaysOrdinaryBuildingOnce() {
		String said = Talk.plainLeftOut(new ConceptCard.Building("crop farm", "crop_farm", 1, "L", false, null), "one stands already", ModuleTypes.KNOWN);
		assertEquals(1, said.split("ordinary building", -1).length - 1, said);
	}

	/**
	 * (item 3, verified) A waiting part never carries a lot, so it never holds a bare module-type lot: only a connector, form or rule is offered and
	 * built alone (a zone needs a layout), its written plan must be that one part, and lots are read from zones only.
	 */
	@Test
	void aWaitingPartNeverHoldsALot() {
		assertFalse(PendingOffers.offerable("zone.town"));
		JsonObject wall = json("{\"connectors\":[{\"id\":\"town_wall\",\"kind\":\"wall\"}]}");
		assertNull(PendingOffers.outOfScope(wall, "town_wall", "connector.wall"));
		assertTrue(PPlan.lots(wall).isEmpty());
		JsonObject withLot = json("{\"connectors\":[{\"id\":\"town_wall\",\"kind\":\"wall\"}],\"zones\":[{\"id\":\"z\",\"program\":{\"lots\":[{\"id\":\"crop_farm_1\",\"type\":\"crop_farm\"}]}}]}");
		assertNotNull(PendingOffers.outOfScope(withLot, "town_wall", "connector.wall"), "a part plan holding a lot is refused before it is checked or built");
	}

	static List<String> keys(List<Proposal> ps) {
		return ps.stream().map(Proposal::key).toList();
	}
}
