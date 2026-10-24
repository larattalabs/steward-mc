package dev.larattalabs.steward.inbox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which toasts a new inbox calls for (pure; the client pushes them): one "needs you" when a build first waits for the player, one when it ends, at most one
 * of each per build however often the inbox is resent. A build is its entry's key until it ends: once its row leaves the inbox (or a new build of the
 * settlement replaces it), the next build toasts again. Updates and proposals do not toast (the HUD line and the inbox carry them). Client thread.
 */
public final class InboxToasts {
	/** {@code need}: a "needs you" toast; {@code warn}: a failure. */
	public record Toast(boolean need, boolean warn, String title, String body) {
	}

	private final Set<String> needSaid = new HashSet<>();
	private final Set<String> endSaid = new HashSet<>();

	/** The toasts the new inbox {@code entries} calls for, remembering what was said. */
	public List<Toast> update(List<InboxModel.Entry> entries) {
		List<Toast> out = new ArrayList<>();
		Set<String> present = new HashSet<>();
		for (InboxModel.Entry e : entries) {
			if ("UPDATE".equals(e.decision()) || "PROPOSAL".equals(e.decision())) continue;
			String key = e.key();
			present.add(key);
			if (e.ended()) {
				// its "needs you" belonged to this build: the settlement's next build may ask again
				needSaid.remove(key);
				if (endSaid.add(key)) out.add(ended(e));
				continue;
			}
			// running again under the same key: a new build, whose end is news again
			endSaid.remove(key);
			if (e.waiting() && needSaid.add(key)) out.add(new Toast(true, false, e.name() + " needs you", e.headline()));
		}
		needSaid.retainAll(present);
		endSaid.retainAll(present);
		return out;
	}

	private static Toast ended(InboxModel.Entry e) {
		return switch (e.decision()) {
			case "FAILED" -> new Toast(false, true, e.name() + ": the build failed", e.headline());
			case "CANCELLED" -> new Toast(false, false, e.name() + ": the build was cancelled", e.headline());
			default -> new Toast(false, false, e.name() + " is built", e.headline());
		};
	}

	/** Forgets everything (the player left the world). */
	public void clear() {
		needSaid.clear();
		endSaid.clear();
	}
}
