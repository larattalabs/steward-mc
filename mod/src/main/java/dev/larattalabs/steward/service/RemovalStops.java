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
		return "Undo stopped" + (said != null ? " (" + said + ")" + taken : ": " + IN_THE_WAY + taken) + ". " + again(cause, "/steward undo " + settlementId);
	}

	/**
	 * (raw-text) When a stopped removal can be asked again, for each typed cause: the one table every stop line ends with (an undo's, a cancel's). OTHER,
	 * no cause or no feature: once it is clear. Pure.
	 */
	static String tail(@Nullable RemovalStopCause cause) {
		if (cause == null) return "once it is clear";
		return switch (cause) {
			case SAVE_REFUSED -> "in a moment";
			case PLAYER_IN_BOX -> "once they step out";
			case COVERED -> "once what stands over it is taken down";
			case CARGO -> "once what was added to its containers is taken out";
			case THINGS_IN_BOX -> "once your things are moved out";
			case WAITING_BEHIND -> "once the removal next to it is cleared";
			default -> "once it is clear";
		};
	}

	/** (raw-text) The sentence a stop line ends with: ask again with {@code command} ("/steward undo set_1"), {@link #tail} for its cause. Pure. */
	static String again(@Nullable RemovalStopCause cause, String command) {
		if (saveRefused(cause)) return "Ask again with " + command + ".";
		return "Run " + command + " again " + tail(cause) + ".";
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
			case WAITING_BEHIND -> "another removal next to it took longer than 10 minutes to clear";
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

	/**
	 * (T4S-PRE) A growth's take-up whose decided hold carries on by itself ({@code held(decided)}, T4): it waits for what the hold names, and nothing is
	 * asked of the player. Pure.
	 */
	static String growthWaiting(@Nullable RemovalStopCause cause, boolean byItself) {
		if (!byItself) return growthWaiting(cause);
		String said = causeWords(cause);
		return "The town's change is part way taken up and waits to carry on" + (said != null ? " (" + said + ")" : "") + ": it carries on by itself. Nothing"
			+ " more to do.";
	}

	// ------------------------------------------------------------------ (T4S-PRE) a removal Architect's transactions hold (SiteBusy)

	/** What a decided, held removal waits for, in the steward's words ("it finishes by itself when chunk 3, -4 loads"); never Architect's text. Pure. */
	static String waitsFor(SiteBusy.Held h) {
		if (h.recordsUnreadable()) return RECORDS_UNREADABLE;
		if (h.chunkX() != null && h.chunkZ() != null) return "it finishes by itself when chunk " + h.chunkX() + ", " + h.chunkZ() + " loads";
		return "it finishes by itself";
	}

	/** (T4 change 7) A hold caused by Architect's records not loading. */
	static final String RECORDS_UNREADABLE = "Architect's records didn't load; it waits";

	/**
	 * An undo (or a cancel's take-up) Architect decided and holds: under way, finishing by itself, nothing for the player to do. Never "Undone" or "Undo
	 * stopped" ({@code Undo.reportingOverCapacity} reads those: the refund has not landed yet). Pure.
	 */
	static String underWay(SiteBusy.Held h) {
		if (h.recordsUnreadable()) return "Undo under way: " + RECORDS_UNREADABLE + ", and finishes by itself. Nothing more to do.";
		return "Undo under way: " + waitsFor(h) + ". Nothing more to do.";
	}

	/** A growth's take-up Architect decided and holds: it carries on by itself (never "ask again"). Pure. */
	static String growthUnderWay(SiteBusy.Held h) {
		return "The town's change is being taken up: " + waitsFor(h) + ". Nothing more to do.";
	}

	/** Another change holds what a removal needs ({@code held(other)}; nothing changed): asked again in a moment. Pure. */
	static final String HELD_OTHER = "Architect is still finishing an earlier change there; it is tried again in a moment.";

	/**
	 * (T4 change 8) What a stop of a removal on Architect's transactions means: blockers are decided before it begins, so it took nothing down. Pure.
	 */
	static final String NOTHING_TAKEN = "nothing was taken down";

	// ------------------------------------------------------------------ (T4RD-S) a call refused because another removal or change is in the way

	/** (T4RD-S) What stands in the way when Steward can't name it: never Architect's ids (an extraction's or a txn's are its own). */
	static final String A_BUILD_NEARBY = "a build nearby";

	/** The owner id prefix of a Steward settlement ({@code steward_mc:settlement/<id>}). */
	static final String SETTLEMENT_OWNER = "steward_mc:settlement/";

	/**
	 * (T4RD-S) The removal in the way, named in the steward's words from the typed busy cause (A7a): this settlement's own site or road by its lot ("the old
	 * smithy"), its own group or region as its old buildings, another settlement's as "&lt;town&gt;'s old buildings", anything else (no owner, a player's,
	 * an id Steward can't resolve, an extraction or a txn) as {@link #A_BUILD_NEARBY}. {@code lotOf}: a site id to its lot name, or null; {@code townOf}: a
	 * settlement id to its name, or null. Pure over the two lookups.
	 */
	static String blocker(SiteBusy.Held h, String owner, java.util.function.Function<String, @Nullable String> lotOf,
		java.util.function.Function<String, @Nullable String> townOf) {
		String id = h.blockingId(), kind = h.blockingKind(), who = h.blockingOwner();
		if (id == null || id.isBlank() || who == null) return A_BUILD_NEARBY;
		if (who.equals(owner)) {
			if ("site".equals(kind) || "road".equals(kind)) {
				String lot = lotOf.apply(id);
				return lot != null && !lot.isBlank() ? "the old " + lot : "one of your old buildings";
			}
			if ("group".equals(kind) || "stage".equals(kind) || "region".equals(kind)) return "your old buildings";
			return A_BUILD_NEARBY;
		}
		if (who.startsWith(SETTLEMENT_OWNER)) {
			String town = townOf.apply(who.substring(SETTLEMENT_OWNER.length()));
			if (town != null && !town.isBlank()) return town + "'s old buildings";
		}
		return A_BUILD_NEARBY;
	}

	/** (T4RD-S) {@link #blocker} read from the running game: lots from Architect's sites, towns from the settlement store; never throws. */
	static String blocker(net.minecraft.server.@Nullable MinecraftServer server, SiteBusy.Held h, String owner) {
		if (!h.waits() || server == null) return A_BUILD_NEARBY;
		return blocker(h, owner, id -> {
			try {
				return ArchitectAccess.api().sites(server).get(id).map(Updates::lotOf).orElse(null);
			} catch (RuntimeException | LinkageError e) {
				return null;
			}
		}, sid -> {
			try {
				return Settlements.store().get(sid).map(dev.larattalabs.steward.model.Settlement::name).orElse(null);
			} catch (RuntimeException | LinkageError e) {
				return null;
			}
		});
	}

	/**
	 * (T4RD-S) Why a busy call didn't go ahead, as a clause: another removal ({@link SiteBusy.Kind#WAITS}) "&lt;blocker&gt; is still being taken down next to
	 * it", the player's blocks on a road being given back, or (another change, a kind Steward doesn't know, held(other)) "Architect is still finishing
	 * another change there". Never "being taken down" for anything that is not a removal. Pure.
	 */
	static String busyWhy(SiteBusy.Held h, String blocker) {
		if (h.waits()) return blocker + " is still being taken down next to it";
		if (h.busy() == SiteBusy.Busy.PLAYER_BLOCKS_RETURNING) return "the player's blocks there are being given back first";
		return "Architect is still finishing another change there";
	}

	/** (T4RD-S) When a busy call can be asked again: once the removal in the way is cleared, else in a moment. Pure. */
	static String busyWhen(SiteBusy.Held h) {
		return h.waits() ? "once it's cleared" : "in a moment";
	}

	/** (T4RD-S) A site-by-site undo (row 1) that stopped on a busy site: what was taken down, why in the steward's words, and when to ask again. Pure. */
	static String undoBusy(String settlementId, int removed, int of, SiteBusy.Held h, String blocker) {
		return "Undo stopped after " + removed + " of " + dev.larattalabs.steward.model.Words.count(of, "site", "sites") + ": " + busyWhy(h, blocker) + ". Run /steward undo "
			+ settlementId + " again " + busyWhen(h) + ".";
	}

	/** (T4RD-S) A whole group's or village's undo (rows 2, 3) refused as busy before it began: nothing was taken down. Pure. */
	static String undoBusy(String settlementId, SiteBusy.Held h, String blocker) {
		return "Undo stopped: " + busyWhy(h, blocker) + ", so nothing was taken down. Run /steward undo " + settlementId + " again " + busyWhen(h) + ".";
	}

	/** (T4RD-S) A growth's take-up (row 4) refused as busy. Pure. */
	static String growthBusy(SiteBusy.Held h, String blocker) {
		return "The town's change could not be taken up: " + busyWhy(h, blocker) + ". Ask again " + busyWhen(h) + ".";
	}

	/** (T4RD-S) A village's cancel (row 5) refused as busy: it stays cancelling. Pure. */
	static String cancelBusy(String settlementId, SiteBusy.Held h, String blocker) {
		return "Not yet: " + busyWhy(h, blocker) + ". Run /steward cancel " + settlementId + " again " + busyWhen(h) + ".";
	}

	/** (T4RD-S) An unrecorded road's take-up (row 7) refused as busy: tried again later by itself. {@code why} says what the road is. Pure. */
	static String roadBusy(String why, String roadId, SiteBusy.Held h, String blocker) {
		return why + ": it is taken up once " + (h.waits() ? blocker + " next to it is cleared" : "Architect has finished another change there")
			+ ".";
	}

	/**
	 * (T4RD-S) Rows 8-12, every other call ({@code applyDelta}, {@code revert}, a crate, a placement): the clause {@link RefusalWords} says after "Could
	 * not ...: ". The blocker is not named (these calls' busy reach Steward as a detail token without its owner). Pure.
	 */
	static String busyReason(SiteBusy.Held h) {
		if (h.decided()) return "it is being taken down, and finishes by itself";
		return busyWhy(h, "a build nearby") + "; it can be done " + (h.waits() ? "once that's cleared" : "in a moment");
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
