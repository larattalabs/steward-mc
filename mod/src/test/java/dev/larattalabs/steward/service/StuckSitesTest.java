package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.DiagnoseScope;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.architect.api.RepairSuspect;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.SettlementStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** (slice DRS) Architect DR's suspects raised to the settlement in the steward's words; the repair for operators only, the op its actor; said once. */
class StuckSitesTest {
	private static final Claim TOWN = new Claim("minecraft:overworld", 0, 0, 64, -64, 320);

	@AfterEach
	void tearDown() {
		StuckSites.resetForTest();
		Settlements.resetForTest();
		ArchitectAccess.reset();
	}

	@Test
	void eachReasonIsSaidInTheStewardsWords() {
		assertEquals("The hall stopped part way when the world closed suddenly, and looks stuck", StuckSites.line("stuck", "The hall"));
		assertEquals("The hall came back with blocks missing after the world closed suddenly", StuckSites.line("lagging", "The hall"));
		assertEquals("The hall came back with its stored items not accounted for after the world closed suddenly", StuckSites.line("custody", "The hall"));
		assertEquals("A repair of the hall was cut off before it finished", StuckSites.line("repair_open", "The hall"));
		assertEquals("The hall couldn't be checked after the world closed suddenly", StuckSites.line("unread", "The hall"));
		assertEquals("The hall may not have come back right after the world closed suddenly", StuckSites.line("something_new", "The hall"));
	}

	@Test
	void theLineNamesAtMostThreePartsAndHowAnOperatorRepairsIt() {
		assertEquals("A; B. An operator can look it over and repair it: /steward repair set_1.", StuckSites.said(List.of("A", "B"), "set_1"));
		assertEquals("A; B; C; and 2 more parts. An operator can look it over and repair it: /steward repair set_1.",
			StuckSites.said(List.of("A", "B", "C", "D", "E"), "set_1"));
		assertTrue(StuckSites.said(List.of("A", "B", "C", "D"), "x").contains("and 1 more part."));
	}

	@Test
	void aSuspectIsSaidOncePerSiteRegionAndReason() {
		assertEquals("s1|rg1|stuck", StuckSites.key(new RepairSuspect("s1", "rg1", "stuck")));
		assertEquals("|rg1|lagging", StuckSites.key(new RepairSuspect(null, "rg1", "lagging")));
		assertNotEquals(StuckSites.key(new RepairSuspect("s1", null, "stuck")), StuckSites.key(new RepairSuspect("s1", null, "lagging")));
	}

	@Test
	void onlyASettlementsOwnSitesAreItsSuspects() {
		assertEquals("set_1", StuckSites.settlementOf("steward_mc:settlement/set_1"));
		assertNull(StuckSites.settlementOf("steward_mc:settlement/"));
		assertNull(StuckSites.settlementOf("player:abc"));
		assertNull(StuckSites.settlementOf(null));
	}

	@Test
	void aRegionIsLookedOverOnceAndASiteOutsideOneByItself() {
		var scopes = StuckSites.scopes(List.of(new RepairSuspect("s1", "rg1", "stuck"), new RepairSuspect("s2", "rg1", "lagging"), new RepairSuspect("s9", null, "lagging")));
		assertEquals(List.of(DiagnoseScope.region("rg1"), DiagnoseScope.site("s9")), scopes);
	}

	@Test
	void onlyAnOperatorMayRepairAnyoneElseIsToldAnOperatorCan() {
		assertNull(StuckSites.gate(true, "set_1"));
		assertEquals("Only an operator can repair a build. Ask one to run /steward repair set_1.", StuckSites.gate(false, "set_1"));
		// Architect's own PERMISSION refusal reads the same
		assertEquals(StuckSites.gate(false, "set_1"), StuckSites.refused(Reason.PERMISSION, "Vale", "set_1"));
	}

	@Test
	void theRepairCommandRefusesANonOperatorBeforeAskingArchitectAnything() {
		Settlements.useForTest(new SettlementStore(), null);
		Settlements.store().put(Settlement.founded("set_op", "Vale", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L));
		var fake = new FakeArchitect();
		fake.install();
		List<String> said = new ArrayList<>();
		var r = StuckSites.repair(null, null, false, "set_op", false, said::add);
		assertFalse(r.ok());
		assertEquals(StuckSites.gate(false, "set_op"), r.message());
		// an op with no player (the console) is no actor: refused the same, as Architect refuses an actor-less repair
		var console = StuckSites.repair(null, null, true, "set_op", true, said::add);
		assertFalse(console.ok());
		assertEquals(StuckSites.gate(false, "set_op"), console.message());
		assertTrue(fake.calls.isEmpty(), "nothing asked of Architect: " + fake.calls);
		assertTrue(said.isEmpty());
		assertEquals("No settlement nope.", StuckSites.repair(null, null, true, "nope", false, said::add).message());
	}

	@Test
	void refusalsAreSaidInTheStewardsWords() {
		assertEquals("Vale changed since the look. Run /steward repair set_1 again to look afresh.", StuckSites.refused(Reason.REPAIR_STALE, "Vale", "set_1"));
		assertEquals("Part of Vale is still being worked on. Repair it once that is done: /steward repair set_1.", StuckSites.refused(Reason.SITE_BUSY, "Vale", "set_1"));
		assertEquals("The repair of Vale didn't go through (field_limit).", StuckSites.refused(Reason.FIELD_LIMIT, "Vale", "set_1"));
	}

	@Test
	void aDryRunSaysWhatARepairWouldDoAndThatItIsFinal() {
		assertEquals("A repair of Vale would put back 214 blocks and leave 3 blocks that are someone's own. It is final (no undo). Run /steward repair set_1 confirm"
			+ " to repair it.", StuckSites.wouldDo("Vale", "set_1", 214, 3, 1));
		assertTrue(StuckSites.wouldDo("Vale", "set_1", 10, 0, 2).contains("10 blocks over 2 parts."));
		assertEquals("Nothing of Vale can be put back by a repair.", StuckSites.wouldDo("Vale", "set_1", 0, 0, 1));
	}

	@Test
	void aRepairIsSaidOnceHoweverManySitesItRepaired() {
		assertEquals("Repaired: 214 blocks put back where the world lost them, 3 blocks left as someone changed them.", StuckSites.repairedLine(214, 3));
		assertEquals("Repaired: 1 block put back where the world lost them.", StuckSites.repairedLine(1, 0));
		assertTrue(StuckSites.firstOf("7f3a"));
		assertFalse(StuckSites.firstOf("7f3a"), "the second site of the same repair: not said again");
		assertTrue(StuckSites.firstOf("8b01"));
	}
}
