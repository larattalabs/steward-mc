package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.RemovalStop;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.SettlementStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (slice DRS) Architect DR-S's refused save on a survival removal: keyed on the typed stop ({@code RegionView.removalStop}, feature {@code removalStop}),
 * said in the steward's words ("the world couldn't be saved just now, nothing was taken down; ask again"), never Architect's raw text (it names chunk
 * files); a growth's take-up left UNDOING with a stage wait reads as waiting, not refused.
 */
class RemovalStopsTest {
	private static final Claim TOWN = new Claim("minecraft:overworld", 0, 0, 64, -64, 320);
	/** Architect 0.78.0's raw refused-save blocker (Groups, DR-S): never shown. */
	private static final String RAW = "the removal of group grp_rg_v was not started: Stockpiles: the ledger was not saved: chunk writes not durable [r.0.0.mca]";

	@TempDir
	java.nio.file.Path dir;
	FakeArchitect a;
	final List<String> said = new ArrayList<>();

	@BeforeEach
	void setUp() {
		a = new FakeArchitect();
		a.features = Set.of("regions", "kitVersion", "sitesLoadHealthy", RemovalStops.FEATURE);
		a.install();
		Settlements.useForTest(new SettlementStore(), dir.resolve("steward-settlements.json"));
	}

	@AfterEach
	void tearDown() {
		ArchitectAccess.reset();
		Settlements.resetForTest();
	}

	// ------------------------------------------------------------------ pure

	@Test
	void onlyANotStartedStopChangesTheWords() {
		assertNull(RemovalStops.undoStopped(null, RAW, null, "set_1"), "no typed stop: the ordinary line");
		assertNull(RemovalStops.undoStopped(RemovalStop.NONE, RAW, null, "set_1"));
		assertNull(RemovalStops.undoStopped(RemovalStop.PARTIAL, RAW, null, "set_1"), "part was taken down: the ordinary stopped line");
		assertEquals("Undo stopped: the world couldn't be saved just now, nothing was taken down; ask again (/steward undo set_1).",
			RemovalStops.undoStopped(RemovalStop.NOT_STARTED, RAW, null, "set_1"));
		assertEquals("Undo stopped before anything was taken down: a player stayed in s3 for 10 minutes. Clear it and run /steward undo set_1 again.",
			RemovalStops.undoStopped(RemovalStop.NOT_STARTED, "a player stayed in s3 for 10 minutes", null, "set_1"));
	}

	@Test
	void theRefusedSavesRawTextIsNeverNoted() {
		assertEquals(RemovalStops.SAVE_REFUSED, RemovalStops.noted(RAW));
		assertEquals("a chest of the player's", RemovalStops.noted("a chest of the player's"));
		assertFalse(RemovalStops.saveRefused("a chest of the player's"));
		assertFalse(RemovalStops.saveRefused(null));
	}

	@Test
	void aGrowthWaitsOnlyWhenItsRevisionIsUndoingWithAWait() {
		var undoing = new dev.larattalabs.architect.api.RevisionView(2, 1, 1, "p", "sha", "UNDOING", 0L, List.of(), List.of(), List.of(), 0L, null, true, "stage s1: x");
		assertTrue(RemovalStops.growthWaits(undoing));
		assertFalse(RemovalStops.growthWaits(new dev.larattalabs.architect.api.RevisionView(2, 1, 1, "p", "sha", "UNDOING", 0L, List.of(), List.of(), List.of(), 0L,
			null, true, null)), "no wait: not this case");
		assertFalse(RemovalStops.growthWaits(new dev.larattalabs.architect.api.RevisionView(2, 1, 1, "p", "sha", "PLACED", 0L, List.of(), List.of(), List.of(), 0L,
			null, true, "stage s1: x")));
		assertFalse(RemovalStops.growthWaits(null));
		assertFalse(RemovalStops.growthWaiting().contains("chunk"));
	}

	// ------------------------------------------------------------------ the undo paths, over Architect's fake

	private Settlement village(String id) {
		Settlement s = Settlement.founded(id, "Vale", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L)
			.withLog(new Settlement.LogEntry(2L, Settlement.Kind.PROJECT_PLACED, "the village", List.of("v1"), "region:rg_v"));
		a.region("rg_v", s.owner());
		a.site("v1", s.owner(), "rg_v");
		Settlements.store().put(s);
		return s;
	}

