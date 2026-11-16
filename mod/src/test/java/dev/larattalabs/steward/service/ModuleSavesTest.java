package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.module.ModuleOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Settlements format 7 and builds format 6 (slice M3; docs/MODULES.md 4.1, 4.4): a module offer and a build's module orders are saved exactly, an
 * older Steward refuses the files rather than drop them, and malformed orders refuse the file rather than be guessed.
 */
class ModuleSavesTest {
	static final String SHA = "ab".repeat(32);

	static ModuleOrder order() {
		return new ModuleOrder("fixture_plot", 1, SHA, JsonParser.parseString("{\"side\": 9, \"crop\": \"wheat\"}").getAsJsonObject(),
			JsonParser.parseString("{\"minecraft:dirt\": 120, \"minecraft:wheat_seeds\": 81}").getAsJsonObject(), "about 40 wheat a day once it's going", true, true);
	}

	@Test
	void aModuleOfferRoundTripsInTheSettlementsFile() {
		Settlement s = Settlement.founded("set_1", "Here", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.SUPPLIED, 1L);
		var p = Proposal.module("module.fixture_plot", "A fenced, lit plot", "You carry wheat seeds.", new ConceptCard.Building("fenced plot", "module_fixture", 1, "L", false,
			null, null), 5L, "seeds", order());
		s = s.withProposals(new Settlement.Proposals(List.of(p), List.of(), List.of(), 5L));
		SettlementStore st = new SettlementStore();
		st.put(s);
		String json = st.toJson();
		assertTrue(json.contains("\"format\": 7"));
		var back = SettlementStore.fromJson(json).get("set_1").orElseThrow().proposals().open().get(0);
		assertEquals(p, back);
		assertEquals(order(), back.module());
		// an older Steward (format 6) refuses it: it would design the lot as a paid decorative building
		String newer = json.replaceFirst("\"format\": 7", "\"format\": 8");
		assertThrows(JsonParseException.class, () -> SettlementStore.fromJson(newer));
	}

	@Test
	void aDamagedModuleOfferRefusesTheSettlementsFile() {
		Settlement s = Settlement.founded("set_1", "Here", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.SUPPLIED, 1L);
		var p = Proposal.module("module.fixture_plot", "A plot", "Seeds.", new ConceptCard.Building("fenced plot", "module_fixture", 1, "L", false, null, null), 5L, "seeds", order());
		s = s.withProposals(new Settlement.Proposals(List.of(p), List.of(), List.of(), 5L));
		SettlementStore st = new SettlementStore();
		st.put(s);
		String good = st.toJson();
		for (String[] bad : new String[][] {{"\"version\": 1,", "\"version\": 1.0000000000000001,"}, {"\"minecraft:dirt\": 120", "\"minecraft:dirt\": 120.5"},
			{"\"programSha\": \"" + SHA + "\"", "\"programSha\": \"x\""}}) {
			assertTrue(good.contains(bad[0]), bad[0]);
			String j = good.replace(bad[0], bad[1]);
			assertThrows(JsonParseException.class, () -> SettlementStore.fromJson(j), bad[1]);
		}
		// a module rule's proposal without its order would be built as a paid design: refused
		var noOrder = com.google.gson.JsonParser.parseString(good).getAsJsonObject();
		noOrder.getAsJsonArray("settlements").get(0).getAsJsonObject().getAsJsonObject("proposals").getAsJsonArray("open").get(0).getAsJsonObject().remove("module");
		assertThrows(JsonParseException.class, () -> SettlementStore.fromJson(noOrder.toString()));
	}

	@Test
	void aHeldBillIsSavedWithTheBuild() {
		BuildStore st = new BuildStore();
		var held = order().accepted(false, "module.fixture_plot").holding(JsonParser.parseString("{\"minecraft:dirt\": 130}").getAsJsonObject());
		st.put(BuildStoreTestAccess.saved().withModuleOrders(Map.of("lot_3", held)));
		var back = BuildStore.fromJson(st.toJson()).all().get(0).moduleOrders().get("lot_3");
		assertEquals(held, back);
		assertEquals(130, back.held().get("minecraft:dirt").getAsInt());
		assertEquals("module.fixture_plot", back.proposal());
		String bad = st.toJson().replace("\"minecraft:dirt\": 130", "\"minecraft:dirt\": 1.5");
		assertThrows(JsonParseException.class, () -> BuildStore.fromJson(bad));
	}

