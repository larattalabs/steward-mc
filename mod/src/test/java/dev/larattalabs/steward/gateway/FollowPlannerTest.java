package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.Designs.FollowResult;
import dev.larattalabs.architect.api.Library.Derivation.Kind;
import dev.larattalabs.architect.api.Library.Follower;
import dev.larattalabs.architect.api.PlayerEdits;
import dev.larattalabs.steward.gateway.FollowPlanner.Match;
import dev.larattalabs.steward.gateway.UpdatePlanner.Action;
import dev.larattalabs.steward.gateway.UpdatePlanner.Plan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FollowPlannerTest {
	private static final Map<String, List<Follower>> FAMILY = Map.of(
		"hut", List.of(new Follower("hut_c1", Kind.COPY, 1, 1, 3, true), new Follower("hut_c2", Kind.COPY, 2, 2, 3, true),
			new Follower("hut_v", Kind.VARIANT, 1, 1, 3, false), new Follower("hut_r", Kind.RESKIN, 1, 1, 3, false), new Follower("hut_s", Kind.SITE, 1, 1, 3, false)),
		"tavern", List.of(new Follower("tavern_c1", Kind.COPY, 1, 1, 2, true)));

	@Test
	void copiesFollowTheVersionTheSourceStandsAtNotItsHead() {
		// the hut's head is v3, but its lot stands at v2: only the copy made from v1 follows, to v2
		var out = FollowPlanner.toFollow(Map.of("hut", 2), e -> FAMILY.getOrDefault(e, List.of()));
		assertEquals(List.of(new FollowPlanner.Source("hut", 2, List.of("hut_c1"), Map.of("hut_c1", 1))), out);
		// at v3 both copies follow; variants, reskins and site copies never do here
		out = FollowPlanner.toFollow(Map.of("hut", 3), e -> FAMILY.getOrDefault(e, List.of()));
		assertEquals(List.of("hut_c1", "hut_c2"), out.get(0).copies());
		assertEquals(3, out.get(0).target());
	}

	@Test
	void aSourceStandingAtItsFirstVersionOrAheadOfNoCopyIsLeft() {
		// the tavern's head moved to v2 but its lot was not updated (skipped, or reverted): nothing follows
		assertTrue(FollowPlanner.toFollow(Map.of("tavern", 1), e -> FAMILY.getOrDefault(e, List.of())).isEmpty());
		assertTrue(FollowPlanner.toFollow(Map.of("hut", 1), e -> FAMILY.getOrDefault(e, List.of())).isEmpty());
		// a building with no copies
		assertTrue(FollowPlanner.toFollow(Map.of("mill", 4), e -> FAMILY.getOrDefault(e, List.of())).isEmpty());
		// sorted by source, one key each
		var both = FollowPlanner.toFollow(Map.of("tavern", 2, "hut", 2), e -> FAMILY.getOrDefault(e, List.of()));
		assertEquals(List.of("hut", "tavern"), both.stream().map(FollowPlanner.Source::entryId).toList());
	}

	@Test
	void theKeyNamesSourceVersionAndTheCopiesWithTheirHeads() {
		String k = FollowPlanner.opKey("gen_hut", 2, Map.of("c1", 1, "c2", 1));
		assertTrue(k.matches("steward_mc:follow:gen_hut:v2:[0-9a-f]{10}"), k);
		// the same members in any order: the same key (a replay)
		assertEquals(k, FollowPlanner.opKey("gen_hut", 2, new java.util.TreeMap<>(Map.of("c2", 1, "c1", 1)).descendingMap()));
		// another target, a head that moved (another follow got there first) or a copy made later: another operation
		assertNotEquals(k, FollowPlanner.opKey("gen_hut", 3, Map.of("c1", 1, "c2", 1)));
		assertNotEquals(k, FollowPlanner.opKey("gen_hut", 2, Map.of("c1", 2, "c2", 1)));
		assertNotEquals(k, FollowPlanner.opKey("gen_hut", 2, Map.of("c1", 1, "c2", 1, "c3", 1)));
		// a refused copy keeps its head, so the next scan's key is the same and replays the refusal
		var once = FollowPlanner.toFollow(Map.of("hut", 3), e -> FAMILY.getOrDefault(e, List.of())).get(0);
		var again = FollowPlanner.toFollow(Map.of("hut", 3), e -> FAMILY.getOrDefault(e, List.of())).get(0);
		assertEquals(FollowPlanner.opKey(once.entryId(), once.target(), once.heads()), FollowPlanner.opKey(again.entryId(), again.target(), again.heads()));
	}

	@Test
	void aCopyRefusedInAMixedAnswerIsNotReMadeWhenItsSiblingFollowed() {
		// c1 and c2 behind v2; c1 followed, c2 refused: the next scan sees c2 alone (another member set, another key), but it is remembered
		var first = new FollowPlanner.Source("hut", 2, List.of("c1", "c2"), Map.of("c1", 1, "c2", 1));
		var refused = java.util.Set.of(FollowPlanner.refusedKey("c2", 1, 2));
		var next = new FollowPlanner.Source("hut", 2, List.of("c2"), Map.of("c2", 1));
		assertNull(FollowPlanner.without(next, refused));
		// the first round itself keeps c1 only, under its own key
		var kept = FollowPlanner.without(first, refused);
		assertEquals(List.of("c1"), kept.copies());
		assertEquals(Map.of("c1", 1), kept.heads());
		// a later source version tries it again; so does a copy whose head moved
		assertNotNull(FollowPlanner.without(new FollowPlanner.Source("hut", 3, List.of("c2"), Map.of("c2", 1)), refused));
		assertNotNull(FollowPlanner.without(new FollowPlanner.Source("hut", 2, List.of("c2"), Map.of("c2", 2)), refused));
	}

	private static FollowResult refused(String why) {
		return new FollowResult("hut_c1", false, 1, 1, 2, why, false, "msg");
	}

	@Test
	void aCopyThatOutgrewItsLotIsToldAndStays() {
		// FS-b: Architect refuses before anything is installed; the head is unchanged
		FollowResult r = new FollowResult("hut_c1", false, 1, 1, 2, "lot_too_small", false, "the follower is 10x8x9, too big for lot h1 (site st3): hut_c1 stays at v1");
		assertTrue(FollowPlanner.refused(r));
		assertEquals("The fisher's hut (h2) stays as it is: the fisher's hut's new design (v2) would no longer fit its lot.",
			FollowPlanner.refusalText("fisher's hut (h2)", "fisher's hut", 2, r));
	}

	@Test
	void eachRefusalHasItsWords() {
		assertTrue(FollowPlanner.refusalText("hut", "cottage", 2, refused("frame")).endsWith("would move its entrance."));
		assertTrue(FollowPlanner.refusalText("hut", "cottage", 2, refused("bar")).endsWith("would make it the same building as the cottage."));
		assertTrue(FollowPlanner.refusalText("hut", "cottage", 2, refused("fit")).contains("larger"));
		assertTrue(FollowPlanner.refusalText("hut", "cottage", 2, refused("check")).contains("check"));
		assertTrue(FollowPlanner.refusalText("hut", "cottage", 2, refused("source_gone")).endsWith("is gone."));
		assertTrue(FollowPlanner.refusalText("hut", "cottage", 2, refused("something_new")).contains("(something_new)"));
	}

	@Test
	void onlyRefusalsAreTold() {
		assertFalse(FollowPlanner.refused(new FollowResult("c", true, 1, 2, 2, null, false, "")), "followed");
		assertFalse(FollowPlanner.refused(new FollowResult("c", false, 2, 2, 2, null, true, "")), "already followed");
		assertFalse(FollowPlanner.refused(refused("head_moved")), "another follow moved it");
		assertTrue(FollowPlanner.refused(refused("bar")));
	}

	@Test
	void theFollowedNoteNamesTheCopies() {
		assertEquals("The fisher's hut's copies (h2, h3) follow its new design (v2): their updates wait in the inbox.",
			FollowPlanner.followedText("fisher's hut", List.of("h2", "h3"), 2));
		assertEquals("The tavern's copy (t2) follows its new design (v3): its update waits in the inbox.", FollowPlanner.followedText("tavern", List.of("t2"), 3));
	}

	@Test
	void aFollowUpdateIsOfferedWhileItsSourceStandsAtThatVersion() {
		assertTrue(FollowPlanner.offered(2, 2));
		assertTrue(FollowPlanner.offered(3, 2));
		assertFalse(FollowPlanner.offered(1, 2), "the source was reverted below it");
		assertTrue(FollowPlanner.offered(null, 2), "a source standing nowhere here holds nothing back");
	}

	@Test
	void aFollowUpdateAlwaysAsksAndSaysWhatItFollows() {
		// at Full, in creative, an ordinary update applies by itself; a follow waits for the player
		Plan apply = new Plan(Action.APPLY, "fisher's hut has an update: 1 part changed (hall), 12 blocks", PlayerEdits.KEEP);
		Plan p = FollowPlanner.asFollow(apply, "fisher's hut");
		assertEquals(Action.ASK, p.action());
		assertEquals("fisher's hut: follows the fisher's hut's new design; 1 part changed (hall), 12 blocks", p.text());
		// the inbox line is what follows the first ": "
		assertEquals("follows the fisher's hut's new design; 1 part changed (hall), 12 blocks", p.text().replaceFirst("^[^:]*: ", ""));
		// a blocked one stays blocked, nothing to write stays nothing
		Plan blocked = new Plan(Action.BLOCKED, "hut cannot be updated: covered", PlayerEdits.KEEP);
		assertEquals(Action.BLOCKED, FollowPlanner.asFollow(blocked, "hut").action());
		Plan nothing = new Plan(Action.NOTHING, "hut: nothing to write.", PlayerEdits.KEEP);
		assertEquals(nothing, FollowPlanner.asFollow(nothing, "hut"));
		// a source's name with a "$" in it is words, not a group reference
		assertTrue(FollowPlanner.asFollow(apply, "$1 shop").text().contains("$1 shop"));
	}

	@Test
	void aFollowUpdateAppliesByItselfAtAutonomousAndFull() {
		// Noah, 2026-11-11: copies auto-apply at Autonomous and Full; below them they wait in the inbox
		Plan apply = new Plan(Action.APPLY, "fisher's hut has an update: 1 part changed (hall), 12 blocks", PlayerEdits.KEEP);
		assertEquals(Action.APPLY, FollowPlanner.asFollow(apply, "fisher's hut", dev.larattalabs.steward.model.Permission.FULL).action());
		assertEquals(Action.APPLY, FollowPlanner.asFollow(apply, "fisher's hut", dev.larattalabs.steward.model.Permission.AUTONOMOUS).action());
		assertEquals(Action.ASK, FollowPlanner.asFollow(apply, "fisher's hut", dev.larattalabs.steward.model.Permission.PROPOSALS).action());
		assertEquals(Action.ASK, FollowPlanner.asFollow(apply, "fisher's hut", dev.larattalabs.steward.model.Permission.OBSERVER).action());
		// an update that asks (the player's edits, a protected area) still asks at Full; a blocked one stays blocked
		Plan ask = new Plan(Action.ASK, "fisher's hut has an update: it touches your edits", PlayerEdits.KEEP);
		assertEquals(Action.ASK, FollowPlanner.asFollow(ask, "fisher's hut", dev.larattalabs.steward.model.Permission.FULL).action());
		Plan blocked = new Plan(Action.BLOCKED, "hut cannot be updated: covered", PlayerEdits.KEEP);
		assertEquals(Action.BLOCKED, FollowPlanner.asFollow(blocked, "hut", dev.larattalabs.steward.model.Permission.FULL).action());
		// the inbox line and the history keep saying what it follows
		assertTrue(FollowPlanner.asFollow(apply, "fisher's hut", dev.larattalabs.steward.model.Permission.FULL).text().contains("follows the fisher's hut's new design"));
	}

	@Test
	void aNameThatMatchesAFamilyMeansItsSource() {
		var fam = List.of(new Match("s2", "hut_c1", "hut"), new Match("s1", "hut", null), new Match("s3", "hut_c2", "hut"));
		assertEquals(Optional.of("s1"), FollowPlanner.familySource(fam));
		// two families, or no source standing among them, or the source on two lots: no pick
		assertTrue(FollowPlanner.familySource(List.of(new Match("s1", "hut", null), new Match("s4", "barn", null))).isEmpty());
		assertTrue(FollowPlanner.familySource(List.of(new Match("s2", "hut_c1", "hut"), new Match("s3", "hut_c2", "hut"))).isEmpty());
		assertTrue(FollowPlanner.familySource(List.of(new Match("s1", "hut", null), new Match("s5", "hut", null))).isEmpty());
		assertTrue(FollowPlanner.familySource(List.of(new Match("s1", "hut", null))).isEmpty());
	}

	@Test
	void aChangeToACopyIsAskedOfItsSource() {
		assertEquals("The fisher's hut is a copy of the fisher's hut's design: ask for the change on the fisher's hut, and its copies follow.",
			FollowPlanner.copyChangeText("fisher's hut", "fisher's hut"));
	}
}
