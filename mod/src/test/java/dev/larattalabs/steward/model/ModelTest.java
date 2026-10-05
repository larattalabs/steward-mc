package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.gateway.ArchitectGateway;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelTest {
	private static JsonObject fixtureCard(String name) throws Exception {
		try (InputStream in = ModelTest.class.getClassLoader().getResourceAsStream(name + ".json")) {
			assertNotNull(in, "fixture " + name);
			return JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card");
		}
	}

	@Test
	void everyFixtureParses() throws Exception {
		for (String f : new String[] {"crater_works", "stilt_village", "sky_temple", "contradiction"}) {
			ConceptCard c = ConceptCard.parse(fixtureCard(f));
			assertFalse(c.site().text().isBlank());
			assertNotNull(c.constraints().near());
		}
	}

	@Test
	void realModelOutputsParse() throws Exception {
		for (String f : new String[] {"crater_works_real", "contradiction_real"}) {
			try (InputStream in = ModelTest.class.getClassLoader().getResourceAsStream("real/" + f + ".json")) {
				assertNotNull(in, f);
				JsonObject card = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card");
				ConceptCard c = ConceptCard.parse(card);
				assertNull(c.site().template(), "a custom site keeps a null template");
				assertEquals("infernal", c.style().template());
			}
		}
	}

	@Test
	void splitsSiteStylePurposeAndKeepsCustomSiteTemplateNull() throws Exception {
		ConceptCard c = ConceptCard.parse(fixtureCard("crater_works"));
		assertEquals("giant meteor crater", c.site().text());
		assertNull(c.site().template());
		assertEquals("sculpt", c.site().terrain());
		assertEquals("infernal", c.style().template());
		assertEquals("mining_outpost", c.purpose().template());
	}

	@Test
	void rejectsBadTerrain() throws Exception {
		JsonObject j = fixtureCard("crater_works");
		j.getAsJsonObject("site").addProperty("terrain", "bulldoze");
		assertThrows(JsonParseException.class, () -> ConceptCard.parse(j));
	}

	@Test
	void contradictionCardKeepsItsResolution() throws Exception {
		ConceptCard c = ConceptCard.parse(fixtureCard("contradiction"));
		assertEquals(1, c.contradictions().size());
	}

	@Test
	void tierIsTheHighestReached() {
		assertEquals(Tier.WOOD, Tier.fromAdvancements(Set.of()));
		assertEquals(Tier.IRON, Tier.fromAdvancements(Set.of("minecraft:story/mine_stone", "minecraft:story/smelt_iron")));
		assertEquals(Tier.NETHER, Tier.fromAdvancements(Set.of("minecraft:story/mine_diamond", "minecraft:story/enter_the_nether")));
		assertTrue(Tier.ELYTRA.atLeast(Tier.DIAMOND));
		assertFalse(Tier.STONE.atLeast(Tier.IRON));
	}

	@Test
	void patronNeverBypassesASurvivalWorld() {
		assertTrue(Difficulty.PATRON.instantAllowed(false));
		assertFalse(Difficulty.PATRON.instantAllowed(true));
		assertFalse(Difficulty.SUPPLIED.instantAllowed(false));
	}

	@Test
	void permissionLevelsGateTheRightActions() {
		assertTrue(Permission.PROPOSALS.needsApproval(Permission.Action.UPGRADE));
		assertFalse(Permission.AUTONOMOUS.needsApproval(Permission.Action.UPGRADE));
		assertTrue(Permission.AUTONOMOUS.needsApproval(Permission.Action.DEMOLISH));
		assertTrue(Permission.AUTONOMOUS.needsApproval(Permission.Action.NEW_DISTRICT));
		assertFalse(Permission.FULL.needsApproval(Permission.Action.DEMOLISH));
	}

	@Test
	void gatewayStatusChecksMajorVersionAndFeatures() {
		Set<String> ok = Set.of("designs", "sites", "events", "survey");
		assertTrue(ArchitectGateway.Status.evaluate("1.2.0", ok).ok());
		assertFalse(ArchitectGateway.Status.evaluate("2.0.0", ok).ok());
		assertFalse(ArchitectGateway.Status.evaluate("1.0.0", Set.of("sites", "events")).ok());
		assertFalse(ArchitectGateway.Status.evaluate("x.y", ok).ok());
	}
}
