package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DistrictRulesTest {
	private static final String OW = "minecraft:overworld";
	private static final Claim TOWN = new Claim(OW, 0, 0, 64, -64, 320);
	private static final DistrictRules.Street STREET = new DistrictRules.Street(10, -40, 30);

	private static Settlement.District district(String id, String dir, DistrictRules.Placement p) {
		return new Settlement.District(id, id, dir, p.claim(), 1L, List.of(new Settlement.Road("r_" + id, p.road(), DistrictRules.ROAD_WIDTH)));
	}

	@Test
	void eachWayTheDistrictTouchesTheVillageSquareAndItsRoadStaysOnTheLand() {
		for (String dir : DistrictRules.DIRECTIONS) {
			var p = DistrictRules.place(TOWN, STREET, List.of(), List.of(), List.of(), dir, 48);
			assertTrue(p.ok(), dir + ": " + p.note());
			Claim d = p.claim();
			assertFalse(d.overlaps(TOWN), dir + " does not overlap the town");
			// it touches: one block further would leave a gap
			int gapX = Math.abs(d.centerX() - TOWN.centerX()) - d.radius() - TOWN.radius();
			int gapZ = Math.abs(d.centerZ() - TOWN.centerZ()) - d.radius() - TOWN.radius();
			assertTrue(gapX == 1 || gapZ == 1, dir + " is adjacent");
			assertNull(DistrictRules.roadProblem(p.road(), List.of(TOWN, d), List.of()), dir);
			// the road ends inside the district, in its margin, clear of where its lots go
			var end = p.road().get(p.road().size() - 1);
			assertTrue(d.contains(OW, end.x(), 70, end.z()), dir);
			boolean inMargin = Math.abs(end.x() - d.centerX()) > d.radius() - DistrictRules.MARGIN || Math.abs(end.z() - d.centerZ()) > d.radius() - DistrictRules.MARGIN;
			assertTrue(inMargin, dir + " ends in the margin");
			// it starts past the town street's end, clear of the street
			var start = p.road().get(0);
			assertTrue(start.x() > STREET.x1() || start.x() < STREET.x0(), dir + " starts past the street's end");
		}
	}

	@Test
	void eastAndWestContinueTheTownStreetsLine() {
		var e = DistrictRules.place(TOWN, STREET, List.of(), List.of(), List.of(), "E", 48);
		assertEquals(STREET.z(), e.claim().centerZ());
		assertEquals(STREET.z(), e.streetZ());
		assertEquals(2, e.road().size(), "a straight road");
		var n = DistrictRules.place(TOWN, STREET, List.of(), List.of(), List.of(), "N", 48);
		assertNull(n.streetZ());
		assertTrue(n.claim().centerZ() < -64);
	}

	@Test
	void withoutAStreetTheRoadStartsBesideTheStone() {
		var p = DistrictRules.place(TOWN, null, List.of(), List.of(), List.of(), "E", 48);
		assertTrue(p.ok(), p.note());
		assertEquals(new Settlement.Pt(3, 0), p.road().get(0));
		for (int[] c : DistrictRules.cells(p.road(), 3)) assertFalse(c[0] == 0 && c[1] == 0, "the road leaves the stone alone");
	}

	@Test
	void aBigClaimKeepsItsDistrictsByTheVillage() {
		Claim big = ClaimRules.withRadius(TOWN, 512);
		var p = DistrictRules.place(big, STREET, List.of(), List.of(), List.of(), "E", 64);
		assertTrue(p.ok(), p.note());
		assertEquals(128 + 1 + 64, p.claim().centerX(), "next to the village square, inside the claim");
	}

	@Test
	void refusalsNameTheirReason() {
		var e = DistrictRules.place(TOWN, STREET, List.of(), List.of(), List.of(), "E", 48);
		var own = List.of(district("dst_1", "E", e));
		assertTrue(DistrictRules.place(TOWN, STREET, own, List.of(), List.of(), "E", 48).note().contains("already a district to the east"));
		assertTrue(DistrictRules.place(TOWN, STREET, List.of(), List.of(), List.of(), "X", 48).note().contains("north, east"));
		Claim neighbour = new Claim(OW, 200, 0, 48, -64, 320);
		assertTrue(DistrictRules.place(TOWN, STREET, List.of(), List.of(neighbour), List.of(), "E", 48).note().contains("settlement at 200,0"));
		var garden = new Settlement.Area("pa_1", "the garden", OW, 40, 5, 50, 15, 1L);
		assertTrue(DistrictRules.place(TOWN, STREET, List.of(), List.of(), List.of(garden), "E", 48).note().contains("protected area \"the garden\""));
	}

	@Test
	void districtsDoNotOverlapEachOther() {
		Claim small = ClaimRules.withRadius(TOWN, 48);
		var street = new DistrictRules.Street(-30, -20, 40);
		var e = DistrictRules.place(small, street, List.of(), List.of(), List.of(), "E", 64);
		assertTrue(e.ok(), e.note());
		var n = DistrictRules.place(small, street, List.of(district("dst_1", "E", e)), List.of(), List.of(), "N", 64);
		assertFalse(n.ok());
		assertTrue(n.note().contains("dst_1"), n.note());
	}

	@Test
	void theLinkRunsAlongTheEdgeToTheStreetsNearerEnd() {
		var n = DistrictRules.place(TOWN, STREET, List.of(), List.of(), List.of(), "N", 48);
		var d = district("dst_1", "N", n);
		Claim c = d.claim();
		var dStreet = new DistrictRules.Street(c.centerZ(), c.centerX() - 30, c.centerX() + 30);
		var link = DistrictRules.link(d, dStreet);
		assertEquals(n.road().get(n.road().size() - 1), link.get(0));
		assertEquals(new Settlement.Pt(dStreet.x0() - 1, dStreet.z()), link.get(link.size() - 1), "next to the street's first cell");
		assertNull(DistrictRules.roadProblem(link, List.of(c), List.of()));
		// it stays in the margin and on the street's line, clear of the lots beside the street
		for (int[] cell : DistrictRules.cells(link, 3)) {
			boolean margin = cell[0] < c.centerX() - c.radius() + DistrictRules.MARGIN;
			boolean streetLine = Math.abs(cell[1] - dStreet.z()) <= 1 && cell[0] <= dStreet.x0();
			assertTrue(margin || streetLine, cell[0] + "," + cell[1]);
		}
		var e = DistrictRules.place(TOWN, STREET, List.of(), List.of(), List.of(), "E", 48);
		var de = district("dst_2", "E", e);
		var eLink = DistrictRules.link(de, new DistrictRules.Street(STREET.z(), de.claim().centerX() - 20, de.claim().centerX() + 20));
		assertEquals(2, eLink.size(), "east: straight on");
	}

	@Test
	void roadCellsAreABandAlongTheLegs() {
		var cells = DistrictRules.cells(List.of(new Settlement.Pt(0, 0), new Settlement.Pt(4, 0), new Settlement.Pt(4, 3)), 3);
		List<String> keys = new ArrayList<>();
		for (int[] c : cells) keys.add(c[0] + "," + c[1]);
		assertEquals(keys.size(), new java.util.HashSet<>(keys).size(), "no cell twice");
		assertTrue(keys.contains("-1,-1") && keys.contains("5,4") && keys.contains("2,1"));
		assertFalse(keys.contains("0,3"));
	}

	@Test
	void northAndSouthGoRoundTheStreetsEndNeverOverALot() {
		// the street reaches the east margin: the road goes round its west end, into the district along its east edge
		var east = new DistrictRules.Street(10, -20, 62);
		var p = DistrictRules.place(TOWN, east, List.of(), List.of(), List.of(), "N", 48);
		assertTrue(p.ok(), p.note());
		assertEquals(new Settlement.Pt(-21, 10), p.road().get(0));
		for (int[] c : DistrictRules.cells(p.road(), 3)) assertTrue(c[0] <= east.x0() || Math.abs(c[1] - east.z()) > 1 || c[0] > east.x1(), "against the street's end, never along it");
		assertTrue(p.road().stream().allMatch(pt -> pt.x() < east.x0()), "never over the lots, which end at the street's ends");
		var d = district("dst_1", "N", p);
		var link = DistrictRules.link(d, new DistrictRules.Street(d.claim().centerZ(), d.claim().centerX() - 30, d.claim().centerX() + 30));
		assertEquals(d.claim().centerX() + 31, link.get(link.size() - 1).x(), "entered on the east side: the street's east end");
		assertNull(DistrictRules.roadProblem(p.road(), List.of(TOWN, d.claim()), List.of()));
		// a street from margin to margin leaves no way round
		var both = DistrictRules.place(TOWN, new DistrictRules.Street(10, -62, 62), List.of(), List.of(), List.of(), "S", 48);
		assertFalse(both.ok());
		assertTrue(both.note().contains("no way round"), both.note());
		// a big claim: the road is not pulled in to the village square's edge
		var big = DistrictRules.place(ClaimRules.withRadius(TOWN, 512), new DistrictRules.Street(10, -100, 126), List.of(), List.of(), List.of(), "N", 48);
		assertTrue(big.ok(), big.note());
		assertEquals(128, big.road().get(1).x(), "two past the street's end");
	}
}
