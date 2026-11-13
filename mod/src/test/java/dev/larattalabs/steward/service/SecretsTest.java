package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.gateway.PPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S-7c-4: Steward never names a hidden room (or anything in the artifact's {@code $.secrets[]}) to the player. */
class SecretsTest {
	private static JsonObject walledTown() throws Exception {
		Path f = Path.of("..", "fixtures", "plans", "walled_town.json");
		return JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("answer");
	}

	@Test
	void aHiddenRoomIsASecretByItsIdItsRegionPartsAndItsEntrance() throws Exception {
		var s = Secrets.of(walledTown().getAsJsonObject("plan"), null);
		assertTrue(s.any());
		assertEquals(java.util.Set.of("secret_cellar"), s.parts());
		for (String p : List.of("secret_cellar", "f_secret_cellar", "f_secret_cellar_door", "secret_cellar:entrance", "f_secret_cellar@2")) assertTrue(s.hides(p), p);
		for (String p : List.of("market_hall_1", "town_wall", "f_island", "w_town_wall", "cellar")) assertFalse(s.hides(p), p);
	}

	@Test
	void claudesWordsLoseEverySentenceNamingTheRoom() throws Exception {
		var answer = walledTown();
		var s = Secrets.of(answer.getAsJsonObject("plan"), null);
		String summary = s.scrub(answer.get("summary").getAsString());
		assertEquals("A small walled market town round a central market hall, with merchants' houses on its street.", summary);
		assertFalse(s.names(summary));
		// its label, its id in words, the label's own words, and the tells
		for (String t : List.of("A hidden cellar.", "The secret cellar waits.", "There is a cellar under it.", "Something concealed.", "The SECRET_CELLAR."))
			assertEquals("", s.scrub(t), t);
		assertEquals("The wall is first.", s.scrub("The wall is first. Its cellar waits for Architect."));
		assertFalse(s.names(Secrets.HINT));
		assertEquals(List.of("a stone arch over water"), s.keep(List.of("a stone arch over water", "a tunnel to the secret cellar")));
	}

	@Test
	void aLotsBriefForTheDesignsLeavesTheRoomOut() throws Exception {
		var plan = walledTown().getAsJsonObject("plan");
		var s = Secrets.of(plan, null);
		var scrubbed = s.scrubBriefs(plan);
		var hall = PPlan.lots(scrubbed).stream().filter(l -> l.get("id").getAsString().equals("market_hall_1")).findFirst().orElseThrow();
		assertEquals("The town's market hall, open-sided on the square.", hall.get("brief").getAsString());
		// the plan itself is not changed (Architect checks the plan as written)
		assertTrue(PPlan.lots(plan).stream().anyMatch(l -> l.has("brief") && l.get("brief").getAsString().contains("secret")));
	}

	@Test
	void aPlanWithNoHiddenRoomIsReadAsItAlwaysWas() {
		var plan = JsonParser.parseString("{\"zones\":[{\"id\":\"town\",\"kind\":\"standard\"}],\"forms\":[{\"id\":\"isle\",\"kind\":\"island\"}]}").getAsJsonObject();
		var s = Secrets.of(plan, null);
		assertFalse(s.any());
		assertSame(Secrets.NONE, s);
		String t = "The hidden valley's secret garden.";
		assertEquals(t, s.scrub(t));
		assertSame(plan, s.scrubBriefs(plan));
		assertFalse(Secrets.of("not json").any());
		assertFalse(Secrets.of((String) null).any());
	}

	@Test
	void aPartTheArtifactsSecretsNameIsASecretToo() {
		var plan = JsonParser.parseString("{\"zones\":[{\"id\":\"town\"}],\"forms\":[{\"id\":\"vault\",\"kind\":\"cavern\",\"label\":\"the treasure vault\"}]}").getAsJsonObject();
		var body = JsonParser.parseString("{\"secrets\":[{\"part\":\"vault\",\"host\":\"town\",\"entrance\":{\"cell\":[1,2,3],\"kind\":\"wall\"},\"room\":[0,0,0,4,2,4]}]}")
			.getAsJsonObject();
		var s = Secrets.of(plan, body);
		assertTrue(s.hides("f_vault_door"));
		assertEquals("", s.scrub("The treasure vault is under the square."));
		assertFalse(s.hides("town"));
	}

	@Test
	void aWaitingRoomIsKeptButNotShown() {
		var s = Secrets.of(JsonParser.parseString("{\"forms\":[{\"id\":\"secret_cellar\",\"kind\":\"hidden_room\"}]}").getAsJsonObject(), null);
		var pending = List.of(new PlanStore.Pending("secret_cellar", "form.hidden_room", false, ""), new PlanStore.Pending("moat", "rule.moat", false, "The moat waits."));
		assertEquals(List.of("moat"), Composer.shown(pending, s).stream().map(PlanStore.Pending::part).toList());
		assertEquals(pending, Composer.shown(pending, Secrets.NONE));
	}

	@Test
	void aSecretRefusedAtStartIsOnePartNotItsName() {
		var s = Secrets.of(JsonParser.parseString("{\"forms\":[{\"id\":\"secret_cellar\",\"kind\":\"hidden_room\",\"label\":\"a hidden cellar\"}]}").getAsJsonObject(), null);
		String plan = "{\"zones\":[{\"id\":\"town\",\"program\":{\"lots\":[{\"id\":\"market_hall_1\",\"label\":\"market hall\"}]}}],"
			+ "\"forms\":[{\"id\":\"secret_cellar\",\"kind\":\"hidden_room\",\"host\":\"market_hall_1\"}]}";
		assertEquals("market hall", MaterialRequests.hostName(plan, "secret_cellar:entrance", "Marketwall"));
		assertEquals("market hall", MaterialRequests.hostName(plan, "f_secret_cellar_door", "Marketwall"));
		assertEquals("Marketwall", MaterialRequests.hostName(null, "secret_cellar", "Marketwall"));
		assertTrue(s.hides("secret_cellar"));
	}

	@Test
	void architectsOwnReasonIsToldWithoutTheRoom() throws Exception {
		var s = Secrets.of(walledTown().getAsJsonObject("plan"), null);
		assertEquals("The land under the plan changed (a part of the plan at 1, 2, 3 is grass, not the trapdoor its plan wrote; re-plan): checking it again...",
			s.mask("The land under the plan changed (f_secret_cellar_door at 1, 2, 3 is grass, not the trapdoor its plan wrote; re-plan): checking it again..."));
		assertEquals("Stage a part of the plan could not be approved.", s.mask("Stage secret_cellar:entrance could not be approved."));
		assertFalse(s.names(s.mask("The hidden cellar's hatch is blocked. The wall is fine.")));
		assertEquals("a part of the plan's hatch is blocked. The wall is fine.", s.mask("The hidden cellar's hatch is blocked. The wall is fine."));
		String plain = "Gate town_wall_g1 of region rg4 is grass.";
		assertEquals(plain, s.mask(plain));
		assertEquals(plain, Secrets.NONE.mask(plain));
	}
}
