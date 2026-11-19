package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.RemovalStop;
import dev.larattalabs.architect.api.RemovalStopCause;
import org.jspecify.annotations.Nullable;

/**
 * (slice DRS) How an undo's removal stopped, as Architect types it (DR-S, feature {@code removalStop}: {@code RegionView.removalStop()}), and the
 * steward's words for it. Whether anything was taken down is read from the typed stop only. Architect gives no typed cause, so one narrow phrase picks
 * the words for a refused save (its raw blocker is never shown: it names chunk files). (slice 8bS) A refused save is told by Architect's typed cause
 * (S-DRS-1, feature {@code removalStopCause}: {@code RegionView.removalStopCause}, {@code SiteGroup.removalStopCause}), never by its text.
 */
public final class RemovalStops {
	/** Architect's feature for the typed stop. */
	public static final String FEATURE = "removalStop";
	/** (8bS) Architect's feature for the stop's typed cause (S-DRS-1). */
	public static final String CAUSE_FEATURE = "removalStopCause";

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

	/** (8bS) A region's last removal stop's typed cause, when this Architect types it; null when it doesn't, or the region can't be read. */
	static @Nullable RemovalStopCause cause(String region) {
		try {
			var api = ArchitectAccess.api();
			if (!api.features().contains(CAUSE_FEATURE)) return null;
			return api.regions().get(region).map(dev.larattalabs.architect.api.RegionView::removalStopCause).orElse(null);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** (8bS) A site group's last whole-group removal stop's typed cause (kept in memory by Architect); null when not typed or not readable. */
	static @Nullable RemovalStopCause groupCause(dev.larattalabs.architect.api.Sites sites, String group) {
		try {
			if (!ArchitectAccess.api().features().contains(CAUSE_FEATURE)) return null;
			return sites.group(group).map(dev.larattalabs.architect.api.SiteGroup::removalStopCause).orElse(null);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** Whether a stop's typed cause is Architect's refused save (a survival removal saves the world and the ledger before it writes; DR-S). Pure. */
	static boolean saveRefused(@Nullable RemovalStopCause cause) {
		return cause == RemovalStopCause.SAVE_REFUSED;
	}

	/**
	 * The undo's stop line for a region removal Architect stopped before it wrote anything on a refused save, or null to keep the ordinary one (no
	 * typed stop; PARTIAL: part was taken down; any other cause, said as before). Pure.
	 */
	static @Nullable String undoStopped(@Nullable RemovalStop stop, @Nullable RemovalStopCause cause, String settlementId) {
		if (stop != RemovalStop.NOT_STARTED || !saveRefused(cause)) return null;
		return "Undo stopped: " + SAVE_REFUSED + " (/steward undo " + settlementId + ").";
	}

	/** What a stopped undo's closing note records: never the refused save's raw text. Pure. */
	static String noted(@Nullable RemovalStopCause cause, String why) {
		return saveRefused(cause) ? SAVE_REFUSED : why;
	}

	/**
	 * A growth's take-up Architect left UNDOING with a wait (a stage's removal stopped: resumed by a same-target call or the next world start): said as
	 * waiting, never refused. (8bS) Architect types no cause for a revision's stop, so its text is never shown (a refused save's names chunk files);
	 * asked of Architect (docs/ARCHITECT-ASKS.md, 8bS). Pure.
	 */
	static String growthWaiting() {
		return "The town's change is part way taken up and waits to carry on: nothing more is taken down until it can. It carries on when you ask again or"
			+ " when the world next starts.";
	}

	/** Whether a growth's revision, read after its take-up answered not removed, is waiting part way (UNDOING with a wait), not refused. Pure. */
	static boolean growthWaits(dev.larattalabs.architect.api.@Nullable RevisionView rv) {
		return rv != null && "UNDOING".equalsIgnoreCase(rv.state()) && rv.waiting() != null && !rv.waiting().isBlank();
	}
}
