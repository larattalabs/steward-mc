package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * (slice VSR) The world-start village sweep takes up one region per settlement at a time: the next is chosen once the first has answered, each region is
 * asked once, its entry logged once, and a removal whose answer a restart lost is retired at the next start with nothing asked of Architect again.
 */
class VillageSweepOnceTest {
	private static final Claim TOWN = new Claim("minecraft:overworld", 0, 0, 64, -64, 320);

	/** A faked world for the sweep: the settlement's history, Architect's regions, and every removal asked (each answers when the test says). */
	private static final class World {
		Settlement s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L);
		final Map<String, VillageSweep.Village> regions = new LinkedHashMap<>();
		/** Removals asked of Architect, in order (a refund each, in survival). */
		final List<String> removals = new ArrayList<>();
		/** Removals asked and not yet answered. */
		final Map<String, Consumer<Boolean>> inFlight = new LinkedHashMap<>();
		/** Duplicate rows Architect's list may give (a region seen twice). */
		final List<String> extraRows = new ArrayList<>();
		Set<String> tried = new HashSet<>();
		Set<String> holding = new HashSet<>();
		/** Regions a player stands in (Architect would hold their removal), and the settlement passed over for one (the 10 s tick asks again). */
		final Set<String> occupied = new HashSet<>();
		boolean held;
		long t = 10;

		void region(String id, String build) {
			regions.put(id, new VillageSweep.Village(id, false, false, false, build));
		}

		List<VillageSweep.Village> list() {
			List<VillageSweep.Village> out = new ArrayList<>(regions.values());
			for (String id : extraRows) out.add(regions.get(id));
			return out;
		}

		/** One world start's sweep of the settlement, as VillageSweep.villages runs it. */
		void start() {
			VillageSweep.chain(s.id(), holding, () -> VillageSweep.nextFree(s, list(), b -> false, tried, occupied::contains, id -> held = true), tried, this::takeUp);
		}

		/** VillageSweep.takeUp and Undo.region as the sweep sees them: logged first, a removal asked (none for a tombstone), retired on its answer. */
		void takeUp(String id, Consumer<Boolean> done) {
			var entry = VillageSweep.entryOf(s, id);
			if (entry.isEmpty()) {
				s = s.withLog(new Settlement.LogEntry(t++, Settlement.Kind.PROJECT_PLACED, VillageSweep.TAKING_UP, List.of(id + "_a", id + "_b"), "region:" + id));
				entry = VillageSweep.entryOf(s, id);
			}
			var placed = entry.get();
			if (regions.get(id).removed()) {
				if (!Undo.retired(s, placed)) s = s.withLog(new Settlement.LogEntry(t++, Settlement.Kind.PROJECT_REMOVED, "Undid", placed.siteIds()));
				done.accept(true);
				return;
			}
			removals.add(id);
			inFlight.put(id, ok -> {
				if (Boolean.TRUE.equals(ok)) {
					regions.put(id, new VillageSweep.Village(id, true, false, false, regions.get(id).build()));
					s = s.withLog(new Settlement.LogEntry(t++, Settlement.Kind.PROJECT_REMOVED, "Undid", placed.siteIds()));
				}
				done.accept(ok);
			});
		}

		void answer(String id, Boolean ok) {
			inFlight.remove(id).accept(ok);
		}

		/** The game stops and starts again: this session's marks go (a lost answer never comes). */
		void restart() {
			inFlight.clear();
			tried = new HashSet<>();
			holding = new HashSet<>();
		}

		long entries(Settlement.Kind kind, String id) {
			return s.log().stream().filter(e -> e.kind() == kind && ("region:" + id).equals(e.siteGroup())).count();
		}

