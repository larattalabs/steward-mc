package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.architect.api.RegionView;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.BatchPlanner;
import dev.larattalabs.steward.model.DistrictRules;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.Settlement.Kind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * A street town taken on as an Architect region at its first growth (docs/GROWTH.md, slice B): Steward's own street pieces and lot sites become one
 * region ({@code Regions.adopt}, no block written), so slice A grows it by revisions. The adoption is saved in the settlement's history before it is
 * asked ({@code TOWN_ADOPTED adopt:<key>}), answered by a receipt ({@code TOWN_ADOPTED region:<id>}) or a refusal ({@code TOWN_RELEASED adopt:<key>}),
 * and undone by {@code Regions.unadopt} ({@code TOWN_RELEASED region:<id>}). Steward never removes an adopted town whole.
 */
public final class Adoption {
	private Adoption() {
	}

	/** The request's ext key that names the adoption's op key (the reconcile finds the region by it). */
	public static final String KEY_EXT = "steward_mc:adopt";
	public static final String INTENT = "adopt:";
	public static final String FEATURE = "regionAdopt";

	/** What a town is adopted with: the street's line and ends, its road sites, and lot id -> site id. */
	public record Inventory(int streetZ, int xLo, int xHi, List<String> roads, Map<String, String> lots) {}

	/** A lot site as the inventory reads it (pure input): its id, its lot ext, its box's centre. */
	public record LotSite(String siteId, @Nullable String lot, int cx, int cz) {}

	// ------------------------------------------------------------------ the inventory

	/** The town's adoptable inventory from the owner's live sites, or empty (no east-west street, or no lot). */
	public static Optional<Inventory> inventory(MinecraftServer server, Settlement s) {
		var all = ArchitectApi.get().sites(server).list(s.owner());
		var street = Districts.streetOf(all, s, null);
		if (street == null) return Optional.empty();
		Set<String> playerBuilds = new HashSet<>();
		for (var e : s.log()) if (e.kind() == Kind.BUILD_ADOPTED) playerBuilds.addAll(e.siteIds());
		List<String> roads = new ArrayList<>();
		List<LotSite> lots = new ArrayList<>();
		String dim = s.claim().dimension();
		for (SiteView v : all) {
			if (v.region() != null || !dim.equals(v.dimension().identifier().toString())) continue;
			var b = v.box();
			int cx = Math.floorDiv(b.minX() + b.maxX(), 2), cz = Math.floorDiv(b.minZ() + b.maxZ(), 2);
			if ("road".equals(v.kind()) && BatchPlanner.isStreet(v.itemKey())) {
				if (onStreet(v, street, s)) roads.add(v.id());
				continue;
			}
			if (!s.id().equals(str(v.ext(), "steward_mc:settlement")) || str(v.ext(), "steward_mc:lot") == null || playerBuilds.contains(v.id())) continue;
			if (!s.claim().contains(dim, cx, s.claim().minY(), cz) || s.districtAt(dim, cx, cz).isPresent()) continue;
			lots.add(new LotSite(v.id(), str(v.ext(), "steward_mc:lot"), cx, cz));
		}
		if (roads.isEmpty() || lots.isEmpty()) return Optional.empty();
		roads.sort(Adoption::bySiteId);
		return Optional.of(new Inventory(street.z(), street.x0(), street.x1(), List.copyOf(roads), allocate(lots)));
	}

	/** A street road on the town's line (as {@link Districts#streetOfPieces} reads it). */
	private static boolean onStreet(SiteView v, DistrictRules.Street street, Settlement s) {
		var b = v.box();
		Integer z = Districts.intExt(v.ext(), BatchPlanner.STREET_Z);
		if (z == null && b.maxX() - b.minX() < b.maxZ() - b.minZ()) return false;
		int cz = z != null ? z : Math.floorDiv(b.minZ() + b.maxZ(), 2);
		Integer x0 = Districts.intExt(v.ext(), BatchPlanner.STREET_X0), x1 = Districts.intExt(v.ext(), BatchPlanner.STREET_X1);
		int mid = ((x0 != null ? x0 : b.minX()) + (x1 != null ? x1 : b.maxX())) / 2;
		return cz == street.z() && s.districtAt(s.claim().dimension(), mid, cz).isEmpty();
	}

