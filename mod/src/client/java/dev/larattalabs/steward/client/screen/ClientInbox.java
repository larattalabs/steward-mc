package dev.larattalabs.steward.client.screen;

import dev.larattalabs.labui.client.hud.Toasts;
import dev.larattalabs.labui.client.jobs.JobBoard;
import dev.larattalabs.steward.client.hud.Keys;
import dev.larattalabs.steward.inbox.InboxJobs;
import dev.larattalabs.steward.inbox.InboxModel;
import dev.larattalabs.steward.inbox.InboxToasts;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * The player's inbox as the server last sent it: one holder the inbox screen reads. Never changed by the client itself: after a decision the screen waits for
 * the server's next inbox (sent after every pipeline step), so a refused decision shows as still waiting. Every inbox is published to lab-ui's shared Jobs
 * &amp; spend panel ({@link InboxJobs}), which also draws the shared needs-you pill; a build that first needs the player, or ends, raises a lab-ui toast
 * ({@link InboxToasts}: one of each per build) carrying its panel row. Client thread.
 */
public final class ClientInbox {
	private static List<InboxModel.Entry> entries = List.of();
	private static long version;
	private static final InboxToasts TOASTS = new InboxToasts();
	private static final InboxJobs JOBS = new InboxJobs();

	private ClientInbox() {
	}

	public static void set(List<InboxModel.Entry> e) {
		entries = List.copyOf(e);
		version++;
		JobBoard.publish(InboxJobs.MOD, JOBS.rows(entries, System.currentTimeMillis(), id -> () -> Minecraft.getInstance().gui.setScreen(new InboxScreen(id))));
		for (InboxToasts.Toast t : TOASTS.update(entries)) {
			Toasts.Level level = t.need() ? Toasts.Level.NEED : t.warn() ? Toasts.Level.WARN : Toasts.Level.INFO;
			Toasts.push(Toasts.Spec.of(level, t.title(), t.body()).bodyLines(3).subject(t.title().startsWith(t.subject()) ? t.subject() : null)
				.hint(Keys.label(Keys.inbox), "open").ref(t.ref()));
		}
	}

	public static List<InboxModel.Entry> entries() {
		return entries;
	}

	/** Bumped on every update (a screen notices that its sent decision got an answer). */
	public static long version() {
		return version;
	}

	/** How many entries are blocked on the player (never an idea): the inbox's title counts these, as the nameplate's "!" and the shared pill do. */
	public static long waiting() {
		return entries.stream().filter(InboxModel.Entry::needsYou).count();
	}

	public static void clear() {
		entries = List.of();
		TOASTS.clear();
		JOBS.clear();
		// the panel's rows go with the world (lab-ui 0.4.1 has no way to drop toasts already queued: they lapse on their own, within 2 min)
		JobBoard.publish(InboxJobs.MOD, List.of());
		version++;
	}
}
