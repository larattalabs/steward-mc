package dev.larattalabs.steward.entity;

import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.service.SettlementRunner;
import dev.larattalabs.steward.service.Settlements;
import java.util.Optional;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;

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
	 * Spawns the steward for a settlement at {@code at}. The mannequin is loaded from entity data, the supported way to set its {@code immovable} and
	 * {@code hide_description} (their setters are private), then named and added in Java. (Not through the summon command: run from inside another command,
	 * such as {@code /steward claim}, vanilla queues it until that command ends, so the entity did not exist yet when we looked for it.)
	 */
	public static boolean spawn(ServerLevel level, BlockPos at, Settlement s) {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("immovable", true);
		tag.putBoolean("hide_description", true);
		tag.putBoolean("Invulnerable", true);
		ListTag tags = new ListTag();
		tags.add(StringTag.valueOf(TAG));
		tags.add(StringTag.valueOf(SETTLEMENT_TAG_PREFIX + s.id()));
		tag.put("Tags", tags);
		try {
			var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("mannequin"));
			Entity e = EntityType.loadEntityRecursive(type, tag, level, EntitySpawnReason.EVENT, x -> {
				x.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
				return x;
			});
			if (!(e instanceof Mannequin m)) {
				Steward.LOGGER.error("the steward of {} could not be made (got {})", s.id(), e);
				return false;
			}
			m.setCustomName(Component.literal("Steward").withStyle(ChatFormatting.GOLD));
			m.setCustomNameVisible(true);
			if (!level.addFreshEntity(m)) {
				Steward.LOGGER.error("the steward of {} could not be added at {}", s.id(), at);
				return false;
			}
			return true;
		} catch (RuntimeException e) {
			Steward.LOGGER.error("could not spawn the steward of {}: {}", s.id(), e.toString());
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
