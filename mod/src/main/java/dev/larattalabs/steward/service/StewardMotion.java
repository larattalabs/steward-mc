package dev.larattalabs.steward.service;

import dev.larattalabs.steward.entity.StewardEntity;
import dev.larattalabs.steward.model.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Where a settlement's steward works (docs/PLAN.md phase 3b: where it stands says what it is doing). The build and the update and revert paths tell it; a
 * steward whose chunk is not loaded is not moved (it is told again by the next change). Server thread.
 */
public final class StewardMotion {
	/** How long it stays at a building it updated or reverted before going home. */
	public static final int VISIT_TICKS = 20 * 60;

	private StewardMotion() {
	}

	/** Sends the settlement's steward to {@code pos} for {@code ticks} (0: until told otherwise), or home with null. */
	public static void workAt(MinecraftServer server, Settlement s, @Nullable BlockPos pos, int ticks) {
		var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(s.claim().dimension())));
		if (level == null) return;
		for (StewardEntity e : level.getEntities(StewardEntity.TYPE, x -> s.id().equals(x.settlementId()))) e.workAt(pos, ticks);
	}

	/** How far outside a building's box the steward stands to look at it. */
	static final int STANDOFF = 2;

	/**
	 * Where the steward stands to work on a box: just outside it ({@link #STANDOFF} blocks), on the side towards {@code from} (its home), never inside. A
	 * named mob inside a building's box is "the player's things" to Architect, which refuses to remove or replace it then (found by the e2e undo).
	 */
	public static BlockPos outside(int minX, int minY, int minZ, int maxX, int maxZ, BlockPos from) {
		int x0 = minX - STANDOFF, x1 = maxX + STANDOFF, z0 = minZ - STANDOFF, z1 = maxZ + STANDOFF;
		int x = Math.max(x0, Math.min(x1, from.getX()));
		int z = Math.max(z0, Math.min(z1, from.getZ()));
		boolean inside = x > x0 && x < x1 && z > z0 && z < z1;
		if (inside) {
			// on the nearest edge of the grown box
			int dx0 = x - x0, dx1 = x1 - x, dz0 = z - z0, dz1 = z1 - z;
			int m = Math.min(Math.min(dx0, dx1), Math.min(dz0, dz1));
			if (m == dx0) x = x0;
			else if (m == dx1) x = x1;
			else if (m == dz0) z = z0;
			else z = z1;
		}
		return new BlockPos(x, minY, z);
	}

	/** Before a building is removed or rewritten: a steward standing in its box steps out of it (and stays out, looking at it). */
	public static void clear(MinecraftServer server, Settlement s, net.minecraft.world.level.levelgen.structure.BoundingBox box) {
		var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(s.claim().dimension())));
		if (level == null) return;
		BlockPos home = new BlockPos(s.claim().centerX(), box.minY(), s.claim().centerZ());
		BlockPos out = outside(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxZ(), home);
		for (StewardEntity e : level.getEntities(StewardEntity.TYPE, x -> s.id().equals(x.settlementId()))) {
			if (e.stepOutOf(box, out)) e.workAt(out, VISIT_TICKS);
		}
	}

	/** Sends the steward to look at a placed site from just outside it, for {@link #VISIT_TICKS}. */
	public static void visit(MinecraftServer server, Settlement s, net.minecraft.world.level.levelgen.structure.BoundingBox box) {
		BlockPos home = new BlockPos(s.claim().centerX(), box.minY(), s.claim().centerZ());
		workAt(server, s, outside(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxZ(), home), VISIT_TICKS);
	}
}
