package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.JobSpec;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConceptCardJobTest {
	private static final ConceptCardJob.Settings SETTINGS = new ConceptCardJob.Settings(true, "supplied", 128);

	@Test
	void buildsACheapStructuredJob() {
		JobSpec j = ConceptCardJob.build("  a fishing village on stilts  ", Map.of(), SETTINGS, "steward_mc:settlement/set_1");
		assertEquals("structured", j.kind());
		assertEquals("claude-sonnet-5-5", j.model());
		assertEquals("low", j.effort());
		assertEquals(0.10, j.budgetUsd());
		assertEquals("concept-card", j.tag());
		assertTrue(j.tools().isEmpty());
		assertTrue(j.blobs().isEmpty());
		assertEquals("steward_mc:settlement/set_1", j.owner());
		assertTrue(j.prompt().contains("a fishing village on stilts"));
		assertFalse(j.prompt().contains("  a fishing"), "the prompt is stripped");
	}

	@Test
	void promptCarriesSettingsAndChipsThatWin() {
		JobSpec j = ConceptCardJob.build("a castle", Map.of("style", "gothic", "story", " "), SETTINGS, null);
		assertTrue(j.prompt().contains("- style: gothic"));
		assertFalse(j.prompt().contains("- story:"), "blank chips are left out");
		assertTrue(j.prompt().contains("world is survival"));
		assertTrue(j.prompt().contains("default difficulty supplied"));
		assertTrue(j.prompt().contains("claim radius 128"));
	}

	@Test
	void systemPromptIsTheMarkdownSection() {
		String sys = ConceptCardJob.systemPrompt();
		assertTrue(sys.startsWith("You turn a player's description"));
		assertFalse(sys.contains("## System prompt"));
		assertTrue(sys.contains("`infernal`"));
	}

	@Test
	void schemaDropsDollarKeywordsAndKeepsStructure() {
		JsonObject s = ConceptCardJob.schema();
		assertFalse(s.has("$schema"));
		assertFalse(s.has("$id"));
		assertEquals("object", s.get("type").getAsString());
		assertTrue(s.getAsJsonArray("required").toString().contains("\"site\""));
		assertTrue(s.getAsJsonObject("properties").has("contradictions"));
	}

	@Test
	void emptyPromptIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> ConceptCardJob.build("   ", Map.of(), SETTINGS, null));
	}
}
