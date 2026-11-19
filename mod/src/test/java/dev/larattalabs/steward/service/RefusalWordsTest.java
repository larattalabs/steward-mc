package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectRefused;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.architect.api.RegionRefused;
import dev.larattalabs.architect.api.Refusal;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * (place-words) The placement and offer paths say the steward's words for every typed refusal, never Architect's message, say or exception text: a
 * sentinel raw string is asserted absent from every line a player sees.
 */
class RefusalWordsTest {
	/** Architect's raw text: never shown. */
	private static final String RAW = "SENTINEL-RAW r.0.0.mca entry_9f3c lot_3 chest 'Grandma's Chest'";

	private static void clean(String line) {
		assertNotNull(line);
		assertFalse(line.contains("SENTINEL"), line);
		assertFalse(line.contains("r.0.0.mca"), line);
		assertFalse(line.contains("Grandma"), line);
	}

	@Test
	void everyReasonHasWordsAndNoneIsItsEnumName() {
		for (Reason r : Reason.values()) {
			String w = RefusalWords.reason(r);
			assertFalse(w.isBlank(), r.name());
			assertFalse(w.contains(r.name()), r.name() + ": " + w);
		}
		assertEquals(RefusalWords.GENERIC, RefusalWords.reason(null));
		assertEquals(RefusalWords.GENERIC, RefusalWords.reason(Reason.OTHER));
		assertEquals("someone is standing where it goes", RefusalWords.reason(Reason.PLAYER_IN_BOX));
	}

	@Test
	void aFailedFutureIsSaidByItsTypedReasonWhereverItIsInTheChain() {
		var refused = new ArchitectRefused(Reason.BILL_CAP, RAW, RAW);
		assertEquals(RefusalWords.reason(Reason.BILL_CAP), RefusalWords.of(new CompletionException(refused)));
		assertEquals(RefusalWords.reason(Reason.BILL_CAP), RefusalWords.of(new RuntimeException(RAW, new CompletionException(refused))));
		var region = new RegionRefused(Reason.DRIFTED, RAW);
		assertEquals(RefusalWords.reason(Reason.DRIFTED), RefusalWords.of(new CompletionException(region)));
		assertEquals(RefusalWords.GENERIC, RefusalWords.of(new CompletionException(new IllegalStateException(RAW))));
		assertEquals(RefusalWords.NO_ANSWER, RefusalWords.of(new CompletionException(new java.util.concurrent.TimeoutException(RAW))));
		assertEquals(RefusalWords.NO_ANSWER, RefusalWords.of(null));
		clean(RefusalWords.of(new CompletionException(refused)));
	}

	@Test
	void aRefusedFitNamesEachTypedReasonOnceNeverItsMessage() {
		var refusals = List.of(new Refusal(Reason.LOT_TOO_SMALL, RAW), new Refusal(Reason.LOT_TOO_SMALL, RAW + " again"), new Refusal(Reason.LAVA, RAW));
		String line = SettlementRunner.fitRefusedLine("lot_3", refusals);
		assertEquals("Lot lot_3 can't take its building: it doesn't fit its lot; there is lava by it.", line);
		clean(line.replace("lot_3", ""));
		assertEquals("Lot lot_1 can't take its building: " + RefusalWords.GENERIC + ".", SettlementRunner.fitRefusedLine("lot_1", List.of()));
	}

	@Test
	void aModuleNotMadeIsSaidByItsTypedDetail() {
		// Architect's refusal, as ModuleInstalls writes it (reason:detail)
		String line = SettlementRunner.moduleNotMadeLine("site_busy:held(other) tx-4: " + RAW, RAW, null);
		assertEquals("Not placed: the module couldn't be made for its lot (the build under it is still being worked on). Nothing was placed.", line);
		// the checker's rule id (ProgramRefused), an error, and a lost answer
		assertTrue(SettlementRunner.moduleNotMadeLine("site_keep", RAW, null).contains("it didn't pass Architect's checks"));
		assertTrue(SettlementRunner.moduleNotMadeLine("error", "java.lang.IllegalStateException: " + RAW, null).contains(RefusalWords.GENERIC));
		assertTrue(SettlementRunner.moduleNotMadeLine(null, null, new CompletionException(new ArchitectRefused(Reason.WORLD_STOPPED, RAW))).contains("the world stopped"));
		assertTrue(SettlementRunner.moduleNotMadeLine(null, null, new CompletionException(new IllegalStateException(RAW))).contains(RefusalWords.GENERIC));
		for (String d : new String[] {"site_busy:" + RAW, "site_keep", "error", "", "bill_cap:lot:" + RAW}) clean(SettlementRunner.moduleNotMadeLine(d, RAW, null));
		// Steward's own refusals keep their own words
		assertEquals("Not placed: the module couldn't be made for its lot (a working crop farm's size isn't one that was measured). Nothing was placed.",
			SettlementRunner.moduleNotMadeLine("params", "a working crop farm's size isn't one that was measured.", null));
	}

