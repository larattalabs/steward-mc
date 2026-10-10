package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.DeltaVerdict;
import dev.larattalabs.architect.api.OutdatedSite;
import dev.larattalabs.architect.api.SiteEvents;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.UpdatePlanner;
import dev.larattalabs.steward.inbox.InboxModel;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * "Update available" for a settlement's placed buildings (Architect delta apply, API 1.7.0): when a building's library entry gets a newer version, the
 * steward checks the delta ({@code checkDelta}) and, by the settlement's permission level ({@link UpdatePlanner}), applies it at once (Autonomous and Full,
 * when it costs no materials) or offers it in the inbox (approve one or all, preview the change as a ghost, skip). The player's own edits to a building are
 * kept. Outdated buildings are found at world load ({@code Sites.outdated}, since version events are missed while the world is closed) and after every
 * {@code ENTRY_VERSIONED}, on the next tick (several versions, or applies finishing inline, make one scan). Pending updates live in memory and are found again
 * after a restart; a skipped version is not offered again this session. An update that would write outside the claim is blocked; one that failed on its own
 * is not retried by itself (it waits in the inbox for the player). Server thread.
 */
public final class Updates {
	/** One building with a newer version: what the delta does and what Steward does with it. */
	public record Pending(String siteId, String lot, int from, int to, UpdatePlanner.Action action, String text) {}

	private static final Map<String, List<Pending>> PENDING = new LinkedHashMap<>();
	/** {@code siteId@version}: offers the player skipped (this session). */
	private static final Set<String> SKIPPED = new HashSet<>();
	/** Sites with an apply on its way: not offered or applied again until it ends. */
	private static final Set<String> APPLYING = new HashSet<>();
	/** {@code siteId@version}: applies that failed, with why. Not applied automatically again (the player can retry from the inbox). */
	private static final Map<String, String> FAILED = new java.util.HashMap<>();
	private static volatile boolean scanPending;
	private static volatile MinecraftServer server;

	private Updates() {
	}

