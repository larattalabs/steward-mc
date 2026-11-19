package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.BuildStatus;
import dev.larattalabs.architect.api.LotState;
import dev.larattalabs.architect.api.PartBuild;
import dev.larattalabs.architect.api.PartShape;
import dev.larattalabs.architect.api.RegionLayout;
import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.architect.api.RegionView;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.view.SettlementView.MapShape;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * (slice 8bS) The settlement map's shapes and states from Architect 8b: its regions' pads, routes and connectors from {@code Regions.layout} (feature
 * {@code regionLayout}), each filled by its {@code BuildStatus} (feature {@code buildStatus}: a lot's {@code LotState.status}, a part's
 * {@code PartBuild.status}). A hidden part (S-7c-4) is dropped before anything goes to the client. Server thread.
 */
final class MapShapes {
	static final String STATUS_FEATURE = "buildStatus";
	static final String LAYOUT_FEATURE = "regionLayout";
	/** (slice LYW) Architect's walls in the layout: walls, gates and towers, widths, a lot's wall (API 1.70.0). */
	static final String WALLS_FEATURE = "layoutWalls";
	/** The most shapes sent (a bundled program's routes and lots fit well under it). */
	static final int MAX = 400;

	private MapShapes() {
	}

	/** A site's {@code BuildStatus} when this Architect gives one, else null (the row reads its state alone). */
	static @Nullable BuildStatus status(SiteView v) {
		try {
			return ArchitectApi.get().features().contains(STATUS_FEATURE) ? v.status() : null;
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** The settlement's map shapes over all its standing regions; null when this Architect gives no layout. */
	static @Nullable List<MapShape> of(Settlement s) {
		var api = ArchitectApi.get();
		if (!api.features().contains(LAYOUT_FEATURE)) return null;
		var secrets = Secrets.ofSettlement(s.id());
		boolean typed = api.features().contains(STATUS_FEATURE);
		boolean walls = api.features().contains(WALLS_FEATURE);
		List<MapShape> out = new ArrayList<>();
		for (RegionView r : api.regions().list(s.owner())) {
			if (r.state() == RegionState.REMOVED || r.state() == RegionState.REMOVING) continue;
			var layout = api.regions().layout(r.id());
			if (layout.isEmpty()) continue;
			logOnChange(s.id(), r.id(), layout.get());
			for (MapShape m : shapes(layout.get(), r, secrets, typed, walls)) {
				if (out.size() >= MAX) return out;
				out.add(m);
			}
		}
		return out;
	}

	/** The last layout summary logged per region, so a screen's refreshes don't repeat it. */
	private static final java.util.Map<String, String> LOGGED = new java.util.concurrent.ConcurrentHashMap<>();

	/** Logs what {@code Regions.layout} gave a region (each part's kind, id, box and end count) when it changed: what the map can draw. */
	private static void logOnChange(String settlement, String region, RegionLayout layout) {
		String line = summary(layout);
		if (line.equals(LOGGED.put(region, line))) return;
		dev.larattalabs.steward.Steward.LOGGER.info("map layout of {} {}: {}", settlement, region, line);
	}

	/** A layout's parts in one line: {@code kind part [minX,minZ..maxX,maxZ] ends=n} (and {@code w=n}, {@code wall=id} when given). Pure. */
	static String summary(RegionLayout layout) {
		StringBuilder b = new StringBuilder().append(layout.parts().size()).append(" part(s)");
		for (PartShape p : layout.parts()) {
			var x = p.box();
			b.append("; ").append(p.kind()).append(' ').append(p.part()).append(" [").append(x.minX()).append(',').append(x.minZ()).append("..").append(x.maxX())
				.append(',').append(x.maxZ()).append("] ends=").append(p.ends().size());
			if (p.width() > 0) b.append(" w=").append(p.width());
			if (p.wall() != null) b.append(" wall=").append(p.wall()).append(p.wallAt() != null ? "@" + p.wallAt() : "");
		}
		return b.toString();
	}

	/** {@link #shapes(RegionLayout, RegionView, Secrets, boolean, boolean)} without walls (before {@code layoutWalls}). Pure. */
	static List<MapShape> shapes(RegionLayout layout, RegionView region, Secrets secrets, boolean typed) {
		return shapes(layout, region, secrets, typed, false);
	}

	/**
	 * One region's shapes: its lots (pads too), routes and connectors, the hidden ones left out; each with its status (a lot's, else its part's, else, in
	 * creative, built once the region stands). {@code typed}: the statuses are read (feature {@code buildStatus}). (slice LYW) {@code walls}: its walls,
	 * gates and towers, each shape's width and wall are read (feature {@code layoutWalls}), and a route given twice (a mixed route's road pieces) is
	 * kept once by its box, at the wider width. Pure.
	 */
	static List<MapShape> shapes(RegionLayout layout, RegionView region, Secrets secrets, boolean typed, boolean walls) {
		List<MapShape> out = new ArrayList<>();
		java.util.Map<String, Integer> routes = new java.util.HashMap<>();
		for (PartShape p : layout.parts()) {
			if (secrets.hides(p.part()) || p.lot() != null && secrets.hides(p.lot())) continue;
			String kind = kindOf(p.kind(), walls);
			if (kind == null) continue;
			var b = p.box();
			int width = walls ? Math.max(0, p.width()) : 0;
			if (walls && "route".equals(kind)) {
				String key = b.minX() + "," + b.minY() + "," + b.minZ() + ".." + b.maxX() + "," + b.maxY() + "," + b.maxZ();
				Integer seen = routes.get(key);
				if (seen != null) {
					MapShape first = out.get(seen);
					if (width > first.width()) out.set(seen, new MapShape(first.part(), first.kind(), first.lot(), first.minX(), first.minZ(), first.maxX(), first.maxZ(),
						first.ends(), first.site(), first.status(), first.reason(), width, first.wall(), first.wallAt()));
					continue;
				}
				routes.put(key, out.size());
			}
			String site = null;
			BuildStatus st = null;
			if ("lot".equals(kind)) {
				String id = p.lot() != null ? p.lot() : p.part();
				LotState ls = region.lots().stream().filter(l -> l.id().equals(id)).findFirst().orElse(null);
				if (ls != null) {
					site = ls.siteId();
					if (typed) st = ls.status();
				}
			}
			if (st == null && typed && region.build() != null) {
				st = region.build().parts().stream().filter(x -> x.part().equals(p.part())).map(PartBuild::status).findFirst().orElse(null);
			}
			String status = st != null ? st.state() : region.build() == null && (region.state() == RegionState.PLACED || region.state() == RegionState.PARTIAL)
				? BuildStatus.BUILT : BuildStatus.PLANNED;
			List<Integer> ends = new ArrayList<>();
			for (var e : p.ends()) {
				ends.add(e.getX());
				ends.add(e.getZ());
			}
			out.add(new MapShape(p.part(), kind, p.lot(), b.minX(), b.minZ(), b.maxX(), b.maxZ(), ends, site, status, st == null ? null : st.reason(), width,
				walls ? p.wall() : null, walls ? p.wallAt() : null));
		}
		return out;
	}

	/** {@link #kindOf(String, boolean)} without walls. Pure. */
	static @Nullable String kindOf(String kind) {
		return kindOf(kind, false);
	}

	/**
	 * The map's kind of a plan part's kind: {@code lot}, {@code route}, {@code connector} (any other {@code connector.*}); (slice LYW) with {@code walls}
	 * also {@code wall} ({@code connector.wall}), {@code gate} ({@code connector.gate}) and {@code tower}; null for one the map leaves out. Pure.
	 */
	static @Nullable String kindOf(String kind, boolean walls) {
		if ("lot".equals(kind) || "route".equals(kind)) return kind;
		if (walls) {
			if ("connector.wall".equals(kind)) return "wall";
			if ("connector.gate".equals(kind)) return "gate";
			if ("tower".equals(kind)) return "tower";
		}
		if (kind.equals("connector") || kind.startsWith("connector.")) return "connector";
		return null;
	}
}
