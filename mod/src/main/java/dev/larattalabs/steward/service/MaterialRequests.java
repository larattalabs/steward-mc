package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.SiteEvents;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.architect.api.SiteWait;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The steward asks for materials (docs/PLAN.md phase 4, Noah: all three ways). When a build of a Supplied settlement starts waiting for materials (Architect's
 * {@code SITE_WAITING}, C25), its bubble names what that building needs next; the inbox and {@code /steward stockpile} show the whole bill; and when a build
 * has waited {@link #LONG_WAIT_TICKS} with the player in the settlement, the steward comes to find them ({@link Summons}) and says what is short. Server thread.
 */
public final class MaterialRequests {
	/** Ten minutes of the world's time: then the steward comes to find the player. */
	static final long LONG_WAIT_TICKS = 12_000;
	/** How often the long waits are read (every 10 s). */
	private static final int EVERY = 200;
	/** Targets whose start of waiting was said, by the tick it began (each wait is said once). */
	private static final Map<String, Long> SAID = new HashMap<>();
	/** Settlements with a long wait: the player the steward goes to find, and the wait's start (said once per wait). */
	private static final Map<String, LongWait> LONG = new HashMap<>();

	private record LongWait(UUID player, long since) {}

	private static volatile MinecraftServer server;

	private MaterialRequests() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			SAID.clear();
			LONG.clear();
		});
		try {
			SiteEvents.SITE_WAITING.register(MaterialRequests::onWaiting);
		} catch (LinkageError e) {
			Steward.LOGGER.info("this Architect has no waiting status (it needs 0.18): the steward does not ask for materials");
			return;
		}
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (s.getTickCount() % EVERY != 0) return;
			try {
				readLongWaits(s);
			} catch (RuntimeException | LinkageError e) {
				Steward.LOGGER.warn("could not read the material waits", e);
			}
		});
	}

	/** The player the steward should go and find because a build has waited long for materials, if any. */
	public static Optional<UUID> longWaitFor(String settlementId) {
		var w = LONG.get(settlementId);
		return w == null ? Optional.empty() : Optional.of(w.player());
	}

	private static void onWaiting(SiteView site, SiteWait wait) {
		var srv = server;
		if (srv == null || !wait.waiting() || wait.nextNeeded().isEmpty()) return;
		var s = settlementOf(site.owner());
		if (s.isEmpty()) return;
		Long said = SAID.get(wait.targetId());
		if (said != null && said == wait.waitingSince()) return;
		SAID.put(wait.targetId(), wait.waitingSince());
		StewardVoice.say(srv, s.get().id(), "The " + nameOf(site) + " waits for materials: " + needs(wait) + ". They go in the stockpile.");
	}

	/** Reads each settlement's waiting targets: one waiting {@link #LONG_WAIT_TICKS} sends the steward to a player in the settlement, once per wait. */
	private static void readLongWaits(MinecraftServer srv) {
		long now = srv.overworld().getGameTime();
		var sites = ArchitectApi.get().sites(srv);
		for (Settlement s : Settlements.store().all()) {
			var sp = Stockpiles.of(srv, s);
			if (sp.isEmpty()) {
				LONG.remove(s.id());
				continue;
			}
			// the longest wait among the settlement's targets (the stockpile's own list; else each target asked)
			SiteWait longest = null;
			for (String target : sp.get().feedOrder()) {
				SiteWait w = sp.get().waiting().get(target);
				if (w == null) w = sites.waiting(target).orElse(null);
				if (w != null && w.waiting() && (longest == null || w.waitingSince() < longest.waitingSince())) longest = w;
			}
			if (longest == null || now - longest.waitingSince() < LONG_WAIT_TICKS) {
				LONG.remove(s.id());
				continue;
			}
			ServerPlayer p = playerIn(srv, s);
			if (p == null) continue;
			var had = LONG.get(s.id());
			LONG.put(s.id(), new LongWait(p.getUUID(), longest.waitingSince()));
			if (had == null || had.since() != longest.waitingSince()) {
				var target = sites.get(longest.targetId());
				String what = target.map(MaterialRequests::nameOf).orElse("build");
				StewardVoice.say(srv, s.id(), "The " + what + " has waited a while for materials: " + needs(longest) + ". Fill the stockpile and I can carry on.");
			}
		}
	}

	/** "12 oak planks, 4 glass" (and how many more units beyond the first 8 kinds). */
	static String needs(SiteWait w) {
		List<Map.Entry<net.minecraft.world.item.Item, Integer>> list = w.nextNeeded().stream()
			.map(n -> Map.entry(n.item(), n.count())).toList();
		return Stockpiles.items(list, 4) + (w.moreUnits() > 0 ? " (" + w.moreUnits() + " more besides)" : "");
	}

	private static String nameOf(SiteView v) {
		if (dev.larattalabs.steward.gateway.BatchPlanner.isStreet(v.itemKey())) return "street";
		if ("road".equals(v.kind())) return "road";
		return Updates.lotOf(v);
	}

	private static Optional<Settlement> settlementOf(String owner) {
		String prefix = "steward_mc:settlement/";
		return owner == null || !owner.startsWith(prefix) ? Optional.empty() : Settlements.store().get(owner.substring(prefix.length()));
	}

	private static ServerPlayer playerIn(MinecraftServer srv, Settlement s) {
		for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
			var at = p.blockPosition();
			if (s.contains(p.level().dimension().identifier().toString(), at.getX(), at.getY(), at.getZ())) return p;
		}
		return null;
	}
}