		long removedEntries(String id) {
			return s.log().stream().filter(e -> e.kind() == Settlement.Kind.PROJECT_REMOVED && e.siteIds().contains(id + "_a")).count();
		}
	}

	@Test
	void twoOrphanedRegionsAreTakenUpOneAfterTheOther() {
		var w = new World();
		w.region("rgA", "b100");
		w.region("rgB", "b200");
		w.start();
		assertEquals(List.of("rgA"), w.removals, "one removal at a time");
		assertEquals(1, w.inFlight.size());
		// a second sweep while the first is in flight (a tick, another caller) starts nothing
		w.start();
		assertEquals(List.of("rgA"), w.removals);
		w.answer("rgA", true);
		assertEquals(List.of("rgA", "rgB"), w.removals, "the next once the first answered");
		assertEquals(1, w.inFlight.size());
		w.answer("rgB", true);
		assertTrue(w.inFlight.isEmpty());
		for (String id : List.of("rgA", "rgB")) {
			assertEquals(1, w.entries(Settlement.Kind.PROJECT_PLACED, id), id + " logged once");
			assertEquals(1, w.removedEntries(id), id + " retired once");
		}
		w.start();
		assertEquals(2, w.removals.size(), "nothing left to take up");
	}

	@Test
	void aRegionSeenTwiceIsTakenUpOnce() {
		var w = new World();
		w.region("rgA", "b100");
		w.extraRows.add("rgA");
		w.start();
		w.answer("rgA", true);
		assertEquals(List.of("rgA"), w.removals);
		assertEquals(1, w.entries(Settlement.Kind.PROJECT_PLACED, "rgA"));
		assertEquals(1, w.removedEntries("rgA"));
		// refused: not asked again this start (no spin), the next region goes on; asked again at the next start
		var r = new World();
		r.region("rgA", "b100");
		r.extraRows.add("rgA");
		r.region("rgB", "b200");
		r.start();
		r.answer("rgA", false);
		assertEquals(List.of("rgA", "rgB"), r.removals, "a refused region is not asked twice in one start");
		r.answer("rgB", true);
		assertEquals(2, r.removals.size());
		r.restart();
		// its entry stands (logged before it was refused): the sweep's own take-up, asked again at the next start under the same entry
		r.start();
		assertEquals(List.of("rgA", "rgB", "rgA"), r.removals);
		r.answer("rgA", true);
		assertEquals(1, r.entries(Settlement.Kind.PROJECT_PLACED, "rgA"), "logged once");
		assertEquals(1, r.removedEntries("rgA"));
		r.restart();
		r.start();
		assertEquals(3, r.removals.size(), "nothing left");
	}

	@Test
	void aRemovalWhoseAnswerARestartLostIsRetiredOnceWithNoSecondRemoval() {
		var w = new World();
		w.region("rgA", "b100");
		w.start();
		assertEquals(List.of("rgA"), w.removals);
		// Architect removed it (its refund paid), but the game stopped before the answer: the entry stands, the region is a tombstone
		w.regions.put("rgA", new VillageSweep.Village("rgA", true, false, false, "b100"));
		w.restart();
		w.start();
		assertEquals(List.of("rgA"), w.removals, "no second removal (no second refund)");
		assertEquals(1, w.entries(Settlement.Kind.PROJECT_PLACED, "rgA"));
		assertEquals(1, w.removedEntries("rgA"), "retired once at the next start");
		w.restart();
		w.start();
		assertEquals(1, w.removedEntries("rgA"), "never twice");
		assertEquals(1, w.removals.size());
	}

	@Test
	void theDecisionLeavesWhatItMustLeave() {
		var s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L)
			.withLog(new Settlement.LogEntry(2L, Settlement.Kind.PROJECT_PLACED, "Hamlet", List.of("x1"), "region:held"))
			.withLog(new Settlement.LogEntry(3L, Settlement.Kind.PROJECT_PLACED, "Hamlet", List.of("x2"), "region:gone"));
		List<VillageSweep.Village> rows = List.of(
			new VillageSweep.Village("theirs", false, false, false, null),
			new VillageSweep.Village("spike", false, false, false, "spike"),
			new VillageSweep.Village("adopted", false, false, true, "b1"),
			new VillageSweep.Village("running", false, false, false, "b2"),
			new VillageSweep.Village("held", false, false, false, "b3"),
			// removed with a build's own entry standing (not the sweep's): undo's to retire, never the sweep's
			new VillageSweep.Village("gone", true, false, false, "b4"),
			new VillageSweep.Village("removed", true, false, false, "b5"));
		assertEquals(Optional.empty(), VillageSweep.nextVillage(s, rows, "b2"::equals, Set.of()));
		var orphan = new VillageSweep.Village("orphan", false, false, false, "b6");
		var more = new ArrayList<>(rows);
		more.add(orphan);
		assertEquals(Optional.of("orphan"), VillageSweep.nextVillage(s, more, "b2"::equals, Set.of()));
		assertEquals(Optional.empty(), VillageSweep.nextVillage(s, more, "b2"::equals, Set.of("orphan")), "tried this start");
	}

	@Test
	void nothingStartsWhileARemovalArchitectResumedIsInFlight() {
		var w = new World();
		w.region("rgA", "b100");
		w.region("rgB", "b200");
		w.regions.put("rgA", new VillageSweep.Village("rgA", false, true, false, "b100"));
		w.start();
		assertTrue(w.removals.isEmpty(), "rgA is being removed (resumed at this start): rgB waits");
		w.regions.put("rgA", new VillageSweep.Village("rgA", true, false, false, "b100"));
		w.restart();
		w.start();
		assertEquals(List.of("rgB"), w.removals, "the next start takes rgB up");
	}

	@Test
	void aRegionAPlayerStandsInIsNotAskedNorBusyAndIsTakenUpOnceTheyLeave() {
		var w = new World();
		w.region("rgA", "b100");
		w.region("rgB", "b200");
		w.occupied.add("rgA");
		w.start();
		// rgA is passed over (nothing asked: Architect holds nothing, the settlement is not busy for it); rgB goes on
		assertEquals(List.of("rgB"), w.removals);
		assertTrue(w.held, "the tick is to ask again");
		assertFalse(w.tried.contains("rgA"), "not tried: chosen again later");
		w.answer("rgB", true);
		assertTrue(w.holding.isEmpty() && w.inFlight.isEmpty(), "nothing in flight, nothing held");
		assertEquals(0, w.entries(Settlement.Kind.PROJECT_PLACED, "rgA"), "nothing logged for it yet");
		// the 10 s tick while the player is still there: still nothing
		w.held = false;
		w.start();
		assertEquals(List.of("rgB"), w.removals);
		assertTrue(w.held);
		// they leave: the next tick takes it up, once
		w.occupied.clear();
		w.start();
		assertEquals(List.of("rgB", "rgA"), w.removals);
		w.start();
		assertEquals(2, w.removals.size(), "no second removal while the first is in flight");
		w.answer("rgA", true);
		w.start();
		assertEquals(2, w.removals.size(), "nor after");
		assertEquals(1, w.entries(Settlement.Kind.PROJECT_PLACED, "rgA"));
		assertEquals(1, w.removedEntries("rgA"));
	}

	@Test
	void aPositionInTheClaimOrABlockAroundItIsInside() {
		int[] box = { 0, 60, 0, 9, 70, 9 };
		assertTrue(VillageSweep.inside(box, List.of(new double[] { 5.5, 64, 5.5 })));
		assertTrue(VillageSweep.inside(box, List.of(new double[] { -0.5, 64, 10.9 })), "a block around it");
		assertFalse(VillageSweep.inside(box, List.of(new double[] { -1.5, 64, 5 })));
		assertFalse(VillageSweep.inside(box, List.of(new double[] { 5, 64, 11.1 })));
		assertFalse(VillageSweep.inside(box, List.of()));
	}

	@Test
	void theSweepsOwnStandingTakeUpsAreFoundByTheirEntries() {
		var s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L)
			.withLog(new Settlement.LogEntry(2L, Settlement.Kind.PROJECT_PLACED, "Hamlet", List.of("x1"), "region:built"))
			.withLog(new Settlement.LogEntry(3L, Settlement.Kind.PROJECT_PLACED, VillageSweep.TAKING_UP, List.of("y1"), "region:lost"))
			.withLog(new Settlement.LogEntry(4L, Settlement.Kind.PROJECT_PLACED, VillageSweep.TAKING_UP, List.of("z1"), "region:done"))
			.withLog(new Settlement.LogEntry(5L, Settlement.Kind.PROJECT_REMOVED, "Undid", List.of("z1")));
		assertEquals(List.of("lost"), VillageSweep.standingTakeUps(s));
	}

	@Test
	void aTakeUpRefusedBeforeItReturnsGoesOnWithoutHoldingTheSettlement() {
		Set<String> holding = new HashSet<>();
		Set<String> tried = new HashSet<>();
		List<String> asked = new ArrayList<>();
		List<String> left = new ArrayList<>(List.of("a", "b", "c"));
		VillageSweep.chain("set_1", holding, () -> left.stream().filter(x -> !tried.contains(x)).findFirst(), tried, (id, done) -> {
			asked.add(id);
			done.accept(false);
			done.accept(true); // a second answer is ignored
		});
		assertEquals(List.of("a", "b", "c"), asked);
		assertTrue(holding.isEmpty());
		// the world closed meanwhile (null): the chain stops there
		Set<String> tried2 = new HashSet<>();
		List<String> asked2 = new ArrayList<>();
		VillageSweep.chain("set_1", holding, () -> left.stream().filter(x -> !tried2.contains(x)).findFirst(), tried2, (id, done) -> {
			asked2.add(id);
			done.accept(null);
		});
		assertEquals(List.of("a"), asked2);
		assertTrue(holding.isEmpty());
	}
}
