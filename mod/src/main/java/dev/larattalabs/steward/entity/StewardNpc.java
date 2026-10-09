package dev.larattalabs.steward.entity;

import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.service.SettlementRunner;
import dev.larattalabs.steward.service.Settlements;
import java.util.Optional;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;

/**
 * The steward as a world citizen: vanilla's persistent, player-shaped {@code minecraft:mannequin} entity, tagged with its settlement. It is saved with the world, survives relogs and is
 * right-clickable; no custom entity type, no custom renderer. (A custom skin, walking to worksites and a conversation screen come later.)
 */
public final class StewardNpc {
	public static final String TAG = "steward_mc.steward";
	public static final String SETTLEMENT_TAG_PREFIX = "steward_mc.settlement.";

	private StewardNpc() {
	}

	public static void init() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !entity.entityTags().contains(TAG)) return InteractionResult.PASS;
			talk(sp, entity);
			return InteractionResult.SUCCESS;
		});
	}

	/** The settlement id an NPC belongs to, from its tags. */
	public static Optional<String> settlementOf(Entity e) {
		return e.entityTags().stream().filter(t -> t.startsWith(SETTLEMENT_TAG_PREFIX)).map(t -> t.substring(SETTLEMENT_TAG_PREFIX.length())).findFirst();
	}

	/** Spawns the steward for a settlement beside {@code at} through the vanilla summon command (the supported way to build a mannequin with its saved fields). */
	public static boolean spawn(ServerLevel level, BlockPos at, Settlement s) {
		String name = "Steward";
		String nbt = "{CustomName:'{\"text\":\"" + name + "\",\"color\":\"gold\"}',CustomNameVisible:1b,immovable:1b,hide_description:1b,Invulnerable:1b,Tags:[\"" + TAG + "\",\""
			+ SETTLEMENT_TAG_PREFIX + s.id() + "\"]}";
		String cmd = String.format("summon minecraft:mannequin %d %d %d %s", at.getX(), at.getY(), at.getZ(), nbt);
		try {
			CommandSourceStack src = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
			level.getServer().getCommands().performPrefixedCommand(src, cmd);
			return true;
		} catch (RuntimeException e) {
			Steward.LOGGER.error("could not spawn the steward: {}", e.toString());
			return false;
		}
	}

	static void talk(ServerPlayer p, Entity npc) {
		Optional<String> id = settlementOf(npc);
		Optional<Settlement> s = id.flatMap(i -> Settlements.store().get(i));
		if (s.isEmpty()) {
			p.sendSystemMessage(Component.literal("Steward: I have no settlement to look after."));
			return;
		}
		Settlement st = s.get();
		p.sendSystemMessage(Component.literal("Steward of " + st.name() + " (" + st.id() + ")"));
		if (!st.described()) {
			p.sendSystemMessage(Component.literal("Tell me what to build: /steward describe " + st.id() + " <your words>"));
			return;
		}
		p.sendSystemMessage(Component.literal(st.card().site().text() + ", " + st.card().style().text() + ", for " + st.card().purpose().text() + ". Permission: " + st.permission().name().toLowerCase() + "."));
		SettlementRunner r = SettlementRunner.active(st.id());
		p.sendSystemMessage(Component.literal(r == null ? "Nothing is being built. Start: /steward start " + st.id() + " <buildings> <budget in USD>" : r.statusLine()));
	}
}
