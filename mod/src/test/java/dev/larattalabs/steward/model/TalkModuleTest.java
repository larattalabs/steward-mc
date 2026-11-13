package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.gateway.TalkJob;
import java.util.List;
import org.junit.jupiter.api.Test;

/** docs/MODULES.md 4.1 "Asked in talk" (slice M3): the talk job may name a module from the live catalog, beside the building asked for. */
class TalkModuleTest {
	static final String BUILDING = "\"building\": {\"role\": \"farm\", \"type\": \"farm\", \"footprint\": \"L\", \"landmark\": false}";

	@Test
	void aModuleIsReadBesideTheBuildingAskedFor() {
		var a = TalkAnswer.parse(JsonParser.parseString("{\"reply\": \"A field it is.\", \"intent\": \"building\", " + BUILDING
			+ ", \"module\": {\"id\": \"fixture_plot\", \"params\": {\"crop\": \"beetroots\"}}}").getAsJsonObject());
		assertEquals(TalkAnswer.Intent.BUILDING, a.intent());
		assertEquals("fixture_plot", a.module().id());
		assertEquals("beetroots", a.module().params().get("crop").getAsString());
		assertEquals("farm", a.building().type(), "the decorative stand-in stays");
	}

	@Test
	void aModuleOutsideABuildingOrWithABadIdIsDropped() {
		var chat = TalkAnswer.parse(JsonParser.parseString("{\"reply\": \"Hello.\", \"intent\": \"chat\", \"module\": {\"id\": \"fixture_plot\"}}").getAsJsonObject());
		assertNull(chat.module());
		var bad = TalkAnswer.parse(JsonParser.parseString("{\"reply\": \"Ok.\", \"intent\": \"building\", " + BUILDING + ", \"module\": {\"id\": \"Not An Id\"}}").getAsJsonObject());
		assertNull(bad.module());
		assertNotNull(bad.building());
	}

	@Test
	void theSchemaTakesTheModuleIdsFromTheLiveCatalog() {
		var none = TalkJob.schema(List.of());
		assertFalse(none.getAsJsonObject("properties").has("module"), "no module offerable: the member is left out");
		var some = TalkJob.schema(List.of("crop_farm"));
		assertEquals("[\"crop_farm\"]", some.getAsJsonObject("properties").getAsJsonObject("module").getAsJsonObject("properties").getAsJsonObject("id").get("enum").toString());
		var c = new TalkJob.Context("Greywater", "a hamlet", "grey", "fishing", List.of(), List.of(), List.of(), "proposals", false,
			List.of("crop_farm: a watered, lit field (params crop [wheat | carrots])"));
		assertEquals(List.of("crop_farm"), c.moduleIds());
		var spec = TalkJob.build("we need food", c, null);
		assertTrue(spec.toString().contains("Working modules: crop_farm"), "listed in the user prompt");
	}
}
