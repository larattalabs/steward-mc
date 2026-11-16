package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** What is left of the plan writer (CA2): the portcullis default, and the Node tools' copy of the plan schema. */
class PlanWriterJobTest {
	@Test
	void theNodeToolsCopyOfThePlanSchemaIsTheJars() throws java.io.IOException {
		// schema/plan-0.json is the pinned jar's architect_mc/schemas/plan.json byte for byte (Regions.planSchema(), TJ SCH-res), for plan-check and the
		// npm tests, which run without the jar; a pin whose schema changed fails here until the copy is refreshed: unzip -p <jar> architect_mc/schemas/plan.json
		String copy = java.nio.file.Files.readString(java.nio.file.Path.of("../schema/plan-0.json"));
		assertEquals(dev.larattalabs.architect.api.Regions.planSchema(), copy, "schema/plan-0.json is stale: copy the pinned jar's architect_mc/schemas/plan.json");
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
		// a wall with lots that declines (an open arch the card asked for): no mech anywhere on it
		var open = com.google.gson.JsonParser.parseString("{\"zones\":[{\"id\":\"town\",\"program\":{\"lots\":[{\"id\":\"g\",\"type\":\"gatehouse\",\"size\":[17,15]}]}}],"
			+ "\"connectors\":[{\"id\":\"w\",\"kind\":\"wall\",\"params\":{\"mechanism\":\"none\"},\"lots\":[{\"lot\":\"g\",\"at\":\"gate\"}]}]}").getAsJsonObject();
		PlanWriterJob.withGateMechanisms(open);
		assertFalse(open.getAsJsonArray("connectors").get(0).getAsJsonObject().getAsJsonArray("lots").get(0).getAsJsonObject().has("mech"));
		// (CA) gatehouses on their wall, sized for the gate, are Architect's composer rules now; Steward's own rules say the portcullis default, and a
		// waiting part's rules (CA2) hold none of the whole-plan rules
		assertTrue(ComposeJob.rules().contains("Steward gives every gatehouse a portcullis itself"), "the portcullis default");
		String part = ComposeJob.partRules();
		assertFalse(part.contains("One lot per building") || part.contains("{lot, at: \"gate\"}"), "the part rules are the part's only");
	}
}
