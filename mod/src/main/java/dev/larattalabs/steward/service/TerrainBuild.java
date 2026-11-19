package dev.larattalabs.steward.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.larattalabs.architect.api.PartBuild;
import dev.larattalabs.architect.api.RegionBuild;
import dev.larattalabs.steward.model.Words;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * (slice 8aS) Steward's side of Architect 8a's staged terrain (ST) and half-built safety (HB), since Architect v0.79.0 (API 1.68.0, feature
 * {@link #FEATURE}): a survival region's land (relief, pads' cut and fill, forms, scatter) is built over time and free, at
 * {@link RegionBuild#terrainPerTick} cells a tick while its land is loaded. What the steward says of it (how long the ground takes, a part waiting on
 * its ground) and which boxes the steward NPC keeps off while they are built. Pure.
 */
public final class TerrainBuild {
	/** Architect's feature: staged terrain is built as targets, and {@link RegionBuild#terrainPerTick} is the world's rate. */
	public static final String FEATURE = "terrainConstruction";
	/** {@code architect_mc:waitsFor.kind} of a wait on staged terrain (Architect's {@code RegionTargets.WAIT_KIND_TERRAIN}, kept stable for Steward). */
	static final String WAIT_KIND = "terrain";
	/** A part's {@link PartBuild#state} while it is being built (the NPC keeps off its box, S-8a-1). */
	static final String BUILDING = "building";

	private TerrainBuild() {
	}

	/** The staged terrain cells of {@code b} still to build (every part's {@code terrainCells - terrainBuilt}, never below 0). */
	static long remaining(@Nullable RegionBuild b) {
		if (b == null) return 0;
		long n = 0;
		for (PartBuild p : b.parts()) n += Math.max(0, p.terrainCells() - p.terrainBuilt());
		return n;
	}

	/** Whole minutes {@code cells} take at {@code perTick} cells a tick (20 ticks a second), rounded up, at least 1; 0 when there is nothing or no rate. */
	static long minutes(long cells, int perTick) {
		if (cells <= 0 || perTick <= 0) return 0;
		long perMinute = (long) perTick * 20 * 60;
		return Math.max(1, (cells + perMinute - 1) / perMinute);
	}

	/**
	 * How long the ground of a build from materials takes, said when its construction sites stand: "The ground is shaped first, and free: 7200 blocks of
	 * earth, about 8 min while its land is loaded." Null without the feature, without a rate (an older Architect's 0), or with no staged terrain left.
	 */
	static @Nullable String timeLine(@Nullable RegionBuild b, boolean feature) {
		if (!feature || b == null || b.terrainPerTick() <= 0) return null;
		long left = remaining(b);
		if (left <= 0) return null;
		return "The ground is shaped first, and free: " + Words.count(left, "block of earth", "blocks of earth") + ", about " + minutes(left, b.terrainPerTick())
			+ " min while its land is loaded.";
	}

	/** The {@code architect_mc:waitsFor} of an item's ext when it is a wait on staged terrain ({@code kind: "terrain"}), else null. */
	static @Nullable JsonObject terrainWait(@Nullable JsonObject ext) {
		if (ext == null || !(ext.get("architect_mc:waitsFor") instanceof JsonObject w)) return null;
		return w.get("kind") instanceof JsonPrimitive k && WAIT_KIND.equals(k.getAsString()) ? w : null;
	}

	/**
	 * A part waiting for its ground in the steward's words, from Architect's typed wait's counts ({@code terrainCells} and {@code terrainBuilt}): "The
	 * farm's ground is being shaped first: about 7200 blocks of earth to go." {@code who}: the waiting part in the steward's words (slice 8bS: Architect 8b's
	 * typed part, never cut out of its {@code say}), else "the build". Null when it is not a terrain wait, or carries no sentence (another item of the
	 * same part says it: Architect gives one per waiting part; only whether there is one is read, never its words).
	 */
	static @Nullable String waitLine(@Nullable JsonObject ext, @Nullable String who) {
		JsonObject w = terrainWait(ext);
		if (w == null || !(w.get("say") instanceof JsonPrimitive)) return null;
		String name = who == null || who.isBlank() ? "the build" : who.strip();
		if (!name.regionMatches(true, 0, "the ", 0, 4)) name = "the " + name;
		name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
		long left = Math.max(0, num(w, "terrainCells") - num(w, "terrainBuilt"));
		String rest = left > 0 ? "about " + Words.count(left, "block of earth", "blocks of earth") + " to go" : "nearly done";
		return name + (name.endsWith("s") ? "'" : "'s") + " ground is being shaped first: " + rest + ".";
	}

	/** What makes a terrain wait said once: the target it waits on and the waiting part (its counts move as the ground is built). */
	static String waitKey(JsonObject w) {
		return "terrain|" + str(w, "target") + "|" + str(w, "waiter") + "|" + str(w, "part");
	}

	/**
	 * The boxes the steward keeps off (HB, S-8a-1): every target's box of each part of {@code b} in state {@code building}, read through {@code boxOf} (a
	 * target id to its site's box; one Architect no longer knows is left out).
	 */
	static List<BoundingBox> keepOff(@Nullable RegionBuild b, Function<String, Optional<BoundingBox>> boxOf) {
		List<BoundingBox> out = new ArrayList<>();
		if (b == null || b.built()) return out;
		for (PartBuild p : b.parts()) {
			if (!BUILDING.equals(p.state())) continue;
			for (String t : p.targets()) boxOf.apply(t).ifPresent(out::add);
		}
		return out;
	}

	/** The first of {@code boxes} that holds {@code (x, y, z)}, grown by one block round its sides and two below (as a site's own occupancy check), or null. */
	public static @Nullable BoundingBox holding(List<BoundingBox> boxes, int x, int y, int z) {
		for (BoundingBox b : boxes) {
			if (x >= b.minX() - 1 && x <= b.maxX() + 1 && z >= b.minZ() - 1 && z <= b.maxZ() + 1 && y >= b.minY() - 2 && y <= b.maxY() + 1) return b;
		}
		return null;
	}

	/**
	 * Where the steward stands clear of {@code box} (one of {@code boxes}), nearest {@code from}: just outside it ({@link StewardMotion#outside}); a spot that
	 * falls in another of the boxes (a part's tiles side by side) is taken outside both together, at most {@code boxes.size()} times. Its y is
	 * {@code from}'s (the caller puts it on the ground).
	 */
	public static net.minecraft.core.BlockPos clearOf(List<BoundingBox> boxes, BoundingBox box, net.minecraft.core.BlockPos from) {
		BoundingBox all = box;
		net.minecraft.core.BlockPos at = StewardMotion.outside(all.minX(), from.getY(), all.minZ(), all.maxX(), all.maxZ(), from);
		for (int i = 0; i < boxes.size(); i++) {
			BoundingBox in = holding(boxes, at.getX(), at.getY(), at.getZ());
			if (in == null) break;
			// at most every box once: two boxes taken together hold each other, so the next spot is outside both
			all = new BoundingBox(Math.min(all.minX(), in.minX()), Math.min(all.minY(), in.minY()), Math.min(all.minZ(), in.minZ()), Math.max(all.maxX(), in.maxX()),
				Math.max(all.maxY(), in.maxY()), Math.max(all.maxZ(), in.maxZ()));
			at = StewardMotion.outside(all.minX(), from.getY(), all.minZ(), all.maxX(), all.maxZ(), from);
		}
		return at;
	}

	private static long num(JsonObject o, String k) {
		return o.get(k) instanceof JsonPrimitive p && p.isNumber() ? p.getAsLong() : 0;
	}

	private static String str(JsonObject o, String k) {
		return o.get(k) instanceof JsonPrimitive p ? p.getAsString() : "";
	}
}
