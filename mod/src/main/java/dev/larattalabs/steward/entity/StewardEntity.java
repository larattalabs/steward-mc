package dev.larattalabs.steward.entity;

import dev.larattalabs.steward.Steward;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The steward: a persistent, player-shaped mob of its settlement (docs/PLAN.md phase 3b). It is saved with the world, cannot be hurt or pushed around by
 * mobs, never despawns, and keeps to its home by the Founding Stone. While it works it walks to the building it works on ({@link #workAt}): the lot whose
 * massing waits, the street being placed, the building being updated; then it comes home. Right-click talks to it (the screens). Its settlement and home are
 * saved; where it works is not (the build that sets it is restored and sets it again).
 */
public final class StewardEntity extends PathfinderMob {
	public static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(Registries.ENTITY_TYPE, Steward.id("steward"));
	public static final EntityType<StewardEntity> TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, KEY,
		EntityType.Builder.<StewardEntity>of(StewardEntity::new, MobCategory.MISC).sized(0.6F, 1.8F).eyeHeight(1.62F).clientTrackingRange(10).build(KEY));

	/** How far from its home it strolls when idle, and how close to home it must be before it stops walking back. */
	static final int HOME_RADIUS = 8;
	/** How close to a work place it stops. */
	static final double WORK_REACH = 1.5;

	private String settlementId = "";
	private @Nullable BlockPos work;
	/** The game time the current work ends (it walks home then); 0 = until told otherwise. */
	private long workUntil;

	public StewardEntity(EntityType<? extends StewardEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
		setPermanentlyInvulnerable(true);
	}

	public static AttributeSupplier.Builder attributes() {
		return createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.5).add(Attributes.FOLLOW_RANGE, 64);
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(1, new WorkGoal());
		goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
		goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.5) {
			@Override
			public boolean canUse() {
				return work == null && super.canUse();
			}
		});
		goalSelector.addGoal(4, new RandomLookAroundGoal(this));
	}

	public String settlementId() {
		return settlementId;
	}

	public void bind(String settlementId, BlockPos home) {
		this.settlementId = settlementId;
		setHomeTo(home, HOME_RADIUS);
	}

	/** Walks to {@code pos} and works there, for {@code ticks} game ticks (0 = until told otherwise); null = home. */
	public void workAt(@Nullable BlockPos pos, int ticks) {
		this.work = pos;
		this.workUntil = pos == null || ticks <= 0 ? 0 : level().getGameTime() + ticks;
	}

	public @Nullable BlockPos work() {
		return work;
	}

	/** Steps out of a box it stands in (grown by one), to {@code to}: Architect will not remove or rewrite a building with a named mob inside it. */
	public boolean stepOutOf(net.minecraft.world.level.levelgen.structure.BoundingBox box, BlockPos to) {
		BlockPos p = blockPosition();
		if (p.getX() < box.minX() - 1 || p.getX() > box.maxX() + 1 || p.getZ() < box.minZ() - 1 || p.getZ() > box.maxZ() + 1 || p.getY() < box.minY() - 2
			|| p.getY() > box.maxY() + 1) return false;
		getNavigation().stop();
		int y = level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, to.getX(), to.getZ());
		snapTo(to.getX() + 0.5, y, to.getZ() + 0.5, getYRot(), getXRot());
		return true;
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		// the main hand talks; consumed, so the off hand is not offered the click too
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
		if (player instanceof ServerPlayer sp) StewardNpc.talk(sp, this);
		return InteractionResult.SUCCESS;
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putString("settlement", settlementId);
		if (hasHome()) {
			BlockPos h = getHomePosition();
			out.putIntArray("home", new int[] {h.getX(), h.getY(), h.getZ()});
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		settlementId = in.getStringOr("settlement", "");
		in.getIntArray("home").filter(a -> a.length == 3).ifPresent(a -> setHomeTo(new BlockPos(a[0], a[1], a[2]), HOME_RADIUS));
	}

	/** To the work place while there is one, else back within its home; it stops near the target and faces it. */
	final class WorkGoal extends Goal {
		private int repath;

		WorkGoal() {
			setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		private @Nullable BlockPos target() {
			if (work != null && workUntil > 0 && level().getGameTime() >= workUntil) work = null;
			if (work != null) return work;
			return hasHome() ? getHomePosition() : null;
		}

		private double reach() {
			return work != null ? WORK_REACH : HOME_RADIUS;
		}

		@Override
		public boolean canUse() {
			BlockPos t = target();
			return t != null && distanceToSqr(t.getX() + 0.5, t.getY(), t.getZ() + 0.5) > reach() * reach();
		}

		@Override
		public boolean canContinueToUse() {
			BlockPos t = target();
			double stop = work != null ? 1.0 : HOME_RADIUS - 2;
			return t != null && distanceToSqr(t.getX() + 0.5, t.getY(), t.getZ() + 0.5) > stop * stop && !getNavigation().isStuck();
		}

		@Override
		public void start() {
			repath = 0;
		}

		@Override
		public void tick() {
			BlockPos t = target();
			if (t == null) return;
			getLookControl().setLookAt(t.getX() + 0.5, t.getY() + 1, t.getZ() + 0.5);
			if (--repath <= 0) {
				repath = 20;
				getNavigation().moveTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5, 0.7);
			}
		}

		@Override
		public void stop() {
			getNavigation().stop();
		}
	}
}
