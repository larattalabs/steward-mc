package dev.larattalabs.steward.block;

import dev.larattalabs.steward.Steward;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/** Steward's blocks: the settlement board (block, item, block entity). */
public final class StewardBlocks {
	public static Block BOARD;
	public static BlockEntityType<SettlementBoardBlockEntity> BOARD_ENTITY;

	private StewardBlocks() {
	}

	public static void init() {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Steward.id("settlement_board"));
		BOARD = Registry.register(BuiltInRegistries.BLOCK, key, new SettlementBoardBlock(BlockBehaviour.Properties.of().setId(key).mapColor(MapColor.COLOR_BROWN)
			.strength(1.0f).sound(SoundType.WOOD).noOcclusion()));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Steward.id("settlement_board"));
		Item item = Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(BOARD, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		BOARD_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Steward.id("settlement_board"), new BlockEntityType<>(SettlementBoardBlockEntity::new,
			Set.of(BOARD)));
		net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS).register(out -> out.accept(item));
	}
}
