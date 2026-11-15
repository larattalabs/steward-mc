package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.Mode;
import dev.larattalabs.architect.api.Quote;
import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.architect.api.State;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Land;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.Settlement.Kind;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.service.FakeArchitect.Answer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (slice SD) A district region founded from materials: the realise's mode, the bill said before it is approved, Architect's refusals in plain words, the
 * "is built" line naming the district, and its undo over {@link FakeArchitect}: refused while a growth of it stands, else {@code Regions.remove} with what
 * its targets drew back exactly once (also while it is still building, and across a lost answer and a restart), the district given up with its region.
 */
class SurvivalDistrictTest {
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

	// ------------------------------------------------------------------ pure

	@Test
	void aDistrictsRegionIsRealisedFromMaterialsWhenItsApprovedQuoteSaysItsPlanIs() {
		assertEquals(Mode.CONSTRUCTION, SettlementRunner.districtRealiseMode(true, true));
		assertEquals(Mode.INSTANT, SettlementRunner.districtRealiseMode(true, false), "a plan made at once: at once");
		assertEquals(Mode.INSTANT, SettlementRunner.districtRealiseMode(false, true), "a town's flat village keeps its own path");
	}

	@Test
	void whatFollowsADistrictsQuoteIsDecidedByItsPlansModeNeverTheWorldsNow() {
		var exact = new ModulePlacement.QuoteCheck(null, null);
		var ask = new ModulePlacement.QuoteCheck(null, "lot_1 may differ");
		var unbilled = new ModulePlacement.QuoteCheck("lot_2 not billed", null);
		var B = SettlementRunner.BillAsk.BEFORE_APPROVAL;
		var A = SettlementRunner.BillAsk.AUTO;
		var R = SettlementRunner.BillAsk.AT_REALISE;
		// a plan from materials: quoted and said whatever the world reads now (it may have been turned off and on while the designs ran)
		for (boolean world : new boolean[] {true, false}) {
			assertEquals(SettlementRunner.BillNext.QUEUE, SettlementRunner.billNext(B, true, true, world, ask), "before the approval: said, then approved");
			assertEquals(SettlementRunner.BillNext.REALISE, SettlementRunner.billNext(A, true, true, world, exact));
			assertEquals(SettlementRunner.BillNext.HOLD, SettlementRunner.billNext(A, true, true, world, ask), "placed without an approval: an inexact bill asked");
			assertEquals(SettlementRunner.BillNext.HOLD, SettlementRunner.billNext(R, true, true, world, exact), "at the realise with no approval of this bill: asked");
			for (var w : SettlementRunner.BillAsk.values()) assertEquals(SettlementRunner.BillNext.CANCEL_UNBILLED, SettlementRunner.billNext(w, true, true, world, unbilled));
		}
		// a plan made at once: on, unsaid, never held
		assertEquals(SettlementRunner.BillNext.QUEUE, SettlementRunner.billNext(B, true, false, true, exact));
		assertEquals(SettlementRunner.BillNext.REALISE, SettlementRunner.billNext(R, true, false, true, exact));
		// no quote: ended in either mode (the world's now is no proof of the plan's: it may have been turned off while a plan from materials was designed)
		for (var w : SettlementRunner.BillAsk.values()) for (boolean world : new boolean[] {true, false})
			assertEquals(SettlementRunner.BillNext.CANCEL_UNQUOTED, SettlementRunner.billNext(w, false, false, world, null));
	}

	@Test
	void architectsMaterialsRefusalsAreSaidInPlainWords() {
		String mode = SettlementRunner.materialsRefusal("NOT_ALLOWED: this plan was made in creative: plan it again in survival (this world builds from a stockpile)");
		assertNotNull(mode);
		assertTrue(mode.startsWith("the world's mode changed"));
		assertNotNull(SettlementRunner.materialsRefusal("NOT_ALLOWED: this plan was made in survival: plan it again in creative"));
		assertNotNull(SettlementRunner.materialsRefusal("NOT_ALLOWED: a region builds in the world's mode: CONSTRUCTION needs a survival world (the toggle on, not creative)"));
		String none = SettlementRunner.materialsRefusal("NOT_ALLOWED: steward_mc:settlement/set_1 has no stockpile in minecraft:overworld: a survival region builds from its owner's stockpile");
		assertNotNull(none);
		assertTrue(none.contains("stockpile is gone"));
		assertNull(SettlementRunner.materialsRefusal("OVERLAP: the claim overlaps region rg2"), "any other refusal keeps Architect's words");
		for (String m : List.of(mode, none)) assertFalse(m.contains("NOT_ALLOWED") || m.contains("plan it again"), m);
	}

