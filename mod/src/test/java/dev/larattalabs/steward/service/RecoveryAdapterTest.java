package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.architect.api.State;
import dev.larattalabs.steward.gateway.BatchPlanner;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Land;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.Settlement.Kind;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.service.FakeArchitect.Answer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (slice GEA) Steward's recovery adapters driven over {@link FakeArchitect}, through the seams ({@link ArchitectAccess}, {@link Settlements#useForTest}):
 * slice GE's reconcile (Adoption.reconcile's closes, the growth sweep's orphans) and slice DG's {@code Undo.growth}. Each test names the slice and the case.
 * A restart is the store read back from its file; a crash before a save is the file made unwritable (the store rolls back, as {@code commit} does).
 * No server: the fake ignores it, and the server's executor is the fake's.
 */
class RecoveryAdapterTest {
	private static final String OW = "minecraft:overworld";
	private static final Claim TOWN = new Claim(OW, 0, 0, 64, -64, 320);
	private static final Claim EAST = new Claim(OW, 113, 0, 48, -64, 320);
	private static int serial;

	@TempDir
	Path dir;
	Path file;
	FakeArchitect a;
	final List<String> said = new ArrayList<>();

	@BeforeEach
	void setUp() {
		a = new FakeArchitect();
		a.install();
		file = dir.resolve("steward-settlements.json");
		Settlements.useForTest(new SettlementStore(), file);
	}

	@AfterEach
	void tearDown() {
		ArchitectAccess.reset();
		Settlements.resetForTest();
	}

	// ------------------------------------------------------------------ helpers

	/** A fresh settlement id per test (Undo's and the sweep's in-flight sets are static). */
	private static String nextId() {
		return "set_gea" + (++serial);
	}

	private Settlement put(Settlement s) throws IOException {
		Settlements.store().put(s);
		Settlements.store().save(file);
		return s;
	}

	private Settlement now(String id) {
		return Settlements.store().get(id).orElseThrow();
	}

	/** The world restarted: the store is what its file holds. */
	private void restart() throws IOException {
		Settlements.useForTest(SettlementStore.load(file), file);
	}

	/** Saves fail from now on (the world dies before its save lands); the store in memory is kept, as a running world has it. */
	private void savesFail() throws IOException {
		Path blocker = dir.resolve("not-a-dir");
		Files.writeString(blocker, "x");
		Settlements.useForTest(Settlements.store(), blocker.resolve("steward-settlements.json"));
	}

	private static long count(Settlement s, Kind k) {
		return s.log().stream().filter(e -> e.kind() == k).count();
	}

	private static Settlement.LogEntry last(Settlement s) {
		return s.log().get(s.log().size() - 1);
	}

	private static Settlement.LogEntry growthEntry(long at, String region, int rid, String... sites) {
		List<Settlement.SiteChange> changes = new ArrayList<>();
		for (String id : sites) changes.add(new Settlement.SiteChange(id, "house", 0, 1));
		return Settlement.LogEntry.of(at, Kind.PROJECT_PLACED, "Placed 1 building", changes, Growths.tag(region, rid), Settlement.Outcome.DONE);
	}