	public static void init() {
		SiteEvents.ENTRY_VERSIONED.register((entry, from) -> scanPending = true);
		// after Architect loads its sites (SERVER_STARTED), on the first tick
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			scanPending = true;
		});
		ServerTickEvents.START_SERVER_TICK.register(server -> {
			if (!scanPending) return;
			scanPending = false;
			refreshAll(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			PENDING.clear();
			SKIPPED.clear();
			APPLYING.clear();
			FAILED.clear();
			scanPending = false;
		});
	}

	public static void refreshAll(MinecraftServer server) {
		for (Settlement s : Settlements.store().all()) {
			try {
				refresh(server, s);
			} catch (RuntimeException e) {
				Steward.LOGGER.warn("could not check {} for updates", s.id(), e);
			}
		}
		server.getPlayerList().getPlayers().forEach(p -> SettlementRunner.sendInbox(server, p.getUUID()));
	}

	/** Re-checks one settlement: applies what its permission lets the steward apply, keeps the rest pending. */
	static void refresh(MinecraftServer server, Settlement s) {
		var sites = ArchitectApi.get().sites(server);
		List<Pending> pending = new ArrayList<>();
		for (OutdatedSite o : sites.outdated(s.owner())) {
			String key = o.siteId() + "@" + o.headVersion();
			if (SKIPPED.contains(key) || APPLYING.contains(o.siteId())) continue;
			// the player reverted away from this version (or a newer one): not offered again; a version after it is
			if (o.headVersion() <= s.revertedFrom(o.siteId())) continue;
			var view = sites.get(o.siteId());
			// a site of this owner in another dimension is not this settlement's
			if (view.isPresent() && !view.get().dimension().identifier().toString().equals(s.claim().dimension())) continue;
			String lot = view.map(Updates::lotOf).orElse(o.siteId());
			DeltaVerdict v = sites.checkDelta(UpdatePlanner.request(o.siteId(), o.headVersion(), UpdatePlanner.editsFor(s.permission()), s.owner()));
			UpdatePlanner.Plan plan = UpdatePlanner.plan(lot, v, s.permission(), s.claim());
			if (plan.action() == UpdatePlanner.Action.APPLY && FAILED.containsKey(key)) {
				plan = new UpdatePlanner.Plan(UpdatePlanner.Action.ASK, lot + " could not be updated by itself (" + FAILED.get(key) + "); update to try again.", plan.playerEdits());
			}
			Steward.LOGGER.info("update check {} {} v{} -> v{}: {} ({}), writes {}", s.id(), o.siteId(), o.version(), o.headVersion(), plan.action(), plan.text(), v.box());
			switch (plan.action()) {
				case NOTHING -> {
				}
				case APPLY -> apply(server, s, new Pending(o.siteId(), lot, o.version(), o.headVersion(), plan.action(), plan.text()));
				case ASK, BLOCKED -> pending.add(new Pending(o.siteId(), lot, o.version(), o.headVersion(), plan.action(), plan.text()));
			}
		}
		if (pending.isEmpty()) PENDING.remove(s.id());
		else PENDING.put(s.id(), List.copyOf(pending));
	}

	/** The building's role as placed ("fishers' cottage"), else its lot id (sites placed before roles were recorded), else the site id. */
	static String lotOf(SiteView v) {
		if (v.ext() == null) return v.id();
		if (v.ext().has("steward_mc:role")) return v.ext().get("steward_mc:role").getAsString();
		return v.ext().has("steward_mc:lot") ? v.ext().get("steward_mc:lot").getAsString() : v.id();
	}

	/** Applies one pending update (or, with an empty {@code siteId}, every one that can be applied). Returns what to tell the player now. */
	public static String approve(MinecraftServer server, String settlementId, String siteId) {
		var s = Settlements.store().get(settlementId);
		List<Pending> list = PENDING.getOrDefault(settlementId, List.of());
		if (s.isEmpty() || list.isEmpty()) return "No update is waiting for " + settlementId + ".";
		int n = 0;
		for (Pending p : list) {
			if (!siteId.isEmpty() && !p.siteId().equals(siteId)) continue;
			if (p.action() == UpdatePlanner.Action.BLOCKED) continue;
			// checked again now: the offer may be old, and the world (or the claim) has changed since
			var sites = ArchitectApi.get().sites(server);
			DeltaVerdict now = sites.checkDelta(UpdatePlanner.request(p.siteId(), p.to(), UpdatePlanner.editsFor(s.get().permission()), s.get().owner()));
			UpdatePlanner.Plan plan = UpdatePlanner.plan(p.lot(), now, s.get().permission(), s.get().claim());
			if (plan.action() == UpdatePlanner.Action.BLOCKED || !now.ok()) continue;
			FAILED.remove(p.siteId() + "@" + p.to());
			apply(server, s.get(), p);
			n++;
		}
		// what could not be applied now shows its current reason
		if (n == 0) {
			refresh(server, s.get());
			server.getPlayerList().getPlayers().forEach(pl -> SettlementRunner.sendInbox(server, pl.getUUID()));
		}
		return n == 0 ? "Nothing there can be applied now (see why in the inbox)." : "Updating " + n + (n == 1 ? " building" : " buildings") + "...";
	}

	/** Stops offering these versions this session. */
	public static String skip(MinecraftServer server, String settlementId, String siteId) {
		List<Pending> list = PENDING.getOrDefault(settlementId, List.of());
		for (Pending p : list) {
			if (!siteId.isEmpty() && !p.siteId().equals(siteId)) continue;
			SKIPPED.add(p.siteId() + "@" + p.to());
			clearPreview(server, p.siteId());
		}
		Settlements.store().get(settlementId).ifPresent(s -> refresh(server, s));
		server.getPlayerList().getPlayers().forEach(pl -> SettlementRunner.sendInbox(server, pl.getUUID()));
		return "Skipped; it is offered again when a newer version comes.";
	}

	private static void apply(MinecraftServer server, Settlement s, Pending p) {
		var sites = ArchitectApi.get().sites(server);
		if (!APPLYING.add(p.siteId())) return;
		int session = Session.current();
		sites.get(p.siteId()).ifPresent(v -> StewardMotion.clear(server, s, v.box()));
		sites.applyDelta(UpdatePlanner.request(p.siteId(), p.to(), UpdatePlanner.editsFor(s.permission()), s.owner())).whenComplete((r, err) -> {
			if (!Session.is(session)) return;
			APPLYING.remove(p.siteId());
			clearPreview(server, p.siteId());
			String msg;
			String key = p.siteId() + "@" + p.to();
			var change = new Settlement.SiteChange(p.siteId(), p.lot(), p.from(), p.to());
			if (err != null) {
				String why = err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
				FAILED.put(key, why);
				msg = "Could not update " + p.lot() + ": " + why;
			} else if (!r.applied()) {
				FAILED.put(key, "refused");
				msg = "Could not update " + p.lot() + " (refused).";
			} else {
				FAILED.remove(key);
				msg = "Updated " + p.lot() + " to version " + r.toVersion() + " (" + r.written() + " blocks" + (r.kept().isEmpty() ? "" : ", " + r.kept().size() + " of your edits kept")
					+ ").";
				change = new Settlement.SiteChange(p.siteId(), p.lot(), p.from(), r.toVersion());
				// the steward goes to look at it
				sites.get(p.siteId()).ifPresent(v -> StewardMotion.visit(server, s, v.box()));
			}
			// failed ones too: the history says what was tried
			Settlements.log(s.id(), Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.UPDATED, msg, List.of(change), null,
				msg.startsWith("Updated") ? Settlement.Outcome.DONE : Settlement.Outcome.FAILED));
			server.getPlayerList().getPlayers().forEach(pl -> pl.sendSystemMessage(Component.literal("Steward (" + s.name() + "): " + msg)));
			// the next scan (next tick) offers what is left; not here, where an apply that completed inline would recurse
			scanPending = true;
		});
	}

	/** Scans again on the next tick (after a revert, or anything else that changes what is offered). */
	public static void rescan() {
		scanPending = true;
	}

	/** Takes an update's preview ghost away (it resolved, or was skipped). */
	private static void clearPreview(MinecraftServer server, String siteId) {
		var clear = new dev.larattalabs.steward.net.StewardNet.ShowLayers(previewKey(siteId), List.of());
		server.getPlayerList().getPlayers().forEach(pl -> net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(pl, clear));
	}

	/** The composite key an update's preview shows under. */
	public static String previewKey(String siteId) {
		return "steward_mc:update/" + siteId;
	}

	/** The pending updates of a settlement, for the inbox. */
	public static List<Pending> pending(String settlementId) {
		return PENDING.getOrDefault(settlementId, List.of());
	}

	/** One inbox entry per settlement with updates waiting. */
	public static List<InboxModel.Entry> inboxEntries() {
		List<InboxModel.Entry> out = new ArrayList<>();
		for (var e : PENDING.entrySet()) {
			var s = Settlements.store().get(e.getKey());
			if (s.isEmpty()) continue;
			out.add(InboxModel.updates(s.get().id(), s.get().name(), e.getValue().stream().map(p -> new InboxModel.Lot(p.siteId(), p.lot(), "update",
				false, p.action() == UpdatePlanner.Action.ASK, "v" + p.from() + " → v" + p.to() + " · " + p.text().replaceFirst("^[^:]*: ", ""))).toList()));
		}
		return out;
	}
}
