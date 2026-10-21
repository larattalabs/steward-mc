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
}
