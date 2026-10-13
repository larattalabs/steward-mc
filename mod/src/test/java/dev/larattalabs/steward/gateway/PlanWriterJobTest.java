package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanWriterJobTest {
	/** Architect's jobs refuse a system prompt over 50,000 characters (job.system); the plan schema grows with each block. */
	static final int ARCHITECT_SYSTEM_LIMIT = 50_000;

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
}
