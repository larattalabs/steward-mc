package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Land;
import dev.larattalabs.steward.model.Settlement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Slice DG (docs/GROWTH.md): which districts take a building, the sweep's regions, and a growing district's land read again. */
class DistrictGrowthTest {
	private static final String OW = "minecraft:overworld";
	private static final Claim EAST = new Claim(OW, 113, 0, 48, -64, 320);
	private static final Claim WEST = new Claim(OW, -113, 0, 48, -64, 320);

	private static Settlement.District district(String id, Claim c, String kind, String state, String region, Land land) {
		return new Settlement.District(id, id, "E", c, 2L, List.of(), false, kind, state, "rg_town", region, land, null, "steward.s." + id);
	}

	@Test
	void aStandingDistrictRegionTakesABuildingOnlyWhileArchitectGrowsRegions() {
		var standing = district("dst_1", EAST, Settlement.District.BESIDE, Settlement.District.STANDING, "rg_d", Land.of(EAST));
		assertTrue(Districts.takes(standing, true));
		assertFalse(Districts.takes(standing, false), "an Architect without growth: it waits, as before");
		var reserved = district("dst_2", EAST, Settlement.District.BESIDE, Settlement.District.RESERVED, null, Land.of(EAST));
		assertTrue(Districts.takes(reserved, false), "its first building builds its region");
		var building = district("dst_3", EAST, Settlement.District.ALONG, Settlement.District.BUILDING, null, Land.of(EAST));
		assertFalse(Districts.takes(building, true), "being built by another build: never a second build in it");
		var outpost = district("dst_4", EAST, Settlement.District.OUTPOST, Settlement.District.STANDING, "rg_o", Land.of(EAST));
		assertTrue(Districts.takes(outpost, true), "an outpost grows on its own street (GR 2.4)");
		var square = new Settlement.District("dst_5", "Square", "N", EAST, 2L, List.of(), false, Settlement.District.SQUARE, Settlement.District.STANDING, null, null, null,
			null, null);
		assertTrue(Districts.takes(square, false));
		assertFalse(Districts.grows(square), "a square district is Steward's street pieces, never a revision");
	}

	@Test
	void theSweepReadsTheTownThenEachStandingDistrictRegionOnce() {
		var a = district("dst_1", EAST, Settlement.District.BESIDE, Settlement.District.STANDING, "rg_d", Land.of(EAST));
		var b = district("dst_2", WEST, Settlement.District.BESIDE, Settlement.District.RESERVED, null, Land.of(WEST));
		var c = district("dst_3", WEST, Settlement.District.OUTPOST, Settlement.District.STANDING, "rg_town", Land.of(WEST));
		assertEquals(List.of("rg_town", "rg_d"), VillageSweep.growable("rg_town", List.of(a, b, c)), "a reserved district has no region; none twice");
		assertEquals(List.of("rg_d", "rg_town"), VillageSweep.growable(null, List.of(a, b, c)), "the town gone: its districts' regions are still swept");
	}

	@Test
	void aBesideDistrictKeepsItsReservationAndTakesWhatItsRegionHoldsOutsideTheTown() {
		var d = district("dst_1", EAST, Settlement.District.BESIDE, Settlement.District.STANDING, "rg_d", Land.of(EAST));
		// grown past the reservation's east edge (161): a growth's street extended
		Land outside = Land.ofRect(OW, 100, -2, 170, 2);
		Land after = Districts.landAfter(d, outside, List.of(d));
		assertTrue(after.contains(OW, 113, 40), "the reservation is kept");
		assertTrue(after.contains(OW, 170, 0), "what the growth took outside the town");
		// the growth undone: Architect's outside shrinks back, and so does the land past the reservation
		Land back = Districts.landAfter(new Settlement.District(d.id(), d.name(), d.direction(), d.claim(), d.at(), d.roads(), d.linkUndone(), d.kind(), d.state(),
			d.parent(), d.region(), after, d.follow(), d.opKey()), Land.ofRect(OW, 100, -2, 140, 2), List.of(d));
		assertFalse(back.contains(OW, 170, 0), "the growth's land is let go with it");
		assertTrue(back.contains(OW, 113, 40), "never the reservation");
	}

	@Test
	void aQuarterOwnsExactlyWhatItsRegionHoldsAndNeverAnotherDistrictsColumns() {
		var q = district("dst_1", EAST, Settlement.District.ALONG, Settlement.District.STANDING, "rg_q", Land.ofRect(OW, 100, 0, 120, 10));
		var other = district("dst_2", WEST, Settlement.District.BESIDE, Settlement.District.STANDING, "rg_w", Land.ofRect(OW, 130, 0, 140, 10));
		Land outside = Land.ofRect(OW, 100, 0, 140, 10);
		Land after = Districts.landAfter(q, outside, List.of(q, other));
		assertTrue(after.contains(OW, 125, 5));
		assertFalse(after.contains(OW, 135, 5), "another district's land is never taken");
		assertNull(Districts.landAfter(q, null, List.of(q)), "nothing read: the saved land stays");
	}
}
