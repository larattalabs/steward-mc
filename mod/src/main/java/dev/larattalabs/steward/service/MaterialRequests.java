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
	/** When each settlement last told its players about materials (game ticks): at most once a minute (each small delivery can start a new wait). */
	private static final Map<String, Long> TOLD = new HashMap<>();
	static final long TELL_EVERY_TICKS = 1_200;

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
			TOLD.clear();
			BUILDING.clear();
			seeded = false;
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
			try {
				if (!seeded) seedBuilds();
				readBuilds(s);
			} catch (RuntimeException | LinkageError e) {
				Steward.LOGGER.warn("could not read the builds from materials", e);
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
		long now = srv.overworld().getGameTime();
		Long last = TOLD.get(s.get().id());
		if (last != null && now - last < TELL_EVERY_TICKS) return;
		TOLD.put(s.get().id(), now);
		tell(srv, s.get(), "The " + nameOf(s.get(), site) + " waits for materials: " + needs(wait) + ". They go in the stockpile.");
	}

	/** Regions built from materials (Architect RS) whose build isn't done yet, by settlement: said once when done. This world's session only. */
	private static final Map<String, String> BUILDING = new java.util.concurrent.ConcurrentHashMap<>();

	private static boolean seeded;

	/** At a world's start: every settlement's placed region still being built from the stockpile is watched again (said once when done). */
	private static void seedBuilds() {
		for (Settlement s : Settlements.store().all()) {
			for (var e : s.log()) {
				if (e.kind() != Settlement.Kind.PROJECT_PLACED) continue;
				String id = dev.larattalabs.steward.gateway.VPlan.regionOf(e.siteGroup());
				if (id == null) continue;
				try {
					var v = ArchitectApi.get().regions().get(id);
					if (v.isPresent() && v.get().build() != null && !v.get().build().done()) watchBuild(s.id(), id);
				} catch (RuntimeException ex) {
					// one region that can't be read: the others are still watched
					Steward.LOGGER.warn("could not read region {} of {}", id, s.id(), ex);
				}
			}
		}
		seeded = true;
	}

	/** Watches a settlement's region being built from the stockpile: the steward says when it is fully built. */
	public static void watchBuild(String settlementId, String regionId) {
		BUILDING.put(regionId, settlementId);
	}

	/** Says, once, each watched region that is now fully built (its every target built and its bodies settled); drops one that is gone. */
	private static void readBuilds(MinecraftServer srv) {
		for (var e : List.copyOf(BUILDING.entrySet())) {
			var v = ArchitectApi.get().regions().get(e.getKey());
			var s = Settlements.store().get(e.getValue());
			if (v.isEmpty() || s.isEmpty() || v.get().state() == dev.larattalabs.architect.api.RegionState.REMOVING
				|| v.get().state() == dev.larattalabs.architect.api.RegionState.REMOVED) {
				BUILDING.remove(e.getKey());
				continue;
			}
			var b = v.get().build();
			if (b != null && b.done()) {
				BUILDING.remove(e.getKey());
				tell(srv, s.get(), s.get().name() + " is built: everything its plan laid out stands, from the stockpile.");
			}
		}
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
				String what = target.map(v -> nameOf(s, v)).orElse("build");
				tell(srv, s, "The " + what + " has waited a while for materials: " + needs(longest) + ". Fill the stockpile and I can carry on.");
			}
		}
	}

	/** Said by the steward (its bubble) and in the chat of the players in the settlement (who may not be near it); in the log too. */
	private static void tell(MinecraftServer srv, Settlement s, String text) {
		Steward.LOGGER.info("materials {}: {}", s.id(), text);
		StewardVoice.say(srv, s.id(), text);
		for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
			var at = p.blockPosition();
			if (s.contains(p.level().dimension().identifier().toString(), at.getX(), at.getY(), at.getZ())) {
				p.sendSystemMessage(net.minecraft.network.chat.Component.literal("[" + s.name() + "] " + text));
			}
		}
	}

	/** "12 oak planks, 4 glass" (and how many more units beyond the first 8 kinds). */
	static String needs(SiteWait w) {
		List<Map.Entry<net.minecraft.world.item.Item, Integer>> list = w.nextNeeded().stream()
			.map(n -> Map.entry(n.item(), n.count())).toList();
		return Stockpiles.items(list, 4) + (w.moreUnits() > 0 ? " (" + w.moreUnits() + " more besides)" : "");
	}

	private static String nameOf(Settlement s, SiteView v) {
		if (dev.larattalabs.steward.gateway.BatchPlanner.isStreet(v.itemKey())) return "street";
		if ("road".equals(v.kind())) return "road";
		// a region's construction target (Architect RS): its plan part, unless it is a lot (which carries its role)
		String part = v.ext() != null && v.ext().has(PART_EXT) && v.ext().get(PART_EXT).isJsonPrimitive() ? v.ext().get(PART_EXT).getAsString() : null;
		if (part != null && (v.ext() == null || !v.ext().has("steward_mc:role"))) return partName(PlanStore.get(s.id()).map(PlanStore.Entry::plan).orElse(null), part);
		return Updates.lotOf(v);
	}

	/** The ext key naming the plan part a region's construction target builds (Architect RS). */
	static final String PART_EXT = "architect_mc:part";

	/** A plan part in words: its label in the plan ("the town wall"), else its id ("north road"); the route lamps as "street lamps". */
	static String partName(@org.jspecify.annotations.Nullable String planJson, String part) {
		if ("__lamps__".equals(part)) return "street lamps";
		if (planJson != null) {
			try {
				var plan = com.google.gson.JsonParser.parseString(planJson).getAsJsonObject();
				for (var e : plan.entrySet()) {
					if (!e.getValue().isJsonArray()) continue;
					for (var p : e.getValue().getAsJsonArray()) {
						if (p.isJsonObject() && p.getAsJsonObject().has("id") && part.equals(p.getAsJsonObject().get("id").getAsString()) && p.getAsJsonObject().has("label")
							&& p.getAsJsonObject().get("label").isJsonPrimitive()) return p.getAsJsonObject().get("label").getAsString();
					}
				}
			} catch (RuntimeException e) {
				// an unreadable plan: the part's id
			}
		}
		return part.replace('_', ' ');
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
