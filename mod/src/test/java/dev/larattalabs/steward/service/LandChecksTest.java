package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.gateway.LandWords;
import dev.larattalabs.steward.inbox.InboxModel;
import dev.larattalabs.steward.inbox.InboxToasts;
import java.util.List;
import org.junit.jupiter.api.Test;

class LandChecksTest {
	/** A complete look with this damage signature (empty: none) and the player's blocks. */
	private static LandWords.Note note(String sig, long placed) {
		boolean real = !sig.isEmpty() || placed > 0;
		return new LandWords.Note(real ? "I looked over Hamlet: ..." : "I found nothing broken or changed in what we built in Hamlet.",
			real ? List.of("The smithy: ...") : List.of(), sig.isEmpty() ? 0 : 3, placed, real, true, sig);
	}

	private static LandWords.Note gap(String sig) {
		return new LandWords.Note("I found nothing broken or changed in what I could see of Hamlet.", List.of("I couldn't see 40 blocks ..."), 0, 0, !sig.isEmpty(),
			false, sig);
	}

	@Test
	void anAutomaticLookSpeaksOnlyWhenTheDamageIsNew() {
		var d = LandChecks.decide(note("r|lot:smithy=3", 0), false, null, false);
		assertTrue(d.row() && d.say());
		assertEquals("r|lot:smithy=3", d.told());
		// the same damage again: nothing said, the row (up) shows the latest look
		d = LandChecks.decide(note("r|lot:smithy=3", 1), true, "r|lot:smithy=3", false);
		assertTrue(d.row());
		assertFalse(d.say());
		// dismissed (no row) and the same damage: no row comes back
		d = LandChecks.decide(note("r|lot:smithy=3", 0), false, "r|lot:smithy=3", false);
		assertFalse(d.row() || d.say());
		// the smithy repaired and the chapel broken by as many blocks: told
		d = LandChecks.decide(note("r|lot:chapel=3", 0), false, "r|lot:smithy=3", false);
		assertTrue(d.row() && d.say());
	}

	@Test
	void thePlayersOwnBlocksNeverSpeakByThemselves() {
		var d = LandChecks.decide(note("", 12), false, null, false);
		assertFalse(d.row() || d.say());
		assertEquals("", d.told());
	}

	@Test
	void repairedClearsTheRow() {
		var d = LandChecks.decide(note("", 0), true, "r|lot:smithy=3", false);
		assertTrue(d.clear());
		assertFalse(d.row() || d.say());
		assertEquals("", d.told());
	}

	@Test
	void anAutomaticLookWithAGapChangesNothing() {
		// the damaged district unloaded: the row stays, what was told stays, nothing is said
		var d = LandChecks.decide(gap(""), true, "r|lot:smithy=3", false);
		assertTrue(d.row());
		assertFalse(d.clear() || d.say());
		assertEquals("r|lot:smithy=3", d.told());
		d = LandChecks.decide(gap("r|lot:chapel=1"), false, "r|lot:smithy=3", false);
		assertFalse(d.row() || d.say());
		assertEquals("r|lot:smithy=3", d.told());
	}

	@Test
	void askedTheRowIsReplacedButAGapIsNotCountedAsTold() {
		var d = LandChecks.decide(note("r|lot:smithy=3", 0), false, "r|lot:smithy=3", true);
		assertTrue(d.row());
		assertFalse(d.say());
		d = LandChecks.decide(note("", 2), false, null, true);
		assertTrue(d.row());
		// nothing seen whole: the row goes; nothing seen with a gap: a known row stays as it is
		assertTrue(LandChecks.decide(note("", 0), true, "x", true).clear());
		d = LandChecks.decide(gap(""), true, "r|lot:smithy=3", true);
		assertTrue(d.row());
		assertFalse(d.clear());
		assertEquals("r|lot:smithy=3", d.told());
	}

	@Test
	void theRowShowsWhenItWasLookedAndAtMostSixLines() {
		var lines = new java.util.ArrayList<String>();
		for (int i = 0; i < 9; i++) lines.add("line " + i);
		var k = new LandChecks.Kept(new LandWords.Note("h", lines, 9, 0, true, true, "s"), 0);
		var row = LandChecks.rowLines(k);
		assertTrue(row.get(0).startsWith("Looked over at "));
		assertEquals(1 + LandChecks.ROW_LINES, row.size());
		assertEquals("And 4 more: ask me what changed.", row.getLast());
	}

	@Test
	void theLandRowNeedsNoOneAndHasItsOwnKey() {
		var e = InboxModel.land("set_4", "Hamlet", "I looked over Hamlet: ...", List.of("The smithy: 1 block broken or changed."));
		assertFalse(e.needsYou());
		assertFalse(e.waiting());
		assertFalse(e.ended());
		assertEquals("set_4#land", e.key());
		assertFalse(new InboxModel.Entry("set_4", "Hamlet", InboxModel.LAND, "h", List.of(), List.of(), 0, 0, 0).needsYou());
		assertTrue(new InboxToasts().update(List.of(e)).isEmpty());
	}
}
