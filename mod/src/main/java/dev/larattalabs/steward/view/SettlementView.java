package dev.larattalabs.steward.view;

import com.google.gson.Gson;
import dev.larattalabs.steward.model.ClaimRules;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the settlement screen and the building panel show (docs/PLAN.md "Interface", phase 3a): the settlement's buildings as Architect has them, its change
 * log as operations, its claim and settings. Built on the server from the settlement and its sites, sent to the client as JSON. Pure: the sites come in as
 * {@link Site} so it is tested without a game.
 */
public record SettlementView(String id, String name, boolean described, boolean busy, ClaimInfo claim, String permission, String difficulty,
	List<Building> buildings, int roads, List<Op> history, int historyTotal, double weeklyUsd, double spentWeekUsd, List<DistrictInfo> districts, List<AreaInfo> areas,
	List<DistrictInfo> proposed, List<String> stockpile, List<PlayerBuildInfo> playerBuilds, dev.larattalabs.steward.inbox.@org.jspecify.annotations.Nullable Attention attention) {
	public SettlementView {
		districts = districts == null ? List.of() : districts;
		// districts waiting in the inbox, where they would go (survey mode previews them)
		proposed = proposed == null ? List.of() : proposed;
		// a Supplied settlement's stockpile: what it holds and what its builds still need (phase 4), empty without one
		stockpile = stockpile == null ? List.of() : stockpile;
		// the player's own builds in the claim (Architect PT): kept as they are by every build
		playerBuilds = playerBuilds == null ? List.of() : playerBuilds;
		areas = areas == null ? List.of() : areas;
	}

	/**
	 * A player's own build in the claim (Architect PT, C32): its id, its title ("Hand-built house"), who built it (its primary placer), its box, and whether
	 * it is still being built.
	 */
	public record PlayerBuildInfo(String id, String title, String by, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean provisional) {}

	/** A district (phase 3f): where it lies ({@code direction} N, E, S, W), its square, and how many buildings stand in it. */
	/** A district: {@code kind} and {@code state} as saved ({@code square} standing for the ones before them); {@code note}: what the player should know of it now. */
	public record DistrictInfo(String id, String name, String direction, int centerX, int centerZ, int radius, int side, int buildings, int roads, String kind,
		String state, @org.jspecify.annotations.Nullable String note) {
		public DistrictInfo {
			kind = kind == null ? "square" : kind;
			state = state == null ? "standing" : state;
		}

		public DistrictInfo(String id, String name, String direction, int centerX, int centerZ, int radius, int side, int buildings, int roads) {
			this(id, name, direction, centerX, centerZ, radius, side, buildings, roads, "square", "standing", null);
		}
	}

	/** An area the player protected (columns x0..x1, z0..z1). */
	public record AreaInfo(String id, String label, int x0, int z0, int x1, int z1) {}

	/** How many operations are sent (newest first); the rest stay in the log. */
	public static final int MAX_HISTORY = 60;

	private static final Gson GSON = new Gson();

	public record ClaimInfo(String dimension, int centerX, int centerZ, int radius, int side, String size) {}

	/**
	 * One placed building. {@code version} the version of its design it stands at, {@code head} the newest; {@code edits} cells the player changed that updates
	 * keep; {@code state} Architect's site state (planned, building, built, ...); the box is its footprint in world x/z.
	 */
	public record Building(String siteId, String role, @Nullable String lot, String entry, int version, int head, int edits, String state, boolean updating,
		int minX, int minZ, int maxX, int maxZ, List<String> ops, int previous, int heldFrom, int minY, int maxY, @Nullable String district) {
		/** A newer version the player has not reverted away from. */
		public boolean updateAvailable() {
			return head > version && head > heldFrom;
		}

		/** It stood at an earlier version it can go back to. */
		public boolean revertible() {
			return previous > 0 && previous != version;
		}
	}

	/** One operation of the change log, as the History tab shows it. */
	public record Op(String op, long at, String kind, String text, String outcome, String recovery, List<String> sites, List<Settlement.SiteChange> changes) {}

	/** A site as the server reads it from Architect ({@code SiteView}), reduced to what the view needs. */
	/** {@code previous}: the version it stood at before this one (0: none, it was placed at this one). */
	public record Site(String id, String kind, @Nullable String itemKey, @Nullable String role, @Nullable String lot, String entry, int version, int head, int deviations,
		String state, boolean updating, int minX, int minZ, int maxX, int maxZ, int previous, int minY, int maxY) {
		public Site(String id, String kind, @Nullable String itemKey, @Nullable String role, @Nullable String lot, String entry, int version, int head, int deviations,
			String state, boolean updating, int minX, int minZ, int maxX, int maxZ, int previous) {
			this(id, kind, itemKey, role, lot, entry, version, head, deviations, state, updating, minX, minZ, maxX, maxZ, previous, 0, 0);
		}
	}

	/** Roads (the street) are counted, not listed: the screen is about buildings. */
	public static SettlementView of(Settlement s, boolean busy, List<Site> sites) {
		return of(s, busy, sites, List.of());
	}

	/** As above, with the squares of the districts waiting in the inbox ({@code proposed}: their proposal's key as id, its title as name). */
	public static SettlementView of(Settlement s, boolean busy, List<Site> sites, List<DistrictInfo> proposed) {
		return of(s, busy, sites, proposed, List.of());
	}

	/** As above, with the stockpile's bill in lines. */
	public static SettlementView of(Settlement s, boolean busy, List<Site> sites, List<DistrictInfo> proposed, List<String> stockpile) {
		return of(s, busy, sites, proposed, stockpile, List.of());
	}

	/** As above, with the player's own builds in the claim. */
	public static SettlementView of(Settlement s, boolean busy, List<Site> sites, List<DistrictInfo> proposed, List<String> stockpile,
		List<PlayerBuildInfo> playerBuilds) {
		return of(s, busy, sites, proposed, stockpile, playerBuilds, null);
	}

	/** As above, with what the settlement asks of the player ({@code attention}: the board shows the line the nameplate and the inbox show). */
	public static SettlementView of(Settlement s, boolean busy, List<Site> sites, List<DistrictInfo> proposed, List<String> stockpile,
		List<PlayerBuildInfo> playerBuilds, dev.larattalabs.steward.inbox.@org.jspecify.annotations.Nullable Attention attention) {
		return of(s, busy, sites, proposed, stockpile, playerBuilds, attention, java.util.Map.of());
	}

	/** As above, with a note for some districts by id ("its road to the town is gone"). */
	public static SettlementView of(Settlement s, boolean busy, List<Site> sites, List<DistrictInfo> proposed, List<String> stockpile,
		List<PlayerBuildInfo> playerBuilds, dev.larattalabs.steward.inbox.@org.jspecify.annotations.Nullable Attention attention, java.util.Map<String, String> notes) {
		var c = s.claim();
		ClaimInfo claim = new ClaimInfo(c.dimension(), c.centerX(), c.centerZ(), c.radius(), ClaimRules.side(c.radius()), ClaimRules.sizeOf(c.radius()));
		List<Settlement.LogEntry> log = s.log();
		List<Building> buildings = new ArrayList<>();
		java.util.Map<String, Integer> perDistrict = new java.util.HashMap<>();
		int roads = 0;
		for (Site x : sites) {
			if (!"building".equals(x.kind())) {
				roads++;
				continue;
			}
			List<String> ops = log.stream().filter(e -> e.siteIds().contains(x.id())).map(Settlement.LogEntry::op).toList();
			String role = x.role() != null && !x.role().isBlank() ? x.role() : x.lot() != null ? x.lot() : x.entry();
			var at = s.districtAt(c.dimension(), (x.minX() + x.maxX()) / 2, (x.minZ() + x.maxZ()) / 2);
			at.ifPresent(d -> perDistrict.merge(d.id(), 1, Integer::sum));
			String in = at.map(Settlement.District::name).orElse(null);
			buildings.add(new Building(x.id(), role, x.lot(), x.entry(), x.version(), x.head(), x.deviations(), x.state(), x.updating(), x.minX(), x.minZ(), x.maxX(),
				x.maxZ(), ops, x.previous(), s.revertedFrom(x.id()), x.minY(), x.maxY(), in));
		}
		buildings.sort(Comparator.comparing(Building::role).thenComparing(Building::siteId));
		List<Op> history = new ArrayList<>();
		for (int i = log.size() - 1; i >= 0 && history.size() < MAX_HISTORY; i--) {
			Settlement.LogEntry e = log.get(i);
			history.add(new Op(e.op(), e.at(), e.kind().name(), e.text(), e.outcome().name(), e.recovery().name(), e.siteIds(), e.changes()));
		}
		List<DistrictInfo> districts = new ArrayList<>();
		for (Settlement.District d : s.lands().districts()) {
			var dc = d.claim();
			int n = perDistrict.getOrDefault(d.id(), 0);
			districts.add(new DistrictInfo(d.id(), d.name(), d.direction(), dc.centerX(), dc.centerZ(), dc.radius(), ClaimRules.side(dc.radius()), n, d.roads().size(),
				d.kind(), d.state(), notes.get(d.id())));
		}
		List<AreaInfo> areas = s.lands().protect().stream().map(a -> new AreaInfo(a.id(), a.label(), a.x0(), a.z0(), a.x1(), a.z1())).toList();
		return new SettlementView(s.id(), s.name(), s.described(), busy, claim, s.permission().name(), s.difficulty().name(), List.copyOf(buildings), roads,
			List.copyOf(history), log.size(), s.autonomy().weeklyUsd(), s.autonomy().spentInWeek(System.currentTimeMillis()), List.copyOf(districts), areas, List.copyOf(proposed), List.copyOf(stockpile), List.copyOf(playerBuilds), attention);
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	public static SettlementView fromJson(String json) {
		return GSON.fromJson(json, SettlementView.class);
	}

	public @Nullable Building building(String siteId) {
		return buildings.stream().filter(b -> b.siteId().equals(siteId)).findFirst().orElse(null);
	}

	/** The operations that touched a building, newest first (from the history sent). */
	public List<Op> opsOf(String siteId) {
		return history.stream().filter(o -> o.sites().contains(siteId)).toList();
	}
}
