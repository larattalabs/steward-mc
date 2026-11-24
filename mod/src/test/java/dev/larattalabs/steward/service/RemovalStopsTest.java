package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.RemovalStop;
import dev.larattalabs.architect.api.RemovalStopCause;
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
		a.features = Set.of("regions", "kitVersion", "sitesLoadHealthy", RemovalStops.FEATURE, RemovalStops.CAUSE_FEATURE);
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
	void aRegionsStopIsSaidInTheStewardsWordsForEachTypedCause() {
		// (undo-words) one phrase per cause, shared with a growth's wait (causeWords); the generic line for OTHER or none
		assertEquals("Undo stopped: the world couldn't be saved just now, nothing was taken down; ask again (/steward undo set_1).",
			RemovalStops.undoStopped(RemovalStop.NOT_STARTED, RemovalStopCause.SAVE_REFUSED, "set_1"));
		assertEquals("Undo stopped (the world couldn't be saved just now), part of it was taken down. Ask again with /steward undo set_1.",
			RemovalStops.undoStopped(RemovalStop.PARTIAL, RemovalStopCause.SAVE_REFUSED, "set_1"));
		assertEquals("Undo stopped (someone is standing in what it takes up), nothing was taken down. Run /steward undo set_1 again once they step out.",
			RemovalStops.undoStopped(RemovalStop.NOT_STARTED, RemovalStopCause.PLAYER_IN_BOX, "set_1"));
		assertEquals("Undo stopped (other buildings stand over part of it), part of it was taken down. Run /steward undo set_1 again once what stands over it is taken down.",
			RemovalStops.undoStopped(RemovalStop.PARTIAL, RemovalStopCause.COVERED, "set_1"));
		assertEquals("Undo stopped (its containers hold more than was put in them), 2 of 5 sites were taken down. Run /steward undo set_1 again once what was added to its containers is taken out.",
			RemovalStops.undoStopped(RemovalStop.PARTIAL, RemovalStopCause.CARGO, "set_1", "2 of 5 sites"));
		assertEquals("Undo stopped (your things are in its way: filled containers, pets, item frames or dropped items). Run /steward undo set_1 again once your things are moved out.",
			RemovalStops.undoStopped(null, RemovalStopCause.THINGS_IN_BOX, "set_1"), "no typed stop: nothing is claimed about what was taken down");
		assertEquals("Undo stopped: something is in its way, nothing was taken down. Run /steward undo set_1 again once it is clear.",
			RemovalStops.undoStopped(RemovalStop.NOT_STARTED, RemovalStopCause.OTHER, "set_1"));
		assertEquals("Undo stopped: something is in its way. Run /steward undo set_1 again once it is clear.", RemovalStops.undoStopped(null, null, "set_1"),
			"no feature: the generic line");
		assertEquals(RemovalStops.undoStopped(null, null, "set_1"), RemovalStops.undoStopped(RemovalStop.NONE, null, "set_1"));
		for (var c : RemovalStopCause.values()) {
			String said = RemovalStops.causeWords(c);
			if (c == RemovalStopCause.OTHER) assertNull(said);
			else for (var stop : new RemovalStop[] {RemovalStop.NOT_STARTED, RemovalStop.PARTIAL, null})
				assertTrue(RemovalStops.undoStopped(stop, c, "set_1").contains(said), c + "/" + stop + ": its own words");
		}
	}

	@Test
	void theRefusedSavesRawTextIsNeverNoted() {
		assertEquals(RemovalStops.SAVE_REFUSED, RemovalStops.noted(RemovalStop.NOT_STARTED, RemovalStopCause.SAVE_REFUSED));
		assertEquals(RemovalStops.WORLD_NOT_SAVED, RemovalStops.noted(RemovalStop.PARTIAL, RemovalStopCause.SAVE_REFUSED), "part was taken down: never \"nothing\"");
		assertEquals(RemovalStops.causeWords(RemovalStopCause.THINGS_IN_BOX), RemovalStops.noted(RemovalStop.NOT_STARTED, RemovalStopCause.THINGS_IN_BOX));
		assertEquals(RemovalStops.IN_THE_WAY, RemovalStops.noted(null, RemovalStopCause.OTHER));
		assertEquals(RemovalStops.IN_THE_WAY, RemovalStops.noted(null, null));
		assertTrue(RemovalStops.saveRefused(RemovalStopCause.SAVE_REFUSED));
		assertFalse(RemovalStops.saveRefused(RemovalStopCause.OTHER));
		assertFalse(RemovalStops.saveRefused((RemovalStopCause) null));
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
		// (8bS) a revision's stop has no typed cause: its text is never said
		assertEquals("The town's change is part way taken up and waits to carry on: nothing more is taken down until it can. It carries on when you ask again"
			+ " or when the world next starts.", RemovalStops.growthWaiting());
	}

	@Test
	void aGrowthsWaitSaysItsTypedCauseInTheStewardsWords() {
		String generic = RemovalStops.growthWaiting();
		assertEquals(generic, RemovalStops.growthWaiting(null), "no cause: only that it waits");
		assertEquals(generic, RemovalStops.growthWaiting(RemovalStopCause.OTHER), "OTHER: only that it waits");
		// a refused save: a region undo's words for it, without its "nothing was taken down" (a growth's take-up is part way)
		assertEquals("The town's change is part way taken up and waits to carry on (the world couldn't be saved just now): nothing more is taken down until it"
			+ " can. It carries on when you ask again or when the world next starts.", RemovalStops.growthWaiting(RemovalStopCause.SAVE_REFUSED));
		assertTrue(RemovalStops.SAVE_REFUSED.startsWith(RemovalStops.WORLD_NOT_SAVED), "one phrase for a refused save");
		assertTrue(RemovalStops.growthWaiting(RemovalStopCause.PLAYER_IN_BOX).contains("(someone is standing in what it takes up)"));
		assertTrue(RemovalStops.growthWaiting(RemovalStopCause.COVERED).contains("(other buildings stand over part of it)"));
		assertTrue(RemovalStops.growthWaiting(RemovalStopCause.CARGO).contains("(its containers hold more than was put in them)"));
		assertTrue(RemovalStops.growthWaiting(RemovalStopCause.THINGS_IN_BOX).contains("(your things are in its way: filled containers, pets, item frames or"
			+ " dropped items)"));
		for (var c : RemovalStopCause.values()) {
			String line = RemovalStops.growthWaiting(c);
			assertTrue(line.startsWith("The town's change is part way taken up and waits to carry on") && line.endsWith("when the world next starts."), line);
			assertFalse(line.contains("nothing was taken down"), line);
		}
	}

	@Test
	void aRevisionsCauseIsReadOnlyWithTheFeature() {
		var waiting = new dev.larattalabs.architect.api.RevisionView(2, 1, 1, "p", "sha", "UNDOING", 0L, List.of(), List.of(), List.of(), 0L, null, true, "x",
			List.of(), RemovalStopCause.SAVE_REFUSED);
		assertNull(RemovalStops.revisionCause(waiting), "no revisionStopCause feature: never read");
		a.features = Set.of("regions", RemovalStops.REVISION_CAUSE_FEATURE);
		assertEquals(RemovalStopCause.SAVE_REFUSED, RemovalStops.revisionCause(waiting));
		assertNull(RemovalStops.revisionCause(null));
		assertEquals(RemovalStopCause.OTHER, RemovalStops.revisionCause(new dev.larattalabs.architect.api.RevisionView(2, 1, 1, "p", "sha", "UNDOING", 0L, List.of(),
			List.of(), List.of(), 0L, null, true, "x", List.of())), "waiting with no cause given: Architect's OTHER");
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
		a.regions.get("rg_v").removalStopCause = RemovalStopCause.SAVE_REFUSED;
		assertEquals(Boolean.FALSE, undo(s));
		assertTrue(said.contains("Undo stopped: the world couldn't be saved just now, nothing was taken down; ask again (/steward undo set_drs1)."), said.toString());
		assertTrue(said.stream().noneMatch(l -> l.contains("chunk") || l.contains("not durable")), said.toString());
		var notes = Settlements.store().get(s.id()).orElseThrow().log().stream().filter(e -> e.kind() == Settlement.Kind.NOTE).map(Settlement.LogEntry::text).toList();
		assertTrue(notes.stream().anyMatch(n -> n.startsWith(Undo.STOPPED) && n.endsWith(RemovalStops.SAVE_REFUSED)), notes.toString());
		assertTrue(notes.stream().noneMatch(n -> n.contains("chunk")), notes.toString());
		assertTrue(Undo.interrupted(Settlements.store().get(s.id()).orElseThrow()).isEmpty(), "the undo's note is closed: asked again by the player");
	}

	/** (undo-words) A blocker text that must never reach the player or the history. */
	private static final String SENTINEL = "zz-architect-raw-blocker-zz";

	private List<String> notes(Settlement s) {
		return Settlements.store().get(s.id()).orElseThrow().log().stream().filter(e -> e.kind() == Settlement.Kind.NOTE).map(Settlement.LogEntry::text).toList();
	}

	@Test
	void eachTypedCauseOnTheRegionPathIsSaidAndNotedInTheStewardsWords() {
		int n = 0;
		for (var c : RemovalStopCause.values()) {
			for (var stop : new RemovalStop[] {RemovalStop.NOT_STARTED, RemovalStop.PARTIAL}) {
				said.clear();
				Settlements.useForTest(new SettlementStore(), dir.resolve("uw" + n + ".json"));
				Settlement s = village("set_uw" + n++);
				a.removeAnswer = FakeArchitect.Answer.BLOCKED;
				a.blocker = SENTINEL;
				a.regions.get("rg_v").removalStop = stop;
				a.regions.get("rg_v").removalStopCause = c;
				assertEquals(Boolean.FALSE, undo(s));
				assertEquals(List.of(RemovalStops.undoStopped(stop, c, s.id())), said, c + "/" + stop);
				String words = RemovalStops.causeWords(c);
				assertTrue(said.get(0).contains(words != null ? words : RemovalStops.IN_THE_WAY), said.toString());
				assertTrue(said.stream().noneMatch(l -> l.contains(SENTINEL) || l.contains("blocked by")), said.toString());
				assertTrue(notes(s).stream().anyMatch(t -> t.startsWith(Undo.STOPPED) && t.endsWith(RemovalStops.noted(stop, c))), notes(s).toString());
				assertTrue(notes(s).stream().noneMatch(t -> t.contains(SENTINEL)), notes(s).toString());
			}
		}
	}

	@Test
	void theRefusedSavesPhraseAloneNoLongerPicksTheWords() {
		// (8bS) the old " was not started: " phrase with a cause that isn't SAVE_REFUSED: said as Architect stopped it, never guessed from the text
		Settlement s = village("set_drs5");
		a.removeAnswer = FakeArchitect.Answer.BLOCKED;
		a.blocker = "the removal of group g was not started: a player stayed in s3";
		a.regions.get("rg_v").removalStop = RemovalStop.NOT_STARTED;
		a.regions.get("rg_v").removalStopCause = RemovalStopCause.PLAYER_IN_BOX;
		undo(s);
		assertTrue(said.stream().noneMatch(l -> l.contains(RemovalStops.SAVE_REFUSED)), said.toString());
		assertEquals(List.of(RemovalStops.undoStopped(RemovalStop.NOT_STARTED, RemovalStopCause.PLAYER_IN_BOX, "set_drs5")), said);
	}

	@Test
	void withoutTheFeaturesTheGenericLineNeverTheBlocker() {
		Settlement s = village("set_drs3");
		a.removeAnswer = FakeArchitect.Answer.BLOCKED;
		a.blocker = SENTINEL;
		a.features = Set.of("regions");
		a.regions.get("rg_v").removalStop = RemovalStop.NOT_STARTED;
		a.regions.get("rg_v").removalStopCause = RemovalStopCause.SAVE_REFUSED;
		undo(s);
		assertEquals(List.of("Undo stopped: something is in its way. Run /steward undo set_drs3 again once it is clear."), said, "no removalStop feature: never read");
		assertTrue(notes(s).stream().noneMatch(t -> t.contains(SENTINEL)), notes(s).toString());
	}

	@Test
	void aRefusedRemovalsErrorIsLoggedNeverSaid() {
		Settlement s = village("set_uwe");
		a.removeAnswer = FakeArchitect.Answer.REFUSE;
		undo(s);
		assertEquals(List.of("Undo stopped: " + Undo.NOT_NOW + ". Run /steward undo set_uwe again."), said);
		assertTrue(said.stream().noneMatch(l -> l.contains("refused: busy")), said.toString());
		assertTrue(notes(s).stream().noneMatch(t -> t.contains("refused: busy")), notes(s).toString());
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
		assertTrue(said.stream().noneMatch(l -> l.contains("chunk")), said.toString());
	}

	@Test
	void aGrowthsWaitWithItsTypedCauseSaysWhatItWaitsFor() {
		Settlement s = Settlement.founded("set_rvc1", "Vale", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L);
		var r = a.region("rg_g", s.owner());
		r.rev(1, 1, "PLACED", "g1");
		a.site("g1", s.owner(), "rg_g");
		a.undoAnswer = FakeArchitect.Answer.BLOCKED;
		a.blocker = RAW;
		a.undoWaits = "stage rv1:solids: " + RAW;
		a.undoWaitCause = RemovalStopCause.SAVE_REFUSED;
		a.features = new java.util.HashSet<>(a.features);
		a.features.add(RemovalStops.REVISION_CAUSE_FEATURE);
		AtomicReference<Boolean> done = new AtomicReference<>();
		Growths.undo(null, s.owner(), new Growths.Ref("rg_g", 1), said::add, done::set);
		a.runServer();
		assertEquals(Boolean.FALSE, done.get(), "not taken up yet: the entry stays");
		assertEquals(List.of(RemovalStops.growthWaiting(RemovalStopCause.SAVE_REFUSED)), said);
		assertTrue(said.stream().noneMatch(l -> l.contains("chunk")), said.toString());
	}

	// ------------------------------------------------------------------ (raw-text) the removal paths undo-words left

	@Test
	void everyCauseHasItsOwnTailAndEveryStopLineEndsWithIt() {
		assertEquals("Ask again with /steward undo set_1.", RemovalStops.again(RemovalStopCause.SAVE_REFUSED, "/steward undo set_1"));
		assertEquals("Run /steward undo set_1 again once they step out.", RemovalStops.again(RemovalStopCause.PLAYER_IN_BOX, "/steward undo set_1"));
		assertEquals("Run /steward cancel set_1 again once it is clear.", RemovalStops.again(null, "/steward cancel set_1"));
		assertEquals(RemovalStops.tail(null), RemovalStops.tail(RemovalStopCause.OTHER));
		Set<String> tails = new java.util.HashSet<>();
		for (var c : RemovalStopCause.values()) {
			tails.add(RemovalStops.tail(c));
			if (c != RemovalStopCause.SAVE_REFUSED) assertTrue(RemovalStops.undoStopped(RemovalStop.NOT_STARTED, c, "set_1")
				.endsWith(" again " + RemovalStops.tail(c) + "."), c.toString());
			assertTrue(SettlementRunner.cancelStopped(false, c, "set_1").endsWith(RemovalStops.again(c, "/steward cancel set_1")), c.toString());
			assertFalse(RemovalStops.undoStopped(RemovalStop.PARTIAL, c, "set_1").contains("Clear it and"), c.toString());
		}
		assertEquals(RemovalStopCause.values().length, tails.size(), "one tail per cause (OTHER's is the generic one)");
	}

	@Test
	void aGroupOrSiteBySiteUndoNeverSaysArchitectsErrorOrBlockers() {
		var err = new java.util.concurrent.CompletionException(new IllegalStateException(SENTINEL));
		String line = Undo.stoppedByError("set_1", 2, 5, err);
		assertEquals("Undo stopped after 2 of 5 sites: " + Undo.SITES_NOT_NOW + ". Run /steward undo set_1 again.", line);
		String blocked = Undo.blockedText("st_9", new dev.larattalabs.architect.api.RemoveResult(false, List.of(SENTINEL), Map.of()));
		assertFalse(blocked.contains(SENTINEL), blocked);
		line = Undo.stoppedAfter("set_1", 1, 3, blocked);
		assertEquals("Undo stopped after 1 of 3 sites: something is in its way. Run /steward undo set_1 again once it is clear.", line);
		assertFalse(line.contains(SENTINEL) || line.contains("st_9") || line.contains("blocked by"), line);
	}

	@Test
	void aCancelsStoppedTakeUpIsSaidByItsTypedCause() {
		assertEquals("The village could not be taken up yet (someone is standing in what it takes up). Run /steward cancel set_1 again once they step out.",
			SettlementRunner.cancelStopped(false, RemovalStopCause.PLAYER_IN_BOX, "set_1"));
		assertEquals("The village could not be taken up yet (something is in its way). Run /steward cancel set_1 again once it is clear.",
			SettlementRunner.cancelStopped(false, null, "set_1"));
		assertEquals("The village could not be taken up just now. Run /steward cancel set_1 again.", SettlementRunner.cancelStopped(true, null, "set_1"));
	}

	private Settlement growthTown(String id) {
		Settlement s = Settlement.founded(id, "Vale", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L);
		var r = a.region("rg_g", s.owner());
		r.rev(1, 1, "PLACED", "g1");
		a.site("g1", s.owner(), "rg_g");
		return s;
	}

	@Test
	void aGrowthsRefusalThatIsNotWaitingNeverSaysArchitectsText() {
		for (var how : new FakeArchitect.Answer[] {FakeArchitect.Answer.BLOCKED, FakeArchitect.Answer.REFUSE}) {
			said.clear();
			Settlement s = growthTown("set_rt_" + how);
			a.undoAnswer = how;
			a.blocker = SENTINEL;
			AtomicReference<Boolean> done = new AtomicReference<>();
			Growths.undo(null, s.owner(), new Growths.Ref("rg_g", 1), said::add, done::set);
			a.runServer();
			assertEquals(Boolean.FALSE, done.get(), how.toString());
			assertEquals(List.of(Growths.refusedLine(null, how == FakeArchitect.Answer.REFUSE)), said, how.toString());
			assertTrue(said.get(0).startsWith("The town's change could not be taken up"), said.toString());
			assertTrue(said.stream().noneMatch(l -> l.contains(SENTINEL) || l.contains("blocked by") || l.contains("refused: busy")), said.toString());
		}
		assertEquals("The town's change could not be taken up: the hall holds things.", Growths.refusedLine("the hall holds things", false),
			"a hall's own words, never followed by Architect's in brackets");
	}

	@Test
	void aRoadsTakeUpNeverSaysArchitectsText() {
		Settlement s = Settlement.founded("set_rd", "Vale", TOWN, Permission.PROPOSALS, Difficulty.SUPPLIED, 1L);
		Settlements.store().put(s);
		for (var how : new FakeArchitect.Answer[] {FakeArchitect.Answer.BLOCKED, FakeArchitect.Answer.REFUSE}) {
			said.clear();
			a.site("road_1", s.owner(), null);
			a.removeAnswer = how;
			a.blocker = SENTINEL;
			boolean[] ran = {false};
			Districts.takeUpForTest(s, "road_1", said::add, () -> ran[0] = true);
			assertTrue(ran[0], how.toString());
			assertEquals(List.of("test, and it could not be taken up" + (how == FakeArchitect.Answer.REFUSE ? " just now" : ": something is in its way")
				+ ". It is tried again later."), said, how.toString());
			assertTrue(said.stream().noneMatch(l -> l.contains(SENTINEL) || l.contains("refused: busy")), said.toString());
		}
	}
}