	/**
	 * Lot ids for Architect (pure): sites in id order ({@code s<N>} numerically); the base is the lot ext lowercased, every character outside
	 * {@code [a-z0-9_]} dropped, {@code l_} in front when it does not start with a letter, cut to 40; a base taken already gets {@code _2}, {@code _3}, ...
	 */
	public static Map<String, String> allocate(List<LotSite> lots) {
		List<LotSite> sorted = new ArrayList<>(lots);
		sorted.sort((a, b) -> bySiteId(a.siteId(), b.siteId()));
		Map<String, String> out = new LinkedHashMap<>();
		Set<String> taken = new HashSet<>();
		for (LotSite l : sorted) {
			String base = l.lot() == null ? "" : l.lot().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
			if (base.isEmpty() || !Character.isLetter(base.charAt(0))) base = "l_" + base;
			if (base.length() > 40) base = base.substring(0, 40);
			String id = base;
			for (int k = 2; taken.contains(id); k++) id = base + "_" + k;
			taken.add(id);
			out.put(id, l.siteId());
		}
		return out;
	}

	/** Site ids in Architect's order: the letters, then the number ({@code s9} before {@code s10}). */
	static int bySiteId(String a, String b) {
		String pa = a.replaceAll("\\d+$", ""), pb = b.replaceAll("\\d+$", "");
		if (!pa.equals(pb)) return pa.compareTo(pb);
		return Long.compare(num(a), num(b));
	}

	private static long num(String id) {
		var m = java.util.regex.Pattern.compile("(\\d+)$").matcher(id);
		return m.find() ? Long.parseLong(m.group(1)) : -1;
	}

	private static @Nullable String str(com.google.gson.@Nullable JsonObject ext, String key) {
		return ext != null && ext.has(key) && ext.get(key).isJsonPrimitive() ? ext.get(key).getAsString() : null;
	}

	// ------------------------------------------------------------------ the history

	/** The intent's entry: the lot map as changes ({@code lot} the lot id), then the roads ({@code lot} null). Outcome PARTIAL (pending). */
	public static Settlement.LogEntry intent(String key, Inventory inv, long now) {
		return Settlement.LogEntry.of(now, Kind.TOWN_ADOPTED, "Taking the town on as one region (Architect), to grow it", changes(inv), INTENT + key,
			Settlement.Outcome.PARTIAL);
	}

	public static Settlement.LogEntry receipt(Settlement.LogEntry intent, String region, long now) {
		return Settlement.LogEntry.of(now, Kind.TOWN_ADOPTED, "Took the town on as one region (Architect) to grow it", intent.changes(),
			dev.larattalabs.steward.gateway.VPlan.REGION_PREFIX + region, Settlement.Outcome.DONE);
	}

	public static Settlement.LogEntry released(String group, String why, long now) {
		return new Settlement.LogEntry(now, Kind.TOWN_RELEASED, why, List.of(), group);
	}

	private static List<Settlement.SiteChange> changes(Inventory inv) {
		List<Settlement.SiteChange> out = new ArrayList<>();
		for (var e : inv.lots().entrySet()) out.add(new Settlement.SiteChange(e.getValue(), e.getKey(), 0, 1));
		for (String r : inv.roads()) out.add(new Settlement.SiteChange(r, null, 0, 1));
		return out;
	}

	/** The lot map an adoption entry recorded (lot id -> site id). */
	public static Map<String, String> lotsOf(Settlement.LogEntry e) {
		Map<String, String> out = new LinkedHashMap<>();
		for (var c : e.changes()) if (c.lot() != null) out.put(c.lot(), c.siteId());
		return out;
	}