	/** A region town (its founding entry {@code region:<id>}) with one standing growth (rid 1 at position 1) of site {@code g1}. */
	private Settlement town(String id, String region, boolean logged) throws IOException {
		Settlement s = Settlement.founded(id, "Gea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed the village", List.of(new Settlement.SiteChange("t1", "hall", 0, 1)),
				"region:" + region, Settlement.Outcome.DONE));
		if (logged) s = s.withLog(growthEntry(3L, region, 1, "g1"));
		var r = a.region(region, s.owner());
		r.rev(1, 1, "PLACED", "g1");
		a.site("t1", s.owner(), region);
		a.site("g1", s.owner(), region);
		return put(s);
	}

	/** {@code Undo.growth} of {@code entry}; the answer {@code done} got (null until it fires: every test asserts TRUE or FALSE, so a callback that never ran fails). */
	private AtomicReference<Boolean> undoGrowth(Settlement s, Settlement.LogEntry entry) {
		AtomicReference<Boolean> done = new AtomicReference<>();
		Undo.growth(null, s, entry, Growths.parse(entry.siteGroup()).orElseThrow(), Map.of(), said::add, done::set);
		return done;
	}

	// ------------------------------------------------------------------ GE: Adoption.reconcile (the REMOVED close, the restart reconcile)

	/** An adopted town (a receipt for {@code region}) over sites s1 and s2. */
	private Settlement adopted(String id, String region) throws IOException {
		Settlement s = Settlement.founded(id, "Gea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);
		var intent = Settlement.LogEntry.of(2L, Kind.TOWN_ADOPTED, "Taking the town on", List.of(new Settlement.SiteChange("s1", "house_1", 0, 1),
			new Settlement.SiteChange("s2", null, 0, 1)), Adoption.INTENT + "steward.b1.adopt", Settlement.Outcome.PARTIAL);
		return put(s.withLog(intent).withLog(Adoption.receipt(intent, region, 3L)));
	}

	@Test
	void ge_aRegionGoneWithItsTombstoneIsRetiredOnce() throws IOException {
		String id = nextId();
		Settlement s = adopted(id, "rg_a");
		a.region("rg_a", s.owner()).state = RegionState.REMOVED;
		a.site("other", "someone", null); // Architect's inventory is known (it lists a site)
		assertTrue(Adoption.reconcile(null, s));
		var after = now(id);
		assertEquals(1, count(after, Kind.PROJECT_REMOVED), "the gone sites recorded once");
		assertEquals(List.of("s1", "s2"), after.log().get(after.log().size() - 2).siteIds());
		assertEquals(Kind.TOWN_RELEASED, last(after).kind());
		assertEquals("region:rg_a", last(after).siteGroup(), "the adoption retired, after its removal record");
		assertTrue(Adoption.standing(after).isEmpty());
		// the next start reads it closed: nothing written again
		restart();
		int size = now(id).log().size();
		assertTrue(Adoption.reconcile(null, now(id)));
		assertEquals(size, now(id).log().size());
	}

	@Test
	void ge_aRegionAndItsSitesGoneWithNoTombstoneCloseAsRemovedOnlyWhenTheInventoryIsKnown() throws IOException {
		String id = nextId();
		Settlement s = adopted(id, "rg_b");
		// no record, no tombstone, no site at all: Architect's site file may be unread, so nothing is read as gone
		assertTrue(Adoption.reconcile(null, s));
		assertEquals(s.log().size(), now(id).log().size(), "left open while Architect lists no site");
		assertTrue(Adoption.standing(now(id)).isPresent());
		// a later start where Architect lists sites again (another's): the region and its buildings are gone
		a.site("other", "someone", null);
		assertTrue(Adoption.reconcile(null, now(id)));
		var after = now(id);
		assertEquals(1, count(after, Kind.PROJECT_REMOVED));
		assertTrue(after.log().get(after.log().size() - 2).text().startsWith("Gone from the world"));
		assertEquals("The town's region and its buildings are gone", last(after).text());
	}

	@Test
	void ge_aLostUnadoptAnswerIsReadAsAReleaseOnTheNextRead() throws IOException {
		String id = nextId();
		Settlement s = adopted(id, "rg_c");
		var r = a.region("rg_c", s.owner());
		r.ext.addProperty(Adoption.KEY_EXT, "steward.b1.adopt");
		a.site("s1", s.owner(), "rg_c");
		a.site("s2", s.owner(), "rg_c");
		// the unadopt went through; its answer was lost
		a.unadoptAnswer = Answer.APPLY_THEN_LOSE;
		AtomicReference<Boolean> done = new AtomicReference<>();
		Adoption.release(null, s, last(s), said::add, done::set);
		assertEquals(Boolean.TRUE, done.get(), "the lost answer resolved by reading again: " + said);
		assertFalse(r.present);
		var after = now(id);
		assertEquals(0, count(after, Kind.PROJECT_REMOVED), "a release, never a removal: every building stands");
		assertEquals(1, count(after, Kind.TOWN_RELEASED));
		assertEquals(1, a.count("regions.unadopt"), "asked once");
		// a restart, the reconcile again: nothing more
		restart();
		assertTrue(Adoption.reconcile(null, now(id)));
		assertEquals(1, count(now(id), Kind.TOWN_RELEASED));
	}

	@Test
	void ge_anAdoptAnswerLostAcrossARestartIsClosedByTheRegionsKey() throws IOException {
		String id = nextId();
		Settlement s = Settlement.founded(id, "Gea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);
		var intent = Settlement.LogEntry.of(2L, Kind.TOWN_ADOPTED, "Taking the town on", List.of(new Settlement.SiteChange("s1", "house_1", 0, 1)),
			Adoption.INTENT + "steward.b7.adopt", Settlement.Outcome.PARTIAL);
		put(s.withLog(intent));
		// another adoption of the owner's, listed first: only the region carrying this intent's key closes it
		a.region("rg_other", s.owner()).ext.addProperty(Adoption.KEY_EXT, "steward.b6.adopt");
		var r = a.region("rg_d", s.owner());
		r.ext.addProperty(Adoption.KEY_EXT, "steward.b7.adopt");
		restart();
		assertTrue(Adoption.reconcile(null, now(id)));
		var after = now(id);
		assertEquals("region:rg_d", Adoption.standing(after).orElseThrow().siteGroup(), "the receipt names the region Architect adopted");
		assertTrue(Adoption.openIntent(after).isEmpty());
		assertTrue(Adoption.reconcile(null, after));
		assertEquals(after.log().size(), now(id).log().size(), "closed once");

		// the other side: Architect never got it (no region carries the key) → not adopted
		String id2 = nextId();
		var intent2 = Settlement.LogEntry.of(2L, Kind.TOWN_ADOPTED, "Taking the town on", List.of(), Adoption.INTENT + "steward.b8.adopt", Settlement.Outcome.PARTIAL);
		put(Settlement.founded(id2, "Gea2", new Claim(OW, 1000, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L).withLog(intent2));
		assertTrue(Adoption.reconcile(null, now(id2)));
		assertEquals(Kind.TOWN_RELEASED, last(now(id2)).kind());
		assertEquals(Adoption.INTENT + "steward.b8.adopt", last(now(id2)).siteGroup());
	}

	@Test
	void ge_aReconcileWhoseSaveFailsWritesNothingAndClosesOnceAfterTheRestart() throws IOException {
		String id = nextId();
		Settlement s = adopted(id, "rg_e");
		a.region("rg_e", s.owner()).state = RegionState.REMOVED;
		a.site("other", "someone", null);
		savesFail();
		assertFalse(Adoption.reconcile(null, s), "the history could not be saved");
		assertTrue(Adoption.standing(now(id)).isPresent(), "memory never holds what the file does not");
		restart();
		assertTrue(Adoption.reconcile(null, now(id)));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(1, count(now(id), Kind.TOWN_RELEASED));
	}

	// ------------------------------------------------------------------ GE: the growth sweep (orphans across a restart)

	@Test
	void ge_aRevisionPlacedButUnloggedAtARestartIsLoggedOnceAndTakenUp() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_t1", false);
		// the build ended (a restart) after its revision was placed: its site carries the settlement and a build id no build holds
		a.sites.get("g1").ext("steward_mc:settlement", id).ext(BatchPlanner.BUILD_EXT, "b1790000000000");
		restart();
		VillageSweep.growths(null, now(id));
		var after = now(id);
		assertEquals(1, after.log().stream().filter(e -> e.kind() == Kind.PROJECT_PLACED && Growths.tag("rg_t1", 1).equals(e.siteGroup())).count(), "logged once");
		assertEquals(1, a.count("regions.undoRevision rg_t1 0"), "taken up by its revision, never the town");
		assertEquals(0, a.count("regions.remove"));
		assertEquals(1, count(after, Kind.PROJECT_REMOVED));
		assertEquals("UNDONE", a.regions.get("rg_t1").revisions.get(0).state);
		assertTrue(a.sites.containsKey("t1"), "the town stands");
		assertFalse(Undo.busy(id));
		// the next sweep (10 s later, or the next start) finds it logged: nothing more
		restart();
		VillageSweep.growths(null, now(id));
		assertEquals(after.log().size(), now(id).log().size());
		assertEquals(1, a.count("regions.undoRevision"));
	}

	@Test
	void ge_anOrphansTakeUpInFlightIsNeverAskedTwice() throws IOException {
		String id = nextId();
		town(id, "rg_t2", false);
		a.sites.get("g1").ext("steward_mc:settlement", id).ext(BatchPlanner.BUILD_EXT, "b1790000000001");
		// a second orphan, in a standing district region of the same settlement: eligible on its own, so only the in-flight hold keeps it from a second call
		put(now(id).withLands(now(id).lands().next().withDistrict(new Settlement.District("dst_1", "Ward", "E", EAST, 2L, List.of(), false,
			Settlement.District.ALONG, Settlement.District.STANDING, null, "rg_t2d", Land.ofRect(OW, 113, 0, 120, 1), null, "steward." + id + ".dst_1"))));
		a.region("rg_t2d", now(id).owner()).rev(1, 1, "PLACED", "q1");
		a.site("q1", now(id).owner(), "rg_t2d").ext("steward_mc:settlement", id).ext(BatchPlanner.BUILD_EXT, "b1790000000002");
		a.undoAnswer = Answer.HOLD;
		VillageSweep.growths(null, now(id));
		assertTrue(Undo.busy(id), "the settlement is busy while its take-up runs");
		assertEquals("UNDOING", a.regions.get("rg_t2").revisions.get(0).state);
		VillageSweep.growths(null, now(id));
		VillageSweep.growths(null, now(id));
		assertEquals(1, a.count("regions.undoRevision"), "one take-up in flight");
		assertEquals(1, now(id).log().stream().filter(e -> Growths.tag("rg_t2", 1).equals(e.siteGroup()) && e.kind() == Kind.PROJECT_PLACED).count());
		assertEquals(0, a.count("regions.undoRevision rg_t2d"), "the district's orphan waits");
		a.complete();
		assertFalse(Undo.busy(id));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		// the next sweep goes on to the district's orphan: logged and taken up once
		a.undoAnswer = Answer.APPLY;
		VillageSweep.growths(null, now(id));
		assertEquals(1, a.count("regions.undoRevision rg_t2d 0"));
		assertEquals(2, count(now(id), Kind.PROJECT_REMOVED));
	}

	@Test
	void ge_aTakeUpARestartCutShortResumesWithTheSameCallAndLogsOnce() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_t9", true);
		// the world stopped mid take-up: Architect reads the revision UNDOING (no longer busy after its own restart)
		a.regions.get("rg_t9").revisions.get(0).state = "UNDOING";
		restart();
		assertEquals(Boolean.TRUE, undoGrowth(now(id), last(s)).get(), String.valueOf(said));
		assertEquals(1, a.count("regions.undoRevision rg_t9 0"), "the same target, which resumes it");
		assertEquals("UNDONE", a.regions.get("rg_t9").revisions.get(0).state);
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
	}

	@Test
	void ge_aRevisionUndoneWhileItsOrphanNoteIsOpenClosesTheNoteOnce() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_t3", false);
		String tag = Growths.tag("rg_t3", 1);
		put(now(id).withLog(VillageSweep.orphanNote(tag)));
		a.regions.get("rg_t3").revisions.get(0).state = "UNDONE";
		a.regions.get("rg_t3").position = 0;
		VillageSweep.growths(null, now(id));
		assertFalse(VillageSweep.orphanOpen(now(id), tag), "closed: it was taken up");
		long notes = count(now(id), Kind.NOTE);
		VillageSweep.growths(null, now(id));
		assertEquals(notes, count(now(id), Kind.NOTE), "closed once");
		assertEquals(0, count(now(id), Kind.PROJECT_PLACED) - 1, "nothing logged for it but the town's founding");
		assertEquals(0, a.count("regions.undoRevision"));
	}

	@Test
	void ge_aGrowthLoggedButUndoneInArchitectIsRetiredWithoutAsking() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_t4", true);
		// Architect took it up (another path, or a lost answer); the history still has it standing
		a.regions.get("rg_t4").revisions.get(0).state = "UNDONE";
		a.regions.get("rg_t4").position = 0;
		a.sites.remove("g1");
		var done = undoGrowth(s, last(s));
		assertEquals(Boolean.TRUE, done.get(), String.valueOf(said));
		assertEquals(0, a.count("regions.undoRevision"), "nothing asked");
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertTrue(Undo.retired(now(id), last(s)));
	}

	@Test
	void ge_aGrowthWhoseRegionIsGoneIsRetired() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_t5", true);
		a.regions.get("rg_t5").present = false; // no record at all
		var done = undoGrowth(s, last(s));
		assertEquals(Boolean.TRUE, done.get());
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED), "the entry retired");
		assertEquals(0, a.count("regions.undoRevision"));
		// a tombstone reads the same
		String id2 = nextId();
		Settlement s2 = put(Settlement.founded(id2, "Gea2", new Claim(OW, 1000, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(growthEntry(3L, "rg_t6", 1, "h1")));
		a.region("rg_t6", s2.owner()).state = RegionState.REMOVED;
		assertEquals(Boolean.TRUE, undoGrowth(s2, last(s2)).get());
		assertEquals(1, count(now(id2), Kind.PROJECT_REMOVED));
	}

	@Test
	void ge_anUndoAnswerLostMidCallIsResolvedOnTheNextRead() throws IOException {
		String id = nextId();
		Settlement s = town(id, "rg_t7", true);
		a.undoAnswer = Answer.APPLY_THEN_LOSE;
		var done = undoGrowth(s, last(s));
		assertEquals(Boolean.TRUE, done.get(), "read again: UNDONE, so it is done " + said);
		assertEquals(1, a.count("regions.undoRevision"));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED), "logged once");
		// refused outright: nothing written, the entry stays for a later undo
		String id2 = nextId();
		Settlement s2 = put(Settlement.founded(id2, "Gea2", new Claim(OW, 1000, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withLog(growthEntry(3L, "rg_t8", 1, "h2")));
		a.region("rg_t8", s2.owner()).rev(1, 1, "PLACED", "h2");
		a.site("h2", s2.owner(), "rg_t8");
		a.undoAnswer = Answer.REFUSE;
		said.clear();
		assertEquals(Boolean.FALSE, undoGrowth(s2, last(s2)).get());
		assertEquals(0, count(now(id2), Kind.PROJECT_REMOVED));
		assertTrue(said.stream().anyMatch(l -> l.startsWith("The town's change could not be taken up")), String.valueOf(said));
		assertEquals("PLACED", a.regions.get("rg_t8").revisions.get(0).state);
		assertFalse(Undo.busy(id2));
	}

	// ------------------------------------------------------------------ DG: Undo.growth

	/** A town with a standing district region {@code rg_dist} (an along quarter, land = its region's outside) grown once (rid 1) and maybe twice. */
	private Settlement district(String id, boolean two) throws IOException {
		String before = "{\"v\": 2, \"outside\": {\"runs\": [[113, 0, 120, 0], [113, 1, 120, 1]]}}";
		String grown = "{\"v\": 2, \"outside\": {\"runs\": [[113, 0, 130, 0], [113, 1, 130, 1]]}}";
		String grown2 = "{\"v\": 2, \"outside\": {\"runs\": [[113, 0, 140, 0], [113, 1, 140, 1]]}}";
		Settlement s = Settlement.founded(id, "Gea", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);
		Land land = Land.ofRect(OW, 113, 0, two ? 140 : 130, 1);
		s = s.withLands(s.lands().next().withDistrict(new Settlement.District("dst_1", "Ward", "E", EAST, 2L, List.of(), false, Settlement.District.ALONG,
			Settlement.District.STANDING, null, "rg_dist", land, null, "steward." + id + ".dst_1")));
		s = s.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed Ward", List.of(new Settlement.SiteChange("d0", "hall", 0, 1)), "region:rg_dist",
			Settlement.Outcome.DONE)).withLog(growthEntry(3L, "rg_dist", 1, "d1"));
		var r = a.region("rg_dist", s.owner());
		r.ext.addProperty(Districts.DISTRICT_EXT, "dst_1");
		r.rev(1, 1, "PLACED", "d1");
		r.landAt.put(0, before);
		r.landAt.put(1, grown);
		a.site("d0", s.owner(), "rg_dist");
		a.site("d1", s.owner(), "rg_dist");
		if (two) {
			s = s.withLog(growthEntry(4L, "rg_dist", 2, "d2"));
			r.rev(2, 2, "PLACED", "d2");
			r.landAt.put(2, grown2);
			a.site("d2", s.owner(), "rg_dist");
		}
		r.setLand(r.landAt.get(r.position));
		return put(s);
	}

	@Test
	void dg_theDistrictsHeadIsUndoneFirst() throws IOException {
		String id = nextId();
		Settlement s = district(id, true);
		// rid 1's entry logged late (the sweep), so it is the newest: headFirst reads Architect's head and hands out rid 2's
		var late = growthEntry(5L, "rg_dist", 1, "d1");
		var older = s.log().stream().filter(e -> e.at() == 3L).findFirst().orElseThrow();
		Settlement reordered = put(s.withLog(late));
		var head = Undo.headFirst(reordered, Optional.of(late));
		assertEquals(Growths.tag("rg_dist", 2), head.orElseThrow().siteGroup(), "Architect's head first");
		assertTrue(a.count("regions.get rg_dist") > 0, "read from Architect, not the history order");
		// asked for rid 1 directly: a newer change stands above it, nothing asked
		assertEquals(Boolean.FALSE, undoGrowth(reordered, late).get());
		assertEquals(0, a.count("regions.undoRevision"));
		// the head taken up, then rid 1 is the head
		assertEquals(Boolean.TRUE, undoGrowth(now(id), head.get()).get(), String.valueOf(said));
		assertEquals(1, a.count("regions.undoRevision rg_dist 1"));
		var r = a.regions.get("rg_dist");
		assertEquals(1, r.position, "the head moved to rid 1's position");
		assertEquals("PLACED", r.revisions.get(0).state, "rid 1 stands");
		assertEquals("UNDONE", r.revisions.get(1).state);
		assertTrue(a.sites.containsKey("d0") && a.sites.containsKey("d1") && !a.sites.containsKey("d2"));
		assertEquals(Land.ofRect(OW, 113, 0, 130, 1), now(id).lands().district("dst_1").orElseThrow().land(), "the land of rid 1 again");
		// now rid 1 is the head: the older (non-late) entry is handed out, standing
		assertEquals(older, Undo.headFirst(now(id), Optional.of(older)).orElseThrow());
	}

	@Test
	void dg_anUndoIsRefusedWhileARevisionIsInFlight() throws IOException {
		String id = nextId();
		Settlement s = district(id, false);
		var r = a.regions.get("rg_dist");
		r.revisions.get(0).state = "PLACING";
		assertEquals(Boolean.FALSE, undoGrowth(s, last(s)).get());
		assertTrue(said.stream().anyMatch(l -> l.contains("still being placed")), String.valueOf(said));
		r.revisions.get(0).state = "PLACED";
		r.busy = "revising 2";
		said.clear();
		assertEquals(Boolean.FALSE, undoGrowth(s, last(s)).get());
		assertTrue(said.stream().anyMatch(l -> l.contains("busy")), String.valueOf(said));
		assertEquals(0, a.count("regions.undoRevision"), "nothing asked while it is in flight");
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), "the entry stays");
		assertFalse(Undo.busy(id));
		assertEquals(Land.ofRect(OW, 113, 0, 130, 1), now(id).lands().district("dst_1").orElseThrow().land(), "the land untouched");
	}

	@Test
	void dg_aSuppliedGrowthStillBuildingIsRefusedThenPaidEqualsReturned() throws IOException {
		String id = nextId();
		Settlement s = district(id, false);
		var r = a.regions.get("rg_dist");
		r.fromMaterials = true;
		a.ledger.bills.put("d1", Map.of("minecraft:cobblestone", 40, "minecraft:oak_planks", 12));
		a.sites.get("d1").state = State.BUILDING;
		a.ledger.add("minecraft:cobblestone", 40);
		assertFalse(a.ledger.build(a, "d1"), "short of planks: it waits");
		assertEquals(Boolean.FALSE, undoGrowth(s, last(s)).get());
		assertTrue(said.stream().anyMatch(l -> l.startsWith("The addition is still being built from the stockpile")), String.valueOf(said));
		assertEquals(0, a.count("regions.undoRevision"), "nothing asked while its target is building");
		a.ledger.add("minecraft:oak_planks", 12);
		assertTrue(a.ledger.build(a, "d1"));
		assertEquals(Boolean.TRUE, undoGrowth(s, last(s)).get(), String.valueOf(said));
		assertEquals(52, a.ledger.paid);
		assertEquals(a.ledger.paid, a.ledger.returned, "paid == returned");
		// a replay: nothing asked, nothing returned again, nothing logged again
		assertEquals(Boolean.TRUE, undoGrowth(now(id), last(s)).get());
		assertEquals(1, a.count("regions.undoRevision"));
		assertEquals(52, a.ledger.returned);
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
	}

	@Test
	void dg_theDistrictsLandIsPutBackAfterItsGrowthIsUndone() throws IOException {
		String id = nextId();
		Settlement s = district(id, false);
		assertEquals(Boolean.TRUE, undoGrowth(s, last(s)).get(), String.valueOf(said));
		var d = now(id).lands().district("dst_1").orElseThrow();
		assertEquals(Land.ofRect(OW, 113, 0, 120, 1), d.land(), "a quarter owns exactly its region's outside again");
		assertEquals(Settlement.District.STANDING, d.state(), "the district stands: its region does");
		assertEquals("rg_dist", d.region());
		assertTrue(a.sites.containsKey("d0"), "the district's founding building stands");
		assertTrue(said.stream().anyMatch(l -> l.startsWith("Undone: Ward's change is taken up")), String.valueOf(said));
		// after a restart the land reads the same (it was saved)
		restart();
		assertEquals(Land.ofRect(OW, 113, 0, 120, 1), now(id).lands().district("dst_1").orElseThrow().land());
	}

	@Test
	void dg_aCrashBetweenArchitectsUndoAndTheEntryGivesTheEntryOnceAtTheNextStart() throws IOException {
		String id = nextId();
		Settlement s = district(id, false);
		var entry = last(s);
		savesFail();
		// Architect undoes it; Steward's save of the entry (and the land) does not land before the world dies
		assertEquals(Boolean.TRUE, undoGrowth(s, entry).get());
		assertEquals("UNDONE", a.regions.get("rg_dist").revisions.get(0).state);
		assertTrue(said.contains(Undo.UNLOGGED), String.valueOf(said));
		restart();
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), "the file never got it");
		assertEquals(Land.ofRect(OW, 113, 0, 130, 1), now(id).lands().district("dst_1").orElseThrow().land(), "nor the land");
		// the next start: the growth sweep puts the land right, and the undo (asked again) gives the entry once without asking Architect
		VillageSweep.growths(null, now(id));
		assertEquals(Land.ofRect(OW, 113, 0, 120, 1), now(id).lands().district("dst_1").orElseThrow().land(), "the land read again from the settled region");
		assertEquals(Boolean.TRUE, undoGrowth(now(id), entry).get());
		assertEquals(Boolean.TRUE, undoGrowth(now(id), entry).get());
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED), "the entry once");
		assertEquals(1, a.count("regions.undoRevision"), "Architect asked once");
		assertFalse(Undo.busy(id));
	}
}
