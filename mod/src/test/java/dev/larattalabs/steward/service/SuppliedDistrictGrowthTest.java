package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.RevisionView;
import dev.larattalabs.architect.api.State;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Land;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.service.Growths.Step;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * (slice DG, the manager's scope 2026-11-14) A Supplied district region's growth, billed and undone, through Steward's own decisions: no e2e can make a
 * district region from materials yet (slice SD), so this drives Steward's path (Growths.decide, Growths.unbuilt, Undo.retired, Undo.removed, the history)
 * over a model of Architect's ledger as built (RS/RV section 8: a target draws its bill from the stockpile as it is built; undoRevision of a built suffix
 * refunds each paid block once per key {@code site:<id>@<placedAt>}, and refuses survival_building, nothing written, while a target is not built).
 * Steward never moves items itself: what it must hold is that it asks for the take-up only once the targets are built, exactly once per growth (a replay
 * after a crash asks nothing again), and logs the removal once.
 */
class SuppliedDistrictGrowthTest {
	private static final String OW = "minecraft:overworld";
	private static final Claim TOWN = new Claim(OW, 0, 0, 64, -64, 320);
	private static final Claim EAST = new Claim(OW, 113, 0, 48, -64, 320);
	private static final String REGION = "rg_ward";

	/** Architect's stockpile ledger for the district region's growth, as built (the model; Architect's own gates prove it). */
	static final class Ledger {
		final Map<String, Integer> stock = new HashMap<>();
		final Map<String, Map<String, Integer>> bills = new HashMap<>();
		final Map<String, State> sites = new HashMap<>();
		final Set<String> refunded = new HashSet<>();
		int paid, returned, undoCalls;
		String revState = "PLACED";
		int position = 1;

		void add(String item, int n) {
			stock.merge(item, n, Integer::sum);
		}

		/** A target built from the stockpile: its whole bill drawn (it waits while the stockpile is short). */
		boolean build(String site) {
			var bill = bills.get(site);
			for (var e : bill.entrySet()) if (stock.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
			for (var e : bill.entrySet()) { stock.merge(e.getKey(), -e.getValue(), Integer::sum); paid += e.getValue(); }
			sites.put(site, State.BUILT);
			return true;
		}

		/** undoRevision to the position below: refused while a target is not built, else each paid block refunded once per key. */
		boolean undo(int toPosition, long placedAt) {
			undoCalls++;
			if (sites.values().stream().anyMatch(s -> s != State.BUILT)) return false;
			for (var e : bills.entrySet()) {
				if (!refunded.add("site:" + e.getKey() + "@" + placedAt)) continue;
				for (var b : e.getValue().entrySet()) { stock.merge(b.getKey(), b.getValue(), Integer::sum); returned += b.getValue(); }
			}
			revState = "UNDONE";
			position = toPosition;
			return true;
		}
	}

	private static RevisionView rev(Ledger l) {
		return new RevisionView(1, 1, 0, "p1", "sha", l.revState, 0L, List.of(), List.of("gward_1"), List.of(), 0L, null, true, null, List.of("s1", "s2"));
	}

	private static Growths.Decision decide(Ledger l) {
		var r = rev(l);
		boolean building = Growths.unbuilt(r, l.sites::get) > 0;
		return Growths.decide(false, false, null, l.position, r.state(), r.position(), building);
	}

	/** One /steward undo (or a cancel's or the sweep's take-up) of the growth entry, as Undo.growth runs it. */
	private static Settlement undoOnce(Settlement s, Settlement.LogEntry entry, Ledger l) {
		var d = decide(l);
		if (d.step() == Step.BUILDING || d.step() == Step.WAIT || d.step() == Step.NEWER || d.step() == Step.UNKNOWN) return s;
		if (d.step() == Step.UNDO && !l.undo(d.toPosition(), 0L)) return s;
		// done (now, or an earlier take-up a crash cut off before its entry): logged once
		if (!Undo.retired(s, entry)) s = s.withLog(Undo.removed(entry, entry.siteIds(), true, Map.of()));
		return s;
	}

