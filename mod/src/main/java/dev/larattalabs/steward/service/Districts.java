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
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * A settlement's districts in the world (docs/PLAN.md phase 3f): founding one (its square and its road, $0), linking its street to that road after its first
 * build, and dropping it once an undo took its road and nothing else stands in it. Server thread.
 */
public final class Districts {
	/** Settlements whose district is being founded (its road is being laid). */
	private static final Set<String> FOUNDING = new HashSet<>();
	/** Waypoints along a road's legs at most this far apart (each carries its own ground height). */
	static final int STEP = 8;

	private Districts() {
	}

	public static boolean founding(String settlementId) {
		return FOUNDING.contains(settlementId);
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
		if (SettlementRunner.busy(s.id())) return s.name() + " is being built; found a district once that is done.";
		if (FOUNDING.contains(s.id())) return "A district of " + s.name() + " is already being founded.";
		ServerLevel level = level(server, s.claim().dimension());
		if (level == null) return "The dimension of " + s.id() + " is not loaded.";
		var p = DistrictRules.place(s.claim(), townStreet(server, s), s.lands().districts(), Settlements.others(s.id()), s.lands().protect(), dir,
			DistrictRules.radiusFor(size));
		if (!p.ok()) return p.note();
		List<BlockPos> points = waypoints(level, p.road());
		if (points == null) return "The road's way is not loaded: stand nearer the " + DistrictRules.directionName(dir) + " edge of " + s.name() + " and try again.";
		String label = name == null || name.isBlank() ? "the " + DistrictRules.directionName(dir) + " district" : name.strip();
		RoadRequest req = road(level, points, s, "district road", actor);
		var sites = ArchitectApi.get().sites(server);
		var check = sites.checkRoad(req);
		if (!check.ok()) return "The road to " + label + " cannot be laid: " + reasons(check.refusals());
		FOUNDING.add(s.id());
		sites.placeRoad(req).whenComplete((r, err) -> {
			FOUNDING.remove(s.id());
			if (err != null || !r.placed()) {
				say.accept("The road to " + label + " was refused: " + (err != null ? message(err) : reasons(r.refusals())));
				return;
			}
			String siteId = r.siteId().orElse(null);
			if (siteId == null) {
				say.accept("The road to " + label + " was laid without a site id; nothing was founded.");
				return;
			}
			var road = new Settlement.Road(siteId, p.road(), DistrictRules.ROAD_WIDTH);
			var res = Settlements.addDistrict(s.id(), id -> new Settlement.District(id, label, dir, p.claim(), System.currentTimeMillis(), List.of(road)),
				"Founded " + label + " (" + DistrictRules.directionName(dir) + ", " + (p.claim().radius() * 2 + 1) + " x " + (p.claim().radius() * 2 + 1) + ") and its road",
				System.currentTimeMillis());
			if (!res.ok()) {
				// not saved: the road goes again, so the world holds nothing the settlement does not know of
				sites.remove(siteId, new RemoveOptions(false, s.owner()));
				say.accept("Could not found " + label + ": " + res.error());
				return;
			}
			onFounded.run();
			say.accept("Founded " + label + ", linked to the town by a road. Its first building lays its street.");
		});
		return null;
	}

