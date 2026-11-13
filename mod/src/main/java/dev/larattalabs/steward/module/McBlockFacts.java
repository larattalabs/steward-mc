package dev.larattalabs.steward.module;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Fallable;

/** {@link PaletteCheck.BlockFacts} from the running game's block registry (each block in its default state). */
public final class McBlockFacts implements PaletteCheck.BlockFacts {
	public static final McBlockFacts INSTANCE = new McBlockFacts();

	private McBlockFacts() {}

	private static Optional<Block> block(String id) {
		Identifier i = Identifier.tryParse(id);
		if (i == null) return Optional.empty();
		return BuiltInRegistries.BLOCK.getOptional(i);
	}

	@Override
	public boolean known(String id) {
		return block(id).isPresent();
	}

	@Override
	public int light(String id) {
		return block(id).map(b -> b.defaultBlockState().getLightEmission()).orElse(0);
	}

	@Override
	public boolean gravity(String id) {
		return block(id).map(b -> b instanceof Fallable).orElse(false);
	}

	@Override
	public boolean conducts(String id) {
		return block(id).map(b -> b.defaultBlockState().isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)).orElse(false);
	}
}
