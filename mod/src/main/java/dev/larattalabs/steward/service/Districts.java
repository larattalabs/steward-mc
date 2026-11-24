package dev.larattalabs.steward.service;

import com.google.gson.JsonObject;
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
		// beside a region town (Architect D3): a $0 reservation Architect has checked, built at its first building (docs/DISTRICTS.md section 9)
		var town = besideTown(server, s, dir);
		if (town != null) return reserveBeside(server, s, town, dir, size, name, say, onFounded);
		if ((dir.equals("E") || dir.equals("W")) && regionTownGone(s)) {
			return "There's no town region to build beside: " + s.name() + "'s town was undone. Build the town again first.";
		}
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
		var sites = ArchitectAccess.api().sites(server);
		var check = sites.checkRoad(req);
		if (!check.ok()) return "The road to " + label + " cannot be laid: " + reasons(check.refusals());
		int session = Session.current();
		LAYING.add(s.id());
		sites.placeRoad(req).whenComplete((r, err) -> {
			// the world closed meanwhile: a placed road is taken up when it next starts (reconcile)
			if (!Session.is(session)) return;
			if (err != null || !r.placed()) {
				LAYING.remove(s.id());
				say.accept("The road to " + label + " was refused: " + (err != null ? said(err) : reasons(r.refusals())) + ".");
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
	 * The town region a district towards {@code dir} is reserved beside, or null for a square district: the town is a region (never a district's or a
	 * part's) and the side is east or west (Noah's Q3). In either mode (slice SD): in a world that builds from materials the district's region is built
	 * from the settlement's stockpile at its first building ({@link SettlementRunner}'s planDistrict).
	 */
	static dev.larattalabs.architect.api.@Nullable RegionView besideTown(MinecraftServer server, Settlement s, String dir) {
		if (!dir.equals("E") && !dir.equals("W")) return null;
		try {
			var id = TownRegion.of(s);
			return id.isEmpty() ? null : ArchitectAccess.api().regions().get(id.get()).orElse(null);
		} catch (RuntimeException | LinkageError e) {
			Steward.LOGGER.info("the town region of {} can't be read ({}): a square district", s.id(), e.toString());
			return null;
		}
	}

	/** The ext key naming the district a region was planned and realised for. */
	public static final String DISTRICT_EXT = "steward_mc:district";

	/** A region Architect holds (not removed) that was realised for district {@code districtId} of the settlement {@code owner}. */
	static java.util.Optional<String> liveRegionOf(String owner, String districtId) {
		try {
			return ArchitectAccess.api().regions().list(owner).stream()
				.filter(v -> v.state() != dev.larattalabs.architect.api.RegionState.REMOVED && v.ext().has(DISTRICT_EXT) && v.ext().get(DISTRICT_EXT).isJsonPrimitive()
					&& districtId.equals(v.ext().get(DISTRICT_EXT).getAsString()))
				.map(dev.larattalabs.architect.api.RegionView::id).findFirst();
		} catch (RuntimeException | LinkageError e) {
			// can't tell: the district stays as it is
			return java.util.Optional.of("unknown");
		}
	}

	/**
	 * Whether the town was a region and is gone (undone): a placed region that wasn't a district's, and no town region now. Never a square district then,
	 * which would lay Steward's road to a town that isn't there.
	 */
	static boolean regionTownGone(Settlement s) {
		java.util.Set<String> districts = new java.util.HashSet<>();
		for (Settlement.District d : s.lands().districts()) if (d.region() != null) districts.add(d.region());
		boolean wasRegion = s.log().stream().anyMatch(e -> e.kind() == Settlement.Kind.PROJECT_PLACED && dev.larattalabs.steward.gateway.VPlan.regionOf(e.siteGroup()) != null
			&& !districts.contains(dev.larattalabs.steward.gateway.VPlan.regionOf(e.siteGroup())));
		if (!wasRegion) return false;
		try {
			return TownRegion.of(s).isEmpty();
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** The sides a quarter of {@code q}'s kind doesn't fit (its whole strip, not a square district's): an "auto" choice skips them. */
	public static java.util.Set<String> quarterMisfits(Settlement s, dev.larattalabs.steward.model.Proposal.Quarter q) {
		java.util.Set<String> out = new java.util.HashSet<>(DistrictRules.DIRECTIONS);
		out.removeAll(quarterStrips(s, q).keySet());
		return out;
	}

	/** Each side a quarter of {@code q}'s kind fits, with its strip: what an "auto" choice surveys. None when the town's region can't be read. */
	static java.util.Map<String, dev.larattalabs.steward.model.Land.Rect> quarterStrips(Settlement s, dev.larattalabs.steward.model.Proposal.Quarter q) {
		java.util.Map<String, dev.larattalabs.steward.model.Land.Rect> out = new java.util.LinkedHashMap<>();
		try {
			var town = TownRegion.of(s).flatMap(id -> ArchitectAccess.api().regions().get(id));
			if (town.isEmpty()) return out;
			var b = town.get().claim();
			int depth = "outpost".equals(q.kind()) ? DistrictRules.OUTPOST_DEPTH : DistrictRules.ALONG_DEPTH;
			for (String dir : DistrictRules.DIRECTIONS) {
				var p = DistrictRules.quarter(s.claim(), new int[] {b.minX(), b.minZ(), b.maxX(), b.maxZ()}, s.lands().districts(), Settlements.others(s.id()),
					s.lands().protect(), dir, depth, "quarter");
				if (p.ok() && p.land() != null) out.put(dir, p.land().rect());
			}
		} catch (RuntimeException | LinkageError e) {
			// can't tell: every side refused, so nothing is offered that won't fit
			out.clear();
		}
		return out;
	}

	/** {@link #choose} for a quarter of {@code q}'s kind: only the sides its strip fits, each judged by the ground of its strip. */
	public static void chooseQuarter(MinecraftServer server, Settlement s, dev.larattalabs.steward.model.Proposal.Quarter q, dev.larattalabs.steward.model.DistrictSite site,
		java.util.Collection<String> skip, Consumer<dev.larattalabs.steward.model.DistrictSite.Choice> then) {
		var strips = quarterStrips(s, q);
		strips.keySet().removeAll(skip);
		// a shore quarter only where its strip is by the water: the chooser's fallback (any side that fits) is no answer for one
		boolean shore = "shore".equals(q.boundary());
		choose(server, s, site, null, strips, c -> then.accept(shore && c.way() != null && !c.why().startsWith("by the water")
			? new dev.larattalabs.steward.model.DistrictSite.Choice(null, "has its strip by the water") : c));
	}

	/**
	 * (slice DG) Whether a building can be added to the district now: a square, a reserved beside district (its first building builds its region), or a
	 * standing district region while Architect grows regions by revision ({@code grows}). Pure.
	 */
	static boolean takes(Settlement.District d, boolean grows) {
		return d.takesBuilding() || (grows && grows(d));
	}

	/** (slice DG) A standing district with its own region: one that grows by revision. Pure. */
	static boolean grows(Settlement.District d) {
		return !d.square() && Settlement.District.STANDING.equals(d.state()) && d.region() != null;
	}

	/** As {@link #takes(Settlement.District, boolean)}, with whether this Architect grows regions. */
	static boolean takes(Settlement.District d) {
		boolean grows;
		try {
			grows = ArchitectAccess.api().features().contains(Growths.FEATURE);
		} catch (RuntimeException | LinkageError e) {
			grows = false;
		}
		return takes(d, grows);
	}

	/**
	 * (slice DG) The land a standing district region owns, read again from Architect's {@code land.outside} (which a growth adds to and its undo takes
	 * back): a beside district its reservation (its claim's square, which never changes) and {@code outside}, a quarter exactly {@code outside}; never
	 * another district's columns. Null (the saved land stays): nothing trustworthy read ({@code read} null), or a quarter whose {@code outside} reads
	 * empty (it never owns nothing while its region stands). Pure.
	 */
	static dev.larattalabs.steward.model.@Nullable Land landAfter(Settlement.District d, dev.larattalabs.steward.gateway.DistrictLand.@Nullable Read read,
		List<Settlement.District> all) {
		if (read == null) return null;
		boolean beside = Settlement.District.BESIDE.equals(d.kind());
		if (read.land() == null && !beside) return null;
		var reservation = dev.larattalabs.steward.model.Land.of(d.claim());
		dev.larattalabs.steward.model.@Nullable Land land = beside ? (read.land() == null ? reservation : reservation.union(read.land())) : read.land();
		for (Settlement.District o : all) {
			if (o.id().equals(d.id()) || land == null) continue;
			land = land.minus(o.land());
		}
		return land;
	}

	/**
	 * (slice DG) The district whose own region is {@code region}: its land read again and saved when it differs (idempotent; no history entry). Only from a
	 * settled region (standing, not busy, no revision placing or being taken up), so a half-written growth or undo never moves what the district owns. Run
	 * after a growth's take-up and by the growth sweep (world start, every 10 s), so a crash between a growth's entry or removal and this save is put right.
	 */
	static void refreshLand(String settlementId, String region) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return;
		var d = s.get().lands().districts().stream().filter(x -> grows(x) && region.equals(x.region())).findFirst();
		if (d.isEmpty()) return;
		try {
			var v = ArchitectAccess.api().regions().get(region);
			if (v.isEmpty() || !settled(v.get())) return;
			var read = dev.larattalabs.steward.gateway.DistrictLand.outsideWhole(v.get(), d.get().claim().dimension());
			var land = landAfter(d.get(), read, s.get().lands().districts());
			if (land == null || land.equals(d.get().land())) return;
			var x = d.get();
			var res = Settlements.updateDistrict(settlementId, new Settlement.District(x.id(), x.name(), x.direction(), x.claim(), x.at(), x.roads(), x.linkUndone(),
				x.kind(), x.state(), x.parent(), x.region(), land, x.follow(), x.opKey()), null);
			if (!res.ok()) Steward.LOGGER.warn("{}: the land of district {} could not be saved again (asked again later): {}", settlementId, x.id(), res.error());
			else Steward.LOGGER.info("{}: district {} owns {} columns again, from its region {}", settlementId, x.id(), land.runs().stream().mapToInt(r -> r.x1() - r.x0() + 1).sum(), region);
		} catch (RuntimeException | LinkageError e) {
			Steward.LOGGER.warn("{}: the land of the district of region {} could not be read again", settlementId, region, e);
		}
	}

	/** A region whose land can be read as it is: placed (or partly), not busy, no revision placing or being taken up. */
	static boolean settled(dev.larattalabs.architect.api.RegionView v) {
		if (v.state() == dev.larattalabs.architect.api.RegionState.REMOVED || v.state() == dev.larattalabs.architect.api.RegionState.REMOVING) return false;
		if (v.busy() != null && !v.busy().isBlank()) return false;
		return v.revisions() == null || v.revisions().stream().allMatch(r -> Growths.ended(r.state()) || "UNDONE".equalsIgnoreCase(r.state()) || "STALE".equalsIgnoreCase(r.state()));
	}

	/**
	 * (slice DG) Why a growth of district {@code districtId}'s region {@code region} may not go ahead now, or null. Architect grows a region anywhere in its
	 * claim, around what stands, but Steward's empty land is not Architect's to see: the claim's columns outside the district's own land must hold no
	 * sibling district's land, and those also outside the town's own region claim no other settlement's. Checked before the plan (nothing spent) and again before the realise.
	 */
	static @Nullable String growthConflict(Settlement s, String districtId, @Nullable String region) {
		var d = s.lands().district(districtId);
		if (d.isEmpty() || region == null) return null;
		try {
			var v = ArchitectAccess.api().regions().get(region);
			if (v.isEmpty()) return null;
			var b = v.get().claim();
			var box = dev.larattalabs.steward.model.Land.ofRect(d.get().claim().dimension(), b.minX(), b.minZ(), b.maxX(), b.maxZ());
			List<dev.larattalabs.steward.model.Land> others = new ArrayList<>();
			for (Settlement o : Settlements.store().all()) {
				if (o.id().equals(s.id())) continue;
				others.add(dev.larattalabs.steward.model.Land.of(o.claim()));
				for (Settlement.District od : o.lands().districts()) others.add(od.land());
			}
			// the town's own region claim (Architect's, which an Expand of the settlement's claim never moves), else the settlement's claim
			var parent = d.get().parent() == null ? java.util.Optional.<dev.larattalabs.architect.api.RegionView>empty() : ArchitectAccess.api().regions().get(d.get().parent());
			var town = parent.map(t -> dev.larattalabs.steward.model.Land.ofRect(d.get().claim().dimension(), t.claim().minX(), t.claim().minZ(), t.claim().maxX(),
				t.claim().maxZ())).orElse(dev.larattalabs.steward.model.Land.of(s.claim()));
			return growthConflict(box, town, d.get(), s.lands().districts(), others);
		} catch (RuntimeException | LinkageError e) {
			return d.get().name() + "'s region can't be read now: ask again later.";
		}
	}

	/** Pure: the columns of {@code regionClaim} past the town's claim and the district's own land, against a sibling's land and other settlements'. */
	static @Nullable String growthConflict(dev.larattalabs.steward.model.Land regionClaim, dev.larattalabs.steward.model.Land town, Settlement.District d,
		List<Settlement.District> siblings, List<dev.larattalabs.steward.model.Land> otherSettlements) {
		// a sibling's land is checked over the whole claim past the district's own land, never exempted as town land (an Expand can take a district into
		// the settlement's claim; review round 2)
		var past = regionClaim.minus(d.land());
		if (past == null) return null;
		for (Settlement.District o : siblings) {
			if (o.id().equals(d.id())) continue;
			if (past.overlaps(o.land())) return d.name() + " can't grow now: its region's ground reaches " + o.name() + "'s land.";
		}
		// another settlement's: past the town's own region claim too
		var free = past.minus(town);
		if (free == null) return null;
		for (var l : otherSettlements) if (free.overlaps(l)) return d.name() + " can't grow now: its region's ground reaches another settlement's land.";
		return null;
	}

	/** A district region's build that ended with nothing placed: a beside district is only reserved again; a quarter, founded for it, is given up. */
	static void release(String settlementId, Settlement.District d) {
		if (Settlement.District.BESIDE.equals(d.kind())) Settlements.districtState(settlementId, d.id(), Settlement.District.RESERVED);
		else Settlements.removeDistrict(settlementId, d.id(), "Gave up " + d.name() + " (its build placed nothing)", System.currentTimeMillis());
	}

	/** The district's key stem: its plan and realise keys are {@code <stem>.dplan} and {@code <stem>.drealise}. */
	static String opKeyStem(Settlement s, String districtId) {
		return "steward." + s.id() + "." + districtId;
	}

	/** The plan request of a district beside the town: the districts program with {@code lots} in one district, in the reservation's request claim. */
	static dev.larattalabs.architect.api.RegionPlanRequest besideRequest(ServerLevel level, Settlement s, String town, int[] request, String districtId,
		List<JsonObject> lots, @Nullable String opKey) {
		return besideRequest(level, s, town, request, districtId, lots, opKey, null);
	}

	/** As above, tagged with the build that plans it (a restart finds its region by the tag when its key is lost). */
	static dev.larattalabs.architect.api.RegionPlanRequest besideRequest(ServerLevel level, Settlement s, String town, int[] request, String districtId,
		List<JsonObject> lots, @Nullable String opKey, @Nullable String buildId) {
		return besideRequest(level, s, town, request, districtId, lots, opKey, buildId, Settlement.District.BESIDE, null);
	}

	/**
	 * The plan request of a district of {@code kind} beside the town (D1-D3's {@code districts} program): a beside district on a survey candidate, an
	 * {@code along} one following {@code follow}'s boundary ({@code size} [length, depth], D2), an {@code outpost} apart by its gap (D2).
	 */
	static dev.larattalabs.architect.api.RegionPlanRequest besideRequest(ServerLevel level, Settlement s, String town, int[] request, String districtId,
		List<JsonObject> lots, @Nullable String opKey, @Nullable String buildId, String kind, Settlement.District.@Nullable Follow follow) {
		JsonObject district = new JsonObject();
		district.addProperty("id", districtId);
		com.google.gson.JsonArray size = new com.google.gson.JsonArray();
		if (Settlement.District.ALONG.equals(kind) && follow != null) {
			// D2's default band, 96 along the boundary and 24 deep
			size.add(Math.min(96, Math.max(request[2] - request[0], request[3] - request[1]) + 1));
			size.add(24);
			JsonObject which = new JsonObject();
			if (!follow.between().isEmpty()) {
				com.google.gson.JsonArray between = new com.google.gson.JsonArray();
				follow.between().forEach(between::add);
				which.add("between", between);
			}
			JsonObject f = new JsonObject();
			f.add(follow.boundary(), which);
			district.add("follow", f);
			if (follow.side() != null) district.addProperty("side", follow.side());
		} else {
			size.add(Math.min(48, request[2] - request[0] + 1));
			size.add(Math.min(48, request[3] - request[1] + 1));
			if (Settlement.District.OUTPOST.equals(kind)) {
				JsonObject o = new JsonObject();
				o.addProperty("gap", 24);
				district.add("outpost", o);
			}
		}
		district.add("size", size);
		com.google.gson.JsonArray ls = new com.google.gson.JsonArray();
		lots.forEach(ls::add);
		district.add("lots", ls);
		com.google.gson.JsonArray districts = new com.google.gson.JsonArray();
		districts.add(district);
		JsonObject spec = new JsonObject();
		spec.add("districts", districts);
		JsonObject params = new JsonObject();
		params.add("spec", spec);
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty(DISTRICT_EXT, districtId);
		if (buildId != null) ext.addProperty(BatchPlanner.BUILD_EXT, buildId);
		var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(request[0], s.claim().minY(), request[1], request[2], s.claim().maxY(), request[3]);
		return new dev.larattalabs.architect.api.RegionPlanRequest("districts", params, level, box, null, null, null,
			dev.larattalabs.architect.api.LoadPolicy.LOADED_ONLY, s.owner(), ext, opKey, town);
	}

	/** One placeholder lot for the founding's dry check: Architect needs a lot to place a district; the real first building plans again with its own. */
	static JsonObject placeholderLot(Settlement s) {
		JsonObject lot = new JsonObject();
		lot.addProperty("id", "reserve_1");
		lot.addProperty("type", "house");
		lot.addProperty("role", "house");
		com.google.gson.JsonArray size = new com.google.gson.JsonArray();
		size.add(9);
		size.add(9);
		lot.add("size", size);
		lot.addProperty("landmark", false);
		return lot;
	}

	private static @Nullable String reserveBeside(MinecraftServer server, Settlement s, dev.larattalabs.architect.api.RegionView town, String dir, String size,
		String name, Consumer<String> say, Runnable onFounded) {
		ServerLevel level = level(server, s.claim().dimension());
		if (level == null) return "The dimension of " + s.id() + " is not loaded.";
		String label = name == null || name.isBlank() ? "the " + DistrictRules.directionName(dir) + " district" : name.strip();
		var b = town.claim();
		var r = DistrictRules.reserve(s.claim(), new int[] {b.minX(), b.minZ(), b.maxX(), b.maxZ()}, s.lands().districts(), Settlements.others(s.id()),
			s.lands().protect(), dir, DistrictRules.radiusFor(size), label);
		if (!r.ok()) return r.note();
		var req = besideRequest(level, s, town.id(), r.request(), "reserve", List.of(placeholderLot(s)), null);
		int session = Session.current();
		LAYING.add(s.id());
		say.accept("Asking Architect whether " + label + " can join the town's street...");
		java.util.concurrent.CompletableFuture<dev.larattalabs.architect.api.BesideVerdict> check;
		try {
			check = ArchitectAccess.api().regions().checkBeside(req);
		} catch (RuntimeException | LinkageError e) {
			LAYING.remove(s.id());
			Steward.LOGGER.warn("beside check for {} could not be asked", s.id(), e);
			return "Architect can't check a district beside the town here (" + RefusalWords.of(e) + ").";
		}
		check.whenComplete((v, err) -> server.execute(() -> {
			if (!Session.is(session)) return;
			LAYING.remove(s.id());
			if (err != null) {
				say.accept("Could not reserve " + label + ": " + said(err) + ".");
				return;
			}
			if (!v.fits()) {
				Steward.LOGGER.info("beside check for {} {}: {} {} {}", s.id(), dir, v.reason(), v.code(), v.message());
				say.accept("Could not reserve " + label + ": " + besideRefusal(v.code(), v.message(), v.reason()));
				return;
			}
			Claim c = r.claim();
			var res = Settlements.addReservation(s.id(), id -> new Settlement.District(id, label, dir, c, System.currentTimeMillis(), List.of(), false,
				Settlement.District.BESIDE, Settlement.District.RESERVED, town.id(), null, dev.larattalabs.steward.model.Land.of(c), null, opKeyStem(s, id)),
				"Reserved " + label + " (" + DistrictRules.directionName(dir) + ", " + (c.radius() * 2 + 1) + " x " + (c.radius() * 2 + 1) + ")",
				System.currentTimeMillis());
			if (!res.ok()) {
				say.accept("Could not reserve " + label + ": " + res.error());
				return;
			}
			onFounded.run();
			say.accept("Reserved " + label + ". Its first building builds its street and its road to the town" + (v.join() != null && "side".equals(v.join().kind())
				? " (joining the street's side)" : "") + ". Nothing is built yet, and undoing this gives the land up.");
		}));
		return null;
	}

	/**
	 * Founds a quarter (an along district or an outpost, Noah's Q2) towards {@code dir}, as {@code building}: its strip beyond the claim held while it is
	 * built, its founding logged with it as the target; the build that follows plans it beside the town and builds its buildings. Returns the district's
	 * id, or throws with why not. The town must be a region. (slice SD2) In a world that builds regions from materials, the quarter is built from the
	 * settlement's stockpile: its build ensures it, quotes the bill before the placement approval and realises it in CONSTRUCTION, as a beside district's
	 * first building. Server thread.
	 */
	public static String foundQuarter(MinecraftServer server, Settlement s, String dir, dev.larattalabs.steward.model.Proposal.Quarter q, String name) {
		var townId = TownRegion.of(s);
		if (townId.isEmpty()) throw new IllegalStateException("A quarter goes beside a town Architect laid out, and " + s.name() + "'s town isn't one (or was undone).");
		var town = ArchitectAccess.api().regions().get(townId.get()).orElseThrow(() -> new IllegalStateException("The town's region can't be read."));
		var b = town.claim();
		int depth = "outpost".equals(q.kind()) ? DistrictRules.OUTPOST_DEPTH : DistrictRules.ALONG_DEPTH;
		var p = DistrictRules.quarter(s.claim(), new int[] {b.minX(), b.minZ(), b.maxX(), b.maxZ()}, s.lands().districts(), Settlements.others(s.id()),
			s.lands().protect(), dir, depth, name);
		if (!p.ok()) throw new IllegalStateException(p.note());
		String kind = "outpost".equals(q.kind()) ? Settlement.District.OUTPOST : Settlement.District.ALONG;
		var follow = Settlement.District.ALONG.equals(kind) ? new Settlement.District.Follow(q.boundary(), q.between(), q.side()) : null;
		String[] id = new String[1];
		var res = Settlements.addReservation(s.id(), i -> {
			id[0] = i;
			return new Settlement.District(i, name, dir, p.claim(), System.currentTimeMillis(), List.of(), false, kind, Settlement.District.BUILDING, townId.get(), null,
				p.land(), follow, opKeyStem(s, i));
		}, "Founded " + name + " (" + ("outpost".equals(q.kind()) ? "an outpost" : "along the " + q.boundary().replace('_', ' ')) + ", to the "
			+ DistrictRules.directionName(dir) + ")", System.currentTimeMillis());
		if (!res.ok()) throw new IllegalStateException(res.error());
		return id[0];
	}

	/**
	 * Architect's refusal of a beside plan in Steward's words (D3 §2.5's bracketed kinds). (raw-sweep) Architect's code and message are read only to
	 * pick the words, never said: anything else is said by its typed reason ({@link RefusalWords#reason}), else the generic line.
	 */
	static String besideRefusal(@Nullable String code, String message) {
		return besideRefusal(code, message, null);
	}

	/** As {@link #besideRefusal(String, String)}, with the verdict's typed reason for a refusal with no words of its own. Pure. */
	static String besideRefusal(@Nullable String code, @Nullable String message, dev.larattalabs.architect.api.@Nullable Reason reason) {
		String m = (code == null ? "" : code) + " " + (message == null ? "" : message);
		if (code != null && code.startsWith("NO_JOIN")) {
			if (m.contains("[apart]")) return "it would not touch the town.";
			if (m.contains("[street]")) return "the town has no street to join yet.";
			if (m.contains("[wall]")) return "the town's wall closes every way in (a gate would let a road through).";
			if (m.contains("[route]") || m.contains("[budget]")) return "no road could be found from there to the town's street.";
			return "it can't join the town's street from there.";
		}
		if (code != null && code.startsWith("PROTECTED")) return "it would cross a protected area.";
		if (code != null && code.startsWith("OVERLAP")) return "something already stands there.";
		if (code != null && code.startsWith("NO_ROOM")) return "there isn't room for a street there.";
		return RefusalWords.reason(reason) + ".";
	}

	/**
	 * (raw-sweep) A failed beside plan in Steward's words: the {@link dev.larattalabs.architect.api.ArchitectRefused} in its cause chain read as
	 * {@link #besideRefusal(String, String, dev.larattalabs.architect.api.Reason)} (its detail, else the code its message starts with), else {@link RefusalWords#of}.
	 */
	static String besideRefusal(Throwable err) {
		for (Throwable t = err; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof dev.larattalabs.architect.api.ArchitectRefused ar) {
				String msg = ar.getMessage() == null ? "" : ar.getMessage();
				String code = !ar.detail().isBlank() ? ar.detail() : msg.matches("^[A-Z_]{3,}.*") ? msg.replaceFirst("^([A-Z_]+).*", "$1") : null;
				return besideRefusal(code, msg, ar.reason());
			}
		}
		return RefusalWords.of(err) + ".";
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
		for (SiteView v : ArchitectAccess.api().sites(server).list(s.get().owner())) {
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
		// a district region Architect placed after its build let go (an answer lost, and its realise slower than the build's wait): no district names it,
		// so it is taken up as the orphan it is (the world-start sweep does the same for one left across a restart)
		try {
			for (var v : ArchitectAccess.api().regions().list(s.get().owner())) {
				if (v.state() == dev.larattalabs.architect.api.RegionState.REMOVED || v.state() == dev.larattalabs.architect.api.RegionState.REMOVING) continue;
				if (!v.ext().has(DISTRICT_EXT) || !v.ext().get(DISTRICT_EXT).isJsonPrimitive()) continue;
				if (s.get().lands().districts().stream().anyMatch(d -> v.id().equals(d.region()))) continue;
				var owner = s.get().lands().district(v.ext().get(DISTRICT_EXT).getAsString());
				// still building: its runner (or the next pass) adopts it
				if (owner.isPresent() && Settlement.District.BUILDING.equals(owner.get().state())) continue;
				if (VillageSweep.entryOf(s.get(), v.id()).isPresent()) continue;
				Steward.LOGGER.warn("{}: region {} was placed for district {} after its build let go: taking it up", settlementId, v.id(), v.ext().get(DISTRICT_EXT).getAsString());
				VillageSweep.takeUp(server, s.get(), v.id(), say);
				return;
			}
		} catch (RuntimeException | LinkageError e) {
			// can't tell this time: the next pass looks again
		}
		for (Settlement.District d : s.get().lands().districts()) {
			// a district left building by a build that is gone (a stop before it ended), with no region of its own Architect still holds: put back
			if (Settlement.District.BUILDING.equals(d.state()) && d.region() == null) {
				if (liveRegionOf(s.get().owner(), d.id()).isEmpty()) release(settlementId, d);
				continue;
			}
			if (!d.square()) {
				giveUpRemoved(server, s.get(), d);
				continue;
			}
			if (!d.linkUndone() && !d.linked() && d.founding().isPresent() && districtStreet(server, s.get(), d) != null) link(server, settlementId, d.id(), say, false);
		}
	}

	/**
	 * (slice SD) A district region removed by an undo whose give-up never saved (a stop, or a failed save, between the removal's entry and it): given up
	 * now, only on Architect's positive tombstone, with its placing entry retired and nothing of the settlement's standing in its land.
	 */
	static void giveUpRemoved(@Nullable MinecraftServer server, Settlement s, Settlement.District d) {
		if (d.square() || d.region() == null) return;
		boolean tombstone;
		try {
			tombstone = ArchitectAccess.api().regions().get(d.region()).map(v -> v.state() == dev.larattalabs.architect.api.RegionState.REMOVED).orElse(false);
		} catch (RuntimeException | LinkageError e) {
			tombstone = false;
		}
		// a sites file that did not load reads as nothing standing: decide at the next healthy read (as BCU's close does; batch-2 verifier)
		boolean healthy;
		try {
			healthy = ArchitectAccess.api().sites(server).loadHealthy();
		} catch (RuntimeException | LinkageError e) {
			healthy = false;
		}
		if (healthy && leftAfterRemoval(s, d, tombstone) && !standsIn(server, s, d)) {
			Settlements.removeDistrict(s.id(), d.id(), "Gave up " + d.name() + " (its build was undone)", System.currentTimeMillis());
		}
	}

	/**
	 * (slice SD) Whether a district region's undo finished but its give-up never saved: Architect holds its tombstone ({@code tombstone}: REMOVED, never an
	 * absent record) and every entry that placed it is retired (a later removal names its sites). Pure over the history.
	 */
	static boolean leftAfterRemoval(Settlement s, Settlement.District d, boolean tombstone) {
		if (!tombstone || d.region() == null) return false;
		String group = dev.larattalabs.steward.gateway.VPlan.REGION_PREFIX + d.region();
		var placed = s.log().stream().filter(e -> e.kind() == Settlement.Kind.PROJECT_PLACED && group.equals(e.siteGroup())).toList();
		return !placed.isEmpty() && placed.stream().allMatch(e -> Undo.retired(s, e));
	}

	/** (TEST) {@link #takeUp(MinecraftServer, Settlement, String, String, Consumer, Runnable)}. */
	static void takeUpForTest(Settlement s, String siteId, Consumer<String> say, Runnable done) {
		takeUp(null, s, siteId, "test", say, done);
	}

	/** (TEST) {@link #takeUpAll} as reconcile runs it, the settlement marked laying meanwhile. */
	static void takeUpAllForTest(Settlement s, List<String> ids, Consumer<String> say) {
		LAYING.add(s.id());
		takeUpAll(null, s, ids, say, () -> LAYING.remove(s.id()));
	}

	/**
	 * (T4R-PIN P2, T4R-RETRY) What an unrecorded road is, in the steward's words, from its own role: never its id. Pure over {@code role} (the road's
	 * {@code steward_mc:infra}, or null).
	 */
	static String unrecordedRoad(@Nullable String role) {
		if (FOUNDING_ROLE.equals(role)) return "A road out to a new district was laid but never recorded";
		if (LINK_ROLE.equals(role)) return "A road linking a district to the town was laid but never recorded";
		return "A district road was laid but never recorded";
	}

	/** {@link #unrecordedRoad(String)} for a road read from Architect (its role unread: the general words). */
	private static String unrecordedRoad(@Nullable MinecraftServer server, String siteId) {
		return unrecordedRoad(roadRole(server, siteId));
	}

	/** A steward road's role ({@code steward_mc:infra}) as Architect has it now, or null (no such site, not a steward road, unreadable). */
	private static @Nullable String roadRole(@Nullable MinecraftServer server, String siteId) {
		try {
			var v = ArchitectAccess.api().sites(server).get(siteId);
			if (v.isEmpty() || !"road".equals(v.get().kind()) || v.get().ext() == null || !v.get().ext().has(INFRA) || !v.get().ext().get(INFRA).isJsonPrimitive()) return null;
			return v.get().ext().get(INFRA).getAsString();
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** (T4R-RETRY) The key of a road take-up waiting behind a busy removal ({@link BusyRetries}). */
	static String roadRetryKey(String settlementId, String siteId) {
		return "road:" + settlementId + ":" + siteId;
	}

	/** Where a retried road take-up's lines go (the steward's voice; tests capture them). */
	static java.util.function.BiConsumer<String, String> retrySay = Undo.VOICE;

	/**
	 * (T4R-RETRY, row 7) An unrecorded road refused as busy, taken up again by itself: only while it is still a steward road of the settlement that no
	 * district records (else nothing; the reconcile decides). While the settlement is busy or another take-up runs for it, it waits again, counted.
	 * Server thread.
	 */
	static void retryRoad(@Nullable MinecraftServer server, String settlementId, String siteId, SiteBusy.Held held) {
		String key = roadRetryKey(settlementId, siteId);
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty() || Settlements.disabled()) { BusyRetries.forget(key); return; }
		boolean runnerBusy = BusyRetries.settlementBusy(settlementId);
		if (LAYING.contains(settlementId) || runnerBusy) {
			BusyRetries.wait(key, held, () -> retryRoad(server, settlementId, siteId, held), false);
			return;
		}
		String role = roadRole(server, siteId);
		boolean known = s.get().lands().districts().stream().anyMatch(d -> d.roads().stream().anyMatch(r -> r.siteId().equals(siteId)));
		if (known || !(FOUNDING_ROLE.equals(role) || LINK_ROLE.equals(role))) { BusyRetries.forget(key); return; }
		LAYING.add(settlementId);
		takeUp(server, s.get(), siteId, unrecordedRoad(role), line -> retrySay.accept(settlementId, line), () -> LAYING.remove(settlementId));
	}

	private static void takeUpAll(MinecraftServer server, Settlement s, List<String> ids, Consumer<String> say, Runnable done) {
		if (ids.isEmpty()) {
			done.run();
			return;
		}
		takeUp(server, s, ids.get(0), unrecordedRoad(server, ids.get(0)), say, () -> takeUpAll(server, s, ids.subList(1, ids.size()), say, done));
	}

	/** Removes a road the settlement does not record, says how that went, then runs {@code done}. */
	private static void takeUp(MinecraftServer server, Settlement s, String siteId, String why, Consumer<String> say, Runnable done) {
		// (batch-077 review P1-1, the same rule) never while settlements are disabled: the road stands unrecorded, and the next session's reconcile takes it up
		if (Settlements.disabled()) {
			Steward.LOGGER.warn("road {} of {} is not taken up: settlements are disabled this session (taken up at the next start)", siteId, s.id());
			say.accept(why + ": settlements are disabled this session, so it stands until the next session.");
			done.run();
			return;
		}
		int session = Session.current();
		String retryKey = roadRetryKey(s.id(), siteId);
		Undo.asking(server, () -> ArchitectAccess.api().sites(server).remove(siteId, new RemoveOptions(false, s.owner()))).whenComplete((rr, e) -> {
			if (!Session.is(session)) return;
			// (T4R-RETRY) any answer but a busy one ends this take-up's wait and its count
			if (!SiteBusy.of(e).busyAtAll() || SiteBusy.of(e).decided()) BusyRetries.forget(retryKey);
			if (e == null && rr.removed()) say.accept(why + ": the road was taken up again.");
			// (T4S-PRE) decided and held: it finishes by itself (the next reconcile finds it gone); not a failure, nothing to try again
			else if (SiteBusy.of(e).decided()) {
				Steward.LOGGER.info("road {} of {} is being taken up, decided and held: {}", siteId, s.id(), SettlementRunner.message(e));
				say.accept(why + ": it is being taken up; " + RemovalStops.waitsFor(SiteBusy.of(e)) + ".");
			} else if (SiteBusy.of(e).busyAtAll()) {
				// (T4RD-S) another removal or change in the way: nothing began. (T4R-RETRY, row 7) taken up again by itself once that removal finishes
				// (or its bounded timer); said once; past the bound the next reconcile takes it up
				Steward.LOGGER.info("road {} of {} is not taken up yet (busy): {}", siteId, s.id(), SettlementRunner.message(e));
				var h = SiteBusy.of(e);
				var step = BusyRetries.wait(retryKey, h, () -> retryRoad(server, s.id(), siteId, h), false);
				if (step.first()) say.accept(RemovalStops.roadBusy(why, h, RemovalStops.blocker(server, h, s.owner())));
			} else {
				// (raw-text) Architect's error or blockers are logged, never said (a road's removal has no typed cause)
				Steward.LOGGER.info("road {} of {} was not taken up: {}", siteId, s.id(), e != null ? SettlementRunner.message(e) : String.join(", ", rr.blockers()));
				say.accept(why + ", and it could not be taken up" + (e != null ? " just now" : ": " + RemovalStops.IN_THE_WAY)
					+ ". It is tried again later.");
			}
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
		var sites = ArchitectAccess.api().sites(server);
		int session = Session.current();
		LAYING.add(settlementId);
		sites.placeRoad(road(level, points, s.get(), LINK_ROLE, null)).whenComplete((r, err) -> {
			if (!Session.is(session)) return;
			if (err != null || !r.placed() || r.siteId().isEmpty()) {
				LAYING.remove(settlementId);
				say.accept("The road to " + d.get().name() + "'s street was refused: " + (err != null ? said(err) : reasons(r.refusals())) + ".");
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
		var sites = ArchitectAccess.api().sites(server);
		List<String> said = new ArrayList<>();
		for (Settlement.District d : s.get().lands().districts()) {
			// a region district is given up with its region (docs/DISTRICTS.md section 4): once its region is confirmed gone and nothing of the settlement's
			// stands in its land; never by roads it doesn't have. A reservation is given up by undoing its founding.
			if (!d.square()) {
				if (d.region() != null && regionGone(d.region()) && !standsIn(server, s.get(), d)
					&& Settlements.removeDistrict(settlementId, d.id(), "Gave up " + d.name() + " (its build was undone)", System.currentTimeMillis()).ok()) {
					said.add(d.name() + " is given up: its land is free again.");
				}
				continue;
			}
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

	/** Whether a region is removed (its tombstone, or no record at all). */
	static boolean regionGone(String region) {
		var v = ArchitectAccess.api().regions().get(region);
		return v.isEmpty() || v.get().state() == dev.larattalabs.architect.api.RegionState.REMOVED;
	}

	/** Whether a site of the settlement other than the district's roads stands in it. */
	static boolean standsIn(@Nullable MinecraftServer server, Settlement s, Settlement.District d) {
		Set<String> roads = new HashSet<>();
		d.roads().forEach(r -> roads.add(r.siteId()));
		for (SiteView v : ArchitectAccess.api().sites(server).list(s.owner())) {
			if (roads.contains(v.id())) continue;
			var b = v.box();
			if (d.land().contains(d.claim().dimension(), (b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2)) return true;
		}
		return false;
	}

	/** How many buildings stand in the town and in each district (oldest first): where the next one goes ({@link dev.larattalabs.steward.model.ProposalRules#where}). */
	public record Counts(int town, java.util.LinkedHashMap<String, Integer> districts) {}

	public static Counts counts(MinecraftServer server, Settlement s) {
		java.util.LinkedHashMap<String, Integer> per = new java.util.LinkedHashMap<>();
		// only districts that take a building: a square, a reserved beside district (its first building builds it), or a standing district region that
		// grows by revision (slice DG)
		for (Settlement.District d : s.lands().districts()) if (takes(d)) per.put(d.id(), 0);
		int town = 0;
		for (SiteView v : ArchitectAccess.api().sites(server).list(s.owner())) {
			if (!"building".equals(v.kind()) || BatchPlanner.isStreet(v.itemKey())) continue;
			var b = v.box();
			var in = s.districtAt(s.claim().dimension(), (b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2);
			if (in.isPresent()) {
				if (per.containsKey(in.get().id())) per.merge(in.get().id(), 1, Integer::sum);
			} else town++;
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
		java.util.Map<String, dev.larattalabs.steward.model.Land.Rect> squares = new java.util.LinkedHashMap<>();
		for (String dir : DistrictRules.DIRECTIONS) {
			if (skip.contains(dir)) continue;
			var p = DistrictRules.place(s.claim(), street, s.lands().districts(), Settlements.others(s.id()), s.lands().protect(), dir, DistrictRules.RADII[0]);
			// a side whose road would run over a building is not one founding could take
			if (p.ok() && overBuilding(server, s, p.road()) == null) {
				Claim c = p.claim();
				squares.put(dir, new dev.larattalabs.steward.model.Land.Rect(c.centerX() - c.radius(), c.centerZ() - c.radius(), c.centerX() + c.radius(), c.centerZ() + c.radius()));
			}
		}
		choose(server, s, site, standing, squares, then);
	}

	/** As above over {@code sides}: each side that may be chosen, with the ground it would take (a square district's, or a quarter's strip). */
	private static void choose(MinecraftServer server, Settlement s, dev.larattalabs.steward.model.DistrictSite site, @Nullable BlockPos standing,
		java.util.Map<String, dev.larattalabs.steward.model.Land.Rect> sides, Consumer<dev.larattalabs.steward.model.DistrictSite.Choice> then) {
		var squares = sides;
		List<String> free = List.copyOf(squares.keySet());
		dev.larattalabs.steward.model.DistrictSite.Standing stand = null;
		if (standing != null) {
			java.util.Map<String, Double> dist = new java.util.LinkedHashMap<>();
			for (var e : squares.entrySet()) {
				var r = e.getValue();
				int dx = Math.max(0, Math.max(r.x0() - standing.getX(), standing.getX() - r.x1())), dz = Math.max(0, Math.max(r.z0() - standing.getZ(), standing.getZ() - r.z1()));
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
			net.minecraft.world.level.levelgen.structure.BoundingBox box;
			if (k.equals("town")) {
				Claim c = DistrictRules.villageSquare(s.claim());
				box = new net.minecraft.world.level.levelgen.structure.BoundingBox(c.centerX() - c.radius(), level.getMinY(), c.centerZ() - c.radius(), c.centerX() + c.radius(),
					level.getMaxY(), c.centerZ() + c.radius());
			} else {
				var r = squares.get(k);
				box = new net.minecraft.world.level.levelgen.structure.BoundingBox(r.x0(), level.getMinY(), r.z0(), r.x1(), level.getMaxY(), r.z1());
			}
			samples.add(ArchitectAccess.api().survey().sample(level, box, PROFILE_RESOLUTION, dev.larattalabs.architect.api.LoadPolicy.LOADED_ONLY));
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
		return streetOf(ArchitectAccess.api().sites(server).list(s.owner()), s, null);
	}

	/** A district's own street, or null before its first build. */
	public static DistrictRules.@Nullable Street districtStreet(MinecraftServer server, Settlement s, Settlement.District d) {
		return streetOf(ArchitectAccess.api().sites(server).list(s.owner()), s, d);
	}

	/**
	 * The street of the town ({@code district} null) or of a district: its street sites there (the first laid, and the extensions additions laid along the same
	 * line), as one centre line and its ends. The one rule every caller uses (founding, layouts, links). Null when there is none.
	 */
	public static DistrictRules.@Nullable Street streetOf(List<SiteView> sites, Settlement s, Settlement.@Nullable District district) {
		List<Piece> pieces = new java.util.ArrayList<>();
		for (SiteView v : sites) {
			var b = v.box();
			pieces.add(piece(v.kind(), v.itemKey(), v.ext(), b.minX(), b.maxX(), b.minZ(), b.maxZ()));
		}
		return streetOfPieces(pieces, s, district);
	}

	/** A site as a {@link Piece}: the line Steward recorded on its own street (east-west or north-south), read from its ext. */
	public static Piece piece(String kind, @Nullable String itemKey, com.google.gson.@Nullable JsonObject ext, int minX, int maxX, int minZ, int maxZ) {
		return new Piece(kind, itemKey, ext != null && ext.has(BatchPlanner.REGION_STREET_EXT), minX, maxX, minZ, maxZ, intExt(ext, BatchPlanner.STREET_Z),
			intExt(ext, BatchPlanner.STREET_X0), intExt(ext, BatchPlanner.STREET_X1), intExt(ext, BatchPlanner.STREET_X), intExt(ext, BatchPlanner.STREET_Z0),
			intExt(ext, BatchPlanner.STREET_Z1));
	}

	/**
	 * A placed site as {@link #streetOfPieces} reads it: its kind, key, the FL street flag, its box's columns and the line Steward recorded on its own streets
	 * ({@code z}, {@code x0..x1} east-west; {@code nsX}, {@code nsZ0..nsZ1} north-south).
	 */
	public record Piece(String kind, @Nullable String itemKey, boolean streetFlag, int minX, int maxX, int minZ, int maxZ, @Nullable Integer z, @Nullable Integer x0,
		@Nullable Integer x1, @Nullable Integer nsX, @Nullable Integer nsZ0, @Nullable Integer nsZ1) {
		public Piece(String kind, @Nullable String itemKey, boolean streetFlag, int minX, int maxX, int minZ, int maxZ, @Nullable Integer z, @Nullable Integer x0,
			@Nullable Integer x1) {
			this(kind, itemKey, streetFlag, minX, maxX, minZ, maxZ, z, x0, x1, null, null, null);
		}
	}

	/** A street piece's line on its axis and its ends along it (lo <= hi, whichever way it was laid). */
	public record Line(boolean northSouth, int line, int lo, int hi) {}

	/** A street piece's line: the one Steward recorded on it, else its box's middle across its longer side (east-west when the box is as wide as deep). */
	public static Line lineOf(Piece v) {
		boolean northSouth = v.z() == null && (v.nsX() != null || v.maxX() - v.minX() < v.maxZ() - v.minZ());
		if (northSouth) {
			int p = v.nsZ0() != null ? v.nsZ0() : v.minZ(), q = v.nsZ1() != null ? v.nsZ1() : v.maxZ();
			return new Line(true, v.nsX() != null ? v.nsX() : Math.floorDiv(v.minX() + v.maxX(), 2), Math.min(p, q), Math.max(p, q));
		}
		int p = v.x0() != null ? v.x0() : v.minX(), q = v.x1() != null ? v.x1() : v.maxX();
		return new Line(false, v.z() != null ? v.z() : Math.floorDiv(v.minZ() + v.maxZ(), 2), Math.min(p, q), Math.max(p, q));
	}

	/** What {@link #readStreet} makes of a street: the street (either axis), or none, and why not when its pieces don't make one straight street. */
	public record StreetRead(DistrictRules.@Nullable Street street, @Nullable String why) {}

	/**
	 * The street of the town ({@code district} null) or of a district on either axis (pure): the same rule as {@link #streetOfPieces}, for a street that runs
	 * east-west or north-south, laid in either direction. A piece's line is the one Steward recorded on it, else its box's middle across its longer side.
	 * The first piece found sets the line; pieces on another line of the same axis are not this street. An east-west street with a piece recorded
	 * north-south (a bent or L-shaped street): none, and {@code why} says so. A tall piece with no recorded line beside an east-west street is passed by,
	 * as {@link #streetOfPieces} does.
	 */
	public static StreetRead readStreet(List<Piece> pieces, Settlement s, Settlement.@Nullable District district) {
		String dim = s.claim().dimension();
		// east-west pieces, north-south pieces Steward recorded as such, and tall pieces with no line recorded (a street laid before lines were recorded,
		// its box with its lanterns): those are north-south only when there is no east-west street, as the east-west reader passes them by
		List<Line> ew = new java.util.ArrayList<>(), ns = new java.util.ArrayList<>(), tall = new java.util.ArrayList<>();
		for (Piece v : pieces) {
			if (!"road".equals(v.kind()) || !(v.streetFlag() || BatchPlanner.isStreet(v.itemKey()))) continue;
			Line l = lineOf(v);
			int cx = l.northSouth() ? l.line() : (l.lo() + l.hi()) / 2, cz = l.northSouth() ? (l.lo() + l.hi()) / 2 : l.line();
			var in = s.districtAt(dim, cx, cz);
			boolean ours = district != null ? in.map(d -> d.id().equals(district.id())).orElse(false) : in.isEmpty();
			if (!ours) continue;
			(!l.northSouth() ? ew : v.nsX() != null ? ns : tall).add(l);
		}
		if (!ew.isEmpty() && !ns.isEmpty()) return new StreetRead(null, "its street turns (pieces run both east-west and north-south), and only a straight street is taken on");
		if (!ew.isEmpty()) return new StreetRead(along(ew, false), null);
		if (!ns.isEmpty() || !tall.isEmpty()) {
			List<Line> all = new java.util.ArrayList<>(ns);
			all.addAll(tall);
			return new StreetRead(along(all, true), null);
		}
		return new StreetRead(null, null);
	}

	/** The street the pieces make: the first piece's line, from the lowest end to the highest of the pieces on it (pieces on another line are not it). */
	private static DistrictRules.Street along(List<Line> lines, boolean northSouth) {
		int line = lines.get(0).line(), lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (Line l : lines) {
			if (l.line() != line) continue;
			lo = Math.min(lo, l.lo());
			hi = Math.max(hi, l.hi());
		}
		return new DistrictRules.Street(line, lo, hi, northSouth);
	}

	/**
	 * (slice NS2) The town's street on either axis, for the legacy street path (additions, the stockpile's clearance): {@link #streetOf}'s east-west
	 * street when there is one (a bent street keeps it, as before), else a straight north-south street as {@link #readStreet} reads it. A district's
	 * street is east-west only, as {@link #streetOf}.
	 */
	public static DistrictRules.@Nullable Street streetEitherAxis(List<SiteView> sites, Settlement s, Settlement.@Nullable District district) {
		List<Piece> pieces = new java.util.ArrayList<>();
		for (SiteView v : sites) {
			var b = v.box();
			pieces.add(piece(v.kind(), v.itemKey(), v.ext(), b.minX(), b.maxX(), b.minZ(), b.maxZ()));
		}
		return streetEitherAxisOfPieces(pieces, s, district);
	}

	/** {@link #streetEitherAxis} over pieces (pure). */
	public static DistrictRules.@Nullable Street streetEitherAxisOfPieces(List<Piece> pieces, Settlement s, Settlement.@Nullable District district) {
		var ew = streetOfPieces(pieces, s, district);
		if (ew != null || district != null) return ew;
		var read = readStreet(pieces, s, null).street();
		return read != null && read.northSouth() ? read : null;
	}

	/** {@link #streetOf} over pieces (pure). */
	public static DistrictRules.@Nullable Street streetOfPieces(List<Piece> pieces, Settlement s, Settlement.@Nullable District district) {
		String dim = s.claim().dimension();
		Integer z = null;
		int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
		for (Piece v : pieces) {
			if (!"road".equals(v.kind()) || !(v.streetFlag() || BatchPlanner.isStreet(v.itemKey()))) continue;
			// the line it was laid on (its box also holds lanterns); streets laid before it was recorded, and a region's: the box's middle, for one
			// running east-west only (a district's north-south street, a ring, a following street: additions know an east-west line)
			if (v.z() == null && (v.nsX() != null || v.maxX() - v.minX() < v.maxZ() - v.minZ())) continue;
			int cz = v.z() != null ? v.z() : Math.floorDiv(v.minZ() + v.maxZ(), 2);
			int p0 = v.x0() != null ? v.x0() : v.minX(), p1 = v.x1() != null ? v.x1() : v.maxX();
			int sx0 = Math.min(p0, p1), sx1 = Math.max(p0, p1);
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

	/** An int the ext records under {@code key}, or null (never unboxed here: a site without it is a region's or an older street). */
	public static @Nullable Integer intExt(com.google.gson.@Nullable JsonObject ext, String key) {
		if (ext == null || !ext.has(key) || !ext.get(key).isJsonPrimitive()) return null;
		return Integer.valueOf(ext.get(key).getAsInt());
	}

	/** The building (its role) a road along {@code pts} would run over, or null. */
	static @Nullable String overBuilding(MinecraftServer server, Settlement s, List<Settlement.Pt> pts) {
		var cells = DistrictRules.cells(pts, DistrictRules.ROAD_WIDTH);
		for (SiteView v : ArchitectAccess.api().sites(server).list(s.owner())) {
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
		// (raw-sweep) each typed reason once in the steward's words (RefusalWords.fit); Architect's messages to the log only
		if (!refusals.isEmpty()) Steward.LOGGER.info("road refused: {}", refusals.stream().map(x -> x.reason() + " " + x.message()).toList());
		return RefusalWords.fit(refusals);
	}

	/** (raw-sweep) A failed call in the steward's words ({@link RefusalWords#of}); Architect's error to the log only. */
	private static String said(Throwable err) {
		Steward.LOGGER.warn("district road failed", err);
		return RefusalWords.of(err);
	}
}