	private static long removals(Settlement s) {
		return s.log().stream().filter(e -> e.kind() == Settlement.Kind.PROJECT_REMOVED).count();
	}

	private static Settlement ward() {
		Settlement s = Settlement.founded("set_dg", "Ward Town", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L);
		s = s.withLands(s.lands().next().withDistrict(new Settlement.District("dst_1", "Ward", "E", EAST, 2L, List.of(), false, Settlement.District.BESIDE,
			Settlement.District.STANDING, "rg_town", REGION, Land.of(EAST), null, "steward.set_dg.dst_1")));
		return s.withLog(Settlement.LogEntry.of(3L, Settlement.Kind.PROJECT_PLACED, "Placed 1 building in Ward",
			List.of(new Settlement.SiteChange("s1", "archive", 0, 1), new Settlement.SiteChange("s2", "lane", 0, 1)), Growths.tag(REGION, 1), Settlement.Outcome.DONE));
	}

	@Test
	void aSuppliedDistrictGrowthIsBilledAndRefundedExactlyOnce() {
		Ledger l = new Ledger();
		l.bills.put("s1", Map.of("minecraft:cobblestone", 40, "minecraft:oak_planks", 12));
		l.bills.put("s2", Map.of("minecraft:dirt_path", 9));
		l.sites.put("s1", State.BUILDING);
		l.sites.put("s2", State.BUILDING);
		l.add("minecraft:cobblestone", 40);
		l.add("minecraft:dirt_path", 9);
		Settlement s = ward();
		var entry = s.log().get(s.log().size() - 1);
		assertEquals(Growths.tag(REGION, 1), entry.siteGroup(), "a district growth's entry names the district's own region");

		// half fed: one target waits for planks; undo asks nothing (BUILDING), refunds nothing, logs nothing
		assertTrue(l.build("s2"));
		assertFalse(l.build("s1"), "short of planks: it waits");
		assertEquals(Step.BUILDING, decide(l).step());
		s = undoOnce(s, entry, l);
		assertEquals(0, l.undoCalls, "nothing asked while its targets are building");
		assertEquals(0, l.returned);
		assertEquals(0, removals(s));

		// fed until built: undo takes it up, the bill comes back exactly
		l.add("minecraft:oak_planks", 12);
		assertTrue(l.build("s1"));
		Map<String, Integer> before = new HashMap<>(l.stock);
		s = undoOnce(s, entry, l);
		assertEquals(1, l.undoCalls);
		assertEquals(l.paid, l.returned, "paid == returned");
		assertEquals(61, l.paid);
		assertEquals(52, l.stock.get("minecraft:cobblestone") + l.stock.get("minecraft:oak_planks") - before.getOrDefault("minecraft:cobblestone", 0)
			- before.getOrDefault("minecraft:oak_planks", 0), "the cobblestone and planks the targets drew are back");
		assertEquals(1, removals(s), "the removal logged once");

		// a replay (a crash after the removal was logged, or the cancel and the sweep both reaching it): GONE, nothing asked, nothing logged again
		assertEquals(Step.GONE, decide(l).step());
		int calls = l.undoCalls, back = l.returned;
		s = undoOnce(s, entry, l);
		assertEquals(calls, l.undoCalls, "a second take-up is never asked");
		assertEquals(back, l.returned, "returned once");
		assertEquals(1, removals(s));
	}

