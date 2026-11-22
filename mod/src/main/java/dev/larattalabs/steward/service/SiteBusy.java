package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectRefused;
import dev.larattalabs.architect.api.Reason;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * (T4S-PRE, TXN-S) What a {@code SITE_BUSY} refusal from Architect's transaction layer means for a removal, read from its typed detail only (T3a's stable
 * {@code ArchitectRefused.detail()} values; T1's {@code Refusal.detail} carries the same leading token with the place after it):
 * <ul>
 * <li>{@code held(decided)}: this removal was decided and is held (a chunk not loaded, a store that didn't load). It finishes by itself later
 * (T4 change 8: {@code RemovalFinished}); nothing is logged as failed or stopped, and nothing is asked again.</li>
 * <li>{@code held(other)}: another transaction holds something this removal needs; nothing changed, so it is asked again in a moment.</li>
 * </ul>
 * The place ({@code <where>}) is read from the detail after its colon, or from the message's {@code held(..) tx-<n>: <where>}, and only ever said in the
 * steward's words: {@code chunk x,z} as "chunk x, z", {@code records unreadable} (T4 change 7) as its own line, anything else not named. A refusal of
 * another reason, {@code SITE_BUSY} with no held detail, or {@code JOURNAL_UNAVAILABLE}/{@code txn_journal} (refused, nothing changed) is
 * {@link Kind#NONE}: the caller's existing words apply. The words for each are {@link RemovalStops}' ({@code underWay}, {@code heldOther}). Pure.
 * <p>
 * (T4RD-S, Architect T4R-D A7a) A removal in the way is typed: {@code ArchitectRefused.busy()} ({@code Busy(kind, blockingId, blockingKind,
 * blockingOwner)}), and its stable detail token {@code <kind_lower>:<blockingId>} (or {@code held(decided)}) where only a {@code Refusal}'s detail reaches
 * Steward ({@code applyDelta}, {@code revert}). Architect's own rule decides the kind, never the text:
 * <ul>
 * <li>{@code HELD_DECIDED} (or the token {@code held(decided)}): {@link Kind#DECIDED}.</li>
 * <li>{@code REMOVAL_IN_PROGRESS}, {@code WAITING_BEHIND}: {@link Kind#WAITS}, another removal is in the way; a {@code REMOVAL_FINISHED} follows it.</li>
 * <li>{@code OPERATION_IN_PROGRESS}, {@code PLAYER_BLOCKS_RETURNING}, {@code held(other)}, and any kind this Steward doesn't know: {@link Kind#OTHER},
 * retried after a short delay (Architect: an unknown kind is never a wait for an event).</li>
 * </ul>
 */
public final class SiteBusy {
	private SiteBusy() {
	}

	/**
	 * Which hold, if any. (T4RD-S) {@link #WAITS}: another removal is in the way (Architect's {@code REMOVAL_IN_PROGRESS}, {@code WAITING_BEHIND}); nothing
	 * of this call began, and it can be asked again once that removal finishes.
	 */
	public enum Kind { NONE, DECIDED, OTHER, WAITS }

	/** (T4RD-S) The typed busy kinds as Steward reads them (Architect's {@code Busy.Kind}, by name; an unknown name is {@link #RETRY_SOON}). */
	public enum Busy { REMOVAL_IN_PROGRESS, WAITING_BEHIND, PLAYER_BLOCKS_RETURNING, HELD_DECIDED, OPERATION_IN_PROGRESS, RETRY_SOON }

	/** The detail tokens (T3a addendum). */
	static final String DECIDED_TOKEN = "held(decided)";
	static final String OTHER_TOKEN = "held(other)";
	/** T4 change 7: the stable {@code <where>} of a hold caused by a participant store that failed to load. */
	static final String RECORDS_UNREADABLE = "records unreadable";

	private static final Pattern CHUNK = Pattern.compile("chunk\\s*(-?\\d+)\\s*,\\s*(-?\\d+)");

	/**
	 * A hold read from a refusal. {@code chunkX}/{@code chunkZ}: the chunk it waits for, when the place names one; {@code recordsUnreadable}: Architect's
	 * records didn't load (T4 change 7).
	 */
	public record Held(Kind kind, @Nullable Integer chunkX, @Nullable Integer chunkZ, boolean recordsUnreadable, @Nullable Busy busy, @Nullable String blockingId,
		@Nullable String blockingKind, @Nullable String blockingOwner) {
		static final Held NONE = new Held(Kind.NONE, null, null, false);

		/** A hold with no typed busy cause (T4S-PRE's shape). */
		public Held(Kind kind, @Nullable Integer chunkX, @Nullable Integer chunkZ, boolean recordsUnreadable) {
			this(kind, chunkX, chunkZ, recordsUnreadable, null, null, null, null);
		}

		/** (T4RD-S) Another removal is in the way: asked again once it finishes. */
		public boolean waits() {
			return kind == Kind.WAITS;
		}

		/** (T4RD-S) Whether Architect refused it as busy at all (any SITE_BUSY: held, waiting, or another operation): never a failure of the call. */
		public boolean busyAtAll() {
			return kind != Kind.NONE;
		}

		public boolean decided() {
			return kind == Kind.DECIDED;
		}

		public boolean other() {
			return kind == Kind.OTHER;
		}

		public boolean held() {
			return kind != Kind.NONE;
		}
	}

	/** {@code siteBusy(err)}: the hold in a failed future's cause chain ({@code CompletionException} wraps it), {@link Held#NONE} for anything else. */
	public static Held of(@Nullable Throwable err) {
		for (Throwable t = err; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof ArchitectRefused r) return of(r);
		}
		return Held.NONE;
	}

	/** (T4RD-S) A typed refusal: its {@code busy()} first (A7a), else its detail token. */
	static Held of(ArchitectRefused r) {
		dev.larattalabs.architect.api.@Nullable Busy b;
		try {
			b = r.busy();
		} catch (LinkageError e) {
			b = null; // an Architect before T4R-D: the detail token only
		}
		if (r.reason() == Reason.SITE_BUSY && b != null && b.kind() != null) {
			Busy k = busyOf(b.kind().name());
			if (k == Busy.HELD_DECIDED) {
				Held h = of(r.reason(), DECIDED_TOKEN, r.getMessage());
				return new Held(Kind.DECIDED, h.chunkX(), h.chunkZ(), h.recordsUnreadable(), k, b.blockingId(), b.blockingKind(), b.blockingOwner());
			}
			return new Held(kindOf(k), null, null, false, k, b.blockingId(), b.blockingKind(), b.blockingOwner());
		}
		return of(r.reason(), r.detail(), r.getMessage());
	}

	/** (T4RD-S) A placement's or delta's refusal ({@code Refusal}, where only the detail token reaches Steward: {@code applyDelta}, {@code revert}). */
	public static Held of(dev.larattalabs.architect.api.@Nullable Refusal r) {
		return r == null ? Held.NONE : of(r.reason(), r.detail(), r.message());
	}

	/** (T4RD-S) Architect's {@code Busy.Kind} by name; a name this Steward doesn't know is {@link Busy#RETRY_SOON}. Pure. */
	static Busy busyOf(@Nullable String name) {
		if (name == null) return Busy.RETRY_SOON;
		for (Busy b : Busy.values()) if (b != Busy.RETRY_SOON && b.name().equals(name)) return b;
		return Busy.RETRY_SOON;
	}

	/** (T4RD-S) The one rule from a typed busy kind to what Steward does: decided, wait for the blocker's end, or ask again soon. Pure. */
	static Kind kindOf(Busy b) {
		return switch (b) {
			case HELD_DECIDED -> Kind.DECIDED;
			case REMOVAL_IN_PROGRESS, WAITING_BEHIND -> Kind.WAITS;
			default -> Kind.OTHER;
		};
	}

	/** The hold for a typed refusal: {@code reason}, its {@code detail} and its message (where the place is when the detail is the bare token). */
	static Held of(@Nullable Reason reason, @Nullable String detail, @Nullable String message) {
		if (reason != Reason.SITE_BUSY) return Held.NONE;
		String d = detail == null ? "" : detail.strip();
		Kind kind = d.startsWith(DECIDED_TOKEN) ? Kind.DECIDED : d.startsWith(OTHER_TOKEN) ? Kind.OTHER : Kind.NONE;
		if (kind == Kind.NONE) return typedToken(d);
		// the place: after the detail's own colon (Refusal.detail: "held(decided) tx-7: chunk 3,4"), else the message's
		String where = d.contains(":") ? d.substring(d.indexOf(':') + 1) : placeIn(message, kind == Kind.DECIDED ? DECIDED_TOKEN : OTHER_TOKEN);
		String w = where == null ? "" : where.strip();
		Matcher m = CHUNK.matcher(w);
		if (m.find()) {
			try {
				return new Held(kind, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), false);
			} catch (NumberFormatException e) {
				// past an int: not named
			}
		}
		return new Held(kind, null, null, w.startsWith(RECORDS_UNREADABLE));
	}

	/**
	 * (T4RD-S) A typed busy detail token {@code <kind_lower>:<blockingId>} (A7a's {@code Busy.token()}), for a known kind only: any other detail (empty
	 * included, today's busy case) is {@link Held#NONE}, so the caller's words stay. Pure.
	 */
	static Held typedToken(String d) {
		int colon = d.indexOf(':');
		if (colon <= 0) return Held.NONE;
		String head = d.substring(0, colon).toUpperCase(java.util.Locale.ROOT);
		Busy b = busyOf(head);
		if (b == Busy.RETRY_SOON || b == Busy.HELD_DECIDED) return Held.NONE;
		String id = d.substring(colon + 1).strip();
		return new Held(kindOf(b), null, null, false, b, id.isEmpty() ? null : id, null, null);
	}

	/** The place in a message {@code held(..) tx-<n>: <where>} (only after its token), or null. */
	private static @Nullable String placeIn(@Nullable String message, String token) {
		if (message == null) return null;
		int at = message.indexOf(token);
		if (at < 0) return null;
		int colon = message.indexOf(':', at + token.length());
		return colon < 0 ? null : message.substring(colon + 1);
	}
}
