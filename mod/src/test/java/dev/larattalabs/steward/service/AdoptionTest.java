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
		assertEquals(Close.NONE, Adoption.standingClose(true, false, false), "it stands");
		assertEquals(Close.REMOVED, Adoption.standingClose(true, true, true), "a tombstone is a removal, never a release");
		assertEquals(Close.RELEASED, Adoption.standingClose(false, false, true), "gone with no tombstone, its sites in no region: a lost unadopt answer");
		assertEquals(Close.NONE, Adoption.standingClose(false, false, false), "gone, but its sites still read as a region's: not decided");
	}

	@Test
	void siteIdsSortAsArchitectNumbersThem() {
		assertTrue(Adoption.bySiteId("s9", "s10") < 0);
		assertTrue(Adoption.bySiteId("r2", "s1") < 0);
		assertEquals(0, Adoption.bySiteId("s3", "s3"));
	}
}
