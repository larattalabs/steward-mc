package dev.larattalabs.steward.layout;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.layout.VillageLayout.Front;
import dev.larattalabs.steward.layout.VillageLayout.Lot;
import dev.larattalabs.steward.layout.VillageLayout.LotSpec;
import dev.larattalabs.steward.layout.VillageLayout.Plan;
import dev.larattalabs.steward.layout.VillageLayout.Rules;
import dev.larattalabs.steward.model.Claim;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VillageLayoutTest {
	private static final Claim CLAIM = new Claim("minecraft:overworld", 0, 0, 64, -64, 320);

	private static Grid flat() {
		return Grid.flat(-64, -64, 129, 129, 70);
	}

	private static List<LotSpec> specs(int n, int sx, int sz) {
		List<LotSpec> l = new ArrayList<>();
		for (int i = 0; i < n; i++) l.add(new LotSpec("lot_" + i, i % 3 == 0 ? "house" : "cabin", sx, sz));
		return l;
	}

	private static boolean overlap(Lot a, Lot b, int gap) {
		return a.x() <= b.maxX() + gap && a.maxX() >= b.x() - gap && a.z() <= b.maxZ() + gap && a.maxZ() >= b.z() - gap;
	}

	@Test
	void placesEveryLotOnFlatGround() {
		Plan p = VillageLayout.plan(CLAIM, flat(), specs(10, 13, 13), Rules.defaults());
		assertEquals(10, p.lots().size());
		assertTrue(p.unplaced().isEmpty());
	}

	@Test
	void lotsStayInsideTheClaimAndNeverOverlap() {
		Plan p = VillageLayout.plan(CLAIM, flat(), specs(14, 15, 12), Rules.defaults());
		for (Lot l : p.lots()) {
			assertTrue(CLAIM.containsBox("minecraft:overworld", l.x(), 0, l.z(), l.maxX(), 0, l.maxZ()), "inside the claim: " + l);
		}
		for (int i = 0; i < p.lots().size(); i++) {
			for (int j = i + 1; j < p.lots().size(); j++) {
				assertFalse(overlap(p.lots().get(i), p.lots().get(j), 3), p.lots().get(i) + " vs " + p.lots().get(j));
			}
		}
	}

	@Test
	void lotsFaceTheStreetOnBothSides() {
		Plan p = VillageLayout.plan(CLAIM, flat(), specs(8, 11, 11), Rules.defaults());
		for (Lot l : p.lots()) {
			if (l.maxZ() < p.streetZ()) assertEquals(Front.SOUTH, l.front());
			else assertEquals(Front.NORTH, l.front());
			assertTrue(Math.abs((l.front() == Front.SOUTH ? l.maxZ() : l.z()) - p.streetZ()) <= 3, "the front edge is next to the street");
		}
		assertTrue(p.lots().stream().anyMatch(l -> l.front() == Front.SOUTH));
		assertTrue(p.lots().stream().anyMatch(l -> l.front() == Front.NORTH));
	}

	@Test
	void waterBlocksLotsAndTheyAreReportedUnplaced() {
		Grid g = flat();
		for (int x = -64; x <= 64; x++) for (int z = -64; z <= 64; z++) if (z > -20 && z < 20) g.set(x, z, 62, true); // a river across the middle
		Plan p = VillageLayout.plan(CLAIM, g, specs(6, 13, 13), Rules.defaults());
		for (Lot l : p.lots()) {
			for (int x = l.x(); x <= l.maxX(); x++) for (int z = l.z(); z <= l.maxZ(); z++) assertFalse(g.waterAt(x, z));
		}
	}

	@Test
	void steepGroundIsSkipped() {
		Grid g = flat();
		for (int x = -64; x <= 64; x++) for (int z = -64; z <= 64; z++) g.set(x, z, 70 + (x + 64) / 2, false); // a steady slope of 0.5 per block
		Plan p = VillageLayout.plan(CLAIM, g, specs(6, 13, 13), new Rules(3, 3, 3, 2));
		for (Lot l : p.lots()) {
			int lo = Integer.MAX_VALUE;
			int hi = Integer.MIN_VALUE;
			for (int x = l.x(); x <= l.maxX(); x++) { lo = Math.min(lo, g.heightAt(x, l.z())); hi = Math.max(hi, g.heightAt(x, l.z())); }
			assertTrue(hi - lo <= 3);
		}
	}

	@Test
	void tooManyLotsLeavesTheRestUnplaced() {
		Plan p = VillageLayout.plan(CLAIM, flat(), specs(200, 20, 20), Rules.defaults());
		assertTrue(p.lots().size() < 200);
		assertEquals(200, p.lots().size() + p.unplaced().size());
	}

	@Test
	void sameInputGivesTheSamePlan() {
		Plan a = VillageLayout.plan(CLAIM, flat(), specs(9, 13, 11), Rules.defaults());
		Plan b = VillageLayout.plan(CLAIM, flat(), specs(9, 13, 11), Rules.defaults());
		assertEquals(a, b);
	}

	@Test
	void groundYIsTheMedianOfTheFootprint() {
		Grid g = flat();
		g.set(0, -20, 73, false);
		Plan p = VillageLayout.plan(CLAIM, g, specs(1, 9, 9), Rules.defaults());
		assertEquals(70, p.lots().get(0).groundY());
	}

	@Test
	void streetCellsSpanTheLots() {
		Plan p = VillageLayout.plan(CLAIM, flat(), specs(6, 13, 13), Rules.defaults());
		List<int[]> cells = VillageLayout.streetCells(p, 3);
		assertEquals((p.streetX1() - p.streetX0() + 1) * 3, cells.size());
		assertEquals(p.streetX0(), cells.get(0)[0]);
	}
}
