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
	 * @param lines further lines, shown under the headline: the buildings by stage, a usage limit the build waits for
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

		/** A build that has ended ({@code DONE}, {@code FAILED} or {@code CANCELLED}): its row stays a few minutes so the player sees how it ended. */
		public boolean ended() {
			return ENDED.contains(decision);
		}

		/** Unique in the inbox: a settlement can have a build in progress and updates waiting at once. */
		public String key() {
			return "UPDATE".equals(decision) ? settlementId + "#updates" : "PROPOSAL".equals(decision) ? settlementId + "#proposals" : settlementId;
		}

		public boolean waiting() {
			return !Pipeline.Decision.NONE.name().equals(decision) && !ended();
		}
	}

	/** The key of a settlement's waiting parts as one proposal row (each part is its own proposal underneath). */
	public static final String PARTS_KEY = "waiting_parts";

	/** The decisions of an ended build's row: its phase's name. */
	public static final java.util.Set<String> ENDED = java.util.Set.of(Pipeline.Phase.DONE.name(), Pipeline.Phase.FAILED.name(), Pipeline.Phase.CANCELLED.name());

	/**
	 * A build's entry. An ended build's carries its phase as its decision ({@link Entry#ended}): nothing to decide, how it ended in words.
	 *
	 * @param waitingLots every building of the build (the ones whose massing waits marked {@code waiting})
	 */
	public static Entry entry(String settlementId, String name, Pipeline.State s, List<Lot> waitingLots) {
		Pipeline.Decision d = Pipeline.awaiting(s);
		String decision = s.phase().terminal() ? s.phase().name() : d.name();
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
		// the spend is not a line: the inbox shows it beside the build's state, from spentUsd and budgetUsd
		if (s.heldNote() != null && !s.phase().terminal()) lines.add("Waiting for your usage limit to reset (" + s.heldNote() + ")");
		return new Entry(settlementId, name, decision, headline, lines, waitingLots, s.budgetUsd(), s.spentUsd(), Pipeline.minimumRaise(s));
	}

	/** The steward's proposals for a settlement: one row per idea (its key, its title, why and about what it costs), each to build or to decline. */
	public static Entry proposals(String settlementId, String name, List<Lot> ideas) {
		boolean parts = ideas.stream().anyMatch(l -> PARTS_KEY.equals(l.id()));
		long others = ideas.size() - (parts ? 1 : 0);
		String headline = others == 0 ? "Parts of " + name + "'s plan can be built now: build them one at a time, or not now (they are offered again next time)."
			: (others == 1 ? "The steward has an idea for " + name + ": build it, or not now (it will not ask again)."
				: "The steward has " + others + " ideas for " + name + ": build one, or decline it (it will not ask again).")
				+ (parts ? " Waiting parts put off come back next time." : "");
		return new Entry(settlementId, name, "PROPOSAL", headline, List.of(), ideas, 0, 0, 0);
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
			case DONE -> "Built: every building stands.";
			case FAILED -> "Failed: it stopped and spends nothing more. The steward said why in chat.";
			case CANCELLING -> "Cancelling: waiting for the work under way to stop.";
			case CANCELLED -> "Cancelled: nothing more is spent.";
		};
	}
}
