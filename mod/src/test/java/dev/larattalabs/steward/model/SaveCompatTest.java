package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Settlement.Kind;
import dev.larattalabs.steward.model.Settlement.Outcome;
import dev.larattalabs.steward.model.Settlement.Recovery;
import dev.larattalabs.steward.service.BuildStore;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Saves written by earlier builds keep loading (fixtures/saves/v<n>: real files from dev worlds). A format change that breaks one fails here before it can
 * disable the settlements of a player's world.
 */
class SaveCompatTest {
	private static String fixture(String name) throws Exception {
		try (InputStream in = SaveCompatTest.class.getClassLoader().getResourceAsStream("saves/" + name)) {
			assertNotNull(in, name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void theV1SettlementsOfARealWorldLoadAndMigrateToOperations() throws Exception {
		SettlementStore st = SettlementStore.fromJson(fixture("v1/steward-settlements.json"));
		assertEquals(1, st.readFormat());
		assertEquals(4, st.all().size());
		Settlement grey = st.get("set_4").orElseThrow();
		assertEquals("Greywater Hamlet", grey.name());
		List<Settlement.LogEntry> log = grey.log();
		for (int i = 0; i < log.size(); i++) assertEquals("op_" + (i + 1), log.get(i).op(), "every entry gets its op id by position");
		assertEquals(Kind.CLAIM_CHANGED, log.get(6).kind(), log.get(6).text());
		Settlement.LogEntry upd = log.get(7);
		assertEquals(Kind.UPDATED, upd.kind(), upd.text());
		assertEquals(1, upd.changes().size());
		assertEquals("cottage_1", upd.changes().get(0).lot());
		assertEquals(2, upd.changes().get(0).to());
		assertEquals(-1, upd.changes().get(0).from(), "the earlier version was not recorded");
		assertEquals(Recovery.NONE, upd.recovery(), "nothing to revert to when the earlier version is unknown");
		// the project placed after the undone one is still undoable, by its site group
		var undo = grey.lastUndoable().orElseThrow();
		assertEquals("g5", undo.siteGroup());
		assertEquals(Outcome.DONE, undo.outcome());
	}

	@Test
	void aMigratedFileIsBackedUpOnceAndSavesInTheNewFormat(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("steward-settlements.json");
		Files.writeString(f, fixture("v1/steward-settlements.json"));
		SettlementStore st = SettlementStore.load(f);
		Path bak = dir.resolve("steward-settlements.json.v1.bak");
		assertTrue(Files.exists(bak));
		assertEquals(fixture("v1/steward-settlements.json"), Files.readString(bak), "the backup is the file as it was");
		st.save(f);
		SettlementStore again = SettlementStore.load(f);
		assertEquals(SettlementStore.FORMAT, again.readFormat());
		assertEquals(st.get("set_4").orElseThrow().log(), again.get("set_4").orElseThrow().log());
	}

	@Test
	void aNewerFormatIsRefusedNotOverwritten() throws Exception {
		String newer = fixture("v1/steward-settlements.json").replaceFirst("\"format\": 1", "\"format\": " + (SettlementStore.FORMAT + 1));
		assertThrows(com.google.gson.JsonParseException.class, () -> SettlementStore.fromJson(newer));
	}

	@Test
	void theV1BuildsFileLoads() throws Exception {
		BuildStore b = BuildStore.fromJson(fixture("v1/steward-builds.json"));
		assertEquals(1, b.all().size());
		assertEquals(dev.larattalabs.steward.pipeline.Pipeline.Phase.AWAITING_MASSING_APPROVAL, b.all().get(0).state().phase());
	}

	@Test
	void operationsGetIdsVersionsAndRecovery() {
		Settlement s = Settlement.founded("set_1", "Here", new Claim("minecraft:overworld", 0, 0, 64, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		s = s.withLog(Settlement.LogEntry.of(2L, Kind.PROJECT_PLACED, "Placed 2 buildings", List.of(new Settlement.SiteChange("s1", "inn", 0, 1),
			new Settlement.SiteChange("s2", "smithy", 0, 1)), "g1", Outcome.DONE));
		s = s.withLog(Settlement.LogEntry.of(3L, Kind.UPDATED, "Updated inn to version 2", List.of(new Settlement.SiteChange("s1", "inn", 1, 2)), null, Outcome.DONE));
		s = s.withLog(Settlement.LogEntry.of(4L, Kind.UPDATED, "Could not update smithy", List.of(new Settlement.SiteChange("s2", "smithy", 1, 2)), null, Outcome.FAILED));
		List<Settlement.LogEntry> log = s.log();
		assertEquals(List.of("op_1", "op_2", "op_3", "op_4"), log.stream().map(Settlement.LogEntry::op).toList());
		assertEquals(List.of("s1", "s2"), log.get(1).siteIds(), "an operation's sites are its changes' sites");
		assertEquals(Recovery.UNDO_PROJECT, log.get(1).recovery());
		assertEquals(Recovery.REVERT_VERSION, log.get(2).recovery());
		assertEquals(Recovery.NONE, log.get(3).recovery(), "a failed update changed nothing");
		assertEquals(Kind.CLAIM_CHANGED, s.withClaim(new Claim("minecraft:overworld", 0, 0, 96, -64, 320), 5L).log().get(4).kind());
	}
}
