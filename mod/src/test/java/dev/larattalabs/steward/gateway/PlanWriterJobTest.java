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
		assertEquals(List.of("plan/0#compact"), spec.systemRefs());
		assertFalse(spec.system().contains("\"schemaVersion\""), "the schema is not sent inline");
		assertTrue(PlanWriterJob.build("{}", null, List.of("stone"), List.of(), null, null, null, false).system().contains("\"schemaVersion\""));
	}
}
