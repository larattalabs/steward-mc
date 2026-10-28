package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WallRepairTest {
	@Test
	void onlyK3sWallRefusalIsRepaired() {
		assertEquals("tower_3", SettlementRunner.wallLotOf("lot tower_3 on wall curtain_wall: the design leaves the wall open at 10,64,20 ... (4 cells)"));
		assertEquals("gate_1", SettlementRunner.wallLotOf("lot gate_1 on wall town_wall: the passage floor is not standable at 3,64,9 (2 cells)"));
		// a changed site, or the land, is the stale re-check's
		assertNull(SettlementRunner.wallLotOf("site s12 changed since the check"));
		assertNull(SettlementRunner.wallLotOf("the land under lot tower_3 on wall curtain_wall changed"));
		assertNull(SettlementRunner.wallLotOf(null));
	}
}
