package dev.larattalabs.steward.pipeline;

import dev.larattalabs.steward.model.BudgetPolicy;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Permission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The generation pipeline of one settlement as a pure state machine: concept card, style bible, a massing-first design group, the player's approvals,
 * the detail designs, then the staged placement (docs/PLAN.md "Generation pipeline"). {@link #step} takes the current state, an event (something happened:
 * a job finished, the player decided) and the settlement's permission level, and returns the next state plus commands (things to ask Architect or the
 * player to do). It makes no calls and holds no clock, so it is tested exhaustively; a thin adapter turns commands into Architect API calls and Architect
 * events into {@link Event}s.
 */
public final class Pipeline {
	private Pipeline() {
	}

	public enum Phase {
		AWAITING_CARD_APPROVAL, BIBLE_RUNNING, AWAITING_BIBLE_APPROVAL, GROUP_RUNNING, AWAITING_MASSING_APPROVAL, READY_TO_PLACE, AWAITING_PLACEMENT_APPROVAL, PLACING, DONE, FAILED,
		CANCELLED;

		public boolean terminal() {
			return this == DONE || this == FAILED || this == CANCELLED;
		}
	}

	/** What the pipeline knows. Immutable; {@code items} maps lot id to its latest stage as Architect reported it. */
	public record State(Phase phase, String settlementId, ConceptCard card, @Nullable String bibleId, @Nullable Integer bibleVersion, @Nullable String groupId,
		Map<String, String> items, double bibleCostUsd, double spentUsd, double budgetUsd, boolean pausedForBudget, @Nullable String heldNote, @Nullable String failure) {
		public State {
			items = Map.copyOf(items);
		}

		public static State start(String settlementId, ConceptCard card, double budgetUsd) {
			return new State(Phase.AWAITING_CARD_APPROVAL, settlementId, card, null, null, null, Map.of(), 0, 0, budgetUsd, false, null, null);
		}

		State with(Phase p) {
			return new State(p, settlementId, card, bibleId, bibleVersion, groupId, items, bibleCostUsd, spentUsd, budgetUsd, pausedForBudget, heldNote, failure);
		}

		State withBible(String id, int version) {
			return new State(phase, settlementId, card, id, version, groupId, items, bibleCostUsd, spentUsd, budgetUsd, pausedForBudget, heldNote, failure);
		}

		State withGroup(String id) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, id, items, bibleCostUsd, spentUsd, budgetUsd, pausedForBudget, heldNote, failure);
		}

		State withProgress(Map<String, String> newItems, double spent, boolean paused, @Nullable String held) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, groupId, newItems, bibleCostUsd, spent, budgetUsd, paused, held, failure);
		}

		State withBibleCost(double c) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, groupId, items, c, c, budgetUsd, pausedForBudget, heldNote, failure);
		}

		State withBudget(double b) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, groupId, items, bibleCostUsd, spentUsd, b, false, heldNote, failure);
		}

		State failed(String why) {
			return new State(Phase.FAILED, settlementId, card, bibleId, bibleVersion, groupId, items, bibleCostUsd, spentUsd, budgetUsd, pausedForBudget, heldNote, why);
		}
	}

	// ------------------------------------------------------------------ events

	public sealed interface Event permits CardApproved, BibleDone, BibleApproved, GroupUpdate, MassingDecision, BudgetRaised, BatchQueued, PlacementApproved, BatchDone, Cancel {}

	/** The player approved (or edited and approved) the concept card. */
	public record CardApproved(ConceptCard card) implements Event {}

	public record BibleDone(boolean ok, @Nullable String bibleId, int version, double costUsd, @Nullable String error) implements Event {}

	public record BibleApproved() implements Event {}

	/**
	 * Architect's group changed. {@code status} is Architect's group status wire name ({@code running}, {@code awaiting_approval}, {@code paused_budget},
	 * {@code held_usage}, {@code done}, {@code failed}, {@code cancelled}); {@code items} maps lot id to its stage ({@code massing}, {@code approval},
	 * {@code detail}, {@code done}, {@code failed}); {@code awaiting} lists the lot ids waiting for approval.
	 */
	public record GroupUpdate(String groupId, String status, Map<String, String> items, List<String> awaiting, double costUsd, @Nullable String heldNote) implements Event {}

	/** The player's decision on the massings. */
	public record MassingDecision(List<String> approve, Map<String, String> redirect, List<String> cancel) implements Event {}

	public record BudgetRaised(double newBudgetUsd) implements Event {}

	/** Architect accepted the placement batch (its stages wait for approval unless the permission level places them as they come). */
	public record BatchQueued(String batchId) implements Event {}

	/** The player approved placing the designed settlement (every planned stage, in order). */
	public record PlacementApproved() implements Event {}

	public record BatchDone(int placed, int skipped) implements Event {}

	public record Cancel() implements Event {}

	// ---------------------------------------------------------------- commands

	public sealed interface Command permits RequestBible, RequestGroup, ApproveGroup, ExtendAndResumeGroup, FitAndQueue, ApproveStages, CancelGroup, CancelBatch, Notify {}

	public record RequestBible(String prompt, String name, double budgetUsd) implements Command {}

	/** Ask Architect for the group (the adapter builds the {@code GroupRequest} with GroupPlanner from the card, layout and bible). */
	public record RequestGroup(String bibleId, int bibleVersion, double budgetUsd) implements Command {}

	public record ApproveGroup(String groupId, List<String> approve, Map<String, String> redirect, List<String> cancel) implements Command {}

	public record ExtendAndResumeGroup(String groupId, double newBudgetUsd) implements Command {}

	/** Fit every designed lot with fitToLot and queue the placement batch; {@code autoApprove} places stages as they come. */
	public record FitAndQueue(String groupId, boolean autoApprove) implements Command {}

	/** Approve every planned stage of the queued batch, in order. */
	public record ApproveStages() implements Command {}

	public record CancelGroup(String groupId) implements Command {}

	/** Stop the placement batch (what is placed stays; the change log can undo it). */
	public record CancelBatch() implements Command {}

	/** Something the player should see in the inbox. */
	public record Notify(String text, boolean needsDecision) implements Command {}

	public record Step(State next, List<Command> commands) {
		static Step of(State s, Command... cs) {
			return new Step(s, List.of(cs));
		}
	}

	// ----------------------------------------------------------------- the step

	public static Step step(State s, Event e, Permission perm) {
		if (s.phase().terminal()) return Step.of(s);
		if (e instanceof Cancel) {
			State c = s.with(Phase.CANCELLED);
			Notify n = new Notify("Cancelled " + label(s), false);
			if (s.phase() == Phase.AWAITING_PLACEMENT_APPROVAL || s.phase() == Phase.PLACING) return Step.of(c, new CancelBatch(), n);
			return s.groupId() != null ? Step.of(c, new CancelGroup(s.groupId()), n) : Step.of(c, n);
		}
		return switch (s.phase()) {
			case AWAITING_CARD_APPROVAL -> e instanceof CardApproved a ? cardApproved(s, a) : Step.of(s);
			case BIBLE_RUNNING -> e instanceof BibleDone b ? bibleDone(s, b, perm) : Step.of(s);
			case AWAITING_BIBLE_APPROVAL -> e instanceof BibleApproved ? requestGroup(s) : Step.of(s);
			case GROUP_RUNNING, AWAITING_MASSING_APPROVAL -> switch (e) {
				case GroupUpdate g -> groupUpdate(s, g, perm);
				case MassingDecision d when s.phase() == Phase.AWAITING_MASSING_APPROVAL -> Step.of(s.with(Phase.GROUP_RUNNING), new ApproveGroup(s.groupId(), d.approve(), d.redirect(), d.cancel()));
				case BudgetRaised b when s.groupId() != null -> Step.of(s.withBudget(b.newBudgetUsd()), new ExtendAndResumeGroup(s.groupId(), b.newBudgetUsd()));
				default -> Step.of(s);
			};
			case READY_TO_PLACE -> e instanceof BatchQueued ? (perm.needsApproval(Permission.Action.NEW_PROJECT)
				? Step.of(s.with(Phase.AWAITING_PLACEMENT_APPROVAL), new Notify("The designs of " + label(s) + " are done and fitted to their lots. Approve to place them.", true))
				: Step.of(s.with(Phase.PLACING))) : Step.of(s);
			case AWAITING_PLACEMENT_APPROVAL -> switch (e) {
				case PlacementApproved p -> Step.of(s.with(Phase.PLACING), new ApproveStages());
				case BatchDone b -> built(s, b);
				default -> Step.of(s);
			};
			case PLACING -> e instanceof BatchDone b ? built(s, b) : Step.of(s);
			default -> Step.of(s);
		};
	}

	private static Step built(State s, BatchDone b) {
		return Step.of(s.with(Phase.DONE), new Notify(label(s) + " is built: " + b.placed() + " buildings placed" + (b.skipped() > 0 ? ", " + b.skipped() + " skipped" : ""), false));
	}

	/** What the pipeline is waiting for the player to decide, if anything (the inbox, and for now the {@code /steward approve|redirect|raise} commands, act on it). */
	public enum Decision { NONE, BIBLE, MASSINGS, BUDGET, PLACEMENT }

	public static Decision awaiting(State s) {
		return switch (s.phase()) {
			case AWAITING_BIBLE_APPROVAL -> Decision.BIBLE;
			case AWAITING_MASSING_APPROVAL -> Decision.MASSINGS;
			case AWAITING_PLACEMENT_APPROVAL -> Decision.PLACEMENT;
			case GROUP_RUNNING -> s.pausedForBudget() ? Decision.BUDGET : Decision.NONE;
			default -> Decision.NONE;
		};
	}

	private static Step cardApproved(State s, CardApproved a) {
		State n = new State(Phase.BIBLE_RUNNING, s.settlementId(), a.card(), null, null, null, Map.of(), 0, 0, s.budgetUsd(), false, null, null);
		// the bible is a small share of the budget: its measured cost is $1.2-2.0
		return Step.of(n, new RequestBible(biblePrompt(a.card()), a.card().name() == null ? s.settlementId() : a.card().name(), BudgetPolicy.BIBLE_HIGH * 1.5));
	}

	private static Step bibleDone(State s, BibleDone b, Permission perm) {
		if (!b.ok() || b.bibleId() == null) return Step.of(s.failed("the style bible failed: " + (b.error() == null ? "unknown" : b.error())), new Notify("The style bible could not be made: " + b.error(), true));
		State n = s.withBible(b.bibleId(), b.version()).withBibleCost(b.costUsd());
		if (perm.needsApproval(Permission.Action.NEW_PROJECT)) {
			return Step.of(n.with(Phase.AWAITING_BIBLE_APPROVAL), new Notify("Style bible ready for " + label(s) + ": review its sheet and approve to start designing.", true));
		}
		return requestGroup(n);
	}

	private static Step requestGroup(State s) {
		double groupBudget = Math.max(0, s.budgetUsd() - s.spentUsd());
		return Step.of(s.with(Phase.GROUP_RUNNING), new RequestGroup(s.bibleId(), s.bibleVersion(), groupBudget));
	}

	private static Step groupUpdate(State s, GroupUpdate g, Permission perm) {
		// spend = the bible's cost plus the group's own aggregate (Architect reports the group's cost without the bible)
		State n = s.withGroup(g.groupId()).withProgress(g.items(), s.bibleCostUsd() + g.costUsd(), "paused_budget".equals(g.status()), g.heldNote());
		return switch (g.status()) {
			case "awaiting_approval" -> {
				if (!perm.needsApproval(Permission.Action.NEW_PROJECT)) {
					yield Step.of(n.with(Phase.GROUP_RUNNING), new ApproveGroup(g.groupId(), g.awaiting(), Map.of(), List.of()));
				}
				// Architect re-sends awaiting_approval while an approval is in flight: tell the player once, on the way in
				if (s.phase() == Phase.AWAITING_MASSING_APPROVAL) yield Step.of(n.with(Phase.AWAITING_MASSING_APPROVAL));
				yield Step.of(n.with(Phase.AWAITING_MASSING_APPROVAL), new Notify(g.awaiting().size() + " massings are ready: approve or redirect each.", true));
			}
			case "paused_budget" -> Step.of(n.with(Phase.GROUP_RUNNING),
				new Notify(String.format("Paused at %d%% of the $%.0f budget (spent $%.2f). Raise the budget to continue.", (int) (BudgetPolicy.SOFT_FRACTION * 100), s.budgetUsd(), n.spentUsd()), true));
			case "held_usage" -> Step.of(n.with(Phase.GROUP_RUNNING), new Notify("Waiting for your Claude usage limit to reset" + (g.heldNote() == null ? "." : " (" + g.heldNote() + ")."), false));
			case "done" -> Step.of(n.with(Phase.READY_TO_PLACE), new FitAndQueue(g.groupId(), !perm.needsApproval(Permission.Action.NEW_PROJECT)));
			case "failed" -> Step.of(n.failed("the design group failed"), new Notify("The design group failed.", true));
			case "cancelled" -> Step.of(n.with(Phase.CANCELLED), new Notify("The design group was cancelled.", false));
			default -> Step.of(n.with(s.phase() == Phase.AWAITING_MASSING_APPROVAL ? Phase.GROUP_RUNNING : s.phase()));
		};
	}

	/** The prompt for the settlement's style bible: the card's style, purpose, site, story and avoid list in one paragraph. */
	public static String biblePrompt(ConceptCard c) {
		List<String> parts = new ArrayList<>();
		parts.add("Style: " + c.style().text());
		parts.add("Settlement: " + c.site().text() + ", used as " + c.purpose().text());
		if (c.story() != null && c.story().text() != null && !c.story().text().isBlank()) parts.add("Backstory: " + c.story().text());
		if (!c.avoid().isEmpty()) parts.add("Avoid: " + String.join(", ", c.avoid()));
		return String.join(". ", parts) + ".";
	}

	/** What to call the settlement in messages: the card's name, which is optional, else the settlement id (never "null"). */
	static String label(State s) {
		String n = s.card() == null ? null : s.card().name();
		return n == null || n.isBlank() ? s.settlementId() : n;
	}

	/** Items by stage, for the HUD ("5 massing, 2 approval, 1 detail, 4 done"). */
	public static Map<String, Integer> stageCounts(State s) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (String stage : s.items().values()) out.merge(stage, 1, Integer::sum);
		return out;
	}
}
