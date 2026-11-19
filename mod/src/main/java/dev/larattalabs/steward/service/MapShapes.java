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
		List<MapShape> out = new ArrayList<>();
		for (RegionView r : api.regions().list(s.owner())) {
			if (r.state() == RegionState.REMOVED || r.state() == RegionState.REMOVING) continue;
			var layout = api.regions().layout(r.id());
			if (layout.isEmpty()) continue;
			logOnChange(s.id(), r.id(), layout.get());
			for (MapShape m : shapes(layout.get(), r, secrets, typed)) {
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

	/** A layout's parts in one line: {@code kind part [minX,minZ..maxX,maxZ] ends=n}. Pure. */
	static String summary(RegionLayout layout) {
		StringBuilder b = new StringBuilder().append(layout.parts().size()).append(" part(s)");
		for (PartShape p : layout.parts()) {
			var x = p.box();
			b.append("; ").append(p.kind()).append(' ').append(p.part()).append(" [").append(x.minX()).append(',').append(x.minZ()).append("..").append(x.maxX())
				.append(',').append(x.maxZ()).append("] ends=").append(p.ends().size());
		}
		return b.toString();
	}

	/**
	 * One region's shapes: its lots (pads too), routes and connectors, the hidden ones left out; each with its status (a lot's, else its part's, else, in
	 * creative, built once the region stands). {@code typed}: the statuses are read (feature {@code buildStatus}). Pure.
	 */
	static List<MapShape> shapes(RegionLayout layout, RegionView region, Secrets secrets, boolean typed) {
		List<MapShape> out = new ArrayList<>();
		for (PartShape p : layout.parts()) {
			if (secrets.hides(p.part()) || p.lot() != null && secrets.hides(p.lot())) continue;
			String kind = kindOf(p.kind());
			if (kind == null) continue;
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
				st = region.build().parts().stream().filter(b -> b.part().equals(p.part())).map(PartBuild::status).findFirst().orElse(null);
			}
			String status = st != null ? st.state() : region.build() == null && (region.state() == RegionState.PLACED || region.state() == RegionState.PARTIAL)
				? BuildStatus.BUILT : BuildStatus.PLANNED;
			List<Integer> ends = new ArrayList<>();
			for (var e : p.ends()) {
				ends.add(e.getX());
				ends.add(e.getZ());
			}
			var b = p.box();
			out.add(new MapShape(p.part(), kind, p.lot(), b.minX(), b.minZ(), b.maxX(), b.maxZ(), ends, site, status, st == null ? null : st.reason()));
		}
		return out;
	}

	/** The map's kind of a plan part's kind: {@code lot}, {@code route}, {@code connector} (any {@code connector.*}); null for one the map leaves out. Pure. */
	static @Nullable String kindOf(String kind) {
		if ("lot".equals(kind) || "route".equals(kind)) return kind;
		if (kind.equals("connector") || kind.startsWith("connector.")) return "connector";
		return null;
	}
}
