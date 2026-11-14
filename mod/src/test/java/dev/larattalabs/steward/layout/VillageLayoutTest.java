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

	private static LotSpec hinted(String id, String placement) {
		return new LotSpec(id, "house", 13, 13, id, null, false, placement);
	}

	private static double toCentre(Lot l) {
		return Math.hypot((l.x() + l.maxX()) / 2.0 - CLAIM.centerX(), (l.z() + l.maxZ()) / 2.0 - CLAIM.centerZ());
	}

	@Test
	void aNearWaterBuildingStandsByTheLakeTheOthersNeedNot() {
		// a lake along the east edge of the claim (x >= 45)
		Grid g = flat();
		for (int x = 45; x <= 64; x++) for (int z = -64; z <= 64; z++) g.set(x, z, 62, true);
		List<LotSpec> specs = new ArrayList<>(specs(5, 13, 13));
		specs.add(hinted("dock_1", "near_water"));
		Plan p = VillageLayout.plan(CLAIM, g, specs, Rules.defaults());
		Lot dock = p.lots().stream().filter(l -> l.id().equals("dock_1")).findFirst().orElseThrow();
		int maxX = p.lots().stream().mapToInt(Lot::maxX).max().orElseThrow();
		assertEquals(maxX, dock.maxX(), "the dock is the lot nearest the lake: " + p.lots());
		assertTrue(45 - dock.maxX() <= 12, "within a few blocks of the water: " + dock);
	}

	@Test
	void centralGoesToTheHeartAndEdgeToTheOutskirts() {
		List<LotSpec> specs = new ArrayList<>();
		specs.add(hinted("barn_1", "edge"));
		specs.addAll(specs(6, 13, 13));
		specs.add(hinted("hall_1", "central"));
		Plan p = VillageLayout.plan(CLAIM, flat(), specs, Rules.defaults());
		Lot hall = p.lots().stream().filter(l -> l.id().equals("hall_1")).findFirst().orElseThrow();
		Lot barn = p.lots().stream().filter(l -> l.id().equals("barn_1")).findFirst().orElseThrow();
		for (Lot l : p.lots()) assertTrue(toCentre(hall) <= toCentre(l), "the hall is the most central lot: " + l);
		for (Lot l : p.lots()) assertTrue(toCentre(barn) >= toCentre(l) - 0.01, "the barn is the outermost lot: " + l);
	}

	@Test
	void builtGroundIsNeverUsed() {
		// the player's build covers the middle of the claim
		Grid g = flat();
		for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) g.setBuilt(x, z, true);
		Plan p = VillageLayout.plan(CLAIM, g, specs(6, 13, 13), Rules.defaults());
		assertFalse(p.lots().isEmpty());
		for (Lot l : p.lots()) for (int x = l.x(); x <= l.maxX(); x++) for (int z = l.z(); z <= l.maxZ(); z++) assertFalse(g.builtAt(x, z), "a lot on built ground: " + l);
	}

	@Test
	void aLotThatFitsNowhereDoesNotHoldUpTheOthers() {
		List<LotSpec> l = new ArrayList<>();
		l.add(new LotSpec("keep_1", "keep", 140, 30)); // wider than the claim: fits nowhere
		l.addAll(specs(4, 14, 12));
		Plan p = VillageLayout.plan(CLAIM, flat(), l, Rules.defaults());
		assertEquals(4, p.lots().size(), "the four that fit are laid out");
		assertEquals(List.of("keep_1"), p.unplaced().stream().map(LotSpec::id).toList());
	}

	@Test
	void twoLotsThatFitNowhereDoNotHoldUpTheOthers() {
		List<LotSpec> l = new ArrayList<>();
		l.add(new LotSpec("keep_1", "keep", 140, 30));
		l.add(new LotSpec("keep_2", "keep", 150, 30));
		l.addAll(specs(4, 14, 12));
		Plan p = VillageLayout.plan(CLAIM, flat(), l, Rules.defaults());
		assertEquals(4, p.lots().size());
		assertEquals(java.util.Set.of("keep_1", "keep_2"), p.unplaced().stream().map(LotSpec::id).collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void anAdditionIsLaidOutOnTheSettlementsOwnStreet() {
		Plan p = VillageLayout.plan(CLAIM, flat(), specs(1, 13, 12), Rules.defaults(), 9);
		assertEquals(9, p.streetZ(), "the existing street, not a new line");
		assertEquals(1, p.lots().size());
	}

	@Test
	void anAdditionStaysBetweenItsStreetsEnds() {
		Plan p = VillageLayout.plan(CLAIM, flat(), specs(6, 13, 12), Rules.defaults(), 9, -20, 20);
		for (Lot l : p.lots()) assertTrue(l.x() >= -20 && l.maxX() <= 20, l.toString());
		assertTrue(p.lots().size() <= 4, "two a side fit along 41 blocks: " + p.lots().size());
	}

	@Test
	void anAdditionsStreetMayLieOffTheClaimsCentre() {
		// centre x = 0, the street runs 10..33: the vault still fits along it
		Plan p = VillageLayout.plan(CLAIM, flat(), specs(1, 11, 9), Rules.defaults(), 9, 10, 33);
		assertEquals(1, p.lots().size());
		assertTrue(p.lots().get(0).x() >= 10 && p.lots().get(0).maxX() <= 33, p.lots().get(0).toString());
	}

	// ------------------------------------------------------------------ (slice NS) a north-south street

	/** Rough ground with a pond and something built: enough that a layout's choices depend on where things are. */
	private static Grid rough() {
		Grid g = Grid.flat(-64, -64, 129, 129, 70);
		java.util.Random r = new java.util.Random(7);
		for (int x = -64; x <= 64; x++) for (int z = -64; z <= 64; z++) g.set(x, z, 70 + (int) (3 * Math.sin(x / 9.0) + 2 * Math.cos(z / 13.0)) + r.nextInt(2), false);
		for (int x = 20; x <= 34; x++) for (int z = -40; z <= -28; z++) g.set(x, z, 62, true);
		for (int x = -50; x <= -44; x++) for (int z = 10; z <= 18; z++) g.setBuilt(x, z, true);
		return g;
	}

	private static List<LotSpec> mixed() {
		List<LotSpec> l = new ArrayList<>(specs(9, 13, 11));
		l.add(new LotSpec("hall", "hall", 17, 15, "hall", null, true, "central"));
		l.add(new LotSpec("mill", "mill", 11, 11, "mill", null, false, "near_water"));
		l.add(new LotSpec("watch", "tower", 9, 9, "tower", null, false, "edge"));
		return l;
	}

	@Test
	void aNorthSouthLayoutIsTheEastWestOneWithXAndZSwapped() {
		Claim off = new Claim("minecraft:overworld", 3, -5, 60, -64, 320);
		Claim offT = new Claim("minecraft:overworld", -5, 3, 60, -64, 320);
		for (Grid g : List.of(flat(), rough())) {
			Plan ns = VillageLayout.plan(off, g, mixed(), Rules.defaults(), true);
			Plan ew = VillageLayout.plan(offT, VillageLayout.transpose(g), mixed(), Rules.defaults());
			assertTrue(ns.northSouth());
			assertFalse(ew.northSouth());
			assertEquals(VillageLayout.transpose(ew, true), ns, "the same rules on either axis");
			assertFalse(ns.lots().isEmpty());
		}
	}

	@Test
	void aNorthSouthStreetsLotsFaceItFromTheEastAndWest() {
		Plan p = VillageLayout.plan(CLAIM, rough(), specs(8, 13, 11), Rules.defaults(), true);
		assertEquals(8, p.lots().size());
		for (Lot l : p.lots()) {
			// a lot west of the street faces east (its street is to the east), one east of it faces west
			if (l.maxX() < p.streetZ()) assertEquals(Front.EAST, l.front());
			else assertEquals(Front.WEST, l.front());
			assertTrue(Math.abs((l.front() == Front.EAST ? l.maxX() : l.x()) - p.streetZ()) <= 3, "the front edge is next to the street's line x: " + l);
			assertEquals(13, l.along(), "a spec's sizeX is its width along the street");
			assertEquals(11, l.deep());
			assertEquals(11, l.sizeX());
			assertEquals(13, l.sizeZ());
			assertTrue(l.z() >= p.streetX0() && l.maxZ() <= p.streetX1(), "the street runs past every lot along z");
			assertTrue(CLAIM.containsBox("minecraft:overworld", l.x(), 0, l.z(), l.maxX(), 0, l.maxZ()));
		}
		assertTrue(p.lots().stream().anyMatch(l -> l.front() == Front.EAST) && p.lots().stream().anyMatch(l -> l.front() == Front.WEST));
		// the street's cells run along z on the line's x
		var cells = VillageLayout.streetCells(p, 3);
		assertTrue(cells.stream().allMatch(c -> Math.abs(c[0] - p.streetZ()) <= 1 && c[1] >= p.streetX0() && c[1] <= p.streetX1()));
		assertArrayEquals(new int[] {p.streetZ() - 2, p.streetX0(), p.streetZ() + 2, p.streetX1()}, p.spanBox(p.streetX1(), p.streetX0(), 2), "either way along it");
		assertArrayEquals(new int[] {p.streetZ(), 5}, p.at(5));
	}

	@Test
	void anAdditionOnANorthSouthStreetKeepsToItsLineAndStretch() {
		Plan p = VillageLayout.plan(CLAIM, flat(), specs(4, 11, 11), Rules.defaults(), 6, -20, 25, true);
		assertEquals(6, p.streetZ());
		for (Lot l : p.lots()) assertTrue(l.z() >= -20 && l.maxZ() <= 25, "kept between z -20 and 25: " + l);
	}

	@Test
	void aPlanSavedBeforeNorthSouthStreetsReadsAsEastWest() {
		// BuildStore keeps a build's plan as Gson JSON: one saved before the field was added has no northSouth
		var gson = new com.google.gson.Gson();
		Plan old = gson.fromJson("{\"lots\":[],\"streetZ\":4,\"streetX0\":-10,\"streetX1\":12,\"streetY\":70,\"unplaced\":[]}", Plan.class);
		assertFalse(old.northSouth());
		assertEquals(4, old.streetZ());
		Plan ns = VillageLayout.plan(CLAIM, flat(), specs(3, 11, 11), Rules.defaults(), true);
		assertEquals(ns, gson.fromJson(gson.toJson(ns), Plan.class), "a north-south plan round-trips");
	}
}
