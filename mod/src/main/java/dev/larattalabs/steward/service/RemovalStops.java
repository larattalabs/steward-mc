package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.RemovalStop;
import org.jspecify.annotations.Nullable;

/**
 * (slice DRS) How an undo's removal stopped, as Architect types it (DR-S, feature {@code removalStop}: {@code RegionView.removalStop()}), and the
 * steward's words for it. Whether anything was taken down is read from the typed stop only. Architect gives no typed cause, so one narrow phrase picks
 * the words for a refused save (its fixed "was not started:" blocker, which is never shown: it names chunk files); asked of Architect as a typed cause
 * (docs/ARCHITECT-ASKS.md, DRS).
 */
public final class RemovalStops {
	/** Architect's feature for the typed stop. */
	public static final String FEATURE = "removalStop";

	/** The steward's words for a refused save (nothing was taken down). */
	static final String SAVE_REFUSED = "the world couldn't be saved just now, nothing was taken down; ask again";

	private RemovalStops() {
	}

	/** A region's last removal stop, when this Architect types it; null when it doesn't, or the region can't be read. */
	static @Nullable RemovalStop of(String region) {
		try {
			var api = ArchitectAccess.api();
			if (!api.features().contains(FEATURE)) return null;
			return api.regions().get(region).map(dev.larattalabs.architect.api.RegionView::removalStop).orElse(null);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/**
	 * Whether a removal's blocker is Architect's refused save (a survival removal saves the world and the ledger before it writes; DR-S). Only ever read
	 * with a typed stop that says nothing was written, or with every site still standing. Pure.
	 */
	static boolean saveRefused(@Nullable String why) {
		return why != null && why.contains(" was not started: ");
	}

	/**
	 * The undo's stop line for a region removal Architect stopped before it wrote anything on a refused save, or null to keep the ordinary one (no
	 * typed stop; PARTIAL: part was taken down; any other blocker, said as before). Pure.
	 */
	static @Nullable String undoStopped(@Nullable RemovalStop stop, String why, String settlementId) {
		if (stop != RemovalStop.NOT_STARTED || !saveRefused(why)) return null;
		return "Undo stopped: " + SAVE_REFUSED + " (/steward undo " + settlementId + ").";
	}

	/** What a stopped undo's closing note records: never the refused save's raw text. Pure. */
	static String noted(String why) {
		return saveRefused(why) ? SAVE_REFUSED : why;
	}

	/**
	 * A growth's take-up Architect left UNDOING with a wait (a stage's removal stopped: resumed by a same-target call or the next world start): said as
	 * waiting, never refused, with what it waits for; a refused save's raw text (it names chunk files) is said as the world not saved. Pure.
	 */
	static String growthWaiting(@Nullable String why) {
		String said = why == null || why.isBlank() ? null : saveRefused(why) ? "the world couldn't be saved just now" : why;
		return "The town's change is part way taken up and waits to carry on" + (said != null ? " (" + said + ")" : "") + ": nothing more is taken down until"
			+ " it can. It carries on when you ask again or when the world next starts.";
	}

	/** Whether a growth's revision, read after its take-up answered not removed, is waiting part way (UNDOING with a wait), not refused. Pure. */
	static boolean growthWaits(dev.larattalabs.architect.api.@Nullable RevisionView rv) {
		return rv != null && "UNDOING".equalsIgnoreCase(rv.state()) && rv.waiting() != null && !rv.waiting().isBlank();
	}
}