	private static Settlement.LogEntry placed(Settlement s) {
		return s.log().stream().filter(e -> "region:rg_v".equals(e.siteGroup())).findFirst().orElseThrow();
	}

	private Boolean undo(Settlement s) {
		AtomicReference<Boolean> done = new AtomicReference<>();
		Undo.region(null, s, placed(s), "rg_v", Map.of(), said::add, done::set, true);
		a.runServer();
		return done.get();
	}

	@Test
	void aRefusedSaveIsSaidInTheStewardsWordsAndTheRegionStaysAsItWas() {
		Settlement s = village("set_drs1");
		a.removeAnswer = FakeArchitect.Answer.BLOCKED;
		a.blocker = RAW;
		a.regions.get("rg_v").removalStop = RemovalStop.NOT_STARTED;
		assertEquals(Boolean.FALSE, undo(s));
		assertTrue(said.contains("Undo stopped: the world couldn't be saved just now, nothing was taken down; ask again (/steward undo set_drs1)."), said.toString());
		assertTrue(said.stream().noneMatch(l -> l.contains("chunk") || l.contains("not durable")), said.toString());
		var notes = Settlements.store().get(s.id()).orElseThrow().log().stream().filter(e -> e.kind() == Settlement.Kind.NOTE).map(Settlement.LogEntry::text).toList();
		assertTrue(notes.stream().anyMatch(n -> n.startsWith(Undo.STOPPED) && n.endsWith(RemovalStops.SAVE_REFUSED)), notes.toString());
		assertTrue(notes.stream().noneMatch(n -> n.contains("chunk")), notes.toString());
		assertTrue(Undo.interrupted(Settlements.store().get(s.id()).orElseThrow()).isEmpty(), "the undo's note is closed: asked again by the player");
	}

	@Test
	void anOtherNotStartedStopSaysNothingWasTakenDownWithItsBlocker() {
		Settlement s = village("set_drs2");
		a.removeAnswer = FakeArchitect.Answer.BLOCKED;
		a.regions.get("rg_v").removalStop = RemovalStop.NOT_STARTED;
		undo(s);
		assertTrue(said.stream().anyMatch(l -> l.startsWith("Undo stopped before anything was taken down: blocked by a chest of the player's")), said.toString());
	}

	@Test
	void withoutTheFeatureOrPartWayTheOrdinaryLineStands() {
		Settlement s = village("set_drs3");
		a.removeAnswer = FakeArchitect.Answer.BLOCKED;
		a.regions.get("rg_v").removalStop = RemovalStop.PARTIAL;
		undo(s);
		assertTrue(said.contains("Undo stopped: blocked by a chest of the player's. Clear it and run /steward undo set_drs3 again."), said.toString());
		said.clear();
		a.features = Set.of("regions");
		a.regions.get("rg_v").removalStop = RemovalStop.NOT_STARTED;
		undo(Settlements.store().get(s.id()).orElseThrow());
		assertTrue(said.contains("Undo stopped: blocked by a chest of the player's. Clear it and run /steward undo set_drs3 again."), "no removalStop feature: never read");
	}

	@Test
	void aGrowthLeftUndoingWithAStageWaitIsWaitingNotRefused() {
		Settlement s = Settlement.founded("set_drs4", "Vale", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L);
		var r = a.region("rg_g", s.owner());
		r.rev(1, 1, "PLACED", "g1");
		a.site("g1", s.owner(), "rg_g");
		a.undoAnswer = FakeArchitect.Answer.BLOCKED;
		a.blocker = RAW;
		a.undoWaits = "stage rv1:solids: " + RAW;
		AtomicReference<Boolean> done = new AtomicReference<>();
		Growths.undo(null, s.owner(), new Growths.Ref("rg_g", 1), said::add, done::set);
		a.runServer();
		assertEquals(Boolean.FALSE, done.get(), "not taken up yet: the entry stays");
		assertEquals(List.of(RemovalStops.growthWaiting()), said);
	}
}
