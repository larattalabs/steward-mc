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
		assertTrue(system.contains("\"x-catalogue\"") && system.contains("\"layout.spiral\""), "the schema is in it");
		assertTrue(system.length() < ARCHITECT_SYSTEM_LIMIT - 4_000, system.length() + " characters: near Architect's limit of " + ARCHITECT_SYSTEM_LIMIT);
	}

	@Test
	void theNodeToolsCopyOfThePlanSchemaIsTheJars() throws java.io.IOException {
		// schema/plan-0.json is the pinned jar's architect_mc/schemas/plan.json byte for byte (Regions.planSchema(), TJ SCH-res), for plan-check and the
		// npm tests, which run without the jar; a pin whose schema changed fails here until the copy is refreshed: unzip -p <jar> architect_mc/schemas/plan.json
		String copy = java.nio.file.Files.readString(java.nio.file.Path.of("../schema/plan-0.json"));
		assertEquals(dev.larattalabs.architect.api.Regions.planSchema(), copy, "schema/plan-0.json is stale: copy the pinned jar's architect_mc/schemas/plan.json");
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

	private static com.google.gson.JsonObject json(String s) {
		return com.google.gson.JsonParser.parseString(s).getAsJsonObject();
	}

	/** Architect's {@code Regions.planCatalogue(kinds)} answer (CONTRACT 7cF): the named kinds' full entries, with their defs. */
	private static com.google.gson.JsonObject catalogueOf(java.util.Set<String> kinds) {
		var entries = new com.google.gson.JsonArray();
		for (String k : kinds) entries.add(json("{\"id\":\"" + k + "\",\"state\":\"supported\",\"note\":\"note of " + k + "\",\"def\":{\"description\":\"long text of " + k + "\"}}"));
		var o = json("{\"catalogueVersion\":21,\"unknown\":[]}");
		o.add("entries", entries);
		return o;
	}

	@Test
	void aRepairRoundLooksUpTheRefusedAndMalformedPartsKindsOnceEach() {
		var parts = List.of(
			json("{\"part\":\"tunnel_a\",\"block\":\"tunnel.under\",\"class\":\"refused\"}"),
			json("{\"part\":\"tunnel_b\",\"block\":\"tunnel.under\",\"class\":\"refused\"}"),
			json("{\"part\":\"crater\",\"block\":\"form.crater\",\"class\":\"malformed\"}"),
			json("{\"part\":\"town\",\"block\":\"zone.standard\",\"class\":\"fits\"}"),
			json("{\"part\":\"rail\",\"block\":\"connector.rail\",\"class\":\"unsupported\"}"),
			json("{\"part\":\"keep\",\"block\":\"zone.standard\",\"class\":\"adjusted\"}"),
			json("{\"part\":\"odd\",\"class\":\"refused\"}"));
		// fits, adjusted and unsupported parts aren't repaired (unsupported ones wait); a part with no block names no kind
		assertEquals(List.of("form.crater", "tunnel.under"), List.copyOf(PlanWriterJob.repairKinds(parts)));
		assertTrue(PlanWriterJob.repairKinds(List.of(parts.get(3))).isEmpty());
	}

	@Test
	void theRepairPromptCarriesTheFailingKindsCatalogueAndTheFirstRoundNone() {
		var asked = new java.util.ArrayList<java.util.Set<String>>();
		String text = PlanWriterJob.catalogueFor(new java.util.TreeSet<>(List.of("form.crater", "tunnel.under")), k -> {
			asked.add(k);
			return catalogueOf(k);
		});
		assertEquals(List.of(java.util.Set.of("form.crater", "tunnel.under")), asked, "one lookup for the kinds");
		assertNotNull(text);
		assertEquals(2, text.strip().split("\n").length, "one line a kind: " + text);
		assertTrue(text.contains("long text of tunnel.under") && text.contains("note of form.crater"));

		String first = PlanWriterJob.build("{}", null, List.of("stone"), List.of(), null, null, null, true).prompt();
		assertEquals(first, PlanWriterJob.build("{}", null, List.of("stone"), List.of(), null, null, null, true, text).prompt(),
			"a first round (no previous plan) carries no catalogue, even if given one");
		assertFalse(first.contains("catalogue"));

		String repair = PlanWriterJob.build("{}", null, List.of("stone"), List.of(), null, "{\"schemaVersion\":\"plan/0\"}", "- {\"part\":\"crater\"}\n", true, text).prompt();
		assertTrue(repair.contains("Architect's catalogue for the kinds to repair") && repair.contains("long text of form.crater"), repair);
		assertTrue(repair.indexOf("long text of form.crater") > repair.indexOf("Architect's check of it") && repair.indexOf("long text") < repair.indexOf("Revise the plan"),
			"between the check and the instruction");
		// the old overload (no catalogue) is the repair prompt without the section
		String plain = PlanWriterJob.build("{}", null, List.of("stone"), List.of(), null, "{\"schemaVersion\":\"plan/0\"}", "- {\"part\":\"crater\"}\n", true).prompt();
		assertFalse(plain.contains("catalogue"));
		assertTrue(repair.startsWith(plain.substring(0, plain.indexOf("Revise the plan"))));

		String part = PlanWriterJob.part("{}", "{}", "{\"id\":\"t\"}", "tunnel.under", List.of(), null, "{}", "- {\"part\":\"t\"}\n", true, text).prompt();
		assertTrue(part.contains("long text of tunnel.under"), "a part's repair round carries it too");
		assertFalse(PlanWriterJob.part("{}", "{}", "{\"id\":\"t\"}", "tunnel.under", List.of(), null, null, null, true, text).prompt().contains("catalogue"));
	}

	@Test
	void withoutTheCatalogueTheRepairRoundGoesOnWithoutIt() {
		var kinds = java.util.Set.of("tunnel.under");
		assertNull(PlanWriterJob.catalogueFor(java.util.Set.of(), k -> fail("no kinds, no lookup")));
		// an Architect before 0.62.0 throws (the default overload); one with nothing for the kinds answers no entries
		assertNull(PlanWriterJob.catalogueFor(kinds, k -> { throw new UnsupportedOperationException("planCatalogue(kinds)"); }));
		assertNull(PlanWriterJob.catalogueFor(kinds, k -> { throw new NoSuchMethodError("planCatalogue"); }));
		assertNull(PlanWriterJob.catalogueFor(kinds, k -> null));
		assertNull(PlanWriterJob.catalogueFor(kinds, k -> json("{\"entries\":[],\"unknown\":[\"tunnel.under\"]}")));
		String repair = PlanWriterJob.build("{}", null, List.of("stone"), List.of(), null, "{}", "- {\"part\":\"t\"}\n", true, null).prompt();
		assertTrue(repair.contains("Revise the plan") && !repair.contains("catalogue"));
	}

	@Test
	void kindsPastTheLengthCapAreNamedNotSent() {
		var kinds = new java.util.TreeSet<String>();
		for (int i = 0; i < 40; i++) kinds.add("form.k" + (100 + i));
		String text = PlanWriterJob.catalogueFor(kinds, k -> {
			var o = catalogueOf(k);
			for (var e : o.getAsJsonArray("entries")) e.getAsJsonObject().getAsJsonObject("def").addProperty("pad", "x".repeat(1_000));
			return o;
		});
		assertNotNull(text);
		assertTrue(text.length() < PlanWriterJob.CATALOGUE_CHARS + 1_000, text.length() + " characters");
		assertTrue(text.contains("form.k100") && text.contains("(left out for length: ") && text.contains("form.k139)"), text.substring(Math.max(0, text.length() - 300)));
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
		// the prompt tells the writer to put gatehouses on their wall, sized for the gate
		String prompt = PlanWriterJob.systemPrompt();
		assertTrue(prompt.contains("{lot, at: \"gate\"}") && prompt.contains("at least 13 along the wall and 7 through it"), "the wall-lots rule");
		assertFalse(prompt.contains("lots aren't placed on a wall line yet"));
	}
}