	@Test
	void aCrashBetweenTheRefundAndItsEntryLogsTheRemovalOnceAtTheReplay() {
		Ledger l = new Ledger();
		l.bills.put("s1", Map.of("minecraft:cobblestone", 5));
		l.bills.put("s2", Map.of("minecraft:dirt_path", 3));
		l.add("minecraft:cobblestone", 5);
		l.add("minecraft:dirt_path", 3);
		l.sites.put("s1", State.BUILDING);
		l.sites.put("s2", State.BUILDING);
		assertTrue(l.build("s1"));
		assertTrue(l.build("s2"));
		Settlement s = ward();
		var entry = s.log().get(s.log().size() - 1);
		// Architect refunded and the world closed before Steward's entry was saved
		assertTrue(l.undo(0, 0L));
		assertEquals(0, removals(s));
		// the replay at the next start: GONE (UNDONE), the entry written once, no second refund
		s = undoOnce(s, entry, l);
		s = undoOnce(s, entry, l);
		assertEquals(1, removals(s));
		assertEquals(l.paid, l.returned);
		assertEquals(1, l.undoCalls);
	}

	@Test
	void aDistrictGrowsOnlyTheWayItsRegionWasBuilt() {
		// the mode gate names the district; nothing is planned (so nothing is billed) on a mismatch or while the district region is still being built
		String m = Growths.modeRefusal("Ward", false, true, false, 0);
		assertTrue(m.startsWith("Ward was built at once and this world builds from materials now"), m);
		assertTrue(Growths.modeRefusal("Ward", true, true, false, 60).contains("Ward is still being built from the stockpile (60%)"));
		assertNull(Growths.modeRefusal("Ward", true, true, true, 100));
	}

	@Test
	void theUndoTakesTheDistrictsHeadFirst() {
		// an older district growth logged late (the sweep) above a newer one: undo goes to the district region's head
		Settlement s = ward();
		s = s.withLog(Settlement.LogEntry.of(4L, Settlement.Kind.PROJECT_PLACED, "Placed 1 building in Ward", List.of(new Settlement.SiteChange("s3", "chapel", 0, 1)),
			Growths.tag(REGION, 2), Settlement.Outcome.DONE));
		Settlement late = s.withLog(Settlement.LogEntry.of(5L, Settlement.Kind.PROJECT_PLACED, "An addition placed after its build ended",
			List.of(new Settlement.SiteChange("s0", null, 0, 1)), Growths.tag(REGION, 9), Settlement.Outcome.PARTIAL));
		var newest = Optional.of(late.log().get(late.log().size() - 1));
		var head = Undo.headFirst(late, newest, 2);
		assertEquals(Growths.tag(REGION, 2), head.orElseThrow().siteGroup(), "the head revision's entry first");
		assertEquals(REGION, Growths.parse(head.get().siteGroup()).orElseThrow().region(), "MaterialRequests reads the district region from the entry");
	}

	@Test
	void theDistrictsFoundingBuildIsNeverRemovedWhileAGrowthOfItStands() {
		// the founding entry names the district's region; a logged growth of that region stands above it
		Settlement s = ward().withLog(Settlement.LogEntry.of(1L, Settlement.Kind.PROJECT_PLACED, "Placed 1 building in Ward",
			List.of(new Settlement.SiteChange("s9", "ward hall", 0, 1)), "region:" + REGION, Settlement.Outcome.DONE));
		Ledger l = new Ledger();
		String why = Growths.blocksRemoval(REGION, null, List.of(rev(l)), s);
		assertNotNull(why, "a standing growth of the district holds its founding build");
		assertTrue(why.contains("grown since") && why.contains(Growths.tag(REGION, 1)), why);
		// still placing: held too, whatever its sites
		l.revState = "PLACING";
		assertTrue(Growths.blocksRemoval(REGION, null, List.of(rev(l)), s).contains("still being made"));
		assertNotNull(Growths.blocksRemoval(REGION, "revising 1", List.of(), s), "busy");
		// the growth undone: the founding build may go
		l.revState = "UNDONE";
		assertNull(Growths.blocksRemoval(REGION, null, List.of(rev(l)), s));
		// a revision of the town (another region) never holds the district
		l.revState = "PLACED";
		assertNull(Growths.blocksRemoval("rg_town", null, List.of(rev(l)), s), "the growth's entry names the district's region, not the town's");
	}
}
