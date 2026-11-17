package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.Settlement.Kind;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.util.DurableWrite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** (slice STORE-FSYNC review P1) A save that fails after its rename (the folder's force) leaves the file as reported: the change written back out. */
class SettlementsDurableTest {
	@AfterEach
	void tearDown() {
		DurableWrite.FAIL_DIR_FORCE_AFTER_RENAME.set(0);
		DurableWrite.FAIL_NEXT.clear();
		SettlementRunner.clearForTest();
		Settlements.resetForTest();
	}

	@Test
	void aChangeWhoseSaveFailedAfterTheRenameIsNotWhatTheFileHolds(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("steward-settlements.json");
		Settlement s = Settlement.founded("set_1", "Gea", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		SettlementStore store = new SettlementStore();
		store.put(s);
		store.save(file);
		String before = Files.readString(file);
		Settlements.useForTest(store, file);

		DurableWrite.FAIL_DIR_FORCE_AFTER_RENAME.set(1);
		var r = Settlements.log("set_1", Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed a hall", List.of(), "x", Settlement.Outcome.DONE));
		assertNotNull(r.error());
		// memory rolled back, and the file (renamed in before the force failed) written back to match it
		assertEquals(s, Settlements.store().get("set_1").orElseThrow());
		assertEquals(before, Files.readString(file));
		assertEquals(s, SettlementStore.load(file).get("set_1").orElseThrow());

		// the next change saves as usual
		var ok = Settlements.log("set_1", Settlement.LogEntry.of(3L, Kind.PROJECT_PLACED, "Placed a hall", List.of(), "x", Settlement.Outcome.DONE));
		assertNull(ok.error());
		assertEquals(s.log().size() + 1, SettlementStore.load(file).get("set_1").orElseThrow().log().size());
	}

	@Test
	void aWriteBackThatFailsTooDisablesSettlementsForTheSession(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("steward-settlements.json");
		Settlement s = Settlement.founded("set_1", "Gea", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		SettlementStore store = new SettlementStore();
		store.put(s);
		store.save(file);
		Settlements.useForTest(store, file);
		// the save renamed in, then the folder force failed; the write-back failed the same way
		DurableWrite.FAIL_DIR_FORCE_AFTER_RENAME.set(2);
		var r = Settlements.log("set_1", Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed a hall", List.of(), "x", Settlement.Outcome.DONE));
		assertNotNull(r.error());
		var next = Settlements.log("set_1", Settlement.LogEntry.of(3L, Kind.PROJECT_PLACED, "Placed a hall", List.of(), "x", Settlement.Outcome.DONE));
		assertTrue(next.error().contains("disabled"), next.error());
	}

	@Test
	void aWriteBackThatFailsBeforeItsRenameStopsEveryStartApprovalAndRunningBuild(@TempDir Path dir) throws Exception {
		// (batch-076 review P1-2) the save renamed in and its folder force failed; the write-back failed before its rename: the file holds the change
		// reported as failed, memory the settlement as it was
		Path file = dir.resolve("steward-settlements.json");
		Settlement s = Settlement.founded("set_1", "Gea", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		SettlementStore store = new SettlementStore();
		store.put(s);
		store.save(file);
		Settlements.useForTest(store, file);
		SettlementRunner running = SettlementRunner.restoredForTest(BuildStoreTestAccess.saved());
		assertTrue(running.aliveForTest(), "a running build acts before the failure");
		assertFalse(SettlementRunner.running().isEmpty());

		DurableWrite.FAIL_NEXT.addAll(List.of(DurableWrite.TestFail.AFTER_RENAME, DurableWrite.TestFail.BEFORE_RENAME));
		var r = Settlements.log("set_1", Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed a hall", List.of(), "x", Settlement.Outcome.DONE));
		assertNotNull(r.error());
		assertTrue(DurableWrite.FAIL_NEXT.isEmpty(), "both writes were tried");
		assertEquals(s.log().size() + 1, SettlementStore.load(file).get("set_1").orElseThrow().log().size(), "the file holds the change");
		assertEquals(s, Settlements.store().get("set_1").orElseThrow(), "memory does not");
		assertTrue(Settlements.disabled());

		// a start and an approval are refused with the one line, before anything about the player is read
		var start = Actions.start(null, "set_1", 5, 10);
		assertFalse(start.ok());
		assertEquals(Settlements.DISABLED, start.message());
		var approve = Actions.decide(null, "set_1", "approve", null, null, 0);
		assertFalse(approve.ok());
		assertEquals(Settlements.DISABLED, approve.message());
		var undo = Actions.undo(null, "set_1", m -> {});
		assertEquals(Settlements.DISABLED, undo.message());
		// nothing new is started from a proposal or a growth, and the running build's callbacks and events do nothing more
		assertFalse(SettlementRunner.canSave());
		assertFalse(running.aliveForTest());
		assertTrue(SettlementRunner.running().isEmpty());
	}
}
