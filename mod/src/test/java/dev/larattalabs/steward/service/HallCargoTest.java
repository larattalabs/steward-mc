package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The steward's words for a storage hall's refused undo read only the halls whose containers Architect's cargo refusal names (M4, the diff review's findings 1 and 4). */
class HallCargoTest {
	@Test
	void theSitesToEmptyComeFromArchitectsRefusal() {
		String m = "NOT_ALLOWED: undo of region rg3 to position 1 refused, nothing written: Move these out of s17 first (removing it puts the old terrain back"
			+ " over them): chest at 105, 66, 578 (595 items). Or confirm again with force: they are lost (blockers); Move these out of s12 first (...): chest at 1, 2, 3 (4 items)";
		assertEquals(List.of(new net.minecraft.core.BlockPos(105, 66, 578), new net.minecraft.core.BlockPos(1, 2, 3)), HallCargo.namedCells(m));
		assertTrue(HallCargo.cargoRefusal(m));
		assertFalse(HallCargo.cargoRefusal("OCCUPIED: undo of region rg1 to position 0 refused, nothing written: noahlaratta is in site c12 of revision 1"),
			"a player standing in a site is not cargo");
		assertFalse(HallCargo.cargoRefusal(null));
	}
}
