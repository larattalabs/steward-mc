package dev.larattalabs.steward.gateway;

import dev.larattalabs.architect.api.LotSpec;
import dev.larattalabs.steward.layout.VillageLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A district region's planned lots as Steward's plan (docs/DISTRICTS.md section 4, "Lots"): as the approved-plan adapter ({@link PPlan#toPlan}) reads an
 * artifact, each lot keeps the front Architect gave it (any of the four) and its height cap ({@link LotSpec#max()}); the flat village's adapter
 * ({@link VPlan#toPlan}) turns every front north or south and has no cap. Pure.
 */
public final class DPlan {
	private DPlan() {
	}

	public static VillageLayout.Plan toPlan(List<LotSpec> placed, List<VillageLayout.LotSpec> specs) {
		Map<String, VillageLayout.LotSpec> byId = new HashMap<>();
		for (var s : specs) byId.put(s.id(), s);
		List<VillageLayout.Lot> lots = new ArrayList<>();
		List<Integer> zs = new ArrayList<>(), ys = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
		for (LotSpec l : placed) {
			var spec = byId.get(l.id());
			if (spec == null) continue;
			seen.add(l.id());
			var b = l.lot();
			VillageLayout.Front front = switch (l.front() == null ? net.minecraft.core.Direction.SOUTH : l.front()) {
				case NORTH -> VillageLayout.Front.NORTH;
				case EAST -> VillageLayout.Front.EAST;
				case WEST -> VillageLayout.Front.WEST;
				default -> VillageLayout.Front.SOUTH;
			};
			int maxHeight = l.max() != null && l.max().y() > 0 ? l.max().y() : b.getYSpan();
			lots.add(new VillageLayout.Lot(l.id(), spec.type(), b.minX(), b.minZ(), b.getXSpan(), b.getZSpan(), front, l.floorY(), spec.role(), spec.notes(),
				spec.landmark(), spec.placement(), maxHeight));
			zs.add(switch (front) {
				case NORTH -> b.minZ() - 1;
				case SOUTH -> b.maxZ() + 1;
				default -> (b.minZ() + b.maxZ()) / 2;
			});
			ys.add(l.floorY());
			x0 = Math.min(x0, b.minX());
			x1 = Math.max(x1, b.maxX());
		}
		List<VillageLayout.LotSpec> unplaced = specs.stream().filter(s -> !seen.contains(s.id())).toList();
		zs.sort(Integer::compare);
		ys.sort(Integer::compare);
		return new VillageLayout.Plan(List.copyOf(lots), zs.isEmpty() ? 0 : zs.get(zs.size() / 2), lots.isEmpty() ? 0 : x0, lots.isEmpty() ? 0 : x1,
			ys.isEmpty() ? 64 : ys.get(ys.size() / 2), unplaced);
	}
}
