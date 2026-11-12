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

	@Test
	void growthsAreUndoneHeadFirstWhateverOrderTheyWereLoggedIn() {
		// (fix round 2) revision 2 logged normally, then the sweep logs the orphan revision 1 late: the newest entry is 1, but 2 is the head
		var two = new Settlement.LogEntry(6L, Settlement.Kind.PROJECT_PLACED, "school", List.of("s3"), "revision:rg1:2");
		var one = new Settlement.LogEntry(7L, Settlement.Kind.PROJECT_PLACED, "An addition placed after its build ended", List.of("s1"), "revision:rg1:1");
		var s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L)
			.withLog(new Settlement.LogEntry(2L, Settlement.Kind.PROJECT_PLACED, "town", List.of("t1"), "region:rg1")).withLog(two).withLog(one);
		var newest = s.lastUndoable();
		assertEquals("revision:rg1:1", newest.get().siteGroup());
		assertEquals("revision:rg1:2", Undo.headFirst(s, newest, 2).get().siteGroup(), "the head's growth first");
		assertEquals("revision:rg1:1", Undo.headFirst(s, newest, 1).get().siteGroup(), "once 2 is undone, 1 is the head");
		// the head's growth undone already (retired): the newest entry is kept
		var undone = s.withLog(new Settlement.LogEntry(8L, Settlement.Kind.PROJECT_REMOVED, "undid", List.of("s3")));
		assertEquals("revision:rg1:1", Undo.headFirst(undone, undone.lastUndoable(), 2).get().siteGroup());
		// the head is a revision Steward never logged: the newest entry is kept (its undo says a newer change stands)
		assertEquals("revision:rg1:1", Undo.headFirst(s, newest, 5).get().siteGroup());
		// not a growth: untouched
		var town = java.util.Optional.of(new Settlement.LogEntry(2L, Settlement.Kind.PROJECT_PLACED, "town", List.of("t1"), "region:rg1"));
		assertSame(town, Undo.headFirst(s, town, 2));
	}

	@Test
	void anOrphansNoteKeepsItsObligationUntilClosed() {
		var s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L);
		assertFalse(VillageSweep.orphanOpen(s, "revision:rg1:3"));
		var noted = s.withLog(VillageSweep.orphanNote("revision:rg1:3"));
		assertTrue(VillageSweep.orphanOpen(noted, "revision:rg1:3"));
		assertFalse(VillageSweep.orphanOpen(noted, "revision:rg1:4"), "another growth's note");
		var closed = noted.withLog(new Settlement.LogEntry(9L, Settlement.Kind.NOTE, VillageSweep.ORPHAN_CLOSED + " (revision:rg1:3): taken up", List.of(), "revision:rg1:3"));
		assertFalse(VillageSweep.orphanOpen(closed, "revision:rg1:3"));
		// the note places nothing and is never undoable
		assertTrue(noted.lastUndoable().isEmpty());
	}
}
