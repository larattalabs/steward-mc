package dev.larattalabs.steward.inbox;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.labui.ui.JobBoardModel;
import dev.larattalabs.labui.ui.JobCost;
import dev.larattalabs.labui.ui.JobRow;
import dev.larattalabs.labui.ui.JobState;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InboxJobsTest {
	private static InboxModel.Entry build(String id, String name, String decision) {
		return new InboxModel.Entry(id, name, decision, "headline " + decision, List.of(), List.of(), 30, 2.5, 0);
	}

	private static JobRow only(List<JobRow> rows) {
		assertEquals(1, rows.size());
		return rows.get(0);
	}

	@Test
	void aBuildRowIsTheSettlementsOwnerWithItsSpendAndBudget() {
		JobRow r = only(new InboxJobs().rows(List.of(build("set_4", "Stilt", "MASSINGS")), 1000, null));
		assertEquals("steward_mc:settlement/set_4", r.id(), "Architect's jobs owned by the settlement fold under it");
		assertEquals("build", r.kind(), "short enough for the panel's kind chip");
		assertEquals("Stilt", r.title());
		assertEquals(JobState.NEEDS_YOU, r.state());
		assertTrue(r.waiting());
		assertEquals(2.5, r.spentUsd());
		assertEquals(30, r.budgetUsd());
		assertEquals("build", r.budgetKind());
		assertEquals("headline MASSINGS", r.step());
	}

	@Test
	void statesFollowTheEntry() {
		InboxJobs j = new InboxJobs();
		assertEquals(JobState.RUNNING, only(j.rows(List.of(build("a", "A", "NONE")), 0, null)).state());
		assertEquals(JobState.PAUSED_BUDGET, only(j.rows(List.of(build("a", "A", "BUDGET")), 0, null)).state());
		assertTrue(only(j.rows(List.of(build("a", "A", "BUDGET")), 0, null)).waiting(), "a paused budget waits for a raise");
		assertEquals(JobState.FAILED, only(j.rows(List.of(build("a", "A", "FAILED")), 0, null)).state());
		assertEquals(JobState.CANCELLED, only(j.rows(List.of(build("a", "A", "CANCELLED")), 0, null)).state());
		var held = new InboxModel.Entry("a", "A", "NONE", "Designing.", List.of(), List.of(), 30, 2, 0, false, 1_800_000_000_000L);
		JobRow h = only(j.rows(List.of(held), 0, null));
		assertEquals(JobState.HELD, h.state());
		assertEquals(1_800_000_000_000L, h.resetAt());
		assertEquals(InboxModel.HELD_LINE, h.heldReason());
	}

	@Test
	void anEndedBuildEndsWhenFirstSeenAndKeepsThatTime() {
		InboxJobs j = new InboxJobs();
		JobRow first = only(j.rows(List.of(build("a", "A", "DONE")), 5_000, null));
		assertEquals(JobState.DONE, first.state());
		assertEquals(5_000, first.endedAt());
		assertEquals(5_000, only(j.rows(List.of(build("a", "A", "DONE")), 9_000, null)).endedAt(), "resent: the same end");
		j.rows(List.of(), 10_000, null);
		assertEquals(11_000, only(j.rows(List.of(build("a", "A", "DONE")), 11_000, null)).endedAt(), "gone and back: a new end");
	}

	@Test
	void updatesAndIdeasAreRowsOfTheirOwnAndIdeasNeverNeedYou() {
		var idea = new InboxModel.Entry("set_4", "Stilt", "PROPOSAL", "an idea", List.of(), List.of(), 0, 0, 0);
		var update = new InboxModel.Entry("set_4", "Stilt", "UPDATE", "updates", List.of(), List.of(), 0, 0, 0);
		var land = InboxModel.land("set_4", "Stilt", "the river moved", List.of());
		List<JobRow> rows = new InboxJobs().rows(List.of(build("set_4", "Stilt", "NONE"), idea, update, land), 0, null);
		assertEquals(List.of("steward_mc:settlement/set_4", "steward_mc:settlement/set_4#proposals", "steward_mc:settlement/set_4#updates"),
			rows.stream().map(JobRow::id).toList(), "land notes are not work");
		JobRow ideaRow = rows.get(1);
		assertEquals(JobState.QUEUED, ideaRow.state());
		assertFalse(ideaRow.waiting(), "an idea is not blocked on the player (the nameplate's rule)");
		assertEquals("Stilt · ideas", ideaRow.title());
		JobRow updRow = rows.get(2);
		assertTrue(updRow.waiting(), "updates wait for the player, as the old HUD line said");
		assertNull(updRow.parentId());
		assertEquals(0, updRow.spentUsd());
	}

	@Test
	void theSharedPillCountsOnlyWhatWaitsAndAStewardLedgerIsNeverPublished() {
		JobBoardModel m = new JobBoardModel();
		List<JobRow> rows = new InboxJobs().rows(List.of(build("a", "Stilt", "BIBLE"), build("b", "Reed", "NONE"),
			new InboxModel.Entry("c", "Ford", "PROPOSAL", "idea", List.of(), List.of(), 0, 0, 0)), 0, null);
		m.publish(InboxJobs.MOD, rows);
		assertEquals(1, JobBoardModel.needsYouCount(m.rows()));
		assertEquals("1 job needs you · Stilt", JobBoardModel.pillText(m.rows()));
		// Architect's ledger already lists the settlement's sidecar jobs, credited to Steward by their owner: today's split shows them once
		long now = System.currentTimeMillis();
		m.publishLedger("architect_mc", List.of(JobCost.of("g12", "steward_mc:settlement/a", 1.25, now), JobCost.of("g13", "", 0.5, now)));
		var t = JobBoardModel.totals(m.ledger(), now, ZoneId.systemDefault());
		assertEquals(1.75, t.total(), 1e-9);
		assertEquals(1.25, t.byMod().get("steward_mc"), 1e-9);
	}

	@Test
	void theLinkOpensTheSettlementsInbox() {
		List<String> opened = new ArrayList<>();
		JobRow r = only(new InboxJobs().rows(List.of(build("set_4", "Stilt", "BIBLE")), 0, id -> () -> opened.add(id)));
		assertNotNull(r.link());
		r.link().run();
		assertEquals(List.of("set_4"), opened);
		assertTrue(r.linkLabel().contains("Inbox"));
	}
}
