package dev.larattalabs.steward.client.screen;

import dev.larattalabs.steward.inbox.InboxModel;
import java.util.List;

/**
 * The player's inbox as the server last sent it: one holder the HUD line and the inbox screen read. Never changed by the client itself: after a decision the
 * screen waits for the server's next inbox (sent after every pipeline step), so a refused decision shows as still waiting. Client thread.
 */
public final class ClientInbox {
	private static List<InboxModel.Entry> entries = List.of();
	private static long version;

	private ClientInbox() {
	}

	public static void set(List<InboxModel.Entry> e) {
		entries = List.copyOf(e);
		version++;
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
		version++;
	}
}
