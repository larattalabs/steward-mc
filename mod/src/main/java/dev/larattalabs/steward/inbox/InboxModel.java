package dev.larattalabs.steward.inbox;

import dev.larattalabs.steward.model.BudgetPolicy;
import dev.larattalabs.steward.pipeline.Pipeline;
import java.util.ArrayList;
import java.util.List;

/**
 * What the inbox shows for one build: the decision it waits for (if any) in words, with what the player needs to decide it (the waiting lots, the budget
 * figures). Pure, built from the pipeline state on the server and sent to the client as is; the client renders it and sends decisions back.
 */
public final class InboxModel {
	private InboxModel() {
	}

	/**
	 * One building of the build: its lot id (what a redirect names), its role, its stage as Architect reports it ({@code massing}, {@code approval},
	 * {@code detail}, {@code done}, {@code failed}, or "" before the design group), whether it is a landmark, whether its massing waits for the player, and a
	 * short description (the massing's size and named parts, when known).
	 */
	public record Lot(String id, String role, String stage, boolean landmark, boolean waiting, String detail) {
		public Lot(String id, String role, String detail) {
			this(id, role, "approval", false, true, detail);
		}
	}

	/**
	 * @param decision a {@link Pipeline.Decision} name ({@code NONE} when the build is just working)
	 * @param lines further lines: progress and spend
	 */
	public record Entry(String settlementId, String name, String decision, String headline, List<String> lines, List<Lot> lots, double budgetUsd, double spentUsd,
		double minRaiseUsd) {
		public Entry {
			lines = List.copyOf(lines);
			lots = List.copyOf(lots);
		}

		/** Buildings done (designed, or placed once placing). */
		public long done() {
			return lots.stream().filter(l -> "done".equals(l.stage())).count();
		}

		/** Unique in the inbox: a settlement can have a build in progress and updates waiting at once. */
		public String key() {
			return "UPDATE".equals(decision) ? settlementId + "#updates" : settlementId;
		}

		public boolean waiting() {
			return !Pipeline.Decision.NONE.name().equals(decision);
		}
	}

	/** @param waitingLots every building of the build (the ones whose massing waits marked {@code waiting}) */
	public static Entry entry(String settlementId, String name, Pipeline.State s, List<Lot> waitingLots) {
		Pipeline.Decision d = Pipeline.awaiting(s);
		String headline = switch (d) {
			case BIBLE -> "The style bible is ready. Approve it to design the buildings.";
			case MASSINGS -> waitingLots.stream().filter(Lot::waiting).count() + " massings wait for you: approve them, or send one back with notes.";
			case BUDGET -> String.format("Paused at %d%% of the $%.0f budget. Raise it to at least $%.0f to go on.", (int) (BudgetPolicy.SOFT_FRACTION * 100), s.budgetUsd(),
				Pipeline.minimumRaise(s));
			case PLACEMENT -> "The designs are done and fitted to their lots. Approve to place them.";
			case NONE -> working(s.phase());
		};
		List<String> lines = new ArrayList<>();
		var counts = Pipeline.stageCounts(s);
		if (!counts.isEmpty()) lines.add("Buildings: " + counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).reduce((a, b) -> a + ", " + b).orElse(""));
		lines.add(String.format("Spent $%.2f of $%.0f", s.spentUsd(), s.budgetUsd()));
		if (s.heldNote() != null) lines.add("Waiting for your Claude usage limit to reset (" + s.heldNote() + ")");
		return new Entry(settlementId, name, d.name(), headline, lines, waitingLots, s.budgetUsd(), s.spentUsd(), Pipeline.minimumRaise(s));
	}

	/** A settlement's waiting updates: one lot per building (its id is the site id, {@code waiting} when it can be applied). */
	public static Entry updates(String settlementId, String name, List<Lot> buildings) {
		long ready = buildings.stream().filter(Lot::waiting).count();
		String headline = ready == 0 ? "Updates are available but cannot be applied right now (see each building)."
			: ready + (ready == 1 ? " building has" : " buildings have") + " a newer version: update, preview the change, or skip. Your own edits are kept.";
		return new Entry(settlementId, name, "UPDATE", headline, List.of(), buildings, 0, 0, 0);
	}

	static String working(Pipeline.Phase p) {
		return switch (p) {
			case AWAITING_CARD_APPROVAL -> "Getting started.";
			case BIBLE_RUNNING, AWAITING_BIBLE_APPROVAL -> "Designing the style bible.";
			case GROUP_RUNNING, AWAITING_MASSING_APPROVAL -> "Designing the buildings.";
			case READY_TO_PLACE -> "Fitting the buildings to their lots.";
			case PLACING, AWAITING_PLACEMENT_APPROVAL -> "Placing the settlement.";
			case DONE -> "Built.";
			case FAILED -> "Failed.";
			case CANCELLED -> "Cancelled.";
		};
	}
}