	@Test
	void theBillIsSaidBeforeItIsApprovedExactOrAbout() {
		JsonObject bom = new JsonObject();
		bom.addProperty("minecraft:cobblestone", 120);
		bom.addProperty("minecraft:oak_planks", 40);
		bom.addProperty("minecraft:gravel", 12);
		bom.addProperty("minecraft:glass_pane", 2);
		var exact = new Quote("rp1", null, null, true, bom, new JsonObject(), Map.of("lot_1", "e1"), true, List.of());
		String line = SettlementRunner.districtBillLine("East Ward", exact, null);
		assertEquals("From materials, East Ward needs 120 cobblestone, 40 oak planks, 12 gravel and 1 more kind (174 items) from the stockpile. "
			+ "Fill the stockpile as it's built.", line);
		var about = new Quote("rp1", null, null, true, bom, new JsonObject(), Map.of(), false, List.of("lot_2 is in unloaded chunks"));
		assertTrue(SettlementRunner.districtBillLine("East Ward", about, null).contains("needs about 120 cobblestone"));
		assertTrue(SettlementRunner.districtBillLine("East Ward", about, null).contains("(lot_2 is in unloaded chunks)"));
		assertTrue(SettlementRunner.districtBillLine("East Ward", null, "PLAN_STALE").contains("couldn't be quoted (PLAN_STALE)"));
		var free = new Quote("rp1", null, null, true, new JsonObject(), new JsonObject(), Map.of(), true, List.of());
		assertEquals("From materials, East Ward takes nothing from the stockpile.", SettlementRunner.districtBillLine("East Ward", free, null));
	}

	@Test
	void theBuiltLineNamesTheDistrictWhoseRegionItIs() throws IOException {
		Settlement s = district(nextId(), false);
		assertEquals("Ward", MaterialRequests.builtName(s, "rg_sd"));
		assertEquals("Gea", MaterialRequests.builtName(s, "rg_town"), "the town's region: the settlement");
	}

	// ------------------------------------------------------------------ undo over the faked Architect

	private static String nextId() {
		return "set_sd" + (++serial);
	}

	private Settlement now(String id) {
		return Settlements.store().get(id).orElseThrow();
	}

	private void restart() throws IOException {
		Settlements.useForTest(SettlementStore.load(file), file);
	}

	private static long count(Settlement s, Kind k) {
		return s.log().stream().filter(e -> e.kind() == k).count();
	}

	private static Settlement.LogEntry founding(Settlement s) {
		return s.log().stream().filter(e -> "region:rg_sd".equals(e.siteGroup())).findFirst().orElseThrow();
	}

	/**
	 * A Supplied town with a beside district standing as its own region {@code rg_sd} built from materials: two targets, {@code d0} built (it drew 40
	 * cobblestone and 12 oak planks) and {@code d1} built or still building (it drew 10 gravel of its 30); {@code grown}: a logged growth of it stands.
	 */
	private Settlement district(String id, boolean grown) throws IOException {
		Settlement s = Settlement.founded(id, "Gea", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L);
		s = s.withLands(s.lands().next().withDistrict(new Settlement.District("dst_1", "Ward", "E", EAST, 2L, List.of(), false, Settlement.District.BESIDE,
			Settlement.District.STANDING, "rg_town", "rg_sd", Land.of(EAST), null, "steward." + id + ".dst_1")));
		s = s.withLog(Settlement.LogEntry.of(3L, Kind.PROJECT_PLACED, "Placed 2 buildings in Ward", List.of(new Settlement.SiteChange("d0", "hall", 0, 1),
			new Settlement.SiteChange("d1", "forge", 0, 1)), "region:rg_sd", Settlement.Outcome.DONE));
		var r = a.region("rg_sd", s.owner());
		r.fromMaterials = true;
		r.ext.addProperty(Districts.DISTRICT_EXT, "dst_1");
		a.site("d0", s.owner(), "rg_sd");
		a.site("d1", s.owner(), "rg_sd");
		a.ledger.bills.put("d0", Map.of("minecraft:cobblestone", 40, "minecraft:oak_planks", 12));
		a.ledger.add("minecraft:cobblestone", 40);
		a.ledger.add("minecraft:oak_planks", 12);
		a.sites.get("d0").state = State.BUILDING;
		assertTrue(a.ledger.build(a, "d0"));
		a.sites.get("d1").state = State.BUILDING;
		a.ledger.add("minecraft:gravel", 10);
		a.ledger.drawPart(a, "d1", Map.of("minecraft:gravel", 10));
		if (grown) {
			s = s.withLog(Settlement.LogEntry.of(4L, Kind.PROJECT_PLACED, "Placed 1 building in Ward", List.of(new Settlement.SiteChange("g1", "archive", 0, 1)),
				Growths.tag("rg_sd", 1), Settlement.Outcome.DONE));
			r.rev(1, 1, "PLACED", "g1");
			a.site("g1", s.owner(), "rg_sd");
		}
		Settlements.store().put(s);
		Settlements.store().save(file);
		return s;
	}

	private AtomicReference<Boolean> undoRegion(Settlement s) {
		AtomicReference<Boolean> done = new AtomicReference<>();
		Undo.region(null, s, founding(s), "rg_sd", Map.of(), said::add, done::set);
		return done;
	}

