package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.module.ModuleOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * (QD on Architect v0.73.0, BC-c/BC-d) What Steward sends and says now that a {@code maxBill} covers only the realise or revision it comes with
 * ({@code survival.caps.<rid>.maxBill}, kept by Architect) and a region charge or an unclean start's reconcile counts against it.
 */
class BillCapBCdTest {
	static final Map<String, Integer> BOUND = Map.of("minecraft:dirt", 214, "minecraft:oak_planks", 40);

	static ModuleOrder approved() {
		var quote = JsonParser.parseString("{\"minecraft:dirt\": 209, \"minecraft:oak_planks\": 40}").getAsJsonObject();
		var shown = dev.larattalabs.steward.module.ModuleBound.allowed(quote, Map.of("minecraft:dirt", 205));
		return ModuleSavesTest.order().accepted(false, "module.fixture_plot").holding(shown).approvedHeld();
	}

	@Test
	void aCappedModuleGrowthThenAPlainGrowthSendsNoCap() {
		// the module growth from materials: its approved bound (the quote, dirt up to its allowance) is its revision's maxBill
		assertEquals(BOUND, SettlementRunner.realiseCap(true, true, Map.of("lot_3", approved()), true));
		// the next growth, a plain one (designed lots, no module order): sent uncapped, so BC-d leaves its rid uncapped; never the module's bound
		assertNull(SettlementRunner.realiseCap(true, false, Map.of(), true), "a plain growth from materials");
		// a composed town with module lots is not a module build: no cap (MATERIALS_WAIT_FOR_LOT_CAP leaves its module lots out from materials)
		assertNull(SettlementRunner.realiseCap(true, false, Map.of("lot_3", approved()), true));
		// built at once: nothing is drawn, nothing to cap
		assertNull(SettlementRunner.realiseCap(false, true, Map.of("lot_3", approved()), true));
		// an Architect without maxBill: no cap (and the module realise from materials is refused before it, said)
		assertNull(SettlementRunner.realiseCap(true, true, Map.of("lot_3", approved()), false));
		// two module lots can't share one revision's cap
		assertNull(SettlementRunner.realiseCap(true, true, Map.of("lot_3", approved(), "lot_4", approved()), true));
	}

	@Test
	void anOvershootAfterAnUncleanStartIsSaidByItsNextNeeded() {
		// (BC-c) the reconcile charged 39 planks standing past the cap; the target holds bill_cap with nextNeeded = its need 39 + the 39 over
		var w = new dev.larattalabs.architect.api.SiteWait("s1", true, 10, List.of(), 0, dev.larattalabs.architect.api.SiteWait.BILL_CAP, List.of());
		assertTrue(MaterialRequests.capped(w));
		assertEquals("The farm has stopped: finishing it would take 78 oak planks more than you approved, so I take no more for it; filling the"
			+ " stockpile won't help. /steward undo set_1 takes it up and gives back what it drew; then ask for it again and I'll put the new bill to you.",
			MaterialRequests.overCapWords("farm", "set_1", List.of(Map.entry("minecraft:oak_planks", 78)), 0, MaterialRequests.TakeUp.UNDO));
	}

	@Test
	void whileTheGrowthsBuildStillRunsTheWordsNameCancelWhichTakesItUp() {
		// (QD-2, the GPT review of pin-073) while the build is PLACING, /steward undo is refused as busy: the words name /steward cancel, which takes
		// a growth from materials up where it stands and refunds what it drew (A2L, RU); after the build has ended they name /steward undo
		String during = MaterialRequests.overCapWords("farm", "set_1", List.of(Map.entry("minecraft:oak_planks", 2)), 0, MaterialRequests.TakeUp.CANCEL);
		assertEquals("The farm has stopped: finishing it would take 2 oak planks more than you approved, so I take no more for it; filling the"
			+ " stockpile won't help. /steward cancel set_1 takes it up and gives back what it drew; then ask for it again and I'll put the new bill to you.", during);
		assertFalse(during.contains("/steward undo"));
		var ext = JsonParser.parseString("{\"architect_mc:waitsFor\": {\"kind\": \"bill_cap\", \"missing\": {\"minecraft:observer\": 1},"
			+ " \"say\": \"Main gatehouse's portcullis waits bill_cap: over\"}}").getAsJsonObject();
		assertTrue(SettlementRunner.capStageWait(ext, "set_1", MaterialRequests.TakeUp.CANCEL).contains("/steward cancel set_1 takes it up"));
		assertTrue(SettlementRunner.capStageWait(ext, "set_1", MaterialRequests.TakeUp.UNDO).contains("/steward undo set_1 takes it up"));
		// no runner for the settlement, or a target whose region is unknown: undo (refused while busy, with a pointer to cancel), never a cancel
		// that could stop another region's build
		assertFalse(SettlementRunner.cancelTakesUp("no_such_settlement", "rg1"));
		assertFalse(SettlementRunner.cancelTakesUp("no_such_settlement", null));
	}

	@Test
	void aBuildSavedBeforeThePinKeepsItsBoundAsTheCap() {
		// a 0.72.0 Steward's save (no QD there: the order's bound is the measured one approved at Accept): read on 0.73.0, the maxBill is that
		// bound exactly, no allowance added on read. Architect moves its own 0.72.0 region-wide cap to the owning rid (BC-d, its gate)
		var order = ModuleSavesTest.order();
		BuildStore st = new BuildStore();
		st.put(BuildStoreTestAccess.saved().withModuleOrders(Map.of("lot_3", order)));
		var back = BuildStore.fromJson(st.toJson()).all().get(0).moduleOrders();
		assertEquals(Map.of("minecraft:dirt", 120, "minecraft:wheat_seeds", 81), SettlementRunner.realiseCap(true, true, back, true));
	}

	@Test
	void aMechanismHeldByTheCapIsSaidInTheStewardsWords() {
		var ext = JsonParser.parseString("{\"architect_mc:waitsFor\": {\"target\": \"stockpile:p1\", \"kind\": \"bill_cap\", \"missing\": {\"minecraft:observer\": 3},"
			+ " \"say\": \"Main gatehouse's portcullis waits bill_cap: its bill is past the region's maxBill by {minecraft:observer=3}\"}}").getAsJsonObject();
		String said = SettlementRunner.capStageWait(ext, "set_1", MaterialRequests.TakeUp.UNDO);
		assertEquals("The main gatehouse's portcullis has stopped: finishing it would take 3 observer more than you approved, so I take no more for it;"
			+ " filling the stockpile won't help. /steward undo set_1 takes it up and gives back what it drew; then ask for it again and I'll put the new"
			+ " bill to you.", said);
		assertFalse(said.contains("maxBill") || said.contains("bill_cap"), "no Architect words");
		// a plain bill wait or a stage wait is not a cap wait
		assertNull(SettlementRunner.capStageWait(JsonParser.parseString("{\"architect_mc:waitsFor\": {\"kind\": \"bill\", \"say\": \"x waits\"}}").getAsJsonObject(), "set_1", MaterialRequests.TakeUp.UNDO));
		assertNull(SettlementRunner.capStageWait(JsonParser.parseString("{\"architect_mc:waitsFor\": {\"say\": \"the street waits for the wall\"}}").getAsJsonObject(), "set_1", MaterialRequests.TakeUp.UNDO));
		assertNull(SettlementRunner.capStageWait(null, "set_1", MaterialRequests.TakeUp.UNDO));
	}
}