	@Test
	void anApprovedUpToBillIsTheCapAfterARestartAndAnOlderExactOneStaysExact() {
		// (QD) held as shown: the quote with its terrain item up to its allowance (209 dirt, 205 from terrain: up to 214), saved in the same member
		var quote = JsonParser.parseString("{\"minecraft:dirt\": 209, \"minecraft:oak_planks\": 40}").getAsJsonObject();
		var allowed = dev.larattalabs.steward.module.ModuleBound.allowed(quote, Map.of("minecraft:dirt", 205));
		var held = order().accepted(false, "module.fixture_plot").holding(allowed);
		BuildStore st = new BuildStore();
		st.put(BuildStoreTestAccess.saved().withModuleOrders(Map.of("lot_3", held)));
		String json = st.toJson();
		assertTrue(json.contains("\"format\": " + BuildStore.FORMAT), "no new member, no format move of its own (PV1 moved it to 7)");
		var back = BuildStore.fromJson(json).all().get(0).moduleOrders().get("lot_3");
		assertEquals(held, back);
		var approved = back.approvedHeld();
		assertEquals(Map.of("minecraft:dirt", 214, "minecraft:oak_planks", 40), dev.larattalabs.steward.module.ModuleBound.cap(approved.bound()),
			"approved after the restart: the bound, and so the maxBill, is what was shown");
		assertNull(approved.held());
		// approved and saved, then read again: the cap is unchanged
		BuildStore st2 = new BuildStore();
		st2.put(BuildStoreTestAccess.saved().withModuleOrders(Map.of("lot_3", approved)));
		assertEquals(approved.bound(), BuildStore.fromJson(st2.toJson()).all().get(0).moduleOrders().get("lot_3").bound());
		// a build held before QD saved the exact quote: approved exactly, as it was shown
		String older = json.replace("\"minecraft:dirt\": 214", "\"minecraft:dirt\": 209");
		var old = BuildStore.fromJson(older).all().get(0).moduleOrders().get("lot_3").approvedHeld();
		assertEquals(Map.of("minecraft:dirt", 209, "minecraft:oak_planks", 40), dev.larattalabs.steward.module.ModuleBound.cap(old.bound()));
	}

	@Test
	void aFormat6SettlementsFileReadsWithNoModuleOffers() throws Exception {
		Settlement s = Settlement.founded("set_1", "Here", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		SettlementStore st = new SettlementStore();
		st.put(s);
		String v6 = st.toJson().replaceFirst("\"format\": 7", "\"format\": 6");
		var back = SettlementStore.fromJson(v6);
		assertEquals(6, back.readFormat());
		assertEquals(s, back.get("set_1").orElseThrow());
	}

	@Test
	void ordersRoundTripInTheBuildsFile(@TempDir Path dir) throws Exception {
		BuildStore st = new BuildStore();
		var b = BuildStoreTestAccess.saved().withModuleBindings(BuildStoreFormat5Test.bindings()).withModuleOrders(Map.of("lot_3", order()));
		st.put(b);
		Path f = dir.resolve("steward-builds.json");
		st.save(f);
		assertTrue(Files.readString(f).contains("\"format\": 7"));
		var back = BuildStore.load(f).all().get(0);
		assertEquals(b, back);
		assertEquals(order(), back.moduleOrders().get("lot_3"));
		assertTrue(back.moduleOrders().get("lot_3").autonomous());
	}

	@Test
	void aFormat5BuildReadsWithNoOrders() {
		BuildStore st = new BuildStore();
		st.put(BuildStoreTestAccess.saved().withModuleBindings(BuildStoreFormat5Test.bindings()));
		String v5 = st.toJson().replaceFirst("\"format\": 7", "\"format\": 5");
		var back = BuildStore.fromJson(v5);
		assertEquals(5, back.readFormat());
		assertEquals(Map.of(), back.all().get(0).moduleOrders());
		assertEquals(2, back.all().get(0).moduleBindings().size());
	}

	@Test
	void aMalformedOrderRefusesTheFileNeverGuessesIt() {
		BuildStore st = new BuildStore();
		st.put(BuildStoreTestAccess.saved().withModuleOrders(Map.of("lot_3", order())));
		String good = st.toJson();
		for (String[] bad : new String[][] {{"\"version\": 1", "\"version\": 1.0"}, {"\"minecraft:dirt\": 120", "\"minecraft:dirt\": 120.5"},
			{"\"minecraft:dirt\": 120", "\"minecraft:dirt\": -1"}, {"\"programSha\": \"" + SHA + "\"", "\"programSha\": \"x\""}, {"\"moduleId\": \"fixture_plot\"", "\"moduleId\": \"Bad\""}}) {
			assertTrue(good.contains(bad[0]), bad[0]);
			String j = good.replace(bad[0], bad[1]);
			assertThrows(JsonParseException.class, () -> BuildStore.fromJson(j), bad[1]);
		}
		String newer = good.replaceFirst("\"format\": 7", "\"format\": 8");
		assertThrows(JsonParseException.class, () -> BuildStore.fromJson(newer), "an older Steward refuses format 7, this one 8");
	}
}