	@Test
	void sd_theDistrictIsNotRemovedWhileAGrowthOfItStands() throws IOException {
		String id = nextId();
		Settlement s = district(id, true);
		assertEquals(Boolean.FALSE, undoRegion(s).get());
		assertEquals(0, a.count("regions.remove"), "nothing asked: its growth is undone first");
		assertTrue(said.stream().anyMatch(l -> l.startsWith("Undo stopped:")), String.valueOf(said));
		assertEquals(0, a.ledger.returned, "nothing refunded");
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED));
		assertTrue(now(id).lands().district("dst_1").isPresent());
		assertFalse(Undo.busy(id));
	}

	@Test
	void sd_aDistrictStillBuildingIsRemovedWithWhatItDrewBackOnceAndGivenUp() throws IOException {
		String id = nextId();
		Settlement s = district(id, false);
		a.regions.get("rg_sd").built = false;
		assertEquals(Boolean.TRUE, undoRegion(s).get(), String.valueOf(said));
		assertEquals(1, a.count("regions.remove rg_sd"));
		assertEquals(62, a.ledger.paid, "d0's whole bill and d1's 10 gravel so far");
		assertEquals(a.ledger.paid, a.ledger.returned, "paid == returned, the still-building target's part included");
		assertEquals(Map.of("minecraft:cobblestone", 40, "minecraft:oak_planks", 12, "minecraft:gravel", 10), Map.copyOf(a.ledger.stock), "the stockpile as fed");
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertTrue(now(id).lands().district("dst_1").isEmpty(), "given up with its region");
		assertTrue(said.stream().anyMatch(l -> l.contains("Ward is given up")), String.valueOf(said));
		// a replay (a second undo of the same entry, the region's tombstone): retired, nothing asked or refunded again
		said.clear();
		assertEquals(Boolean.TRUE, undoRegion(now(id)).get());
		assertEquals(1, a.count("regions.remove rg_sd"));
		assertEquals(62, a.ledger.returned);
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED), "logged once");
		assertFalse(Undo.busy(id));
	}

	@Test
	void sd_aLostRemoveAnswerThenARestartLogsOnceAndRefundsOnce() throws IOException {
		String id = nextId();
		Settlement s = district(id, false);
		// Architect removes it (and refunds); the answer is lost on its way
		a.removeAnswer = Answer.APPLY_THEN_LOSE;
		var done = undoRegion(s);
		assertEquals(RegionState.REMOVED, a.regions.get("rg_sd").state);
		assertNotEquals(Boolean.TRUE, done.get(), "not taken for done: its answer was lost");
		assertEquals(0, count(now(id), Kind.PROJECT_REMOVED), "the entry stays");
		assertEquals(62, a.ledger.returned, "Architect refunded what its targets drew");
		long back = a.ledger.returned;
		assertEquals(a.ledger.paid, back, "paid == returned");
		// a restart; the undo asked again finds the tombstone: logged once, nothing asked or refunded again
		restart();
		a.removeAnswer = Answer.APPLY;
		assertEquals(Boolean.TRUE, undoRegion(now(id)).get(), String.valueOf(said));
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED));
		assertEquals(1, a.count("regions.remove rg_sd"), "asked once");
		assertEquals(back, a.ledger.returned, "refunded once");
		assertTrue(now(id).lands().district("dst_1").isEmpty(), "given up");
		assertEquals(Boolean.TRUE, undoRegion(now(id)).get());
		assertEquals(1, count(now(id), Kind.PROJECT_REMOVED), "still once");
	}

	@Test
	void sd_aGiveUpThatNeverSavedIsDoneAtTheNextStartOnlyOnATombstone() throws IOException {
		String id = nextId();
		Settlement s = district(id, false);
		var entry = founding(s);
		// Architect removed it; the removal's entry saved, the give-up did not (a stop between the two saves)
		var r = a.regions.get("rg_sd");
		r.state = RegionState.REMOVED;
		a.sites.values().removeIf(x -> "rg_sd".equals(x.region));
		// not yet retired: the reconcile leaves it (the next undo logs the removal once and gives it up)
		Districts.giveUpRemoved(null, now(id), now(id).lands().district("dst_1").orElseThrow());
		assertTrue(now(id).lands().district("dst_1").isPresent(), "its entry is not retired: the undo puts it right");
		assertTrue(Settlements.log(id, Undo.removed(entry, entry.siteIds(), true, Map.of())).ok());
		restart();
		// no record at all (no tombstone): never read as removed
		r.present = false;
		Districts.giveUpRemoved(null, now(id), now(id).lands().district("dst_1").orElseThrow());
		assertTrue(now(id).lands().district("dst_1").isPresent(), "an absent record is not a removal");
		assertFalse(Districts.leftAfterRemoval(now(id), now(id).lands().district("dst_1").orElseThrow(), false));
		// the tombstone: given up, once
		r.present = true;
		Districts.giveUpRemoved(null, now(id), now(id).lands().district("dst_1").orElseThrow());
		assertTrue(now(id).lands().district("dst_1").isEmpty(), "given up at the next start");
		assertEquals(1, now(id).log().stream().filter(e -> e.text().startsWith("Gave up Ward")).count(), "once");
	}
}
