package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.ProposalRules;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProposalWordsJobTest {
	@Test
	void theFixturesReadAndOddAnswersDoNot() throws Exception {
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("proposal-words/granary.json")) {
			var o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("answer");
			var w = ProposalWordsJob.parse(o);
			assertEquals("A reed-thatched granary on stilts", w.title());
			assertEquals("stilted granary", w.role());
		}
		assertNull(ProposalWordsJob.parse(JsonParser.parseString("{\"title\":\"x\",\"why\":\" \",\"role\":\"y\"}").getAsJsonObject()), "a blank reason");
		var long_ = ProposalWordsJob.parse(JsonParser.parseString("{\"title\":\"" + "t".repeat(90) + "\",\"why\":\"line\\nbreak\",\"role\":\"r\"}").getAsJsonObject());
		assertTrue(long_.title().length() <= 60);
		assertEquals("line break", long_.why(), "one line");
	}

	@Test
	void theJobCarriesTheRulesIdeaAndKeepsItsKind() {
		var farm = ProposalRules.find("farm");
		var spec = ProposalWordsJob.build("Reedwick, a stilt village, mossy, for fishing", List.of("smokehouse", "fisher's hut"), farm, "steward_mc:settlement/set_1");
		assertEquals(ProposalWordsJob.TAG, spec.tag());
		assertTrue(spec.prompt().contains("Reedwick") && spec.prompt().contains("type farm") && spec.prompt().contains("smokehouse"), spec.prompt());
		assertTrue(spec.system().contains("do not turn a farm into a castle"));
		assertFalse(spec.schema().getAsJsonObject("properties").has("type"), "the kind is the rules' to decide");
	}
}
