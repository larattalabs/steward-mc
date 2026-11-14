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
	void survivalWorldsBuildNothingFree() {
		assertEquals(Difficulty.SUPPLIED, Difficulty.forWorld(Difficulty.PATRON, true), "Patron in a survival world is Supplied");
		assertEquals(Difficulty.PATRON, Difficulty.forWorld(Difficulty.PATRON, false));
		assertEquals(Difficulty.HARDCORE, Difficulty.forWorld(Difficulty.HARDCORE, true));
		assertEquals(Difficulty.SUPPLIED, Difficulty.forWorld(Difficulty.SUPPLIED, false), "Supplied stays Supplied in creative");
		assertEquals(Difficulty.SUPPLIED, Difficulty.forWorld(Difficulty.ECONOMY, true), "Economy is built as Supplied in survival");
		// what a build is placed by now: free outside survival (until phase 4 is offered there), the world's rule in it
		assertEquals(Difficulty.PATRON, Difficulty.effective(Difficulty.SUPPLIED, false), "survival turned off: free");
		assertEquals(Difficulty.SUPPLIED, Difficulty.effective(Difficulty.PATRON, true), "survival turned on: from materials");
		assertEquals(Difficulty.HARDCORE, Difficulty.effective(Difficulty.HARDCORE, true));
		Settlement s = Settlement.founded("set_1", "Here", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		assertSame(s, s.withDifficulty(Difficulty.PATRON, 2L), "no change, no history");
		Settlement t = s.withDifficulty(Difficulty.SUPPLIED, 2L);
		assertEquals(Difficulty.SUPPLIED, t.difficulty());
		assertEquals("Difficulty: Supplied", t.log().get(t.log().size() - 1).text());
	}

	@Test
	void gatewayStatusChecksMajorVersionAndFeatures() {
		Set<String> ok = Set.of("designs", "sites", "events", "survey");
		int m = ArchitectGateway.REQUIRED_MINOR;
		assertTrue(ArchitectGateway.Status.evaluate("1." + m + ".0", ok).ok());
		assertTrue(ArchitectGateway.Status.evaluate("1." + (m + 1) + ".2", ok).ok());
		assertFalse(ArchitectGateway.Status.evaluate("1." + (m - 1) + ".9", ok).ok(), "a loaded Architect older than the pin is refused");
		assertFalse(ArchitectGateway.Status.evaluate("2.0.0", ok).ok(), "the next major API is refused");
		assertFalse(ArchitectGateway.Status.evaluate("0." + m + ".0", ok).ok());
		assertFalse(ArchitectGateway.Status.evaluate("1." + m + ".0", Set.of("sites", "events")).ok());
		assertFalse(ArchitectGateway.Status.evaluate("x.y", ok).ok());
		assertFalse(ArchitectGateway.Status.evaluate(null, ok).ok(), "an unreadable loaded version is refused");
	}

	@Test
	void gatewayMinimumIsThePinnedApi() {
		// the pin's own API version, inlined at compile time: a pin bump moves the minimum with it
		assertEquals(dev.larattalabs.architect.api.ArchitectApi.VERSION, ArchitectGateway.PINNED_VERSION);
		assertEquals(Integer.parseInt(ArchitectGateway.PINNED_VERSION.split("\\.")[1]), ArchitectGateway.REQUIRED_MINOR);
		assertTrue(ArchitectGateway.REQUIRED_MINOR >= 56, "at least Architect 0.63.0's API 1.56");
	}

	@Test
	void gatewayFailsAnOlderLoadedApi() {
		// check() reads the loaded Architect's apiVersion(), never the inlined VERSION; an older or other-major one fails
		Set<String> features = Set.of("designs", "sites", "events");
		assertFalse(ArchitectGateway.Status.evaluate("1.12.0", features).ok(), "an old loaded Architect fails even though Steward compiled against a newer one");
		assertFalse(ArchitectGateway.Status.evaluate("2.0.0", features).ok());
		assertFalse(ArchitectGateway.Status.evaluate(null, features).ok());
		assertTrue(ArchitectGateway.Status.evaluate(ArchitectGateway.PINNED_VERSION, features).ok());
	}

	@Test
	void everyRecipeUnlocksInTheRecipeBook() throws Exception {
		// survival players find the Founding Stone, Ledger and Board in the recipe book only through these advancements (UI audit F6)
		String[][] unlocks = {{"founding_stone", "misc"}, {"steward_ledger", "misc"}, {"settlement_board", "building_blocks"}};
		for (String[] u : unlocks) {
			assertNotNull(ModelTest.class.getResourceAsStream("/data/steward_mc/recipe/" + u[0] + ".json"), u[0]);
			try (InputStream in = ModelTest.class.getResourceAsStream("/data/steward_mc/advancement/recipes/" + u[1] + "/" + u[0] + ".json")) {
				assertNotNull(in, "an unlock advancement for " + u[0]);
				JsonObject a = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
				assertEquals("steward_mc:" + u[0], a.getAsJsonObject("rewards").getAsJsonArray("recipes").get(0).getAsString());
				assertEquals("steward_mc:" + u[0], a.getAsJsonObject("criteria").getAsJsonObject("has_the_recipe").getAsJsonObject("conditions").get("recipes").getAsString());
				assertTrue(a.getAsJsonObject("criteria").has("has_book"), "unlocks once the player holds a book");
			}
		}
	}
}
