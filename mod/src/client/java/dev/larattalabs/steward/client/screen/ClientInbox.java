package dev.larattalabs.steward.client.screen;

import dev.larattalabs.steward.client.hud.Keys;
import dev.larattalabs.steward.client.hud.Toasts;
import dev.larattalabs.steward.inbox.InboxModel;
import dev.larattalabs.steward.inbox.InboxToasts;
import java.util.List;

/**
 * The player's inbox as the server last sent it: one holder the HUD line and the inbox screen read. Never changed by the client itself: after a decision the
 * screen waits for the server's next inbox (sent after every pipeline step), so a refused decision shows as still waiting. A build that first needs the
 * player, or ends, raises a toast ({@link InboxToasts}: one of each per build). Client thread.
 */
public final class ClientInbox {
	private static List<InboxModel.Entry> entries = List.of();
	private static long version;
	private static final InboxToasts TOASTS = new InboxToasts();

	private ClientInbox() {
	}

	public static void set(List<InboxModel.Entry> e) {
		entries = List.copyOf(e);
		version++;
		for (InboxToasts.Toast t : TOASTS.update(entries)) {
			Toasts.push(t.need() ? Toasts.Level.NEED : t.warn() ? Toasts.Level.WARN : Toasts.Level.INFO, t.title(), t.body(), Keys.label(Keys.inbox), "open");
		}
	}

	public static List<InboxModel.Entry> entries() {
		return entries;
	}

	/** Bumped on every update (a screen notices that its sent decision got an answer). */
	public static long version() {
		return version;
	}

	public static long waiting() {
		return entries.stream().filter(InboxModel.Entry::waiting).count();
	}

	public static void clear() {
		entries = List.of();
		TOASTS.clear();
		// the toasts already shown or waiting go too: after a relog they would come again from the new inbox, and in another world they aren't its own
		dev.larattalabs.steward.client.hud.Toasts.clear();
		version++;
	}
}
