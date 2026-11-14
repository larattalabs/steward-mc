package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.Settlement.Kind;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.service.Adoption.Close;
import dev.larattalabs.steward.service.Adoption.LotSite;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A street town taken on as one region (docs/GROWTH.md, slice B): its lot ids, its history and how a crash or a lost answer is closed. */
class AdoptionTest {
	private static final Claim TOWN = new Claim("minecraft:overworld", 0, 0, 64, -64, 320);

	private static Settlement town() {
		Settlement s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);
		s = s.withLog(new Settlement.LogEntry(2L, Kind.PROJECT_PLACED, "Placed 2 buildings", List.of("s1", "s2", "r1"), "g1"));
		return s.withLog(new Settlement.LogEntry(3L, Kind.PROJECT_PLACED, "Placed 1 buildings", List.of("s3", "r2"), "g2"));
	}

	private static Adoption.Inventory inv() {
		return new Adoption.Inventory(10, -20, 30, List.of("r1", "r2"), Adoption.allocate(List.of(new LotSite("s1", "house_1", 0, 0),
			new LotSite("s2", "farm_1", 5, 0), new LotSite("s3", "house_1", 10, 0))));
	}

	@Test
	void lotIdsAreValidUniqueAndStable() {
		var m = Adoption.allocate(List.of(new LotSite("s10", "house_1", 0, 0), new LotSite("s9", "house_1", 0, 0), new LotSite("s2", "House 1!", 0, 0),
			new LotSite("s3", "1st_tower", 0, 0), new LotSite("s4", "a".repeat(60), 0, 0), new LotSite("s5", null, 0, 0), new LotSite("s6", "a".repeat(60), 0, 0)));
		assertEquals(7, m.size());
		for (String id : m.keySet()) assertTrue(id.matches("[a-z][a-z0-9_]{0,47}"), id);
		// in site order: s9 is the first house_1, s10 the second
		assertEquals("s9", m.get("house_1"));
		assertEquals("s10", m.get("house_1_2"));
		assertEquals("s2", m.get("house1"));
		assertEquals("s3", m.get("l_1st_tower"));
		assertEquals("s5", m.get("l_"));
		assertEquals("s4", m.get("a".repeat(40)));
		assertEquals("s6", m.get("a".repeat(40) + "_2"));
		assertEquals(m, Adoption.allocate(List.of(new LotSite("s6", "a".repeat(60), 0, 0), new LotSite("s5", null, 0, 0), new LotSite("s4", "a".repeat(60), 0, 0),
			new LotSite("s3", "1st_tower", 0, 0), new LotSite("s2", "House 1!", 0, 0), new LotSite("s10", "house_1", 0, 0), new LotSite("s9", "house_1", 0, 0))),
			"the same sites in any order give the same map");
	}

	@Test
	void anIntentIsClosedByItsReceiptOrItsRefusal() {
		Settlement s = town();
		assertTrue(Adoption.openIntent(s).isEmpty());
		var intent = Adoption.intent("steward.b1.adopt", inv(), 4L);
		s = s.withLog(intent);
		assertEquals(intent.siteGroup(), Adoption.openIntent(s).orElseThrow().siteGroup(), "the saved intent (its op id given by the log)");
		assertTrue(Adoption.standing(s).isEmpty());
		assertEquals("set_1", s.id());
		// the intent is never undone: the newest undoable is still the last build
		assertEquals(List.of("s3", "r2"), s.lastUndoable().orElseThrow().siteIds());
		Settlement refused = s.withLog(Adoption.released(intent.siteGroup(), "Not taken on: NOT_ALLOWED", 5L));
		assertTrue(Adoption.openIntent(refused).isEmpty());
		assertTrue(Adoption.standing(refused).isEmpty());
		Settlement adopted = s.withLog(Adoption.receipt(intent, "rg1", 5L));
		assertTrue(Adoption.openIntent(adopted).isEmpty());
		assertEquals("rg1", Adoption.regionOf(Adoption.standing(adopted).orElseThrow()));
		assertEquals(Map.of("house_1", "s1", "farm_1", "s2", "house_1_2", "s3"), Adoption.lotsOf(Adoption.standing(adopted).orElseThrow()));
		assertEquals(List.of("r1", "r2"), Adoption.roadsOf(Adoption.standing(adopted).orElseThrow()));
	}

	@Test
	void undoGoesGrowthsThenTheAdoptionThenTheOldBuilds() throws Exception {
		Settlement s = town();
		var intent = Adoption.intent("steward.b1.adopt", inv(), 4L);
		s = s.withLog(intent).withLog(Adoption.receipt(intent, "rg1", 5L));
		s = s.withLog(new Settlement.LogEntry(6L, Kind.PROJECT_PLACED, "Placed 1 buildings", List.of("c4", "s7"), "revision:rg1:1"));
		assertEquals("revision:rg1:1", s.lastUndoable().orElseThrow().siteGroup(), "its growth first");
		s = s.withLog(new Settlement.LogEntry(7L, Kind.PROJECT_REMOVED, "Undid 2", List.of("c4", "s7")));
		var u = s.lastUndoable().orElseThrow();
		assertEquals(Kind.TOWN_ADOPTED, u.kind(), "then the adoption (let go)");
		assertEquals("region:rg1", u.siteGroup());
		s = s.withLog(Adoption.released("region:rg1", "Let go", 8L));
		assertEquals(List.of("s3", "r2"), s.lastUndoable().orElseThrow().siteIds(), "then the old builds as before, none of their sites read as removed");
		// the town is a street town again: TownRegion no longer names the adoption's region
		assertEquals(Optional.empty(), TownRegion.pick(s, id -> Optional.of(new TownRegion.Seen(true, null, null))));
		// adopted again by another build: a new key, a new intent, a new region
		var again = Adoption.intent("steward.b2.adopt", inv(), 9L);
		s = s.withLog(again);
		assertEquals(again.siteGroup(), Adoption.openIntent(s).orElseThrow().siteGroup());
		s = s.withLog(Adoption.receipt(again, "rg4", 10L));
		assertEquals("rg4", Adoption.regionOf(Adoption.standing(s).orElseThrow()));
		assertEquals(Optional.of("rg4"), TownRegion.pick(s, id -> Optional.of(new TownRegion.Seen(true, null, null))));
		// saved and read back
		SettlementStore st = new SettlementStore();
		st.put(s);
		var back = SettlementStore.fromJson(st.toJson()).get("set_1").orElseThrow();
		assertEquals("rg4", Adoption.regionOf(Adoption.standing(back).orElseThrow()));
		assertEquals(Kind.TOWN_ADOPTED, back.lastUndoable().orElseThrow().kind());
	}

	@Test
	void theReconcileClosesWhatALostAnswerLeftOpen() {
		assertEquals(Close.RECEIPT, Adoption.intentClose(true, false));
		assertEquals(Close.NOT_ADOPTED, Adoption.intentClose(false, false));
		assertEquals(Close.NOT_ADOPTED, Adoption.intentClose(true, true), "a removed region is not an adoption that stands");
		assertEquals(Close.NONE, Adoption.standingClose(true, false, 3, 3, false, true), "it stands");
		assertEquals(Close.REMOVED, Adoption.standingClose(true, true, 3, 3, true, true), "a tombstone is a removal, never a release");
		assertEquals(Close.RELEASED, Adoption.standingClose(false, false, 3, 3, true, true), "gone with no tombstone, its sites in no region: a lost unadopt answer");
		assertEquals(Close.RELEASED, Adoption.standingClose(false, false, 3, 1, true, true), "some sites gone since, the rest in no region: still a release");
		assertEquals(Close.NONE, Adoption.standingClose(false, false, 3, 3, false, true), "gone, but its sites still read as a region's: not decided");
		assertEquals(Close.NONE, Adoption.standingClose(true, false, 3, 0, true, true), "the region stands: nothing to close");
		assertEquals(Close.NONE, Adoption.standingClose(false, false, 0, 0, true, true), "an entry with no sites, without a tombstone, is never read as let go or removed");
	}

	@Test
	void aTownWhoseRegionAndBuildingsBothVanishedIsRemovedNotLetGo() {
		assertEquals(Close.REMOVED, Adoption.standingClose(false, false, 3, 0, true, true), "region gone, no tombstone, no site standing: removed");
		assertEquals(Close.REMOVED, Adoption.standingClose(false, true, 3, 0, true, true), "and with a tombstone: removed");
		assertEquals(Close.NONE, Adoption.standingClose(false, false, 3, 0, true, false),
			"Architect lists no site at all (its site file unread): every site only looks gone, so nothing is decided");
		assertEquals(Close.REMOVED, Adoption.standingClose(true, true, 3, 0, true, false), "a tombstone decides even then");
	}

	@Test
	void siteIdsSortAsArchitectNumbersThem() {
		assertTrue(Adoption.bySiteId("s9", "s10") < 0);
		assertTrue(Adoption.bySiteId("r2", "s1") < 0);
		assertEquals(0, Adoption.bySiteId("s3", "s3"));
	}

	private static com.google.gson.JsonObject ext(String... kv) {
		var o = new com.google.gson.JsonObject();
		for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
		return o;
	}

	private static Adoption.Raw lot(String id, String settlement, String lot, int x, int z) {
		return new Adoption.Raw(id, "building", "lot", "minecraft:overworld", null, x - 4, x + 4, z - 4, z + 4, ext("steward_mc:settlement", settlement,
			"steward_mc:lot", lot), 1);
	}

	private static Adoption.Raw road(String id, String key, int x0, int x1, int z) {
		var e = ext("steward_mc:settlement", "set_1");
		e.addProperty("steward_mc:street_z", z);
		e.addProperty("steward_mc:street_x0", x0);
		e.addProperty("steward_mc:street_x1", x1);
		return new Adoption.Raw(id, "road", key, "minecraft:overworld", null, x0, x1, z - 1, z + 1, e, 1);
	}

	@Test
	void theInventoryTakesTheTownsStreetAndLotsOnly() {
		Settlement s = town();
		// a square district inside the town's land (its lots and its own street are left out by land)
		s = s.withLands(s.lands().next().withDistrict(new Settlement.District("dst_1", "East", "E", new Claim("minecraft:overworld", 50, 0, 12, -64, 320), 1L,
			List.of())));
		s = s.withLog(new Settlement.LogEntry(4L, Kind.BUILD_ADOPTED, "Took on noah's house", List.of("s9"), null));
		List<Adoption.Raw> sites = new java.util.ArrayList<>(List.of(
			road("r1", "street", -30, 20, 0),
			road("r2", "street#1", 21, 30, 0),
			road("r3", "street", 45, 60, 0),
			road("r4", "street", -30, 20, 30),
			lot("s1", "set_1", "house_1", -20, 8),
			lot("s2", "set_1", "house_1", 0, -8),
			lot("s3", "set_1", "library_1", 25, 8),
			lot("s4", "set_1", "house_1", 52, 8),
			lot("s5", "set_2", "house_1", 10, 8),
			lot("s9", "set_1", "house_1", 15, -8),
			lot("s6", "set_1", "far_1", 200, 0)));
		sites.add(new Adoption.Raw("s7", "building", "lot", "minecraft:overworld", "rg1", -5, 3, 10, 18, ext("steward_mc:settlement", "set_1", "steward_mc:lot",
			"tower_1"), 1));
		sites.add(new Adoption.Raw("s8", "building", "lot", "minecraft:the_nether", null, -5, 3, 10, 18, ext("steward_mc:settlement", "set_1", "steward_mc:lot",
			"house_1"), 1));
		sites.add(new Adoption.Raw("s10", "building", "lot", "minecraft:overworld", null, 5, 13, 10, 18, ext("steward_mc:settlement", "set_1"), 3));
		var inv = Adoption.inventoryOf(sites, s).orElseThrow();
		assertEquals(0, inv.streetZ());
		assertEquals(-30, inv.xLo());
		assertEquals(30, inv.xHi());
		assertEquals(List.of("r1", "r2"), inv.roads(), "the town's street pieces; not the district's (r3) nor another line (r4)");
		assertEquals(Map.of("house_1", "s1", "house_1_2", "s2", "library_1", "s3"), inv.lots(),
			"its lots; not the district's (s4), another settlement's (s5), a player's build (s9), one outside the claim (s6), a region's (s7), another dimension's (s8), "
				+ "one with no lot (s10)");
		assertTrue(Adoption.inventoryOf(List.of(lot("s1", "set_1", "house_1", 0, 8)), s).isEmpty(), "no street: nothing to adopt");
	}
}
