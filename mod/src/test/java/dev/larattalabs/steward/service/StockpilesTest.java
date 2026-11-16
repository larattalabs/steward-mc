package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class StockpilesTest {
	@Test
	void theCratesAndARingRoundThemAreKeptClear() {
		assertArrayEquals(new int[] {9, -4, 12, -2}, Stockpiles.ringed(List.of(new BlockPos(10, 64, -3), new BlockPos(11, 64, -3))));
		assertArrayEquals(new int[] {-1, -1, 1, 1}, Stockpiles.ringed(List.of(new BlockPos(0, 70, 0))));
		assertNull(Stockpiles.ringed(List.of()));
	}

	@Test
	void aNewCrateKeepsOffTheTownsStreet() {
		// (slice CRATE) an east-west street at z 10 from x -20 to 20: three columns either side of its line and past its ends are not for a crate
		var ew = Stockpiles.offStreet(new dev.larattalabs.steward.model.DistrictRules.Street(10, -20, 20));
		assertFalse(ew.test(0, 10), "on the street (the claim centre of a town built at once)");
		assertFalse(ew.test(0, 13));
		assertFalse(ew.test(23, 10), "past its end");
		assertTrue(ew.test(0, 14));
		assertTrue(ew.test(24, 10));
		var ns = Stockpiles.offStreet(new dev.larattalabs.steward.model.DistrictRules.Street(5, -8, 8, true));
		assertFalse(ns.test(5, 0));
		assertFalse(ns.test(8, 0));
		assertTrue(ns.test(9, 0));
		assertTrue(Stockpiles.offStreet(null).test(0, 0), "no street: anywhere");
	}
}