	public static List<String> roadsOf(Settlement.LogEntry e) {
		return e.changes().stream().filter(c -> c.lot() == null).map(Settlement.SiteChange::siteId).toList();
	}

	/** The open intent (an {@code adopt:} entry no later receipt or refusal closes), or empty. Pure. */
	public static Optional<Settlement.LogEntry> openIntent(Settlement s) {
		var log = s.log();
		for (int i = log.size() - 1; i >= 0; i--) {
			var e = log.get(i);
			if (e.kind() == Kind.TOWN_ADOPTED && e.siteGroup() != null && e.siteGroup().startsWith(dev.larattalabs.steward.gateway.VPlan.REGION_PREFIX))
				return Optional.empty();
			if (e.kind() == Kind.TOWN_RELEASED && e.siteGroup() != null && e.siteGroup().startsWith(INTENT)) return Optional.empty();
			if (e.kind() == Kind.TOWN_ADOPTED && e.siteGroup() != null && e.siteGroup().startsWith(INTENT)) return Optional.of(e);
		}
		return Optional.empty();
	}

	/** The adoption that stands (a receipt no later release of its region closes), or empty. Pure. */
	public static Optional<Settlement.LogEntry> standing(Settlement s) {
		Set<String> released = new HashSet<>();
		var log = s.log();
		for (int i = log.size() - 1; i >= 0; i--) {
			var e = log.get(i);
			if (e.siteGroup() == null || !e.siteGroup().startsWith(dev.larattalabs.steward.gateway.VPlan.REGION_PREFIX)) continue;
			if (e.kind() == Kind.TOWN_RELEASED) released.add(e.siteGroup());
			else if (e.kind() == Kind.TOWN_ADOPTED && !released.contains(e.siteGroup())) return Optional.of(e);
		}
		return Optional.empty();
	}

	public static @Nullable String regionOf(Settlement.LogEntry e) {
		return dev.larattalabs.steward.gateway.VPlan.regionOf(e.siteGroup());
	}

	// ------------------------------------------------------------------ the reconcile

	/** What the reconcile does with an open intent, or a standing adoption (pure). */
	public enum Close { NONE, RECEIPT, NOT_ADOPTED, REMOVED, RELEASED }

	/** An open intent: the region found by its key (not removed) → its receipt; none, or removed → not adopted. */
	public static Close intentClose(boolean found, boolean removed) {
		return found && !removed ? Close.RECEIPT : Close.NOT_ADOPTED;
	}

	/**
	 * A standing adoption: its region removed (a tombstone) → retired as removed; gone with no tombstone while its sites stand in no region → released
	 * (a lost unadopt answer); else it stands.
	 */
	public static Close standingClose(boolean present, boolean tombstone, boolean sitesOutOfRegions) {
		if (tombstone) return Close.REMOVED;
		if (!present && sitesOutOfRegions) return Close.RELEASED;
		return Close.NONE;
	}

	/**
	 * Closes what a crash, a restart or a lost answer left open (world start, before the sweep; before an undo or a growth of the settlement). Not while a
	 * build of the settlement runs (its adopt call may be in flight). Returns false when the history could not be saved.
	 */
	public static boolean reconcile(MinecraftServer server, Settlement s) {
		return reconcile(server, s, false);
	}

