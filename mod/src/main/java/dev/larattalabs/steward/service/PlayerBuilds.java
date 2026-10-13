package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.Build;
import dev.larattalabs.architect.api.BuildState;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The player's own builds in a settlement's claim (Architect PT, C32, API 1.24): houses, barns, farms, walls and paths a player built by hand, which
 * every Architect write keeps. Steward shows them, names them ("Noah's house") and gives them to the plan writer as features a plan may build round.
 * Little one-off blocks and touched-up terrain are not builds (Architect counts them as natural). Server thread.
 */
public final class PlayerBuilds {
	private PlayerBuilds() {
	}

	/** The builds meeting {@code s}'s claim, released ones left out (they are no longer kept); empty when Architect can't say. */
	public static List<Build> in(MinecraftServer server, Settlement s) {
		var level = Districts.level(server, s.claim().dimension());
		if (level == null) return List.of();
		int cx = s.claim().centerX(), cz = s.claim().centerZ(), r = s.claim().radius();
		try {
			return ArchitectApi.get().sites(server).builds(level.dimension(), new BoundingBox(cx - r, level.getMinY(), cz - r, cx + r, level.getMaxY(), cz + r))
				.stream().filter(b -> b.state() != BuildState.RELEASED).toList();
		} catch (RuntimeException | LinkageError e) {
			return List.of();
		}
	}

	/** "Noah's house", "a farm": the primary placer's name when they are online, the kind in plain words. */
	public static String name(MinecraftServer server, Build b) {
		String kind = kind(b);
		UUID who = b.primaryPlacer();
		var p = who == null ? null : server.getPlayerList().getPlayer(who);
		return p != null ? p.getGameProfile().name() + "'s " + kind : "a " + kind;
	}

	static String kind(Build b) {
		return switch (b.kind()) {
			case HOUSE -> "house";
			case BARN -> "barn";
			case FARM -> "farm";
			case WALL -> "wall";
			case TOWER -> "tower";
			case PATH -> "path";
			default -> "build";
		};
	}

	/** One line a build for the plan writer: its id, what it is, its size, where it is from the stone, its doors. */
	public static List<String> promptLines(MinecraftServer server, Settlement s, List<Build> builds) {
		List<String> out = new ArrayList<>();
		int cx = s.claim().centerX(), cz = s.claim().centerZ();
		for (Build b : builds) {
			BoundingBox bb = b.bounds();
			int dx = bb.getCenter().getX() - cx, dz = bb.getCenter().getZ() - cz;
			String where = (dx == 0 && dz == 0 ? "at the stone" : (Math.abs(dz) > 0 ? Math.abs(dz) + (dz < 0 ? " north" : " south") : "")
				+ (dx != 0 && dz != 0 ? " and " : "") + (dx != 0 ? Math.abs(dx) + (dx < 0 ? " west" : " east") : "") + " of the stone");
			String doors = b.entrances().isEmpty() ? "no door" : b.entrances().size() == 1 ? "a door facing " + b.entrances().get(0).facing().getName()
				: b.entrances().size() + " doors (door_1 facing " + b.entrances().get(0).facing().getName() + ")";
			out.add(String.format(Locale.ROOT, "- %s: %s, %dx%d and %d high, %s, %s%s", b.id(), name(server, b), bb.getXSpan(), bb.getZSpan(), bb.getYSpan(),
				where, doors, b.state() == BuildState.PROVISIONAL ? " (still being built)" : ""));
		}
		return out;
	}

	/** For the settlement screen: "Noah's house, a farm". */
	public static List<String> viewLines(MinecraftServer server, List<Build> builds) {
		return builds.stream().map(b -> name(server, b)).toList();
	}
}
