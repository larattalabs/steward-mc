package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.LoadPolicy;
import dev.larattalabs.architect.api.Sample;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.DistrictSite;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * What the land round a settlement's stone is like, in a line for the card call (docs/PLAN.md "Zones": the card call is told what is there, so "the jungle
 * side" or "by the lake" means something): each side's biomes, where the water is, which way the ground rises. Surveyed in loaded chunks only (every 8th
 * column). Server thread.
 */
public final class Land {
	/** The survey's step: enough to tell biomes, water and height apart. */
	static final int RESOLUTION = 8;
	private static final String[] SIDES = {"north", "east", "south", "west"};

	private Land() {
	}

	/** Surveys the land round {@code s}'s stone and gives the summary to {@code then} on the server thread (null when nothing could be read). */
	public static void around(MinecraftServer server, Settlement s, Consumer<@Nullable String> then) {
		var level = Districts.level(server, s.claim().dimension());
		if (level == null) {
			then.accept(null);
			return;
		}
		int cx = s.claim().centerX(), cz = s.claim().centerZ(), r = Math.max(48, s.claim().radius());
		int h = r / 2;
		// each side's half of the claim, and the middle round the stone
		int[][] boxes = {{cx - r, cz - r, cx + r, cz - h}, {cx + h, cz - r, cx + r, cz + r}, {cx - r, cz + h, cx + r, cz + r}, {cx - r, cz - r, cx - h, cz + r},
			{cx - h, cz - h, cx + h, cz + h}};
		List<CompletableFuture<Sample>> samples = new ArrayList<>();
		for (int[] b : boxes) {
			samples.add(ArchitectApi.get().survey().sample(level, new BoundingBox(b[0], level.getMinY(), b[1], b[2], level.getMaxY(), b[3]), RESOLUTION, LoadPolicy.LOADED_ONLY));
		}
		int session = Session.current();
		CompletableFuture.allOf(samples.toArray(new CompletableFuture[0])).whenComplete((v, err) -> server.execute(() -> {
			if (!Session.is(session)) return;
			try {
				Map<String, DistrictSite.Profile> sides = new LinkedHashMap<>();
				DistrictSite.Profile middle = null;
				for (int i = 0; i < samples.size(); i++) {
					var f = samples.get(i);
					if (f.isCompletedExceptionally()) continue;
					var p = Districts.profile(f.join());
					if (i < 4) sides.put(SIDES[i], p);
					else middle = p;
				}
				then.accept(summary(sides, middle));
			} catch (RuntimeException e) {
				Steward.LOGGER.warn("land survey of {}: {}", s.id(), e.toString());
				then.accept(null);
			}
		}));
	}

	/** The summary line: each side's main biomes, where the water is, which way the ground rises. Null when too little was read. Pure. */
	static @Nullable String summary(Map<String, DistrictSite.Profile> sides, DistrictSite.@Nullable Profile middle) {
		List<String> parts = new ArrayList<>();
		String wettest = null;
		double wet = 0;
		String highest = null, lowest = null;
		double hi = -1e9, lo = 1e9;
		for (var e : sides.entrySet()) {
			var p = e.getValue();
			if (p.known() < DistrictSite.MIN_KNOWN) continue;
			List<String> b = new ArrayList<>();
			p.biomes().entrySet().stream().sorted((a, c) -> Double.compare(c.getValue(), a.getValue())).limit(2).filter(x -> x.getValue() >= 0.15)
				.forEach(x -> b.add(x.getKey().replace('_', ' ') + " " + Math.round(x.getValue() * 100) + "%"));
			if (!b.isEmpty()) parts.add(e.getKey() + ": " + String.join(", ", b));
			if (p.water() > wet) { wet = p.water(); wettest = e.getKey(); }
			if (p.height() > hi) { hi = p.height(); highest = e.getKey(); }
			if (p.height() < lo) { lo = p.height(); lowest = e.getKey(); }
		}
		if (parts.isEmpty()) return null;
		StringBuilder out = new StringBuilder(String.join("; ", parts)).append(".");
		if (wettest != null && wet >= 0.05) out.append(" Most water to the ").append(wettest).append(" (").append(Math.round(wet * 100)).append("% of that side).");
		else out.append(" Little or no water nearby.");
		if (middle != null && middle.known() >= DistrictSite.MIN_KNOWN && hi - lo >= 6) {
			out.append(" The ground rises to the ").append(highest).append(" (").append(Math.round(hi - middle.height())).append(" above the stone) and falls to the ")
				.append(lowest).append(".");
		} else out.append(" The ground is fairly level.");
		return out.toString();
	}
}
