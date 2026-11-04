package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanWriterJobTest {
	/**
	 * Architect's structured jobs refuse a system prompt over 120,000 characters (job.system, C33, API 1.25; Steward needs 1.26). The schema usually goes
	 * by reference (systemRefs); inline is the fallback for a helper without it, and grows with each block.
	 */
	static final int ARCHITECT_SYSTEM_LIMIT = 120_000;

	@Test
	void theSystemPromptFitsArchitectsLimitWithRoomToGrow() {
		String system = PlanWriterJob.systemPrompt();
		assertTrue(system.contains("\"schemaVersion\""), "the schema is in it");
		assertTrue(system.length() < ARCHITECT_SYSTEM_LIMIT - 4_000, system.length() + " characters: near Architect's limit of " + ARCHITECT_SYSTEM_LIMIT);
	}

	@Test
	void theUserPromptCarriesTheCardTheLandAndTheAnchors() {
		String u = PlanWriterJob.userPrompt("{\"name\":\"X\"}", "north: plains 100%.", List.of("stone", "player"));
		assertTrue(u.contains("{\"name\":\"X\"}") && u.contains("north: plains 100%.") && u.contains("stone, player"));
		assertTrue(PlanWriterJob.userPrompt("{}", null, List.of("stone")).contains("not surveyed"));
	}

	@Test
	void theUserPromptListsThePlayersBuildsOnlyWhenThereAreAny() {
		String line = "- build-3: Noah's house, 9x11 and 7 high, 12 south and 4 east of the stone, a door facing north";
		String u = PlanWriterJob.userPrompt("{}", null, List.of("stone"), List.of(line));
		assertTrue(u.contains(line) && u.contains("{build: id}"));
		assertFalse(PlanWriterJob.userPrompt("{}", null, List.of("stone")).contains("own builds"));
	}

	@Test
	void withSystemRefsTheSchemaGoesByReference() {
		var spec = PlanWriterJob.build("{}", null, List.of("stone"), List.of(), null, null, null, true);
		assertEquals(List.of("plan/0#compact", "vocab/1#compact"), spec.systemRefs());
		assertFalse(spec.system().contains("\"schemaVersion\""), "the schema is not sent inline");
		assertTrue(PlanWriterJob.build("{}", null, List.of("stone"), List.of(), null, null, null, false).system().contains("\"schemaVersion\""));
	}

	@Test
	void everyGatehouseAWallNamesGetsAPortcullisUnlessItCantHoldOneOrThePlanSaysOtherwise() {
		// (Noah, 2026-11-03; Architect GM-a2) a wall without lots: the lenient params.mechanism, which a small gatehouse answers with a plain passage
		var lenient = com.google.gson.JsonParser.parseString("{\"zones\":[{\"id\":\"town\",\"program\":{\"lots\":[{\"id\":\"gatehouse_1\",\"type\":\"gatehouse\",\"size\":[9,9]}]}}],"
			+ "\"connectors\":[{\"id\":\"w\",\"kind\":\"wall\",\"params\":{\"gate_lot\":\"gatehouse_1\"}},{\"id\":\"plain\",\"kind\":\"wall\"},"
			+ "{\"id\":\"kept\",\"kind\":\"wall\",\"params\":{\"gate_lot\":\"gatehouse_1\",\"mechanism\":false}},{\"id\":\"s\",\"kind\":\"street\",\"params\":{\"gate_lot\":\"x\"}}]}").getAsJsonObject();
		PlanWriterJob.withGateMechanisms(lenient);
		var cs = lenient.getAsJsonArray("connectors");
		assertEquals("portcullis", cs.get(0).getAsJsonObject().getAsJsonObject("params").get("mechanism").getAsString());
		assertFalse(cs.get(1).getAsJsonObject().has("params"), "a wall naming no gate lot is left as it is");
		assertFalse(cs.get(2).getAsJsonObject().getAsJsonObject("params").get("mechanism").getAsBoolean(), "a declined mechanism is kept");
		assertFalse(cs.get(3).getAsJsonObject().getAsJsonObject("params").has("mechanism"), "only walls");
		// a wall with lots: mech on a gate entry whose lot holds the mechanism (13 along, 7 through); never on one too small (explicit mech refuses
		// NO_ROOM on a required wall), a turned one, a tower, or an entry that already names its kind
		var listed = com.google.gson.JsonParser.parseString("{\"zones\":[{\"id\":\"town\",\"program\":{\"lots\":["
			+ "{\"id\":\"g_big\",\"type\":\"gatehouse\",\"size\":[17,15]},{\"id\":\"g_small\",\"type\":\"gatehouse\",\"size\":[9,9]},"
			+ "{\"id\":\"g_turned\",\"type\":\"gatehouse\",\"size\":[11,13]},{\"id\":\"g_typed\",\"type\":\"gatehouse\",\"size\":[13,7]},"
			+ "{\"id\":\"g_swing\",\"type\":\"gatehouse\",\"size\":[13,11]},{\"id\":\"t\",\"type\":\"tower\",\"size\":[13,13]}]}}],"
			+ "\"connectors\":[{\"id\":\"w\",\"kind\":\"wall\",\"required\":true,\"lots\":[{\"lot\":\"g_big\",\"at\":\"gate\"},{\"lot\":\"g_small\",\"at\":\"gate\"},"
			+ "{\"lot\":\"g_turned\",\"at\":\"gate\"},{\"lot\":\"g_typed\"},{\"lot\":\"g_swing\",\"at\":\"gate\",\"mech\":\"swing\"},{\"lot\":\"t\",\"at\":\"corner\"}]}]}").getAsJsonObject();
		PlanWriterJob.withGateMechanisms(listed);
		var ls = listed.getAsJsonArray("connectors").get(0).getAsJsonObject().getAsJsonArray("lots");
		java.util.List<String> mech = new java.util.ArrayList<>();
		for (var q : ls) mech.add(q.getAsJsonObject().has("mech") ? q.getAsJsonObject().get("mech").getAsString() : "-");
		assertEquals(List.of("portcullis", "-", "-", "portcullis", "swing", "-"), mech);
		assertFalse(listed.getAsJsonArray("connectors").get(0).getAsJsonObject().has("params"), "a wall with lots never gets the lenient key (it is not read)");
	}
}
