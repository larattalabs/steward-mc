package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.gateway.TalkJob;
import dev.larattalabs.steward.model.TalkAnswer.Intent;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TalkTest {
	private static final TalkRules.Facts FACTS = new TalkRules.Facts("Greywater", "resting", 3, 1, 2);

	private static JsonObject fixture(String name) throws Exception {
		try (InputStream in = TalkTest.class.getClassLoader().getResourceAsStream("talk/" + name)) {
			assertNotNull(in, name);
			return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	@Test
	void theStewardAnswersSmallTalkItselfForNothing() {
		assertTrue(TalkRules.local("Hello steward!", FACTS).orElseThrow().contains("Greywater"));
		assertTrue(TalkRules.local("thanks", FACTS).isPresent());
		String status = TalkRules.local("what are you doing?", FACTS).orElseThrow();
		assertTrue(status.contains("resting") && status.contains("3 buildings") && status.contains("1 district") && status.contains("2 ideas"), status);
		assertTrue(TalkRules.local("help", FACTS).orElseThrow().contains("inbox"));
		// anything with substance goes to the talk job
		assertTrue(TalkRules.local("add a library", FACTS).isEmpty());
		assertTrue(TalkRules.local("hello, can you build a harbour?", FACTS).isEmpty());
		assertTrue(TalkRules.local("thanks, now make the tavern creepier?", FACTS).isEmpty());
	}

	@Test
	void theFixturesReadAsTheirIntents() throws Exception {
		var lib = TalkAnswer.parse(fixture("library.json").getAsJsonObject("answer"));
		assertEquals(Intent.BUILDING, lib.intent());
		assertEquals("scholars' library", lib.building().role());
		assertEquals(1, lib.building().count());
		assertEquals("central", lib.building().placement());
		var harbour = TalkAnswer.parse(fixture("harbour.json").getAsJsonObject("answer"));
		assertEquals(Intent.DISTRICT, harbour.intent());
		assertEquals("E", harbour.way());
		assertEquals("the harbour", harbour.district());
		var creepier = TalkAnswer.parse(fixture("creepier.json").getAsJsonObject("answer"));
		assertEquals(Intent.CHANGE, creepier.intent());
		assertEquals("tavern", creepier.changeTarget());
		assertEquals(Intent.CHAT, TalkAnswer.parse(fixture("weather.json").getAsJsonObject("answer")).intent());
	}

	@Test
	void anAnswerMissingWhatItsIntentNeedsIsOnlyTalk() {
		var a = TalkAnswer.parse(JsonParser.parseString("{\"reply\":\"Sure.\",\"intent\":\"building\"}").getAsJsonObject());
		assertEquals(Intent.CHAT, a.intent());
		var b = TalkAnswer.parse(JsonParser.parseString("{\"reply\":\"Hm.\",\"intent\":\"demolish\"}").getAsJsonObject());
		assertEquals(Intent.UNCLEAR, b.intent());
		var c = TalkAnswer.parse(JsonParser.parseString("{\"reply\":\"East.\",\"intent\":\"district\",\"way\":\"NE\"}").getAsJsonObject());
		assertNull(c.way(), "a way that is not N, E, S or W is dropped");
		var bad = TalkAnswer.parse(JsonParser.parseString("{\"reply\":\"" + "x".repeat(400) + "\",\"intent\":\"building\",\"building\":{\"role\":\"\",\"type\":\"Bad Type\",\"footprint\":\"Q\",\"landmark\":false}}").getAsJsonObject());
		assertTrue(bad.reply().length() <= TalkAnswer.MAX_REPLY);
		assertEquals("custom", bad.building().type());
		assertEquals("M", bad.building().footprint());
		assertThrows(com.google.gson.JsonParseException.class, () -> TalkAnswer.parse(JsonParser.parseString("{\"intent\":\"chat\"}").getAsJsonObject()));
	}

	@Test
	void theJobCarriesTheSettlementAndTheSchema() {
		var c = new TalkJob.Context("Greywater", "a hamlet by a lake", "mossy and grey", "fishing", List.of("smokehouse", "cottage"), List.of("the harbour (east): nothing built yet"),
			List.of("A farm"), "proposals", true);
		var spec = TalkJob.build("add a library", c, "steward_mc:settlement/set_4");
		assertEquals("structured", spec.kind());
		assertEquals(TalkJob.TAG, spec.tag());
		assertTrue(spec.prompt().contains("add a library") && spec.prompt().contains("smokehouse, cottage") && spec.prompt().contains("is full")
			&& spec.prompt().contains("the harbour (east)"), spec.prompt());
		assertTrue(spec.system().contains("**building**"));
		assertEquals("object", spec.schema().get("type").getAsString());
		assertFalse(spec.schema().has("$schema"));
	}
}
