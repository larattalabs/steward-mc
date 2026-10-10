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
		goalSelector.addGoal(1, new LifeGoal());
		goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
		goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.5) {
			@Override
			public boolean canUse() {
				return plan == Plan.NONE && super.canUse();
			}
		});
		goalSelector.addGoal(4, new RandomLookAroundGoal(this));
	}

	public String settlementId() {
		return level().isClientSide() ? entityData.get(SETTLEMENT) : settlementId;
	}

	public void bind(String settlementId, BlockPos home) {
		this.settlementId = settlementId;
		setHomeTo(home, HOME_RADIUS);
		tagOwner();
	}

	/**
	 * Architect's occupancy checks ignore an entity tagged {@code architect:owner=<owner>} when the request's owner is the same (Architect 0c, ask C18):
	 * the steward then never holds up its own settlement's removals, deltas or placements. Until 0c it only steps out of the box first.
	 */
	private void tagOwner() {
		entityData.set(SETTLEMENT, settlementId);
		if (settlementId.isEmpty()) return;
		entityTags().removeIf(t -> t.startsWith("architect:owner="));
		addTag("architect:owner=steward_mc:settlement/" + settlementId);
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
		tagOwner();
		// saved while seated or lying: it loads standing (the plan is picked again)
		setNoGravity(false);
		in.getIntArray("home").filter(a -> a.length == 3).ifPresent(a -> setHomeTo(new BlockPos(a[0], a[1], a[2]), HOME_RADIUS));
	}

	// ------------------------------------------------------------------ what it does

	/** What the steward is up to, picked by {@link LifeGoal} (in this order): a player waits on a decision, work, night, a seat, home. */
	enum Plan { COME, WORK, SLEEP, SIT, HOME, NONE }

	private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> POSTURE = net.minecraft.network.syncher.SynchedEntityData.defineId(StewardEntity.class,
		net.minecraft.network.syncher.EntityDataSerializers.INT);
	/** The bed's facing while it lies in one (a Direction ordinal, -1 none): the client lays the body along it. */
	private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> BED = net.minecraft.network.syncher.SynchedEntityData.defineId(StewardEntity.class,
		net.minecraft.network.syncher.EntityDataSerializers.INT);

	/** The nameplate's activity line and its "!" (the player is needed), from {@link dev.larattalabs.steward.service.StewardStatus}. */
	private static final net.minecraft.network.syncher.EntityDataAccessor<String> ACTIVITY = net.minecraft.network.syncher.SynchedEntityData.defineId(StewardEntity.class,
		net.minecraft.network.syncher.EntityDataSerializers.STRING);
	private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> NEEDS_YOU = net.minecraft.network.syncher.SynchedEntityData.defineId(StewardEntity.class,
		net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);

	@Override
	protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder b) {
		super.defineSynchedData(b);
		b.define(POSTURE, Poses.Posture.IDLE.ordinal());
		b.define(BED, -1);
		b.define(ACTIVITY, "");
		b.define(SETTLEMENT, "");
		b.define(NEEDS_YOU, false);
	}

	/** Its settlement id, synced: the client matches speech bubbles to their steward by it. */
	private static final net.minecraft.network.syncher.EntityDataAccessor<String> SETTLEMENT = net.minecraft.network.syncher.SynchedEntityData.defineId(StewardEntity.class,
		net.minecraft.network.syncher.EntityDataSerializers.STRING);

	public String activity() {
		return entityData.get(ACTIVITY);
	}

	public boolean needsYou() {
		return entityData.get(NEEDS_YOU);
	}

	/** What it is doing itself, for the nameplate when no build or update speaks for it. */
	private String doing() {
		return switch (plan) {
			case SLEEP -> settled ? "asleep" : "off to bed";
			case SIT -> settled ? "resting" : "";
			case WORK -> "looking it over";
			case COME -> "coming to find you";
			default -> "";
		};
	}

	public Poses.Posture posture() {
		return Poses.Posture.of(entityData.get(POSTURE));
	}

	public net.minecraft.core.@Nullable Direction bedFacing() {
		int d = entityData.get(BED);
		return d < 0 ? null : net.minecraft.core.Direction.values()[d];
	}

	/** Client: the pose channels, eased towards the posture's every tick ({@code prev} for the frame between ticks). */
	public final float[] pose = new float[Poses.N];
	public final float[] posePrev = new float[Poses.N];

	private Plan plan = Plan.NONE;
	private int planAge;
	/** Ticks spent idle at home (a seat is taken after a while). */
	private int idleTicks;
	private net.minecraft.world.phys.@Nullable Vec3 comeSpot;
	private net.minecraft.world.phys.@Nullable Vec3 comeFor;
	private java.util.@Nullable UUID comePlayer;
	private @Nullable BlockPos seat;
	private @Nullable BlockPos bed;
	private boolean settled;
	private int nextBedSearch;
	/** The game time it stops talking (a line it said: it faces the nearest player and gestures). */
	private long talkUntil;

	/** It has just said something: for a few seconds it turns to the nearest player and talks. */
	public void speak() {
		talkUntil = level().getGameTime() + 70;
	}
	/** Seats and beds are looked for this far from home. */
	static final int SEAT_RADIUS = 6, BED_RADIUS = 24;
	/** It comes to find a player this far away at most (and only inside its settlement's claim). */
	static final double COME_RANGE = 64;
	/** How long it sits before getting up (ticks). */
	static final int SIT_TICKS = 20 * 45;
	/** How long it idles at home before it looks for a seat (ticks). */
	static final int IDLE_BEFORE_SIT = 20 * 30;

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) {
			System.arraycopy(pose, 0, posePrev, 0, Poses.N);
			Poses.ease(pose, Poses.target(posture()));
		}
	}

	@Override
	protected void customServerAiStep(net.minecraft.server.level.ServerLevel level) {
		super.customServerAiStep(level);
		if (plan == Plan.NONE) idleTicks++;
		entityData.set(POSTURE, choosePosture().ordinal());
		if (tickCount % 20 == 0 && !settlementId.isEmpty()) {
			var st = dev.larattalabs.steward.service.StewardStatus.of(settlementId, doing());
			entityData.set(ACTIVITY, st.activity());
			entityData.set(NEEDS_YOU, st.needsYou());
		}
		entityData.set(BED, plan == Plan.SLEEP && settled && bed != null ? StewardSpots.bedFacing(level, bed).ordinal() : -1);
		planAge++;
	}

	/** The posture for what it is doing now. */
	private Poses.Posture choosePosture() {
		if (settled && plan == Plan.SLEEP) return Poses.Posture.LIE;
		if (settled && plan == Plan.SIT) return Poses.Posture.SIT;
		if (!getNavigation().isDone()) return Poses.Posture.WALK;
		if (level().getGameTime() < talkUntil) {
			Player p = level().getNearestPlayer(this, 12);
			if (p != null) getLookControl().setLookAt(p, 30, 30);
			return Poses.Posture.TALK;
		}
		return switch (plan) {
			case COME -> {
				Player p = comePlayer == null ? null : level().getPlayerByUUID(comePlayer);
				// it says its piece when the player is close, then waits
				yield p != null && distanceToSqr(p) < 4.5 * 4.5 && (planAge / 60) % 3 == 0 ? Poses.Posture.TALK : Poses.Posture.WAIT;
			}
			// looking the building over, now and then a hand to the chin
			case WORK -> (planAge / 100) % 4 == 3 ? Poses.Posture.THINK : Poses.Posture.REVIEW;
			default -> (tickCount / 200) % 7 == 6 ? Poses.Posture.THINK : Poses.Posture.IDLE;
		};
	}

	/** Who it should go and find, and where they stand, or null (no decision waits, the player is elsewhere). */
	private @Nullable Player summoner() {
		if (settlementId.isEmpty()) return null;
		var who = dev.larattalabs.steward.service.Summons.waitingFor(settlementId);
		if (who.isEmpty()) return null;
		Player p = level().getPlayerByUUID(who.get());
		if (p == null || distanceToSqr(p) > COME_RANGE * COME_RANGE) return null;
		var s = dev.larattalabs.steward.service.Settlements.store().get(settlementId);
		if (s.isEmpty() || !s.get().claim().contains(level().dimension().identifier().toString(), p.getBlockX(), p.getBlockY(), p.getBlockZ())) return null;
		return p;
	}

	private Plan pick() {
		if (summoner() != null) return Plan.COME;
		if (work != null) return Plan.WORK;
		BlockPos home = hasHome() ? getHomePosition() : blockPosition();
		if (level().isDarkOutside()) {
			// a bed is looked for at most every ten seconds (the search reads a wide box)
			if ((bed == null || !(level().getBlockState(bed).getBlock() instanceof net.minecraft.world.level.block.BedBlock)) && tickCount >= nextBedSearch) {
				nextBedSearch = tickCount + 200;
				bed = StewardSpots.bed(level(), home, BED_RADIUS);
			}
			if (bed != null) return Plan.SLEEP;
		} else {
			bed = null;
		}
		if (plan == Plan.SIT && planAge < SIT_TICKS) return Plan.SIT;
		if (idleTicks > IDLE_BEFORE_SIT && plan != Plan.SIT) {
			seat = StewardSpots.seat(level(), home, SEAT_RADIUS);
			if (seat != null) return Plan.SIT;
		}
		if (hasHome() && distanceToSqr(home.getX() + 0.5, home.getY(), home.getZ() + 0.5) > HOME_RADIUS * HOME_RADIUS) return Plan.HOME;
		return Plan.NONE;
	}

	private void setPlan(Plan p) {
		if (p == plan) return;
		if (settled) {
			// up off the seat or out of the bed
			noPhysics = false;
			setNoGravity(false);
			setPos(getX(), Math.floor(getY()) + 1, getZ());
		}
		plan = p;
		planAge = 0;
		settled = false;
		comeSpot = null;
		comeFor = null;
		if (p != Plan.NONE) idleTicks = 0;
		getNavigation().stop();
	}

	/** Where it walks for its plan, or null (it stays). */
	private net.minecraft.world.phys.@Nullable Vec3 target() {
		return switch (plan) {
			case COME -> {
				Player p = summoner();
				if (p == null) yield null;
				comePlayer = p.getUUID();
				// a spot near the player, kept until they move off (AgentCraft's fan-out round the player)
				if (comeSpot == null || comeFor == null || comeFor.distanceTo(p.position()) > StewardSpots.FOLLOW_SLACK) {
					comeSpot = StewardSpots.userSpot(level(), p.position(), position());
					comeFor = p.position();
				}
				yield comeSpot;
			}
			case WORK -> work == null ? null : net.minecraft.world.phys.Vec3.atBottomCenterOf(work);
			case SLEEP -> bed == null ? null : net.minecraft.world.phys.Vec3.atBottomCenterOf(bed);
			case SIT -> seat == null ? null : net.minecraft.world.phys.Vec3.atBottomCenterOf(seat);
			case HOME -> hasHome() ? net.minecraft.world.phys.Vec3.atBottomCenterOf(getHomePosition()) : null;
			case NONE -> null;
		};
	}

	/** How close is close enough for each plan. */
	private double reach() {
		return switch (plan) {
			case COME -> 0.8;
			case WORK -> WORK_REACH;
			case SLEEP, SIT -> 1.6;
			case HOME -> HOME_RADIUS - 2;
			case NONE -> 0;
		};
	}

	/** Arrived where it sits or sleeps: onto the seat or into the bed. */
	private void settle() {
		if (plan == Plan.SIT && seat != null) {
			// on a stair: a little forward of its raised back, facing down the step (AgentCraft's sit point); a slab: its middle
			var st = level().getBlockState(seat);
			double x = seat.getX() + 0.5, z = seat.getZ() + 0.5;
			float yaw = getYRot();
			if (st.getBlock() instanceof net.minecraft.world.level.block.StairBlock) {
				var f = st.getValue(net.minecraft.world.level.block.StairBlock.FACING);
				x -= f.getStepX() * 0.15;
				z -= f.getStepZ() * 0.15;
				yaw = f.getOpposite().toYRot();
			}
			hold(x, seat.getY() + 0.5, z, yaw);
		} else if (plan == Plan.SLEEP && bed != null) {
			// on the mattress of the foot half: the renderer lays the body from there towards the head (checked from above in the e2e)
			var facing = StewardSpots.bedFacing(level(), bed);
			BlockPos foot = bed.relative(facing.getOpposite());
			hold(foot.getX() + 0.5, foot.getY() + 0.6875, foot.getZ() + 0.5, facing.getOpposite().toYRot());
		}
	}

	/** Settles at a spot: no physics while seated or lying, so a stair's back or the bed does not push it off. */
	private void hold(double x, double y, double z, float yaw) {
		snapTo(x, y, z, yaw, 0);
		setYHeadRot(yaw);
		yBodyRot = yaw;
		setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		noPhysics = true;
		setNoGravity(true);
		settled = true;
	}

	/** Picks the plan every second, walks to its place, settles, faces what it attends to. Other goals (strolling, looking about) run when it has none. */
	final class LifeGoal extends Goal {
		private int repath;
		private int think;

		LifeGoal() {
			setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		private int nextCheck;

		@Override
		public boolean canUse() {
			// once a second: picking reads the world (seats, and at night beds)
			if (tickCount < nextCheck) return false;
			nextCheck = tickCount + 20;
			if (work != null && workUntil > 0 && level().getGameTime() >= workUntil) work = null;
			Plan p = pick();
			setPlan(p);
			return p != Plan.NONE;
		}

		@Override
		public boolean canContinueToUse() {
			return plan != Plan.NONE;
		}

		@Override
		public void start() {
			repath = 0;
			think = 0;
		}

		@Override
		public void tick() {
			if (--think <= 0) {
				think = 20;
				if (work != null && workUntil > 0 && level().getGameTime() >= workUntil) work = null;
				Plan p = pick();
				setPlan(p);
				if (p == Plan.NONE) return;
			}
			var t = target();
			if (plan == Plan.COME && comePlayer != null) {
				Player pl = level().getPlayerByUUID(comePlayer);
				if (pl != null) getLookControl().setLookAt(pl, 30, 30);
			} else if (t != null && !settled) {
				getLookControl().setLookAt(t.x, t.y + 1, t.z);
			}
			if (settled || t == null) return;
			double r = reach();
			if (distanceToSqr(t) <= r * r) {
				getNavigation().stop();
				settle();
				return;
			}
			if (--repath <= 0) {
				repath = 20;
				getNavigation().moveTo(t.x, t.y, t.z, plan == Plan.COME ? 0.8 : 0.7);
			}
		}

		@Override
		public void stop() {
			getNavigation().stop();
		}
	}
}
