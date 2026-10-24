package dev.larattalabs.steward.inbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class InboxToastsTest {
	private static InboxModel.Entry build(String decision) {
		return new InboxModel.Entry("set_4", "Stilt", decision, "headline " + decision, List.of(), List.of(), 30, 2, 0);
	}

	@Test
	void aBuildToastsOnceWhenItFirstNeedsYouAndOnceWhenItEnds() {
		InboxToasts t = new InboxToasts();
		assertTrue(t.update(List.of(build("NONE"))).isEmpty(), "working: nothing to say");
		var need = t.update(List.of(build("BIBLE")));
		assertEquals(1, need.size());
		assertTrue(need.get(0).need());
		assertEquals("Stilt needs you", need.get(0).title());
		assertEquals("Stilt", need.get(0).subject(), "the name the client shortens, keeping \"needs you\"");
		assertTrue(t.update(List.of(build("BIBLE"))).isEmpty(), "the inbox resent: not again");
		assertTrue(t.update(List.of(build("NONE"))).isEmpty());
		assertTrue(t.update(List.of(build("MASSINGS"))).isEmpty(), "at most one needs-you per build");
		var done = t.update(List.of(build("DONE")));
		assertEquals(1, done.size());
		assertFalse(done.get(0).need());
		assertEquals("Stilt is built", done.get(0).title());
		assertTrue(t.update(List.of(build("DONE"))).isEmpty(), "the ended row resent: not again");
		assertTrue(t.update(List.of()).isEmpty(), "the row went");
		// the settlement's next build is news again
		assertEquals(1, t.update(List.of(build("PLACEMENT"))).size());
	}

	@Test
	void aNewBuildReplacingAnEndedRowToastsAgainAndAFailureWarns() {
		InboxToasts t = new InboxToasts();
		assertTrue(t.update(List.of(build("FAILED"))).get(0).warn());
		// a new build under the same key, before the ended row lapsed
		assertEquals("Stilt needs you", t.update(List.of(build("BUDGET"))).get(0).title());
		assertEquals(1, t.update(List.of(build("CANCELLED"))).size());
	}

	@Test
	void updatesAndProposalsDoNotToast() {
		InboxToasts t = new InboxToasts();
		var idea = new InboxModel.Entry("set_4", "Stilt", "PROPOSAL", "an idea", List.of(), List.of(), 0, 0, 0);
		var update = new InboxModel.Entry("set_4", "Stilt", "UPDATE", "updates", List.of(), List.of(), 0, 0, 0);
		assertTrue(t.update(List.of(idea, update)).isEmpty());
		t.update(List.of(build("BIBLE")));
		t.clear();
		assertEquals(1, t.update(List.of(build("BIBLE"))).size(), "a new world: said again");
	}

	@Test
	void aBuildHeldByTheUsageLimitIsHeldUntilItEnds() {
		var held = new InboxModel.Entry("set_4", "Stilt", "NONE", "Designing.", List.of("Buildings: 1 done", InboxModel.HELD_LINE + " (resets at 4pm)"), List.of(), 30, 2, 0);
		assertTrue(held.held());
		assertFalse(build("NONE").held());
		var ended = new InboxModel.Entry("set_4", "Stilt", "DONE", "Built.", List.of(InboxModel.HELD_LINE + " (resets at 4pm)"), List.of(), 30, 2, 0);
		assertFalse(ended.held(), "an ended build is not held");
	}
}
