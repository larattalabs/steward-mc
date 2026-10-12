package dev.larattalabs.steward.service;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.Mode;
import dev.larattalabs.architect.api.RemoveOptions;
import dev.larattalabs.architect.api.RoadRequest;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.BatchPlanner;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.DistrictRules;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * A settlement's districts in the world (docs/PLAN.md phase 3f): founding one (its square and its road, $0), linking its street to that road after its first
 * build, dropping it once an undo took its roads and nothing else stands in it, and putting right what an interrupted founding or link left behind
 * ({@link #reconcile}). Server thread.
 *
 * <p>A road is saved in the settlement only once Architect has placed it. A road placed but never saved (the world closed, the save failed and the removal
 * too) carries its role in its ext, and {@link #reconcile} takes it up again when the world next starts.
 */
public final class Districts {
	/** Settlements whose district road is being laid (founding or linking): they count as busy ({@link SettlementRunner#busy}). */
	private static final Set<String> LAYING = new HashSet<>();
	/** Waypoints along a road's legs at most this far apart (each carries its own ground height). */
	static final int STEP = 8;
	/** The roles a district's roads carry in their ext: {@code steward_mc:infra} marks them as the steward's roads (a building's role is free text). */
	static final String FOUNDING_ROLE = "district road", LINK_ROLE = "district link", INFRA = "steward_mc:infra";

	private Districts() {
	}

	public static void init() {
		// laying is one world's: a road whose callback never came is reconciled at the next start
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> LAYING.clear());
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			for (Settlement s : Settlements.store().all()) {
				try {
					reconcile(server, s.id(), m -> Steward.LOGGER.info("{}: {}", s.id(), m));
				} catch (RuntimeException e) {
					Steward.LOGGER.warn("reconciling the districts of {} failed", s.id(), e);
				}
			}
		});
	}

	/** A district road of the settlement is being laid. */
	public static boolean busy(String settlementId) {
		return LAYING.contains(settlementId);
	}

	/**
	 * Founds a district towards {@code dir} ("N", "E", "S", "W"): places it, lays its road, then saves it and logs the founding (the road its site). {@code say}
	 * gets the outcome. Returns null when it started, else why not.
	 */
	public static @Nullable String found(MinecraftServer server, @Nullable ServerPlayer actor, Settlement s, String dir, String size, String name, Consumer<String> say) {
		return found(server, actor, s, dir, size, name, say, () -> {
		});
	}

	/** As above; {@code onFounded} runs once the district is saved. */
	public static @Nullable String found(MinecraftServer server, @Nullable ServerPlayer actor, Settlement s, String dir, String size, String name, Consumer<String> say,
		Runnable onFounded) {
		if (LAYING.contains(s.id())) return "A road of " + s.name() + " is being laid; try again in a moment.";
		if (SettlementRunner.busy(s.id())) return s.name() + " is busy (a build or an undo); found a district once that is done.";
		// in a survival world nothing is built free: the road is built from the settlement's stockpile (Architect C24), made first when there is none
		if (dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false) && Stockpiles.ensure(server, s, say) == null) {
			return "A district's road in a survival world is built from " + s.name() + "'s stockpile, and none could be made.";
		}
		ServerLevel level = level(server, s.claim().dimension());
		if (level == null) return "The dimension of " + s.id() + " is not loaded.";
		var p = DistrictRules.place(s.claim(), townStreet(server, s), s.lands().districts(), Settlements.others(s.id()), s.lands().protect(), dir,
			DistrictRules.radiusFor(size));
		if (!p.ok()) return p.note();
		String over = overBuilding(server, s, p.road());
		if (over != null) return "The road to the " + DistrictRules.directionName(dir) + " would run over " + over + ". Try another way.";
		List<BlockPos> points = waypoints(level, p.road());
		if (points == null) return "The road's way is not loaded: stand nearer the " + DistrictRules.directionName(dir) + " edge of " + s.name() + " and try again.";
		String label = name == null || name.isBlank() ? "the " + DistrictRules.directionName(dir) + " district" : name.strip();
		RoadRequest req = road(level, points, s, FOUNDING_ROLE, actor);
		var sites = ArchitectApi.get().sites(server);
		var check = sites.checkRoad(req);
		if (!check.ok()) return "The road to " + label + " cannot be laid: " + reasons(check.refusals());
		int session = Session.current();
		LAYING.add(s.id());
		sites.placeRoad(req).whenComplete((r, err) -> {
			// the world closed meanwhile: a placed road is taken up when it next starts (reconcile)
			if (!Session.is(session)) return;
			if (err != null || !r.placed()) {
				LAYING.remove(s.id());
				say.accept("The road to " + label + " was refused: " + (err != null ? message(err) : reasons(r.refusals())));
				return;
			}
			String siteId = r.siteId().orElse(null);
			if (siteId == null) {
				LAYING.remove(s.id());
				say.accept("The road to " + label + " was laid without a site id; nothing was founded.");
				return;
			}
			var road = new Settlement.Road(siteId, p.road(), DistrictRules.ROAD_WIDTH, false);
			var res = Settlements.addDistrict(s.id(), id -> new Settlement.District(id, label, dir, p.claim(), System.currentTimeMillis(), List.of(road)),
				"Founded " + label + " (" + DistrictRules.directionName(dir) + ", " + (p.claim().radius() * 2 + 1) + " x " + (p.claim().radius() * 2 + 1) + ") and its road",
				System.currentTimeMillis());
			if (!res.ok()) {
				// not saved: the road goes again, so the world holds nothing the settlement does not know of (the settlement stays busy until it has)
				takeUp(server, s, siteId, "Could not found " + label + " (" + res.error() + ")", say, () -> LAYING.remove(s.id()));
				return;
			}
			LAYING.remove(s.id());
			onFounded.run();
			say.accept("Founded " + label + ", linked to the town by a road. Its first building lays its street." + gaps(r));
		});
		return null;
	}

	/**
	 * Puts right what an interrupted founding or link left: the settlement's district roads that no district records are taken up, and a district whose
	 * street stands without its link road gets one. Nothing while the settlement is busy. Server thread.
	 */
	public static void reconcile(MinecraftServer server, String settlementId, Consumer<String> say) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty() || SettlementRunner.busy(settlementId)) return;
		Set<String> known = new HashSet<>();
		for (Settlement.District d : s.get().lands().districts()) d.roads().forEach(r -> known.add(r.siteId()));
		List<String> orphans = new ArrayList<>();
		for (SiteView v : ArchitectApi.get().sites(server).list(s.get().owner())) {
			// only the steward's own district roads: a road site marked as one (never a building, whatever its role says)
			String infra = v.ext() != null && v.ext().has(INFRA) && v.ext().get(INFRA).isJsonPrimitive() ? v.ext().get(INFRA).getAsString() : "";
			if ("road".equals(v.kind()) && (FOUNDING_ROLE.equals(infra) || LINK_ROLE.equals(infra)) && !known.contains(v.id())) orphans.add(v.id());
		}
		if (!orphans.isEmpty()) {
			// one at a time, the settlement busy meanwhile; links wait for the next pass
			LAYING.add(settlementId);
			takeUpAll(server, s.get(), orphans, say, () -> LAYING.remove(settlementId));
			return;
		}
		for (Settlement.District d : s.get().lands().districts()) {
			if (!d.linkUndone() && !d.linked() && d.founding().isPresent() && districtStreet(server, s.get(), d) != null) link(server, settlementId, d.id(), say, false);
		}
	}

	private static void takeUpAll(MinecraftServer server, Settlement s, List<String> ids, Consumer<String> say, Runnable done) {
		if (ids.isEmpty()) {
			done.run();
			return;
		}
		takeUp(server, s, ids.get(0), "A road (" + ids.get(0) + ") was laid but never recorded", say, () -> takeUpAll(server, s, ids.subList(1, ids.size()), say, done));
	}

	/** Removes a road the settlement does not record, says how that went, then runs {@code done}. */
	private static void takeUp(MinecraftServer server, Settlement s, String siteId, String why, Consumer<String> say, Runnable done) {
		int session = Session.current();
		ArchitectApi.get().sites(server).remove(siteId, new RemoveOptions(false, s.owner())).whenComplete((rr, e) -> {
			if (!Session.is(session)) return;
			if (e == null && rr.removed()) say.accept(why + ": the road was taken up again.");
			else say.accept(why + ", and the road " + siteId + " could not be taken up (" + (e != null ? message(e) : String.join(", ", rr.blockers()))
				+ "). It is tried again later.");
			done.run();
		});
	}

	/**
	 * After a district's first build laid its street: a road from where the founding road ended to that street (logged, undone like a project). Nothing when
	 * the district is linked already, has no founding road or no street.
	 */
	static void link(MinecraftServer server, String settlementId, String districtId, Consumer<String> say, boolean afterBuild) {
		if (LAYING.contains(settlementId)) return;
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return;
		var found = s.get().lands().district(districtId);
		// a link the player undid is laid again only by a new build in the district; that build clears the mark first, so a link it cannot lay now (its way not
		// loaded) is retried by reconcile
		if (found.isPresent() && found.get().linkUndone() && afterBuild && !found.get().linked()) {
			var res = Settlements.updateDistrict(settlementId, found.get().withRoads(found.get().roads(), false), null);
			if (res.ok()) found = res.settlement().lands().district(districtId);
		}
		var d = found;
		if (d.isEmpty() || d.get().linked() || d.get().founding().isEmpty() || (d.get().linkUndone() && !afterBuild)) return;
		var street = districtStreet(server, s.get(), d.get());
		if (street == null) return;
		var pts = DistrictRules.link(d.get(), street);
		if (pts.isEmpty()) return;
		List<Claim> land = new ArrayList<>(s.get().claims());
		String why = DistrictRules.roadProblem(pts, land, s.get().lands().protect());
		ServerLevel level = level(server, s.get().claim().dimension());
		List<BlockPos> points = level == null ? null : waypoints(level, pts);
		if (why != null || points == null) {
			say.accept("The road to " + d.get().name() + "'s street was not laid: " + (why != null ? why : "its way is not loaded (tried again later)."));
			return;
		}
		var sites = ArchitectApi.get().sites(server);
		int session = Session.current();
		LAYING.add(settlementId);
		sites.placeRoad(road(level, points, s.get(), LINK_ROLE, null)).whenComplete((r, err) -> {
			if (!Session.is(session)) return;
			if (err != null || !r.placed() || r.siteId().isEmpty()) {
				LAYING.remove(settlementId);
				say.accept("The road to " + d.get().name() + "'s street was refused: " + (err != null ? message(err) : reasons(r.refusals())));
				return;
			}
			String siteId = r.siteId().get();
			var now = Settlements.store().get(settlementId).flatMap(x -> x.lands().district(districtId));
			if (now.isEmpty()) {
				takeUp(server, s.get(), siteId, "The district went while its road was laid", say, () -> LAYING.remove(settlementId));
				return;
			}
			var res = Settlements.updateDistrict(settlementId, now.get().withRoad(new Settlement.Road(siteId, pts, DistrictRules.ROAD_WIDTH, true)),
				new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.ROAD_PLACED, "Linked " + now.get().name() + "'s street to its road", List.of(siteId)));
			if (!res.ok()) takeUp(server, s.get(), siteId, "Could not save the road to " + now.get().name() + "'s street", say, () -> LAYING.remove(settlementId));
			else LAYING.remove(settlementId);
		});
	}

	/**
	 * After an undo: roads that are gone leave their districts, and a district whose roads are all gone, with nothing else standing in it, is given up (logged).
	 * Returns what to tell the player ("" when nothing changed).
	 */
	public static String afterUndo(MinecraftServer server, String settlementId) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return "";
		var sites = ArchitectApi.get().sites(server);
		List<String> said = new ArrayList<>();
		for (Settlement.District d : s.get().lands().districts()) {
			List<Settlement.Road> left = d.roads().stream().filter(r -> sites.get(r.siteId()).isPresent()).toList();
			// no road left and nothing standing (its roads may have gone by an earlier undo, its last building by this one): given up
			if (left.isEmpty() && !standsIn(server, s.get(), d)) {
				if (Settlements.removeDistrict(settlementId, d.id(), "Gave up " + d.name() + " (its road was undone)", System.currentTimeMillis()).ok()) {
					said.add(d.name() + " is given up: its land is free again.");
				}
			} else if (left.size() != d.roads().size()) {
				// a link road undone is the player's choice: not laid again by itself
				boolean linkGone = d.roads().stream().anyMatch(r -> r.link() && !left.contains(r));
				Settlements.updateDistrict(settlementId, d.withRoads(left, d.linkUndone() || linkGone), null);
			}
		}
		return String.join(" ", said);
	}

	/** Whether a site of the settlement other than the district's roads stands in it. */
	static boolean standsIn(MinecraftServer server, Settlement s, Settlement.District d) {
		Set<String> roads = new HashSet<>();
		d.roads().forEach(r -> roads.add(r.siteId()));
		for (SiteView v : ArchitectApi.get().sites(server).list(s.owner())) {
			if (roads.contains(v.id())) continue;
			var b = v.box();
			if (d.claim().contains(d.claim().dimension(), (b.minX() + b.maxX()) / 2, d.claim().minY(), (b.minZ() + b.maxZ()) / 2)) return true;
		}
		return false;
	}

	/** How many buildings stand in the town and in each district (oldest first): where the next one goes ({@link dev.larattalabs.steward.model.ProposalRules#where}). */
	public record Counts(int town, java.util.LinkedHashMap<String, Integer> districts) {}

	public static Counts counts(MinecraftServer server, Settlement s) {
		java.util.LinkedHashMap<String, Integer> per = new java.util.LinkedHashMap<>();
		for (Settlement.District d : s.lands().districts()) per.put(d.id(), 0);
		int town = 0;
		for (SiteView v : ArchitectApi.get().sites(server).list(s.owner())) {
			if (!"building".equals(v.kind()) || BatchPlanner.isStreet(v.itemKey())) continue;
			var b = v.box();
			var in = s.districtAt(s.claim().dimension(), (b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2);
			if (in.isPresent()) per.merge(in.get().id(), 1, Integer::sum);
			else town++;
		}
		return new Counts(town, per);
	}

	/** The first way (N, E, S, W) a district fits towards, or null. */
	public static @Nullable String firstWay(MinecraftServer server, Settlement s, java.util.Collection<String> skip) {
		var street = townStreet(server, s);
		for (String dir : DistrictRules.DIRECTIONS) {
			if (skip.contains(dir)) continue;
			if (DistrictRules.place(s.claim(), street, s.lands().districts(), Settlements.others(s.id()), s.lands().protect(), dir, DistrictRules.RADII[0]).ok()) return dir;
		}
		return null;
	}

	/**
	 * Chooses the side of the town for a district (docs/PLAN.md "Zones", first step): the way said, else where the player stands ({@code standing}), else the
	 * free side whose ground suits {@code site} best (each free side's square is surveyed, loaded chunks only), else today's order. {@code then} gets the
	 * choice on the server thread (its way null when no side fits). Sides in {@code skip} (declined, or waiting already) are not considered.
	 */
	public static void choose(MinecraftServer server, Settlement s, dev.larattalabs.steward.model.DistrictSite site, @Nullable BlockPos standing,
		java.util.Collection<String> skip, Consumer<dev.larattalabs.steward.model.DistrictSite.Choice> then) {
		var street = townStreet(server, s);
		java.util.Map<String, Claim> squares = new java.util.LinkedHashMap<>();
		for (String dir : DistrictRules.DIRECTIONS) {
			if (skip.contains(dir)) continue;
			var p = DistrictRules.place(s.claim(), street, s.lands().districts(), Settlements.others(s.id()), s.lands().protect(), dir, DistrictRules.RADII[0]);
			// a side whose road would run over a building is not one founding could take
			if (p.ok() && overBuilding(server, s, p.road()) == null) squares.put(dir, p.claim());
		}
		List<String> free = List.copyOf(squares.keySet());
		dev.larattalabs.steward.model.DistrictSite.Standing stand = null;
		if (standing != null) {
			java.util.Map<String, Double> dist = new java.util.LinkedHashMap<>();
			for (var e : squares.entrySet()) {
				Claim c = e.getValue();
				int dx = Math.max(0, Math.abs(standing.getX() - c.centerX()) - c.radius()), dz = Math.max(0, Math.abs(standing.getZ() - c.centerZ()) - c.radius());
				dist.put(e.getKey(), Math.hypot(dx, dz));
			}
			stand = new dev.larattalabs.steward.model.DistrictSite.Standing(dist);
		}
		// nothing to survey: the way said, where they stand, or no wish about the ground
		ServerLevel level = level(server, s.claim().dimension());
		if (level == null || free.isEmpty() || stand != null || (site.way() != null && free.contains(site.way())) || !site.caresAboutGround()) {
			then.accept(site.choose(free, java.util.Map.of(), 0, stand));
			return;
		}
		List<String> keys = new ArrayList<>(free);
		keys.add("town");
		List<java.util.concurrent.CompletableFuture<dev.larattalabs.architect.api.Sample>> samples = new ArrayList<>();
		for (String k : keys) {
			Claim c = k.equals("town") ? DistrictRules.villageSquare(s.claim()) : squares.get(k);
			var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(c.centerX() - c.radius(), level.getMinY(), c.centerZ() - c.radius(), c.centerX() + c.radius(),
				level.getMaxY(), c.centerZ() + c.radius());
			samples.add(ArchitectApi.get().survey().sample(level, box, PROFILE_RESOLUTION, dev.larattalabs.architect.api.LoadPolicy.LOADED_ONLY));
		}
		int session = Session.current();
		java.util.concurrent.CompletableFuture.allOf(samples.toArray(new java.util.concurrent.CompletableFuture[0])).whenComplete((v, err) -> server.execute(() -> {
			if (!Session.is(session)) return;
			java.util.Map<String, dev.larattalabs.steward.model.DistrictSite.Profile> profiles = new java.util.LinkedHashMap<>();
			double town = 0;
			for (int i = 0; i < keys.size(); i++) {
				var f = samples.get(i);
				if (f.isCompletedExceptionally()) continue;
				var p = profile(f.join());
				if (keys.get(i).equals("town")) town = p.height();
				else profiles.put(keys.get(i), p);
			}
			if (err != null) Steward.LOGGER.warn("district survey of {}: {}", s.id(), err.toString());
			then.accept(site.choose(free, profiles, town, null));
		}));
	}

	/** The survey's step when sizing up a side for a district (every 4th column: enough to tell water, trees, height and biome). */
	static final int PROFILE_RESOLUTION = 4;

	/** What a surveyed square is like. */
	static dev.larattalabs.steward.model.DistrictSite.Profile profile(dev.larattalabs.architect.api.Sample sm) {
		var b = new dev.larattalabs.steward.model.DistrictSite.ProfileBuilder();
		for (int j = 0; j < sm.depth(); j++) {
			for (int i = 0; i < sm.width(); i++) {
				int k = sm.index(i, j);
				boolean read = !sm.isMissing(i, j) && sm.height()[k] != dev.larattalabs.architect.api.Sample.MISSING;
				int g = read ? (sm.ground() != null && sm.ground()[k] != dev.larattalabs.architect.api.Sample.MISSING ? sm.ground()[k] : sm.height()[k]) : 0;
				b.add(read, read && (sm.water().get(k) || sm.frozen().get(k)), read && sm.tree().get(k), g, read ? sm.biomeAt(sm.worldX(i), sm.worldZ(j)) : null);
			}
		}
		return b.build();
	}

	/** The town's street (not a district's): its centre line and ends, or null before there is one. */
	public static DistrictRules.@Nullable Street townStreet(MinecraftServer server, Settlement s) {
		return streetOf(ArchitectApi.get().sites(server).list(s.owner()), s, null);
	}

	/** A district's own street, or null before its first build. */
	public static DistrictRules.@Nullable Street districtStreet(MinecraftServer server, Settlement s, Settlement.District d) {
		return streetOf(ArchitectApi.get().sites(server).list(s.owner()), s, d);
	}

	/**
	 * The street of the town ({@code district} null) or of a district: its street sites there (the first laid, and the extensions additions laid along the same
	 * line), as one centre line and its ends. The one rule every caller uses (founding, layouts, links). Null when there is none.
	 */
	public static DistrictRules.@Nullable Street streetOf(List<SiteView> sites, Settlement s, Settlement.@Nullable District district) {
		String dim = s.claim().dimension();
		Integer z = null;
		int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
		for (SiteView v : sites) {
			if (!"road".equals(v.kind()) || !BatchPlanner.isStreet(v.itemKey())) continue;
			var b = v.box();
			// the line it was laid on (its box also holds lanterns); streets laid before it was recorded: the box's middle
			int cz = intExt(v, BatchPlanner.STREET_Z, Math.floorDiv(b.minZ() + b.maxZ(), 2));
			int sx0 = intExt(v, BatchPlanner.STREET_X0, b.minX()), sx1 = intExt(v, BatchPlanner.STREET_X1, b.maxX());
			var in = s.districtAt(dim, (sx0 + sx1) / 2, cz);
			boolean ours = district != null ? in.map(d -> d.id().equals(district.id())).orElse(false) : in.isEmpty();
			if (!ours) continue;
			// the first street found sets the line; another line (a second whole build of the town) is not this street
			if (z == null) z = cz;
			if (cz != z) continue;
			x0 = Math.min(x0, sx0);
			x1 = Math.max(x1, sx1);
		}
		return z == null ? null : new DistrictRules.Street(z, x0, x1);
	}

	private static int intExt(SiteView v, String key, int fallback) {
		return v.ext() != null && v.ext().has(key) && v.ext().get(key).isJsonPrimitive() ? v.ext().get(key).getAsInt() : fallback;
	}

	/** The building (its role) a road along {@code pts} would run over, or null. */
	static @Nullable String overBuilding(MinecraftServer server, Settlement s, List<Settlement.Pt> pts) {
		var cells = DistrictRules.cells(pts, DistrictRules.ROAD_WIDTH);
		for (SiteView v : ArchitectApi.get().sites(server).list(s.owner())) {
			if (!"building".equals(v.kind())) continue;
			var b = v.box();
			for (int[] c : cells) {
				if (c[0] >= b.minX() && c[0] <= b.maxX() && c[1] >= b.minZ() && c[1] <= b.maxZ()) return Updates.lotOf(v);
			}
		}
		return null;
	}

	/** The road's waypoints: its corners, and points between them every {@link #STEP} blocks, each at the ground's height there; null when a chunk is not loaded. */
	static @Nullable List<BlockPos> waypoints(ServerLevel level, List<Settlement.Pt> corners) {
		List<BlockPos> out = new ArrayList<>();
		for (int i = 0; i < corners.size(); i++) {
			Settlement.Pt a = corners.get(i);
			if (i > 0) {
				Settlement.Pt prev = corners.get(i - 1);
				int len = Math.max(Math.abs(a.x() - prev.x()), Math.abs(a.z() - prev.z()));
				for (int k = STEP; k < len; k += STEP) {
					int x = prev.x() + Integer.signum(a.x() - prev.x()) * k, z = prev.z() + Integer.signum(a.z() - prev.z()) * k;
					BlockPos p = ground(level, x, z);
					if (p == null) return null;
					out.add(p);
				}
			}
			BlockPos p = ground(level, a.x(), a.z());
			if (p == null) return null;
			out.add(p);
		}
		return out.size() > 256 ? null : out;
	}

	private static @Nullable BlockPos ground(ServerLevel level, int x, int z) {
		if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) return null;
		return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
	}

	/** A district road: no lanterns (their posts would stand beside the band the rules checked), shallow water decked. */
	private static RoadRequest road(ServerLevel level, List<BlockPos> points, Settlement s, String role, @Nullable ServerPlayer actor) {
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:role", role);
		ext.addProperty(INFRA, role);
		// partial (Architect 0.15): a stretch it cannot lay (too steep, deep water) is left as a gap and said, instead of refusing the whole road
		// in a survival world from materials, fed by the settlement's stockpile (Architect C24); else placed at once
		Mode mode = dev.larattalabs.steward.gateway.WorldMode.survival(level.getServer()).orElse(false) ? Mode.CONSTRUCTION : Mode.INSTANT;
		return new RoadRequest(level, points, DistrictRules.ROAD_WIDTH, null, null, false, true, mode, s.owner(), ext, actor, false).withPartial(true);
	}

	static @Nullable ServerLevel level(MinecraftServer server, String dimension) {
		return server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
			net.minecraft.resources.Identifier.parse(dimension)));
	}

	/** What a partial road left out, for the player ("" when nothing). */
	static String gaps(dev.larattalabs.architect.api.PlaceResult r) {
		if (r.skipped().isEmpty()) return "";
		return " The road has " + (r.skipped().size() == 1 ? "a gap" : r.skipped().size() + " gaps") + " where the ground would not take it (" + String.join("; ",
			r.skipped().stream().limit(3).map(String::valueOf).toList()) + ").";
	}

	private static String reasons(List<dev.larattalabs.architect.api.Refusal> refusals) {
		return refusals.isEmpty() ? "no reason given" : String.join("; ", refusals.stream().map(x -> x.message()).toList());
	}

	private static String message(Throwable err) {
		Steward.LOGGER.warn("district road failed", err);
		return err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
	}
}
