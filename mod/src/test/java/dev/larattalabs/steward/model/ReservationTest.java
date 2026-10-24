package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Settlement.District;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A beside district's reservation (docs/DISTRICTS.md section 9): where it goes, its request claim, and its undo. */
class ReservationTest {
	private static final String OW = "minecraft:overworld";
	private static final Claim TOWN = new Claim(OW, 0, 0, 64, -64, 320);
	/** The town region's claim, smaller than the settlement's (a flat village in a bigger claim). */
	private static final int[] REGION = {-40, -40, 40, 40};

	@Test
	void eastAndWestReserveTheSquareBeyondTheClaimAndReachIntoTheRegionBySixteen() {
		var e = DistrictRules.reserve(TOWN, REGION, List.of(), List.of(), List.of(), "E", 48, "Harbour");
		assertTrue(e.ok(), e.note());
		assertEquals(new Claim(OW, 113, 0, 48, -64, 320), e.claim(), "touching the claim's east edge (x 64), owning none of it");
		assertArrayEquals(new int[] {25, -48, 161, 48}, e.request(), "from 16 columns inside the region's claim (25..40) to the reservation's far edge");
		var w = DistrictRules.reserve(TOWN, REGION, List.of(), List.of(), List.of(), "W", 48, "Mill");
		assertEquals(-113, w.claim().centerX());
		assertArrayEquals(new int[] {-161, -48, -25, 48}, w.request());
		assertFalse(DistrictRules.reserve(TOWN, REGION, List.of(), List.of(), List.of(), "N", 48, "Hill").ok(), "north and south are square districts (Q3)");
	}

	@Test
	void aReservationIsRefusedOverADistrictAnotherSettlementOrAProtectedArea() {
		var square = new District("dst_1", "Old", "E", new Claim(OW, 113, 60, 48, -64, 320), 1L, List.of());
		assertFalse(DistrictRules.reserve(TOWN, REGION, List.of(square), List.of(), List.of(), "E", 48, "Harbour").ok());
		assertFalse(DistrictRules.reserve(TOWN, REGION, List.of(), List.of(new Claim(OW, 200, 0, 40, -64, 320)), List.of(), "E", 48, "Harbour").ok());
		var garden = new Settlement.Area("pa_1", "garden", OW, 100, 0, 104, 4, 1L);
		var r = DistrictRules.reserve(TOWN, REGION, List.of(), List.of(), List.of(garden), "E", 48, "Harbour");
		assertFalse(r.ok());
		assertTrue(r.note().contains("garden"), r.note());
	}

	private static Settlement reserved() {
		Settlement s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);
		Claim c = new Claim(OW, 113, 0, 48, -64, 320);
		var l = s.lands().next();
		District d = new District("dst_" + l.serial(), "Harbour", "E", c, 2L, List.of(), false, District.BESIDE, District.RESERVED, "rg1", null, Land.of(c), null,
			"steward.set_1.dst_1");
		return s.withLands(l.withDistrict(d)).withLog(Settlement.LogEntry.ofDistrict(2L, Settlement.Kind.DISTRICT_FOUNDED, "Reserved Harbour", d.id()));
	}

	@Test
	void aReservationIsUndoableWhileOnlyReserved() {
		Settlement s = reserved();
		var u = s.lastUndoable();
		assertTrue(u.isPresent(), "a founding with no site, its district reserved");
		assertEquals("dst_1", u.get().district());
		// once it is building, its build's entry undoes it, not the founding
		District building = s.lands().district("dst_1").orElseThrow();
		building = new District(building.id(), building.name(), building.direction(), building.claim(), building.at(), building.roads(), false, building.kind(),
			District.BUILDING, building.parent(), "rg9", building.savedLand(), null, building.opKey());
		assertTrue(s.withLands(s.lands().withDistrict(building)).lastUndoable().isEmpty());
		// given up: gone, and nothing left to undo
		assertTrue(s.withLands(s.lands().withoutDistrict("dst_1")).lastUndoable().isEmpty());
	}

	@Test
	void theDistrictTargetIsSavedAndAnOlderEntryReadsWithNone() {
		SettlementStore st = new SettlementStore();
		st.put(reserved());
		Settlement back = SettlementStore.fromJson(st.toJson()).get("set_1").orElseThrow();
		assertEquals("dst_1", back.log().get(back.log().size() - 1).district());
		assertEquals(reserved().log(), back.log());
		assertNull(back.log().get(0).district(), "the founding of the settlement names no district");
	}
}
