package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** (slice 8bS) With Architect's SETTLEMENT_CHANGED a round rebuilds only the dirty views and the ones a player just entered. */
class SettlementSyncTest {
	private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

	@Test
	void anOwnerNamesItsSettlement() {
		assertEquals("set_4", SettlementSync.settlementOf("steward_mc:settlement/set_4"));
		assertNull(SettlementSync.settlementOf("steward_mc:settlement/"));
		assertNull(SettlementSync.settlementOf("architect_mc:player/abc"));
		assertNull(SettlementSync.settlementOf(null));
	}

	@Test
	void aQuietRoundOnlyTracksWhichClaimEachPlayerIsIn() {
		// A stays in set_1, B stays in set_2, C stands in no claim: nothing dirty, nothing rebuilt
		var at = Map.of(A, "set_1", B, "set_2", C, "");
		var was = Map.of(A, "set_1", B, "set_2", C, "");
		assertEquals(Set.of(), SettlementSync.toRebuild(at, was, Set.of(), false));
		assertFalse(SettlementSync.looked("set_1", "set_1", Set.of()));
		assertFalse(SettlementSync.looked("", "", Set.of()), "still outside every claim: nothing sent");
	}

	@Test
	void aDirtySettlementAndAClaimJustEnteredAreRebuilt() {
		// set_2 dirty (Architect's signal, a saved change); A walked from set_1 into set_3; set_1 has nobody now
		var at = Map.of(A, "set_3", B, "set_2", C, "");
		var was = Map.of(A, "set_1", B, "set_2", C, "set_1");
		var rebuilt = SettlementSync.toRebuild(at, was, Set.of("set_2", "set_1"), false);
		assertEquals(Set.of("set_2", "set_3"), rebuilt, "a dirty settlement nobody stands in is not built");
		assertTrue(SettlementSync.looked("set_3", "set_1", rebuilt));
		assertTrue(SettlementSync.looked("", "set_1", rebuilt), "left every claim: told so");
		assertTrue(SettlementSync.toRebuild(Map.of(A, "set_9"), Map.of(), Set.of(), false).contains("set_9"), "a player new this session gets the view");
	}

	@Test
	void aFullRoundRebuildsEveryViewPlayersStandIn() {
		var at = Map.of(A, "set_1", B, "set_2", C, "");
		assertEquals(Set.of("set_1", "set_2"), SettlementSync.toRebuild(at, at, Set.of(), true));
	}
}
