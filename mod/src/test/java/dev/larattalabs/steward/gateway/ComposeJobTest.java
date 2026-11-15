package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.ComposeRequest;
import dev.larattalabs.architect.api.Cost;
import dev.larattalabs.architect.api.Design;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Slice CA: the compose request Steward sends (Architect 7c CO/AU), and how it reads the design's end. */
class ComposeJobTest {
	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static JsonObject fixture(String path) throws java.io.IOException {
		try (var in = ComposeJobTest.class.getClassLoader().getResourceAsStream(path)) {
			assertNotNull(in, path);
			return json(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	private static final Map<String, BlockPos> ANCHORS = Map.of("stone", new BlockPos(10, 64, -5), "player", new BlockPos(12, 65, -3));

	@AfterEach
	void clearSim() {
		SimAnswers.clear();
	}

	@Test
	void theRequestCarriesTheCardAsBriefAndHintsTheRulesTwoRepairsAndTheCap() throws Exception {
		JsonObject card = fixture("strange-asks/cards/hidden_village.json").getAsJsonObject("card");
		var claim = new BoundingBox(-40, -64, -40, 40, 320, 40);
		ComposeRequest r = ComposeJob.request("set_1", card.toString(), "north: plains 100%.", claim, ANCHORS, "00000000-0000-0000-0000-000000000001");
		assertTrue(r.brief().startsWith("Hidden Falls. A hidden village tucked"), r.brief());
		assertTrue(r.brief().length() <= ComposeJob.BRIEF_MAX);
		assertEquals(1.00, r.budgetUsd());
		assertFalse(r.images());
		assertNull(r.styleId(), "the style is made after the plan");
		assertTrue(r.opKey().matches("[A-Za-z0-9_.:-]{1,128}"), r.opKey());
		JsonObject ext = r.ext();
		assertEquals(2, ext.get(ComposeRequest.EXT_REPAIR_ROUNDS).getAsInt());
		String rules = ext.get(ComposeRequest.EXT_SYSTEM).getAsString();
		assertTrue(rules.length() < ComposeJob.SYSTEM_MAX, rules.length() + " characters");
		assertTrue(rules.contains("One lot per building") && rules.contains("form.hidden_room") && rules.contains("`reason` is for the player"));
		assertFalse(rules.contains("## Rules"), "the section's body only");
		JsonObject hints = ext.getAsJsonObject(ComposeRequest.EXT_HINTS);
		assertTrue(hints.toString().length() <= ComposeJob.HINTS_MAX);
		assertEquals("Hidden Falls", hints.getAsJsonObject("card").get("name").getAsString());
		assertEquals("[10,64,-5]", hints.getAsJsonObject("anchors").get("stone").toString());
		assertEquals("[12,65,-3]", hints.getAsJsonObject("anchors").get("player").toString());
		assertEquals("north: plains 100%.", hints.get("land").getAsString());
		assertEquals(6, hints.getAsJsonArray("wanted").size());
		assertEquals("set_1", ext.get(ComposeJob.EXT_SETTLEMENT).getAsString());
		assertEquals(ComposeJob.cardSha(card.toString()), ext.get(ComposeJob.EXT_CARD).getAsString());
		assertFalse(ext.has(ComposeJob.SIM_COMPOSE) || ext.has(ComposeJob.SIM_PICK), "no sim script unless one is set");
	}

	@Test
	void theSameSettlementAndCardGiveTheSameKeyAndAnotherCardAnother() {
		String a = ComposeJob.opKey("w1.set_1", ComposeJob.cardSha("{\"name\":\"A\"}"));
		assertEquals(a, ComposeJob.opKey("w1.set_1", ComposeJob.cardSha("{\"name\":\"A\"}")));
		assertNotEquals(a, ComposeJob.opKey("w1.set_1", ComposeJob.cardSha("{\"name\":\"B\"}")));
		assertNotEquals(a, ComposeJob.opKey("w2.set_1", ComposeJob.cardSha("{\"name\":\"A\"}")), "another world");
		assertTrue(ComposeJob.opKey("odd id/with spaces".repeat(10), "abc").matches("[A-Za-z0-9_.:-]{1,128}"));
	}

	@Test
	void anOversizedCardDropsItsLongTextBeforeItsBuildings() {
		JsonObject card = json("{\"name\":\"Big\",\"program\":[{\"type\":\"house\",\"count\":3,\"role\":\"homes\",\"notes\":\"" + "n".repeat(6000) + "\"}]}");
		card.addProperty("interpretation", "i".repeat(6000));
		card.add("assumptions", JsonParser.parseString("[\"" + "a".repeat(6000) + "\"]"));
		JsonObject h = ComposeJob.hints(card, null, ANCHORS);
		assertTrue(h.toString().length() <= ComposeJob.HINTS_MAX, h.toString().length() + "");
		assertEquals(1, h.getAsJsonArray("wanted").size());
		assertEquals(3, h.getAsJsonArray("wanted").get(0).getAsJsonObject().get("count").getAsInt());
		assertEquals("not surveyed", h.get("land").getAsString());
	}

	@Test
	void aScriptedPlanAnswerIsTheSimComposersRoundAndThePickFitsNothing() {
		SimAnswers.set(SimAnswers.PLAN, json("{\"plan\":{\"schemaVersion\":\"plan/0\",\"zones\":[]},\"waits\":[],\"summary\":\"A small town.\"}"));
		JsonObject ext = ComposeJob.ext("set_1", "abc", null, new JsonObject());
		var rounds = ext.getAsJsonArray(ComposeJob.SIM_COMPOSE);
		assertEquals(1, rounds.size());
		assertEquals("A small town.", rounds.get(0).getAsJsonObject().get("reason").getAsString());
		assertEquals("plan/0", rounds.get(0).getAsJsonObject().getAsJsonObject("plan").get("schemaVersion").getAsString());
		assertFalse(ext.getAsJsonArray(ComposeJob.SIM_PICK).get(0).getAsJsonObject().get("fits").getAsBoolean());
	}

	private static Design design(Design.Status status, String result, double usd, String error) {
		return new Design("d1", status, "", Optional.empty(), new Cost(usd, 0, 0, 0, 0, 0), Optional.ofNullable(error), new JsonObject(), Optional.empty(), 0, 0,
			Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(result == null ? null : json(result)));
	}

	@Test
	void onlyTheSettlementDesignIsTheComposesNotItsTemplatePick() {
		// stub e2e 11-14: the pick (a region design with the same ext) ended first and was read as a failed compose
		var settlement = new Design("d1", Design.Status.DONE, "", Optional.empty(), Cost.NONE, Optional.empty(), json("{\"kind\":\"settlement\"}"), Optional.empty(), 0, 0,
			Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
		var pick = new Design("d2", Design.Status.DONE, "", Optional.empty(), Cost.NONE, Optional.empty(), json("{\"type\":\"region.design\",\"kind\":\"region\"}"),
			Optional.empty(), 0, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
		assertTrue(ComposeJob.isCompose(settlement));
		assertFalse(ComposeJob.isCompose(pick));
	}

	@Test
	void theOutcomesAreReadAsSteward_keepsThem() {
		var composed = ComposeJob.interpret(design(Design.Status.DONE,
			"{\"outcome\":\"COMPOSED\",\"reason\":\"A village by the falls.\",\"plan\":{\"schemaVersion\":\"plan/0\"},\"rounds\":2,\"cost\":0.31}", 0.31, null));
		assertEquals(ComposeJob.Kind.PLAN, composed.kind());
		assertEquals("A village by the falls.", composed.reason());
		assertEquals(2, composed.rounds());
		assertEquals(0.31, composed.usd(), 1e-9);
		assertNull(composed.stopped());

		// NO_PLAN: the best round's plan is kept; its reason is Architect's diagnostic, never shown
		var noPlan = ComposeJob.interpret(design(Design.Status.DONE,
			"{\"outcome\":\"NO_PLAN\",\"reason\":\"no round's plan checked READY; the best is round 1\",\"plan\":{\"schemaVersion\":\"plan/0\"},\"rounds\":2,\"stopped\":\"oscillation\"}", 0.2, null));
		assertEquals(ComposeJob.Kind.PLAN, noPlan.kind());
		assertEquals("", noPlan.reason());
		assertEquals("oscillation", noPlan.stopped());

		// stopped by the cap with a best round: still kept, the error said
		var budget = ComposeJob.interpret(design(Design.Status.FAILED, "{\"outcome\":\"NO_PLAN\",\"plan\":{\"schemaVersion\":\"plan/0\"},\"rounds\":1}", 1.0, "budget"));
		assertEquals(ComposeJob.Kind.PLAN, budget.kind());
		assertEquals("budget", budget.error());

		var picked = ComposeJob.interpret(design(Design.Status.DONE, "{\"outcome\":\"PICKED\",\"program\":\"flat_village\",\"params\":{\"n\":3},\"reason\":\"flat land\"}", 0.02, null));
		assertEquals(ComposeJob.Kind.PICKED, picked.kind());
		JsonObject plan = ComposeJob.pickedPlan("hidden_falls", picked.program(), picked.params());
		assertEquals("flat_village", plan.getAsJsonArray("zones").get(0).getAsJsonObject().getAsJsonObject("program").get("template").getAsString());
		assertEquals(3, plan.getAsJsonArray("zones").get(0).getAsJsonObject().getAsJsonObject("program").getAsJsonObject("params").get("n").getAsInt());

		var failed = ComposeJob.interpret(design(Design.Status.FAILED, null, 0.05, "round 1 failed: x"));
		assertEquals(ComposeJob.Kind.NONE, failed.kind());
		assertEquals("round 1 failed: x", failed.error());
		assertEquals(0.05, failed.usd(), 1e-9, "a failed design's cost is still counted");
	}

	@Test
	void hiddenVillagesDroppedRoomIsFoundAndEveryBuildingCounted() throws Exception {
		JsonObject card = fixture("strange-asks/cards/hidden_village.json").getAsJsonObject("card");
		// the 11-13 recording: the waterfall and the ten lots, no hidden room (DEV.md)
		JsonObject plan = fixture("strange-asks/plans-2026-11-13/hidden_village-0.64.1.json").getAsJsonObject("answer").getAsJsonObject("plan");
		assertTrue(ComposeJob.asksHidden(card));
		assertFalse(ComposeJob.hasHiddenRoom(plan), "the room was dropped");
		assertEquals(List.of(), ComposeJob.missing(card, plan), "but every building has its lot");
		JsonObject withRoom = plan.deepCopy();
		if (!withRoom.has("forms")) withRoom.add("forms", new com.google.gson.JsonArray());
		withRoom.getAsJsonArray("forms").add(json("{\"id\":\"hollow\",\"kind\":\"hidden_room\",\"host\":{\"part\":\"falls\"},\"required\":true}"));
		assertTrue(ComposeJob.hasHiddenRoom(withRoom));
		assertFalse(ComposeJob.asksHidden(json("{\"name\":\"Plain\",\"site\":{\"text\":\"a farming village on a plain\"}}")));
	}

	@Test
	void aBuildingWithNoLotIsNamedByItsRole() {
		JsonObject card = json("{\"program\":[{\"type\":\"cottage\",\"count\":2,\"role\":\"cottage homes\"},{\"type\":\"tavern\",\"count\":1,\"role\":\"hearth tavern\"},"
			+ "{\"type\":\"cabin\",\"count\":4,\"role\":\"hidden dwellings\"}]}");
		JsonObject plan = json("{\"zones\":[{\"id\":\"town\",\"program\":{\"lots\":[{\"id\":\"cabin_1\",\"type\":\"cabin\"},{\"id\":\"cabin_2\",\"type\":\"cabin\"},"
			+ "{\"id\":\"cabin_3\",\"type\":\"cabin\"}],\"layout\":\"street\"}},{\"id\":\"q\",\"program\":{\"districts\":[{\"lots\":[{\"id\":\"cabin_4\",\"type\":\"Cabin\"}]}]}},"
			+ "{\"id\":\"v\",\"program\":{\"template\":\"flat_village\",\"params\":{\"lots\":[{\"id\":\"tavern_1\",\"type\":\"tavern\"}]}}}]}");
		assertEquals(List.of("2 cottage homes"), ComposeJob.missing(card, plan));
		// a bundled template's own buildings can't be counted against the card
		assertEquals(List.of(), ComposeJob.missing(card, ComposeJob.pickedPlan("x", "crater_works", new JsonObject())));
	}
}
