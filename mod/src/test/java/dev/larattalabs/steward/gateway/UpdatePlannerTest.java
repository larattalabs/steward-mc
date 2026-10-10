package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.DeltaVerdict;
import dev.larattalabs.architect.api.Mode;
import dev.larattalabs.architect.api.PartDelta;
import dev.larattalabs.architect.api.PartStatus;
import dev.larattalabs.architect.api.PlayerEdits;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.architect.api.Refusal;
import dev.larattalabs.steward.gateway.UpdatePlanner.Action;
import dev.larattalabs.steward.model.Permission;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class UpdatePlannerTest {
	private static final BoundingBox BOX = new BoundingBox(BlockPos.ZERO);

	private static DeltaVerdict verdict(boolean ok, List<Refusal> r, int a, int rm, int c, Map<String, PartDelta> parts) {
		return new DeltaVerdict(ok, r, a, rm, c, parts, List.of(), List.of(), Map.of(), Map.of(), BOX, Mode.INSTANT, List.of());
	}

	private static Map<String, PartDelta> parts() {
		return Map.of("roof", new PartDelta("roof", PartStatus.CHANGED, 4, 1, 9, BOX, BOX), "wing", new PartDelta("wing", PartStatus.ADDED, 30, 0, 0, null, BOX),
			"hall", new PartDelta("hall", PartStatus.UNCHANGED, 0, 0, 0, BOX, BOX));
	}

	@Test
	void creativeUpgradesFollowThePermissionLevel() {
		DeltaVerdict v = verdict(true, List.of(), 34, 1, 9, parts());
		assertEquals(Action.ASK, UpdatePlanner.plan("Tavern", v, Permission.PROPOSALS).action());
		assertEquals(Action.APPLY, UpdatePlanner.plan("Tavern", v, Permission.AUTONOMOUS).action());
		assertEquals(Action.APPLY, UpdatePlanner.plan("Tavern", v, Permission.FULL).action());
	}

	@Test
	void theSummaryNamesChangedPartsAndSkipsUnchangedOnes() {
		String s = UpdatePlanner.summary(verdict(true, List.of(), 34, 1, 9, parts()));
		assertTrue(s.startsWith("2 parts changed (+wing, roof)"), s);
		assertTrue(s.contains("44 blocks"));
		assertFalse(s.contains("hall"));
	}

	@Test
	void permanentRefusalsBlockAndTemporaryOnesAsk() {
		DeltaVerdict frame = verdict(false, List.of(new Refusal(Reason.FRAME_CHANGED, "front changed")), 0, 0, 0, Map.of());
		assertEquals(Action.BLOCKED, UpdatePlanner.plan("Tavern", frame, Permission.FULL).action());
		DeltaVerdict busy = verdict(false, List.of(new Refusal(Reason.SITE_BUSY, "still building")), 0, 0, 0, Map.of());
		UpdatePlanner.Plan p = UpdatePlanner.plan("Tavern", busy, Permission.FULL);
		assertEquals(Action.ASK, p.action());
		assertTrue(p.text().contains("right now"));
	}

	@Test
	void anEmptyDeltaIsNothing() {
		assertEquals(Action.NOTHING, UpdatePlanner.plan("Hut", verdict(true, List.of(), 0, 0, 0, Map.of()), Permission.FULL).action());
	}

	@Test
	void playerEditsAreKeptExceptForObservers() {
		assertEquals(PlayerEdits.KEEP, UpdatePlanner.editsFor(Permission.PROPOSALS));
		assertEquals(PlayerEdits.KEEP, UpdatePlanner.editsFor(Permission.FULL));
		assertEquals(PlayerEdits.REFUSE, UpdatePlanner.editsFor(Permission.OBSERVER));
		assertEquals(PlayerEdits.KEEP, UpdatePlanner.request("s1", 2, PlayerEdits.KEEP, "steward_mc:settlement/set_1").playerEdits());
		assertEquals("steward_mc:settlement/set_1", UpdatePlanner.request("s1", 2, PlayerEdits.KEEP, "steward_mc:settlement/set_1").owner(), "the settlement asks as the owner (OVERLAP_OWNED otherwise)");
	}

	@Test
	void aVersionThatRemovesAPartIsADemolitionAutonomousAsksAbout() {
		var removes = Map.of("wing", new PartDelta("wing", PartStatus.REMOVED, 0, 30, 0, BOX, null));
		DeltaVerdict v = verdict(true, List.of(), 0, 30, 0, removes);
		assertEquals(Action.ASK, UpdatePlanner.plan("Tavern", v, Permission.AUTONOMOUS).action());
		assertEquals(Action.APPLY, UpdatePlanner.plan("Tavern", v, Permission.FULL).action());
	}

	@Test
	void anUpdateReachingOutsideTheClaimIsBlocked() {
		var claim = new dev.larattalabs.steward.model.Claim("minecraft:overworld", 0, 0, 48, -64, 320);
		DeltaVerdict inside = verdict(true, List.of(), 34, 1, 9, parts());
		assertEquals(Action.APPLY, UpdatePlanner.plan("Tavern", inside, Permission.FULL, claim).action());
		DeltaVerdict outside = new DeltaVerdict(true, List.of(), 34, 0, 0, parts(), List.of(), List.of(), Map.of(), Map.of(), new BoundingBox(40, 64, 0, 52, 70, 8),
			Mode.INSTANT, List.of());
		UpdatePlanner.Plan p = UpdatePlanner.plan("Tavern", outside, Permission.FULL, claim);
		assertEquals(Action.BLOCKED, p.action());
		assertTrue(p.text().contains("claim"), p.text());
	}
}
