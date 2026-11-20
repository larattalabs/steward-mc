package dev.larattalabs.steward.inbox;

import dev.larattalabs.labui.ui.JobRow;
import dev.larattalabs.labui.ui.JobState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Steward's rows of lab-ui's shared Jobs &amp; spend panel (S-J4, {@code ../architect-mc/docs/JOBS-PANEL.md} J4): one per inbox entry, so K
 * and the shared needs-you pill show what the steward waits on. Pure; the client publishes {@link #rows} from every inbox it receives.
 *
 * <ul>
 * <li><b>Ids</b>: a build's row is the settlement's Architect owner ({@code steward_mc:settlement/<id>}), so the Architect jobs a build
 * owns fold under it; its updates and proposals are {@code …#updates} and {@code …#proposals}. Land notes are not work and get no row.</li>
 * <li><b>Needs you</b>: exactly the entry's {@link InboxModel.Entry#needsYou()} (the nameplate's "!" and the board say the same); a
 * steward's idea never counts as needing you: it is an offer ({@link JobRow#offer}, lab-ui 0.5.0), listed under Ideas and shown on the idle pill
 * ("N ideas for you") only while nothing waits.</li>
 * <li><b>Spend</b>: a build row carries its spend and budget for the panel's detail only. The panel's "today" total is summed from the
 * cost ledger, and every Claude job Steward runs is an Architect sidecar job owned by {@code steward_mc:…}, which Architect's ledger
 * already lists under its own job id (and credits to Steward by that owner). Steward publishes no ledger of its own: the client has no
 * sidecar job ids, and entries under any other id would count the same spend twice.</li>
 * </ul>
 *
 * Remembers when it first saw each ended build, so its row ends then (the panel moves ended rows away after a while).
 */
public final class InboxJobs {
	public static final String MOD = "steward_mc";
	private static final String OWNER = MOD + ":settlement/";

	private final Map<String, Long> endedAt = new HashMap<>();

	/**
	 * The rows for {@code entries}, as of {@code now} (epoch ms).
	 *
	 * @param open what opening a settlement's inbox does (its settlement id), or null for rows without a link
	 */
	public List<JobRow> rows(List<InboxModel.Entry> entries, long now, @Nullable Function<String, Runnable> open) {
		List<JobRow> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (InboxModel.Entry e : entries) {
			if (InboxModel.LAND.equals(e.decision())) continue;
			String id = rowId(e);
			if (!seen.add(id)) continue;
			JobState state = state(e);
			JobRow.Builder b = JobRow.builder(id, kind(e), title(e), state).step(step(e)).needsYou(e.needsYou())
				.offer("PROPOSAL".equals(e.decision()));
			// updates and ideas are rows of their own (under an ended build's row they would move away with it)
			if (!"UPDATE".equals(e.decision()) && !"PROPOSAL".equals(e.decision())) {
				b.spent(e.spentUsd());
				if (e.budgetUsd() > 0) b.budget(e.budgetUsd(), "build");
			}
			if (e.held()) b.held(InboxModel.HELD_LINE, e.limitResetsAt());
			if (state.ended()) b.ended(endedAt.computeIfAbsent(id, k -> now));
			if (open != null) b.link("Steward · Inbox (" + e.name() + ")", open.apply(e.settlementId()));
			out.add(b.build());
		}
		endedAt.keySet().retainAll(seen);
		return out;
	}

	/** Forgets the ended builds (the player left the world). */
	public void clear() {
		endedAt.clear();
	}

	/** The panel row an entry is published as (a toast about it carries it as its ref). */
	public static String rowId(InboxModel.Entry e) {
		return switch (e.decision()) {
			case "UPDATE" -> OWNER + e.settlementId() + "#updates";
			case "PROPOSAL" -> OWNER + e.settlementId() + "#proposals";
			default -> OWNER + e.settlementId();
		};
	}

	static String kind(InboxModel.Entry e) {
		return switch (e.decision()) {
			case "UPDATE" -> "update";
			case "PROPOSAL" -> "idea";
			default -> "build";
		};
	}

	static String title(InboxModel.Entry e) {
		return switch (e.decision()) {
			case "UPDATE" -> e.name() + " · updates";
			// (LUI-2) an idea's row is under the panel's Ideas group, and its title is the idle pill's ("1 idea for you · Ford"): the name alone
			default -> e.name();
		};
	}

	/** At most this many idea names in an idea row's step; the rest are counted. */
	static final int IDEA_NAMES = 2;

	/**
	 * A row's step (the panel's second column). (LUI-2) An idea's is what is offered, by name ("smokehouse", "smokehouse, well +1 more"), not
	 * the inbox's sentence, which the panel cut; every other row's is the entry's headline.
	 */
	static String step(InboxModel.Entry e) {
		if (!"PROPOSAL".equals(e.decision())) return e.headline();
		List<String> names = e.lots().stream().map(InboxModel.Lot::role).filter(r -> r != null && !r.isBlank()).toList();
		if (names.isEmpty()) return "an idea";
		String shown = String.join(", ", names.subList(0, Math.min(IDEA_NAMES, names.size())));
		return names.size() > IDEA_NAMES ? shown + " +" + (names.size() - IDEA_NAMES) + " more" : shown;
	}

	static JobState state(InboxModel.Entry e) {
		if (e.ended()) {
			return switch (e.decision()) {
				case "FAILED" -> JobState.FAILED;
				case "CANCELLED" -> JobState.CANCELLED;
				default -> JobState.DONE;
			};
		}
		if (e.held()) return JobState.HELD;
		if ("BUDGET".equals(e.decision())) return JobState.PAUSED_BUDGET;
		if (e.needsYou()) return JobState.NEEDS_YOU;
		// an idea waits for the player's pick without blocking anything
		if ("PROPOSAL".equals(e.decision())) return JobState.QUEUED;
		return JobState.RUNNING;
	}
}
