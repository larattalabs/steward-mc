package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.RemovalStop;
import dev.larattalabs.architect.api.RemovalStopCause;
import org.jspecify.annotations.Nullable;

/**
 * (slice DRS) How an undo's removal stopped, as Architect types it (DR-S, feature {@code removalStop}: {@code RegionView.removalStop()}), and the
 * steward's words for it. Whether anything was taken down is read from the typed stop only. (slice 8bS) The cause is Architect's typed one (S-DRS-1,
 * feature {@code removalStopCause}: {@code RegionView.removalStopCause}, {@code SiteGroup.removalStopCause}), never its text. (undo-words) Every
 * typed cause is said in the steward's words ({@link #causeWords}); Architect's blocker text is never shown (it names chunk files, the player's things).
 */
public final class RemovalStops {
	/** Architect's feature for the typed stop. */
	public static final String FEATURE = "removalStop";
	/** (8bS) Architect's feature for the stop's typed cause (S-DRS-1). */
	public static final String CAUSE_FEATURE = "removalStopCause";

	/** (RVC) Architect's feature for a revision's typed stop cause ({@code RevisionView.removalStopCause}; ask 8bS-1). */
	public static final String REVISION_CAUSE_FEATURE = "revisionStopCause";

	/** (RVC) The steward's words for a refused save, wherever it stopped a removal (a region's, a group's or a growth's take-up). */
	static final String WORLD_NOT_SAVED = "the world couldn't be saved just now";
	/** The steward's words for a refused save (nothing was taken down). */
	static final String SAVE_REFUSED = WORLD_NOT_SAVED + ", nothing was taken down; ask again";

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

	/** (undo-words) A site group's last whole-group removal stop, typed (S-DRS-1, {@code SiteGroup.removalStop}); null when not typed or not readable. */
	static @Nullable RemovalStop groupStop(dev.larattalabs.architect.api.Sites sites, String group) {
		try {
			if (!ArchitectAccess.api().features().contains(CAUSE_FEATURE)) return null;
			return sites.group(group).map(dev.larattalabs.architect.api.SiteGroup::removalStop).orElse(null);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** Whether a stop's typed cause is Architect's refused save (a survival removal saves the world and the ledger before it writes; DR-S). Pure. */
	static boolean saveRefused(@Nullable RemovalStopCause cause) {
		return cause == RemovalStopCause.SAVE_REFUSED;
	}

	/** (undo-words) What a stop with no typed cause (OTHER, none, or an Architect without the feature) is said as: never Architect's text. */
	static final String IN_THE_WAY = "something is in its way";

	/**
	 * (undo-words) The undo's stop line for a region's (or a site group's) removal Architect stopped, always in the steward's words: what its typed
	 * cause waits for ({@link #causeWords}, one phrase per cause, shared with a growth's wait), whether anything was taken down from the typed stop
	 * (NOT_STARTED: nothing; PARTIAL: part of it; no typed stop: neither is claimed), and what to do. OTHER, no cause or no feature: the generic line.
	 * Architect's blocker text is never read here. A refused save that took nothing down keeps its own line. Pure.
	 */
	static String undoStopped(@Nullable RemovalStop stop, @Nullable RemovalStopCause cause, String settlementId) {
		return undoStopped(stop, cause, settlementId, null);
	}

	/** As above; {@code part}: for PARTIAL, the steward's own count of what was taken down ("2 of 5 sites"), else "part of it". Pure. */
	static String undoStopped(@Nullable RemovalStop stop, @Nullable RemovalStopCause cause, String settlementId, @Nullable String part) {
		if (stop == RemovalStop.NOT_STARTED && saveRefused(cause)) return "Undo stopped: " + SAVE_REFUSED + " (/steward undo " + settlementId + ").";
		String said = causeWords(cause);
		String taken = stop == RemovalStop.NOT_STARTED ? ", nothing was taken down"
			: stop == RemovalStop.PARTIAL ? ", " + (part != null ? part + " were" : "part of it was") + " taken down" : "";
		String next = saveRefused(cause) ? "Ask again with /steward undo " + settlementId + "." : "Clear it and run /steward undo " + settlementId + " again.";
		return "Undo stopped" + (said != null ? " (" + said + ")" + taken : ": " + IN_THE_WAY + taken) + ". " + next;
	}

	/** (undo-words) What a stopped undo's closing note records: the steward's words for its typed cause, never Architect's text. Pure. */
	static String noted(@Nullable RemovalStop stop, @Nullable RemovalStopCause cause) {
		if (stop == RemovalStop.NOT_STARTED && saveRefused(cause)) return SAVE_REFUSED;
		String said = causeWords(cause);
		return said != null ? said : IN_THE_WAY;
	}

	/**
	 * (RVC) A growth's revision's typed stop cause, when this Architect types it (feature {@code revisionStopCause}); null when it doesn't, the revision
	 * is not waiting, or it can't be read.
	 */
	static @Nullable RemovalStopCause revisionCause(dev.larattalabs.architect.api.@Nullable RevisionView rv) {
		if (rv == null) return null;
		try {
			if (!ArchitectAccess.api().features().contains(REVISION_CAUSE_FEATURE)) return null;
			return rv.removalStopCause();
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/**
	 * (RVC) What a typed stop cause waits for, in the steward's words (never Architect's text, which can name chunk files or the player's things by their
	 * custom names); null for OTHER or no cause (the line then says only that it waits). A refused save is said as a region's undo says it. Pure.
	 */
	static @Nullable String causeWords(@Nullable RemovalStopCause cause) {
		if (cause == null) return null;
		return switch (cause) {
			case SAVE_REFUSED -> WORLD_NOT_SAVED;
			case PLAYER_IN_BOX -> "someone is standing in what it takes up";
			case COVERED -> "other buildings stand over part of it";
			case CARGO -> "its containers hold more than was put in them";
			case THINGS_IN_BOX -> "your things are in its way: filled containers, pets, item frames or dropped items";
			default -> null;
		};
	}

	/**
	 * A growth's take-up Architect left UNDOING with a wait (a stage's removal stopped: resumed by a same-target call or the next world start): said as
	 * waiting, never refused. (8bS) Architect's text is never shown (a refused save's names chunk files). (RVC) With the revision's typed cause (feature
	 * {@code revisionStopCause}) it says what it waits for ({@link #causeWords}); without one, or OTHER, only that it waits. Pure.
	 */
	static String growthWaiting(@Nullable RemovalStopCause cause) {
		String said = causeWords(cause);
		return "The town's change is part way taken up and waits to carry on" + (said != null ? " (" + said + ")" : "") + ": nothing more is taken down until"
			+ " it can. It carries on when you ask again or when the world next starts.";
	}

	/** {@link #growthWaiting(RemovalStopCause)} with no cause: only that it waits. Pure. */
	static String growthWaiting() {
		return growthWaiting(null);
	}

	/** Whether a growth's revision, read after its take-up answered not removed, is waiting part way (UNDOING with a wait), not refused. Pure. */
	static boolean growthWaits(dev.larattalabs.architect.api.@Nullable RevisionView rv) {
		return rv != null && "UNDOING".equalsIgnoreCase(rv.state()) && rv.waiting() != null && !rv.waiting().isBlank();
	}
}
