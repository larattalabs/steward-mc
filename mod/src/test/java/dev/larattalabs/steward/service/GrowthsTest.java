package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.service.Growths.Step;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A region town's growth (docs/GROWTH.md): its lot ids, its history tag, and what taking it up does in each state of the town. */
class GrowthsTest {
	private static VillageLayout.LotSpec spec(String id, String type) {
		return new VillageLayout.LotSpec(id, type, 9, 11, type, null, false, null);
	}

	@Test
	void aGrowthsLotsGetIdsTheTownHasNot() {
		// ProgramPlanner restarts house_1 for every build: an addition of a type the town has would repeat its id
		var renamed = Growths.rename(List.of(spec("house_1", "house"), spec("house_2", "house"), spec("library_1", "library")), "b-1762891234567-Ab");
		Set<String> ids = new HashSet<>();
		for (var l : renamed) {
			assertTrue(l.id().matches("[a-z][a-z0-9_]{0,47}"), l.id());
			assertFalse(l.id().matches("(house|library)_\\d+"), l.id());
			ids.add(l.id());
		}
		assertEquals(3, ids.size());
		assertEquals("house", renamed.get(0).type(), "the type, size and role stay");
		assertEquals(9, renamed.get(0).sizeX());
		assertEquals(renamed, Growths.rename(List.of(spec("house_1", "house"), spec("house_2", "house"), spec("library_1", "library")), "b-1762891234567-Ab"),
			"the same build gives the same ids (a re-plan, a restart)");
		assertNotEquals(renamed.get(0).id(), Growths.rename(List.of(spec("house_1", "house")), "b-1762891234999-Cd").get(0).id(), "another build, other ids");
		String longBuild = "b".repeat(200) + "123";
		for (var l : Growths.rename(List.of(spec("a_1", "a"), spec("a_2", "a")), longBuild)) assertTrue(l.id().length() <= 48, l.id());
	}

	@Test
	void theTagNamesTheRegionAndTheRid() {
		assertEquals("revision:rg_12:3", Growths.tag("rg_12", 3));
		var ref = Growths.parse("revision:rg_12:3").orElseThrow();
		assertEquals("rg_12", ref.region());
		assertEquals(3, ref.rid());
		assertEquals("revision:a:b:7", Growths.tag("a:b", 7));
		assertEquals("a:b", Growths.parse("revision:a:b:7").orElseThrow().region());
		for (String bad : new String[] {null, "region:rg_1", "g5", "revision:", "revision:rg_1", "revision:rg_1:", "revision::3", "revision:rg_1:x",
			"revision:rg_1:0"}) assertTrue(Growths.parse(bad).isEmpty(), String.valueOf(bad));
	}

	@Test
	void takingAGrowthUpUndoesOnlyItsOwnRevision() {
		// the head: undone to the position below
		var d = Growths.decide(false, null, 2, "PLACED", 2);
		assertEquals(Step.UNDO, d.step());
		assertEquals(1, d.toPosition());
		assertEquals(Step.UNDO, Growths.decide(false, null, 1, "PARTIAL", 1).step());
		assertEquals(Step.UNDO, Growths.decide(false, null, 1, "FAILED", 1).step());
		// a newer revision stands above it: the undo would take that one too
		assertEquals(Step.NEWER, Growths.decide(false, null, 3, "PLACED", 2).step());
		// the history moved below it (undone outside Steward, and another revision took the position): never an undo to a position by itself
		assertEquals(Step.NEWER, Growths.decide(false, null, 1, "PLACED", 2).step());
		// undone already, or never accepted: nothing to take
		assertEquals(Step.GONE, Growths.decide(false, null, 0, "UNDONE", 1).step());
		assertEquals(Step.GONE, Growths.decide(false, null, 1, "STALE", 2).step());
		assertEquals(Step.GONE, Growths.decide(true, null, 0, null, 0).step(), "the town is gone");
		// being removed is not gone: Architect may stop the removal and the town stands again
		assertEquals(Step.WAIT, Growths.decide(false, true, null, 1, "PLACED", 1).step());
		// no revision with the rid: nothing is asked
		assertEquals(Step.UNKNOWN, Growths.decide(false, null, 2, null, 0).step());
		// still placing, or the town busy: later
		assertEquals(Step.WAIT, Growths.decide(false, null, 1, "PLACING", 1).step());
		assertEquals(Step.WAIT, Growths.decide(false, null, 1, "ACCEPTED", 1).step());
		assertEquals(Step.WAIT, Growths.decide(false, "realise rv4", 1, "PLACED", 1).step());
		// its own take-up cut short: resumed by the same call, busy or not
		var resume = Growths.decide(false, "undo rv2", 2, "UNDOING", 2);
		assertEquals(Step.UNDO, resume.step());
		assertEquals(1, resume.toPosition());
	}

	@Test
	void anEndedRevisionIsPlacedPartialOrFailed() {
		for (String s : new String[] {"PLACED", "PARTIAL", "FAILED", "placed"}) assertTrue(Growths.ended(s), s);
		for (String s : new String[] {null, "ACCEPTED", "PLACING", "UNDOING", "UNDONE", "STALE"}) assertFalse(Growths.ended(s), String.valueOf(s));
	}
}
