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
	List<Building> buildings, int roads, List<Op> history, int historyTotal, double weeklyUsd, double spentWeekUsd) {

	/** How many operations are sent (newest first); the rest stay in the log. */
	public static final int MAX_HISTORY = 60;

	private static final Gson GSON = new Gson();

	public record ClaimInfo(String dimension, int centerX, int centerZ, int radius, int side, String size) {}

	/**
	 * One placed building. {@code version} the version of its design it stands at, {@code head} the newest; {@code edits} cells the player changed that updates
	 * keep; {@code state} Architect's site state (planned, building, built, ...); the box is its footprint in world x/z.
	 */
	public record Building(String siteId, String role, @Nullable String lot, String entry, int version, int head, int edits, String state, boolean updating,
		int minX, int minZ, int maxX, int maxZ, List<String> ops, int previous, int heldFrom, int minY, int maxY) {
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
		var c = s.claim();
		ClaimInfo claim = new ClaimInfo(c.dimension(), c.centerX(), c.centerZ(), c.radius(), ClaimRules.side(c.radius()), ClaimRules.sizeOf(c.radius()));
		List<Settlement.LogEntry> log = s.log();
		List<Building> buildings = new ArrayList<>();
		int roads = 0;
		for (Site x : sites) {
			if (!"building".equals(x.kind())) {
				roads++;
				continue;
			}
			List<String> ops = log.stream().filter(e -> e.siteIds().contains(x.id())).map(Settlement.LogEntry::op).toList();
			String role = x.role() != null && !x.role().isBlank() ? x.role() : x.lot() != null ? x.lot() : x.entry();
			buildings.add(new Building(x.id(), role, x.lot(), x.entry(), x.version(), x.head(), x.deviations(), x.state(), x.updating(), x.minX(), x.minZ(), x.maxX(),
				x.maxZ(), ops, x.previous(), s.revertedFrom(x.id()), x.minY(), x.maxY()));
		}
		buildings.sort(Comparator.comparing(Building::role).thenComparing(Building::siteId));
		List<Op> history = new ArrayList<>();
		for (int i = log.size() - 1; i >= 0 && history.size() < MAX_HISTORY; i--) {
			Settlement.LogEntry e = log.get(i);
			history.add(new Op(e.op(), e.at(), e.kind().name(), e.text(), e.outcome().name(), e.recovery().name(), e.siteIds(), e.changes()));
		}
		return new SettlementView(s.id(), s.name(), s.described(), busy, claim, s.permission().name(), s.difficulty().name(), List.copyOf(buildings), roads,
			List.copyOf(history), log.size(), s.autonomy().weeklyUsd(), s.autonomy().spentInWeek(System.currentTimeMillis()));
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
