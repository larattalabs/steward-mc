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
	private static final Map<String, String> SAID = new HashMap<>();
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
			KeepOff.clear();
			seeded = false;
			seedFailed = false;
		});
		try {
			SiteEvents.SITE_WAITING.register(MaterialRequests::onWaiting);
		} catch (LinkageError e) {
			Steward.LOGGER.info("this Architect has no waiting status (it needs 0.18): the steward does not ask for materials");
			return;
		}
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			// the world's builds are watched again on its first tick, not its first read: a fed target can finish within one read's interval
			// (Architect 0.66.0 built a 495-cell lot 8 s after the join), and one built before the seed is never said
			// (a seed that failed is tried again at each read, as before)
			if (!seeded && (!seedFailed || s.getTickCount() % EVERY == 0)) {
				try {
					seedBuilds();
				} catch (RuntimeException | LinkageError e) {
					seedFailed = true;
					Steward.LOGGER.warn("could not look for the builds from materials", e);
				}
			}
			if (s.getTickCount() % EVERY != 0) return;
			try {
				readLongWaits(s);
			} catch (RuntimeException | LinkageError e) {
				Steward.LOGGER.warn("could not read the material waits", e);
			}
			try {
				// (slice A2) growths left without a build or an entry, once their revision has ended
				if (SettlementRunner.canSave()) VillageSweep.growths(s);
			} catch (RuntimeException | LinkageError e) {
				Steward.LOGGER.warn("could not look for growths without a build", e);
			}
			try {
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
		// (slice 8aS, Architect T1b) held by another change (an open transaction): not short of materials, and it carries on by itself
		if (srv == null || !wait.waiting() || wait.nextNeeded().isEmpty() && !held(wait)) return;
		var s = settlementOf(site.owner());
		if (s.isEmpty()) return;
		// once per wait and per reason (QD: a shortage that becomes a bill_cap wait keeps its waitingSince, and is said again)
		String key = saidKey(wait);
		if (key.equals(SAID.get(wait.targetId()))) return;
		long now = srv.overworld().getGameTime();
		Long last = TOLD.get(s.get().id());
		// (QD) a bill_cap wait is never held back by the settlement's throttle: more stock would not help, and the player must hear why
		if (!capped(wait) && last != null && now - last < TELL_EVERY_TICKS) return;
		SAID.put(wait.targetId(), key);
		TOLD.put(s.get().id(), now);
		// (QD) held by the approved bound, not by the stockpile: more materials would not help
		if (held(wait)) tell(srv, s.get(), heldWords(nameOf(s.get(), site)));
		else if (capped(wait)) tell(srv, s.get(), overCap(nameOf(s.get(), site), s.get().id(), wait, takeUp(s.get(), regionOf(site), ridOf(site))));
		else tell(srv, s.get(), "The " + nameOf(s.get(), site) + " waits for materials: " + needs(wait) + ". They go in the stockpile.");
	}

	/** Regions built from materials (Architect RS) whose build isn't done yet, by settlement: said once when done. This world's session only. */
	private static final Map<String, String> BUILDING = new java.util.concurrent.ConcurrentHashMap<>();

	private static boolean seeded;
	private static boolean seedFailed;

	/** At a world's start: every settlement's placed region still being built from the stockpile is watched again (said once when done). */
	private static void seedBuilds() {
		for (Settlement s : Settlements.store().all()) {
			for (var e : s.log()) {
				// (slice SST) an adopted survival town's receipt names its region too: a lot it adopted still building is watched again, said once when built
				if (!seedsWatch(e)) continue;
				String id = regionOfEntry(e.siteGroup());
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

	/** The region a placed entry's tag names: a town's ({@code region:<id>}) or a growth's ({@code revision:<id>:<rid>}, slice A2); else null. Pure. */
	/** Whether a history entry names a region the world-start seed watches: a placed build's, or (slice SST) an adoption's receipt ({@code region:}). */
	static boolean seedsWatch(Settlement.LogEntry e) {
		if (e.kind() == Settlement.Kind.PROJECT_PLACED) return true;
		return e.kind() == Settlement.Kind.TOWN_ADOPTED && e.siteGroup() != null && e.siteGroup().startsWith(dev.larattalabs.steward.gateway.VPlan.REGION_PREFIX);
	}

	static @org.jspecify.annotations.Nullable String regionOfEntry(@org.jspecify.annotations.Nullable String siteGroup) {
		String id = dev.larattalabs.steward.gateway.VPlan.regionOf(siteGroup);
		return id != null ? id : Growths.parse(siteGroup).map(Growths.Ref::region).orElse(null);
	}

	/** (slice 8aS) The regions of {@code settlementId} being built from the stockpile now (watched until fully built). */
	public static List<String> buildingRegions(String settlementId) {
		return BUILDING.entrySet().stream().filter(e -> settlementId.equals(e.getValue())).map(Map.Entry::getKey).sorted().toList();
	}

	/** Watches a settlement's region being built from the stockpile: the steward says when it is fully built. */
	public static void watchBuild(String settlementId, String regionId) {
		BUILDING.put(regionId, settlementId);
	}

	/**
	 * (slice A2L) After a growth of the region is taken up: the watch is dropped, unsaid, when the town reads fully built again (only the growth kept it
	 * unbuilt); one with other construction still unfinished stays watched.
	 */
	public static void forgetIfBuilt(String regionId) {
		if (!BUILDING.containsKey(regionId)) return;
		try {
			var v = ArchitectApi.get().regions().get(regionId);
			if (v.isEmpty() || v.get().build() == null || v.get().build().built()) BUILDING.remove(regionId);
		} catch (RuntimeException | LinkageError e) {
			// read again by the watch itself
		}
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
			// (slice A2L review 6) a growth being taken up (Steward's undo or cancel, Architect's busy, or a take-up Architect finished that the sweep has
			// not logged yet): nothing said, so a town that only lost its addition is never announced built
			if (Undo.busy(s.get().id()) || (v.get().busy() != null && !v.get().busy().isBlank()) || VillageSweep.reconcilePending(s.get(), v.get())) continue;
			var b = v.get().build();
			// done: built and the region PLACED; a town that stood PARTIAL before its growth reads PARTIAL again, so built is enough there (slice A2)
			if (b != null && (b.done() || (b.built() && v.get().state() == dev.larattalabs.architect.api.RegionState.PARTIAL))) {
				BUILDING.remove(e.getKey());
				tell(srv, s.get(), builtName(s.get(), e.getKey()) + " is built: everything its plan laid out stands, from the stockpile.");
			}
		}
	}

	/** (slice SD) Who a watched region's "is built" names: the district whose region it is, else the settlement (its town). Pure. */
	static String builtName(Settlement s, String regionId) {
		return s.lands().districts().stream().filter(d -> regionId.equals(d.region())).map(Settlement.District::name).findFirst().orElse(s.name());
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
				// (slice 8aS) a target another change holds is not waiting for materials: the steward never goes to fetch a player for it
				if (w != null && w.waiting() && !held(w) && (longest == null || w.waitingSince() < longest.waitingSince())) longest = w;
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
				if (capped(longest)) tell(srv, s, overCap(what, s.id(), longest, takeUp(s, target.map(MaterialRequests::regionOf).orElse(null), target.map(MaterialRequests::ridOf).orElse(0))));
				else tell(srv, s, "The " + what + " has waited a while for materials: " + needs(longest) + ". Fill the stockpile and I can carry on.");
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

	/** (QD) What makes a wait said once: when it began and why (a bill_cap wait said once per over-count). Pure. */
	static String saidKey(SiteWait w) {
		if (!capped(w)) return w.waitingSince() + "|" + (w.blocked() == null ? "" : w.blocked());
		return w.waitingSince() + "|" + w.blocked() + "|" + w.nextNeeded().stream().map(n -> n.item() + "=" + n.count()).collect(java.util.stream.Collectors.joining(","))
			+ "|" + w.moreUnits();
	}

	/** (slice 8aS, Architect T1b, API 1.68.0) Whether a target is held by an open transaction ({@code txn_held}): the feed skips it for a moment. */
	static boolean held(SiteWait w) {
		return SiteWait.TXN_HELD.equals(w.blocked());
	}

	/** (slice 8aS) A target held by another change in words: one short line, nothing for the player to do. Pure. */
	static String heldWords(String what) {
		return "The " + what + " is held by another change for a moment: it carries on by itself.";
	}

	/** (QD, S-M5-9) Whether a target waits because its next cell would draw past the bound its realise was approved under ({@code bill_cap}). */
	static boolean capped(SiteWait w) {
		return SiteWait.BILL_CAP.equals(w.blocked());
	}

	/**
	 * (QD) A {@code bill_cap} wait in words: how far over what the player approved it would go (Architect's {@code nextNeeded} names the capped
	 * items and the units over), and that nothing past it is taken. Since Architect v0.73.0 (BC-d) the cap is this growth's own (its revision's,
	 * {@code survival.caps.<rid>}): a later plain growth is never held to it. (BC-c) After an unclean start the reconcile charges what stands even
	 * past the cap; the wait's {@code nextNeeded} is then the need plus that overshoot (what the cap must rise by to finish, so the words say "finishing it would take N more", and promise only that no more is taken, never that nothing past the bound was: the GPT review of pin-073); the API
	 * doesn't say an overshoot apart. Architect can't raise a standing build's cap, so the way on is to take the growth up
	 * and ask for it again, which quotes and asks afresh. Never automatic. Which command takes it up depends on where its build stands (QD-2, the
	 * GPT review of pin-073): while the growth's build still runs (PLACING: a later stage waits on the capped one) {@code /steward undo} is refused
	 * (the settlement is busy) and {@code /steward cancel} is the way (A2L: a growth from materials cancelled part way is taken up by Architect's
	 * RU, what it drew refunded); once its build has ended (its sites stand, logged), {@code /steward undo} takes it up the same way (RU,
	 * RecoveryAdapterTest's dg_aSuppliedGrowthStillBuildingIsUndoneAndRefundsWhatWasDrawn). {@code how} says which ({@link TakeUp}).
	 */
	static String overCap(String what, String settlementId, SiteWait w, TakeUp how) {
		var over = w.nextNeeded().stream()
			.map(n -> Map.entry(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(n.item()).toString(), n.count())).toList();
		return overCapWords(what, settlementId, over, w.moreUnits(), how);
	}

	/**
	 * (pin-073 batch review, P1) How a capped growth is taken up now. {@code CANCEL}: its build still runs ({@link SettlementRunner#cancelTakesUp}).
	 * Else {@code /steward undo} is named only when the operation it would take ({@link Undo#headFirst} of {@code lastUndoable}) is this growth's own
	 * ({@link #ownOp}): {@code UNDO}, or {@code UNDO_LATER} while a build or a district's build refuses it for now (no promise: what finishes meanwhile may be newer). A newer operation standing
	 * (a district started while the town was still building) is {@code NEWER}: undo would take that one, never this growth, so no command is named
	 * for it ({@code newer} says what undo would take). {@code NONE}: the growth's own entry isn't in the history.
	 */
	record TakeUp(Way way, @org.jspecify.annotations.Nullable String newer) {
		enum Way { CANCEL, UNDO, UNDO_LATER, NEWER, NONE }

		static final TakeUp CANCEL = new TakeUp(Way.CANCEL, null), UNDO = new TakeUp(Way.UNDO, null), UNDO_LATER = new TakeUp(Way.UNDO_LATER, null),
			NONE = new TakeUp(Way.NONE, null);
	}

	/** {@link TakeUp} for a capped target of {@code region}'s revision {@code rid} (0: its first realise), from the live runner, history and Architect. */
	static TakeUp takeUp(Settlement s, @org.jspecify.annotations.Nullable String region, int rid) {
		if (SettlementRunner.cancelTakesUp(s.id(), region)) return TakeUp.CANCEL;
		Settlement now = Settlements.store().get(s.id()).orElse(s);
		var head = Undo.headFirst(now, now.lastUndoable());
		boolean refusedNow = SettlementRunner.busy(s.id()) || Undo.districtBlocking(now, head).isPresent();
		return takeUp(false, head, region, rid, refusedNow);
	}

	/** {@link #takeUp(Settlement, String, int)} over its readings. Pure. */
	static TakeUp takeUp(boolean cancel, Optional<Settlement.LogEntry> head, @org.jspecify.annotations.Nullable String region, int rid, boolean refusedNow) {
		if (cancel) return TakeUp.CANCEL;
		if (head.isEmpty()) return TakeUp.NONE;
		if (!ownOp(head.get(), region, rid)) return new TakeUp(TakeUp.Way.NEWER, head.get().text());
		return refusedNow ? TakeUp.UNDO_LATER : TakeUp.UNDO;
	}

	/** Whether {@code e} is the operation of {@code region}'s revision {@code rid}: its growth's tag, or (rid 0) the region's own placing entry. Pure. */
	static boolean ownOp(Settlement.LogEntry e, @org.jspecify.annotations.Nullable String region, int rid) {
		if (region == null) return false;
		var g = Growths.parse(e.siteGroup());
		if (g.isPresent()) return g.get().region().equals(region) && g.get().rid() == rid;
		return rid == 0 && region.equals(dev.larattalabs.steward.gateway.VPlan.regionOf(e.siteGroup()));
	}

	/** The revision a target belongs to (Architect's ext {@code architect_mc:revision.rid}, BC-d), else 0 (its region's first realise). */
	static int ridOf(SiteView v) {
		return ridOf(v.ext());
	}

	static int ridOf(com.google.gson.@org.jspecify.annotations.Nullable JsonObject ext) {
		if (ext == null || !(ext.get("architect_mc:revision") instanceof com.google.gson.JsonObject rv)) return 0;
		return rv.get("rid") instanceof com.google.gson.JsonPrimitive r && r.isNumber() ? r.getAsInt() : 0;
	}

	/** (QD-2) The region a target belongs to (Architect's {@code region}, else {@code architect_mc:region} on its ext), or null when it says none. */
	static @org.jspecify.annotations.Nullable String regionOf(SiteView v) {
		if (v.region() != null) return v.region();
		var ext = v.ext();
		return ext != null && ext.get("architect_mc:region") instanceof com.google.gson.JsonPrimitive r && r.isString() ? r.getAsString() : null;
	}

	/** {@link #overCap} over plain item ids (testable without the item registry). */
	static String overCapWords(String what, String settlementId, List<Map.Entry<String, Integer>> over, int moreUnits, TakeUp how) {
		String again = " takes it up and gives back what it drew; then ask for it again and I'll put the new bill to you.";
		String way = switch (how.way()) {
			case CANCEL -> "/steward cancel " + settlementId + again;
			case UNDO -> "/steward undo " + settlementId + again;
			// (fix-check) never a promise: what finishes meanwhile (a district) may be logged after it and be what undo takes then
			case UNDO_LATER -> "Undo can't run while the build under way goes on. Once it has ended, see what undo would take up (the settlement's"
				+ " history): if it is this one, /steward undo " + settlementId + again;
			// undo takes the newest operation first: naming it here would take up something else
			case NEWER -> "An undo now would take up \"" + how.newer() + "\" first, which came after it: to take this one up, undo what came after it,"
				+ " newest first, or leave it as it stands.";
			case NONE -> "Its own entry isn't in the settlement's history, so undo can't take it up; it stays as it stands.";
		};
		return "The " + what + " has stopped: finishing it would take " + dev.larattalabs.steward.module.ModuleBound.overWords(over, moreUnits)
			+ " more than you approved, so I take no more for it; filling the stockpile won't help. " + way;
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
		if (part != null && (v.ext() == null || !v.ext().has("steward_mc:role"))) {
			String plan = PlanStore.get(s.id()).map(PlanStore.Entry::plan).orElse(null);
			// (S-7c-4) a hidden room's entrance is asked for under its host ("market hall"), never its own name
			if (Secrets.of(plan).hides(part)) return hostName(plan, part, s.name());
			return partName(plan, part);
		}
		return Updates.lotOf(v);
	}

	/** The ext key naming the plan part a region's construction target builds (Architect RS). */
	static final String PART_EXT = "architect_mc:part";

	/**
	 * A secret part's host in words (S-7c-4): its host lot's label or role ("market hall"), else {@code fallback} (the settlement's name); never the
	 * part's own name.
	 */
	static String hostName(@org.jspecify.annotations.Nullable String planJson, String part, String fallback) {
		if (planJson == null) return fallback;
		try {
			var plan = com.google.gson.JsonParser.parseString(planJson).getAsJsonObject();
			String id = part.replaceFirst("@\\d+$", "").replaceFirst(":.*$", "").replaceFirst("^f_", "").replaceFirst("_door$", "");
			var form = PendingOffers.partOf(plan, id).orElse(null);
			if (form == null) form = PendingOffers.partOf(plan, part.replaceFirst(":.*$", "")).orElse(null);
			String host = form != null && form.has("host") && form.get("host").isJsonPrimitive() ? form.get("host").getAsString() : null;
			if (host == null) return fallback;
			for (var lot : dev.larattalabs.steward.gateway.PPlan.lots(plan)) {
				if (!host.equals(lot.get("id").getAsString())) continue;
				for (String k : new String[] {"label", "role", "type"}) if (lot.has(k) && lot.get(k).isJsonPrimitive()) return lot.get(k).getAsString().replace('_', ' ');
			}
			var other = PendingOffers.partOf(plan, host).orElse(null);
			if (other != null && other.has("label") && other.get("label").isJsonPrimitive()) return other.get("label").getAsString();
			return host.replace('_', ' ');
		} catch (RuntimeException e) {
			return fallback;
		}
	}

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
