package dev.larattalabs.steward.item;

import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.service.Actions;
import dev.larattalabs.steward.service.Settlements;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * The steward's ledger (docs/PLAN.md "Interface", 3d): the settlement in your hand, wherever you are. Right-click: the screen of the settlement you stand in,
 * else of the nearest one in this dimension (its card or describe screen while nothing is built, its inbox while it is being built). Crafted from a book, a
 * gold nugget and green dye.
 */
public final class StewardLedger extends Item {
	public static final ResourceKey<Item> KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Steward.MOD_ID, "steward_ledger"));
	public static Item INSTANCE;

	public StewardLedger(Properties p) {
		super(p);
	}

	public static void init() {
		INSTANCE = Registry.register(BuiltInRegistries.ITEM, KEY, new StewardLedger(new Item.Properties().setId(KEY).stacksTo(1)));
		net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES).register(out -> out.accept(INSTANCE));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
		Optional<Settlement> s = settlementFor(sp);
		if (s.isEmpty()) {
			sp.sendSystemMessage(Component.literal("The ledger is empty: there is no settlement in this dimension. Found one with a Founding Stone."));
			return InteractionResult.FAIL;
		}
		Actions.openFor(sp, s.get());
		return InteractionResult.SUCCESS;
	}

	/** The settlement the player stands in, else the nearest in their dimension (by its stone). */
	static Optional<Settlement> settlementFor(ServerPlayer p) {
		var here = Settlements.at(p.level(), p.blockPosition());
		if (here.isPresent()) return here;
		String dim = p.level().dimension().identifier().toString();
		return Settlements.store().all().stream().filter(s -> s.claim().dimension().equals(dim))
			.min(Comparator.comparingDouble(s -> Math.hypot(s.claim().centerX() - p.getX(), s.claim().centerZ() - p.getZ())));
	}
}
