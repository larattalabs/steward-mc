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

	@Test
	void aRefundPastCapacityIsSaidWithHowFarOverAndTheWaysBackUnder() {
		// (slice OVF-S, S2) Architect 0.77.0's keyed refunds enter the stock past capacity: said with the excess and the ways back under, never as items
		// beside the crate; Steward can't read a paused state, so the ways are said, not which case it is
		String ways = " To bring it under, take out at a crate what no build needs, add a crate (/steward stockpile s1 add), or resume a paused build that needs"
			+ " what it holds.";
		assertEquals("The stockpile now holds more than it fits: 3 stacks over. Until it is back under, only items your running builds still need go in." + ways,
			Stockpiles.overCapacityWords(3, "s1"));
		assertEquals("The stockpile now holds more than it fits: 1 stack over. Until it is back under, only items your running builds still need go in." + ways,
			Stockpiles.overCapacityWords(1, "s1"));
		assertNull(Stockpiles.overCapacityWords(0, "s1"), "within capacity: nothing said");
		assertNull(Stockpiles.overCapacityWords(-5, "s1"));
	}

	@Test
	void theCountLineShowsTheCapacityAndHowFarOverItIs() {
		// (slice OVF-S2) /steward stockpile's first line
		assertEquals("Stockpile: 1 crate, 12 of 27 stacks used.", Stockpiles.countLine(1, 12, 27));
		assertEquals("Stockpile: 2 crates, 54 of 54 stacks used.", Stockpiles.countLine(2, 54, 54), "full is not over");
		assertEquals("Stockpile: 1 crate, 30 of 27 stacks used, over capacity by 3 stacks.", Stockpiles.countLine(1, 30, 27));
		assertEquals("Stockpile: 1 crate, 28 of 27 stacks used, over capacity by 1 stack.", Stockpiles.countLine(1, 28, 27));
		assertEquals("Stockpile: 0 crates, 2 of 0 stacks used, over capacity by 2 stacks.", Stockpiles.countLine(0, 2, 0), "every crate gone");
	}
}