	/**
	 * After a district's first build laid its street: a road from where the founding road ended to that street (logged, undone like a project). Nothing
	 * when the district has its link already or no street.
	 */
	public static void link(MinecraftServer server, String settlementId, String districtId, Consumer<String> say) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return;
		var d = s.get().lands().district(districtId);
		if (d.isEmpty() || d.get().roads().size() != 1) return;
		var street = districtStreet(server, s.get(), d.get());
		if (street == null) return;
		var pts = DistrictRules.link(d.get(), street);
		if (pts.isEmpty()) return;
		List<Claim> land = new ArrayList<>(s.get().claims());
		String why = DistrictRules.roadProblem(pts, land, s.get().lands().protect());
		ServerLevel level = level(server, s.get().claim().dimension());
		List<BlockPos> points = level == null ? null : waypoints(level, pts);
		if (why != null || points == null) {
			say.accept("The road to " + d.get().name() + "'s street was not laid: " + (why != null ? why : "its way is not loaded."));
			return;
		}
		var sites = ArchitectApi.get().sites(server);
		sites.placeRoad(road(level, points, s.get(), "district link", null)).whenComplete((r, err) -> {
			if (err != null || !r.placed() || r.siteId().isEmpty()) {
				say.accept("The road to " + d.get().name() + "'s street was refused: " + (err != null ? message(err) : reasons(r.refusals())));
				return;
			}
			String siteId = r.siteId().get();
			var now = Settlements.store().get(settlementId).flatMap(x -> x.lands().district(districtId));
			if (now.isEmpty()) {
				sites.remove(siteId, new RemoveOptions(false, s.get().owner()));
				return;
			}
			var res = Settlements.updateDistrict(settlementId, now.get().withRoad(new Settlement.Road(siteId, pts, DistrictRules.ROAD_WIDTH)),
				new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.ROAD_PLACED, "Linked " + now.get().name() + "'s street to its road", List.of(siteId)));
			if (!res.ok()) {
				sites.remove(siteId, new RemoveOptions(false, s.get().owner()));
				say.accept("Could not save the road to " + now.get().name() + "'s street, so it was taken up again.");
			}
		});
	}

	/**
	 * After an undo: roads that are gone leave their districts, and a district whose founding road is gone, with nothing else standing in it, is dropped
	 * (logged). Returns what to tell the player ("" when nothing changed).
	 */
	public static String afterUndo(MinecraftServer server, String settlementId) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return "";
		var sites = ArchitectApi.get().sites(server);
		List<String> said = new ArrayList<>();
		for (Settlement.District d : s.get().lands().districts()) {
			boolean foundingGone = d.roads().isEmpty() || sites.get(d.roads().get(0).siteId()).isEmpty();
			List<Settlement.Road> left = d.roads().stream().filter(r -> sites.get(r.siteId()).isPresent()).toList();
			if (foundingGone && !standsIn(server, s.get(), d)) {
				if (Settlements.removeDistrict(settlementId, d.id(), "Gave up " + d.name() + " (its road was undone)", System.currentTimeMillis()).ok()) {
					said.add(d.name() + " is given up: its land is free again.");
				}
			} else if (left.size() != d.roads().size() && !foundingGone) {
				Settlements.updateDistrict(settlementId, new Settlement.District(d.id(), d.name(), d.direction(), d.claim(), d.at(), left), null);
			}
		}
		return String.join(" ", said);
	}

	/** How many buildings stand in the town and in each district (oldest first): where the next one goes ({@link dev.larattalabs.steward.model.ProposalRules#where}). */
	public record Counts(int town, java.util.LinkedHashMap<String, Integer> districts) {}

	public static Counts counts(MinecraftServer server, Settlement s) {
		java.util.LinkedHashMap<String, Integer> per = new java.util.LinkedHashMap<>();
		for (Settlement.District d : s.lands().districts()) per.put(d.id(), 0);
		int town = 0;
		for (SiteView v : ArchitectApi.get().sites(server).list(s.owner())) {
			if (!"building".equals(v.kind()) || BatchPlanner.STREET_KEY.equals(v.itemKey())) continue;
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

	/** The town's street (not a district's): its centre line and ends, or null before there is one. */
	public static DistrictRules.@Nullable Street townStreet(MinecraftServer server, Settlement s) {
		for (SiteView v : ArchitectApi.get().sites(server).list(s.owner())) {
			if (!BatchPlanner.STREET_KEY.equals(v.itemKey())) continue;
			var b = v.box();
			if (s.districtAt(s.claim().dimension(), (b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2).isPresent()) continue;
			return new DistrictRules.Street((b.minZ() + b.maxZ()) / 2, b.minX(), b.maxX());
		}
		return null;
	}

	/** A district's own street, or null before its first build. */
	public static DistrictRules.@Nullable Street districtStreet(MinecraftServer server, Settlement s, Settlement.District d) {
		for (SiteView v : ArchitectApi.get().sites(server).list(s.owner())) {
			if (!BatchPlanner.STREET_KEY.equals(v.itemKey())) continue;
			var b = v.box();
			if (!d.claim().contains(d.claim().dimension(), (b.minX() + b.maxX()) / 2, d.claim().minY(), (b.minZ() + b.maxZ()) / 2)) continue;
			return new DistrictRules.Street((b.minZ() + b.maxZ()) / 2, b.minX(), b.maxX());
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

	private static RoadRequest road(ServerLevel level, List<BlockPos> points, Settlement s, String role, @Nullable ServerPlayer actor) {
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:role", role);
		return new RoadRequest(level, points, DistrictRules.ROAD_WIDTH, null, null, true, true, Mode.INSTANT, s.owner(), ext, actor, false);
	}

	static @Nullable ServerLevel level(MinecraftServer server, String dimension) {
		return server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
			net.minecraft.resources.Identifier.parse(dimension)));
	}

	private static String reasons(List<dev.larattalabs.architect.api.Refusal> refusals) {
		return refusals.isEmpty() ? "no reason given" : String.join("; ", refusals.stream().map(x -> x.message()).toList());
	}

	private static String message(Throwable err) {
		Steward.LOGGER.warn("district road failed", err);
		return err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
	}
}
