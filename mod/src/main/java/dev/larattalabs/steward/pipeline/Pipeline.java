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
		AWAITING_CARD_APPROVAL, BIBLE_RUNNING, AWAITING_BIBLE_APPROVAL, GROUP_RUNNING, AWAITING_MASSING_APPROVAL, READY_TO_PLACE, AWAITING_PLACEMENT_APPROVAL, PLACING,
		/** (Architect K3) a wall lot's design is being repaired after the bind refused it; nothing is placed until it ends ({@link State#repair()}). */
		REPAIRING,
		/** Cancelled by the player, waiting for Architect to stop what it runs (a bible job, the group, the batch's rollback): the build keeps its slot. */
		CANCELLING, DONE, FAILED, CANCELLED;

		public boolean terminal() {
			return this == DONE || this == FAILED || this == CANCELLED;
		}
	}

	/** What the pipeline knows. Immutable; {@code items} maps lot id to its latest stage as Architect reported it. */
	public record State(Phase phase, String settlementId, ConceptCard card, @Nullable String bibleId, @Nullable Integer bibleVersion, @Nullable String groupId,
		Map<String, String> items, double bibleCostUsd, double spentUsd, double budgetUsd, boolean pausedForBudget, @Nullable String heldNote, @Nullable String failure,
		@Nullable Repair repair, Map<String, Integer> repairs) {
		public State {
			items = Map.copyOf(items);
			// a state saved before wall repairs: none
			repairs = repairs == null ? Map.of() : Map.copyOf(repairs);
		}

		/** A state without a wall repair (as saved before Architect K3). */
		public State(Phase phase, String settlementId, ConceptCard card, @Nullable String bibleId, @Nullable Integer bibleVersion, @Nullable String groupId,
			Map<String, String> items, double bibleCostUsd, double spentUsd, double budgetUsd, boolean pausedForBudget, @Nullable String heldNote, @Nullable String failure) {
			this(phase, settlementId, card, bibleId, bibleVersion, groupId, items, bibleCostUsd, spentUsd, budgetUsd, pausedForBudget, heldNote, failure, null, Map.of());
		}

		public static State start(String settlementId, ConceptCard card, double budgetUsd) {
			return new State(Phase.AWAITING_CARD_APPROVAL, settlementId, card, null, null, null, Map.of(), 0, 0, budgetUsd, false, null, null);
		}

		State with(Phase p) {
			return new State(p, settlementId, card, bibleId, bibleVersion, groupId, items, bibleCostUsd, spentUsd, budgetUsd, pausedForBudget, heldNote, failure, repair, repairs);
		}

		State withBible(String id, int version) {
			return new State(phase, settlementId, card, id, version, groupId, items, bibleCostUsd, spentUsd, budgetUsd, pausedForBudget, heldNote, failure, repair, repairs);
		}

		State withGroup(String id) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, id, items, bibleCostUsd, spentUsd, budgetUsd, pausedForBudget, heldNote, failure, repair, repairs);
		}

		/** (slice M3) The same state holding (or, with null, no longer holding) this note: the bill the player is asked to approve, shown with the decision. */
		public State withHeldNote(@Nullable String note) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, groupId, items, bibleCostUsd, spentUsd, budgetUsd, pausedForBudget, note, failure, repair, repairs);
		}

		State withProgress(Map<String, String> newItems, double spent, boolean paused, @Nullable String held) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, groupId, newItems, bibleCostUsd, spent, budgetUsd, paused, held, failure, repair, repairs);
		}

		State withBibleCost(double c) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, groupId, items, c, c, budgetUsd, pausedForBudget, heldNote, failure, repair, repairs);
		}

		State withBudget(double b) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, groupId, items, bibleCostUsd, spentUsd, b, false, heldNote, failure, repair, repairs);
		}

		State paused(double budget) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, groupId, items, bibleCostUsd, spentUsd, budget, true, heldNote, failure, repair, repairs);
		}

		State withSpent(double usd) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, groupId, items, bibleCostUsd, usd, budgetUsd, pausedForBudget, heldNote, failure, repair, repairs);
		}

		State withRepair(@Nullable Repair r, Map<String, Integer> rounds) {
			return new State(phase, settlementId, card, bibleId, bibleVersion, groupId, items, bibleCostUsd, spentUsd, budgetUsd, pausedForBudget, heldNote, failure, r, rounds);
		}

		State failed(String why) {
			return new State(Phase.FAILED, settlementId, card, bibleId, bibleVersion, groupId, items, bibleCostUsd, spentUsd, budgetUsd, pausedForBudget, heldNote, why, repair, repairs);
		}
	}

	/**
	 * A wall lot's repair the build owns (Architect K3): its lot, its operation key (Architect finds the design by it after a restart or a lost answer, and a
	 * repeat of it is the same design), its round, its design once Architect answered, what of the budget it reserved, and whether a cancel waits for it.
	 */
	public record Repair(String lotId, String role, String key, String why, int round, @Nullable String designId, double reservedUsd, boolean stopping) {
		Repair withDesign(String id) {
			return new Repair(lotId, role, key, why, round, id, reservedUsd, stopping);
		}

		Repair stopped() {
			return new Repair(lotId, role, key, why, round, designId, reservedUsd, true);
		}
	}

	/** A wall lot's design repairs at the bind (Architect K3): at most this many rounds a lot, each reserving its most, {@link #WALL_REPAIR_USD}. */
	public static final int WALL_REPAIRS = 2;
	public static final double WALL_REPAIR_USD = 2.0;

	// ------------------------------------------------------------------ events

	public sealed interface Event permits CardApproved, BibleDone, BibleApproved, GroupUpdate, MassingDecision, BudgetRaised, BudgetRaiseFailed, BatchQueued, PlacementApproved,
		BatchDone, Cancel, CancelConfirmed, WallRefused, RepairAsked, RepairEnded, ModulePlace, HoldBill {}

	/**
	 * The player approved (or edited and approved) the concept card. {@code bibleId}: the settlement's style bible to reuse (a later build of a settlement
	 * that has one), so no new bible is made or approved; null makes one.
	 */
	public record CardApproved(ConceptCard card, @Nullable String bibleId, int bibleVersion) implements Event {
		public CardApproved(ConceptCard card) {
			this(card, null, 0);
		}
	}

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

	/**
	 * (Architect K3) the bind refused a wall lot's design ("lot <id> on wall <wall>: ..."). {@code designed}: this build has a design of it to repair;
	 * {@code key}: the repair's operation key.
	 */
	public record WallRefused(String lotId, String role, String why, boolean designed, String key) implements Event {}

	/** Architect took the repair's request (or found it by its key): its design. */
	public record RepairAsked(String key, String designId) implements Event {}

	/** How a repair ended: its design done or failed (stopped included), Architect refused the request (nothing designed), or it was lost (its most stays counted). */
	public enum RepairEnd { DONE, FAILED, REFUSED, LOST }

	/** The repair ended, its design having cost {@code costUsd}; {@code why} for an end without a design. */
	public record RepairEnded(String key, RepairEnd end, double costUsd, @Nullable String why) implements Event {}


	/**
	 * Architect did not take a raise and the build stays paused. {@code extended}: the group's cap was raised but resuming failed, so the new budget stands (a
	 * raise to the same amount retries the resume); else the budget goes back.
	 */
	public record BudgetRaiseFailed(double previousBudgetUsd, String why, boolean extended) implements Event {
		public BudgetRaiseFailed(double previousBudgetUsd, String why) {
			this(previousBudgetUsd, why, false);
		}
	}

	/** Architect accepted the placement batch (its stages wait for approval unless the permission level places them as they come). */
	public record BatchQueued(String batchId) implements Event {}

	/** The player approved placing the designed settlement (every planned stage, in order). */
	public record PlacementApproved() implements Event {}

	public record BatchDone(int placed, int skipped, @Nullable String note) implements Event {
		public BatchDone(int placed, int skipped) {
			this(placed, skipped, null);
		}
	}

	/** The player cancelled; a second cancel while cancelling stops waiting for Architect. */
	/**
	 * (docs/MODULES.md 4.2) A growth's module lots are bound and their bill is within the approved bound: placed now. The player approved it at
	 * Accept (the offer's bound), so it is never asked again unless the bill is past it ({@link HoldBill}).
	 */
	public record ModulePlace() implements Event {}

	/** (docs/MODULES.md 4.2 step 5) The quote is past the approved bound (or, Autonomous, takes over half the stockpile's count of an item): held, nothing consumed, the player asked. */
	public record HoldBill(String note) implements Event {}

	public record Cancel() implements Event {}

	/** Nothing of this build runs at Architect any more (its cancellation finished, or there was nothing to stop). */
	public record CancelConfirmed() implements Event {}

	// ---------------------------------------------------------------- commands

	public sealed interface Command permits RequestBible, RequestGroup, ApproveGroup, ExtendAndResumeGroup, FitAndQueue, ApproveStages, CancelBible, CancelGroup, CancelBatch,
		Notify, AskRepair, StopRepair, Rebind {}

	public record RequestBible(String prompt, String name, double budgetUsd) implements Command {}

	/** Ask Architect for the group (the adapter builds the {@code GroupRequest} with GroupPlanner from the card, layout and bible). */
	public record RequestGroup(String bibleId, int bibleVersion, double budgetUsd) implements Command {}

	public record ApproveGroup(String groupId, List<String> approve, Map<String, String> redirect, List<String> cancel) implements Command {}

	/** {@code groupBudgetUsd} is the group's own cap: the project's budget less what the bible cost (Architect counts the group without it). */
	public record ExtendAndResumeGroup(String groupId, double groupBudgetUsd, double previousBudgetUsd) implements Command {}

	/** Fit every designed lot with fitToLot and queue the placement batch; {@code autoApprove} places stages as they come. */
	public record FitAndQueue(String groupId, boolean autoApprove) implements Command {}

	/** Approve every planned stage of the queued batch, in order. */
	public record ApproveStages() implements Command {}

	/** Stop the style bible job (the adapter knows its id; one that is acknowledged later is cancelled on arrival). */
	public record CancelBible() implements Command {}

	/** {@code groupId} is null when the group's ack has not come yet: the adapter cancels it on arrival. */
	public record CancelGroup(@Nullable String groupId) implements Command {}

	/** Stop the placement batch (what is placed stays; the change log can undo it). */
	public record CancelBatch() implements Command {}

	/** (Architect K3) ask for the repair of a wall lot's design under its key (asking again under the same key is the same design). */
	public record AskRepair(String lotId, String key, String why) implements Command {}

	/** Stop the repair's design (its id, or found by its key when null); the repair then ends with what it cost. */
	public record StopRepair(String key, @Nullable String designId) implements Command {}

	/** Bind the approved artifact again, the repaired design in place of the refused one. */
	public record Rebind() implements Command {}

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
		if (e instanceof Cancel) return cancel(s);
		if (e instanceof RepairAsked a) return repairAsked(s, a);
		if (e instanceof RepairEnded r) return repairEnded(s, r);
		return switch (s.phase()) {
			case AWAITING_CARD_APPROVAL -> e instanceof CardApproved a ? cardApproved(s, a) : Step.of(s);
			case BIBLE_RUNNING -> e instanceof BibleDone b ? bibleDone(s, b, perm) : Step.of(s);
			case AWAITING_BIBLE_APPROVAL -> e instanceof BibleApproved ? requestGroup(s) : Step.of(s);
			case GROUP_RUNNING, AWAITING_MASSING_APPROVAL -> switch (e) {
				case GroupUpdate g -> groupUpdate(s, g, perm);
				case MassingDecision d when s.phase() == Phase.AWAITING_MASSING_APPROVAL -> Step.of(s.with(Phase.GROUP_RUNNING), new ApproveGroup(s.groupId(), d.approve(), d.redirect(), d.cancel()));
				// paused before the group was requested (the bible used the budget up): the raise requests it
				case BudgetRaised b when s.groupId() == null && s.pausedForBudget() -> requestGroup(s.withBudget(b.newBudgetUsd()));
				case BudgetRaised b when s.groupId() != null -> Step.of(s.withBudget(b.newBudgetUsd()),
					new ExtendAndResumeGroup(s.groupId(), b.newBudgetUsd() - s.bibleCostUsd(), s.budgetUsd()));
				case BudgetRaiseFailed f -> f.extended()
					? Step.of(s.paused(s.budgetUsd()), new Notify(String.format("The budget is $%.0f but the design group did not resume: %s. Raise to $%.0f again to retry.", s.budgetUsd(),
						f.why(), s.budgetUsd()), true))
					: Step.of(s.paused(f.previousBudgetUsd()), new Notify("The budget could not be raised: " + f.why(), true));
				default -> Step.of(s);
			};
			case READY_TO_PLACE -> switch (e) {
				case ModulePlace m -> Step.of(s.with(Phase.PLACING).withHeldNote(null));
				case HoldBill h -> Step.of(s.with(Phase.AWAITING_PLACEMENT_APPROVAL).withHeldNote(h.note()), new Notify(h.note(), true));
				case BatchQueued q -> perm.needsApproval(Permission.Action.NEW_PROJECT)
					? Step.of(s.with(Phase.AWAITING_PLACEMENT_APPROVAL), new Notify("The designs of " + label(s) + " are done and fitted to their lots. Approve to place them.", true))
					: Step.of(s.with(Phase.PLACING));
				// placed before a restart was noticed (read back from the sites)
				case BatchDone b -> built(s, b);
				// a build placed without the player's word binds while still ready: its wall lot refused there is repaired as in PLACING
				case WallRefused w -> wallRefused(s, w);
				default -> Step.of(s);
			};
			case AWAITING_PLACEMENT_APPROVAL -> switch (e) {
				case PlacementApproved p -> Step.of(s.with(Phase.PLACING).withHeldNote(null), new ApproveStages());
				// asked again (a quote past the bound once more): still waiting, said
				case HoldBill h -> Step.of(s.withHeldNote(h.note()), new Notify(h.note(), true));
				case BatchDone b -> built(s, b);
				default -> Step.of(s);
			};
			case PLACING -> switch (e) {
				case BatchDone b -> built(s, b);
				case HoldBill h -> Step.of(s.with(Phase.AWAITING_PLACEMENT_APPROVAL).withHeldNote(h.note()), new Notify(h.note(), true));
				case WallRefused w -> wallRefused(s, w);
				default -> Step.of(s);
			};
			// nothing but the repair's end moves it (above)
			case REPAIRING -> Step.of(s);
			case CANCELLING -> cancelling(s, e);
			default -> Step.of(s);
		};
	}

	/**
	 * Cancel: what runs at Architect is stopped first, and the build holds its slot (CANCELLING) until Architect says it stopped, so a second build cannot
	 * spend alongside it and what a rolled-back batch placed is still logged for undo. Nothing running: cancelled at once.
	 */
	private static Step cancel(State s) {
		Notify n = new Notify("Cancelling " + label(s) + "...", false);
		return switch (s.phase()) {
			case BIBLE_RUNNING -> Step.of(s.with(Phase.CANCELLING), new CancelBible(), n);
			case GROUP_RUNNING, AWAITING_MASSING_APPROVAL -> s.groupId() == null && s.pausedForBudget()
				? Step.of(s.with(Phase.CANCELLED), new Notify("Cancelled " + label(s), false))
				: Step.of(s.with(Phase.CANCELLING), new CancelGroup(s.groupId()), n);
			case READY_TO_PLACE, AWAITING_PLACEMENT_APPROVAL, PLACING -> Step.of(s.with(Phase.CANCELLING), new CancelBatch(), n);
			// a wall lot's repair: stopped, and the build holds its slot until it has (its cost then known)
			case REPAIRING -> Step.of(s.withRepair(s.repair().stopped(), s.repairs()).with(Phase.CANCELLING), new StopRepair(s.repair().key(), s.repair().designId()), n);
			// a second cancel while a repair stops: asked to stop again, still owned (a repair that cannot be confirmed stopped is given up by the runner)
			case CANCELLING -> s.repair() != null
				? Step.of(s, new StopRepair(s.repair().key(), s.repair().designId()), new Notify("Still waiting for the repair of the " + s.repair().role()
					+ " to stop; " + label(s) + " is cancelled once it has.", false))
				// a second cancel: stop waiting for Architect (it said nothing back)
				: Step.of(s.with(Phase.CANCELLED), new Notify("Stopped waiting for Architect; " + label(s) + " is cancelled.", false));
			default -> Step.of(s.with(Phase.CANCELLED), new Notify("Cancelled " + label(s), false));
		};
	}

	private static Step cancelling(State s, Event e) {
		// a repair outstanding: only its end (above) cancels the build, never the group's, batch's or region's word
		if (s.repair() != null) return Step.of(s);
		return switch (e) {
			case BibleDone b -> Step.of(s.withBibleCost(b.costUsd()).with(Phase.CANCELLED), new Notify("Cancelled " + label(s) + ".", false));
			// (never less than the build already counted: a wall repair's cost is past the bible's and the group's)
			case GroupUpdate g when List.of("done", "failed", "cancelled").contains(g.status()) ->
				Step.of(s.withProgress(g.items(), Math.max(s.spentUsd(), s.bibleCostUsd() + g.costUsd()), false, null).with(Phase.CANCELLED), new Notify(String.format(
					"Cancelled %s (spent $%.2f).", label(s), Math.max(s.spentUsd(), s.bibleCostUsd() + g.costUsd())), false));
			case BatchDone b -> Step.of(s.with(Phase.CANCELLED), new Notify("Cancelled " + label(s) + (b.placed() > 0 ? ": " + b.placed() + " buildings placed before it stopped stay (undo removes them)." : "."), false));
			case CancelConfirmed c -> Step.of(s.with(Phase.CANCELLED), new Notify("Cancelled " + label(s) + ".", false));
			default -> Step.of(s);
		};
	}

	/** The bind refused a wall lot's design: repaired (within the rounds and the budget, its most reserved), or the build fails naming it. */
	private static Step wallRefused(State s, WallRefused w) {
		if (s.repair() != null) return Step.of(s);
		int round = s.repairs().getOrDefault(w.lotId(), 0);
		String no = !w.designed() ? "Architect refused the " + w.role() + " on the wall, and this build has no design of it to repair"
			: round >= WALL_REPAIRS ? "the " + w.role() + " on the wall still doesn't close it after " + round + " repairs"
			: s.spentUsd() + WALL_REPAIR_USD > s.budgetUsd() ? "repairing the " + w.role() + " on the wall would go past the budget"
			: null;
		if (no != null) return Step.of(s.failed(no + " (" + w.why() + ")"), new Notify("The build of " + label(s) + " stopped: " + no + ".", true));
		var rounds = new java.util.HashMap<>(s.repairs());
		rounds.put(w.lotId(), round + 1);
		var r = new Repair(w.lotId(), w.role(), w.key(), w.why(), round + 1, null, WALL_REPAIR_USD, false);
		return Step.of(s.withRepair(r, rounds).withSpent(s.spentUsd() + WALL_REPAIR_USD).with(Phase.REPAIRING), new AskRepair(w.lotId(), w.key(), w.why()),
			new Notify("Architect refused the " + w.role() + " on the wall (" + w.why() + "): redesigning it (repair " + (round + 1) + " of " + WALL_REPAIRS + ").", false));
	}

	private static Step repairAsked(State s, RepairAsked a) {
		var r = s.repair();
		if (r == null || !r.key().equals(a.key()) || r.designId() != null) return Step.of(s);
		var known = r.withDesign(a.designId());
		// a cancel came first: the design is stopped now that it is known
		return known.stopping() ? Step.of(s.withRepair(known, s.repairs()), new StopRepair(known.key(), known.designId())) : Step.of(s.withRepair(known, s.repairs()));
	}

	/** The repair ended: its reservation trued up to its cost; then placed again, the build failed naming the lot, or a waiting cancel done. */
	private static Step repairEnded(State s, RepairEnded e) {
		var r = s.repair();
		if (r == null || !r.key().equals(e.key())) return Step.of(s);
		double delta = switch (e.end()) {
			case LOST -> 0;
			case REFUSED -> -r.reservedUsd();
			default -> Math.max(0, e.costUsd()) - r.reservedUsd();
		};
		State t = s.withRepair(null, s.repairs()).withSpent(Math.max(0, s.spentUsd() + delta));
		if (s.phase() == Phase.CANCELLING) return Step.of(t.with(Phase.CANCELLED), new Notify("Cancelled " + label(s) + " (the wall repair stopped).", false));
		if (s.phase() != Phase.REPAIRING) return Step.of(t);
		if (e.end() == RepairEnd.DONE) return Step.of(t.with(Phase.PLACING), new Rebind(), new Notify("The " + r.role() + " is redesigned: placing again.", false));
		String why = "the " + r.role() + " on the wall could not be redesigned: " + (e.why() != null ? e.why() : e.end().name().toLowerCase());
		return Step.of(t.failed(why), new Notify("The build of " + label(s) + " stopped: " + why + ".", true));
	}

	private static Step built(State s, BatchDone b) {
		// said as it is: no building placed is not built; some skipped, or a part that failed besides the buildings ({@code note}), is built in part
		String more = (b.skipped() > 0 ? ", " + b.skipped() + " skipped" : "") + (b.note() != null ? " (" + b.note() + ")" : "");
		if (b.placed() == 0) return Step.of(s.with(Phase.DONE), new Notify("No building of " + label(s) + " was placed" + more + ".", false));
		return Step.of(s.with(Phase.DONE), new Notify(label(s) + (b.skipped() > 0 || b.note() != null ? " is built in part: " : " is built: ") + b.placed()
			+ " buildings placed" + more, false));
	}

	/** The smallest budget that lets a paused build go on: its spend sits under the soft pause again with $5 of room, rounded up to $5. */
	public static double minimumRaise(State s) {
		return Math.ceil((s.spentUsd() + 5) / BudgetPolicy.SOFT_FRACTION / 5.0) * 5.0;
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
		if (a.bibleId() != null) {
			// the settlement's style, approved when it was made: straight to the designs, at no bible cost
			return requestGroup(new State(Phase.GROUP_RUNNING, s.settlementId(), a.card(), a.bibleId(), a.bibleVersion(), null, Map.of(), 0, 0, s.budgetUsd(), false, null, null));
		}
		State n = new State(Phase.BIBLE_RUNNING, s.settlementId(), a.card(), null, null, null, Map.of(), 0, 0, s.budgetUsd(), false, null, null);
		// the bible is a small share of the budget (its measured cost is $1.2-2.0), never more than the whole of it
		return Step.of(n, bibleRequest(n));
	}

	/** The style bible request of a build in BIBLE_RUNNING: the same every time it is made (an operation key finds it again after a restart). */
	public static RequestBible bibleRequest(State s) {
		return new RequestBible(biblePrompt(s.card()), s.card().name() == null ? s.settlementId() : s.card().name(), Math.min(BudgetPolicy.BIBLE_HIGH * 1.5, s.budgetUsd()));
	}

	/** The design group request of a build in GROUP_RUNNING: what is left of the budget, in its bible. */
	public static RequestGroup groupRequest(State s) {
		return new RequestGroup(s.bibleId(), s.bibleVersion(), s.budgetUsd() - s.spentUsd());
	}

	private static Step bibleDone(State s, BibleDone b, Permission perm) {
		if (!b.ok() || b.bibleId() == null) return Step.of(s.withBibleCost(b.costUsd()).failed("the style bible failed: " + (b.error() == null ? "unknown" : b.error())),
			new Notify("The style bible could not be made: " + b.error(), true));
		State n = s.withBible(b.bibleId(), b.version()).withBibleCost(b.costUsd());
		if (perm.needsApproval(Permission.Action.NEW_PROJECT)) {
			return Step.of(n.with(Phase.AWAITING_BIBLE_APPROVAL), new Notify("Style bible ready for " + label(s) + ": review its sheet and approve to start designing.", true));
		}
		return requestGroup(n);
	}

	/** The smallest group budget worth requesting (a little more than one massing). */
	static final double MIN_GROUP_USD = 0.5;

	private static Step requestGroup(State s) {
		double groupBudget = s.budgetUsd() - s.spentUsd();
		// the bible used the budget up: pause before asking Architect for anything (a raise requests the group)
		if (groupBudget < MIN_GROUP_USD) {
			State p = s.with(Phase.GROUP_RUNNING).paused(s.budgetUsd());
			return Step.of(p, new Notify(String.format("The style bible used $%.2f of the $%.0f budget, which leaves too little to design. Raise the budget to at least $%.0f to continue.",
				s.spentUsd(), s.budgetUsd(), minimumRaise(p)), true));
		}
		return Step.of(s.with(Phase.GROUP_RUNNING), groupRequest(s));
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
			// Architect re-sends the paused status: ask once, on the way in
			case "paused_budget" -> s.pausedForBudget() ? Step.of(n.with(Phase.GROUP_RUNNING)) : Step.of(n.with(Phase.GROUP_RUNNING),
				new Notify(String.format("Paused at %d%% of the $%.0f budget (spent $%.2f). Raise the budget to at least $%.0f to continue.", (int) (BudgetPolicy.SOFT_FRACTION * 100), s.budgetUsd(),
					n.spentUsd(), minimumRaise(n)), true));
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
