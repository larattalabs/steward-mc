package dev.larattalabs.steward.entity;

import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.service.SettlementRunner;
import dev.larattalabs.steward.service.Settlements;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.AABB;

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
			// entity tags are not synced to the client, so the client returns PASS for both hands and the server sees two interacts: act on one
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			return switch (use(entity.entityTags().contains(TAG), hand == InteractionHand.MAIN_HAND)) {
				case TALK -> {
					talk(sp, entity);
					yield InteractionResult.SUCCESS;
				}
				case SWALLOW -> InteractionResult.FAIL;
				case PASS -> InteractionResult.PASS;
			};
		});
	}

	public enum Use { TALK, SWALLOW, PASS }

	/** What a right-click on an entity does: talk on the main hand, swallow the off-hand repeat (so the off-hand item is not used either), ignore other entities. */
	public static Use use(boolean isSteward, boolean mainHand) {
		if (!isSteward) return Use.PASS;
		return mainHand ? Use.TALK : Use.SWALLOW;
	}

	/** The settlement id an NPC belongs to, from its tags. */
	public static Optional<String> settlementOf(Entity e) {
		return e.entityTags().stream().filter(t -> t.startsWith(SETTLEMENT_TAG_PREFIX)).map(t -> t.substring(SETTLEMENT_TAG_PREFIX.length())).findFirst();
	}

	/**
	 * Spawns the steward for a settlement at {@code at}. The summon command is the supported way to set a mannequin's {@code immovable} and {@code hide_description} (their setters are
	 * private); the name is set in Java afterwards, and the result is checked by finding the tagged entity, since a rejected summon does not throw.
	 */
	public static boolean spawn(ServerLevel level, BlockPos at, Settlement s) {
		String settlementTag = SETTLEMENT_TAG_PREFIX + s.id();
		String cmd = String.format("summon minecraft:mannequin %d %d %d {immovable:1b,hide_description:1b,Invulnerable:1b,Tags:[\"%s\",\"%s\"]}",
			at.getX(), at.getY(), at.getZ(), TAG, settlementTag);
		try {
			CommandSourceStack src = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
			level.getServer().getCommands().performPrefixedCommand(src, cmd);
		} catch (RuntimeException e) {
			Steward.LOGGER.error("could not spawn the steward: {}", e.toString());
			return false;
		}
		List<Mannequin> found = level.getEntitiesOfClass(Mannequin.class, new AABB(at).inflate(2), m -> m.entityTags().contains(settlementTag));
		if (found.isEmpty()) {
			Steward.LOGGER.error("the steward of {} did not appear at {} (summon rejected)", s.id(), at);
			return false;
		}
		Mannequin m = found.get(0);
		m.setCustomName(Component.literal("Steward").withStyle(ChatFormatting.GOLD));
		m.setCustomNameVisible(true);
		return true;
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
