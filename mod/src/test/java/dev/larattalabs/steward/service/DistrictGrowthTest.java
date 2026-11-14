package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.gateway.DistrictLand;
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
		Land after = Districts.landAfter(d, new DistrictLand.Read(outside), List.of(d));
		assertTrue(after.contains(OW, 113, 40), "the reservation is kept");
		assertTrue(after.contains(OW, 170, 0), "what the growth took outside the town");
		// the growth undone: Architect's outside shrinks back, and so does the land past the reservation
		Land back = Districts.landAfter(new Settlement.District(d.id(), d.name(), d.direction(), d.claim(), d.at(), d.roads(), d.linkUndone(), d.kind(), d.state(),
			d.parent(), d.region(), after, d.follow(), d.opKey()), new DistrictLand.Read(Land.ofRect(OW, 100, -2, 140, 2)), List.of(d));
		assertFalse(back.contains(OW, 170, 0), "the growth's land is let go with it");
		assertTrue(back.contains(OW, 113, 40), "never the reservation");
	}

	@Test
	void aQuarterOwnsExactlyWhatItsRegionHoldsAndNeverAnotherDistrictsColumns() {
		var q = district("dst_1", EAST, Settlement.District.ALONG, Settlement.District.STANDING, "rg_q", Land.ofRect(OW, 100, 0, 120, 10));
		var other = district("dst_2", WEST, Settlement.District.BESIDE, Settlement.District.STANDING, "rg_w", Land.ofRect(OW, 130, 0, 140, 10));
		Land outside = Land.ofRect(OW, 100, 0, 140, 10);
		Land after = Districts.landAfter(q, new DistrictLand.Read(outside), List.of(q, other));
		assertTrue(after.contains(OW, 125, 5));
		assertFalse(after.contains(OW, 135, 5), "another district's land is never taken");
		assertNull(Districts.landAfter(q, null, List.of(q)), "nothing trustworthy read: the saved land stays");
		assertNull(Districts.landAfter(q, new DistrictLand.Read(null), List.of(q)), "a standing quarter never owns nothing");
		var b = district("dst_3", EAST, Settlement.District.BESIDE, Settlement.District.STANDING, "rg_b", Land.ofRect(OW, 60, 0, 200, 10));
		assertEquals(Land.of(EAST), Districts.landAfter(b, new DistrictLand.Read(null), List.of(b)), "a beside district: back to its reservation");
	}

	@Test
	void aPartOrMalformedLandReadIsNeverTrusted() {
		assertNull(DistrictLand.outsideWhole(null, OW));
		assertNull(DistrictLand.outsideWhole(JsonParser.parseString("{\"architect_mc:land\":{\"v\":2}}").getAsJsonObject(), OW), "no outside");
		assertNull(DistrictLand.outsideWhole(JsonParser.parseString("{\"architect_mc:land\":{\"outside\":{\"runs\":[[1,2,3,2],[1,2,3]]}}}").getAsJsonObject(), OW),
			"one malformed run: the whole read is refused");
		assertNull(DistrictLand.outsideWhole(JsonParser.parseString("{\"architect_mc:land\":{\"outside\":{\"runs\":[[1,2,3,4]]}}}").getAsJsonObject(), OW));
		assertNull(DistrictLand.outsideWhole(JsonParser.parseString("{\"architect_mc:land\":{\"outside\":{\"runs\":[[1.9,2.1,3.9,2.9]]}}}").getAsJsonObject(), OW),
			"a fraction is never read as an integer");
		for (String bad : new String[] {"1e-400", "1.999999999999999999999", "3000000000"})
			assertNull(DistrictLand.outsideWhole(JsonParser.parseString("{\"architect_mc:land\":{\"outside\":{\"runs\":[[" + bad + ",2,3,2]]}}}").getAsJsonObject(), OW), bad);
		assertNotNull(DistrictLand.outsideWhole(JsonParser.parseString("{\"architect_mc:land\":{\"outside\":{\"runs\":[[1.0,2,3,2]]}}}").getAsJsonObject(), OW),
			"an integral number written with a point is still that integer");
		var ok = DistrictLand.outsideWhole(JsonParser.parseString("{\"architect_mc:land\":{\"outside\":{\"runs\":[[5,2,1,2]]}}}").getAsJsonObject(), OW);
		assertTrue(ok.land().contains(OW, 3, 2));
		assertNull(DistrictLand.outsideWhole(JsonParser.parseString("{\"architect_mc:land\":{\"outside\":{\"runs\":[]}}}").getAsJsonObject(), OW).land(),
			"read whole, and empty");
	}

	@Test
	void aGrowthNeverReachesAnotherSettlementsOrASiblingsLand() {
		Land town = Land.of(new Claim(OW, 0, 0, 64, -64, 320));
		var q = district("dst_1", EAST, Settlement.District.ALONG, Settlement.District.STANDING, "rg_q", Land.ofRect(OW, 65, -64, 90, 64));
		// the quarter's region claim: its strip (65..160) and 16 columns into the town
		Land claim = Land.ofRect(OW, 49, -64, 160, 64);
		assertNull(Districts.growthConflict(claim, town, q, List.of(q), List.of()), "free ground, or its own");
		var sib = district("dst_2", EAST, Settlement.District.OUTPOST, Settlement.District.STANDING, "rg_o", Land.ofRect(OW, 120, 40, 140, 64));
		assertTrue(Districts.growthConflict(claim, town, q, List.of(q, sib), List.of()).contains("dst_2's land"));
		assertTrue(Districts.growthConflict(claim, town, q, List.of(q), List.of(Land.ofRect(OW, 150, 0, 300, 10))).contains("another settlement"));
		assertNull(Districts.growthConflict(claim, town, q, List.of(q), List.of(Land.ofRect(OW, 200, 0, 300, 10))), "beyond its claim: no matter");
		// (review round 2) the settlement's claim expanded over the sibling: the sibling's land is still never the growth's
		Land expanded = Land.of(new Claim(OW, 0, 0, 256, -64, 320));
		assertTrue(Districts.growthConflict(claim, expanded, q, List.of(q, sib), List.of()).contains("dst_2's land"));
		// a beside district's claim is its reservation and the seam in the town: nothing past them, never a conflict
		var b = district("dst_3", EAST, Settlement.District.BESIDE, Settlement.District.STANDING, "rg_b", Land.of(EAST));
		assertNull(Districts.growthConflict(Land.ofRect(OW, 49, -48, 161, 48), town, b, List.of(b), List.of(Land.ofRect(OW, 162, 0, 300, 10))));
	}
}
