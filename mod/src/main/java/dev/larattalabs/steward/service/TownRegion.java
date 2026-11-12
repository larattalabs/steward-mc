package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.steward.gateway.BatchPlanner;
import dev.larattalabs.steward.gateway.VPlan;
import dev.larattalabs.steward.model.Settlement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Which region is the settlement's town (docs/DISTRICTS.md section 4): the newest standing region of a placed build that is neither a district's (beside a
 * region, or named by a district) nor a waiting part's ({@link PendingOffers#TAG} on its build tag). One rule for every caller: founding a district, the
 * waiting parts, the town's street. Empty when the town region is gone (undone) or the town was never a region.
 */
public final class TownRegion {
	/** What the rule reads of a region. */
	record Seen(boolean standing, @Nullable String beside, @Nullable String buildTag) {}

	private TownRegion() {
	}

	public static Optional<String> of(Settlement s) {
		var regions = ArchitectApi.get().regions();
		return pick(s, id -> regions.get(id).map(v -> new Seen(v.state() != RegionState.REMOVED && v.state() != RegionState.REMOVING, v.beside(),
			v.ext().has(BatchPlanner.BUILD_EXT) && v.ext().get(BatchPlanner.BUILD_EXT).isJsonPrimitive() ? v.ext().get(BatchPlanner.BUILD_EXT).getAsString() : null)));
	}

	static Optional<String> pick(Settlement s, Function<String, Optional<Seen>> view) {
		Set<String> districts = new HashSet<>();
		for (Settlement.District d : s.lands().districts()) if (d.region() != null) districts.add(d.region());
		List<Settlement.LogEntry> log = s.log();
		// a street town taken on as a region (docs/GROWTH.md, slice B): its adoption names the region while no later release does
		Set<String> released = new HashSet<>();
		for (int i = log.size() - 1; i >= 0; i--) {
			Settlement.LogEntry e = log.get(i);
			if (e.kind() == Settlement.Kind.TOWN_RELEASED && e.siteGroup() != null) released.add(e.siteGroup());
			boolean adopted = e.kind() == Settlement.Kind.TOWN_ADOPTED && e.siteGroup() != null && !released.contains(e.siteGroup());
			if (e.kind() != Settlement.Kind.PROJECT_PLACED && !adopted) continue;
			String id = VPlan.regionOf(e.siteGroup());
			if (id == null || districts.contains(id)) continue;
			Optional<Seen> v = view.apply(id);
			if (v.isEmpty() || !v.get().standing() || v.get().beside() != null) continue;
			if (v.get().buildTag() != null && v.get().buildTag().startsWith(PendingOffers.TAG)) continue;
			return Optional.of(id);
		}
		return Optional.empty();
	}
}
