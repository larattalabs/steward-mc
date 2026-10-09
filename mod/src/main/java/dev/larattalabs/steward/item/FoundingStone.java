package dev.larattalabs.steward.item;

import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.service.Settlements;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/**
 * The Founding Stone: right-click a block to claim a 129x129 settlement area centred there. Describe it next with {@code /steward describe <words>}, then build with
 * {@code /steward build}. (The card screen replaces the commands later.) Singleplayer: the server side does the work; the client just swings.
 */
public final class FoundingStone extends Item {
	public static final ResourceKey<Item> KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Steward.MOD_ID, "founding_stone"));
	public static Item INSTANCE;

	public FoundingStone(Properties p) {
		super(p);
	}

	public static void init() {
		INSTANCE = Registry.register(BuiltInRegistries.ITEM, KEY, new FoundingStone(new Item.Properties().setId(KEY).stacksTo(1)));
		net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES).register(out -> out.accept(INSTANCE));
	}

	@Override
	public InteractionResult useOn(UseOnContext ctx) {
		if (ctx.getLevel().isClientSide() || !(ctx.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
		boolean ok = claim(player, ctx.getLevel(), ctx.getClickedPos());
		if (ok && !player.getAbilities().instabuild) ctx.getItemInHand().shrink(1);
		return ok ? InteractionResult.SUCCESS : InteractionResult.FAIL;
	}

	/** The stone's effect (also behind {@code /steward claim}): claims the area around {@code at} for the player, with feedback. */
	public static boolean claim(ServerPlayer player, net.minecraft.world.level.Level level, BlockPos at) {
		var existing = Settlements.at(level, at);
		if (existing.isPresent()) {
			player.sendSystemMessage(Component.literal("This land already belongs to " + existing.get().name() + " (" + existing.get().id() + ")."));
			return false;
		}
		Settlements.Result r = Settlements.found(level, at, "Settlement", System.currentTimeMillis());
		if (!r.ok()) {
			player.sendSystemMessage(Component.literal(r.error()));
			return false;
		}
		boolean npc = dev.larattalabs.steward.entity.StewardNpc.spawn((net.minecraft.server.level.ServerLevel) level, at.above(), r.settlement());
		int side = Settlements.DEFAULT_RADIUS * 2 + 1;
		player.sendSystemMessage(Component.literal("Claimed " + side + " x " + side + " blocks as " + r.settlement().id() + ". Describe it: /steward describe " + r.settlement().id() + " <your words>" + (npc ? "" : " (the steward could not appear)")));
		return true;
	}
}
