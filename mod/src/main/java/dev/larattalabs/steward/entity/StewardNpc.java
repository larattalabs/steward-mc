package dev.larattalabs.steward.entity;

import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.service.SettlementRunner;
import dev.larattalabs.steward.service.Settlements;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;

/**
 * The steward as a world citizen: {@link StewardEntity}, a persistent player-shaped mob of its settlement that walks to its work. Stewards spawned before it
 * existed were vanilla {@code minecraft:mannequin} entities tagged with their settlement; each is replaced by a {@link StewardEntity} in place when it loads.
 * The tags stay on the new entity, so selectors that found the old one find it too.
 */
public final class StewardNpc {
	public static final String TAG = "steward_mc.steward";
	public static final String SETTLEMENT_TAG_PREFIX = "steward_mc.settlement.";

	private StewardNpc() {
	}

	/** Old mannequin stewards seen loading, replaced on the next tick (an entity is not added from inside another's load). */
	private static final java.util.List<Mannequin> OLD = new java.util.ArrayList<>();

	public static void init() {
		net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(StewardEntity.TYPE, StewardEntity.attributes());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Mannequin m && m.entityTags().contains(TAG)) OLD.add(m);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (OLD.isEmpty()) return;
			List<Mannequin> todo = List.copyOf(OLD);
			OLD.clear();
			for (Mannequin m : todo) if (m.isAlive() && m.level() instanceof ServerLevel l) replace(l, m);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> OLD.clear());
		// the old mannequins, until they are replaced (a click on the new entity goes through its own mobInteract)
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			// entity tags are not synced to the client, so the client returns PASS for both hands and the server sees two interacts: act on one
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			if (entity instanceof StewardEntity) return InteractionResult.PASS;
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

	/** The settlement id a steward belongs to: its own field, or (an old mannequin) its tags. */
	public static Optional<String> settlementOf(Entity e) {
		if (e instanceof StewardEntity s && !s.settlementId().isEmpty()) return Optional.of(s.settlementId());
		return e.entityTags().stream().filter(t -> t.startsWith(SETTLEMENT_TAG_PREFIX)).map(t -> t.substring(SETTLEMENT_TAG_PREFIX.length())).findFirst();
	}

	/** Replaces an old mannequin steward by a {@link StewardEntity} where it stands, with the same settlement, name and tags. */
	static void replace(ServerLevel level, Mannequin old) {
		Optional<String> id = settlementOf(old);
		if (id.isEmpty()) return;
		BlockPos at = old.blockPosition();
		StewardEntity n = make(level, at, id.get());
		if (n == null) return;
		n.setYRot(old.getYRot());
		old.discard();
		if (level.addFreshEntity(n)) Steward.LOGGER.info("the steward of {} is now a steward_mc:steward at {}", id.get(), at);
		else Steward.LOGGER.error("the steward of {} could not be replaced at {}", id.get(), at);
	}

	private static StewardEntity make(ServerLevel level, BlockPos at, String settlementId) {
		StewardEntity e = StewardEntity.TYPE.create(level, EntitySpawnReason.EVENT);
		if (e == null) return null;
		e.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		e.bind(settlementId, at);
		e.addTag(TAG);
		e.addTag(SETTLEMENT_TAG_PREFIX + settlementId);
		e.setCustomName(Component.literal("Steward").withStyle(ChatFormatting.GOLD));
		e.setCustomNameVisible(true);
		return e;
	}

	/** Spawns the steward for a settlement at {@code at} (its home: it keeps near and comes back to it). */
	public static boolean spawn(ServerLevel level, BlockPos at, Settlement s) {
		try {
			StewardEntity e = make(level, at, s.id());
			if (e == null || !level.addFreshEntity(e)) {
				Steward.LOGGER.error("the steward of {} could not be added at {}", s.id(), at);
				return false;
			}
			return true;
		} catch (RuntimeException e) {
			Steward.LOGGER.error("could not spawn the steward of {}: {}", s.id(), e.toString());
			return false;
		}
	}

	/** Right-click: the screen that fits the settlement (describe it, its inbox while it is built, else its card); chat for a steward without one. */
	static void talk(ServerPlayer p, Entity npc) {
		Optional<String> id = settlementOf(npc);
		Optional<Settlement> s = id.flatMap(i -> Settlements.store().get(i));
		if (s.isEmpty()) {
			p.sendSystemMessage(Component.literal("Steward: I have no settlement to look after."));
			return;
		}
		dev.larattalabs.steward.service.Actions.openFor(p, s.get());
	}

	/** The settlement's status as chat lines (kept for a player without the client mod's screens). */
	static void status(ServerPlayer p, Optional<Settlement> s) {
		if (s.isEmpty()) return;
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
