package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** (slice A2, review should-fix 1) A growth's removal is logged once: a replay after a crash finds its placing entry retired. */
class UndoRetiredTest {
	private static final Claim TOWN = new Claim("minecraft:overworld", 0, 0, 64, -64, 320);
	private static final Settlement.LogEntry GROWTH = new Settlement.LogEntry(5L, Settlement.Kind.PROJECT_PLACED, "library", List.of("s1", "s2"), "revision:rg1:2");

	private static Settlement base() {
		return Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L)
			.withLog(new Settlement.LogEntry(2L, Settlement.Kind.PROJECT_PLACED, "town", List.of("t1"), "region:rg1")).withLog(GROWTH);
	}

	@Test
	void aGrowthIsRetiredOnlyOnceARemovalNamesAllItsSites() {
		assertFalse(Undo.retired(base(), GROWTH), "standing");
		var partly = base().withLog(new Settlement.LogEntry(6L, Settlement.Kind.PROJECT_REMOVED, "undid", List.of("s1")));
		assertFalse(Undo.retired(partly, GROWTH));
		var whole = base().withLog(new Settlement.LogEntry(6L, Settlement.Kind.PROJECT_REMOVED, "undid", List.of("s1", "s2")));
		assertTrue(Undo.retired(whole, GROWTH));
		// a removal before it (an older build's) never retires it
		var older = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L)
			.withLog(new Settlement.LogEntry(2L, Settlement.Kind.PROJECT_REMOVED, "undid", List.of("s1", "s2"))).withLog(GROWTH);
		assertFalse(Undo.retired(older, GROWTH));
		// the copy lastUndoable hands out (same time, kind and tag) is found too
		var copy = new Settlement.LogEntry(5L, Settlement.Kind.PROJECT_PLACED, "library", List.of("s1", "s2"), "revision:rg1:2");
		assertTrue(Undo.retired(whole, copy));
	}
}