	/** {@code inBuild}: asked by the settlement's own build before its adoption is asked (nothing of it is in flight). */
	public static boolean reconcile(MinecraftServer server, Settlement s, boolean inBuild) {
		if (!inBuild && SettlementRunner.active(s.id()) != null) return true;
		var regions = ArchitectApi.get().regions();
		long now = System.currentTimeMillis();
		var intent = openIntent(s);
		if (intent.isPresent()) {
			String key = intent.get().siteGroup().substring(INTENT.length());
			var found = regions.list(s.owner()).stream().filter(v -> key.equals(str(v.ext(), KEY_EXT))).findFirst();
			var close = intentClose(found.isPresent(), found.map(v -> v.state() == RegionState.REMOVED).orElse(false));
			var entry = close == Close.RECEIPT ? receipt(intent.get(), found.get().id(), now)
				: released(intent.get().siteGroup(), "Not taken on as one region (Architect has no record of it)", now);
			Steward.LOGGER.info("{}: the adoption {} closed at the reconcile: {}", s.id(), key, close);
			if (!Settlements.log(s.id(), entry).ok()) return false;
			s = Settlements.store().get(s.id()).orElse(s);
		}
		var adopted = standing(s);
		if (adopted.isPresent()) {
			String region = regionOf(adopted.get());
			Optional<RegionView> v = regions.get(region);
			boolean tombstone = v.map(x -> x.state() == RegionState.REMOVED).orElse(false);
			var sites = ArchitectApi.get().sites(server);
			boolean out = !adopted.get().siteIds().isEmpty() && adopted.get().siteIds().stream().allMatch(id -> sites.get(id).map(x -> x.region() == null).orElse(true));
			var close = standingClose(v.isPresent(), tombstone, out);
			if (close == Close.REMOVED) {
				List<String> gone = adopted.get().siteIds().stream().filter(id -> sites.get(id).isEmpty()).toList();
				if (!Settlements.log(s.id(), released(adopted.get().siteGroup(), "The town's region was removed", now)).ok()) return false;
				if (!gone.isEmpty() && !Settlements.log(s.id(), new Settlement.LogEntry(now, Kind.PROJECT_REMOVED, "Removed with the town's region (" + gone.size()
					+ " sites)", gone)).ok()) return false;
			} else if (close == Close.RELEASED) {
				if (!Settlements.log(s.id(), released(adopted.get().siteGroup(), "Let the town go as a region (Architect un-adopted it)", now)).ok()) return false;
			}
			if (close != Close.NONE) Steward.LOGGER.info("{}: the adoption of {} closed at the reconcile: {}", s.id(), region, close);
		}
		return true;
	}

	/**
	 * Undoes an adoption ({@code TOWN_ADOPTED region:}): {@code Regions.unadopt}, then {@code TOWN_RELEASED}; every site goes back to its old group, so
	 * the older builds' entries undo as before. {@code done} as {@link Undo#region}'s.
	 */
	public static boolean release(MinecraftServer server, Settlement s, Settlement.LogEntry entry, Consumer<String> say, Consumer<@Nullable Boolean> done) {
		String region = regionOf(entry);
		var regions = ArchitectApi.get().regions();
		var v = regions.get(region);
		// gone already (a lost unadopt answer, or removed): the reconcile closes it
		if (v.isEmpty() || v.get().state() == RegionState.REMOVED) {
			boolean ok = reconcile(server, s);
			say.accept(ok ? "The town was let go as a region already." : Undo.UNLOGGED);
			done.accept(ok);
			return ok;
		}
		say.accept("Letting the town go as one region (Architect): every building stays as it stands...");
		int session = Session.current();
		regions.unadopt(region).whenComplete((r, err) -> server.execute(() -> {
			if (!Session.is(session) || SettlementRunner.worldStopped(err)) { done.accept(null); return; }
			if (err != null) {
				// answered with an error after it went through (Architect deleted the record): the reconcile sees it
				if (regions.get(region).isEmpty() && reconcile(server, Settlements.store().get(s.id()).orElse(s))
					&& standing(Settlements.store().get(s.id()).orElse(s)).isEmpty()) {
					say.accept("Undone: the town is a street town again (each building back in its own build).");
					done.accept(true);
					return;
				}
				say.accept("Undo stopped: " + SettlementRunner.message(err) + ". Run /steward undo " + s.id() + " again once it is clear.");
				done.accept(false);
				return;
			}
			boolean logged = Settlements.log(s.id(), released(entry.siteGroup(), "Let the town go as a region (Architect un-adopted it)", System.currentTimeMillis())).ok();
			if (!logged) say.accept(Undo.UNLOGGED);
			say.accept("Undone: the town is a street town again (each building back in its own build).");
			done.accept(true);
		}));
		return true;
	}
}
