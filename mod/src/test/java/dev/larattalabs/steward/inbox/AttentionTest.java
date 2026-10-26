package dev.larattalabs.steward.inbox;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.pipeline.Pipeline;
import dev.larattalabs.steward.pipeline.Pipeline.State;
import dev.larattalabs.steward.view.SettlementView;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The inbox entry, the nameplate and the settlement board say the same thing about what waits for the player (J2: one {@link Attention}). */
class AttentionTest {
	private static final long NOW = 1_800_000_000_000L;
	private static final Settlement S = Settlement.founded("set_4", "Stilt", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS,
		Difficulty.PATRON, 1L);

	private static ConceptCard card() {
		var in = AttentionTest.class.getClassLoader().getResourceAsStream("stilt_village.json");
		return ConceptCard.parse(JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
	}

	private static State designing() {
		Permission p = Permission.PROPOSALS;
		State s = Pipeline.step(State.start("set_4", card(), 30), new Pipeline.CardApproved(card()), p).next();
		s = Pipeline.step(s, new Pipeline.BibleDone(true, "b", 1, 1.2, null), p).next();
		return Pipeline.step(s, new Pipeline.BibleApproved(), p).next();
	}

	/** What each surface shows, from one attention: the entry (inbox, HUD), the nameplate status, the board's view. */
	private record Surfaces(boolean entry, boolean nameplate, boolean board, String nameplateLine, String boardLine, boolean entryHeld, long entryResets) {}

	private static Surfaces surfaces(State s, Attention a) {
		var entry = InboxModel.entry("set_4", "Stilt", s, List.of(), a);
		// the nameplate's status is the attention's line and need (StewardStatus.of)
		var view = SettlementView.of(S, true, List.of(), List.of(), List.of(), List.of(), a);
		return new Surfaces(entry.needsYou(), a.needsYou(), view.attention().needsYou(), a.line(), view.attention().line(), entry.held(), entry.limitResetsAt());
	}

	private static void agree(Surfaces x, boolean needsYou) {
		assertEquals(needsYou, x.entry(), "inbox entry");
		assertEquals(needsYou, x.nameplate(), "nameplate");
		assertEquals(needsYou, x.board(), "board");
		assertEquals(x.nameplateLine(), x.boardLine(), "the board says what the nameplate says");
	}

	@Test
	void massingsWaitingNeedThePlayerOnEverySurface() {
		State m = Pipeline.step(designing(), new Pipeline.GroupUpdate("g", "awaiting_approval", Map.of("cabin_1", "approval"), List.of("cabin_1"), 2, null),
			Permission.PROPOSALS).next();
		Attention a = Attention.of(m, 0, null, false, "", NOW);
		agree(surfaces(m, a), true);
		assertEquals("1 massing waits for you", a.line(), "the board no longer says only \"Being built\" while massings wait");
	}

	@Test
	void aBuildHeldByTheUsageLimitIsHeldEverywhereAndNeedsNoOne() {
		State s = designing();
		long resets = NOW + 3_600_000;
		Attention a = Attention.of(s, resets, null, false, "", NOW);
		assertEquals(Attention.Kind.HELD, a.kind());
		Surfaces x = surfaces(s, a);
		agree(x, false);
		assertTrue(x.entryHeld());
		assertEquals(resets, x.entryResets(), "the reset is a number, not a line's prefix");
		assertEquals(Attention.Kind.WORKING, Attention.of(s, NOW - 1, null, false, "", NOW).kind(), "a reset in the past holds nothing");
	}

	@Test
	void aHeldStageAndAnUpdateNeedThePlayerAndAnIdeaNever() {
		State s = designing();
		agree(surfaces(s, Attention.of(s, 0, "street", false, "", NOW)), true);
		Attention update = Attention.of(null, 0, null, true, "resting", NOW);
		assertTrue(update.needsYou());
		assertTrue(InboxModel.updates("set_4", "Stilt", List.of(new InboxModel.Lot("s1", "smithy", "update", false, true, "v2"))).needsYou());
		// an idea: in the inbox, never "needs you" (no "!", not counted by the HUD, no "needs you" toast)
		InboxModel.Entry ideas = InboxModel.proposals("set_4", "Stilt", List.of(new InboxModel.Lot("p_1", "a granary", "proposal", false, true, "about $3")));
		assertTrue(ideas.waiting(), "still a row the player may act on");
		assertFalse(ideas.needsYou());
		assertFalse(Attention.of(null, 0, null, false, "resting", NOW).needsYou());
		assertTrue(new InboxToasts().update(List.of(ideas)).isEmpty());
	}
}
