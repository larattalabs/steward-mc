package dev.larattalabs.steward.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The settlement board's block entity: no state of its own (the client draws the settlement it stands in). */
public final class SettlementBoardBlockEntity extends BlockEntity {
	public SettlementBoardBlockEntity(BlockPos pos, BlockState state) {
		super(StewardBlocks.BOARD_ENTITY, pos, state);
	}
}
