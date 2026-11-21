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
		// (WORDS-089) a bill cap is a module's bound, measured unless approved since: never "what you approved"
		assertEquals("its bill went past its limit", RefusalWords.reason(Reason.BILL_CAP));
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
		// Steward's own failure keeps its own words; anything else's message is never read
		assertEquals("the build could not be saved before placing", RefusalWords.of(new CompletionException(new RefusalWords.Own("the build could not be saved before placing"))));
		assertEquals(RefusalWords.GENERIC, RefusalWords.of(new IllegalStateException(RAW)));
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

	// ----------------------------------------------------------------- (raw-sweep)

	/** The plan schema's reason codes ({@code kit/schemas/plan.json $defs/reason.code}, Architect 0.83.0). */
	private static final List<String> PLAN_CODES = List.of("SCHEMA", "UNKNOWN_REF", "CYCLE", "EXTENSION_BOUND", "UNSUPPORTED", "NO_FEATURE", "NO_ROOM",
		"OUT_OF_CLAIM", "OVERLAP", "PROTECTED", "KEEP_OUT", "TOO_STEEP", "WATER", "UNREACHABLE", "TOLERANCE", "DEPENDENCY", "BUDGET", "CANCELLED", "LIMIT",
		"NO_GAP", "SPAN", "ANCHOR", "HEADROOM", "MOVER", "NO_COVER", "VOLUME", "LEAK", "FIRE", "VOCAB", "PALETTE", "SURVIVAL", "NO_CLIMB", "NO_PROGRAM",
		"AUTHORED_STALE");

	@Test
	void everyPlanCodeClassAndOverallHasWordsAndNoneIsItsToken() {
		for (String c : PLAN_CODES) {
			String w = RefusalWords.planCode(c);
			assertNotEquals(RefusalWords.GENERIC, w, c);
			assertFalse(w.contains(c), c + ": " + w);
		}
		assertEquals(RefusalWords.GENERIC, RefusalWords.planCode(RAW));
		for (String c : List.of("refused", "unsupported", "blocked", "malformed", "incomplete")) {
			String w = RefusalWords.planClass(c);
			assertNotEquals(RefusalWords.GENERIC, w, c);
			assertFalse(w.contains(c), c + ": " + w);
		}
		for (var o : dev.larattalabs.architect.api.PlanCheck.Overall.values()) assertFalse(RefusalWords.planOverall(o).contains(o.name()), o.name());
	}

	private static JsonObject part(String id, String cls, String code) {
		JsonObject p = new JsonObject();
		p.addProperty("part", id);
		p.addProperty("class", cls);
		p.addProperty("required", true);
		var reasons = new com.google.gson.JsonArray();
		if (code != null) {
			JsonObject r = new JsonObject();
			r.addProperty("code", code);
			r.addProperty("message", RAW);
			reasons.add(r);
		}
		p.add("reasons", reasons);
		return p;
	}

	private static dev.larattalabs.architect.api.PlanCheck check(dev.larattalabs.architect.api.PlanCheck.Overall o, String sha, JsonObject... parts) {
		return new dev.larattalabs.architect.api.PlanCheck("chk", "p", "s", "k", "v", o, List.of(parts), null, null, null, sha, List.of(RAW));
	}

	@Test
	void anUnbuildablePlanNamesItsPartsInTheStewardsWordsNeverArchitectsTokens() {
		var o = dev.larattalabs.architect.api.PlanCheck.Overall.REQUIRED_BLOCKED;
		String line = SettlementRunner.unbuildable(check(o, null, part("great_hall", "refused", "NO_ROOM"), part("mine_shaft", "blocked", null),
			part("tower", "refused", RAW)));
		assertEquals("the plan's great hall (there is no room left for it), mine shaft (something stands in its way), tower (it doesn't fit there) can't be built here", line);
		for (String token : List.of("NO_ROOM", "refused", "blocked", "REQUIRED_BLOCKED")) assertFalse(line.contains(token), token + ": " + line);
		clean(line);
		// a tunnel past the volume pass's caps keeps its hint, from the typed code
		String tunnel = SettlementRunner.unbuildable(check(o, null, part("tunnel", "incomplete", "VOLUME")));
		assertTrue(tunnel.endsWith("(Architect couldn't read all the rock a tunnel goes through: stand nearer it and start again)"), tunnel);
		assertFalse(tunnel.contains("VOLUME") || tunnel.contains("incomplete"), tunnel);
		// no stuck part and no artifact: the overall answer in words, never its enum name
		String none = SettlementRunner.unbuildable(check(dev.larattalabs.architect.api.PlanCheck.Overall.INCOMPLETE, null));
		assertEquals("Architect ran out of time checking the plan", none);
		assertNull(SettlementRunner.unbuildable(check(dev.larattalabs.architect.api.PlanCheck.Overall.READY, "sha1")));
	}

	@Test
	void aBesideRefusalSaysTheStewardsWordsNeverArchitectsMessage() {
		var join = new ArchitectRefused(Reason.OTHER, "NO_JOIN [apart] " + RAW);
		assertEquals("it would not touch the town.", Districts.besideRefusal(new CompletionException(join)));
		var overlap = new ArchitectRefused(Reason.OVERLAP, RAW, "OVERLAP");
		assertEquals("something already stands there.", Districts.besideRefusal(new CompletionException(overlap)));
		var typed = new ArchitectRefused(Reason.TOO_STEEP, RAW);
		assertEquals("the ground is too steep.", Districts.besideRefusal(new CompletionException(typed)));
		assertEquals(RefusalWords.GENERIC + ".", Districts.besideRefusal(new CompletionException(new IllegalStateException(RAW))));
		assertEquals("something already stands there.", Districts.besideRefusal("OVERLAP", RAW, Reason.OVERLAP));
		assertEquals("it can't join the town's street from there.", Districts.besideRefusal("NO_JOIN [edge]", RAW, null));
		assertEquals("there is lava by it.", Districts.besideRefusal(RAW, RAW, Reason.LAVA));
		for (String l : List.of(Districts.besideRefusal(new CompletionException(join)), Districts.besideRefusal(new CompletionException(overlap)),
			Districts.besideRefusal(RAW, RAW, null))) clean(l);
	}

	@Test
	void aFailedCardIsSaidInTheStewardsWordsNeverTheJobsText() {
		var cost = new dev.larattalabs.architect.api.Cost(0, 0, 0, 0, 0, 0);
		for (String e : List.of("the concept-card job did not finish: " + RAW, "the concept card could not be read: " + RAW, "the concept-card job returned no card")) {
			clean(new dev.larattalabs.steward.gateway.CardResult(null, e, cost).said());
		}
		assertEquals("reading it ran past its budget", new dev.larattalabs.steward.gateway.CardResult(null, "the concept-card job hit its budget", cost).said());
		assertEquals("Claude didn't finish reading it", new dev.larattalabs.steward.gateway.CardResult(null, "the concept-card job did not finish: " + RAW, cost).said());
	}

	@Test
	void aRefusedFollowNeverSaysArchitectsCodeOrMessage() {
		var r = new dev.larattalabs.architect.api.Designs.FollowResult("hut_c1", false, 1, 1, 2, "SENTINEL_code", false, RAW);
		String line = dev.larattalabs.steward.gateway.FollowPlanner.refusalText("hut (h2)", "hut", 2, r);
		assertEquals("The hut (h2) stays as it is: the hut's new design (v2) could not be followed.", line);
		clean(line);
		String call = dev.larattalabs.steward.gateway.FollowPlanner.callRefusedText("hut", 2, RefusalWords.of(new CompletionException(new ArchitectRefused(Reason.SITE_BUSY, RAW))));
		assertEquals("The hut's copies can't follow its new design (v2): the build under it is still being worked on.", call);
	}

	@Test
	void aRefusedChangeRequestOrLandLookSaysTheTypedReasonNeverArchitectsText() {
		var refused = new ArchitectRefused(Reason.SITE_BUSY, RAW, RAW);
		assertEquals("the build under it is still being worked on", ChangeRequests.refusal(new CompletionException(refused)));
		clean(ChangeRequests.refusal(new CompletionException(refused)));
		clean(ChangeRequests.refusal(new RuntimeException(RAW)));
		var look = LandChecks.refusedLook("Hamlet", false, new ArchitectRefused(Reason.JOURNAL_UNAVAILABLE, RAW));
		var note = dev.larattalabs.steward.gateway.LandWords.note("Hamlet", List.of(look), x -> null, true);
		for (String l : note.lines()) clean(l);
		clean(note.headline());
		assertEquals(List.of("I couldn't look over Hamlet: " + RefusalWords.GENERIC), note.lines());
	}

	@Test
	void savingOffIsSaidByItsTypedDetail() {
		// (SOFF-WORDS) Architect SOFF2's hold: NOT_ALLOWED with detail saving_off:<dims>; the dimension list and the message are never said
		var held = new ArchitectRefused(Reason.NOT_ALLOWED, RAW, "saving_off:minecraft:overworld,minecraft:the_nether");
		String line = RefusalWords.of(new CompletionException(held));
		assertEquals(RefusalWords.SAVING_OFF, line);
		clean(line);
		assertEquals(RefusalWords.SAVING_OFF, RefusalWords.of(new ArchitectRefused(Reason.NOT_ALLOWED, RAW, "saving_off")));
		// before SOFF2 (no detail), and another NOT_ALLOWED detail, keep the reason's line
		assertEquals("that isn't allowed in this world", RefusalWords.of(new ArchitectRefused(Reason.NOT_ALLOWED, RAW)));
		assertEquals("that isn't allowed in this world", RefusalWords.of(new ArchitectRefused(Reason.NOT_ALLOWED, RAW, "saving_offline")));
		assertFalse(RefusalWords.savingOff(null));
	}
}
