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
 */
public final class SiteBusy {
	private SiteBusy() {
	}

	/** Which hold, if any. */
	public enum Kind { NONE, DECIDED, OTHER }

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
	public record Held(Kind kind, @Nullable Integer chunkX, @Nullable Integer chunkZ, boolean recordsUnreadable) {
		static final Held NONE = new Held(Kind.NONE, null, null, false);

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
			if (t instanceof ArchitectRefused r) return of(r.reason(), r.detail(), r.getMessage());
		}
		return Held.NONE;
	}

	/** The hold for a typed refusal: {@code reason}, its {@code detail} and its message (where the place is when the detail is the bare token). */
	static Held of(@Nullable Reason reason, @Nullable String detail, @Nullable String message) {
		if (reason != Reason.SITE_BUSY) return Held.NONE;
		String d = detail == null ? "" : detail.strip();
		Kind kind = d.startsWith(DECIDED_TOKEN) ? Kind.DECIDED : d.startsWith(OTHER_TOKEN) ? Kind.OTHER : Kind.NONE;
		if (kind == Kind.NONE) return Held.NONE;
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

	/** The place in a message {@code held(..) tx-<n>: <where>} (only after its token), or null. */
	private static @Nullable String placeIn(@Nullable String message, String token) {
		if (message == null) return null;
		int at = message.indexOf(token);
		if (at < 0) return null;
		int colon = message.indexOf(':', at + token.length());
		return colon < 0 ? null : message.substring(colon + 1);
	}
}
