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

	/**
	 * Takes a player's build on as one of {@code s}'s buildings (Architect PT2): Architect captures it as version 1 of its own design and site, writing no
	 * block; the history records it, so an undo lets it go (the build stays). Then updates, change requests ("add a floor") and reverts work on it as on
	 * any building. The asking player is the consent's actor (a placer of it, or an op). Server thread; {@code say} gets the result.
	 */
	public static void adopt(MinecraftServer server, net.minecraft.server.level.ServerPlayer player, Settlement s, String buildId, java.util.function.Consumer<String> say) {
		var sites = ArchitectApi.get().sites(server);
		var build = sites.build(buildId);
		if (build.isEmpty() || in(server, s).stream().noneMatch(b -> b.id().equals(build.get().id()))) {
			say.accept("No build " + buildId + " in " + s.name() + "'s claim (see /steward builds " + s.id() + ").");
			return;
		}
		Build b = build.get();
		var ext = new com.google.gson.JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:role", by(server, b) + "'s " + kind(b));
		ext.addProperty("steward_mc:build", b.id());
		int session = Session.current();
		sites.adopt(b.id(), s.owner(), new dev.larattalabs.architect.api.BuildConsent(java.util.Set.of(b.id()), player.getUUID()), ext).whenComplete((r, err) -> {
			if (!Session.is(session)) return;
			if (err != null) {
				Throwable c = err.getCause() != null ? err.getCause() : err;
				String why = String.valueOf(c.getMessage());
				say.accept(why.startsWith("provisional") ? "That build is still going up: the steward takes it on once you've finished it (a few quiet minutes)."
					: "The steward can't take that build on: " + why);
				return;
			}
			var entry = Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.BUILD_ADOPTED, "Took on " + by(server, b) + "'s " + kind(b) + " (" + b.id() + ")",
				List.of(new Settlement.SiteChange(r.siteId(), null, 0, r.version())), null, Settlement.Outcome.DONE);
			boolean logged = Settlements.log(s.id(), entry).ok();
			say.accept("The steward now looks after " + by(server, b) + "'s " + kind(b) + " (" + r.siteId() + ", " + r.cells() + " blocks): ask for changes to it, like a"
				+ " new floor. Undo lets it go, and it stays as it stands." + (logged ? "" : " " + Undo.UNLOGGED));
		});
	}

	/** "Noah's house", "a farm": the primary placer's name when they are online, the kind in plain words. */
	public static String name(MinecraftServer server, Build b) {
		String kind = kind(b);
		UUID who = b.primaryPlacer();
		var p = who == null ? null : server.getPlayerList().getPlayer(who);
		return p != null ? p.getGameProfile().name() + "'s " + kind : "a " + kind;
	}

	/** For the screens: "Hand-built house", or "Hand-built" when Architect can't tell what it is. */
	public static String title(Build b) {
		String kind = kind(b);
		return "build".equals(kind) ? "Hand-built" : "Hand-built " + kind;
	}

	/** Who built it: the primary placer's name when they are online, else "a player". */
	public static String by(MinecraftServer server, Build b) {
		UUID who = b.primaryPlacer();
		var p = who == null ? null : server.getPlayerList().getPlayer(who);
		return p != null ? p.getGameProfile().name() : "a player";
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
				where, doors, b.state() == BuildState.PROVISIONAL ? " (still being built)" : b.siteId() != null ? " (the steward looks after it as " + b.siteId() + ")" : ""));
		}
		return out;
	}

}
