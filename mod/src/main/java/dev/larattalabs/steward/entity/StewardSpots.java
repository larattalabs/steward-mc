// userSpot is ported from AgentCraft Worlds (AgentManager.userSpot), MIT:
// Copyright (c) 2026 AgentCraft contributors; Copyright (c) 2026 Laratta Labs (AgentCraft Worlds changes). See assets-src/NOTICE.
package dev.larattalabs.steward.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Where the steward can stand, sit and sleep, read from the world. Server thread. */
public final class StewardSpots {
	/** How far from the player it stands when it comes to find them. */
	public static final double USER_DISTANCE = 3.2;
	/** How far the player may move before it picks a new spot near them. */
	public static final double FOLLOW_SLACK = 2.6;

	private StewardSpots() {
	}

	/** Standing room at {@code feet}: something sturdy below, nothing solid at the feet or the head. */
	public static boolean standable(BlockGetter level, BlockPos feet) {
		BlockPos below = feet.below();
		return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
			&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
	}

	/**
	 * A spot about {@link #USER_DISTANCE} from the player, on the steward's side of them (then fanning out left and right, then further round), with standing
	 * room. Null when there is none.
	 */
	public static @Nullable Vec3 userSpot(BlockGetter level, Vec3 player, Vec3 from) {
		double base = from.distanceToSqr(player) < 0.25 ? 0 : Math.atan2(from.z - player.z, from.x - player.x);
		double[] radii = {USER_DISTANCE, USER_DISTANCE + 0.5, USER_DISTANCE - 0.6};
		double[] offs = {0, 0.45, -0.45, 0.9, -0.9, 1.4, -1.4, 2.0, -2.0, Math.PI};
		for (double r : radii) {
			for (double o : offs) {
				double x = player.x + Math.cos(base + o) * r;
				double z = player.z + Math.sin(base + o) * r;
				int by = (int) Math.floor(player.y + 0.01);
				for (int dy : new int[] {0, 1, -1}) {
					BlockPos feet = BlockPos.containing(x, by + dy, z);
					if (standable(level, feet)) return new Vec3(x, feet.getY(), z);
				}
			}
		}
		return null;
	}

	/** A seat near {@code home}: the bottom half of a stair or a bottom slab with headroom, the nearest first. */
	public static @Nullable BlockPos seat(BlockGetter level, BlockPos home, int radius) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(home.offset(-radius, -2, -radius), home.offset(radius, 2, radius))) {
			BlockState s = level.getBlockState(p);
			boolean seat = (s.getBlock() instanceof StairBlock && s.getValue(StairBlock.HALF) == Half.BOTTOM)
				|| (s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM);
			if (!seat || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
				|| !level.getBlockState(p.above(2)).getCollisionShape(level, p.above(2)).isEmpty()) continue;
			double d = p.distSqr(home);
			if (d < bestD) {
				bestD = d;
				best = p.immutable();
			}
		}
		return best;
	}

	/** A free bed near {@code home} (its head half), the nearest first. */
	public static @Nullable BlockPos bed(BlockGetter level, BlockPos home, int radius) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(home.offset(-radius, -4, -radius), home.offset(radius, 4, radius))) {
			BlockState s = level.getBlockState(p);
			if (!(s.getBlock() instanceof BedBlock) || s.getValue(BlockStateProperties.BED_PART) != BedPart.HEAD || s.getValue(BlockStateProperties.OCCUPIED)) continue;
			double d = p.distSqr(home);
			if (d < bestD) {
				bestD = d;
				best = p.immutable();
			}
		}
		return best;
	}

	/** The way a bed's head points (the sleeper's feet point the other way). */
	public static Direction bedFacing(BlockGetter level, BlockPos head) {
		BlockState s = level.getBlockState(head);
		return s.getBlock() instanceof BedBlock ? s.getValue(BedBlock.FACING) : Direction.NORTH;
	}
}