	@Test
	void aBatchItemThatFailedIsSaidByItsTypedReason() {
		String line = SettlementRunner.itemNotPlacedLine("lot_3", Reason.OCCUPIED);
		assertEquals("Not placed: lot_3: an animal or a villager is where it goes.", line);
		assertEquals("Not placed: lot_4: " + RefusalWords.GENERIC + ".", SettlementRunner.itemNotPlacedLine("lot_4", null));
	}

	@Test
	void aQuoteThatLeftAPartUnbilledOrInexactIsSaidInTheStewardsWords() {
		// Architect's notes, classed by ModulePlacement.quoteCheck
		var unbilled = ModulePlacement.quoteCheck(false, List.of("lot:lot_3: " + RAW + " not billed", "road:r1: " + RAW + " could not be read"));
		assertNotNull(unbilled.refused());
		String said = RefusalWords.unbilled(unbilled.refused());
		assertEquals("part of it has no bill and part of it couldn't be read", said);
		clean(said);
		var drift = ModulePlacement.quoteCheck(false, List.of("lot:lot_3: " + RAW + " may differ (drift)", "tiles: 4 paid terrain cell(s) are in chunks not loaded " + RAW));
		assertNotNull(drift.ask());
		String asked = RefusalWords.inexact(drift.ask());
		assertEquals("part of the land isn't loaded and the land may change before it is built", asked);
		clean(asked);
		assertEquals("its quote is not exact", RefusalWords.inexact(ModulePlacement.quoteCheck(false, List.of()).ask()));
		// the single module path's hold says the same words
		var order = ModuleSavesTest.order().accepted(false, "module.fixture_plot");
		String why = SettlementRunner.holdWhy(order, List.of(), List.of(), drift);
		assertEquals("Architect can't quote the bill exactly (" + asked + "), so I ask first.", why);
		clean(why);
		// the composed path's hold, given the steward's words by the runner
		var held = dev.larattalabs.steward.module.ComposedModules.held(com.google.gson.JsonParser.parseString("{\"lot:lot_3\": {\"minecraft:dirt\": 1}}").getAsJsonObject(),
			java.util.Map.of("lot_3", order), RefusalWords.inexact(drift.ask()));
		assertNotNull(held.ask());
		clean(held.ask());
	}

	@Test
	void aComposeWithNoPlanIsSaidByItsTypedRefusedCode() {
		JsonObject r = new JsonObject();
		r.addProperty("outcome", "NO_PLAN");
		r.addProperty("refused", "NOT_ALLOWED: " + RAW);
		r.addProperty("summary", RAW);
		r.addProperty("reason", RAW);
		assertEquals("No plan was composed: towns can only be built in the Overworld for now. The build takes today's layout.", Composer.noPlanLine(r));
		assertEquals("towns can only be built in the Overworld for now", PendingOffers.noPlanWhy(r));
		JsonObject untyped = new JsonObject();
		untyped.addProperty("summary", RAW);
		untyped.addProperty("refused", RAW);
		assertEquals("The plan could not be composed: the build takes today's layout.", Composer.noPlanLine(untyped));
		assertEquals("Architect composed no plan for it", PendingOffers.noPlanWhy(untyped));
		assertEquals("The plan could not be composed: the build takes today's layout.", Composer.noPlanLine(null));
		JsonObject region = new JsonObject();
		region.addProperty("refused", "REGION_LIMIT: " + RAW);
		assertEquals("it is too big for the claim", PendingOffers.noPlanWhy(region));
		for (JsonObject x : new JsonObject[] {r, untyped, region}) {
			clean(Composer.noPlanLine(x));
			clean(PendingOffers.noPlanWhy(x));
		}
	}

	@Test
	void aDesignGroupCancelWithNoAnswerSaysNoArchitectText() {
		clean(SettlementRunner.CANCEL_GROUP_WAITS);
		assertTrue(SettlementRunner.CANCEL_GROUP_WAITS.startsWith("Could not reach Architect to stop the design group."));
	}
}
