package dev.larattalabs.steward.service;

import dev.larattalabs.steward.model.Words;
import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.Batch;
import dev.larattalabs.architect.api.BatchView;
import dev.larattalabs.architect.api.BibleJob;
import dev.larattalabs.architect.api.BibleRequest;
import dev.larattalabs.architect.api.FitOptions;
import dev.larattalabs.architect.api.Group;
import dev.larattalabs.architect.api.GroupRequest;
import dev.larattalabs.architect.api.LoadPolicy;
import dev.larattalabs.architect.api.Mode;
import dev.larattalabs.architect.api.RemoveOptions;
import dev.larattalabs.architect.api.LotFit;
import dev.larattalabs.architect.api.SiteEvents;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.BatchPlanner;
import dev.larattalabs.steward.gateway.CardResult;
import dev.larattalabs.steward.gateway.ConceptCardJob;
import dev.larattalabs.steward.gateway.GroupPlanner;
import dev.larattalabs.steward.gateway.MassingPlacement;
import dev.larattalabs.steward.gateway.ProgramPlanner;
import dev.larattalabs.steward.net.StewardNet;
import dev.larattalabs.steward.layout.Grid;
import dev.larattalabs.steward.layout.TerrainGrid;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.pipeline.Pipeline;
import dev.larattalabs.steward.pipeline.ResyncRules;
import dev.larattalabs.steward.pipeline.Pipeline.Command;
import dev.larattalabs.steward.pipeline.Pipeline.Event;
import dev.larattalabs.steward.pipeline.Pipeline.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import dev.larattalabs.steward.module.ModuleInstalls;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The live side of the generation pipeline for ONE settlement (a developer-driven runner until the Founding Stone exists): it turns {@link Pipeline}
 * commands into Architect API calls and Architect events into pipeline events. All callbacks arrive on the server thread (Architect's API contract). Dev scope: in
 * memory only (nothing is persisted), one village street layout, landmarks configurable.
 *
 * <p>One runner per settlement at a time ({@link #busy}); Architect events reach runners through one set of listeners registered at init ({@link #init}), and every
 * runner is dropped when the server stops. The player is looked up by UUID whenever the runner speaks, so a respawn or relog does not silence it.
 */
public final class SettlementRunner {
	private static final int CLAIM_RADIUS = Settlements.DEFAULT_RADIUS;

	private static final Map<String, SettlementRunner> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();

	/** How long an ended build stays in the inbox (how it ended, its spend), before it goes. */
	static final long ENDED_ROW_MS = 5 * 60_000;

	private static BuildStore builds = new BuildStore();
	/** The world's builds file, or null when it could not be read (then nothing is saved over it, as with the settlements file). */
	private static java.nio.file.Path buildsFile;
	private static volatile boolean resyncPending;

	/**
	 * Registers the event routing once (Fabric events cannot unregister, so runners never register their own) and the lifecycle: unfinished builds are restored
	 * at SERVER_STARTING, before any SERVER_STARTED listener runs, so Architect's catch-up events (fired in its SERVER_STARTED) find their runners whichever mod's
	 * listener runs first; on the first tick after SERVER_STARTED each restored build re-reads its group or batch ({@link ResyncRules}). Every runner is dropped when the world closes.
	 */
	public static void init() {
		SiteEvents.BIBLE_DONE.register(job -> running().forEach(r -> r.onBibleDone(job)));
		SiteEvents.GROUP_UPDATED.register(g -> running().forEach(r -> r.onGroup(g)));
		SiteEvents.GROUP_DONE.register(g -> running().forEach(r -> r.onGroup(g)));
		SiteEvents.BATCH_DONE.register(b -> running().forEach(r -> r.onBatchDone(b)));
		SiteEvents.DESIGN_DONE.register(d -> running().forEach(r -> r.onRepairDone(d)));
		// an item held up by something in its way (an animal, a player, ground not loaded): the steward says so, or it looks like nothing happens
		SiteEvents.ITEM_WAITING.register(e -> running().forEach(r -> r.onItemWaiting(e)));
		ServerLifecycleEvents.SERVER_STARTING.register(SettlementRunner::restoreAll);
		// queued, so it runs after every SERVER_STARTED listener: Architect loads its sites there and fires its catch-up
		// on the first tick, after every SERVER_STARTED listener (Architect loads its sites and queue and fires its catch-up there); server.execute from the
		// server thread would run inline, inside this listener
		ServerLifecycleEvents.SERVER_STARTED.register(server -> resyncPending = true);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(server -> {
			if (!resyncPending) return;
			resyncPending = false;
			// running(): empty while settlements are disabled for the session (STORE-FSYNC), so nothing resumes then; (batch-077 review P1-2) nor
			// once an earlier runner's resync disables them (a failed save not written back): the fence is read again before each
			resumeEach(running(), SettlementRunner::resync);
			// villages no build or history entry holds (cancelled twice before Architect answered, a history that could not be saved): taken up; never
			// when the builds could not be read (a build that did not resume is not an orphan)
			try {
				if (canSave()) VillageSweep.sweep(server);
				else Steward.LOGGER.warn("the builds could not be read, so villages without a build are not looked for this session");
			} catch (RuntimeException e) {
				Steward.LOGGER.error("could not look for villages left without a build", e);
			}
		});
		// a village region has no done event: its state is read once a second while it is placed
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 != 0) return;
			// (batch-076 review P1-2) settlements disabled mid-session: each running build's player told once; its callbacks do nothing more
			if (Settlements.disabled() && !fenceSaid && !ACTIVE.isEmpty()) {
				fenceSaid = true;
				for (SettlementRunner r : List.copyOf(ACTIVE.values())) if (r.state != null && !r.state.phase().terminal()) r.say(Settlements.DISABLED);
			}
			for (SettlementRunner r : running()) {
				try {
					r.watchVillage();
					r.checkRepair();
				} catch (RuntimeException e) {
					Steward.LOGGER.error("could not read the village of {}", r.claimedId, e);
				}
				// an ended build's row has had its minutes: the player's inbox goes without it
				if (r.endedAt > 0 && System.currentTimeMillis() - r.endedAt > ENDED_ROW_MS) {
					r.endedAt = -1;
					sendInbox(server, r.playerId);
				}
			}
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			{
				List.copyOf(ACTIVE.values()).forEach(r -> r.deliverUnread(handler.getPlayer()));
				sendInbox(server, handler.getPlayer().getUUID());
			});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ACTIVE.clear();
			dev.larattalabs.steward.gateway.VPlan.clear();
			dev.larattalabs.steward.gateway.PPlan.clear();
			dev.larattalabs.steward.gateway.DevLayouts.clear();
			resyncPending = false;
			fenceSaid = false;
			builds = new BuildStore();
			buildsFile = null;
		});
	}

	/**
	 * (batch-077 review P1-2) The first tick's resumes, one runner after another, each only while settlements are not disabled: an earlier one's resync
	 * can disable them (a failed save not written back), and a later one must then neither queue nor place. Its failures are logged, the next goes on.
	 */
	static void resumeEach(List<SettlementRunner> runners, java.util.function.Consumer<SettlementRunner> resync) {
		for (SettlementRunner r : runners) {
			if (Settlements.disabled()) {
				Steward.LOGGER.error("settlements were disabled while builds resumed: {} and the builds after it resume next session", r.claimedId);
				return;
			}
			try {
				resync.accept(r);
			} catch (RuntimeException e) {
				Steward.LOGGER.error("could not resume the build of {}", r.claimedId, e);
			}
		}
	}

	/** Whether the running builds were told that settlements are disabled (once a session). */
	private static boolean fenceSaid;

	/** The runners Architect's events and the ticks reach: none while settlements are disabled (batch-076 review P1-2), nothing more spent or placed. */
	static List<SettlementRunner> running() {
		return Settlements.disabled() ? List.of() : List.copyOf(ACTIVE.values());
	}

	/** (TEST) A build restored from {@code b} as this session's runner of its settlement (no server: only its guards are read). */
	static SettlementRunner restoredForTest(BuildStore.Saved b) {
		SettlementRunner r = new SettlementRunner(null, b);
		ACTIVE.put(b.settlementId(), r);
		return r;
	}

	/** (TEST) Whether this runner's callbacks still act ({@link #alive()}). */
	boolean aliveForTest() {
		return alive();
	}

	/** (TEST) The builds file this session saves to (null: none, as when it could not be read). */
	static void useBuildsFileForTest(java.nio.file.@org.jspecify.annotations.Nullable Path f) {
		buildsFile = f;
	}

	/** (TEST) No runner, as after SERVER_STOPPED. */
	static void clearForTest() {
		ACTIVE.clear();
	}

	private static void restoreAll(MinecraftServer server) {
		// (dev) the layouts the e2e set before a restart, before any build resumes
		dev.larattalabs.steward.gateway.DevLayouts.load(server);
		buildsFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("steward-builds.json");
		// a build logs its placement to its settlement for undo: with the settlements unreadable, none resumes (the builds file is left as it is)
		if (Settlements.disabled()) {
			Steward.LOGGER.error("settlements are disabled this session, so no unfinished build resumes ({} is kept for the next session)", buildsFile);
			builds = new BuildStore();
			buildsFile = null;
			return;
		}
		try {
			builds = BuildStore.load(buildsFile);
		} catch (java.io.IOException | RuntimeException e) {
			Steward.LOGGER.error("could not read {}: {}. Builds are not saved this session (the file is left untouched).", buildsFile, e.toString());
			builds = new BuildStore();
			buildsFile = null;
			return;
		}
		for (BuildStore.Saved b : builds.all()) ACTIVE.put(b.settlementId(), new SettlementRunner(server, b));
		if (!builds.all().isEmpty()) Steward.LOGGER.info("restored {} unfinished build(s)", builds.all().size());
	}

	/** Whether builds can be saved in this world (its builds file was readable): a build that cannot be saved is not started, since a restart could spend twice. */
	public static boolean canSave() {
		// (batch-076 review P1-2) nor while settlements are disabled mid-session (a failed save not written back): a build logs to its settlement
		return buildsFile != null && !Settlements.disabled();
	}

	/** Whether a settlement has a runner that has not finished (a second one would spend twice). */
	/**
	 * Whether the settlement is busy: being built, a district road being laid, an undo running, or a village of it being removed (Architect restores its
	 * land over ticks, also a removal it resumed after a restart or one asked for without Steward's undo; since 0.17.1, C23, a resumed removal always ends).
	 * One guard for everything that changes its sites.
	 */
	public static boolean busy(String settlementId) {
		SettlementRunner r = ACTIVE.get(settlementId);
		// a build that ended while its realise is still unanswered stays busy: the answer may yet bring a region (a district's is held until it does);
		// an answer that never comes is settled by looking the realise up by its key (settleLostRealise)
		if (r != null && r.state != null && r.state.phase().terminal() && r.queueInFlight) r.settleLostRealise(System.currentTimeMillis());
		return (r != null && (r.state == null || !r.state.phase().terminal() || r.queueInFlight)) || Districts.busy(settlementId) || Undo.busy(settlementId) || PendingOffers.busy(settlementId)
			|| ArchitectApi.get().regions().list("steward_mc:settlement/" + settlementId).stream()
				.anyMatch(v -> v.state() == dev.larattalabs.architect.api.RegionState.REMOVING);
	}

	/** The runner of a settlement that is being built in this session (not persisted yet), or null. */
	public static SettlementRunner active(String settlementId) {
		return ACTIVE.get(settlementId);
	}

	/** One line for the steward to say: the pipeline phase, what each building is doing and the spend, and what it waits for from the player. */
	public String statusLine() {
		if (state == null) return "Getting started.";
		String line = String.format("%s. Buildings: %s. Spent $%.2f of $%.0f.", state.phase().name().toLowerCase().replace('_', ' '), Pipeline.stageCounts(state), state.spentUsd(), state.budgetUsd());
		String hint = hint();
		return hint == null ? line : line + " " + hint;
	}

	/** How the player answers what the pipeline waits for (the stopgap commands until the inbox exists), or null. */
	private String hint() {
		if (state == null || settlement == null) return null;
		String id = settlement.id();
		return switch (Pipeline.awaiting(state)) {
			case BIBLE -> "Waiting for you: /steward approve " + id + " to design the buildings with this style.";
			case MASSINGS -> "Waiting for you: /steward approve " + id + " (all " + Words.count(lastAwaiting.size(), "massing", "massings") + "), or /steward redirect " + id + " <lot> <what to change> (lots: "
				+ String.join(", ", lastAwaiting) + "). See them on their lots: /steward show " + id + ".";
			case BUDGET -> String.format("Waiting for you: /steward raise %s <new budget in USD> (now $%.0f).", id, state.budgetUsd());
			case PLACEMENT -> "Waiting for you: /steward approve " + id + " to place it.";
			case NONE -> null;
		};
	}

	// ----------------------------------------------------------------- the player's decisions (stopgap commands until the inbox exists)

	/** Approves whatever is waiting: the style bible, every awaiting massing, or the placement. Returns what happened, for the command's feedback. */
	public String approve() {
		if (state == null) return "Nothing is waiting for you yet.";
		// the village's region holds a stage (the land changed under it since the plan): built as planned on the player's word
		if (heldStage != null && vRegionId != null) {
			String stage = heldStage, region = vRegionId;
			ArchitectApi.get().regions().nudge(region, dev.larattalabs.architect.api.WaitAction.Kind.APPROVE_STAGE).whenComplete((r, err) -> server.execute(() -> {
				if (err != null) say("Architect would not go on with the " + stage + " stage: " + said("the stage go-on", err) + ".");
				else if (!r.done()) {
					Steward.LOGGER.info("{}: the {} stage's go-on was not done: {}", settlement.id(), stage, r.message());
					say("Architect would not go on with the " + stage + " stage just now; approve it again in a moment.");
				}
				else heldStage = null;
			}));
			return "Approved: the " + stage + " stage is built as planned.";
		}
		return switch (Pipeline.awaiting(state)) {
			case BIBLE -> { feed(new Pipeline.BibleApproved()); yield "Approved the style bible; designing the buildings."; }
			case MASSINGS -> {
				List<String> lots = List.copyOf(lastAwaiting);
				feed(new Pipeline.MassingDecision(lots, Map.of(), List.of()));
				yield "Approved " + lots.size() + " massings; detailing them.";
			}
			case PLACEMENT -> {
				// (slice M3) a held bill: the bill shown becomes the bound, saved before the phase moves on (a crash between leaves it approved, never lost)
				if ((moduleBuild() || composedModules()) && !approveHeld())
					yield "Not approved: the approval could not be saved, so nothing is built under it. Approve again.";
				// (slice SD) a district's bill held for the player: the bill they were shown is the one approved
				if (districtShown != null) { districtCleared = districtShown; districtSurvival = districtShownSurvival; districtShown = null; }
				feed(new Pipeline.PlacementApproved());
				yield "Approved; placing the settlement stage by stage.";
			}
			case BUDGET -> "The design group is paused at its budget: /steward raise " + settlement.id() + " <new budget in USD>.";
			case NONE -> "Nothing is waiting for you (" + state.phase().name().toLowerCase().replace('_', ' ') + ").";
		};
	}

	/** Approves one awaiting massing (the others stay waiting). */
	public String approveLot(String lot) {
		if (state == null || Pipeline.awaiting(state) != Pipeline.Decision.MASSINGS) return "No massing is waiting for approval.";
		if (!lastAwaiting.contains(lot)) return "Lot " + lot + " is not waiting.";
		feed(new Pipeline.MassingDecision(List.of(lot), Map.of(), List.of()));
		return "Approved " + lot + ".";
	}

	/** Sends one awaiting massing back with the player's notes (the others stay waiting). */
	public String redirect(String lot, String notes) {
		if (state == null || Pipeline.awaiting(state) != Pipeline.Decision.MASSINGS) return "No massing is waiting for approval.";
		if (!lastAwaiting.contains(lot)) return "Lot " + lot + " is not waiting; waiting: " + String.join(", ", lastAwaiting) + ".";
		feed(new Pipeline.MassingDecision(List.of(), Map.of(lot, notes), List.of()));
		return "Sent " + lot + " back with your notes.";
	}

	/** Raises the design budget and resumes a group paused at its soft budget. */
	public String raise(double newBudgetUsd) {
		if (state == null || (state.groupId() == null && !state.pausedForBudget())) return "There is no design group to fund yet.";
		if (raiseInFlight) return "The last raise is still on its way to Architect; try again in a moment.";
		// the same amount retries a paused build's resume (its cap was raised, the resume failed)
		if (newBudgetUsd < state.budgetUsd() || (newBudgetUsd == state.budgetUsd() && !state.pausedForBudget()))
			return String.format("The budget is already $%.0f; give a higher one.", state.budgetUsd());
		// the soft pause is a share of the budget: a raise that leaves the spend above it pauses again at once (the phase 1 gate run did, from $30 to $35)
		double atLeast = Pipeline.minimumRaise(state);
		if (newBudgetUsd < atLeast) return String.format("At $%.0f the build would pause again at once (it pauses at %d%% and has spent $%.2f). Give at least $%.0f.",
			newBudgetUsd, (int) (dev.larattalabs.steward.model.BudgetPolicy.SOFT_FRACTION * 100), state.spentUsd(), atLeast);
		feed(new Pipeline.BudgetRaised(newBudgetUsd));
		return String.format("Budget raised to $%.0f.", newBudgetUsd);
	}

	/**
	 * (QD-2) Whether {@code /steward cancel} is what takes this settlement's capped growth up now (and {@code /steward undo} is refused as busy): its
	 * build still runs, a growth realised from materials, which {@link #cancel} takes up where it stands, refunding what it drew (A2L, RU). Once the
	 * build has ended, {@code /steward undo} does that ({@link MaterialRequests#overCap}).
	 */
	public static boolean cancelTakesUp(String settlementId, @org.jspecify.annotations.Nullable String regionId) {
		SettlementRunner r = ACTIVE.get(settlementId);
		// (QD-2 GPT review) only the runner growing the waiting target's own region: another region's growth (a district's) is never the one cancelled
		return r != null && regionId != null && regionId.equals(r.growRegion) && r.cancelTakesUp();
	}

	private boolean cancelTakesUp() {
		return state != null && !state.phase().terminal() && state.phase() != Pipeline.Phase.CANCELLING && growth() && vRegionId != null && growRegion != null
			&& ArchitectApi.get().regions().get(growRegion).map(Growths::fromMaterials).orElse(false);
	}

	public String cancel() {
		if (state == null || state.phase().terminal()) return nothingToCancel(server, settlement);
		boolean second = state.phase() == Pipeline.Phase.CANCELLING;
		// a placement still rolling back: a second cancel would end the build before what it placed is recorded (no undo could reach it, and from
		// materials it would go on using the stockpile), so it waits for the rollback
		if (second && batchId != null) return "Still stopping: what the build placed is being recorded first, so it can be undone. It ends on its own.";
		// a growth realised or in flight: the same, its revision is recorded and taken up first (never dropped with the build's save)
		if (second && growth() && (vRegionId != null || queueInFlight)) return "Still stopping: the town's change is being recorded and taken up first. It ends on its own.";
		// (slice A2L, Architect RU) a growth placed from materials is cancelled part way: its revision is taken up, what it drew refunded
		boolean fromMaterials = !second && growth() && vRegionId != null && growRegion != null
			&& ArchitectApi.get().regions().get(growRegion).map(Growths::fromMaterials).orElse(false);
		feed(new Pipeline.Cancel());
		if (second) return "Stopped waiting for Architect.";
		if (fromMaterials) return "Cancelling: the addition is taken up where it stands, and what it drew from the stockpile is refunded.";
		return state.phase() == Pipeline.Phase.CANCELLING ? "Cancelling: stopping what runs first. What is already placed stays (remove it with /steward undo " + settlement.id() + ")."
			: "Cancelled.";
	}

	private final MinecraftServer server;
	/** The dimension the build is in; the level is looked up when needed (a restored runner is made before levels exist). */
	private final String dimension;
	/** This build's id ({@code <settlement>@<start time>}), tagged on every placed site so a restart tells this build's sites from an earlier one's. */
	private final String buildId;
	private final UUID playerId;
	/** Where the player stood when the dev build started: the centre of a dev settlement (one from the Founding Stone uses its claim). */
	private final net.minecraft.core.BlockPos origin;
	private final Permission permission;
	private final CardService cards;
	private final int landmarks;
	private State state;
	private Settlement settlement;
	private VillageLayout.Plan plan;
	private String bibleJobId;
	private String groupId;
	private String batchId;
	private Set<String> landmarkIds = Set.of();
	/** The settlement id this runner holds in {@link #ACTIVE} while it is starting, before the settlement itself is known. */
	private String claimedId;
	/**
	 * Massings already approved, redirected or cancelled, as {@code lot@massing id@version}: Architect re-sends the awaiting status while a decision is in
	 * flight, and a second decision on the same massing fails. A redirect makes a new massing version, which is decided afresh.
	 */
	private final Set<String> decided = new java.util.HashSet<>();
	/** Each lot's current massing ({@code id@version}), from the last group update. */
	private final Map<String, String> massingRefs = new HashMap<>();
	/** Requests sent whose acknowledgement has not come: a cancel in the meantime is carried out when it comes. */
	private boolean bibleInFlight, groupInFlight, queueInFlight;
	/** A lookup by operation key is out (recoverByKey, cancelByKeys): a cancel does not end before it is back, as it may find work to stop. */
	private boolean recovering;
	/** Lost answers looked up by key in play, this build: bounded, so a refusal mistaken for a lost answer cannot loop. */
	private int liveRecoveries;
	static final int MAX_LIVE_RECOVERIES = 2;
	/** The newest raise sent to Architect: an older raise's failure arriving late changes nothing. */
	private int raiseSeq;
	/** A raise is on its way to Architect: the next waits for it, so a failure always goes back to a budget Architect confirmed. */
	private boolean raiseInFlight;
	/** Events the runner raises itself while a step is being carried out; fed once it is done. */
	private final java.util.ArrayDeque<Event> later = new java.util.ArrayDeque<>();
	/** The world session the runner belongs to ({@link Session}). */
	private final int session = Session.current();
	/** The lots whose massings Architect last reported as awaiting approval. */
	private List<String> lastAwaiting = List.of();
	/** The Architect site group the placement batch made (finished batches are not kept across restarts; the group is). */
	private String siteGroupId;
	/** What the runner said while its player was offline, delivered when they join ("since you were away"). */
	private final List<String> unread = new ArrayList<>();

	public SettlementRunner(MinecraftServer server, ServerLevel level, ServerPlayer player, Permission permission, CardService cards, int landmarks) {
		this.server = server;
		this.dimension = level.dimension().identifier().toString();
		this.buildId = "b" + System.currentTimeMillis();
		this.playerId = player.getUUID();
		this.origin = player.blockPosition();
		this.permission = permission;
		this.cards = cards;
		this.landmarks = landmarks;
	}

	/** A runner restored from the builds file. */
	private SettlementRunner(MinecraftServer server, BuildStore.Saved b) {
		this.server = server;
		this.dimension = b.dimension();
		this.buildId = b.buildId();
		this.playerId = b.playerId();
		this.origin = new net.minecraft.core.BlockPos(b.settlement().claim().centerX(), 0, b.settlement().claim().centerZ());
		this.permission = b.permission();
		this.cards = null;
		this.landmarks = b.landmarks();
		this.claimedId = b.settlementId();
		this.settlement = b.settlement();
		this.state = b.state();
		this.plan = b.plan();
		this.bibleJobId = b.bibleJobId();
		this.groupId = b.groupId();
		this.batchId = b.batchId();
		this.siteGroupId = b.siteGroupId();
		this.landmarkIds = Set.copyOf(b.landmarkIds());
		this.decided.addAll(b.decided());
		this.lastAwaiting = b.lastAwaiting();
		this.addition = b.addition();
		this.districtId = b.districtId();
		this.newStreet = b.laysStreet();
		this.keyed = b.usesKeys();
		this.vPlanId = b.vPlan();
		this.vRegionId = b.vRegion();
		this.districtSpecs = b.districtSpecs() == null ? null : List.copyOf(b.districtSpecs());
		this.growRegion = b.growRegion();
		this.growRid = b.growRid() == null ? 0 : b.growRid();
		this.growPosition = b.growPosition() == null ? 0 : b.growPosition();
		this.growKey = b.growKey();
		this.growZone = b.growZone();
		this.moduleBindings = b.moduleBindings();
		this.moduleOrders = b.moduleOrders();
		this.restored = true;
	}

	/** Restored from the builds file after a restart (its accepting hooks are gone). */
	private boolean restored;

	private ServerLevel level() {
		ServerLevel l = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.parse(dimension)));
		if (l == null) throw new IllegalStateException("dimension " + dimension + " is not loaded");
		return l;
	}

	/** Saves the build (or drops it once finished). Called after every step and whenever an Architect id arrives. */
	private boolean persist() {
		if (!alive()) return false;
		if (settlement == null || state == null || plan == null) return true;
		boolean ended = cache();
		if (buildsFile == null) return false;
		try {
			builds.save(buildsFile);
			// the pin goes only once the ended build is gone from the save (a crash before keeps a saved build whose approval still stands)
			if (ended) releasePin();
			return true;
		} catch (java.io.IOException e) {
			Steward.LOGGER.warn("could not save {}", buildsFile, e);
			return false;
		}
	}

	/**
	 * The build as it stands now, put into the shared store (or dropped from it once finished), without writing the file; whether it ended. (M5b
	 * GPT review P2) A state rolled back after a failed save is put back with this, so another build's save never writes the rejected one.
	 */
	private boolean cache() {
		boolean ended = state.phase().terminal();
		if (ended) builds.remove(settlement.id());
		else builds.put(new BuildStore.Saved(settlement.id(), buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId,
			List.copyOf(landmarkIds), List.copyOf(decided), lastAwaiting, addition, districtId, newStreet, keyed, vPlanId, vRegionId, districtSpecs, growRegion,
			growth() ? growRid : null, growth() ? growPosition : null, growKey, moduleBindings, moduleOrders, growZone));
		return ended;
	}

	/** An approved plan's irrigated lots' reserved cells (Architect IRR), from its artifact; none for any other build. */
	/** The approved artifact's wall lots' {@code architect:wallLot} exts (Architect K3), for their design requests; none without an artifact. */
	private Map<String, JsonObject> wallLotExts() {
		String sha = dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId);
		if (sha == null) return Map.of();
		var body = ArchitectApi.get().regions().artifact(sha).map(dev.larattalabs.architect.api.PlanArtifact::body).orElse(null);
		if (body == null || !body.has("lots") || !body.get("lots").isJsonArray()) return Map.of();
		return dev.larattalabs.steward.gateway.PPlan.wallLots(body.getAsJsonArray("lots"));
	}

	/** The approved artifact's lots that hang inverted (Architect CS: {@code $.lots[].inverted}); none without an artifact. */
	private java.util.Set<String> invertedLots() {
		String sha = dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId);
		if (sha == null) return java.util.Set.of();
		var body = ArchitectApi.get().regions().artifact(sha).map(dev.larattalabs.architect.api.PlanArtifact::body).orElse(null);
		if (body == null || !body.has("lots") || !body.get("lots").isJsonArray()) return java.util.Set.of();
		var out = dev.larattalabs.steward.gateway.PPlan.invertedLots(body.getAsJsonArray("lots"));
		if (!out.isEmpty()) Steward.LOGGER.info("inverted lots of {}: {} (invertedDesigns {})", settlement.id(), out, invertedDesigns());
		return out;
	}

	/** Whether this Architect reads {@code DesignRequest.inverted} (feature {@code invertedDesigns}, API 1.65.0). */
	private static boolean invertedDesigns() {
		return ArchitectApi.get().features().contains(dev.larattalabs.steward.gateway.PPlan.INVERTED_FEATURE);
	}

	private Map<String, dev.larattalabs.architect.api.DesignRequest.Reserved> reservedCells() {
		String sha = dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId);
		if (sha == null) return Map.of();
		var body = ArchitectApi.get().regions().artifact(sha).map(dev.larattalabs.architect.api.PlanArtifact::body).orElse(null);
		if (body == null || !body.has("lots") || !body.get("lots").isJsonArray()) return Map.of();
		var out = dev.larattalabs.steward.gateway.PPlan.reserved(body.getAsJsonArray("lots"));
		if (!out.isEmpty()) Steward.LOGGER.info("reserved cells for {}: {}", settlement.id(), out.entrySet().stream().map(e -> e.getKey() + " " + e.getValue().cells().size())
			.collect(Collectors.joining(", ")));
		return out;
	}

	/** Whether this runner still owns its slot in the running world: a callback for a closed world, or for a runner replaced since, changes nothing. */
	private boolean alive() {
		// (batch-076 review P1-2) settlements disabled mid-session: the build neither spends nor places more, and is inert (an answer that arrives is
		// neither acted on nor cancelled: its request is found by its key when the build resumes from its save next session)
		return !Settlements.disabled() && Session.is(session) && claimedId != null && ACTIVE.get(claimedId) == this;
	}

	/** After a restart: gather what Architect kept, let {@link ResyncRules} decide, and carry it out. Bible and group results also arrive as Architect's catch-up events. */
	private void resync() {
		// (batch-077 review P1-2) settlements disabled (by an earlier runner's resync, or before): nothing is resumed, queued or placed; the build stays
		// saved and resumes next session
		if (Settlements.disabled()) {
			Steward.LOGGER.warn("resume {}: settlements are disabled this session, so the build resumes next session", claimedId);
			return;
		}
		var api = ArchitectAccess.api();
		var sites = api.sites(server);
		say("Picking up the build of " + settlement.name() + " where it stopped (" + state.phase().name().toLowerCase().replace('_', ' ') + ").");
		var batch = batchId == null ? java.util.Optional.<BatchView>empty() : sites.batch(batchId);
		ResyncRules.BatchSeen seen = batch.isEmpty() ? ResyncRules.BatchSeen.ABSENT : batch.get().running() ? ResyncRules.BatchSeen.RUNNING : ResyncRules.BatchSeen.FINISHED;
		// this build's running batch: by its build tag (a keyed build's only), or for builds from before keys by its lots
		var running = sites.batches(settlement.owner()).stream().filter(BatchView::running).filter(b -> buildId.equals(extString(b.ext(), BatchPlanner.BUILD_EXT))
			|| (!keyed && b.items().stream().anyMatch(i -> plan.lots().stream().anyMatch(l -> l.id().equals(i.itemKey()))))).findFirst();
		List<ResyncRules.StageSeen> stages = siteGroupId == null ? List.of() : sites.group(siteGroupId).map(g -> g.stages().stream()
			.filter(st -> batchId == null || batchId.equals(st.batchId())).map(st -> new ResyncRules.StageSeen(st.name(), st.state().terminal())).toList()).orElse(List.of());
		List<String> placed = placedSites(sites);
		// no decision is in flight after a restart: one that reached Architect is no longer awaiting, one that did not is asked for (or made) again
		decided.clear();
		// a village's plan, used again: Architect sweeps what goes unused (close-out A), and this build may still wait on the player for weeks
		if (village()) touchPlan();
		// a village's placement is its region: read again, found by the build's tag, or realised when it never was. Architect keeps its regions over a
		// restart, so a placement approved but not found was never realised: it is realised now, with the designs already made
		if (village() && switch (state.phase()) { case READY_TO_PLACE, AWAITING_PLACEMENT_APPROVAL, PLACING -> true; default -> false; }) {
			Steward.LOGGER.info("resume {}: village {} (region {}), phase {}", settlement.id(), vPlanId, vRegionId, state.phase());
			switch (state.phase()) {
				case AWAITING_PLACEMENT_APPROVAL -> { }
				// (slice M3) a module build was approved at Accept: bound (by its saved keys) and quoted again, then placed, never asked again
				case READY_TO_PLACE -> { if (vRegionId != null) watchVillage(); else if (!findVillage()) { if (moduleBuild()) realiseVillage(); else queueVillage(!permission.needsApproval(Permission.Action.NEW_PROJECT)); } }
				// not found by its key: realised again under the same key (Architect answers a realise it has with that region, never a second one), unless
				// something of it is in the world without its record (a batch of this build in any state, or a site, tiles included, of a region Architect
				// does not list): then it is not placed twice
				default -> {
					if (vRegionId != null) watchVillage();
					else if (findVillage()) { }
					else if (sites.batches(settlement.owner()).stream().anyMatch(b -> buildId.equals(extString(b.ext(), BatchPlanner.BUILD_EXT)))
						|| sites.list(settlement.owner(), true).stream().anyMatch(x -> x.region() != null && api.regions().get(x.region()).isEmpty())) {
						interrupted("the village's placement (something of it is in the world, but Architect has no record of it)");
					}
					else realiseVillage();
				}
			}
			return;
		}
		// (Architect K3) a wall lot's repair: never placed alongside it; read again (a stop waiting is sent again), and the pipeline moves on when it ends
		if (state.repair() != null) {
			Steward.LOGGER.info("resume {}: wall repair {} ({})", settlement.id(), state.repair().key(), state.phase());
			if (state.repair().stopping()) execute(new Pipeline.StopRepair(state.repair().key(), state.repair().designId()));
			else checkRepair();
			return;
		}
		// a village being cancelled: its region, when it has one, is taken up; else the cancel goes on as any build's (a bible or group still running is stopped)
		if (village() && state.phase() == Pipeline.Phase.CANCELLING) {
			if (vRegionId != null) { cancelVillage(); return; }
			// found: adopting it takes it up (the build is cancelling)
			if (findVillage()) return;
		}
		ResyncRules.Action action = ResyncRules.decide(state.phase(), bibleJobId != null, groupId != null, batchId != null, seen, running.isPresent(), stages, placed.size(),
			state.groupId() == null && state.pausedForBudget());
		Steward.LOGGER.info("resume {}: phase {}, batch {}, stages {}, placed {} -> {}", settlement.id(), state.phase(), seen, stages.size(), placed.size(), action);
		switch (action) {
			case NONE, WAIT_FOR_BATCH -> { }
			case REREAD_BIBLE -> api.bibles().job(bibleJobId).ifPresentOrElse(j -> { if (j.finished()) onBibleDone(j); },
				() -> interrupted("the style bible (Architect no longer has its job)"));
			case RESUME_CANCEL -> {
				// the batch finished and is gone: what it placed is logged (undo needs it) before the cancel ends
				if (batchId != null && seen == ResyncRules.BatchSeen.ABSENT && !placed.isEmpty()) {
					if (!logPlaced(placedSites(sites, true), placed.size())) { logFailed(); return; }
					batchId = null;
					feed(new Pipeline.CancelConfirmed());
				} else if (batchId != null) execute(new Pipeline.CancelBatch());
				else if (groupId != null) execute(new Pipeline.CancelGroup(groupId));
				else if (bibleJobId != null) execute(new Pipeline.CancelBible());
				// nothing saved: whatever Architect received is found by its key and stopped first
				else if (keyed) cancelByKeys();
				else feed(new Pipeline.CancelConfirmed());
				drainLater();
			}
			case REREAD_GROUP -> api.designs().group(groupId).ifPresentOrElse(this::onGroup, () -> interrupted("design group " + groupId + " (Architect no longer has it)"));
			case ADOPT_RUNNING_BATCH -> {
				batchId = running.get().id();
				siteGroupId = running.get().group();
				if (state.phase() == Pipeline.Phase.READY_TO_PLACE) feed(new Pipeline.BatchQueued(batchId));
				else persist();
			}
			case FINISH_FROM_BATCH -> onBatchDone(batch.get());
			case FINISH_FROM_SITES -> {
				if (!logPlaced(placedSites(sites, true), placed.size())) { logFailed(); return; }
				batchId = null;
				feed(new Pipeline.BatchDone(placed.size(), Math.max(0, plan.lots().size() - placed.size())));
			}
			case REQUEUE -> {
				// a placement Architect received (running or ended) is taken over, not fitted and sent again with another body
				var byKey = keyed ? sites.batchByKey(settlement.owner(), opKey("batch")) : java.util.Optional.<BatchView>empty();
				if (byKey.isPresent()) { say("Architect had the placement: picking it up."); adoptBatch(byKey.get()); }
				else if (!keyed || mayResend()) fitAndQueue(!permission.needsApproval(Permission.Action.NEW_PROJECT));
				else interrupted("the placement (too long ago to look up safely)");
			}
			case INTERRUPTED -> recoverByKey();
		}
	}

	/** The building sites this build placed: tagged with its build id (the street road is not a building). */
	private List<String> placedSites(dev.larattalabs.architect.api.Sites sites) {
		return placedSites(sites, false);
	}

	private List<String> placedSites(dev.larattalabs.architect.api.Sites sites, boolean withStreet) {
		return sites.list(settlement.owner()).stream().filter(v -> buildId.equals(extString(v.ext(), BatchPlanner.BUILD_EXT)) && (withStreet || !BatchPlanner.isStreet(v.itemKey())))
			.map(dev.larattalabs.architect.api.SiteView::id).toList();
	}

	private static String extString(JsonObject ext, String key) {
		return ext != null && ext.has(key) && ext.get(key).isJsonPrimitive() ? ext.get(key).getAsString() : null;
	}

	/** Architect's flat village (slice V) this build is laid out on: its plan, and the region once realised. Null for Steward's own layout. */
	private @org.jspecify.annotations.Nullable String vPlanId, vRegionId;
	/**
	 * (slice PV1, docs/GROWTH.md "Plan v1 growth") The addition's lot from the town's composed plan: when the kept plan is the one the town region was built
	 * from ({@link PlanGrowth#approvedFor}) and names a lot of the asked type the region doesn't hold yet, that lot (its id, size, role and brief) on its zone's
	 * streets; otherwise {@code specs} as they are. A module, a district's build, a growth restored or re-planned never take this.
	 */
	private List<VillageLayout.LotSpec> fromPlan(Settlement now, dev.larattalabs.architect.api.RegionView town, CardResult r, List<VillageLayout.LotSpec> specs) {
		if (moduleBuild() || districtId != null || specs.size() != 1 || r.card().program().size() != 1) return specs;
		if (dev.larattalabs.steward.gateway.PPlan.villageOnly(now.id())) return specs;
		var entry = PlanStore.get(now.id());
		if (entry.isEmpty()) return specs;
		JsonObject plan;
		try {
			plan = com.google.gson.JsonParser.parseString(entry.get().plan()).getAsJsonObject();
		} catch (RuntimeException e) {
			return specs;
		}
		long founded = now.log().stream().filter(e -> e.kind() == Settlement.Kind.PROJECT_PLACED && ("region:" + town.id()).equals(e.siteGroup()))
			.mapToLong(Settlement.LogEntry::at).min().orElse(-1);
		java.util.Set<String> all = new java.util.HashSet<>(), first = new java.util.HashSet<>();
		for (var l : town.lots()) {
			all.add(l.id());
			if (l.revision() == 0) first.add(l.id());
		}
		if (founded < 0 || !PlanGrowth.approvedFor(entry.get().at(), founded, first, plan)) return specs;
		java.util.Set<String> waiting = new java.util.HashSet<>();
		for (var p : entry.get().pending()) waiting.add(p.part());
		var b = r.card().program().get(0);
		var pick = PlanGrowth.pick(plan, waiting, Secrets.of(entry.get()), all, b.type(), b.role(), ModuleOffers.moduleTypes());
		if (pick.isEmpty()) return specs;
		planLot = true;
		growZone = pick.get().growZone();
		Steward.LOGGER.info("growth {} of {}: from its plan, lot {} ({} {}x{}) in zone {}{}{}", buildId, town.id(), pick.get().spec().id(), pick.get().spec().type(),
			pick.get().spec().sizeX(), pick.get().spec().sizeZ(), pick.get().zone(), pick.get().district() == null ? "" : " district " + pick.get().district(),
			growZone == null ? " (its zone doesn't stand: the town's streets)" : "");
		return List.of(pick.get().spec());
	}

	/**
	 * A standing region grows (docs/GROWTH.md, slices A, A2 and DG): the town, or {@code district}'s own region. The same checks for both: being changed
	 * waits; the mode gate (the region's own mode against the world's); a region built from materials only once built, from the settlement's stockpile,
	 * its crates kept clear; then the plan. Nothing is spent before the plan.
	 */
	private void growStanding(CardResult r, double budgetUsd, Settlement now, dev.larattalabs.architect.api.RegionView v, List<VillageLayout.LotSpec> specs,
		boolean worldSurvival, Settlement.@org.jspecify.annotations.Nullable District district) {
		String name = district == null ? now.name() : district.name();
		String what = district == null ? "The town" : district.name();
		boolean inFlight = v.revisions() != null && v.revisions().stream().anyMatch(x -> !Growths.ended(x.state())
			&& !"UNDONE".equalsIgnoreCase(x.state()) && !"STALE".equalsIgnoreCase(x.state()));
		// a region being changed waits, whatever it is built from (never Steward's street pieces beside a change in flight)
		if ((v.busy() != null && !v.busy().isBlank()) || inFlight) {
			abandon(what + " is being changed right now; ask again once it has settled.");
			return;
		}
		// (slice A2) a region grows only in the mode it was built in (Architect plans in the world's mode, realises in the region's), and one built from
		// materials only once all of it is built: else nothing is spent
		boolean regionMaterials = worldSurvival && server.getDefaultGameType() != net.minecraft.world.level.GameType.CREATIVE;
		String no = Growths.modeRefusal(name, Growths.fromMaterials(v), regionMaterials, v.build() != null && v.build().built(), v.build() == null ? 0 : v.build().percent());
		if (no != null) {
			// (slice SST) a town from materials still being built: watched, so the steward says once when it is built and can grow
			if (Growths.fromMaterials(v) && regionMaterials && v.build() != null && !v.build().built()) MaterialRequests.watchBuild(now.id(), v.id());
			abandon(no);
			return;
		}
		// built from materials: its targets draw from the settlement's stockpile, made again if it went
		if (Growths.fromMaterials(v)) {
			var sp = Stockpiles.ensure(server, now, this::say);
			if (sp == null) {
				abandon(what + " is built from materials and grows from the stockpile, and none could be made by the founding stone. Clear a spot beside it and start again.");
				return;
			}
			// the growth keeps off the crates and the ring round them (a protected area, which Architect's growth keeps clear of), as planComposed
			String held = Stockpiles.protectCrates(server, Settlements.store().get(now.id()).orElse(now), sp, true);
			if (held != null) { abandon("The stockpile's ground could not be kept clear of the growth (" + held + "). Start again."); return; }
		}
		planGrowth(r, budgetUsd, v.id(), specs, null);
	}

	/**
	 * A region town's growth (docs/GROWTH.md): the town region, the revision's rid and position as planned, and the key that planned it ({@code vgrow},
	 * {@code vgrow2}, {@code vgrow3}). Saved with the build; null for any other build.
	 */
	private @org.jspecify.annotations.Nullable String growRegion, growKey;
	private int growRid, growPosition;
	/**
	 * (slice PV1) A town growth from its composed plan: the plan zone it grows on ({@code grow.zone}; null for the town's streets; BuildStore format 7), and
	 * whether this build's lot is the plan's own (sent under the plan's lot id, never renamed; not saved: the saved lots are sent as they are).
	 */
	private @org.jspecify.annotations.Nullable String growZone;
	private boolean planLot;
	/** Module lots' bindings (docs/MODULES.md 4.2 step 4; BuildStore format 5): carried through every save. Made by growth placement (slice M3). */
	private java.util.Map<String, dev.larattalabs.steward.module.ModuleBinding> moduleBindings = java.util.Map.of();
	/**
	 * (slice M3) What the player accepted for each module lot (BuildStore format 6): the catalog version, program, params and in survival the approved
	 * bound. Set when the growth is planned, saved with the build; empty for any other build.
	 */
	private java.util.Map<String, dev.larattalabs.steward.module.ModuleOrder> moduleOrders = java.util.Map.of();
	/** (slice M3) A module build's order before its lot has an id (from {@link #startModule} until the growth is planned). */
	private dev.larattalabs.steward.module.@org.jspecify.annotations.Nullable ModuleOrder pendingModule;
	/** (slice M3) A bind or a quote is out: the realise waits for it, and nothing starts a second. */
	private boolean moduleBusy;
	/** (slice M3) The plan and entries whose quote passed this session ({@code planId|lot=entry,...}): the realise goes on only for exactly them. */
	private @org.jspecify.annotations.Nullable String quoteCleared;
	// (QD) Architect refused the realise at put-down (BILL_CAP: its bill then was past the approved bound): the next quote is put to the player
	// whatever it says, never realised on its own. (QD-2) Saved with the build on the module order (ModuleOrder#refused), so a restart asks too.
	/** (QD) Architect's features for a module's survival bill: the terrain part of a quote, and a realise held to a cap. */
	static final String QUOTE_TERRAIN = "quoteTerrain", MAX_BILL = "maxBill";

	/**
	 * Whether this build places module lots alone (slice M3): its lots come from the catalog, never a design, at $0. A composed town that also holds module
	 * lots is not one ({@link #composedModules}): its other lots are designed.
	 */
	private boolean moduleBuild() {
		return (pendingModule != null || !moduleOrders.isEmpty()) && !composedModules();
	}

	/**
	 * (slice M5, docs/MODULES.md "M5 design") Whether this build is a town from its composed plan (plan v1, an approved artifact) that holds module lots:
	 * those are bound to the catalog at the realise and never designed; its other lots are designed as for any plan v1 town.
	 */
	private boolean composedModules() {
		return !moduleOrders.isEmpty() && pendingModule == null && dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId) != null;
	}

	/** (slice M5) The plan the design group designs: the build's plan without its module lots (a module lot is never designed). */
	private VillageLayout.Plan designPlan() {
		if (!composedModules() || plan == null) return plan;
		return new VillageLayout.Plan(plan.lots().stream().filter(l -> !moduleOrders.containsKey(l.id())).toList(), plan.streetZ(), plan.streetX0(), plan.streetX1(),
			plan.streetY(), plan.unplaced(), plan.northSouth());
	}

	private boolean growth() {
		return growRegion != null;
	}
	/** (Architect RS) This build lays a Supplied settlement out from its composed plan: a plan that can't be used falls back to Steward's own layout, never the flat village. */
	private boolean supplied;

	private boolean village() {
		return vPlanId != null;
	}

	/** Whether this build's requests carry operation keys: every build started now; one restored from before keys does not. */
	private boolean keyed = true;

	/** The operation key of this build's {@code kind} of request (bible, group, batch): Architect finds the request by it after a restart (C9). */
	/** Whether a build with this id is running (restored or new) this session. */
	static boolean running(String buildId) {
		return ACTIVE.values().stream().anyMatch(r -> buildId.equals(r.buildId));
	}

	/** Whether a build in this session still holds its placement (not ended, or its realise still unanswered): an ended runner kept in ACTIVE does not. */
	static boolean holds(String buildId) {
		return ACTIVE.values().stream().anyMatch(r -> buildId.equals(r.buildId) && (r.state == null || !r.state.phase().terminal() || r.queueInFlight));
	}

	String opKey(String kind) {
		return "steward." + buildId + "." + kind;
	}

	/**
	 * A request was sent but its id never saved (the game stopped between the two): Architect is asked for it by its operation key. Found, it is adopted;
	 * never received, it is sent again under the same key (so it can only be made once). Builds from before keys, or a helper that is not connected, are
	 * reported interrupted as before.
	 */
	private void recoverByKey() {
		recoverByKey(0, false);
	}

	/** Lookups need the helper connected, which it may not be yet just after a restart: tried again every 5 s, six times, before giving up. */
	private static final int RECOVER_TRIES = 6;
	/** Architect keeps an operation key at least 30 days after its work ends: older than this, an empty lookup proves nothing, so nothing is sent again. */
	static final long KEY_TRUST_MS = 25L * 24 * 3600_000;

	private void recoverLater(int attempt, boolean live, String what, Throwable err) {
		if (attempt + 1 >= RECOVER_TRIES) { interrupted(what + " (" + said("the lookup of " + what, err) + ")"); return; }
		recovering = true;
		java.util.concurrent.CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS).execute(() -> server.execute(() -> {
			recovering = false;
			if (alive()) recoverByKey(attempt + 1, live);
		}));
	}

	/** When the build began (its id is "b" + the time), or 0 when that cannot be read. */
	long startedAt() {
		try {
			return Long.parseLong(buildId.substring(1));
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/** Whether a request that Architect has no record of may be sent again: the build uses keys, and is young enough that its keys are still kept. */
	private boolean mayResend() {
		long at = startedAt();
		return keyed && at > 0 && System.currentTimeMillis() - at < KEY_TRUST_MS;
	}

	/**
	 * A request was sent but its id never saved (a restart came between the two, or its acknowledgement was lost, {@code live}): Architect is asked for
	 * it by its operation key. Found, it is adopted (or, when the build is being cancelled or has ended, cancelled). Never received, it is sent again under
	 * the same key (so it can only be made once), unless the build is ending or too old for its keys to be trusted. Builds from before keys are reported
	 * interrupted as before.
	 */
	private void recoverByKey(int attempt, boolean live) {
		if (!keyed) {
			interrupted(switch (state.phase()) {
				case BIBLE_RUNNING -> "the style bible request (it may still be made and charged)";
				case GROUP_RUNNING, AWAITING_MASSING_APPROVAL -> "the design group request";
				default -> "the placement";
			});
			return;
		}
		var api = ArchitectApi.get();
		String owner = settlement.owner();
		switch (state.phase()) {
			case BIBLE_RUNNING -> { recovering = true; api.bibles().jobByKey(owner, opKey("bible")).whenComplete((job, err) -> {
				recovering = false;
				if (!alive()) return;
				if (err != null) { recoverLater(attempt, live, "the style bible request (it may still be made and charged)", err); return; }
				// the phase may have moved on while the lookup was out (a cancel): handled with what was found
				if (state.phase() != Pipeline.Phase.BIBLE_RUNNING) { settleFound(job.map(j -> j.id()).orElse(null), null, null); return; }
				if (job.isPresent()) {
					say("Architect had the style bible request: picking it up.");
					bibleJobId = job.get().id();
					paidRequestAcked = true;
					persist();
					if (job.get().finished()) onBibleDone(job.get());
				} else if (mayResend()) {
					say("The style bible request never reached Architect: asking again.");
					execute(Pipeline.bibleRequest(state));
				} else interrupted("the style bible request (too long ago to look up safely)");
			}); }
			case GROUP_RUNNING, AWAITING_MASSING_APPROVAL -> { recovering = true; api.designs().groupByKey(owner, opKey("group")).whenComplete((g, err) -> {
				recovering = false;
				if (!alive()) return;
				if (err != null) { recoverLater(attempt, live, "the design group request", err); return; }
				if (state.phase() != Pipeline.Phase.GROUP_RUNNING && state.phase() != Pipeline.Phase.AWAITING_MASSING_APPROVAL) {
					settleFound(null, g.map(Group::id).orElse(null), null);
					return;
				}
				if (g.isPresent()) {
					say("Architect had the design group request: picking it up.");
					adoptGroup(g.get());
				} else if (mayResend()) {
					say("The design group request never reached Architect: asking again.");
					execute(Pipeline.groupRequest(state));
				} else interrupted("the design group request (too long ago to look up safely)");
			}); }
			case CANCELLING -> cancelByKeys();
			default -> {
				var b = api.sites(server).batchByKey(owner, opKey("batch"));
				if (b.isEmpty()) {
					// in play (its acknowledgement lost) the placement is simply asked for again, under the same key; after a restart, said as before
					if (live && mayResend()) fitAndQueue(!permission.needsApproval(Permission.Action.NEW_PROJECT));
					else interrupted("the placement");
					return;
				}
				say("Architect had the placement: picking it up.");
				adoptBatch(b.get());
			}
		}
	}

	/** Takes over a group Architect had: its id, which items are landmarks (the landmark stage comes first), and its state. */
	private void adoptGroup(Group g) {
		groupId = g.id();
		landmarkIds = g.items().stream().filter(i -> i.role() == GroupRequest.Role.LANDMARK).map(Group.Item::itemKey).collect(Collectors.toSet());
		paidRequestAcked = true;
		persist();
		if (state.phase() == Pipeline.Phase.CANCELLING) { execute(new Pipeline.CancelGroup(g.id())); return; }
		onGroup(g);
	}

	/** Takes over a batch Architect had: running, it is this build's placement; finished, its outcome. */
	private void adoptBatch(BatchView b) {
		batchId = b.id();
		siteGroupId = b.group();
		persist();
		if (b.running()) {
			if (state.phase() == Pipeline.Phase.READY_TO_PLACE) feed(new Pipeline.BatchQueued(batchId));
		} else onBatchDone(b);
	}

	/** The build moved on (a cancel) while a lookup was out: what was found is stopped, and a cancel in progress learns there is nothing more to wait for. */
	private void settleFound(@org.jspecify.annotations.Nullable String jobId, @org.jspecify.annotations.Nullable String foundGroup,
		@org.jspecify.annotations.Nullable String foundBatch) {
		var api = ArchitectApi.get();
		if (jobId != null) { bibleJobId = jobId; persist(); }
		if (foundGroup != null) { groupId = foundGroup; persist(); }
		if (foundBatch != null) { batchId = foundBatch; persist(); }
		if (state.phase() == Pipeline.Phase.CANCELLING) {
			if (jobId != null) execute(new Pipeline.CancelBible());
			else if (foundGroup != null) execute(new Pipeline.CancelGroup(foundGroup));
			else if (foundBatch != null) execute(new Pipeline.CancelBatch());
			else feed(new Pipeline.CancelConfirmed());
		} else if (state.phase().terminal()) {
			// ended meanwhile: nothing may keep running for it
			if (jobId != null) api.bibles().cancel(jobId);
			if (foundGroup != null) api.designs().cancelGroup(foundGroup);
			if (foundBatch != null) api.sites(server).cancelBatch(foundBatch);
		}
	}

	/**
	 * A cancel restored with no id saved: any of this build's requests Architect received is found by its key and stopped (a placed part is logged, as
	 * undo needs it) before the cancel ends, so nothing keeps running, or charging, for a build that is gone.
	 */
	private void cancelByKeys() {
		cancelByKeys(0);
	}

	private void cancelByKeys(int attempt) {
		var api = ArchitectApi.get();
		String owner = settlement.owner();
		recovering = true;
		api.bibles().jobByKey(owner, opKey("bible")).thenCombine(api.designs().groupByKey(owner, opKey("group")), (job, g) -> new Object[] {job, g})
			.whenComplete((pair, err) -> server.execute(() -> {
				recovering = false;
				if (!alive()) return;
				// a lookup that failed (the helper away) proves nothing: the cancel waits and asks again, keeping the build saved
				if (err != null || pair == null) {
					if (attempt + 1 >= RECOVER_TRIES) {
						say("The cancel is waiting for Architect (" + (err == null ? RefusalWords.NO_ANSWER : said("the cancel's lookup", err)) + "); the build stays saved. Cancel again later.");
						return;
					}
					recovering = true;
					java.util.concurrent.CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS).execute(() -> server.execute(() -> {
						recovering = false;
						if (alive()) cancelByKeys(attempt + 1);
					}));
					return;
				}
				@SuppressWarnings("unchecked") var job = (java.util.Optional<dev.larattalabs.architect.api.BibleJob>) pair[0];
				@SuppressWarnings("unchecked") var g = (java.util.Optional<Group>) pair[1];
				var b = api.sites(server).batchByKey(owner, opKey("batch"));
				if (b.isPresent() && !b.get().running()) {
					List<String> placed = placedSites(api.sites(server), true);
					if (!placed.isEmpty() && !logPlaced(placed, placedSites(api.sites(server)).size())) { logFailed(); return; }
				}
				settleFound(job.filter(j -> !j.finished()).map(j -> j.id()).orElse(null), g.filter(x -> !x.status().isFinal()).map(Group::id).orElse(null),
					b.filter(BatchView::running).map(BatchView::id).orElse(null));
			}));
	}

	private void interrupted(String what) {
		say("The build lost track of " + what + ". Cancel it with /steward cancel " + settlement.id() + " (what Architect received is found and stopped) and start it again.");
	}

	void deliverUnread(ServerPlayer p) {
		if (!p.getUUID().equals(playerId) || unread.isEmpty()) return;
		p.sendSystemMessage(Component.literal("While you were away (" + settlement.name() + "):"));
		for (String line : unread) p.sendSystemMessage(Component.literal("  " + line));
		unread.clear();
	}

	/**
	 * Records the placed sites in the settlement's change log as one operation, each site from version 0 to the version it stands at ({@code /steward undo}
	 * removes them). Dev builds are not saved settlements. Returns false when the log could not be saved (the build then stays unfinished, so a restart logs it
	 * again from the sites).
	 */
	private boolean logPlaced(List<String> siteIds, int buildings) {
		return logPlaced(siteIds, buildings, 0);
	}

	private boolean logPlaced(List<String> siteIds, int buildings, int notPlaced) {
		return logPlaced(siteIds, buildings, notPlaced, null);
	}

	/** {@code note}: why the placement fell short beyond the lots it skipped (a village region partial or failed); it makes the entry partial. */
	private boolean logPlaced(List<String> siteIds, int buildings, int notPlaced, @org.jspecify.annotations.Nullable String note) {
		if (siteIds.isEmpty()) return true;
		// a dev build has no saved settlement; any other build's settlement must be there, or its sites would never reach the undo log
		var saved = Settlements.store().get(settlement.id());
		if (saved.isEmpty()) return DEV_ID.equals(settlement.id());
		// (slice A2L review 2) a growth is logged once by its revision's tag, whatever sites it holds now (a cancel's batch, or a late answer, may bring more)
		if (siteGroupId != null && Growths.isGrowth(siteGroupId) && saved.get().log().stream()
			.anyMatch(e -> e.kind() == Settlement.Kind.PROJECT_PLACED && siteGroupId.equals(e.siteGroup()))) return true;
		// logged already (a restart came between the log and dropping the checkpoint): not twice
		java.util.Set<String> ids = new java.util.HashSet<>(siteIds);
		if (saved.get().log().stream().anyMatch(e -> e.kind() == Settlement.Kind.PROJECT_PLACED && new java.util.HashSet<>(e.siteIds()).equals(ids))) {
			// logged before a restart: the district's link may still be missing (a restart before it was laid is also put right when the world starts)
			if (districtId != null && saved.get().lands().district(districtId).map(Settlement.District::square).orElse(false)) Districts.link(server, settlement.id(), districtId, this::say, true);
			return true;
		}
		var sites = ArchitectAccess.api().sites(server);
		List<Settlement.SiteChange> changes = siteIds.stream().map(id -> {
			var v = sites.get(id);
			// a village lot's site carries Architect's lot id only: its role is the plan's
			String lot = v.map(x -> extString(x.ext(), "steward_mc:role")).orElse(null);
			if (lot == null) {
				String lotId = v.map(dev.larattalabs.steward.gateway.Typed::lot).orElse(null);
				if (lotId != null && plan != null) lot = plan.lots().stream().filter(l -> l.id().equals(lotId)).map(VillageLayout.Lot::role).findFirst().orElse(lotId);
			}
			return new Settlement.SiteChange(id, lot, 0, v.map(dev.larattalabs.architect.api.SiteView::version).orElse(1));
		}).toList();
		var district = districtId == null ? null : saved.get().lands().district(districtId).orElse(null);
		String where = district == null ? "" : " in " + district.name();
		var entry = Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.PROJECT_PLACED, "Placed " + Words.count(buildings, "building", "buildings")
			+ where + (notPlaced > 0 ? " (" + notPlaced + " not placed)" : "") + (note != null ? " (" + note + ")" : ""), changes, siteGroupId,
			notPlaced > 0 || note != null ? Settlement.Outcome.PARTIAL : Settlement.Outcome.DONE);
		// a district's own region: the district names it, owns what it took outside the town and stands, saved with the entry (docs/DISTRICTS.md section 4)
		if (district != null && !district.square() && vRegionId != null) {
			// (slice DG review round 2) only a whole read of the region's land ever replaces what the district owns; else it keeps its land (the sweep reads it
			// again once the region has settled)
			var read = ArchitectApi.get().regions().get(vRegionId).map(v -> dev.larattalabs.steward.gateway.DistrictLand.outsideWhole(v, district.claim().dimension()))
				.orElse(null);
			var land = java.util.Optional.ofNullable(read == null ? null : read.land());
			var res = Settlements.placeDistrict(settlement.id(), district.id(), vRegionId, land.orElse(null), entry);
			if (!res.ok()) Steward.LOGGER.warn("placing district {} of {}: {}", district.id(), settlement.id(), res.error());
			// (slice DG) a growth of a standing district: its land could not be saved with it (another settlement's overlap), so the growth is logged alone,
			// never left unlogged; its land is put right at the next growth or undo
			if (!res.ok() && growth() && Settlement.District.STANDING.equals(district.state()) && vRegionId.equals(district.region()))
				return Settlements.log(settlement.id(), entry).ok();
			return res.ok();
		}
		boolean ok = Settlements.log(settlement.id(), entry).ok();
		// a district's build laid its street: a road links it to where the district's road ends (nothing when it is linked already)
		if (ok && district != null && district.square()) Districts.link(server, settlement.id(), districtId, this::say, true);
		return ok;
	}

	private void logFailed() {
		say("Could not save the settlement's change log, so this build stays open (nothing is lost). Reopen the world to finish it.");
	}

	public State state() {
		return state;
	}

	/** Steps: concept card, survey and layout, then the pipeline. */
	public void start(String prompt, int buildings, double budgetUsd) {
		if (!claim(DEV_ID)) return;
		say("Reading your description...");
		// owned by the settlement-to-be (founded as DEV_ID once the card is in), so its cost is Steward's in Architect's jobs ledger, not Architect's own
		cards.submit(prompt, Map.of(), new ConceptCardJob.Settings(false, "patron", CLAIM_RADIUS), Settlement.ownerOf(DEV_ID)).whenComplete((r, err) -> {
			if (err != null) { abandon("Card failed: " + said("the concept card", err) + "."); return; }
			if (!r.ok()) { Steward.LOGGER.info("concept card failed: {}", r.error()); abandon("Card failed: " + r.said() + "."); return; }
			for (String l : CardResult.lines(r.card(), r.cost())) say(l);
			surveyAndStart(r, buildings, budgetUsd);
		});
	}

	/** Dev: continue a paused group from a previous run (the sidecar kept it): rebuild the card from its job, re-survey the same spot, raise the budget and resume. */
	public void resume(String groupIdToResume, String cardJobId, int buildings, double newBudgetUsd) {
		if (!claim(DEV_ID)) return;
		var api = ArchitectApi.get();
		Group g = api.designs().group(groupIdToResume).orElse(null);
		var job = api.jobs().get(cardJobId).orElse(null);
		if (g == null || job == null) { abandon("Unknown group or card job."); return; }
		CardResult r = CardResult.interpret(job);
		if (!r.ok()) { Steward.LOGGER.info("concept card job {} unreadable: {}", cardJobId, r.error()); abandon("Card job unreadable: " + r.said() + "."); return; }
		this.resumeGroup = g;
		this.resumeBudget = newBudgetUsd;
		surveyAndStart(r, buildings, newBudgetUsd);
	}

	private Settlement existing;
	/** An addition to a built settlement (a proposal's building): no new street, and the settlement's style bible is reused. */
	private boolean addition;
	/** The district the build is in (its square is surveyed and laid out), or null for the town. */
	private @org.jspecify.annotations.Nullable String districtId;
	/** Whether the build lays a street: a whole settlement, or a district's first build. */
	private boolean newStreet = true;
	/** The settlement's street (its centre line), found by {@link #markSites}: an addition is laid out on it. */
	private @org.jspecify.annotations.Nullable Integer streetZ;
	/** (slice NS2) Whether that street runs north-south ({@link #streetZ} then its x): the town's street on the legacy path, read by {@link #markSites}. */
	private boolean streetNorthSouth;
	/**
	 * For an accepted proposal: run when the pipeline is about to start (it records the acceptance and the spend; false = it could not, and nothing starts),
	 * and when the build ends having spent nothing (it gives the proposal and the spend back). Not kept across a restart.
	 */
	private java.util.function.BooleanSupplier onStart;
	private Runnable onNothingSpent;
	/** Run when the build never started (no room, a failed survey, a save that failed): an accepted proposal backs off. */
	private Runnable onAbandoned;
	/**
	 * A paid request of this build (a bible job or a design group) was sent to Architect: from then on it may have cost something, even if its
	 * acknowledgement never came (a dropped link), so an accepted proposal is not given back.
	 */
	private boolean paidRequestAcked;
	private Group resumeGroup;

	public SettlementRunner hooks(java.util.function.BooleanSupplier onStart, Runnable onNothingSpent, Runnable onAbandoned) {
		this.onStart = onStart;
		this.onNothingSpent = onNothingSpent;
		this.onAbandoned = onAbandoned;
		return this;
	}
	private double resumeBudget;

	/**
	 * Builds one more building for a settlement that is already built (an accepted proposal): its card with {@code building} as the whole program, beside
	 * what stands (the layout keeps off the settlement's sites), with no new street, in the settlement's own style bible when it has one.
	 */
	public void startAddition(Settlement s, dev.larattalabs.steward.model.ConceptCard.Building building, double budgetUsd) {
		startAddition(s, building, budgetUsd, null);
	}

	/** As above, in district {@code districtId} (its first building lays its street), or the town when null. */
	public void startAddition(Settlement s, dev.larattalabs.steward.model.ConceptCard.Building building, double budgetUsd, @org.jspecify.annotations.Nullable String districtId) {
		startAddition(s, building, budgetUsd, districtId, 1);
	}

	/** As above, with {@code buildings} of it (a quarter's, built at once). */
	public void startAddition(Settlement s, dev.larattalabs.steward.model.ConceptCard.Building building, double budgetUsd, @org.jspecify.annotations.Nullable String districtId,
		int buildings) {
		this.districtId = districtId;
		var c = s.card();
		var card = new dev.larattalabs.steward.model.ConceptCard(c.name(), c.site(), c.style(), c.purpose(), c.story(), c.constraints(), c.avoid(), c.interpretation(),
			c.contradictions(), c.assumptions(), List.of(building));
		this.addition = true;
		startExisting(new Settlement(s.id(), s.name(), card, s.claim(), s.siteVersion(), s.styleVersion(), s.purposeVersion(), s.permission(), s.difficulty(), s.log(),
			s.proposals(), s.bible(), s.autonomy(), s.lands()), Math.max(1, buildings), budgetUsd);
	}

	/**
	 * (slice PD) The first build of a district the town's approved plan named and its founding didn't build: reserved beside the town ({@code districtId}),
	 * its region planned with the plan's own lots ({@code specs}) on SD's path (planDistrict: the stockpile, the quote and one approval from materials).
	 */
	public void startPlanDistrict(Settlement s, String districtId, List<VillageLayout.LotSpec> specs, double budgetUsd) {
		if (specs.isEmpty()) throw new IllegalArgumentException("a plan district needs its lots");
		this.planSpecs = List.copyOf(specs);
		var first = specs.get(0);
		startAddition(s, new dev.larattalabs.steward.model.ConceptCard.Building(first.role(), first.type(), specs.size(), "M", false, null, null), budgetUsd, districtId,
			specs.size());
	}

	/** (slice PD) The plan district's lots this build lays out instead of the card's program; null for every other build. In memory (a restart keeps districtSpecs). */
	private @org.jspecify.annotations.Nullable List<VillageLayout.LotSpec> planSpecs;

	/**
	 * (docs/MODULES.md 4.2, slice M3) Places an accepted module offer: one module lot, the catalog's program run at $0, as an addition on growth (A, A2,
	 * or B then A). Never a design, never Steward's street pieces: a town that can't grow that way ends the build before anything is spent.
	 */
	public void startModule(Settlement s, dev.larattalabs.steward.model.ConceptCard.Building lot, dev.larattalabs.steward.module.ModuleOrder order) {
		startModule(s, lot, order, null);
	}

	/** As above, in {@code districtId} when the offer was bound into a district that grows by revision (slice DG). */
	public void startModule(Settlement s, dev.larattalabs.steward.model.ConceptCard.Building lot, dev.larattalabs.steward.module.ModuleOrder order,
		@org.jspecify.annotations.Nullable String districtId) {
		this.pendingModule = order;
		startAddition(s, lot, 0, districtId);
	}

	/** (slice M3) The module lot's spec: its exact frame [W, D] (never a footprint class), the role and the catalog's words. */
	private List<VillageLayout.LotSpec> moduleSpecs(dev.larattalabs.steward.model.ConceptCard.Building b) {
		var kind = dev.larattalabs.steward.module.ModuleKind.of(pendingModule.moduleId()).orElseThrow();
		int[] f = kind.frame(pendingModule.params());
		return List.of(new VillageLayout.LotSpec("module_1", kind.lotType(), f[0], f[1], kind.role(), b.notes(), false, null));
	}

	/** Start from a settlement the player claimed with the Founding Stone and described: its claim and card are used, no card job runs. */
	public void startExisting(Settlement s, int buildings, double budgetUsd) {
		if (!s.described()) { say("Describe " + s.id() + " first: /steward describe " + s.id() + " <your words>"); return; }
		if (!claim(s.id())) return;
		this.existing = s;
		surveyAndStart(new CardResult(s.card(), null, new dev.larattalabs.architect.api.Cost(0, 0, 0, 0, 0, 0)), buildings, budgetUsd);
	}

	private void surveyAndStart(CardResult r, int buildings, double budgetUsd) {
		// a district's build surveys and lays out the district's own square
		var district = existing != null && districtId != null ? existing.lands().district(districtId).orElse(null) : null;
		if (districtId != null && district == null) { abandon("That district is gone."); return; }
		int cx = district != null ? district.claim().centerX() : existing != null ? existing.claim().centerX() : origin.getX();
		int cz = district != null ? district.claim().centerZ() : existing != null ? existing.claim().centerZ() : origin.getZ();
		ServerLevel level = level();
		// the settlement's own claim (sized by its card, grown by Expand); a dev build uses the default
		// a one-street village stays near the stone even in a big claim (the rest of a big claim is room for districts and regions)
		int rad = district != null ? district.claim().radius()
			: Math.min(dev.larattalabs.steward.model.ClaimRules.SURVEY_RADIUS, existing != null ? existing.claim().radius() : CLAIM_RADIUS);
		BoundingBox area = new BoundingBox(cx - rad, level.getMinY(), cz - rad, cx + rad, level.getMaxY(), cz + rad);
		say("Surveying the land...");
		ArchitectApi.get().survey().sample(level, area, 1, LoadPolicy.LOADED_ONLY).whenComplete((sample, err) -> {
			// an exception in here would be swallowed by the future and leave the build silently stuck: it abandons the build, and says why
			try {
				afterSurvey(r, buildings, budgetUsd, district, rad, sample, err);
			} catch (RuntimeException e) {
				Steward.LOGGER.error("the build of {} failed after its survey", existing != null ? existing.id() : DEV_ID, e);
				abandon("The build stopped after the survey: " + e);
			}
		});
	}

	private void afterSurvey(CardResult r, int buildings, double budgetUsd, Settlement.@org.jspecify.annotations.Nullable District district, int rad,
		dev.larattalabs.architect.api.Sample sample, Throwable err) {
		{
			ServerLevel level = level();
			int cx = district != null ? district.claim().centerX() : existing != null ? existing.claim().centerX() : origin.getX();
			int cz = district != null ? district.claim().centerZ() : existing != null ? existing.claim().centerZ() : origin.getZ();
			if (err != null) { abandon("Survey failed: " + said("the survey", err) + "."); return; }
			Grid grid = TerrainGrid.fromSample(sample);
			// what the settlement has built is taken ground (its buildings and its street), with a block's margin
			if (existing != null) markSites(grid, existing, district);
			int wet = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
			for (int z = grid.z0(); z < grid.z0() + grid.depth(); z++) for (int x = grid.x0(); x < grid.x0() + grid.width(); x++) {
				if (grid.waterAt(x, z)) { wet++; continue; }
				lo = Math.min(lo, grid.heightAt(x, z)); hi = Math.max(hi, grid.heightAt(x, z));
			}
			Steward.LOGGER.info("survey: {}x{} columns, {} unusable (water or unloaded), {} loaded chunks, ground y {}..{}, trees {}", grid.width(), grid.depth(), wet, sample.chunksLoaded(), lo, hi, sample.tree().cardinality());
			Claim claim = district != null ? district.claim() : existing != null ? dev.larattalabs.steward.model.ClaimRules.withRadius(existing.claim(), rad)
				: new Claim(level.dimension().identifier().toString(), cx, cz, CLAIM_RADIUS, level.getMinY(), level.getMaxY());
			settlement = existing != null ? existing : Settlement.found(DEV_ID, r.card(), claim, permission, Difficulty.PATRON, System.currentTimeMillis());
			// landmarks run on the dearer model: the size's typical count, and at most one per four buildings
			int maxLandmarks = dev.larattalabs.steward.model.BudgetPolicy.maxLandmarks(r.card().site().size(), buildings);
			ProgramPlanner.Result program = ProgramPlanner.lots(r.card(), buildings, maxLandmarks, ModuleOffers.moduleTypes());
			if (planSpecs == null && !program.fromCard()) say("This card has no building program (it was described before programs existed), so this is a generic village. Describe it again to get buildings of its own.");
			if (planSpecs == null && !program.leftOut().isEmpty()) say("Left out at " + Words.count(buildings, "building", "buildings") + ": " + String.join(", ", program.leftOut()) + ".");
			// (slice PD) a plan district's first build: the plan's own lots (ids, sizes, labels, briefs), never the card's program
			List<VillageLayout.LotSpec> specs = planSpecs != null ? planSpecs : moduleBuild() && pendingModule != null ? moduleSpecs(r.card().program().get(0)) : program.specs();
			if (moduleBuild() && dev.larattalabs.steward.module.ModuleKind.of(pendingModule == null ? null : pendingModule.moduleId()).isEmpty()) {
				abandon("Steward doesn't know how to place that module.");
				return;
			}
			// a district keeps a wider margin (the road along its edge); its first building lays its street, east and west on the town street's line
			var rules = district != null ? new VillageLayout.Rules(5, 3, 3, dev.larattalabs.steward.model.DistrictRules.MARGIN) : VillageLayout.Rules.defaults();
			newStreet = !addition || (district != null && streetZ == null);
			Integer line = district != null && streetZ == null && ("E".equals(district.direction()) || "W".equals(district.direction())) ? district.claim().centerZ() : null;
			// Architect's flat village (slice V) for a Patron settlement's first whole build on flat ground: its lots, lanes, square and props
			// standing: any site, or a region (one whose only sites are its ground tiles is hidden from the sites' list)
			boolean stands = existing != null && (!ArchitectApi.get().sites(server).list(existing.owner()).isEmpty()
				|| ArchitectApi.get().regions().list(existing.owner()).stream().anyMatch(v -> v.state() != dev.larattalabs.architect.api.RegionState.REMOVING));
			// an addition faces the street and may reach past its ends (the street is extended to it when it is queued)
			// (slice NS2) an addition to a north-south street town (the legacy path) is laid along that street, its lots facing east or west
			if (!newStreet && streetNorthSouth) Steward.LOGGER.info("addition to {}: along its north-south street x {}", settlement.id(), streetZ);
			java.util.function.Supplier<VillageLayout.Plan> own = () -> !newStreet ? VillageLayout.plan(claim, grid, specs, rules, streetZ, Integer.MIN_VALUE, Integer.MAX_VALUE, streetNorthSouth)
				: line != null ? VillageLayout.plan(claim, grid, specs, rules, line)
				: VillageLayout.plan(claim, grid, specs, rules, district == null && dev.larattalabs.steward.gateway.VPlan.northSouthStreet(settlement.id()));
			boolean worldSurvival = dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false);
			boolean overworld = level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
			// a district beside a region town (docs/DISTRICTS.md section 9): its first building plans the district's own region beside the town
			if (district != null && !district.square()) {
				// (slice DG) a standing district region grows by revision, as a region town does (docs/GROWTH.md, slice DG)
				if (Settlement.District.STANDING.equals(district.state()) && district.region() != null && existing != null
					&& ArchitectApi.get().features().contains(Growths.FEATURE)) {
					if (!Adoption.reconcile(server, existing, true)) { abandon("The settlement's history could not be saved; try again."); return; }
					VillageSweep.growths(server, Settlements.store().get(existing.id()).orElse(existing));
					var now = Settlements.store().get(existing.id()).orElse(existing);
					var dv = ArchitectApi.get().regions().get(district.region());
					if (dv.isEmpty() || dv.get().state() == dev.larattalabs.architect.api.RegionState.REMOVED || dv.get().state() == dev.larattalabs.architect.api.RegionState.REMOVING) {
						abandon(district.name() + "'s region is gone: undo it, or found it again.");
						return;
					}
					// (slice DG) the land the growth may take: never another settlement's, nor a sibling district's (checked before anything is spent)
					String conflict = Districts.growthConflict(now, district.id(), district.region());
					if (conflict != null) { abandon(conflict + " Nothing was spent."); return; }
					growStanding(r, budgetUsd, now, dv.get(), specs, worldSurvival, district);
					return;
				}
				// (slice M3) a module grows a region or nothing: never a district's first building
				if (moduleBuild()) { abandon("A working module is placed only by growing a region, and " + district.name() + " has none yet. Nothing was spent."); return; }
				planDistrict(r, budgetUsd, district, specs);
				return;
			}
			// an addition to a region town grows the town (docs/GROWTH.md, slice A): a revision of its region, never Steward's street pieces beside it
			if (addition && district == null && existing != null && ArchitectApi.get().features().contains(Growths.FEATURE)) {
				// an adoption a lost answer or a failed save left open is closed first: its receipt makes the town a region town (slice B)
				if (!Adoption.reconcile(server, existing, true)) { abandon("The settlement's history could not be saved; try again."); return; }
				// (slice A2) a growth left without a build is logged first, so a new growth stacks above an entry, never above an unlogged revision
				VillageSweep.growths(server, Settlements.store().get(existing.id()).orElse(existing));
				var now = Settlements.store().get(existing.id()).orElse(existing);
				if (Adoption.openIntent(now).isPresent()) { abandon("The town is being changed right now; ask again once it has settled."); return; }
				var town = TownRegion.of(now).flatMap(id -> ArchitectApi.get().regions().get(id));
				if (town.isPresent()) {
					// (slice PV1) a town built from its composed plan grows the plan's own lot of the asked type when the plan has one not built yet
					growStanding(r, budgetUsd, now, town.get(), fromPlan(now, town.get(), r, specs), worldSurvival, null);
					return;
				}
				// a street town: taken on as one region first, then grown (docs/GROWTH.md, slice B); in a world that builds from materials, as a survival
				// region grown from materials (slice SST, Architect SAD); anything that can't be keeps today's path
				var as = Adoption.adoptsAs(existing.difficulty(), worldSurvival, server.getDefaultGameType() == net.minecraft.world.level.GameType.CREATIVE);
				if (as != Adoption.As.NONE && ArchitectApi.get().features().contains(Adoption.FEATURE)
					&& overworld && !dev.larattalabs.steward.gateway.VPlan.isLegacy(existing.id())) {
					var read = Adoption.read(server, existing);
					var inv = read.inventory();
					// a street that can't be taken on (bent): today's street pieces, and the player is told why
					if (inv.isEmpty() && read.why() != null) {
						Steward.LOGGER.info("adopt {}: not taken on: {}", existing.id(), read.why());
						say("The town isn't taken on as one region: " + read.why() + ". It grows along its street as before.");
					}
					if (inv.isPresent()) {
						java.util.function.Supplier<VillageLayout.Plan> legacyPlan = own;
						adoptTown(r, budgetUsd, specs, inv.get(), as == Adoption.As.SURVIVAL, () -> {
							// (slice M3) a module grows a region or nothing: never Steward's own street pieces
							if (moduleBuild()) { abandon("The town couldn't be taken on as one region, and a working module is placed only by growing one. Nothing was spent."); return; }
							plan = legacyPlan.get();
							continueWithPlan(r, budgetUsd, district);
						});
						return;
					}
				}
			}
			// (slice M3) a module is placed only as an addition on growth (A, A2, B): a town that took none of those paths ends here, before anything is spent
			if (moduleBuild()) {
				abandon("A working module is placed only by growing the town through Architect, and this town can't grow that way now (a district, a bent "
					+ "street town, a world whose mode can't take it on, or an Architect without growth). Nothing was spent.");
				return;
			}
			// (slice M5-SCULPT-2) a sculpted card takes its kept composed plan when that plan carries the shaping itself (a carved river, a ground-moving
			// form): only a composed settlement's, never the flat village from the card
			boolean planShapes = existing != null && composed(existing) && dev.larattalabs.steward.gateway.VPlan.carriesShaping(keptPlanOf(existing), waitingOf(existing));
			if (existing != null && dev.larattalabs.steward.gateway.VPlan.applies(existing.id(), existing.difficulty(), worldSurvival, overworld, addition, districtId,
				r.card().site().terrain(), planShapes, stands, lo, hi)) {
				// the settlement's composed plan, when it has one (plan v1); else Architect's flat village from the card
				if (composed(existing)) planComposed(existing, r, buildings, budgetUsd, district, claim, specs, own);
				else {
					// (slice M5-SCULPT) a kept plan this build doesn't take: its module lots are left out of the card's village, said
					// (batch-076 review P2-4) the card only when specs were laid out from it; a plan's own lots are matched as they are
					var m = withoutPlanModules(existing, planSpecs == null ? r.card() : null, specs, "its plan isn't used for this build", "as Architect's flat village from the card");
					if (m == null) return;
					planVillage(r, m.specs().size(), budgetUsd, district, claim, m.specs(), m.removed() > 0 ? streetPlan(m.specs(), claim, grid, rules, line) : own);
				}
				return;
			}
			// (Architect RS) a Supplied settlement's composed plan, built from the stockpile; else Steward's own layout as before
			// Architect builds a region from materials only with the toggle on and the world's default mode not creative
			boolean regionSurvival = worldSurvival && server.getDefaultGameType() != net.minecraft.world.level.GameType.CREATIVE;
			if (existing != null && composed(existing) && dev.larattalabs.steward.gateway.VPlan.appliesSupplied(existing.id(), existing.difficulty(), regionSurvival,
				Composer.survivalAvailable(), overworld, addition, districtId, r.card().site().terrain(), planShapes, stands, lo, hi)) {
				supplied = true;
				planComposed(existing, r, buildings, budgetUsd, district, claim, specs, own);
				return;
			}
			// (slice M5-SCULPT, docs/MODULES.md B3) a first whole build whose kept plan names working modules but doesn't take it: the card's buildings on
			// Steward's streets, each module lot left out and said before anything is spent, never designed as an ordinary building in its place
			if (existing != null && !addition && districtId == null && planSpecs == null && !moduleBuild()) {
				var m = withoutPlanModules(existing, r.card(), specs, StreetModules.why(r.card().site().terrain(), keptPlanOf(existing), planShapes, overworld, stands,
					hi - lo <= dev.larattalabs.steward.gateway.VPlan.FLAT_RANGE), "on Steward's streets");
				if (m == null) return;
				if (m.removed() > 0) {
					plan = streetPlan(m.specs(), claim, grid, rules, line).get();
					continueWithPlan(r, budgetUsd, district);
					return;
				}
			}
			plan = own.get();
			continueWithPlan(r, budgetUsd, district);
		}
	}

	/** (slice M5-SCULPT-2) The kept plan's parts that wait for Architect (none: no kept plan). */
	private static java.util.Set<String> waitingOf(Settlement s) {
		java.util.Set<String> waiting = new java.util.HashSet<>();
		PlanStore.get(s.id()).ifPresent(e -> e.pending().forEach(p -> waiting.add(p.part())));
		return waiting;
	}

	/** The settlement's kept plan as composed (null: none, or unreadable). */
	private static @org.jspecify.annotations.Nullable JsonObject keptPlanOf(Settlement s) {
		try {
			return PlanStore.get(s.id()).map(e -> com.google.gson.JsonParser.parseString(e.plan()).getAsJsonObject()).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * (slice M5-SCULPT) The card's lots without the buildings the kept plan's module lots stand for, each module lot said as left out; null when nothing
	 * is left to build (the build ends, nothing spent). The card's own lots when the plan names no module.
	 */
	private StreetModules.@org.jspecify.annotations.Nullable Result withoutPlanModules(Settlement s, dev.larattalabs.steward.model.@org.jspecify.annotations.Nullable ConceptCard card, List<VillageLayout.LotSpec> specs, String why,
		String laidOut) {
		var m = StreetModules.leaveOut(keptPlanOf(s), ModuleOffers.moduleTypes(), offeredModules(), card, specs, why, laidOut);
		for (String w : m.said()) say(w);
		if (m.removed() > 0) Steward.LOGGER.info("plan modules of {} left out of the card's layout: {} lot(s), {} left", s.id(), m.removed(), m.specs().size());
		if (m.specs().isEmpty()) {
			abandon("Every building the card asks for here is a working module of its plan, and none can be placed without the plan. Nothing was spent.");
			return null;
		}
		return m;
	}

	/** Steward's own street layout of {@code specs} for a first whole build (as {@code own} in {@link #afterSurvey}, over other lots). */
	private java.util.function.Supplier<VillageLayout.Plan> streetPlan(List<VillageLayout.LotSpec> specs, Claim claim, Grid grid, VillageLayout.Rules rules,
		@org.jspecify.annotations.Nullable Integer line) {
		return () -> !newStreet ? VillageLayout.plan(claim, grid, specs, rules, streetZ, Integer.MIN_VALUE, Integer.MAX_VALUE, streetNorthSouth)
			: line != null ? VillageLayout.plan(claim, grid, specs, rules, line)
			: VillageLayout.plan(claim, grid, specs, rules, dev.larattalabs.steward.gateway.VPlan.northSouthStreet(settlement.id()));
	}

	/**
	 * A street town taken on as one region, then grown (docs/GROWTH.md, slice B): the intent is saved in the history before Architect is asked, so a crash
	 * or a lost answer is closed by the reconcile; adopted, the receipt is saved and slice A grows it; refused outright (nothing moved), today's path
	 * ({@code legacy}); any other answer (busy, lost, the world stopping) stops the build, never today's path while the outcome is unknown.
	 */
	/** This build's adoption call is in flight (the reconcile leaves its intent open meanwhile). */
	private volatile boolean adopting;

	/** Whether a build of the settlement has an adoption call in flight. */
	public static boolean adoptInFlight(String settlementId) {
		SettlementRunner r = ACTIVE.get(settlementId);
		return r != null && r.adopting;
	}

	private void adoptTown(CardResult r, double budgetUsd, List<VillageLayout.LotSpec> specs, Adoption.Inventory inv, boolean survival, Runnable legacy) {
		var saved = Settlements.store().get(settlement.id()).orElse(null);
		if (saved == null) { legacy.run(); return; }
		if (!Adoption.reconcile(server, saved, true)) { abandon("The settlement's history could not be saved; try again."); return; }
		saved = Settlements.store().get(settlement.id()).orElse(saved);
		// an adoption that stands but whose town could not be read (or one just closed as standing): nothing more is asked now
		if (Adoption.standing(saved).isPresent() || Adoption.openIntent(saved).isPresent()) {
			abandon("The town is being changed right now; ask again once it has settled.");
			return;
		}
		// (slice SST, S-SAD-1) a survival adoption: the stockpile first, which folds the older builds' crates into it (adoptCrates), so no site is still
		// fed by a live crate (Architect refuses that) and the town's construction draws from the stockpile once adopted
		if (survival) {
			var sp = Stockpiles.ensure(server, saved, this::say);
			if (sp == null) {
				abandon("The town is built from materials and grows from the stockpile, and none could be made by the founding stone. Clear a spot beside it and start again.");
				return;
			}
			saved = Settlements.store().get(settlement.id()).orElse(saved);
		}
		String key = opKey("adopt");
		var intent = Adoption.intent(key, inv, System.currentTimeMillis());
		// not saved: not asked
		if (!Settlements.log(settlement.id(), intent).ok()) { legacy.run(); return; }
		ServerLevel level = level();
		var c = saved.claim();
		var box = new BoundingBox(c.centerX() - c.radius(), level.getMinY(), c.centerZ() - c.radius(), c.centerX() + c.radius(), level.getMaxY(), c.centerZ() + c.radius());
		JsonObject geometry = inv.geometry();
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", settlement.id());
		ext.addProperty(Adoption.KEY_EXT, key);
		say("The town is taken on as one region (Architect), so it can grow...");
		int session = Session.current();
		adopting = true;
		Steward.LOGGER.info("adopt {}: street {} {} {} {}..{}, {} roads, {} lots, key {}", settlement.id(), inv.northSouth() ? "north-south" : "east-west",
			inv.northSouth() ? "x" : "z", inv.streetZ(), inv.xLo(), inv.xHi(), inv.roads().size(),
			inv.lots().size(), key);
		ArchitectApi.get().regions().adopt(new dev.larattalabs.architect.api.AdoptRegionRequest(settlement.owner(), level.dimension(), box,
			dev.larattalabs.architect.api.AdoptRegionRequest.VILLAGE_LAYOUT, geometry, inv.roads(), inv.lots(), key, ext)).whenComplete((v, err) -> server.execute(() -> {
				adopting = false;
				if (!Session.is(session) || !alive()) return;
				if (worldStopped(err)) return;
				if (err == null && v != null) {
					if (!Settlements.log(settlement.id(), Adoption.receipt(intent, v.id(), System.currentTimeMillis())).ok()) {
						abandon("The town was taken on as a region, but the history could not be saved; it is put right at the next start.");
						return;
					}
					Steward.LOGGER.info("adopt {}: region {}{}", settlement.id(), v.id(), survival ? " (survival)" : "");
					// (slice SST) a survival region grows as any region built from materials (A2): the mode gate (the world's mode read again: it may
					// have changed while the adoption was asked), a lot still building waits, the stockpile
					var town = ArchitectAccess.api().regions().get(v.id()).orElse(v);
					if (survival || Growths.fromMaterials(town)) {
						var now = Settlements.store().get(settlement.id()).orElse(settlement);
						growStanding(r, budgetUsd, now, town, specs, dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false), null);
						return;
					}
					planGrowth(r, budgetUsd, v.id(), specs, null);
					return;
				}
				Throwable e = err == null ? null : err.getCause() != null ? err.getCause() : err;
				boolean definitive = e instanceof dev.larattalabs.architect.api.ArchitectRefused rf && switch (rf.reason()) {
					case NOT_ALLOWED, OVERLAP, PROTECTED -> true;
					default -> false;
				};
				if (definitive) {
					String why = message(err);
					Steward.LOGGER.info("adopt {}: refused ({}): Steward's own street instead", settlement.id(), why);
					if (!Settlements.log(settlement.id(), Adoption.released(intent.siteGroup(), "Not taken on as one region: " + RefusalWords.of(err), System.currentTimeMillis())).ok()) {
						abandon("The settlement's history could not be saved; try again.");
						return;
					}
					legacy.run();
					return;
				}
				Steward.LOGGER.warn("adopt {}: no clear answer ({}); the reconcile closes it", settlement.id(), err == null ? "no region" : message(err));
				abandon("The town is being changed right now; ask again once it has settled.");
			}));
	}

	/**
	 * A region town's growth (docs/GROWTH.md): the addition's lots, renamed so their ids are the region's own, planned as a revision of the town on its
	 * streets' frontage, else on an extension of a street end; full on both, the steward looks to a district. The plan is saved before anything else, and
	 * a plan asked for before (a restart) is found by its key first, so a lost answer never plans twice. {@code stale}: the key of a plan that went stale
	 * while its lots were designed: planned once more with the same lots under {@code vgrow3}, its designs kept by lot id.
	 */
	private void planGrowth(CardResult r, double budgetUsd, String region, List<VillageLayout.LotSpec> specs, @org.jspecify.annotations.Nullable String stale) {
		var regions = ArchitectApi.get().regions();
		// (slice PV1) a plan lot keeps the plan's own id (the region's first lots have their plan ids; Architect refuses a standing one)
		List<VillageLayout.LotSpec> lots = stale != null && districtSpecs != null ? districtSpecs : planLot ? List.copyOf(specs) : Growths.rename(specs, buildId);
		districtSpecs = lots;
		growRegion = region;
		// (slice M3) the module lot's order under its region lot id, saved with the build from its plan on
		if (pendingModule != null && stale == null) {
			var ids = lots.stream().map(VillageLayout.LotSpec::id).toList();
			moduleOrders = Map.of(ids.get(0), pendingModule);
			pendingModule = null;
		}
		if (stale == null) say(planLot && !lots.isEmpty() ? "The town grows from its plan: " + lots.get(0).role() + " (its lot " + lots.get(0).id().replace('_', ' ')
			+ ", not built yet). Architect finds room on " + (growZone != null ? "that part of the town's streets..." : "its streets...")
			: districtId == null ? "The town grows: Architect finds room on its streets..." : growingName() + " grows: Architect finds room on its street...");
		// asked before (a restart before the answer was saved): the newest of this build's growth plans
		if (stale == null) {
			for (String k : List.of("vgrow3", "vgrow2", "vgrow")) {
				var found = regions.planByKey(settlement.owner(), opKey(k)).filter(p -> p.revisionOf() != null && region.equals(p.revisionOf().region()));
				if (found.isPresent()) { grown(r, budgetUsd, found.get(), k, lots); return; }
			}
		}
		List<String[]> tries = stale != null ? List.<String[]>of(new String[] {"vgrow3", stale.startsWith("vgrow2") ? "extend" : "frontage"})
			: List.of(new String[] {"vgrow", "frontage"}, new String[] {"vgrow2", "extend"});
		planGrowthTry(r, budgetUsd, region, lots, tries, 0, Session.current());
	}

	private void planGrowthTry(CardResult r, double budgetUsd, String region, List<VillageLayout.LotSpec> lots, List<String[]> tries, int i, int session) {
		String key = tries.get(i)[0], prefer = tries.get(i)[1];
		JsonObject params = dev.larattalabs.steward.gateway.VPlan.params(lots, settlement.id(), buildId);
		JsonObject grow = new JsonObject();
		grow.add("lots", moduleGrowLots(params.getAsJsonArray("lots")));
		grow.addProperty("prefer", prefer);
		// (slice DG) a district region's growth names the district that grows (Architect GR: required for a districts region)
		if (districtId != null) grow.addProperty("district", districtId);
		// (slice PV1) a plan lot grows on its own zone's streets (Architect GR §8: a plan/0 zone's streets carry architect_mc:street.zone)
		else if (growZone != null) grow.addProperty("zone", growZone);
		JsonObject p = new JsonObject();
		p.add("grow", grow);
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", settlement.id());
		ext.addProperty(BatchPlanner.BUILD_EXT, buildId);
		if (districtId != null) ext.addProperty(Districts.DISTRICT_EXT, districtId);
		ArchitectApi.get().regions().planRevision(region, new dev.larattalabs.architect.api.RevisionRequest(Growths.PROGRAM, p, opKey(key), "steward", ext))
			.whenComplete((rp, err) -> server.execute(() -> {
				if (!Session.is(session) || !alive()) return;
				if (worldStopped(err)) return;
				if (err != null) {
					String m = message(err);
					boolean full = m.contains("NO_ROOM") || m.contains("REGION_LIMIT");
					if (full && i + 1 < tries.size()) { planGrowthTry(r, budgetUsd, region, lots, tries, i + 1, session); return; }
					// (slice M3) no room for the module at this size: the next smaller size of its discrete set, as a new plan (never another box), said
					if (full && r != null && smallerModule(r, budgetUsd, region, lots, session)) return;
					if (full) Proposals.full(settlement.id(), districtId);
					String inD = districtId == null ? null : settlement.lands().district(districtId).map(Settlement.District::name).orElse(districtId);
					growthNotPlanned(r, full ? (inD != null ? "No room left in " + inD + " for it: its street is full."
						: "No room left in the town for it: its streets are full. A district could take it.")
						: "Architect could not find room for it in the town: " + said("the growth plan", err) + ".");
					return;
				}
				try {
					grown(r, budgetUsd, rp, key, lots);
				} catch (RuntimeException e) {
					Steward.LOGGER.error("the growth of {} failed after its plan", settlement.id(), e);
					growthNotPlanned(r, "The build stopped after the town's growth plan: " + e);
				}
			}));
	}

	/**
	 * (slice M3) The grow lots as sent: a module lot gets the catalog's lot (its type, exact size, {@code irrigate} and FC's {@code field} where the
	 * module has them) and its {@code steward_mc:module} ext beside Steward's tags; any other lot as it was.
	 */
	private com.google.gson.JsonArray moduleGrowLots(com.google.gson.JsonArray lots) {
		if (moduleOrders.isEmpty()) return lots;
		com.google.gson.JsonArray out = new com.google.gson.JsonArray();
		for (var e : lots) {
			JsonObject l = e.getAsJsonObject();
			var order = moduleOrders.get(l.get("id").getAsString());
			var kind = order == null ? java.util.Optional.<dev.larattalabs.steward.module.ModuleKind>empty() : dev.larattalabs.steward.module.ModuleKind.of(order.moduleId());
			if (kind.isEmpty()) { out.add(l); continue; }
			JsonObject m = kind.get().growLot(l.get("id").getAsString(), order.params());
			JsonObject ext = l.has("ext") ? l.getAsJsonObject("ext").deepCopy() : new JsonObject();
			m.add("ext", order.ref().into(ext));
			if (l.has("role")) m.add("role", l.get("role"));
			out.add(m);
		}
		return out;
	}

	/**
	 * (slice M3, docs/MODULES.md 4.2 step 1) The module had no room at its size on either preference: the next smaller size of its discrete set is
	 * planned as a new plan (its own keys), said, with its own measured bound, which must stay within the one the player approved. False when there is
	 * no smaller size (or no bound for it): the caller ends the build as for any growth with no room.
	 */
	private boolean smallerModule(CardResult r, double budgetUsd, String region, List<VillageLayout.LotSpec> lots, int session) {
		if (moduleOrders.size() != 1 || lots.size() != 1) return false;
		String lotId = lots.get(0).id();
		var order = moduleOrders.get(lotId);
		var kind = dev.larattalabs.steward.module.ModuleKind.of(order.moduleId());
		if (kind.isEmpty()) return false;
		var next = kind.get().smaller(order.params());
		if (next.isEmpty()) return false;
		var entry = ModuleOffers.catalog().get(order.moduleId(), order.version()).filter(e -> e.programSha().equals(order.programSha()));
		if (entry.isEmpty()) return false;
		var p = dev.larattalabs.steward.module.ModuleGate.params(entry.get(), next.get());
		if (!p.ok()) return false;
		// the bound the player approved stays the authorization: the smaller field's quote is held to it, never to the smaller size's own estimate
		JsonObject bound = order.bound();
		int[] was = kind.get().frame(order.params()), now = kind.get().frame(p.values());
		say("No room in the town for the " + was[0] + " by " + was[1] + " " + kind.get().role() + ": trying a smaller one, " + now[0] + " by " + now[1] + ", as a new plan.");
		moduleOrders = Map.of(lotId, order.resized(p.values(), bound));
		var spec = lots.get(0);
		List<VillageLayout.LotSpec> resized = List.of(new VillageLayout.LotSpec(spec.id(), spec.type(), now[0], now[1], spec.role(), spec.notes(), spec.landmark(), spec.placement()));
		districtSpecs = resized;
		String sfx = ".s" + now[0] + "x" + now[1];
		planGrowthTry(r, budgetUsd, region, resized, List.of(new String[] {"vgrow" + sfx, "frontage"}, new String[] {"vgrow2" + sfx, "extend"}), 0, session);
		return true;
	}

	/** The growth's plan: saved (plan, rid, position, key) before anything else, then the lots go on as the build's plan. */
	private void grown(CardResult r, double budgetUsd, dev.larattalabs.architect.api.RegionPlan rp, String key, List<VillageLayout.LotSpec> lots) {
		vPlanId = rp.planId();
		growKey = key;
		growRid = rp.revisionOf() == null ? 0 : rp.revisionOf().rid();
		growPosition = rp.revisionOf() == null ? 0 : rp.revisionOf().position();
		plan = dev.larattalabs.steward.gateway.DPlan.toPlan(rp.lots(), lots);
		Steward.LOGGER.info("growth {} of {}: plan {} under {}, revision {} at position {}", buildId, growRegion, vPlanId, key, growRid, growPosition);
		persist();
		// planned again after its plan went stale (the designs are made): realised now
		if (r != null) continueWithPlan(r, budgetUsd, null);
		// cancelled while it was planned again: nothing is realised (the new plan is let go when the build ends)
		else if (state == null || state.phase().terminal()) return;
		else if (state.phase() == Pipeline.Phase.CANCELLING) feed(new Pipeline.CancelConfirmed());
		else realiseVillage();
	}

	/** A growth not planned: before its designs, the build is abandoned (nothing spent); planned again after them (a stale plan), it is cancelled. */
	private void growthNotPlanned(@org.jspecify.annotations.Nullable CardResult r, String why) {
		if (r != null) { abandon(why); return; }
		say(why + " The designs stay in the library.");
		feed(new Pipeline.Cancel());
	}

	/** Asks Architect for the flat village over the claim, then goes on with its lots as the plan. */
	private void planVillage(CardResult r, int buildings, double budgetUsd, Settlement.@org.jspecify.annotations.Nullable District district, Claim claim,
		List<VillageLayout.LotSpec> specs, java.util.function.Supplier<VillageLayout.Plan> own) {
		ServerLevel level = level();
		var box = new BoundingBox(claim.centerX() - claim.radius(), level.getMinY(), claim.centerZ() - claim.radius(), claim.centerX() + claim.radius(), level.getMaxY(),
			claim.centerZ() + claim.radius());
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", settlement.id());
		ext.addProperty(BatchPlanner.BUILD_EXT, buildId);
		say("Laying out the village (Architect's flat village: lots, lanes, a square)...");
		var bible = existing != null && existing.bible() != null && existing.bible().styleVersion() == existing.styleVersion() ? existing.bible() : null;
		int session = Session.current();
		ArchitectApi.get().regions().plan(new dev.larattalabs.architect.api.RegionPlanRequest(dev.larattalabs.steward.gateway.VPlan.PROGRAM,
			dev.larattalabs.steward.gateway.VPlan.params(specs, settlement.id(), buildId), level, box, null, bible == null ? null : bible.id(),
			bible == null ? null : bible.version(), LoadPolicy.LOADED_ONLY, settlement.owner(), ext, opKey("vplan"))).whenComplete((rp, err) -> server.execute(() -> {
				if (!Session.is(session) || !alive()) return;
				if (worldStopped(err)) return;
				if (err != null) { abandon("Architect could not lay out the village: " + said("the village plan", err) + "."); return; }
				try {
					// a north-south street: Steward's designs and growth know an east-west one, so its own layout is used (nothing is spent yet)
					if (dev.larattalabs.steward.gateway.VPlan.northSouth(rp.lots())) {
						Steward.LOGGER.info("village plan {} runs north-south: Steward's own layout instead", rp.planId());
						plan = own.get();
					} else {
						vPlanId = rp.planId();
						plan = dev.larattalabs.steward.gateway.VPlan.toPlan(rp, specs);
					}
					continueWithPlan(r, budgetUsd, district);
				} catch (RuntimeException e) {
					Steward.LOGGER.error("the build of {} failed after its village plan", settlement.id(), e);
					abandon("The build stopped after the village plan: " + e);
				}
			}));
	}

	/**
	 * A reserved beside district's first building: Architect plans the district's region beside the town (its street, its join to the town's street and this
	 * building's lot) under this build's keys, so the village's realise, watch, cancel and restart paths carry it. The district is {@code building} while the
	 * build runs, and back to {@code reserved} when it ends with no region ({@link #apply}). Its lots keep the fronts Architect gives them ({@link
	 * dev.larattalabs.steward.gateway.DPlan}).
	 */
	private void planDistrict(CardResult r, double budgetUsd, Settlement.District district, List<VillageLayout.LotSpec> specs) {
		// a reserved beside district's first building, or a quarter founded for this build (building, no region yet)
		boolean beside = Settlement.District.BESIDE.equals(district.kind()) && Settlement.District.RESERVED.equals(district.state());
		boolean quarter = !district.square() && !Settlement.District.BESIDE.equals(district.kind()) && Settlement.District.BUILDING.equals(district.state())
			&& district.region() == null;
		if (!beside && !quarter) {
			abandon(district.name() + " stands as its own region already: adding to it waits for Architect's growth (6d).");
			return;
		}
		var regions = ArchitectApi.get().regions();
		var town = district.parent() == null ? java.util.Optional.<dev.larattalabs.architect.api.RegionView>empty() : regions.get(district.parent());
		if (town.isEmpty() || town.get().state() == dev.larattalabs.architect.api.RegionState.REMOVED || town.get().state() == dev.larattalabs.architect.api.RegionState.REMOVING) {
			abandon("The town " + district.name() + " was reserved beside is gone: undo the reservation and found the district again.");
			return;
		}
		// (slice SD) a world that builds regions from materials: the district's region is built from the settlement's one stockpile (Architect keeps one per
		// owner and dimension; its targets join the feed after the town's), so it is found or made first, and the plan keeps off its crates. (slice SD2)
		// a quarter (along, outpost) the same, quoted and billed as a beside district is. (slice CRATE) a new crate keeps off the town's street.
		if (regionMaterials()) {
			var sp = Stockpiles.ensureOffStreet(server, settlement, this::say);
			if (sp == null) {
				abandon(district.name() + " is built from materials, from " + settlement.name() + "'s stockpile, and none could be made by the founding stone. "
					+ "Clear a spot beside it and start again. Nothing was spent.");
				return;
			}
			String held = Stockpiles.protectCrates(server, Settlements.store().get(settlement.id()).orElse(settlement), sp, true);
			if (held != null) { abandon("The stockpile's ground could not be kept clear of " + district.name() + " (" + held + "). Start again."); return; }
		}
		if (!Settlements.districtState(settlement.id(), district.id(), Settlement.District.BUILDING).ok()) {
			abandon("Could not save " + district.name() + " as building.");
			return;
		}
		ServerLevel level = level();
		var b = town.get().claim();
		int[] request = districtRequest(Settlements.store().get(settlement.id()).orElse(settlement), district, new int[] {b.minX(), b.minZ(), b.maxX(), b.maxZ()});
		// Architect may place the district anywhere in the request's claim: if another district's land is there now, nothing is planned
		var envelope = dev.larattalabs.steward.model.Land.ofRect(district.claim().dimension(), request[0], request[1], request[2], request[3]);
		var sibling = settlement.lands().districts().stream().filter(o -> !o.id().equals(district.id()) && o.land().overlaps(envelope)).findFirst();
		if (sibling.isPresent()) {
			// nothing planned: a beside district is reserved again, a quarter given up (Districts.release)
			Districts.release(settlement.id(), district);
			abandon(district.name() + "'s ground now reaches " + sibling.get().name() + "'s land: found it again.");
			return;
		}
		List<JsonObject> lots = new ArrayList<>();
		for (var e : dev.larattalabs.steward.gateway.VPlan.params(specs, settlement.id(), buildId).getAsJsonArray("lots")) lots.add(e.getAsJsonObject());
		districtSpecs = List.copyOf(specs);
		var req = Districts.besideRequest(level, settlement, town.get().id(), request, district.id(), lots, opKey("vplan"), buildId, district.kind(), district.follow());
		say("Laying out " + district.name() + " beside the town (its street and its road to the town's street)...");
		int session = Session.current();
		if (regionMaterials()) districtPlannedMaterials = true;
		regions.plan(req).whenComplete((rp, err) -> server.execute(() -> {
			if (!Session.is(session) || !alive()) return;
			if (worldStopped(err)) return;
			if (err != null) {
				Steward.LOGGER.info("{}: the plan of {} failed: {}", settlement.id(), district.name(), message(err));
				abandon("Architect could not lay out " + district.name() + ": " + Districts.besideRefusal(err));
				return;
			}
			try {
				vPlanId = rp.planId();
				plan = dev.larattalabs.steward.gateway.DPlan.toPlan(rp.lots(), specs);
				continueWithPlan(r, budgetUsd, district);
			} catch (RuntimeException e) {
				Steward.LOGGER.error("the build of {} failed after its district plan", settlement.id(), e);
				abandon("The build stopped after the district's plan: " + e);
			}
		}));
	}

	/** Whether this build takes the settlement's composed plan (docs/COMPOSITION.md section 10): one is kept, and this Architect checks and realises plans. */
	private static boolean composed(Settlement s) {
		return !dev.larattalabs.steward.gateway.PPlan.villageOnly(s.id()) && PlanStore.get(s.id()).isPresent() && Composer.available();
	}

	/** The kept plan as this build builds it: the parts that wait not required, each lot tagged with this build ({@link dev.larattalabs.steward.gateway.PPlan#forBuild}). */
	private JsonObject builtPlan() {
		// (slice M5) its module lots as this build binds them (the saved orders); a module lot with none is left out, as it was at the Start re-check
		// (slice MODULE-TYPE-2) a bare lot this build already laid out as an ordinary lot (a build saved before Noah's 11-20 rule) stays as laid out
		return withModules(keptPlan(), moduleOrders, ModuleOffers.moduleTypes(), laidOutIds());
	}

	/** The lot ids of this build's layout (empty before it is laid out). */
	private java.util.Set<String> laidOutIds() {
		java.util.Set<String> out = new java.util.HashSet<>();
		if (plan != null) for (var l : plan.lots()) out.add(l.id());
		return out;
	}

	/** The kept plan as this build builds it, its module lots as the composer wrote them (what the Start re-check decides them from). */
	private JsonObject keptPlan() {
		var entry = PlanStore.get(settlement.id()).orElseThrow();
		java.util.Set<String> waiting = new java.util.HashSet<>();
		for (var p : entry.pending()) waiting.add(p.part());
		return dev.larattalabs.steward.gateway.PPlan.forBuild(com.google.gson.JsonParser.parseString(entry.plan()).getAsJsonObject(), waiting, settlement.id(), buildId);
	}

	/**
	 * (slice M5) The ids of the modules the catalog offers this town now (the composer's vocabulary). (slice MODULE-TYPE-2) A bare plan lot of any
	 * other module's type is still a module ask, refused and left out of the first build, said ({@link dev.larattalabs.steward.module.ComposedModules#notOfferedWords}).
	 */
	static java.util.Set<String> offeredModules() {
		java.util.Set<String> out = new java.util.HashSet<>();
		try {
			for (var o : ModuleOffers.composable()) out.add(o.kind().id());
		} catch (RuntimeException | LinkageError e) {
			// no catalog: nothing is offered (every module lot is left out)
		}
		return out;
	}

	/** (slice M5) The plan as built with these module orders: each ordered lot is the catalog's lot, every other module lot is left out. */
	static JsonObject withModules(JsonObject plan, Map<String, dev.larattalabs.steward.module.ModuleOrder> orders) {
		return withModules(plan, orders, ModuleOffers.moduleTypes(), java.util.Set.of());
	}

	/**
	 * As above over every module's type ({@code types}, failing closed: slice MODULE-TYPE-2). {@code laidOut}: lots a build already laid out as ordinary
	 * lots; a bare one of them stays (a build saved before the 11-20 rule finishes as it began), a marked one never does. Pure.
	 */
	static JsonObject withModules(JsonObject plan, Map<String, dev.larattalabs.steward.module.ModuleOrder> orders, Map<String, String> types, java.util.Set<String> laidOut) {
		return withModulesApplied(plan, orders, types, laidOut).plan();
	}

	/** As {@link #withModules(JsonObject, Map, Map, java.util.Set)}, with the parts that went with the left-out lots (slice MODULE-TYPE-3). Pure. */
	static dev.larattalabs.steward.module.PlanPrune.Result withModulesApplied(JsonObject plan, Map<String, dev.larattalabs.steward.module.ModuleOrder> orders,
		Map<String, String> types, java.util.Set<String> laidOut) {
		java.util.Set<String> leftOut = new java.util.HashSet<>();
		Map<String, JsonObject> byId = new HashMap<>();
		for (JsonObject l : dev.larattalabs.steward.gateway.PPlan.lots(plan)) byId.putIfAbsent(l.get("id").getAsString(), l);
		for (var a : dev.larattalabs.steward.module.ComposedModules.readAll(dev.larattalabs.steward.gateway.PPlan.lots(plan), types)) {
			if (orders.containsKey(a.lotId())) continue;
			if (laidOut.contains(a.lotId()) && !dev.larattalabs.steward.module.ComposedModules.marked(byId.get(a.lotId()))) continue;
			leftOut.add(a.lotId());
		}
		return dev.larattalabs.steward.module.ComposedModules.applied(plan, orders, leftOut);
	}

	/**
	 * (slice MODULE-TYPE-3) What the steward says, once before the check, of the parts that went with the left-out lots (a road, a wall, a rule that
	 * served only them): each by its label, else its id's words; a secret part (S-7c-4) as "one part". Null when none went. Pure.
	 */
	/**
	 * (MODULE-TYPE-3 fix round) What the steward says of left-out lots kept in the plan after all, as ordinary buildings, because other parts of the
	 * plan are placed by them (or the build order couldn't be written without them). Null when none. Pure.
	 */
	static @org.jspecify.annotations.Nullable String keptWords(java.util.List<String> kept, JsonObject plan) {
		if (kept.isEmpty()) return null;
		Map<String, JsonObject> byId = new HashMap<>();
		for (JsonObject l : dev.larattalabs.steward.gateway.PPlan.lots(plan)) byId.putIfAbsent(l.get("id").getAsString(), l);
		List<String> names = new ArrayList<>();
		for (String id : kept) {
			String n = byId.containsKey(id) ? dev.larattalabs.steward.gateway.PPlan.role(byId.get(id)) : id.replace('_', ' ');
			if (!names.contains(n)) names.add(n);
		}
		String list = names.size() == 1 ? names.get(0) : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
		boolean one = kept.size() == 1;
		return "After all, the plan's " + list + (one ? " stays" : " stay") + " in the town as " + (one ? "an ordinary building" : "ordinary buildings")
			+ ": the rest of the plan is laid out by " + (one ? "it" : "them") + ", so leaving " + (one ? "it" : "them") + " out would stop the whole build.";
	}

	static @org.jspecify.annotations.Nullable String droppedWords(java.util.List<String> dropped, JsonObject plan, Secrets secrets) {
		if (dropped.isEmpty()) return null;
		Map<String, String> labels = new HashMap<>();
		for (String family : dev.larattalabs.steward.module.PlanPrune.FAMILIES)
			if (plan.has(family) && plan.get(family).isJsonArray()) for (var e : plan.getAsJsonArray(family))
				if (e.isJsonObject() && e.getAsJsonObject().has("id") && e.getAsJsonObject().has("label") && e.getAsJsonObject().get("label").isJsonPrimitive())
					labels.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject().get("label").getAsString());
		List<String> names = new ArrayList<>();
		int hidden = 0;
		for (String id : dropped) {
			if (secrets.hides(id)) hidden++;
			else names.add(labels.getOrDefault(id, id.replace('_', ' ')));
		}
		if (hidden > 0) names.add(names.isEmpty() ? (hidden == 1 ? "one part" : hidden + " parts") : (hidden == 1 ? "one more part" : hidden + " more parts"));
		String list = names.size() == 1 ? names.get(0) : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
		boolean one = dropped.size() == 1;
		return "The plan's " + list + (one ? " is" : " are") + " left out too: " + (one ? "it" : "they") + " served only what is left out.";
	}

	/**
	 * (slice M5, docs/MODULES.md "M5 design" 2) The kept plan's module lots, decided now: an order for each one Steward can build here, or why not, said
	 * (a refused one is left out of the town, never designed in its place or left a pad). Only an Architect that runs given programs is asked.
	 */
	private Map<String, dev.larattalabs.steward.module.ModuleOrder> decideModules(JsonObject base) {
		var asked = dev.larattalabs.steward.module.ComposedModules.readAll(dev.larattalabs.steward.gateway.PPlan.lots(base), ModuleOffers.moduleTypes());
		if (asked.isEmpty()) return Map.of();
		var offered = offeredModules();
		Map<String, JsonObject> lotsById = new HashMap<>();
		for (JsonObject l : dev.larattalabs.steward.gateway.PPlan.lots(base)) lotsById.put(l.get("id").getAsString(), l);
		ServerLevel level = level();
		net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(settlement.claim().centerX(), level.getSeaLevel(), settlement.claim().centerZ());
		// (M5b) what the player carries and (from materials) the stockpile holds: a sorting hall's chosen items are the most held filter kinds
		Map<String, Integer> held = new HashMap<>();
		ServerPlayer holder = server.getPlayerList().getPlayer(playerId);
		if (holder != null) for (var st : holder.getInventory()) if (!st.isEmpty()) held.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
		if (supplied) Stockpiles.of(server, Settlements.store().get(settlement.id()).orElse(settlement))
			.ifPresent(sp -> sp.stock().forEach((it, n) -> held.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).toString(), n, Integer::sum)));
		// (LIFT-S) from materials, module lots are built only under Architect's per-lot cap (LCAP); without it the guard leaves them out, said
		var town = new dev.larattalabs.steward.module.ComposedModules.Town(level.getBiome(at).value().coldEnoughToSnow(at, level.getSeaLevel()), supplied, held, lotCaps());
		Map<String, dev.larattalabs.steward.module.ModuleOrder> orders = new LinkedHashMap<>();
		java.util.Set<String> taken = new java.util.HashSet<>();
		for (var a : asked) {
			// (slice MODULE-TYPE-2; Noah 11-20, "Leave it out and say why") a bare lot of a module the catalog doesn't offer this town: left out of the
			// first build, said once here (before the check, the designs or the quote), and the rest builds
			if (!dev.larattalabs.steward.module.ComposedModules.marked(lotsById.get(a.lotId())) && !offered.contains(a.moduleId())) {
				say(dev.larattalabs.steward.module.ComposedModules.notOfferedWords(dev.larattalabs.steward.gateway.PPlan.role(lotsById.get(a.lotId())), a.moduleId()));
				continue;
			}
			var kind = dev.larattalabs.steward.module.ModuleKind.of(a.moduleId()).filter(k -> ModuleOffers.kinds().contains(k)).orElse(null);
			var verdict = kind == null ? new dev.larattalabs.steward.module.ModuleGate.Verdict(null, dev.larattalabs.steward.module.ModuleGate.Why.UNKNOWN, "")
				: ModuleOffers.verdict(kind.id());
			var d = dev.larattalabs.steward.module.ComposedModules.decide(a, kind, verdict, town, taken,
				dev.larattalabs.steward.module.ComposedModules.size(lotsById.get(a.lotId())), ModuleOffers::bound);
			String name = dev.larattalabs.steward.gateway.PPlan.role(lotsById.get(a.lotId()));
			if (!d.ok()) {
				say(dev.larattalabs.steward.module.ComposedModules.leftOutWords(name, d.why()));
				continue;
			}
			taken.add(kind.id());
			orders.put(a.lotId(), d.order());
			say(dev.larattalabs.steward.module.ComposedModules.boundWords(name, kind, d.order(), d.changed(), supplied));
		}
		return orders;
	}

	/**
	 * Why a check of the plan as built can't be built from, or null: no artifact (the parts that wait are not required, so anything else is what stops it),
	 * or a required part refused or malformed. Optional parts that did not fit are dropped by Architect and said.
	 */
	static @org.jspecify.annotations.Nullable String unbuildable(dev.larattalabs.architect.api.PlanCheck c) {
		return unbuildable(c, Secrets.NONE);
	}

	/** As {@link #unbuildable(dev.larattalabs.architect.api.PlanCheck)}; a secret part (S-7c-4) is "a part of the plan", never its name. */
	static @org.jspecify.annotations.Nullable String unbuildable(dev.larattalabs.architect.api.PlanCheck c, Secrets secrets) {
		// (raw-sweep) each stuck part said by its typed class and first reason code in the steward's words (RefusalWords.planCode/planClass), never the tokens
		List<String> stuck = new ArrayList<>();
		boolean volume = false;
		for (JsonObject part : c.parts()) {
			String cls = part.has("class") ? part.get("class").getAsString() : "";
			boolean required = part.has("required") && part.get("required").getAsBoolean();
			String id = part.has("part") ? part.get("part").getAsString() : "?";
			if (!required || cls.equals("fits") || cls.equals("adjusted")) continue;
			String code = part.has("reasons") && part.get("reasons").isJsonArray() && !part.getAsJsonArray("reasons").isEmpty()
				&& part.getAsJsonArray("reasons").get(0).isJsonObject() && part.getAsJsonArray("reasons").get(0).getAsJsonObject().has("code")
				? part.getAsJsonArray("reasons").get(0).getAsJsonObject().get("code").getAsString() : null;
			if ("VOLUME".equals(code)) volume = true;
			String why = code != null && !RefusalWords.planCode(code).equals(RefusalWords.GENERIC) ? RefusalWords.planCode(code) : RefusalWords.planClass(cls);
			stuck.add((secrets.hides(id) ? "one part" : id.replace('_', ' ')) + " (" + why + ")");
		}
		if (!stuck.isEmpty()) return "the plan's " + String.join(", ", stuck) + " can't be built here"
			// a tunnel past the volume pass's caps: the rock under it couldn't all be read
			+ (volume ? " (Architect couldn't read all the rock a tunnel goes through: stand nearer it and start again)" : "");
		if (c.artifactSha() == null) return RefusalWords.planOverall(c.overall());
		return null;
	}

	/** The optional parts Architect left out of what is built (they did not fit), in plain words. */
	private static List<String> leftOut(dev.larattalabs.architect.api.PlanCheck c, Secrets secrets) {
		List<String> out = new ArrayList<>();
		for (JsonObject part : c.parts()) {
			// (S-7c-4) a hidden room that didn't fit is left out unsaid
			if (part.has("part") && secrets.hides(part.get("part").getAsString())) continue;
			String cls = part.has("class") ? part.get("class").getAsString() : "";
			boolean required = part.has("required") && part.get("required").getAsBoolean();
			if (!required && (cls.equals("refused") || cls.equals("malformed") || cls.equals("incomplete"))) out.add(part.has("part") ? part.get("part").getAsString().replace('_', ' ') : "?");
		}
		return out;
	}

	/**
	 * Lays the village out from the settlement's composed plan (plan v1): the plan as built is checked again now (the land may have changed since it was
	 * described), its approved artifact pinned, and its lots become this build's plan; the designs then run as for any build, and the village is realised from
	 * the artifact ({@link #realiseVillage}). A plan that can't be built here stops the build before anything is spent; one Architect can't check falls
	 * back to the flat village. Its streets run the way the plan laid them (slice NS2: a north-south street too).
	 */
	private void planComposed(Settlement existing, CardResult r, int buildings, double budgetUsd, Settlement.@org.jspecify.annotations.Nullable District district,
		Claim claim, List<VillageLayout.LotSpec> specs, java.util.function.Supplier<VillageLayout.Plan> own) {
		// built from materials: the stockpile first, so the plan is checked with its crates standing (Architect keeps clear of them)
		if (supplied) {
			var sp = Stockpiles.ensure(server, existing, this::say);
			if (sp == null) {
				abandon("A town built from materials needs its stockpile, and none could be made by the founding stone. Clear a spot beside it and start again.");
				return;
			}
			// the plan keeps clear of the crates (and a ring round them, to reach them): a protected area, which Architect's check treats as keep-out;
			// a plain crate site is not, and the street or square laid over it would keep a hole where it stands
			String held = Stockpiles.protectCrates(server, existing, sp, true);
			if (held != null) { abandon("The stockpile's ground could not be kept clear of the plan (" + held + "). Start again."); return; }
		}
		// with its stockpile area, when one was just made
		Settlement checked = Settlements.store().get(existing.id()).orElse(existing);
		say("Laying out the village from its plan (Architect checks it against the land as it is now)...");
		// (slice M5) the plan's working modules, decided before the check: the plan checked and built holds each as the catalog's lot (or leaves it out)
		moduleOrders = Map.of();
		JsonObject base = keptPlan();
		var orders = decideModules(base);
		var applied = withModulesApplied(base, orders, ModuleOffers.moduleTypes(), java.util.Set.of());
		JsonObject built = applied.plan();
		String went = droppedWords(applied.dropped(), base, Secrets.of(base, null));
		if (went != null) say(went);
		String stays = keptWords(applied.kept(), base);
		if (stays != null) say(stays);
		// (slice M5-SCULPT-2, GPT review P1) a shaped site took this plan for its shaping: the plan as built must still carry it (a channel from a zone
		// left out goes with it), else nothing is spent
		boolean sculpt = "sculpt".equals(r.card().site().terrain());
		List<String> shaping = sculpt ? dev.larattalabs.steward.gateway.VPlan.shapingParts(built, waitingOf(existing)) : List.of();
		if (sculpt && shaping.isEmpty()) {
			abandon("The plan's " + dev.larattalabs.steward.gateway.VPlan.shapingWords(base, dev.larattalabs.steward.gateway.VPlan.shapingParts(base, java.util.Set.of()))
				+ " went with what is left out, and this shaped site isn't built without it. Nothing was spent; describe the settlement again for a new plan.");
			return;
		}
		int session = Session.current();
		// a plan Architect can't check, or without lots: the flat village for a Patron town; a Supplied one stops before anything is spent (its survey was taken
		// before its stockpile and that ground's protection, so Steward's own layout over it could pay for designs that then don't fit), and is started again
		// (slice M5, GPT review 3) a plan that names working modules never falls back to designing the card's buildings: nothing is spent, start again
		boolean namesModules = !dev.larattalabs.steward.module.ComposedModules.readAll(dev.larattalabs.steward.gateway.PPlan.lots(base), ModuleOffers.moduleTypes()).isEmpty();
		Runnable otherwise = () -> {
			if (supplied) abandon("The plan couldn't be used from materials this time: start the build again.");
			// (slice M5-SCULPT-2, GPT review P2) a shaped site is never laid out as the flat village from its card
			else if (sculpt) abandon("The plan that shapes this site couldn't be used this time, and a shaped site isn't built without it. Nothing was spent: start the build again.");
			else if (namesModules) abandon("The plan with its working modules couldn't be used this time, and its buildings aren't designed without it. Nothing was spent: start the build again.");
			else planVillage(r, buildings, budgetUsd, district, claim, specs, own);
		};
		Composer.check(server, checked, built, playerId).whenComplete((c, err) -> server.execute(() -> {
			if (!Session.is(session) || !alive()) return;
			if (worldStopped(err)) return;
			if (err != null) {
				say("Architect could not check the plan (" + said("the plan check", err) + ")" + (supplied ? "." : ": the village is laid out as usual."));
				otherwise.run();
				return;
			}
			try {
				String no = unbuildable(c, Secrets.of(built, null));
				if (no != null) {
					Steward.LOGGER.info("{}: the plan check came back {}: {}", settlement.id(), c.overall(), c.parts());
					abandon("The plan can't be built as it stands: " + no + ". Describe the settlement again for a new plan.");
					return;
				}
				// Architect builds a region from materials only in a survival world (its toggle on, the world's default mode not creative): its check says
				// whether it ran in survival; otherwise the town would be refused at the realise, so Steward's own layout builds it from materials
				// the check's mode is this build's: built from materials for a Supplied build, at once for a Patron one; the world's mode changed while it was
				// surveyed: nothing is spent, and a new Start plans in the mode the world is in now
				if (c.survival() != supplied) {
					abandon("The world's mode changed while the land was surveyed (" + (c.survival() ? "it builds from materials now" : "it no longer builds from materials")
						+ "): start the build again.");
					return;
				}
				// (slice M5-SCULPT-2, GPT review P1) the shaping must be built, not an optional part Architect refused or dropped while the rest fits
				if (sculpt && !dev.larattalabs.steward.gateway.VPlan.shapingBuilt(shaping, c.parts())) {
					Steward.LOGGER.info("{}: the plan's shaping {} isn't built by the check: {}", settlement.id(), shaping, c.parts());
					abandon("The plan's " + dev.larattalabs.steward.gateway.VPlan.shapingWords(built, shaping) + " can't be built here as the land is now, and this shaped site isn't"
						+ " built without it. Nothing was spent; describe the settlement again for a new plan.");
					return;
				}
				var art = ArchitectApi.get().regions().artifact(c.artifactSha());
				var lots = art.flatMap(a -> java.util.Optional.ofNullable(a.body())).filter(b -> b.has("lots") && b.get("lots").isJsonArray()).map(b -> b.getAsJsonArray("lots"));
				if (lots.isEmpty() || lots.get().isEmpty()) { say("The plan's check has no lots to build on" + (supplied || namesModules ? "." : ": the village is laid out as usual.")); otherwise.run(); return; }
				// built from materials: what the plan's ground, streets and parts take (the buildings' bills are said once they are designed)
				if (supplied) {
					String bill = Composer.billLine(c.cost());
					if (bill != null) say("From materials, the plan's ground, streets and parts need " + bill + ". Fill the stockpile as they're built.");
				}
				var out = leftOut(c, Secrets.of(built, art.map(a -> a.body()).orElse(null)));
				if (!out.isEmpty()) say("Left out of what is built (they didn't fit here): " + String.join(", ", out) + ".");
				var waits = Composer.shown(PlanStore.get(settlement.id()).map(PlanStore.Entry::pending).orElse(List.of()), Secrets.ofSettlement(settlement.id()));
				if (!waits.isEmpty()) say("Built now without " + String.join(", ", waits.stream().map(w -> w.part().replace('_', ' ')).toList()) + ": "
					+ (waits.size() == 1 ? "it waits" : "they wait") + " for Architect.");
				// (GPT review 4) each module's lot as Architect laid it, checked before anything is designed: its exact frame, its water, a plain lot
				for (var l : lots.get()) {
					if (!l.isJsonObject() || !l.getAsJsonObject().has("id")) continue;
					var o = orders.get(l.getAsJsonObject().get("id").getAsString());
					var kind = o == null ? java.util.Optional.<dev.larattalabs.steward.module.ModuleKind>empty() : dev.larattalabs.steward.module.ModuleKind.of(o.moduleId());
					if (o == null || kind.isEmpty()) continue;
					String bad = dev.larattalabs.steward.module.ComposedModules.lotProblem(l.getAsJsonObject(), kind.get(), o);
					if (bad != null) {
						abandon("The plan's working " + kind.get().role() + " can't be built here: " + bad + ". Nothing was spent; describe the settlement again for a new plan.");
						return;
					}
				}
				pin(c.artifactSha());
				vPlanId = dev.larattalabs.steward.gateway.PPlan.ARTIFACT_PREFIX + c.artifactSha();
				// (slice M5) the module lots' orders, saved with the build beside its artifact (BuildStore format 6), so a restart binds the same
				// only the lots Architect kept: an optional module lot it left out (it didn't fit) is said above with the others, never bound
				java.util.Set<String> kept = new java.util.HashSet<>();
				for (var l : lots.get()) if (l.isJsonObject() && l.getAsJsonObject().has("id")) kept.add(l.getAsJsonObject().get("id").getAsString());
				var placed = new LinkedHashMap<String, dev.larattalabs.steward.module.ModuleOrder>();
				orders.forEach((lot, o) -> { if (kept.contains(lot)) placed.put(lot, o); });
				moduleOrders = java.util.Collections.unmodifiableMap(placed);
				// (S-7c-4) the designs never hear of a hidden room: a lot's brief without the sentences naming one (Architect builds the room, not the design)
				var secrets = Secrets.of(built, art.map(a -> a.body()).orElse(null));
				if (secrets.any()) Steward.LOGGER.info("plan as built for {}: {} secret part(s) {} in the artifact, answered {}", settlement.id(), secrets.parts().size(),
					art.map(a -> a.body()).filter(b -> b.has("secrets") && b.get("secrets").isJsonArray()).map(b -> b.getAsJsonArray("secrets").size()).orElse(0),
					c.parts().stream().filter(x -> x.has("part") && secrets.hides(x.get("part").getAsString())).map(x -> x.get("part").getAsString() + "=" + (x.has("class") ? x.get("class").getAsString() : "?")).toList());
				plan = dev.larattalabs.steward.gateway.PPlan.toPlan(lots.get(), secrets.scrubBriefs(built));
				// (Architect K3) a wall lot narrower than 7 is asked for only of an Architect that takes it (wallLots; an older one refuses the request), and its
				// repair needs keyed designs: stopped here, before anything is spent
				var walls = plan.lots().stream().filter(VillageLayout.Lot::exact).map(l -> l.id() + " " + l.type() + " " + l.along() + "x" + l.deep() + "x" + l.maxHeight()).toList();
				if (!walls.isEmpty()) Steward.LOGGER.info("wall lots of {}: {}", settlement.id(), String.join(", ", walls));
				// a wall lot's massing must take its box, or its detail cannot close the wall (Architect wallLots.exactMassing; Steward's groups are massing-first)
				var wall = plan.lots().stream().filter(VillageLayout.Lot::exact).findFirst();
				if (wall.isPresent() && !ArchitectApi.get().features().contains("wallLots.exactMassing")) {
					abandon("The " + wall.get().role() + " on the wall must fill its lot, and this Architect can't design it to its box: update Architect.");
					return;
				}
				var narrow = plan.lots().stream().filter(l -> l.exact() && (l.along() < 7 || l.deep() < 7)).findFirst();
				if (narrow.isPresent() && !ArchitectApi.get().features().contains("wallLots")) {
					abandon("The " + narrow.get().role() + " on the wall is " + narrow.get().along() + " x " + narrow.get().deep()
						+ ", and this Architect designs nothing under 7 across: update Architect.");
					return;
				}
				Steward.LOGGER.info("plan of {}: artifact {}, {} lots ({} unplaced), fronts {}", settlement.id(), c.artifactSha(), plan.lots().size(), plan.unplaced().size(),
					plan.lots().stream().map(l -> l.front().name().toLowerCase()).collect(java.util.stream.Collectors.groupingBy(f -> f, java.util.TreeMap::new,
						java.util.stream.Collectors.counting())));
				continueWithPlan(r, budgetUsd, district);
			} catch (RuntimeException e) {
				Steward.LOGGER.error("the build of {} failed after its plan check", settlement.id(), e);
				abandon("The build stopped after the plan's check: " + e);
			}
		}));
	}

	/** With the plan made (Steward's layout or Architect's village): checks it, then starts the designs. */
	private void continueWithPlan(CardResult r, double budgetUsd, Settlement.@org.jspecify.annotations.Nullable District district) {
		{
			say("Layout: " + Words.count(plan.lots().size(), "lot", "lots") + " on the street" + (plan.unplaced().isEmpty() ? "" : " (" + plan.unplaced().size() + " did not fit)"));
			if (plan.lots().isEmpty()) {
				// an addition that found no room: the steward looks to a district (or proposes one) next time
				if (addition && existing != null) Proposals.full(existing.id(), districtId);
				abandon(addition ? "No room left " + (district != null ? "in " + district.name() : "in the town") + " for it." : "No dry, flat room here. Try another spot.");
				return;
			}
			// an approved plan's streets are Architect's, checked against the protected areas already (and not one east-west street)
			if (existing != null && dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId) == null) {
				String crossed = protectedCrossed(existing, plan, newStreet ? List.of(new int[] {plan.streetX0(), plan.streetX1()}) : List.of(), districtId);
				if (crossed != null) { abandon("The street would cross " + crossed + ". Clear it, or protect less, and try again."); return; }
			}
			if (resumeGroup != null) {
				groupId = resumeGroup.id();
				state = new State(Pipeline.Phase.GROUP_RUNNING, settlement.id(), r.card(), resumeGroup.bible().id(), resumeGroup.bible().version(), groupId, Map.of(), 0,
					resumeGroup.cost().usd(), resumeBudget, false, null, null);
				say("Resuming group " + groupId + " with budget $" + resumeBudget);
				var d = ArchitectApi.get().designs();
				if (resumeGroup.status() == Group.Status.DONE) { say("The group is already done; placing."); apply(Pipeline.step(state, new Pipeline.GroupUpdate(groupId, "done", Map.of(), List.of(), resumeGroup.cost().usd(), null), permission)); return; }
				d.extendGroup(groupId, resumeBudget).thenCompose(v -> alive() ? d.resumeGroup(groupId) : java.util.concurrent.CompletableFuture.<Void>completedFuture(null))
					.whenComplete((v, e) -> { if (e != null) say("Resume failed: " + said("the resume", e) + "."); });
				noteMassings(resumeGroup);
				for (Group.Item it : resumeGroup.items()) if (it.status() == dev.larattalabs.architect.api.Design.Status.DONE || it.stage().isPresent()) decided.add(decisionKey(it.itemKey()));
				return;
			}
			// an accepted proposal records itself now (acceptance, spend), or does not start
			if (onStart != null && !onStart.getAsBoolean()) { abandon("Could not save the settlement, so nothing is built."); return; }
			// (slice M3) a module lot is never designed: its entry is the catalog's variant, bound before the realise (docs/MODULES.md 4.2)
			// (slice M5) a composed town whose every lot is a working module: nothing to design either; placed as any plan v1 town once approved
			boolean modulesOnly = composedModules() && designPlan().lots().isEmpty();
			if (moduleBuild() || modulesOnly) {
				var bible = existing != null && existing.bible() != null && existing.bible().styleVersion() == existing.styleVersion() ? existing.bible() : null;
				state = new State(Pipeline.Phase.READY_TO_PLACE, settlement.id(), r.card(), bible == null ? null : bible.id(), bible == null ? null : bible.version(), null,
					Map.of(), 0, 0, 0, false, null, null);
				persist();
				if (modulesOnly) {
					say("Every building of the plan is a working module from Steward's catalog: nothing to design, $0.");
					queueVillage(!permission.needsApproval(Permission.Action.NEW_PROJECT));
					return;
				}
				say("A working module from Steward's catalog: no design, $0. Making it for its lot...");
				realiseVillage();
				return;
			}
			state = State.start(settlement.id(), r.card(), budgetUsd);
			var bible = existing != null && existing.bible() != null && existing.bible().styleVersion() == existing.styleVersion() ? existing.bible() : null;
			if (bible != null) say("Designing in " + settlement.name() + "'s own style (its style bible is reused).");
			apply(Pipeline.step(state, bible != null ? new Pipeline.CardApproved(r.card(), bible.id(), bible.version()) : new Pipeline.CardApproved(r.card()), permission));
		}
	}

	/**
	 * Marks the settlement's standing sites as built ground (a block's margin round each), so a layout keeps off them, and finds the street the build faces
	 * (the district's, or the town's). The roads the steward laid, the player's protected areas and, for a town build, its districts' squares are taken too.
	 */
	private void markSites(Grid grid, Settlement s, Settlement.@org.jspecify.annotations.Nullable District district) {
		String dim = s.claim().dimension();
		java.util.Set<String> roads = new java.util.HashSet<>();
		for (Settlement.District d : s.lands().districts()) {
			for (Settlement.Road r : d.roads()) {
				roads.add(r.siteId());
				for (int[] c : dev.larattalabs.steward.model.DistrictRules.cells(r.points(), r.width() + 2)) if (grid.has(c[0], c[1])) grid.setBuilt(c[0], c[1], true);
			}
			if (district == null) {
				// the district's land, which a town build keeps off, and its region's join inside the town (town land the district holds; read live)
				List<dev.larattalabs.steward.model.Land> off = new ArrayList<>(List.of(d.land()));
				if (d.region() != null) ArchitectApi.get().regions().get(d.region())
					.flatMap(v -> dev.larattalabs.steward.gateway.DistrictLand.inside(v, dim)).ifPresent(off::add);
				for (var land : off) for (dev.larattalabs.steward.model.Land.Run r : land.runs()) for (int x = r.x0(); x <= r.x1(); x++) {
					if (grid.has(x, r.z())) grid.setBuilt(x, r.z(), true);
				}
			}
		}
		for (Settlement.Area a : s.lands().protect()) {
			if (!a.dimension().equals(dim)) continue;
			for (int x = a.x0(); x <= a.x1(); x++) for (int z = a.z0(); z <= a.z1(); z++) if (grid.has(x, z)) grid.setBuilt(x, z, true);
		}
		var all = ArchitectApi.get().sites(server).list(s.owner());
		for (var v : all) {
			// a road's box spans its whole route: its cells were marked above
			if (roads.contains(v.id())) continue;
			var b = v.box();
			for (int x = b.minX() - 1; x <= b.maxX() + 1; x++) for (int z = b.minZ() - 1; z <= b.maxZ() + 1; z++) if (grid.has(x, z)) grid.setBuilt(x, z, true);
		}
		// the street of where the build goes: the district's own, or the town's (the rule founding and links use too); (slice NS2) the town's on either axis
		var street = Districts.streetEitherAxis(all, s, district);
		streetNorthSouth = street != null && street.northSouth();
		if (street != null) streetZ = street.z();
	}

	/** The protected area the street pieces ({@code spans} along the plan's street line, 5 wide) or the plan's lots would cross, or null. */
	/**
	 * What a street span would cross that it must keep off, said as a phrase ("the protected area \"x\"", "Quay's land"), or null: a protected area, another
	 * district's land, and a district region's join inside the town (read live). {@code own}: the district this build is in, whose land is its own.
	 */
	private static @org.jspecify.annotations.Nullable String protectedCrossed(Settlement s, VillageLayout.Plan plan, List<int[]> spans,
		@org.jspecify.annotations.Nullable String own) {
		String dim = s.claim().dimension();
		for (Settlement.Area a : s.lands().protect()) {
			for (int[] sp : spans) {
				int[] b = plan.spanBox(sp[0], sp[1], 2);
				if (a.overlaps(dim, b[0], b[1], b[2], b[3])) return "the protected area \"" + a.label() + "\"";
			}
		}
		for (Settlement.District d : s.lands().districts()) {
			if (d.id().equals(own)) continue;
			List<dev.larattalabs.steward.model.Land> off = new ArrayList<>(List.of(d.land()));
			if (d.region() != null) ArchitectApi.get().regions().get(d.region())
				.flatMap(v -> dev.larattalabs.steward.gateway.DistrictLand.inside(v, dim)).ifPresent(off::add);
			for (int[] sp : spans) {
				int[] b = plan.spanBox(sp[0], sp[1], 2);
				var band = dev.larattalabs.steward.model.Land.ofRect(dim, b[0], b[1], b[2], b[3]);
				for (var l : off) if (l.overlaps(band)) return d.name() + "'s land";
			}
		}
		return null;
	}

	/** The id of the dev settlement {@code /steward build} and {@code resume} make (not saved). */
	private static final String DEV_ID = "set_dev";

	/** Takes the settlement's slot in {@link #ACTIVE}, or says why not. */
	private boolean claim(String settlementId) {
		if (busy(settlementId)) {
			say(settlementId + " is already being built; wait for it to finish.");
			return false;
		}
		claimedId = settlementId;
		ACTIVE.put(settlementId, this);
		return true;
	}

	/** Gives up before the pipeline started: says why and frees the slot. */
	/**
	 * A district region's first build that ended with no region of its own: the district is only reserved again (its undo gives it up). Not while a realise
	 * may still answer, and not while Architect holds a region tagged with the district (a lost answer): then it stays {@code building} until the world-start
	 * sweep takes that region up or a later pass finds none ({@link Districts#reconcile}).
	 */
	private void releaseDistrict() {
		if (districtId == null || settlement == null || queueInFlight) return;
		Settlements.store().get(settlement.id()).flatMap(s -> s.lands().district(districtId))
			.filter(d -> Settlement.District.BUILDING.equals(d.state()) && d.region() == null)
			.filter(d -> Districts.liveRegionOf(settlement.owner(), d.id()).isEmpty())
			.ifPresent(d -> Districts.release(settlement.id(), d));
	}

	private void abandon(String why) {
		say(why);
		releaseDistrict();
		// a build that stops before its state is saved leaves nothing on disk that needs its approved plan: the pin goes
		if (state == null || settlement == null || builds.all().stream().noneMatch(b -> b.settlementId().equals(settlement.id()))) releasePin();
		// it never started: an accepted proposal is given back (it was recorded only if onStart ran), and backs off before it is tried again
		if (onNothingSpent != null && state != null) {
			onNothingSpent.run();
			onNothingSpent = null;
		}
		if (onAbandoned != null) {
			onAbandoned.run();
			onAbandoned = null;
		}
		if (claimedId != null) ACTIVE.remove(claimedId, this);
		sendInbox(server, playerId);
	}

	private void apply(Pipeline.Step step) {
		Pipeline.Phase before = state == null ? null : state.phase();
		state = step.next();
		// the board and the nameplate show the new state on the next tick, not the next round
		if (settlement != null) SettlementSync.dirty(settlement.id());
		// it ended: its row stays in the inbox a few minutes ({@link #ENDED_ROW_MS}), so the player sees how
		if (state.phase().terminal() && (before == null || !before.terminal())) {
			endedAt = System.currentTimeMillis();
			releaseDistrict();
		}
		// the massings show as ghosts on their lots while they wait for the player, and go when decided
		if (state.phase() == Pipeline.Phase.AWAITING_MASSING_APPROVAL && before != Pipeline.Phase.AWAITING_MASSING_APPROVAL) showMassings(true);
		if (before == Pipeline.Phase.AWAITING_MASSING_APPROVAL && state.phase() != Pipeline.Phase.AWAITING_MASSING_APPROVAL) hideMassings();
		// saved before anything is asked of Architect: a crash after a request then restores a build that knows it asked (and says it was interrupted)
		// rather than one that asks, and pays, again
		// the style bible the player approved (or that needed no approval) is the settlement's from now on: its later builds reuse it
		if ((before == Pipeline.Phase.BIBLE_RUNNING || before == Pipeline.Phase.AWAITING_BIBLE_APPROVAL) && state.phase() == Pipeline.Phase.GROUP_RUNNING && state.bibleId() != null
			&& settlement != null && Settlements.store().get(settlement.id()).isPresent()) {
			Settlements.bible(settlement.id(), new Settlement.BibleRef(state.bibleId(), state.bibleVersion(), Settlements.store().get(settlement.id()).get().styleVersion()));
		}
		// ended before any paid request of it was sent (only setup steps ran): an accepted proposal is given back. Once a bible job or a group was asked for,
		// it may have cost something, whatever the spend says or whether its acknowledgement came
		// (slice M3) a module build spends nothing paid: its offer is given back exactly when nothing of it stands (never realised, or taken up whole), also
		// for a build restored after a restart (its hook is gone: the order names the proposal)
		boolean nothingStands = moduleBuild() ? vRegionId == null && !queueInFlight : !paidRequestAcked;
		if (before != null && !before.terminal() && state.phase().terminal() && state.phase() != Pipeline.Phase.DONE && nothingStands) {
			if (onNothingSpent != null) {
				onNothingSpent.run();
				onNothingSpent = null;
			} else if (moduleBuild() && restored) giveModuleBack();
		}
		boolean saved = persist();
		if (!saved && step.commands().stream().anyMatch(SettlementRunner::spends)) {
			say("Could not save the build, so nothing more is spent on it. Cancel it (/steward cancel " + state.settlementId() + ") and check the world's folder can be written.");
			for (Command c : step.commands()) if (!spends(c)) execute(c);
		} else {
			for (Command c : step.commands()) execute(c);
		}
		persist();
		sendInbox(server, playerId);
		moveSteward();
		drainLater();
	}

	/** Where the steward last went for this build, so it is told only when that changes. */
	private net.minecraft.core.@org.jspecify.annotations.Nullable BlockPos stewardAt;
	private boolean stewardSent;

	/**
	 * Where the steward works for the build's phase: by the first lot whose massing waits for the player, at the end of the street while it is fitted and
	 * placed, else home (the bible and the designs are made at its table). Never inside a lot or on the street, which would hold their placement up.
	 */
	private net.minecraft.core.@org.jspecify.annotations.Nullable BlockPos workPlace() {
		if (state == null || plan == null || state.phase().terminal()) return null;
		Claim at = districtId == null ? settlement.claim() : settlement.lands().district(districtId).map(Settlement.District::claim).orElse(settlement.claim());
		var home = new net.minecraft.core.BlockPos(at.centerX(), plan.streetY(), at.centerZ());
		return switch (state.phase()) {
			case AWAITING_MASSING_APPROVAL -> plan.lots().stream().filter(l -> lastAwaiting.contains(l.id())).findFirst()
				.map(l -> StewardMotion.outside(l.x(), l.groundY(), l.z(), l.x() + l.sizeX() - 1, l.z() + l.sizeZ() - 1, home)).orElse(null);
			case READY_TO_PLACE, AWAITING_PLACEMENT_APPROVAL, PLACING -> {
				int[] end = plan.at(plan.streetX1() + 4);
				yield new net.minecraft.core.BlockPos(end[0], plan.streetY(), end[1]);
			}
			default -> null;
		};
	}

	private void moveSteward() {
		if (settlement == null || !alive()) return;
		var at = workPlace();
		if (stewardSent && java.util.Objects.equals(at, stewardAt)) return;
		stewardSent = true;
		stewardAt = at;
		StewardMotion.workAt(server, settlement, at, 0);
	}

	private void drainLater() {
		while (!later.isEmpty() && alive()) feed(later.poll());
	}

	/** Architect failed a pending request because the world is closing (API 1.10): not an outcome of the request. */
	static boolean worldStopped(@org.jspecify.annotations.Nullable Throwable err) {
		Throwable e = err == null ? null : err.getCause() != null ? err.getCause() : err;
		return e instanceof dev.larattalabs.architect.api.ArchitectRefused r && r.reason() == dev.larattalabs.architect.api.Reason.WORLD_STOPPED;
	}

	/**
	 * An answer lost on its way rather than a refusal: a timeout, the helper's link dropped or not connected. The request may have arrived and be at work,
	 * so it is looked up by its key instead of being taken as failed.
	 */
	static boolean uncertain(@org.jspecify.annotations.Nullable Throwable err) {
		if (err == null) return false;
		Throwable e = err.getCause() != null ? err.getCause() : err;
		if (e instanceof java.util.concurrent.TimeoutException) return true;
		if (e instanceof dev.larattalabs.architect.api.ArchitectRefused r) return r.reason() == dev.larattalabs.architect.api.Reason.SIDECAR_UNAVAILABLE;
		// Architect's words for a link that went (SidecarLink: "sidecar connection lost: ..."), or an answer that never came; not any message that
		// happens to hold "link" (a bible named "blinkhaven")
		String m = String.valueOf(e.getMessage()).toLowerCase();
		// (slice M5a, seen under load) "the helper disconnected during follow <id>; it resumes when the helper restarts: call again with the same opKey"
		return m.contains("connection lost") || m.contains("not connected") || m.contains("timed out") || m.startsWith("timeout") || m.contains("sidecar link lost")
			|| m.contains("helper disconnected");
	}

	/** (QD) Architect refused a realise held to a cap: its bill at put-down was past it ({@code BILL_CAP}). */
	static boolean billCapped(@org.jspecify.annotations.Nullable Throwable err) {
		Throwable e = err == null ? null : err.getCause() != null ? err.getCause() : err;
		return e instanceof dev.larattalabs.architect.api.ArchitectRefused r && r.reason() == dev.larattalabs.architect.api.Reason.BILL_CAP;
	}

	/**
	 * (T4S-PRE, TXN-S) {@code siteBusy(err)}: a {@code SITE_BUSY} from Architect's transactions, read from its typed detail ({@link SiteBusy}):
	 * held(decided) (finishing by itself), held(other) (nothing changed: asked again), or none.
	 */
	static SiteBusy.Held siteBusy(@org.jspecify.annotations.Nullable Throwable err) {
		return SiteBusy.of(err);
	}

	/**
	 * (T4S-PRE) Whether a cancel's take-up of a region with nothing of it in the world is done: answered removed, or Architect holds its tombstone, or
	 * (the record absent) Architect's sites loaded cleanly ({@code healthy}): an absent record read from a store that did not load is never taken for
	 * gone. Pure.
	 */
	static boolean takenUp(boolean answeredRemoved, dev.larattalabs.architect.api.@org.jspecify.annotations.Nullable RegionState state, boolean present, boolean healthy) {
		if (answeredRemoved) return true;
		if (present) return state == dev.larattalabs.architect.api.RegionState.REMOVED;
		return healthy;
	}

	/**
	 * (T4S-PRE) A removal txn ended ({@link RemovalEnds}): each runner of this world cancelling a village or growth whose take-up waited on a decided
	 * hold reads its region again ({@link #cancelVillage}, which finds the tombstone or the UNDONE revision and ends the cancel once).
	 */
	static void removalFinished(String kind, String id) {
		for (SettlementRunner r : List.copyOf(ACTIVE.values())) {
			if (r.state == null || r.state.phase() != Pipeline.Phase.CANCELLING || r.vRegionId == null || !r.cancelHeld) continue;
			if ("region".equals(kind) && !id.equals(r.vRegionId)) continue;
			r.cancelHeld = false;
			r.server.execute(r::cancelVillage);
		}
	}

	/** (T4S-PRE) This runner's cancel waits on a take-up Architect decided and holds: it ends once that finishes ({@link #removalFinished}, or a restart). */
	private boolean cancelHeld;

	/** (LIFT-S) A {@code BILL_CAP} refusal's detail ({@code lot:<id>}, {@code unknown_lot:<id>}, or one it can't read: never a region cap, WORDS-089), or null for another error. */
	static @org.jspecify.annotations.Nullable String capDetail(@org.jspecify.annotations.Nullable Throwable err) {
		Throwable e = err == null ? null : err.getCause() != null ? err.getCause() : err;
		return e instanceof dev.larattalabs.architect.api.ArchitectRefused r && r.reason() == dev.larattalabs.architect.api.Reason.BILL_CAP ? r.detail() : null;
	}

	static boolean keyConflict(@org.jspecify.annotations.Nullable Throwable err) {
		Throwable e = err == null ? null : err.getCause() != null ? err.getCause() : err;
		return e instanceof dev.larattalabs.architect.api.ArchitectRefused r && r.reason() == dev.larattalabs.architect.api.Reason.OP_KEY_CONFLICT;
	}

	/** Architect's helper refused a cancel because the thing is unknown or already final (its words, 1.8). */
	private static boolean refusedAsOver(Throwable err) {
		String m = String.valueOf(message(err)).toLowerCase();
		return m.contains("no group") || m.contains("already");
	}

	static String message(Throwable err) {
		return err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
	}

	/**
	 * (raw-sweep) A failed call's refusal in the steward's words ({@link RefusalWords#of}: its typed reason, Steward's own words, else the generic line);
	 * Architect's text ({@link #message}) goes to the log only, with what failed.
	 */
	private String said(String what, Throwable err) {
		Steward.LOGGER.info("{}: {} failed: {}", settlement == null ? DEV_ID : settlement.id(), what, message(err));
		return RefusalWords.of(err);
	}

	/** (place-words) A design group's cancel that got no answer: the steward's words, Architect's error to the log only. */
	static final String CANCEL_GROUP_WAITS = "Could not reach Architect to stop the design group. It is cancelled when Architect answers; cancel again to stop waiting.";

	/** (place-words) A lot's fit refused: its typed reasons in the steward's words ({@link RefusalWords#fit}), never Architect's messages. Pure. */
	static String fitRefusedLine(String lotId, java.util.Collection<dev.larattalabs.architect.api.Refusal> refusals) {
		return "Lot " + lotId + " can't take its building: " + RefusalWords.fit(refusals) + ".";
	}

	/** (place-words) A module's variant not made: why from its typed detail ({@link RefusalWords#module}), or the error's typed reason. Pure. */
	static String moduleNotMadeLine(@org.jspecify.annotations.Nullable String detail, @org.jspecify.annotations.Nullable String message,
		@org.jspecify.annotations.Nullable Throwable err) {
		String why = detail == null && err != null ? RefusalWords.of(err) : RefusalWords.module(detail, message);
		return "Not placed: the module couldn't be made for its lot (" + why + "). Nothing was placed.";
	}

	/** (place-words) A batch item that failed: its key (Steward's own lot id) and its typed reason in the steward's words. Pure. */
	static String itemNotPlacedLine(String itemKey, dev.larattalabs.architect.api.@org.jspecify.annotations.Nullable Reason reason) {
		return "Not placed: " + itemKey + ": " + RefusalWords.reason(reason) + ".";
	}

	/** Commands that ask Architect to spend on Claude or to change the world. */
	private static boolean spends(Command c) {
		return c instanceof Pipeline.RequestBible || c instanceof Pipeline.RequestGroup || c instanceof Pipeline.ExtendAndResumeGroup || c instanceof Pipeline.FitAndQueue
			|| c instanceof Pipeline.ApproveStages || c instanceof Pipeline.ApproveGroup || c instanceof Pipeline.AskRepair || c instanceof Pipeline.Rebind;
	}

	/** The composite key the settlement's massing ghosts show under. */
	private String massingKey() {
		return "steward_mc:massings/" + settlement.id();
	}

	/**
	 * Sends the awaiting massings (or, when none wait, every massing of the group) to the player as ghosts on their lots, and with {@code describe} lists
	 * each in chat: lot, role, size and named parts. Returns what to tell a command caller.
	 */
	/** A lot with its massing (the version the group item points at). */
	private record LotMassing(VillageLayout.Lot lot, dev.larattalabs.architect.api.Massing massing) {
		String detail() {
			var m = massing;
			return m.size().x() + "x" + m.size().z() + ", " + m.size().y() + " tall" + (m.parts().isEmpty() ? "" : " (" + String.join(", ", m.parts().keySet()) + ")");
		}
	}

	/** The awaiting massings (or, when none wait, every massing of the group), with their lots. Empty when there is no group or Architect lost it. */
	private List<LotMassing> massings() {
		if (groupId == null || plan == null) return List.of();
		var designs = ArchitectApi.get().designs();
		Group g = designs.group(groupId).orElse(null);
		if (g == null) return List.of();
		Set<String> waiting = Set.copyOf(lastAwaiting);
		List<LotMassing> out = new ArrayList<>();
		for (Group.Item it : g.items()) {
			if (it.massing().isEmpty() || (!waiting.isEmpty() && !waiting.contains(it.itemKey()))) continue;
			VillageLayout.Lot lot = plan.lots().stream().filter(l -> l.id().equals(it.itemKey())).findFirst().orElse(null);
			var m = designs.massing(it.massing().get().id(), it.massing().get().version()).orElse(null);
			if (lot != null && m != null) out.add(new LotMassing(lot, m));
		}
		return out;
	}

	public UUID playerId() {
		return playerId;
	}

	/** When this build ended (its inbox row is kept until {@link #ENDED_ROW_MS} after), or -1 while it runs and once the row has gone. */
	private long endedAt = -1;

	/**
	 * This build's inbox entry, or null before it has a settlement and a state (still reading the card or surveying). Once it has ended, a row saying how
	 * for {@link #ENDED_ROW_MS}, then null.
	 */
	public dev.larattalabs.steward.inbox.InboxModel.Entry inboxEntry() {
		if (settlement == null || state == null || (state.phase().terminal() && endedAt < 0)) return null;
		// every building, with its stage; the waiting massings with their size and parts
		Map<String, String> details = new java.util.HashMap<>();
		boolean massingsWait = Pipeline.awaiting(state) == Pipeline.Decision.MASSINGS;
		if (massingsWait) for (LotMassing lm : massings()) details.put(lm.lot().id(), lm.detail());
		List<dev.larattalabs.steward.inbox.InboxModel.Lot> lots = new ArrayList<>();
		if (plan != null) {
			for (VillageLayout.Lot l : plan.lots()) {
				String stage = state.items().getOrDefault(l.id(), "");
				boolean waits = massingsWait && lastAwaiting.contains(l.id());
				lots.add(new dev.larattalabs.steward.inbox.InboxModel.Lot(l.id(), l.role(), stage, landmarkIds.contains(l.id()) || l.landmark(), waits,
					details.getOrDefault(l.id(), l.along() + "x" + (l.deep() - dev.larattalabs.steward.gateway.LotBrief.APPROACH_MARGIN) + " lot")));
			}
		}
		return dev.larattalabs.steward.inbox.InboxModel.entry(settlement.id(), settlement.name(), state, lots, attention(false, ""));
	}

	/** Sends a player their inbox: every unfinished build of theirs, and the ones that ended in the last minutes (an empty inbox clears the client's). */
	public static void sendInbox(MinecraftServer server, UUID player) {
		ServerPlayer p = server.getPlayerList().getPlayer(player);
		if (p == null) return;
		List<dev.larattalabs.steward.inbox.InboxModel.Entry> entries = new ArrayList<>();
		for (SettlementRunner r : List.copyOf(ACTIVE.values())) {
			if (!r.playerId.equals(player)) continue;
			var e = r.inboxEntry();
			if (e != null) entries.add(e);
		}
		entries.addAll(Updates.inboxEntries());
		entries.addAll(Proposals.inboxEntries());
		entries.addAll(LandChecks.inboxEntries());
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new StewardNet.Inbox(List.copyOf(entries)));
	}

	public String showMassings(boolean describe) {
		if (groupId == null || plan == null) return "No massings yet.";
		List<LotMassing> shown = massings();
		List<StewardNet.Layer> layers = new ArrayList<>();
		List<String> lines = new ArrayList<>();
		for (LotMassing lm : shown) {
			var m = lm.massing();
			MassingPlacement at = MassingPlacement.on(lm.lot(), m.size().x(), m.size().z());
			var lot = lm.lot();
			// its label over the middle of its lot, above the massing's top
			layers.add(new StewardNet.Layer(m.id() + "@" + m.version(), at.x(), at.y(), at.z(), at.rotation(), "MASSING", lot.role() + " · " + lot.id(), lot.x() + lot.sizeX() / 2,
				lot.groundY() + m.size().y() + 2, lot.z() + lot.sizeZ() / 2));
			lines.add(lm.lot().id() + ": " + lm.lot().role() + ", " + lm.detail());
		}
		if (layers.isEmpty()) return "No massings to show.";
		ServerPlayer p = server.getPlayerList().getPlayer(playerId);
		if (p == null) return "Your player is not online.";
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new StewardNet.ShowLayers(massingKey(), List.copyOf(layers)));
		if (describe) {
			say(Words.count(layers.size(), "massing is", "massings are") + " shown as blue ghosts on their lots (hide them: /steward hide " + settlement.id() + "):");
			lines.forEach(l -> say("  " + l));
		}
		return "Showing " + Words.count(layers.size(), "massing", "massings") + " on their lots.";
	}

	public void hideMassings() {
		ServerPlayer p = settlement == null ? null : server.getPlayerList().getPlayer(playerId);
		if (p != null) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new StewardNet.ShowLayers(massingKey(), List.of()));
	}

	private void feed(Event e) {
		if (state == null || !alive()) return;
		apply(Pipeline.step(state, e, permission));
	}

	private void execute(Command c) {
		// (batch-077 review P1-2) a mutation boundary: nothing is asked of Architect while settlements are disabled (the build resumes from its save)
		if (Settlements.disabled()) { Steward.LOGGER.warn("{}: {} not sent: settlements are disabled this session", claimedId, c.getClass().getSimpleName()); return; }
		var api = ArchitectAccess.api();
		switch (c) {
			case Pipeline.RequestBible b -> {
				say("Designing the style bible...");
				// keyed by the build: after a restart the request is found again (adopted), never sent twice
				BibleRequest req = new BibleRequest(b.prompt(), b.name(), settlement.owner(), new JsonObject(), null, b.budgetUsd(), List.of(), null, null).withOpKey(opKey("bible"));
				bibleInFlight = true;
				paidRequestAcked = true;
				api.bibles().request(req).whenComplete((job, err) -> {
					bibleInFlight = false;
					// the world closing is not the bible failing: the build stays saved, and the request is found by its key after the restart
					if (worldStopped(err)) return;
					// an answer lost on the way (a timeout, a dropped link) is not a refusal: the request may have arrived, so it is looked up by its key
					if (err != null && uncertain(err) && keyed && alive()) {
						// looked up by key; past the bound, kept saved and reported (the request may still be at work and charging: never dropped)
						if (liveRecoveries++ < MAX_LIVE_RECOVERIES) { say("Architect's answer was lost: looking the style bible request up."); recoverByKey(0, true); }
						else interrupted("the style bible request (Architect's answers keep getting lost; it may still be made and charged)");
						return;
					}
					if (err != null) { feed(new Pipeline.BibleDone(false, null, 0, 0, said("the style bible request", err))); return; }
					// cancelled while the request was on its way (or this runner belongs to a closed world): stop the job now
					if (!alive() || state.phase().terminal()) { if (!job.finished() && !Settlements.disabled()) api.bibles().cancel(job.id()); return; }
					bibleJobId = job.id();
					paidRequestAcked = true;
					persist();
					if (state.phase() == Pipeline.Phase.CANCELLING && !job.finished()) api.bibles().cancel(job.id());
					// the job as it stood at the ack: a fast or cached bible may already be done, and BIBLE_DONE fired before we knew its id
					if (job.finished()) onBibleDone(job);
				});
			}
			case Pipeline.RequestGroup g -> {
				GroupPlanner.Options o = new GroupPlanner.Options(g.bibleId(), g.bibleVersion(), g.budgetUsd(), 3, 3, landmarks);
				// (slice M5) a composed town's module lots are never designed: their style is checked now, before anything more is spent
				if (composedModules()) {
					String no = modulePalette(g.bibleId(), g.bibleVersion());
					if (no != null) { say(no); feed(new Pipeline.Cancel()); return; }
				}
				GroupPlanner.Built built = GroupPlanner.build(settlement, designPlan(), o, reservedCells(), wallLotExts(), invertedLots(), invertedDesigns());
				landmarkIds = built.request().items().stream().filter(i -> i.role() == GroupRequest.Role.LANDMARK).map(GroupRequest.Item::itemKey).collect(Collectors.toSet());
				say("Designing " + Words.count(built.request().items().size(), "building", "buildings") + " (massings first)...");
				groupInFlight = true;
				paidRequestAcked = true;
				api.designs().requestGroup(built.request().withOpKey(opKey("group"))).whenComplete((id, err) -> {
					groupInFlight = false;
					if (worldStopped(err)) return;
					if (err != null && uncertain(err) && keyed && alive()) {
						if (liveRecoveries++ < MAX_LIVE_RECOVERIES) { say("Architect's answer was lost: looking the design group request up."); recoverByKey(0, true); }
						else interrupted("the design group request (Architect's answers keep getting lost; it may still be at work)");
						return;
					}
					if (err != null) {
						say("Group failed: " + said("the design group request", err) + ".");
						// nothing reached Architect: the cancel finishes at once
						feed(new Pipeline.Cancel());
						return;
					}
					if (!alive() || state.phase().terminal()) { if (!Settlements.disabled()) api.designs().cancelGroup(id); return; }
					groupId = id;
					paidRequestAcked = true;
					persist();
					if (state.phase() == Pipeline.Phase.CANCELLING) { execute(new Pipeline.CancelGroup(id)); return; }
					// catch up on updates that fired before we knew the id (repeats are harmless: approvals are deduplicated)
					api.designs().group(id).ifPresent(this::onGroup);
				});
			}
			case Pipeline.ApproveGroup a -> {
				List<String> approve = a.approve().stream().filter(l -> decided.add(decisionKey(l))).toList();
				Map<String, String> redirect = new LinkedHashMap<>();
				a.redirect().forEach((k, v) -> { if (decided.add(decisionKey(k))) redirect.put(k, v); });
				List<String> cancel = a.cancel().stream().filter(l -> decided.add(decisionKey(l))).toList();
				Map<String, String> keys = new HashMap<>();
				for (String l : approve) keys.put(l, decisionKey(l));
				for (String l : redirect.keySet()) keys.put(l, decisionKey(l));
				for (String l : cancel) keys.put(l, decisionKey(l));
				if (approve.isEmpty() && redirect.isEmpty() && cancel.isEmpty()) break;
				api.designs().approveGroup(a.groupId(), approve, redirect, cancel, settlement.owner()).whenComplete((r, err) -> {
					if (err == null) return;
					say("Architect did not take that decision: " + said("the decision", err) + ".");
					// automatic approvals are not retried (a refusal would repeat on every update)
					if (!permission.needsApproval(Permission.Action.NEW_PROJECT)) return;
					// the player's decision: forget it and re-read the group, so the pipeline waits for it again instead of moving on
					keys.values().forEach(decided::remove);
					api.designs().group(a.groupId()).ifPresent(this::onGroup);
				});
			}
			case Pipeline.ExtendAndResumeGroup e -> {
				int seq = ++raiseSeq;
				raiseInFlight = true;
				api.designs().extendGroup(e.groupId(), e.groupBudgetUsd()).whenComplete((v, err) -> {
					if (!alive() || seq != raiseSeq) return;
					if (err != null) {
						raiseInFlight = false;
						feed(new Pipeline.BudgetRaiseFailed(e.previousBudgetUsd(), said("the budget raise", err), false));
						return;
					}
					api.designs().resumeGroup(e.groupId()).whenComplete((v2, err2) -> {
						if (!alive() || seq != raiseSeq) return;
						raiseInFlight = false;
						if (err2 != null) feed(new Pipeline.BudgetRaiseFailed(e.previousBudgetUsd(), message(err2), true));
					});
				});
			}
			case Pipeline.CancelBible cb -> {
				if (bibleJobId != null) {
					String id = bibleJobId;
					api.bibles().cancel(id);
					// a refused cancel is silent (Architect 1.8): an unknown job has nothing to stop
					if (api.bibles().job(id).isEmpty()) later.add(new Pipeline.CancelConfirmed());
					// a job that ended before the cancel reached it sends no second event: read it
					api.bibles().job(id).filter(BibleJob::finished).ifPresent(this::onBibleDone);
				} else if (!bibleInFlight && !recovering) { if (keyed) cancelByKeys(); else later.add(new Pipeline.CancelConfirmed()); }
			}
			case Pipeline.CancelGroup g -> {
				String id = g.groupId() != null ? g.groupId() : groupId;
				// always sent: a group missing from the local view may still run at the helper. Its future fails for an unknown or finished group; then the
				// group as it stands now decides (Architect: a failed future plus a final or missing group means it is over)
				if (id != null) api.designs().cancelGroup(id).whenComplete((v, err) -> {
					if (!alive()) return;
					var now = api.designs().group(id);
					if (now.isPresent() && now.get().status().isFinal()) onGroup(now.get());
					// only the helper's refusal ("no group", "already <status>") says it is over; a lost link or a timeout says nothing
					else if (now.isEmpty() && err != null && refusedAsOver(err)) feed(new Pipeline.CancelConfirmed());
					else if (err != null) {
						Steward.LOGGER.warn("{}: the design group {} wasn't stopped: {}", settlement.id(), id, message(err), err);
						say(CANCEL_GROUP_WAITS);
					}
				});
				else if (!groupInFlight && !recovering) { if (keyed) cancelByKeys(); else later.add(new Pipeline.CancelConfirmed()); }
			}
			case Pipeline.FitAndQueue f -> fitAndQueue(f.autoApprove());
			case Pipeline.ApproveStages a -> {
				if (village()) realiseVillage(); else approveStages();
			}
			case Pipeline.CancelBatch cb -> {
				if (village()) cancelVillage();
				else if (batchId != null) cancelBatch(batchId);
				else if (!queueInFlight && !recovering) { if (keyed) cancelByKeys(); else later.add(new Pipeline.CancelConfirmed()); }
			}
			// (Architect K3) a wall lot's repair
			case Pipeline.AskRepair a -> askRepair(a);
			case Pipeline.StopRepair x -> stopRepair(x);
			// the repaired version is the entry's head now: bound again under the same key
			case Pipeline.Rebind r -> realiseVillage();
			case Pipeline.Notify n -> {
				String hint = n.needsDecision() ? hint() : null;
				say(hint == null ? n.text() : n.text() + " " + hint);
			}
		}
	}

	private void fitAndQueue(boolean autoApprove) {
		// (batch-077 review P1-2) nothing is queued or placed while settlements are disabled
		if (Settlements.disabled()) { Steward.LOGGER.warn("{}: not queued: settlements are disabled this session", claimedId); return; }
		if (village()) { queueVillage(autoApprove); return; }
		var api = ArchitectApi.get();
		Group g = api.designs().group(groupId).orElse(null);
		if (g == null) { say("The group disappeared."); return; }
		Map<String, String> entries = new LinkedHashMap<>();
		Map<String, LotFit> fits = new HashMap<>();
		for (Group.Item it : g.items()) it.entryId().ifPresent(e -> entries.put(it.itemKey(), e));
		var sites = api.sites(server);
		ServerLevel level = level();
		for (VillageLayout.Lot lot : plan.lots()) {
			String entry = entries.get(lot.id());
			if (entry == null) continue;
			BoundingBox box = BatchPlanner.lotBox(lot, lot.sizeX() > 20 ? 40 : 24);
			LotFit fit = sites.fitToLot(entry, box, BatchPlanner.streetSide(lot), FitOptions.DEFAULT.withLevel(level));
			fits.put(lot.id(), fit);
			if (!fit.ok()) {
				Steward.LOGGER.info("fit {} lot {} ({}) refused: {}", settlement.id(), lot.id(), entry, fit.verdict().refusals().stream().map(r -> r.reason() + " " + r.message()).collect(Collectors.joining("; ")));
				say(fitRefusedLine(lot.id(), fit.verdict().refusals()));
			}
		}
		// the street: all of it for a new one; for an addition, the pieces its lots reach past the street's ends (the street as it stands now)
		var now = Settlements.store().get(settlement.id()).orElse(settlement);
		var district = districtId == null ? null : now.lands().district(districtId).orElse(null);
		List<int[]> spans = new java.util.ArrayList<>();
		if (newStreet) spans.add(new int[] {plan.streetX0(), plan.streetX1()});
		else {
			var street = Districts.streetEitherAxis(sites.list(settlement.owner()), now, district);
			spans.addAll(extensions(street, plan));
		}
		// ground protected since the survey: its lots are skipped, a street piece across it is left out
		for (VillageLayout.Lot lot : plan.lots()) {
			var a = now.lands().protect().stream().filter(x -> x.overlaps(now.claim().dimension(), lot.x(), lot.z(), lot.maxX(), lot.maxZ())).findFirst();
			if (a.isPresent() && fits.remove(lot.id()) != null) say(lot.role() + " is left out: its lot is in the protected area \"" + a.get().label() + "\".");
		}
		spans.removeIf(sp -> {
			String crossed = protectedCrossed(now, plan, List.of(sp), districtId);
			if (crossed != null) say("A piece of the street is left out: it would cross " + crossed + ".");
			return crossed != null;
		});
		Claim land = district != null ? district.claim() : settlement.claim();
		BatchPlanner.Result res = BatchPlanner.build(settlement, plan, landmarkIds, entries, fits, level,
			dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false), autoApprove, spans, buildId, land);
		// from materials (phase 4): the settlement's stockpile feeds the build, made first when there is none (else the build keeps its own crate)
		if (res.batch().sharedCrate() && Stockpiles.ensure(server, now, this::say, clearOf(plan, sites.list(now.owner()), now)) != null) {
			say("The stockpile feeds this build: the street first, then the landmarks, then the rest.");
			// the whole bill before anything is placed (Architect C26: each lot's template, foundation and approach; the street's road)
			String bill = billOf(res.batch(), fits, sites);
			if (bill != null) say(bill);
		}
		else if (res.note() != null) say(res.note());
		if (!res.skippedLotIds().isEmpty()) say("Skipped lots (no design or no fit): " + res.skippedLotIds());
		say("Placing " + Words.count(res.batch().items().size(), "item", "items") + "...");
		queueInFlight = true;
		sites.queue(res.batch().withOpKey(opKey("batch"))).whenComplete((id, err) -> {
			queueInFlight = false;
			if (worldStopped(err)) return;
			// lost on the way, or the same key with another body (a placement already there): looked up by its key and taken over
			if (err != null && (uncertain(err) || keyConflict(err)) && keyed && alive()) {
				if (liveRecoveries++ < MAX_LIVE_RECOVERIES) { say("Architect's answer about the placement was lost: looking it up."); recoverByKey(0, true); }
				else interrupted("the placement (Architect's answers keep getting lost; it may still be at work)");
				return;
			}
			if (err != null) { say("Queue failed: " + said("the placement batch", err) + "."); feed(new Pipeline.Cancel()); return; }
			if (!alive() || state.phase().terminal()) { if (!Settlements.disabled()) sites.cancelBatch(id); return; }
			batchId = id;
			siteGroupId = sites.batch(id).map(BatchView::group).orElse(null);
			if (state.phase() == Pipeline.Phase.CANCELLING) { persist(); cancelBatch(id); return; }
			feed(new Pipeline.BatchQueued(id));
			// a batch that finished before we knew its id (everything placed in the queue's first pass)
			sites.batch(id).filter(b -> !b.running()).ifPresent(this::onBatchDone);
		});
	}

	/** Cancels the placement batch; its rollback ends in BATCH_DONE (and the future), which logs what stayed placed and finishes the cancel. */
	private void cancelBatch(String id) {
		var sites = ArchitectApi.get().sites(server);
		sites.cancelBatch(id).whenComplete((view, err) -> {
			if (!alive()) return;
			if (view != null) { onBatchDone(view); return; }
			// refused: the batch ended before the cancel reached it, or Architect no longer has it
			var now = sites.batch(id);
			if (now.isPresent() && !now.get().running()) onBatchDone(now.get());
			else if (now.isEmpty()) feed(new Pipeline.CancelConfirmed());
		});
	}

	/** Approves every planned stage of the batch's site group, in order (each places once the ones before it are finished). */
	private void approveStages() {
		var sites = ArchitectApi.get().sites(server);
		String siteGroup = siteGroupId != null ? siteGroupId : batchId == null ? null : sites.batch(batchId).map(BatchView::group).orElse(null);
		var group = siteGroup == null ? null : sites.group(siteGroup).orElse(null);
		if (group == null) { say("The placement batch is gone; nothing to approve."); return; }
		for (var st : group.stages()) {
			if (st.state() != dev.larattalabs.architect.api.Stage.State.PLANNED || !batchId.equals(st.batchId())) continue;
			try {
				sites.approveStage(siteGroup, st.name());
			} catch (IllegalStateException e) {
				Steward.LOGGER.info("{}: stage {} could not be approved: {}", settlement.id(), st.name(), e.getMessage());
				say("Stage " + st.name() + " could not be approved: " + RefusalWords.of(e) + ".");
			}
		}
	}

	// ----------------------------------------------------------------- Architect events

	private void onBibleDone(BibleJob job) {
		if (bibleJobId == null || !bibleJobId.equals(job.id())) return;
		bibleJobId = null;
		boolean ok = job.status() == BibleJob.Status.DONE;

		// (raw-sweep) the job's own error to the log; the steward's words in the event (the pipeline says them)
		if (!ok) Steward.LOGGER.info("{}: the style bible job {} ended {}: {}", settlement.id(), job.id(), job.status(), job.error().orElse(""));
		feed(new Pipeline.BibleDone(ok, ok ? job.bibleId() : null, job.version(), job.cost().usd(), ok ? null
			: "budget".equals(job.error().orElse("")) ? "it ran past its budget" : "Architect didn't finish it"));
	}

	private void onGroup(Group g) {
		if (groupId == null || !groupId.equals(g.id())) return;
		// cancelling a placement: the (finished) group's updates do not end it, the batch's rollback does
		// (and a village realised, or a wall repair outstanding: its region's removal or the repair ends the cancel, never the group)
		if (state != null && state.phase() == Pipeline.Phase.CANCELLING && (batchId != null || queueInFlight || vRegionId != null || state.repair() != null)) return;
		noteMassings(g);
		Map<String, String> items = new LinkedHashMap<>();
		for (Group.Item it : g.items()) {
			String stage = it.stage().map(Group.Stage::wire).orElse(it.status().name().toLowerCase());
			items.put(it.itemKey(), stage);
		}
		lastAwaiting = List.copyOf(g.awaiting());
		heldUntil = g.usageLimitUntil();
		String held = g.usageLimitUntil() > 0 ? "until " + new java.util.Date(g.usageLimitUntil()) : null;
		feed(new Pipeline.GroupUpdate(g.id(), g.status().wire(), items, g.awaiting(), g.cost().usd(), held));
	}

	private void noteMassings(Group g) {
		for (Group.Item it : g.items()) it.massing().ifPresent(m -> massingRefs.put(it.itemKey(), m.toString()));
	}

	/** The key a decision on a lot's current massing is remembered under. */
	private String decisionKey(String lot) {
		return lot + "@" + massingRefs.getOrDefault(lot, "?");
	}

	// ------------------------------------------------------------------ Architect's flat village (slice V)

	/**
	 * The designs are done: the village is realised with them on its lots (Architect places the ground, the street, the lots and the props). When placing
	 * needs the player's approval, it waits for it first (the approval then realises it).
	 */
	private void queueVillage(boolean autoApprove) {
		// (slice SD) a district's first building from materials: its whole bill is quoted and said before it is approved (or placed)
		if (districtFromMaterials()) {
			var entries = villageLotEntries();
			if (entries != null) { quoteDistrict(entries, autoApprove ? BillAsk.AUTO : BillAsk.BEFORE_APPROVAL); return; }
		}
		if (autoApprove) realiseVillage();
		else {
			// (slice 8aS) what the approval would place: the artifact and its lots' entries, so a check can quote that realise before it is placed
			var entries = villageLotEntries();
			String artifact = vPlanId == null ? null : dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId);
			if (entries != null && artifact != null)
				Steward.LOGGER.info("placement {}: artifact {} entries {}", settlement.id(), artifact, new com.google.gson.Gson().toJson(entries));
			feed(new Pipeline.BatchQueued(VPLAN_PENDING));
		}
	}

	/**
	 * (slice SD) A district's own region from materials, its first building (never a growth, never a composed plan's artifact): quoted and approved before
	 * its realise. From materials when the world builds so now, or when it did as its plan was made (this session): a world turned off and on while the
	 * buildings were designed can't skip the bill. A district built at once is never quoted (a quote refusal can't end a creative build).
	 */
	private boolean districtFromMaterials() {
		return districtId != null && !growth() && vPlanId != null && dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId) == null
			&& (regionMaterials() || districtPlannedMaterials);
	}

	/** (slice SD) The world built regions from materials when this build planned its district (in memory). */
	private boolean districtPlannedMaterials;

	/** (slice SD) The quote said and approved for exactly this plan and these entries (in memory: a restart quotes and asks again before placing). */
	private @org.jspecify.annotations.Nullable String districtCleared, districtShown;
	/** (slice SD) Whether the cleared (or shown) quote's plan builds from materials: the realise then asks CONSTRUCTION. */
	private boolean districtSurvival, districtShownSurvival;
	/** (slice SD) A district's quote is out: nothing realises meanwhile, and no second quote starts. */
	private boolean districtQuoting;

	/** (slice SD) When a district's bill is quoted: before the placement approval, for a build placed without one, or at the realise (no approval of it). */
	enum BillAsk { BEFORE_APPROVAL, AUTO, AT_REALISE }

	/**
	 * (slice SD) Quotes a district's first building from materials (Architect's {@code Regions.quote}, read-only) with its final entries, and says it. A quote
	 * that fails or leaves a part unbilled ends the build before anything is consumed. Before the approval the bill is said and the approval that follows
	 * is of it; placed without an approval, an exact bill goes on, an inexact one is asked first; at the realise with no approval of this plan and these
	 * entries (a re-plan, a restart) it is asked. The approval is today's (no bound: slice QD's "up to N" comes later).
	 */
	private void quoteDistrict(Map<String, String> entries, BillAsk when) {
		String name = Settlements.store().get(settlement.id()).flatMap(x -> x.lands().district(districtId)).map(Settlement.District::name).orElse("The district");
		String plan0 = vPlanId;
		String key = ModulePlacement.cleared(vPlanId, entries);
		int session = Session.current();
		if (districtQuoting) return;
		districtQuoting = true;
		java.util.concurrent.CompletableFuture<dev.larattalabs.architect.api.Quote> q;
		try {
			q = ArchitectApi.get().regions().quote(vPlanId, entries);
		} catch (RuntimeException | LinkageError e) {
			q = java.util.concurrent.CompletableFuture.failedFuture(e);
		}
		q.whenComplete((quote, err) -> server.execute(() -> {
			if (!Session.is(session)) return;
			districtQuoting = false;
			if (!alive() || state == null || !java.util.Objects.equals(plan0, vPlanId) || vRegionId != null) return;
			if (switch (state.phase()) { case READY_TO_PLACE, AWAITING_PLACEMENT_APPROVAL, PLACING -> false; default -> true; }) return;
			var next = billNext(when, err == null && quote != null, quote != null && quote.survival(), regionMaterials(),
				quote == null ? null : ModulePlacement.quoteCheck(quote.exact(), quote.notes()));
			if (quote != null) Steward.LOGGER.info("district bill {} {}: {} (exact {}, notes {}, survival {}, {} -> {})", settlement.id(), vPlanId, quote.bom(), quote.exact(), quote.notes(),
				quote.survival(), when, next);
			boolean survival = quote != null && quote.survival();
			String line = quote == null ? null : districtBillLine(name, quote, null);
			switch (next) {
				case CANCEL_UNQUOTED -> {
					if (err != null) Steward.LOGGER.warn("district bill {} {}: not quoted: {}", settlement.id(), vPlanId, message(err), err);
					say("Not placed: " + name + "'s bill couldn't be quoted (" + RefusalWords.of(err) + "), so nothing is placed unchecked. Start it again.");
					feed(new Pipeline.Cancel());
				}
				case CANCEL_UNBILLED -> {
					say("Not placed: Architect couldn't quote all of " + name + "'s bill (" + RefusalWords.unbilled(ModulePlacement.quoteCheck(quote.exact(), quote.notes()).refused())
						+ "), so nothing is consumed unchecked.");
					feed(new Pipeline.Cancel());
				}
				case QUEUE -> {
					if (survival) say(line);
					districtCleared = key;
					districtSurvival = survival;
					feed(new Pipeline.BatchQueued(VPLAN_PENDING));
				}
				case REALISE -> {
					if (survival) say(line);
					districtCleared = key;
					districtSurvival = survival;
					realiseVillage();
				}
				case HOLD -> {
					districtShown = key;
					districtShownSurvival = true;
					var ask = ModulePlacement.quoteCheck(quote.exact(), quote.notes()).ask();
					feed(new Pipeline.HoldBill(line + (when == BillAsk.AUTO ? " Architect can't quote it exactly (" + ask + "), so I ask first. Approve to build it, or cancel."
						: " Approve to build it with this bill, or cancel.")));
				}
			}
		}));
	}

	/** (slice SD) What follows a district's quote. */
	enum BillNext { CANCEL_UNQUOTED, CANCEL_UNBILLED, QUEUE, REALISE, HOLD }

	/**
	 * (slice SD) What follows a district's quote: {@code quoted} whether Architect answered, {@code survival} whether its plan builds from materials (the
	 * quote's own word, never the world's now; {@code worldMaterials} is not read), {@code check} the quote's exactness. No quote: ended before anything
	 * is placed, in either mode (a plan's mode is known only from its quote). A plan built at once: on, unsaid. From materials: a part unbilled ends it; before the approval, said and approved; placed
	 * without one, an exact bill goes on and an inexact one is asked; at the realise with no approval of it, asked. Pure.
	 */
	static BillNext billNext(BillAsk when, boolean quoted, boolean survival, boolean worldMaterials, ModulePlacement.@org.jspecify.annotations.Nullable QuoteCheck check) {
		// no quote: the plan's mode isn't known (the world's now is no proof of it), so nothing is placed unchecked
		if (!quoted) return BillNext.CANCEL_UNQUOTED;
		if (!survival) return when == BillAsk.BEFORE_APPROVAL ? BillNext.QUEUE : BillNext.REALISE;
		if (check != null && check.refused() != null) return BillNext.CANCEL_UNBILLED;
		return switch (when) {
			case BEFORE_APPROVAL -> BillNext.QUEUE;
			case AUTO -> check == null || check.ask() == null ? BillNext.REALISE : BillNext.HOLD;
			case AT_REALISE -> BillNext.HOLD;
		};
	}

	/**
	 * (slice SD) What a district's first building from materials takes from the stockpile, said before it is approved: Architect's quote of its plan with
	 * these designs (its street, its join, its lots' buildings), "about" when Architect can't say it exactly, or that it couldn't be quoted. Pure.
	 */
	static String districtBillLine(String name, dev.larattalabs.architect.api.@org.jspecify.annotations.Nullable Quote q, @org.jspecify.annotations.Nullable String error) {
		if (q == null) return "From materials, from the stockpile: " + name + "'s bill couldn't be quoted (" + error + "); it's said as it's built.";
		JsonObject cost = new JsonObject();
		cost.add("bom", q.bom() == null ? new JsonObject() : q.bom());
		String bill = Composer.billLine(cost);
		if (bill == null) return "From materials, " + name + " takes nothing from the stockpile.";
		return "From materials, " + name + " needs " + (q.exact() ? "" : "about ") + bill + " from the stockpile"
			+ (q.exact() || q.notes().isEmpty() ? "" : " (" + String.join("; ", q.notes()) + ")") + ". Fill the stockpile as it's built.";
	}

	/** The village's lots, each with its design (a lot with none stays a pad): what its realise and quote take. Null when the group is gone. */
	private @org.jspecify.annotations.Nullable Map<String, String> villageLotEntries() {
		Group g = groupId == null ? null : ArchitectAccess.api().designs().group(groupId).orElse(null);
		if (g == null || plan == null) return null;
		Map<String, String> entries = new LinkedHashMap<>();
		for (Group.Item it : g.items()) it.entryId().ifPresent(e -> entries.put(it.itemKey(), e));
		Map<String, String> lotEntries = new LinkedHashMap<>();
		for (VillageLayout.Lot l : plan.lots()) if (entries.containsKey(l.id())) lotEntries.put(l.id(), entries.get(l.id()));
		return lotEntries;
	}

	/** What the pipeline holds as the batch of a village waiting for the player's approval to be placed. */
	static final String VPLAN_PENDING = "village:pending";

	private void realiseVillage() {
		if (vRegionId != null || queueInFlight || districtQuoting) return;
		// (batch-077 review P1-2) nothing is realised while settlements are disabled
		if (Settlements.disabled()) { Steward.LOGGER.warn("{}: not realised: settlements are disabled this session", claimedId); return; }
		var api = ArchitectAccess.api();
		Map<String, String> entries = new LinkedHashMap<>();
		// (slice M3) module lots: their bound variants, made (or found by key) and their bill quoted first; the realise waits until then
		if (moduleBuild()) {
			if (!modulesReady()) return;
			moduleBindings.forEach((lot, b) -> { if (b.entryId() != null && moduleOrders.containsKey(lot)) entries.put(lot, b.entryId()); });
		}
		Group g = groupId == null ? null : api.designs().group(groupId).orElse(null);
		// (slice M5) a composed town's module lots: bound to the artifact's lots now, and from materials each held to its bound in one quote of the whole
		// realise; the realise waits until then
		if (composedModules()) {
			// a town of module lots only has no design group
			if (g == null && !designPlan().lots().isEmpty()) { say("The group disappeared."); return; }
			Map<String, String> designed = new LinkedHashMap<>();
			if (g != null) for (Group.Item it : g.items()) it.entryId().ifPresent(e -> designed.put(it.itemKey(), e));
			if (!composedReady(designed)) return;
			moduleBindings.forEach((lot, b) -> { if (b.entryId() != null && moduleOrders.containsKey(lot)) entries.put(lot, b.entryId()); });
		}
		if (g == null && !moduleBuild() && !composedModules()) { say("The group disappeared."); return; }
		if (g != null) for (Group.Item it : g.items()) it.entryId().ifPresent(e -> entries.put(it.itemKey(), e));
		// only the planned lots, each with its design (a copy included); a lot with none stays a pad
		Map<String, String> lotEntries = new LinkedHashMap<>();
		for (VillageLayout.Lot l : plan.lots()) if (entries.containsKey(l.id())) lotEntries.put(l.id(), entries.get(l.id()));
		var missing = plan.lots().stream().map(VillageLayout.Lot::id).filter(id -> !lotEntries.containsKey(id)).toList();
		// (docs/MODULES.md 4.2 step 6) a module lot whose entry is missing refuses the realise: never left as a pad
		var moduleMissing = missing.stream().filter(moduleOrders::containsKey).toList();
		if (!moduleMissing.isEmpty()) {
			say("Not placed: the module lot " + String.join(", ", moduleMissing) + " has no entry bound, and a module lot is never left as a pad.");
			feed(new Pipeline.Cancel());
			return;
		}
		// (slice SD) a district from materials is realised only with the bill approved for exactly this plan and these entries (a re-plan or a restart
		// since: quoted and asked again)
		// a realise Architect has already under this build's keys (a restart between it and its saved answer) is picked up, never quoted or asked again
		if (districtFromMaterials() && !ModulePlacement.cleared(vPlanId, lotEntries).equals(districtCleared)) {
			if (!findVillage()) quoteDistrict(lotEntries, BillAsk.AT_REALISE);
			return;
		}
		if (!missing.isEmpty()) say("Left as pads (no design): " + String.join(", ", missing));
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", settlement.id());
		ext.addProperty(BatchPlanner.BUILD_EXT, buildId);
		// a district's region names its district, so a region whose answer was lost still holds the district (releaseDistrict, Districts.reconcile)
		if (districtId != null) ext.addProperty(Districts.DISTRICT_EXT, districtId);
		// a district's: its region may not take another district's land (a build saved before planning checked that, or a district founded since)
		String conflict = districtConflict();
		if (conflict != null) {
			say("Not placed: " + conflict + (growth() ? " The build ends here." : " The build ends here; undo the reservation and found the district again."));
			feed(new Pipeline.Cancel());
			return;
		}
		// (slice A2) checked again at the realise (the designs took a while): the town's mode against the world's, a town built from materials fully built
		// and its stockpile standing; else nothing is placed
		if (growth()) {
			String no = growthRealiseRefusal();
			if (no != null) { say("Not placed: " + no); feed(new Pipeline.Cancel()); return; }
		}
		// (slice M3) the player approved it at Accept (or approved the bill since): placed now, never asked again
		if (moduleBuild() && state.phase() == Pipeline.Phase.READY_TO_PLACE) feed(new Pipeline.ModulePlace());
		String districtName = districtId == null ? null : Settlements.store().get(settlement.id()).flatMap(x -> x.lands().district(districtId)).map(Settlement.District::name).orElse(null);
		say(growth() ? "Placing it in " + growingName() + " (" + lotEntries.size() + (lotEntries.size() == 1 ? " building" : " buildings") + ")..."
			: districtName != null ? "Placing " + districtName + " (" + Words.count(lotEntries.size(), "building", "buildings") + ", its street and its road to the town)..."
			: "Placing the village (" + Words.count(lotEntries.size(), "building", "buildings") + ", its street, lanes and square)...");
		queueInFlight = true;
		inFlightSince = System.currentTimeMillis();
		ServerPlayer actor = ArchitectAccess.player(server, playerId);
		int session = Session.current();
		String artifact = dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId);
		if (artifact != null) {
			realiseArtifact(artifact, lotEntries, actor, ext, !dev.larattalabs.steward.gateway.PPlan.retried(vPlanId), session);
			return;
		}
		// keyed: asked again (a restart, a lost answer) it is the same realise, never a second village
		// a district planned again after its realise was refused realises under the second key (found again by it after a restart)
		String key = growth() ? opKey("vgrow3".equals(growKey) ? "vgrowrealise3" : "vgrowrealise")
			: districtId != null && api.regions().planByKey(settlement.owner(), opKey("vplan2")).map(p -> p.planId().equals(vPlanId)).orElse(false)
			? opKey("vrealise2") : opKey("vrealise");
		// (slice A2) a growth of a town built from materials is built from them (Architect realises a revision in its town's own mode; the request says so)
		// (slice SD) a district's own region in a world that builds from materials is built from the stockpile: the request says so (Architect builds it
		// in the mode its plan was made in, and refuses a plan made in another mode than the world's now)
		Mode mode = growth() ? (growRegion != null && api.regions().get(growRegion).map(Growths::fromMaterials).orElse(false) ? Mode.CONSTRUCTION : Mode.INSTANT)
			: districtRealiseMode(districtId != null, districtSurvival);
		var request = new dev.larattalabs.architect.api.RealiseRequest(vPlanId, mode, actor, lotEntries, null, true, null, false, ext, 0, key);
		// (QD) a module growth from materials is held to the bound the player approved: Architect refuses it at put-down past that bound
		// (BILL_CAP), and draws nothing past it while it builds (the bill_cap wait)
		var cap = mode == Mode.CONSTRUCTION ? moduleCap() : null;
		// never realised from materials uncapped (quoteModules refuses an Architect without maxBill before this)
		if (cap == null && mode == Mode.CONSTRUCTION && moduleBuild()) {
			queueInFlight = false;
			say("Not placed: the module's bill can't be held to what you approved here. Nothing was consumed.");
			feed(new Pipeline.Cancel());
			return;
		}
		if (cap != null) {
			// (TEST, QD-2: /steward dev cap_short) once: one item sent as 0, so Architect refuses the realise at put-down
			String shortItem = DEV_CAP_SHORT.getAndSet(null);
			if (shortItem != null && cap.containsKey(shortItem)) {
				var shorted = new TreeMap<>(cap);
				shorted.put(shortItem, 0);
				cap = java.util.Collections.unmodifiableMap(shorted);
				Steward.LOGGER.info("module {} realise {}: dev cap_short {}", settlement.id(), vPlanId, shortItem);
			}
			Steward.LOGGER.info("module {} realise {} under {}: maxBill {}", settlement.id(), vPlanId, key, cap);
			request = request.withMaxBill(cap);
		}
		// (FENCE-TESTS-4) a throw on the call itself is answered as a failed realise (as sendApproved's is), never left in flight
		java.util.concurrent.CompletableFuture<String> sent;
		try {
			sent = api.regions().realise(request);
		} catch (RuntimeException | LinkageError e) {
			sent = java.util.concurrent.CompletableFuture.failedFuture(e);
		}
		sent.whenComplete((id, err) -> ArchitectAccess.onServer(server, () -> realised(id, err, session)));
	}

	// ----------------------------------------------------------------- module lots (slice M3, docs/MODULES.md 4.2)

	/** The growth plan's own lots (box, frame, IRR's report), read back by the key that planned it; null when Architect has it no more. */
	private @org.jspecify.annotations.Nullable List<dev.larattalabs.architect.api.LotSpec> growthPlanLots() {
		if (vPlanId == null || growKey == null) return null;
		return ArchitectAccess.api().regions().planByKey(settlement.owner(), opKey(growKey)).filter(p -> vPlanId.equals(p.planId()))
			.map(dev.larattalabs.architect.api.RegionPlan::lots).orElse(null);
	}

	/** Whether a callback may still act: the runner is the world's, and the build is still being placed (not cancelled, not ended). */
	private boolean placingModules(int session) {
		return Session.is(session) && alive() && state != null && switch (state.phase()) {
			case READY_TO_PLACE, PLACING -> true;
			default -> false;
		};
	}

	/**
	 * Whether every module lot is bound to a variant made for its geometry now, and (from materials) its quote cleared for exactly these entries.
	 * When not, it starts what is missing (one at a time) and answers false; that work calls {@link #realiseVillage} again when it is done.
	 */
	private boolean modulesReady() {
		if (moduleBusy || !placingModules(Session.current())) return false;
		var lots = growthPlanLots();
		if (lots == null) {
			say("Not placed: the town's growth plan can't be read back from Architect, so the module can't be bound to its lot. Nothing was placed.");
			feed(new Pipeline.Cancel());
			return false;
		}
		for (var e : moduleOrders.entrySet()) {
			var spec = lots.stream().filter(l -> l.id().equals(e.getKey())).findFirst();
			if (spec.isEmpty()) {
				say("Not placed: the growth plan has no lot " + e.getKey() + " for the module. Nothing was placed.");
				feed(new Pipeline.Cancel());
				return false;
			}
			var b = moduleBindings.get(e.getKey());
			if (!ModulePlacement.holds(b, e.getValue(), ModulePlacement.geometry(spec.get())) || b.entryId() == null) {
				bindModule(e.getKey(), e.getValue(), ModulePlacement.geometry(spec.get()), ModulePlacement.reserved(spec.get()));
				return false;
			}
		}
		if (growRegion != null && ArchitectAccess.api().regions().get(growRegion).map(Growths::fromMaterials).orElse(false)) {
			Map<String, String> entries = new TreeMap<>();
			moduleBindings.forEach((lot, b) -> { if (moduleOrders.containsKey(lot)) entries.put(lot, b.entryId()); });
			if (!ModulePlacement.cleared(vPlanId, entries).equals(quoteCleared)) {
				quoteModules(entries);
				return false;
			}
		}
		return true;
	}

	/**
	 * Binds one module lot (4.2 steps 3-4, "A stale plan", "Recovery"): a binding that still holds is kept; else (none, or the re-plan moved the lot)
	 * a new binding under the next key is saved <b>before</b> Architect is asked, so a restart finds the variant by that key and never makes a
	 * second. The variant is looked up by its key first, then made (a keyed request: asked again with the same body, the same entry).
	 */
	private void bindModule(String lotId, dev.larattalabs.steward.module.ModuleOrder order, JsonObject geometry,
		dev.larattalabs.architect.api.DesignRequest.@org.jspecify.annotations.Nullable Reserved reserved) {
		var entry = ModuleOffers.catalog().get(order.moduleId(), order.version()).filter(x -> x.programSha().equals(order.programSha()));
		if (entry.isEmpty()) {
			say("Not placed: the catalog no longer holds " + order.moduleId() + "@" + order.version() + " as it was offered. Nothing was placed.");
			feed(new Pipeline.Cancel());
			return;
		}
		var old = moduleBindings.get(lotId);
		dev.larattalabs.steward.module.ModuleBinding b;
		if (ModulePlacement.holds(old, order, geometry)) b = old;
		else {
			var bible = settlement.bible() != null && settlement.bible().styleVersion() == settlement.styleVersion() ? settlement.bible() : null;
			// (slice M5) a composed town's module is dressed in this build's own bible (made after the plan), the one its palette was checked against
			String bibleId = composedModules() && state != null && state.bibleId() != null ? state.bibleId() : bible == null ? null : bible.id();
			int bibleVersion = composedModules() && state != null && state.bibleId() != null && state.bibleVersion() != null ? state.bibleVersion() : bible == null ? 0 : bible.version();
			String key = ModulePlacement.nextKey(old, buildId, lotId);
			if (old != null) say("The town changed under the plan: the module is made again for its new lot.");
			b = new dev.larattalabs.steward.module.ModuleBinding(order.moduleId(), order.version(), order.programSha(), order.params(), null,
				bibleId, bibleId == null ? 0 : bibleVersion, dev.larattalabs.steward.module.Canon.sha(geometry), key, settlement.owner());
			var next = new LinkedHashMap<>(moduleBindings);
			next.put(lotId, b);
			moduleBindings = java.util.Collections.unmodifiableMap(next);
			// saved before it is asked: a restart finds the variant by this key, never makes a second
			if (!persist()) { say("Not placed: the build could not be saved, so the module is not asked for."); feed(new Pipeline.Cancel()); return; }
		}
		moduleBusy = true;
		int session = Session.current();
		var bound = b;
		ModuleInstalls.byKey(b.opKey(), b.owner()).whenComplete((found, err) -> server.execute(() -> {
			if (!placingModules(session)) { moduleBusy = false; return; }
			if (err == null && found.isPresent()) { moduleBound(lotId, bound, found.get()); return; }
			var pin = bound.bibleId() == null ? null : new dev.larattalabs.architect.api.BiblePin(bound.bibleId(), bound.bibleVersion());
			ModuleInstalls.variant(entry.get(), ModuleOffers.env(), bound.params(), pin, reserved, bound.opKey(), bound.owner(),
				dev.larattalabs.steward.module.McBlockFacts.INSTANCE).whenComplete((o, err2) -> server.execute(() -> {
					if (!placingModules(session)) { moduleBusy = false; return; }
					if (err2 != null || o == null || !o.ok()) {
						moduleBusy = false;
						// a lost or late answer is not a refusal: the variant may still be made, and its key finds it, so it is asked again (bounded)
						if (lostAnswer(err2, o) && moduleRetries++ < MODULE_RETRIES) {
							Steward.LOGGER.info("module {} lot {}: no answer for {} ({}), asking again by its key", settlement.id(), lotId, bound.opKey(), o == null ? message(err2) : o.message());
							java.util.concurrent.CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS).execute(() -> server.execute(() -> {
								if (placingModules(session)) realiseVillage();
							}));
							return;
						}
						Steward.LOGGER.warn("module {} lot {}: no variant ({}): {}", settlement.id(), lotId, o == null ? "error" : o.detail(), o == null ? message(err2) : o.message());
						say(moduleNotMadeLine(o == null ? null : o.detail(), o == null ? null : o.message(), err2));
						feed(new Pipeline.Cancel());
						return;
					}
					moduleBound(lotId, bound, o.entryId());
				}));
		}));
	}

	/**
	 * (QD, S-M5-9) The cap a module growth from materials is realised under: its one module lot's approved bound as saved (the measured bound the
	 * player approved at Accept, or the bill they approved since: the quote plus the terrain allowance), item by item. Null when it isn't a
	 * module build, Architect has no {@code maxBill}, or there is no bound (quoteModules refuses that before a realise).
	 */
	private java.util.@org.jspecify.annotations.Nullable Map<String, Integer> moduleCap() {
		return realiseCap(true, moduleBuild(), moduleOrders, ArchitectAccess.api().features().contains(MAX_BILL));
	}

	/**
	 * (QD, BC-d) The {@code maxBill} a realise or revision is sent with, or null for none. Since Architect v0.73.0 (BC-d) a cap covers only the
	 * realise or revision it comes with ({@code survival.caps.<rid>.maxBill}, kept by Architect): a revision sent without one is uncapped. So
	 * only a module growth built from materials is capped (its one module lot's approved bound); a plain growth, a district or a town built at
	 * once never sends one, and is never held to an earlier module growth's bound. Steward keeps no cap of its own beyond the order's bound. Pure.
	 */
	static java.util.@org.jspecify.annotations.Nullable Map<String, Integer> realiseCap(boolean fromMaterials, boolean moduleBuild,
			Map<String, dev.larattalabs.steward.module.ModuleOrder> orders, boolean hasMaxBill) {
		if (!fromMaterials || !moduleBuild || orders.size() != 1 || !hasMaxBill) return null;
		return dev.larattalabs.steward.module.ModuleBound.cap(orders.values().iterator().next().bound());
	}

	/** Lost or late variant answers asked again by key, this build (a refusal is never retried). */
	private int moduleRetries;
	static final int MODULE_RETRIES = 3;

	/** Whether a variant's answer was lost or late (a timeout, the helper's link), not a refusal: Architect's typed refusals and the checker's findings are definitive. */
	static boolean lostAnswer(@org.jspecify.annotations.Nullable Throwable err, ModuleInstalls.@org.jspecify.annotations.Nullable Outcome o) {
		if (err != null) return uncertain(err);
		if (o == null || o.detail() == null) return o == null;
		if (o.detail().startsWith("sidecar_unavailable")) return true;
		String m = String.valueOf(o.message()).toLowerCase(java.util.Locale.ROOT);
		return o.detail().equals("error") && (m.contains("timeoutexception") || uncertain(new RuntimeException(o.message())));
	}

	private void moduleBound(String lotId, dev.larattalabs.steward.module.ModuleBinding b, String entryId) {
		var next = new LinkedHashMap<>(moduleBindings);
		next.put(lotId, b.withEntry(entryId));
		moduleBindings = java.util.Collections.unmodifiableMap(next);
		moduleBusy = false;
		if (!persist()) { say("Not placed: the build could not be saved."); feed(new Pipeline.Cancel()); return; }
		Steward.LOGGER.info("module {} lot {}: {} under {}", settlement.id(), lotId, entryId, b.opKey());
		realiseVillage();
	}

	/**
	 * (4.2 step 5, section 6) The whole growth's bill, quoted read-only with the final entries (Architect's {@code Regions.quote}), against the bound the
	 * player approved, before anything is consumed. Past it (or, Autonomous, over half the stockpile's count of an item: Q4) the build holds and asks;
	 * within it, the realise goes on for exactly these entries.
	 */
	private void quoteModules(Map<String, String> entries) {
		if (moduleOrders.size() != 1) {
			say("Not placed: one module lot is billed per growth for now. Nothing was consumed.");
			feed(new Pipeline.Cancel());
			return;
		}
		var lot = moduleOrders.keySet().iterator().next();
		var order = moduleOrders.get(lot);
		if (order.bound() == null) {
			say("Not placed: the town builds from materials and the module has no approved bound to hold its bill to. Nothing was consumed.");
			feed(new Pipeline.Cancel());
			return;
		}
		// (QD, S-M5-9) a module from materials is realised under its approved bound as maxBill, never uncapped
		if (!ArchitectAccess.api().features().contains(MAX_BILL)) {
			say("Not placed: this Architect can't hold a build to the bill you approved (it has no maxBill). Nothing was consumed.");
			feed(new Pipeline.Cancel());
			return;
		}
		moduleBusy = true;
		int session = Session.current();
		String key = ModulePlacement.cleared(vPlanId, entries);
		ArchitectAccess.api().regions().quote(vPlanId, entries).whenComplete((q, err) -> ArchitectAccess.onServer(server, () -> {
			moduleBusy = false;
			if (!placingModules(session)) return;
			if (err != null || q == null) {
				if (err != null) Steward.LOGGER.warn("module quote {}: not quoted: {}", settlement.id(), message(err), err);
				say("Not placed: the bill couldn't be quoted (" + RefusalWords.of(err) + "), and nothing is consumed unchecked.");
				feed(new Pipeline.Cancel());
				return;
			}
			JsonObject bill = dev.larattalabs.steward.module.ModuleBound.clean(q.bom());
			if (bill == null) { say("Not placed: Architect's quote is not a bill of items. Nothing was consumed."); feed(new Pipeline.Cancel()); return; }
			// (QD, Architect QL) the quote's terrain part: those items may go a little over (the allowance), every other item stays exact; an
			// Architect without it keeps every item exact
			Map<String, Integer> terrain = ArchitectAccess.api().features().contains(QUOTE_TERRAIN) ? q.terrain() : Map.of();
			JsonObject allowed = dev.larattalabs.steward.module.ModuleBound.allowed(bill, terrain);
			if (allowed == null) { say("Not placed: Architect's quote has a terrain count that is not a count. Nothing was consumed."); feed(new Pipeline.Cancel()); return; }
			var now = moduleOrders.get(lot);
			Steward.LOGGER.info("module {} quote {}: {} (bound {}, exact {}, notes {}, terrain {})", settlement.id(), vPlanId, bill, now.bound(), q.exact(), q.notes(), terrain);
			var check = ModulePlacement.quoteCheck(q.exact(), q.notes());
			// a quote that leaves a part unbilled can't hold the build to anything: refused, nothing consumed
			if (check.refused() != null) {
				say("Not placed: Architect couldn't quote the whole bill (" + RefusalWords.unbilled(check.refused()) + "), so nothing is consumed unchecked.");
				feed(new Pipeline.Cancel());
				return;
			}
			var over = dev.larattalabs.steward.module.ModuleBound.over(bill, now.bound());
			List<dev.larattalabs.steward.module.ModuleBound.Heavy> heavy = List.of();
			if (now.autonomous()) {
				Map<String, Integer> stock = new HashMap<>();
				Stockpiles.of(server, Settlements.store().get(settlement.id()).orElse(settlement))
					.ifPresent(sp -> sp.stock().forEach((it, n) -> stock.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).toString(), n)));
				heavy = dev.larattalabs.steward.module.ModuleBound.heavy(bill, stock);
			}
			boolean refusedAtPutDown = now.refusedAtPutDown();
			String why = holdWhy(now, over, heavy, check);
			if (why != null) {
				// held, saved with the build: the approval binds to exactly this bill (the quote, a terrain item up to its allowance), also after a restart.
				// Asked again after a put-down refusal, it is never less than what was already approved, item by item
				JsonObject shown = refusedAtPutDown ? dev.larattalabs.steward.module.ModuleBound.atLeast(allowed, now.bound()) : allowed;
				// (FENCE-TESTS-4, persist before mutate) a hold that isn't saved is never put to the player: rolled back, the build stops as saved
				if (!saveOrders(Map.of(lot, now.holding(shown)))) {
					say("Not placed: the build could not be saved with the module's bill, so nothing is consumed.");
					feed(new Pipeline.Cancel());
					return;
				}
				feed(new Pipeline.HoldBill(why + " Nothing has been taken from the stockpile. The whole bill: " + dev.larattalabs.steward.module.ModuleBound.upTo(shown, terrain.keySet(), 1000)
					+ ". Approve to build it with this bill (it becomes the bound: " + dev.larattalabs.steward.module.ModuleBound.CRASH_EXCEPTION + "), or cancel."));
				return;
			}
			// (FENCE-TESTS-4) a dropped hold that isn't saved goes no further: rolled back, the build stops as saved
			if (now.held() != null && !saveOrders(Map.of(lot, now.holding(null)))) {
				say("Not placed: the build could not be saved with the module's bill, so nothing is consumed.");
				feed(new Pipeline.Cancel());
				return;
			}
			quoteCleared = key;
			realiseVillage();
		}));
	}

	/**
	 * (FENCE-TESTS-4) The module orders set to {@code next} and saved; a save that fails puts them back as they were, in the runner and in the shared
	 * store (another build's save never writes the rejected ones). Whether it landed.
	 */
	private boolean saveOrders(Map<String, dev.larattalabs.steward.module.ModuleOrder> next) {
		var before = moduleOrders;
		moduleOrders = next;
		if (persist()) return true;
		moduleOrders = before;
		if (alive() && settlement != null && state != null && plan != null) cache();
		return false;
	}

	/**
	 * Why a module growth's quote is put to the player before its realise, or null when it goes on: refused at put-down ({@code BILL_CAP}, saved on
	 * the order: QD-2), past the bound, (Autonomous) heavy on the stockpile, or inexact and not approved so. Pure.
	 */
	static @org.jspecify.annotations.Nullable String holdWhy(dev.larattalabs.steward.module.ModuleOrder order, List<dev.larattalabs.steward.module.ModuleBound.Over> over,
		List<dev.larattalabs.steward.module.ModuleBound.Heavy> heavy, ModulePlacement.QuoteCheck check) {
		return order.refusedAtPutDown() ? dev.larattalabs.steward.module.ModuleBound.putDownRefused(order)
			: !over.isEmpty() ? "The bill came to more than you approved: " + dev.larattalabs.steward.module.ModuleBound.words(over, 100) + "."
			: !heavy.isEmpty() ? "The whole bill takes more than half of the stockpile's " + heavy.stream().map(h -> dev.larattalabs.steward.module.ModuleBound.plain(h.item())
				+ " (" + h.bill() + " of " + h.stock() + ")").collect(Collectors.joining(", ")) + ", so I ask first."
			: check.ask() != null && !order.inexactApproved() ? "Architect can't quote the bill exactly (" + RefusalWords.inexact(check.ask()) + "), so I ask first." : null;
	}

	/** (TEST, QD-2) The item the next module realise sends as 0 ({@code /steward dev cap_short}), once; null for none. */
	private static final java.util.concurrent.atomic.AtomicReference<String> DEV_CAP_SHORT = new java.util.concurrent.atomic.AtomicReference<>();

	public static void devCapShort(@org.jspecify.annotations.Nullable String item) {
		DEV_CAP_SHORT.set(item);
	}

	// ----------------------------------------------------------------- module lots of a composed town (slice M5, docs/MODULES.md "M5 design")

	/**
	 * (slice M5, "M5 design" 4) Why this bible can't dress one of the town's module lots, in the steward's words, or null when it can (or can't be read:
	 * the variant's own refusal says so at the bind). Checked when the bible is approved, before the design group is asked for; the lamp's fallback is
	 * kept on the order and said.
	 */
	private @org.jspecify.annotations.Nullable String modulePalette(@org.jspecify.annotations.Nullable String bibleId, @org.jspecify.annotations.Nullable Integer bibleVersion) {
		if (bibleId == null || bibleVersion == null) return null;
		var bible = ArchitectApi.get().bibles().get(bibleId, bibleVersion);
		if (bible.isEmpty()) return null;
		var next = new LinkedHashMap<String, dev.larattalabs.steward.module.ModuleOrder>();
		for (var e : moduleOrders.entrySet()) {
			var o = e.getValue();
			var entry = ModuleOffers.catalog().get(o.moduleId(), o.version()).filter(x -> x.programSha().equals(o.programSha()));
			var kind = dev.larattalabs.steward.module.ModuleKind.of(o.moduleId());
			if (entry.isEmpty() || kind.isEmpty()) return "Stopped: the catalog no longer holds the working " + e.getKey().replace('_', ' ') + " as the plan named it. Nothing more was spent.";
			var pc = dev.larattalabs.steward.module.PaletteCheck.check(entry.get().palette(), bible.get().roles(), dev.larattalabs.steward.module.McBlockFacts.INSTANCE);
			if (!pc.ok()) return "Stopped: the town's style can't dress the working " + kind.get().role() + " (" + String.join("; ", pc.broken())
				+ "). Nothing more was spent; describe the settlement again for another style.";
			if (pc.lampFallback() && !o.lampFallback()) say("The working " + kind.get().role() + " is lit by plain lanterns: the town's lamp is too dim.");
			var withLamp = o.withLamp(pc.lampFallback());
			// (from materials) its bound in this bible's blocks, set now, before the designs are paid for: no bound stops the build here
			String sha = dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId);
			if (sha != null && artifactSurvival(sha) && withLamp.bound() == null) {
				JsonObject bound = ModuleOffers.bound(entry.get(), withLamp.params(),
					dev.larattalabs.steward.module.StyleBound.townItems(bible.get().roles(), pc.lamp(), ModuleOffers::itemOfBlock));
				if (bound == null) return "Stopped: the working " + kind.get().role() + "'s bill from the stockpile can't be held to its measured bound in the town's style. Nothing more was spent.";
				withLamp = withLamp.resized(withLamp.params(), bound);
			}
			next.put(e.getKey(), withLamp);
		}
		moduleOrders = java.util.Collections.unmodifiableMap(next);
		persist();
		return null;
	}

	/**
	 * (slice M5, "M5 design" 5, 6) Whether every module lot of the composed town is bound to a variant made for its lot in the approved artifact now, and
	 * (from materials) the whole realise quoted with exactly these entries and each module held to its bound. When not, it starts what is missing (one at
	 * a time) and answers false; that work calls {@link #realiseVillage} again. {@code designed}: the designed lots' entries.
	 */
	private boolean composedReady(Map<String, String> designed) {
		if (moduleBusy || !placingModules(Session.current())) return false;
		String sha = dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId);
		var body = sha == null ? null : ArchitectAccess.api().regions().artifact(sha).map(dev.larattalabs.architect.api.PlanArtifact::body).orElse(null);
		if (body == null || !body.has("lots") || !body.get("lots").isJsonArray()) {
			say("Not placed: the approved plan can't be read back from Architect, so its working modules can't be bound to their lots. Nothing was placed.");
			feed(new Pipeline.Cancel());
			return false;
		}
		Map<String, JsonObject> lots = new HashMap<>();
		for (var l : body.getAsJsonArray("lots")) if (l.isJsonObject() && l.getAsJsonObject().has("id")) lots.put(l.getAsJsonObject().get("id").getAsString(), l.getAsJsonObject());
		for (var e : moduleOrders.entrySet()) {
			var kind = dev.larattalabs.steward.module.ModuleKind.of(e.getValue().moduleId());
			JsonObject a = lots.get(e.getKey());
			String no = kind.isEmpty() ? "Steward no longer knows how to place " + e.getValue().moduleId()
				: a == null ? "the approved plan has no lot " + e.getKey().replace('_', ' ') + " for it"
				: dev.larattalabs.steward.module.ComposedModules.lotProblem(a, kind.get(), e.getValue());
			if (no != null) {
				say("Not placed: the working " + (kind.map(dev.larattalabs.steward.module.ModuleKind::role).orElse(e.getKey().replace('_', ' '))) + " can't go on its lot: " + no
					+ ". Nothing was placed.");
				feed(new Pipeline.Cancel());
				return false;
			}
			JsonObject geometry = dev.larattalabs.steward.module.ComposedModules.geometry(a);
			var b = moduleBindings.get(e.getKey());
			if (!ModulePlacement.holds(b, e.getValue(), geometry) || b.entryId() == null) {
				com.google.gson.JsonArray one = new com.google.gson.JsonArray();
				one.add(a);
				bindModule(e.getKey(), e.getValue(), geometry, dev.larattalabs.steward.gateway.PPlan.reserved(one).get(e.getKey()));
				return false;
			}
		}
		if (artifactSurvival(sha)) {
			Map<String, String> entries = new TreeMap<>(designed);
			moduleBindings.forEach((lot, b) -> { if (moduleOrders.containsKey(lot)) entries.put(lot, b.entryId()); });
			if (!ModulePlacement.cleared(vPlanId, entries).equals(quoteCleared)) {
				quoteComposed(sha, entries);
				return false;
			}
		}
		return true;
	}

	/**
	 * (slice M5, "M5 design" 6) The composed town's whole realise, quoted read-only ({@code quoteApproved}) with its final entries; each module lot's own
	 * pieces ({@code lot:<id>}, {@code fill:<id>}) held to its bound, set first from its measurement in the town's blocks and saved. Past a bound, or a
	 * quote that may differ, the build holds and asks (each module's share saved as the bill the player approves); a module lot the quote doesn't bill,
	 * or a part left unbilled, refuses; within, the realise goes on for exactly these entries. The rest of the town is billed as a Supplied plan v1
	 * town's is today.
	 */
	private void quoteComposed(String sha, Map<String, String> entries) {
		// the bounds: the measurement's, in this town's blocks, saved before anything is quoted (an order keeps one it has: approved, or set before)
		var now = Settlements.store().get(settlement.id()).orElse(settlement);
		var withBounds = new LinkedHashMap<String, dev.larattalabs.steward.module.ModuleOrder>();
		for (var e : moduleOrders.entrySet()) {
			var o = e.getValue();
			if (o.bound() == null) {
				final var order = o;
				var entry = ModuleOffers.catalog().get(order.moduleId(), order.version()).filter(x -> x.programSha().equals(order.programSha()));
				JsonObject bound = entry.map(x -> ModuleOffers.bound(x, order.params(), ModuleOffers.townItems(now, x))).orElse(null);
				if (bound == null) {
					say("Not placed: the working " + e.getKey().replace('_', ' ') + " has no measured bound to hold its bill to. Nothing was consumed.");
					feed(new Pipeline.Cancel());
					return;
				}
				o = o.resized(o.params(), bound);
			}
			withBounds.put(e.getKey(), o);
		}
		moduleOrders = java.util.Collections.unmodifiableMap(withBounds);
		if (!persist()) { say("Not placed: the build could not be saved, so nothing is quoted."); feed(new Pipeline.Cancel()); return; }
		moduleBusy = true;
		int session = Session.current();
		String key = ModulePlacement.cleared(vPlanId, entries);
		java.util.concurrent.CompletableFuture<dev.larattalabs.architect.api.Quote> fq;
		try {
			fq = ArchitectAccess.api().regions().quoteApproved(sha, entries);
		} catch (RuntimeException | LinkageError e) {
			fq = java.util.concurrent.CompletableFuture.failedFuture(e);
		}
		fq.whenComplete((q, err) -> ArchitectAccess.onServer(server, () -> {
			moduleBusy = false;
			if (!placingModules(session)) return;
			if (err != null || q == null) {
				if (err != null) Steward.LOGGER.warn("module quote {}: not quoted: {}", settlement.id(), message(err), err);
				say("Not placed: the bill couldn't be quoted (" + RefusalWords.of(err) + "), and nothing is consumed unchecked.");
				feed(new Pipeline.Cancel());
				return;
			}
			var check = ModulePlacement.quoteCheck(q.exact(), q.notes());
			Steward.LOGGER.info("composed modules {} quote {}: {} (exact {}, notes {})", settlement.id(), sha, q.bomParts(), q.exact(), q.notes());
			if (check.refused() != null) {
				say("Not placed: Architect couldn't quote the whole bill (" + RefusalWords.unbilled(check.refused()) + "), so nothing is consumed unchecked.");
				feed(new Pipeline.Cancel());
				return;
			}
			var verdict = dev.larattalabs.steward.module.ComposedModules.held(q.bomParts(), moduleOrders, check.ask() == null ? null : RefusalWords.inexact(check.ask()));
			if (verdict.refused() != null) {
				say("Not placed: " + verdict.refused() + ", so nothing is consumed unchecked.");
				feed(new Pipeline.Cancel());
				return;
			}
			// (LIFT-S) cleared: what the realise is sent with, each module lot held to its saved bound (Architect LCAP); from the orders as saved
			// below, so a restart quotes again and sends the same lot caps
			dev.larattalabs.steward.module.ComposedModules.Caps caps = null;
			if (verdict.ask() == null) {
				caps = dev.larattalabs.steward.module.ComposedModules.caps(q.bomParts(), verdict.orders(), entries.keySet());
				if (caps.refused() != null) {
					say("Not placed: " + caps.refused() + ", so nothing is consumed unchecked.");
					feed(new Pipeline.Cancel());
					return;
				}
			}
			var before = moduleOrders;
			moduleOrders = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(verdict.orders()));
			// (M5b, LIFT-S: persist before mutate) the hold or the cleared orders saved before anything is asked or realised; unsaved, nothing goes on
			var cleared = caps;
			boolean went = ComposedSteps.saveThen(this::persist, () -> {
				if (verdict.ask() != null) {
					// held, saved with the build: the approval binds to exactly these bills, also after a restart
					feed(new Pipeline.HoldBill(composedHoldWords(verdict.ask())));
					return;
				}
				// (Noah, 2026-11-15) each module's limit is shown with the quote
				if (verdict.said() != null) say(verdict.said());
				quoteCleared = key;
				composedCaps = cleared;
				composedCapsKey = key;
				realiseVillage();
			});
			if (!went) {
				// (LIFT-S GPT review) the rejected orders are not kept: the build stops as it was saved
				moduleOrders = before;
				say("Not placed: the build could not be saved with its working modules' bills, so nothing is consumed.");
				feed(new Pipeline.Cancel());
			}
		}));
	}

	/**
	 * (LIFT-S) The caps of the composed quote cleared this session ({@link #quoteCleared}), for its realise; null until then. Each lot's cap is its
	 * order's bound as saved; a restart quotes again before any realise, so they are never sent from anything but the saved orders.
	 */
	private dev.larattalabs.steward.module.ComposedModules.@org.jspecify.annotations.Nullable Caps composedCaps;
	/** (LIFT-S) The cleared quote's key ({@link #quoteCleared}) {@link #composedCaps} came with: a realise sends them only while it still holds. */
	private @org.jspecify.annotations.Nullable String composedCapsKey;

	/** (LIFT-S) Whether this Architect caps a realise per lot (LCAP: {@code maxBillPerLot}). */
	static boolean lotCaps() {
		try {
			return ArchitectAccess.api().features().contains(dev.larattalabs.steward.module.ComposedModules.LOT_CAP_FEATURE);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** (M5b) The composed hold's words: the bills, and what approving them means, the crash exception included (as every capped approval). Pure. */
	static String composedHoldWords(String ask) {
		return ask + " Nothing has been taken from the stockpile. Approve to build it with these bills (they become the bounds: "
			+ dev.larattalabs.steward.module.ModuleBound.CRASH_EXCEPTION + "), or cancel.";
	}

	/**
	 * (QD-2, M5b) The orders after a realise refused at put-down ({@code BILL_CAP}): each marked refused, so the next quote is put to the player,
	 * also after a restart, until they approve it ({@code ModuleOrder.approvedHeld}). Pure.
	 */
	static Map<String, dev.larattalabs.steward.module.ModuleOrder> refusedAtPutDown(Map<String, dev.larattalabs.steward.module.ModuleOrder> orders) {
		var out = new LinkedHashMap<String, dev.larattalabs.steward.module.ModuleOrder>();
		orders.forEach((lot, o) -> out.put(lot, o.refusedAtPutDown(true)));
		return java.util.Collections.unmodifiableMap(out);
	}

	/**
	 * The player approved a held bill (the approve that ends AWAITING_PLACEMENT_APPROVAL): the bill they were shown becomes the bound, saved before
	 * anything is quoted again, so the next quote within it goes on, also after a restart. Nothing held (an approve after a hold that cleared): unchanged.
	 */
	private boolean approveHeld() {
		var before = moduleOrders;
		var next = new LinkedHashMap<String, dev.larattalabs.steward.module.ModuleOrder>();
		moduleOrders.forEach((lot, o) -> next.put(lot, o.held() == null ? o : o.approvedHeld()));
		moduleOrders = java.util.Collections.unmodifiableMap(next);
		quoteCleared = null;
		// (M5b, persist before mutate) an approval that isn't saved isn't taken: the bill stays held, nothing is quoted or realised under it
		if (persist()) return true;
		// rolled back in the runner and in the shared store (GPT review P2: the store held the approval, which another build's save would write)
		moduleOrders = before;
		if (alive() && settlement != null && state != null && plan != null) cache();
		return false;
	}

	/** A restored module build that ended with nothing standing: its proposal back in the inbox (once), as the accepting hook would have. */
	private void giveModuleBack() {
		for (var o : moduleOrders.values()) {
			// the proposal exactly as it was offered: the build's copy of the settlement holds it as it was when the build started
			var offered = o.proposal() == null ? java.util.Optional.<dev.larattalabs.steward.model.Proposal>empty()
				: settlement.proposals().open().stream().filter(x -> o.proposal().equals(x.key()) && x.offersModule()).findFirst();
			if (offered.isPresent()) { Proposals.giveBack(settlement.id(), offered.get(), null); continue; }
			var kind = dev.larattalabs.steward.module.ModuleKind.of(o.moduleId());
			if (o.proposal() == null || kind.isEmpty() || plan == null) continue;
			var lot = new dev.larattalabs.steward.model.ConceptCard.Building(kind.get().role(), kind.get().lotType(), 1, "L", false, null, null);
			var p = dev.larattalabs.steward.model.Proposal.module(o.proposal(), Character.toUpperCase(kind.get().title().charAt(0)) + kind.get().title().substring(1),
				"Offered again: the last try placed nothing.", lot, System.currentTimeMillis(), "seeds", new dev.larattalabs.steward.module.ModuleOrder(o.moduleId(), o.version(),
					o.programSha(), o.params(), o.bound(), o.rate(), o.lampFallback(), false)).in(districtId);
			Proposals.giveBack(settlement.id(), p, null);
		}
	}

	private static JsonObject sum(JsonObject a, JsonObject b) {
		JsonObject o = a.deepCopy();
		for (var e : b.entrySet()) o.addProperty(e.getKey(), (o.has(e.getKey()) ? o.get(e.getKey()).getAsInt() : 0) + e.getValue().getAsInt());
		return o;
	}

	/**
	 * (slice SD) A region realise's refusal over materials in plain words, or null: the world's mode changed between the plan and the realise (Architect:
	 * "plan it again in survival/creative", or CONSTRUCTION outside a survival world), or the owner has no stockpile there. Pure.
	 */
	static @org.jspecify.annotations.Nullable String materialsRefusal(String m) {
		if (m.contains("plan it again in survival") || m.contains("plan it again in creative") || m.contains("CONSTRUCTION needs a survival world"))
			return "the world's mode changed while the buildings were designed (a region is built the way the world builds when it is planned). Start it again.";
		if (m.contains("has no stockpile") || m.contains("builds from its owner's stockpile"))
			return "the settlement's stockpile is gone, and a region built from materials is built from it. Start it again: a new one is made by the founding stone.";
		return null;
	}

	/** (slice SD) The mode a non-growth region realise asks: a district's region from materials when its approved quote says its plan builds so. Pure. */
	static Mode districtRealiseMode(boolean district, boolean planFromMaterials) {
		return district && planFromMaterials ? Mode.CONSTRUCTION : Mode.INSTANT;
	}

	/** Whether this world builds regions from materials (Architect's RegionSurvival.on: the survival toggle on, the default game type not creative). */
	private boolean regionMaterials() {
		return ArchitectAccess.regionMaterials(server);
	}

	/** (slice A2) Why a growth may not be realised now, or null: the mode gate again, and a town built from materials keeps its stockpile. */
	private @org.jspecify.annotations.Nullable String growthRealiseRefusal() {
		var town = growRegion == null ? java.util.Optional.<dev.larattalabs.architect.api.RegionView>empty() : ArchitectAccess.api().regions().get(growRegion);
		if (town.isEmpty()) return null;
		var v = town.get();
		boolean world = regionMaterials();
		boolean built = v.build() != null && v.build().built();
		if (Growths.fromMaterials(v) == world && Growths.fromMaterials(v) && !built)
			return growingName() + " is being built from the stockpile again (an update or another change); it can grow once it's built. Start it again then.";
		String no = Growths.modeRefusal(districtId == null ? settlement.name() : growingName(), Growths.fromMaterials(v), world, built, v.build() == null ? 0 : v.build().percent());
		if (no != null) return no + " Start it again.";
		if (Growths.fromMaterials(v) && !ArchitectAccess.hasStockpile(server, Settlements.store().get(settlement.id()).orElse(settlement)))
			return "the town's stockpile is gone, and a town built from materials grows from it. Start it again: a new one is made by the founding stone.";
		return null;
	}

	/**
	 * Realises the approved artifact (plan v1), with the designs that fit their lots (a design past its lot's approved box or height would make Architect
	 * refuse the whole village: that lot is left a pad and said). Stale (the land under the plan changed while the buildings were designed): the plan Architect
	 * froze in the artifact is checked once more, the new artifact saved with the build before the old one is let go, and realised under a second key; a
	 * restart after that does not retry again. A cancel, or a runner replaced, while the re-check runs ends it there. The pin goes when the region is taken
	 * as the build's ({@link #adoptVillage}) or the build ends ({@link #persist}).
	 */
	private void realiseArtifact(String sha, Map<String, String> lotEntries, @org.jspecify.annotations.Nullable ServerPlayer actor, JsonObject ext, boolean first, int session) {
		var regions = ArchitectAccess.api().regions();
		Map<String, String> fitting = palette(sha, fitting(sha, lotEntries));
		// (slice M5) a module lot is never left a pad: one Architect's box or a palette rule would drop refuses the realise, nothing placed
		var dropped = moduleOrders.keySet().stream().filter(l -> lotEntries.containsKey(l) && !fitting.containsKey(l)).sorted().toList();
		if (!dropped.isEmpty()) {
			realised(null, new RefusalWords.Own("the working " + String.join(", ", dropped.stream().map(l -> l.replace('_', ' ')).toList())
				+ " would be left a pad, and a module lot never is"), session);
			return;
		}
		// (Architect K3) a wall lot left without a design (none, or its design filtered out above) would leave the wall open where its tower or gatehouse
		// stands: Architect binds nothing there, so the build ends here, naming it
		var open = plan == null ? null : plan.lots().stream().filter(l -> l.exact() && !fitting.containsKey(l.id())).findFirst().orElse(null);
		if (open != null) {
			realised(null, new RefusalWords.Own("the " + open.role() + " on the wall has no design that fits its box (" + (lotEntries.containsKey(open.id())
				? "its design is past the lot's approved box or rule" : "it was not designed") + "), so the wall would stand open there"), session);
			return;
		}
		// (M5b, LIFT-S) a composed town's module lots from materials are held only by a cap scoped to each lot (Architect LCAP, maxBillPerLot): such a
		// realise is never sent uncapped (an Architect without it: the guard leaves them out before this; a build saved under one and resumed on
		// another stops here)
		boolean fromMaterials = artifactSurvival(sha);
		if (composedMaterialsUncapped(fromMaterials, moduleOrders, lotCaps())) {
			realised(null, new RefusalWords.Own("the working modules' bills can't be held to their measured limits in a town built from materials by this Architect"), session);
			return;
		}
		var request = new dev.larattalabs.architect.api.ApprovedRealise(sha, realiseMode(sha), actor, fitting, opKey("vrealise"), ext);
		if (fromMaterials && !moduleOrders.isEmpty()) {
			var caps = composedCaps;
			// only the caps of the quote cleared for exactly these entries this session, and only for lots this realise binds (never an earlier one's)
			if (caps == null || caps.perLot() == null || composedCapsKey == null || !composedCapsKey.equals(quoteCleared)
				|| !fitting.keySet().containsAll(caps.perLot().keySet()) || !caps.perLot().keySet().equals(moduleOrders.keySet())) {
				realised(null, new RefusalWords.Own("the working modules' bills weren't quoted for exactly this placing, and nothing is consumed unchecked"), session);
				return;
			}
			var perLot = devShortLot(caps.perLot());
			Steward.LOGGER.info("composed modules {} realise {} under {}: maxBillPerLot {}", settlement.id(), sha, opKey("vrealise"), perLot);
			request = new dev.larattalabs.architect.api.ApprovedRealise(sha, realiseMode(sha), actor, fitting, opKey("vrealise"), ext, null).withMaxBillPerLot(perLot);
		}
		// (slice 8aS) the lot entries it binds are logged too, so a check can quote the same realise (Architect's quoteApproved) against what it drew
		Steward.LOGGER.info("realise {}: artifact {} under key {} ({}) entries {}", settlement.id(), sha, opKey("vrealise"), first ? "first" : "after a re-check",
			new com.google.gson.Gson().toJson(fitting));
		var sent = request;
		// (LIFT-S, persist before mutate) the orders whose bounds are the lot caps saved before the realise is sent; unsaved, nothing is sent
		if (!ComposedSteps.saveThen(this::persist, () -> sendApproved(sha, sent, lotEntries, actor, ext, first, session)))
			realised(null, new RefusalWords.Own("the build could not be saved before placing, and nothing is consumed unchecked"), session);
	}

	/**
	 * (TEST, LIFT-S: {@code /steward dev cap_short}) The lot caps with one item sent as 0, once, in the first lot (by id) whose cap names it, so
	 * Architect refuses the realise at put-down ({@code BILL_CAP}, detail {@code lot:<id>}); unchanged when none is set or none names it.
	 */
	private Map<String, Map<String, Integer>> devShortLot(Map<String, Map<String, Integer>> perLot) {
		String item = DEV_CAP_SHORT.get();
		if (item == null) return perLot;
		for (var e : perLot.entrySet()) {
			if (!e.getValue().containsKey(item) || !DEV_CAP_SHORT.compareAndSet(item, null)) continue;
			var lots = new TreeMap<>(perLot);
			var shorted = new TreeMap<>(e.getValue());
			shorted.put(item, 0);
			lots.put(e.getKey(), java.util.Collections.unmodifiableMap(shorted));
			Steward.LOGGER.info("composed modules {}: dev cap_short {} in lot {}", settlement.id(), item, e.getKey());
			return java.util.Collections.unmodifiableMap(lots);
		}
		return perLot;
	}

	/** The approved artifact's realise, sent, and its answer (a stale artifact checked once more, a wall lot repaired). */
	private void sendApproved(String sha, dev.larattalabs.architect.api.ApprovedRealise request, Map<String, String> lotEntries,
		@org.jspecify.annotations.Nullable ServerPlayer actor, JsonObject ext, boolean first, int session) {
		var regions = ArchitectAccess.api().regions();
		// (FENCE-TESTS-3) a throw on the call itself is answered as a failed realise (as quoteComposed's quote is), never left in flight
		java.util.concurrent.CompletableFuture<String> sent;
		try {
			sent = regions.realiseApproved(request);
		} catch (RuntimeException | LinkageError e) {
			sent = java.util.concurrent.CompletableFuture.failedFuture(e);
		}
		sent
			.whenComplete((id, err) -> ArchitectAccess.onServer(server, () -> {
				if (!Session.is(session)) return;
				Throwable e = err == null ? null : err.getCause() != null ? err.getCause() : err;
				boolean stale = e instanceof dev.larattalabs.architect.api.ArchitectRefused rf && rf.reason() == dev.larattalabs.architect.api.Reason.ARTIFACT_STALE;
				// (Architect K3) a wall lot's design leaves the wall open, or its passage unstandable: the design is repaired, not the land re-checked
				String wallLot = stale ? wallLotOf(e.getMessage()) : null;
				if (wallLot != null && alive() && !over()) { wallRefused(lotEntries, session, wallLot, e.getMessage()); return; }
				if (!first || !stale || !alive() || over()) { realised(id, err, session); return; }
				Steward.LOGGER.info("{}: the artifact {} is stale: {}", settlement.id(), sha, e.getMessage());
				JsonObject frozen = frozenPlan(sha);
				// (B092-FIX, batch 092 GPT review P1; Noah 2026-11-21: "One narrow extra round") a sculpted site is never checked again here: it stops
				// before anything more is pinned, quoted or realised, and a new Start plans it anew (a re-check can't show its shaping is still the one
				// approved); every other site is checked again as before
				String sculptStop = dev.larattalabs.steward.gateway.VPlan.staleRecheckStop(settlement.card() == null ? null : settlement.card().site().terrain(),
					frozen, waitingOf(settlement));
				if (sculptStop != null) {
					Steward.LOGGER.info("{}: a stale artifact on a sculpted site: stopped, not checked again", settlement.id());
					realised(null, new RefusalWords.Own(sculptStop), session);
					return;
				}
				say("The land under the plan changed while the buildings were designed: checking it again...");
				if (frozen == null) { realised(null, new RefusalWords.Own("the approved plan could not be read again"), session); return; }
				var saved = Settlements.store().get(settlement.id()).orElse(settlement);
				Composer.check(server, saved, frozen, playerId).whenComplete((c, cerr) -> ArchitectAccess.onServer(server, () -> {
					if (!Session.is(session)) return;
					// a runner replaced meanwhile (cancelled twice, another build started): nothing more of this one reaches the world
					if (!alive()) return;
					if (over()) {
						queueInFlight = false;
						if (state != null && state.phase() == Pipeline.Phase.CANCELLING) feed(new Pipeline.CancelConfirmed());
						return;
					}
					if (cerr != null) Steward.LOGGER.warn("{}: the plan was not checked again: {}", settlement.id(), message(cerr));
					String no = cerr != null ? "it couldn't be checked again (" + RefusalWords.of(cerr) + ")" : unbuildable(c, Secrets.of(frozen, null));
					// the world's mode changed while the buildings were designed: built from materials now, or no longer; this build stops, a new Start plans anew
					if (no == null && c.survival() != artifactSurvival(sha)) no = "the world's mode changed while the buildings were designed (" + (c.survival()
						? "it builds from materials now" : "it no longer builds from materials") + "): start the build again";
					if (no != null) { realised(null, new RefusalWords.Own(no), session); return; }
					String next = c.artifactSha();
					// saved with the build before the old one is let go (a crash between keeps a target to resume from); the same sha keeps its pin
					pin(next);
					String before = vPlanId;
					vPlanId = dev.larattalabs.steward.gateway.PPlan.RETRIED_PREFIX + next;
					if (!persist()) {
						vPlanId = before;
						// the new pin is not the build's (it kept the old one): let go so it isn't orphaned
						if (!next.equals(sha)) regions.unpinArtifact(next, settlement.owner());
						realised(null, new RefusalWords.Own("the build could not be saved before placing again"), session);
						return;
					}
					if (!next.equals(sha)) regions.unpinArtifact(sha, settlement.owner());
					// (slice M5) module lots: bound again to the new artifact's lots where they moved, and quoted again, before the second realise
					if (composedModules()) { queueInFlight = false; quoteCleared = null; realiseVillage(); return; }
					realiseArtifact(next, lotEntries, actor, ext, false, session);
				}));
			}));
	}

	/**
	 * (M5b, LIFT-S) Whether a composed realise would build module lots from materials with no cap per lot: an Architect without LCAP's
	 * {@code maxBillPerLot} ({@code lotCaps} false). Pure.
	 */
	static boolean composedMaterialsUncapped(boolean fromMaterials, Map<String, dev.larattalabs.steward.module.ModuleOrder> orders, boolean lotCaps) {
		return fromMaterials && !orders.isEmpty() && !lotCaps;
	}

	/** Architect K3's refusal of a wall lot's bound design: "lot <id> on wall <wall>: ...". */
	private static final java.util.regex.Pattern ON_WALL = java.util.regex.Pattern.compile("^lot (\\S+) on wall \\S+:");

	/** The wall lot a refusal names, or null when it is not K3's wall refusal. */
	static @org.jspecify.annotations.Nullable String wallLotOf(@org.jspecify.annotations.Nullable String message) {
		if (message == null) return null;
		var m = ON_WALL.matcher(message.strip());
		return m.find() ? m.group(1) : null;
	}

	/** Whether this Architect keys design requests and answers a cancel (design.opKeys): a wall lot's repair needs both. */
	static boolean keyedDesigns() {
		try {
			return ArchitectApi.get().features().contains("design.opKeys");
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/**
	 * The bind refused a wall lot's design: the pipeline decides (a repair within the rounds and the budget, or the build stops naming it). No realise is in
	 * flight from here; the repair's operation key names the build, the lot and the round, so asking again is the same design.
	 */
	private void wallRefused(Map<String, String> lotEntries, int session, String lotId, String why) {
		queueInFlight = false;
		VillageLayout.Lot lot = plan == null ? null : plan.lots().stream().filter(l -> l.id().equals(lotId)).findFirst().orElse(null);
		String role = lot == null ? lotId : lot.role();
		if (!keyedDesigns()) {
			Steward.LOGGER.info("{}: the wall lot {}'s design was refused: {}", settlement.id(), lotId, why);
			realised(null, new RefusalWords.Own("Architect refused the " + role + " on the wall, and this Architect cannot repair a design safely"
				+ " (no design.opKeys)"), session);
			return;
		}
		String key = "steward." + buildId + ".repair." + lotId + "." + (state.repairs().getOrDefault(lotId, 0) + 1);
		feed(new Pipeline.WallRefused(lotId, role, why, lot != null && lotEntries.containsKey(lotId), key));
	}

	/** Asks for the repair of {@code lotId}'s design under its key: the next version of its entry with the refusal as the change, sized and briefed as its lot. */
	private void askRepair(Pipeline.AskRepair a) {
		var r = state.repair();
		VillageLayout.Lot lot = plan == null ? null : plan.lots().stream().filter(l -> l.id().equals(a.lotId())).findFirst().orElse(null);
		String entry = repairEntry(a.lotId());
		if (r == null || !r.key().equals(a.key()) || lot == null || entry == null) {
			later.add(new Pipeline.RepairEnded(a.key(), Pipeline.RepairEnd.REFUSED, 0, "its design could not be found to repair"));
			return;
		}
		dev.larattalabs.architect.api.DesignRequest req;
		try {
			var notes = (lot.notes() == null || lot.notes().isBlank() ? "" : lot.notes() + " ") + "A repair of its design: Architect refused it on the wall (" + a.why()
				+ "). Fix exactly that, and keep the rest as it is.";
			var withWhy = new VillageLayout.Lot(lot.id(), lot.type(), lot.x(), lot.z(), lot.sizeX(), lot.sizeZ(), lot.front(), lot.groundY(), lot.role(), notes,
				lot.landmark(), lot.placement(), lot.maxHeight(), lot.exact());
			req = dev.larattalabs.steward.gateway.LotBrief.build(settlement, withWhy, lot.maxHeight(), null, Pipeline.WALL_REPAIR_USD);
			var reserved = reservedCells().get(a.lotId());
			if (reserved != null) req = req.withReserved(reserved);
			var wallExt = wallLotExts().get(a.lotId());
			if (wallExt != null) req.ext().add(dev.larattalabs.steward.gateway.PPlan.WALL_LOT_EXT, wallExt);
			req = dev.larattalabs.steward.gateway.PPlan.inverted(req, invertedDesigns(), invertedLots().contains(a.lotId()));
			if (state.bibleId() != null) req = req.withBible(state.bibleId(), state.bibleVersion());
			req = req.versionOf(entry, null).withOpKey(a.key());
		} catch (RuntimeException x) {
			Steward.LOGGER.warn("repair {} of {} could not be put to Architect", a.key(), settlement.id(), x);
			later.add(new Pipeline.RepairEnded(a.key(), Pipeline.RepairEnd.REFUSED, 0, "it could not be put to Architect"));
			return;
		}
		// saved (its reservation, its key, its words) before it is asked, also when it is asked again on the watch
		if (!persist()) {
			say("Could not save the build, so the repair of the " + lot.role() + " is not asked for. Cancel it (/steward cancel " + state.settlementId() + ").");
			return;
		}
		int session = Session.current();
		repairAsking = true;
		ArchitectApi.get().designs().request(req).whenComplete((id, err) -> server.execute(() -> {
			repairAsking = false;
			if (!Session.is(session)) return;
			// this runner replaced meanwhile: the design is stopped, whoever owns the key now finds it by the key
			if (!alive()) { if (id != null && !Settlements.disabled()) ArchitectApi.get().designs().cancel(id); return; }
			// (WALL-RACE) the repair ended meanwhile (the build stopped, or another round or lot's repair is the build's now): its design is stopped, not left running
			if (Pipeline.answeredAfterItsRepair(state == null ? null : state.repair(), a.key())) {
				if (id != null) {
					Steward.LOGGER.info("repair {} of {}: design {} answered after the repair ended; stopped", a.key(), settlement.id(), id);
					ArchitectApi.get().designs().cancel(id);
				}
				return;
			}
			if (err != null) {
				// refused: nothing designed; lost, or its key bound to a request already made: found by its key on the watch (never a second design)
				if (ChangeRequests.definite(err) && !keyConflict(err) && !uncertain(err)) feed(new Pipeline.RepairEnded(a.key(), Pipeline.RepairEnd.REFUSED, 0, "Architect would not redesign it (" + said("the repair " + a.key(), err) + ")"));
				else Steward.LOGGER.warn("repair {} of {}: the answer was lost ({}); found by its key", a.key(), settlement.id(), message(err));
				return;
			}
			feed(new Pipeline.RepairAsked(a.key(), id));
			checkRepair();
		}));
	}

	/** The design a wall lot's repair is a version of: the lot's entry in the build's design group (its repair is the same entry's next version). */
	private @org.jspecify.annotations.Nullable String repairEntry(String lotId) {
		Group g = groupId == null ? null : ArchitectApi.get().designs().group(groupId).orElse(null);
		if (g == null) return null;
		return g.items().stream().filter(it -> it.itemKey().equals(lotId)).findFirst().flatMap(Group.Item::entryId).orElse(null);
	}

	/** Stops the repair's design; the outcome (stopped, or already final, with its cost) ends it. A stop that gets no answer is sent again on the watch. */
	private void stopRepair(Pipeline.StopRepair x) {
		repairStopSent = System.currentTimeMillis();
		if (x.designId() == null) { checkRepair(); return; }
		int session = Session.current();
		ArchitectApi.get().designs().cancelDesign(x.designId()).whenComplete((o, err) -> server.execute(() -> {
			if (!Session.is(session) || !alive()) return;
			// no answer: sent again on the watch (repairStopSent keeps its time)
			if (err != null) { Steward.LOGGER.warn("repair {} of {}: the stop got no answer ({}); asked again", x.key(), settlement.id(), message(err)); return; }
			switch (o.kind()) {
				case CANCELLED, ALREADY_FINAL -> o.design().ifPresentOrElse(d -> repairFinal(d, true, x.key()),
					() -> feed(new Pipeline.RepairEnded(x.key(), Pipeline.RepairEnd.FAILED, 0, "stopped")));
				case UNKNOWN -> feed(new Pipeline.RepairEnded(x.key(), Pipeline.RepairEnd.LOST, 0, "Architect does not know its design"));
			}
		}));
	}

	/**
	 * Where the repair stands, every second and whenever Architect says something of it: read by its design, else found by its key (empty: Architect never
	 * received it: asked again under the key, or, while a cancel waits, ended with nothing spent); final, it ends. A waiting stop that got no answer is sent
	 * again. Architect reports DONE only once the repaired version is installed, so a final read can be bound at once.
	 */
	void checkRepair() {
		if (state == null || !alive()) return;
		var r = state.repair();
		if (r == null) return;
		var designs = ArchitectApi.get().designs();
		if (r.designId() != null) {
			var d = designs.get(r.designId());
			if (d.isPresent()) {
				if (d.get().status().isFinal()) repairFinal(d.get(), false, r.key());
				else if (r.stopping() && repairStopSent > 0 && System.currentTimeMillis() - repairStopSent > 20_000) execute(new Pipeline.StopRepair(r.key(), r.designId()));
				return;
			}
		}
		if (repairLooking || repairAsking) return;
		repairLooking = true;
		int session = Session.current();
		designs.designByKey(settlement.owner(), r.key()).whenComplete((found, err) -> server.execute(() -> {
			repairLooking = false;
			if (!Session.is(session) || !alive() || state == null || state.repair() == null || !state.repair().key().equals(r.key())) return;
			// Architect not reachable: looked for again on the next watch
			if (err != null) return;
			if (found.isPresent()) {
				if (state.repair().designId() == null) feed(new Pipeline.RepairAsked(r.key(), found.get().id()));
				if (found.get().status().isFinal()) repairFinal(found.get(), false, r.key());
				return;
			}
			// never received: a waiting cancel ends it (nothing spent); else asked again under its key with its own words, while its key is still kept
			if (state.repair().stopping()) feed(new Pipeline.RepairEnded(r.key(), Pipeline.RepairEnd.REFUSED, 0, "Architect never received it"));
			else if (state.phase() == Pipeline.Phase.REPAIRING && !mayResend()) feed(new Pipeline.RepairEnded(r.key(), Pipeline.RepairEnd.LOST, 0,
				"Architect has no record of it, and the build is too old to ask again safely"));
			// (a repair saved without its words cannot be asked again the same: ended, its most counted)
			else if (state.phase() == Pipeline.Phase.REPAIRING && (r.why() == null || r.why().isBlank())) feed(new Pipeline.RepairEnded(r.key(), Pipeline.RepairEnd.LOST, 0,
				"Architect has no record of it, and it was saved without the refusal to ask again with"));
			else if (state.phase() == Pipeline.Phase.REPAIRING) {
				askRepair(new Pipeline.AskRepair(r.lotId(), r.key(), r.why()));
				drainLater();
			}
		}));
	}

	/**
	 * The repair's design is final: the pipeline ends it with its cost (DONE binds again; failed or stopped, the build ends or the cancel is done). A
	 * CANCELLED read ({@code confirmed} false: a lookup or DESIGN_DONE) is not the end: Architect marks it so before its work has stopped, so the cancel's
	 * own answer ({@link #stopRepair}) is asked for and ends it.
	 */
	private void repairFinal(dev.larattalabs.architect.api.Design d, boolean confirmed, String key) {
		var r = state == null ? null : state.repair();
		// (WALL-RACE) only the repair it was read for, with that repair's own design: never another lot's or round's, nor one whose design isn't known yet
		if (!Pipeline.ownsCompletion(r, key, d.id())) {
			if (r != null) Steward.LOGGER.info("repair {} of {}: design {} (read for {}) is not its own, dropped", r.key(), settlement.id(), d.id(), key);
			return;
		}
		if (d.status() == dev.larattalabs.architect.api.Design.Status.CANCELLED && !confirmed) {
			if (repairStopSent == 0 || System.currentTimeMillis() - repairStopSent > 20_000) execute(new Pipeline.StopRepair(r.key(), d.id()));
			return;
		}
		// its next version is the same entry, which the bind takes again: one that came back as another entry is not bound (it would bind the old one)
		String entry = repairEntry(r.lotId());
		boolean sameEntry = d.entryId().isPresent() && d.entryId().get().equals(entry);
		boolean done = d.status() == dev.larattalabs.architect.api.Design.Status.DONE && sameEntry;
		// (raw-sweep) the design's own error to the log; the steward's words in the event
		if (!done) Steward.LOGGER.info("{}: repair {} ended {} ({}): {}", settlement.id(), r.key(), d.status(), d.entryId().orElse("none"), d.error().orElse(""));
		String why = done ? null : d.status() == dev.larattalabs.architect.api.Design.Status.DONE ? "its repair came back as another design"
			: "budget".equals(d.error().orElse("")) ? "it ran past its budget" : "Architect didn't finish designing it";
		feed(new Pipeline.RepairEnded(r.key(), done ? Pipeline.RepairEnd.DONE : Pipeline.RepairEnd.FAILED, d.cost().usd(), why));
	}

	/** A design finished: this build's repair is looked at. */
	private void onRepairDone(dev.larattalabs.architect.api.Design d) {
		var r = state == null ? null : state.repair();
		// keyed by the repair it is for when queued: by the time it runs, that repair may have ended and another lot's begun
		if (r != null && d.id().equals(r.designId())) { String key = r.key(); server.execute(() -> repairFinal(d, false, key)); }
	}

	/** This session's repair requests and lookups in flight, and when the waiting stop was sent (0: none, or it got no answer). */
	private boolean repairAsking, repairLooking;
	private long repairStopSent;

	/**
	 * The mode a plan's region is realised in: the approved artifact's (Architect RS: checked in survival, it is built from the stockpile; else at once), so a
	 * restart or a stale retry realises in the mode the plan was approved in, never the world's mode of the moment.
	 */
	private static Mode realiseMode(String sha) {
		return artifactSurvival(sha) ? Mode.CONSTRUCTION : Mode.INSTANT;
	}

	/** Whether an approved artifact was checked in survival (built from materials). */
	private static boolean artifactSurvival(String sha) {
		var body = ArchitectAccess.api().regions().artifact(sha).map(dev.larattalabs.architect.api.PlanArtifact::body).orElse(null);
		return body != null && body.has("survival") && body.get("survival").isJsonPrimitive() && body.get("survival").getAsBoolean();
	}

	/** Whether this build is cancelling or has ended (nothing more is placed for it). */
	private boolean over() {
		return state == null || switch (state.phase()) { case CANCELLING, DONE, FAILED, CANCELLED -> true; default -> false; };
	}

	/** The plan as approved: the one Architect froze in the artifact (what the designs were made for), else the kept plan as this build builds it. */
	private @org.jspecify.annotations.Nullable JsonObject frozenPlan(String sha) {
		var body = ArchitectAccess.api().regions().artifact(sha).map(dev.larattalabs.architect.api.PlanArtifact::body).orElse(null);
		if (body != null && body.has("plan") && body.get("plan").isJsonObject()) return body.getAsJsonObject("plan");
		try {
			return builtPlan();
		} catch (RuntimeException e) {
			Steward.LOGGER.warn("the plan of {} could not be read again", settlement.id(), e);
			return null;
		}
	}

	/** The lot entries whose designs fit their approved lots, box, height and cost ({@link dev.larattalabs.steward.gateway.PPlan#lotProblem}); the others are said and left pads. */
	private Map<String, String> fitting(String sha, Map<String, String> lotEntries) {
		var body = ArchitectAccess.api().regions().artifact(sha).map(dev.larattalabs.architect.api.PlanArtifact::body).orElse(null);
		if (body == null || !body.has("lots") || !body.get("lots").isJsonArray()) return lotEntries;
		Map<String, JsonObject> lots = new java.util.HashMap<>();
		for (var l : body.getAsJsonArray("lots")) if (l.isJsonObject() && l.getAsJsonObject().has("id")) lots.put(l.getAsJsonObject().get("id").getAsString(), l.getAsJsonObject());
		var library = ArchitectAccess.api().library();
		Map<String, String> out = new LinkedHashMap<>();
		for (var e : lotEntries.entrySet()) {
			JsonObject lot = lots.get(e.getKey());
			String entryId = e.getValue().contains("@") ? e.getValue().substring(0, e.getValue().indexOf('@')) : e.getValue();
			var entry = lot == null ? java.util.Optional.<dev.larattalabs.architect.api.Library.Entry>empty() : library.get(entryId);
			if (entry.isEmpty()) { out.put(e.getKey(), e.getValue()); continue; }
			// the head's non-air count, the one Architect compares with the lot's cost (C37b; 0 = it couldn't read the template: Architect's to refuse)
			long cells = entry.get().cells();
			String problem = dev.larattalabs.steward.gateway.PPlan.lotProblem(lot, entry.get().size().x(), entry.get().size().y(), entry.get().size().z(), cells > 0 ? cells : -1);
			if (problem == null) out.put(e.getKey(), e.getValue());
			else say(e.getKey().replace('_', ' ') + " is left a pad: its design (" + problem + ") doesn't fit the plan as approved.");
		}
		return out;
	}

	/**
	 * The lot entries that keep the plan's required palette rules ("only copper", "no wood": VOC-c), by Architect's own measure ({@code checkPalette}):
	 * while a required rule fails, the lot that pushes it furthest out is left a pad and said, rather than the whole village refused at the realise.
	 */
	private Map<String, String> palette(String sha, Map<String, String> entries) {
		Map<String, String> out = new LinkedHashMap<>(entries);
		var regions = ArchitectAccess.api().regions();
		for (int i = 0; i < 24 && !out.isEmpty(); i++) {
			var pc = regions.checkPalette(sha, out);
			if (pc.isEmpty() || pc.get().ok()) break;
			var bad = pc.get().rules().stream().filter(r -> r.required() && !r.fits() && r.worstLot() != null && out.containsKey(r.worstLot())).findFirst();
			if (bad.isEmpty()) break;
			var r = bad.get();
			out.remove(r.worstLot());
			say(r.worstLot().replace('_', ' ') + " is left a pad: its design breaks the plan's rule on " + r.family() + String.format(java.util.Locale.ROOT,
				" (%.0f%% of the measured blocks, the rule asks %.0f%% to %.0f%%).", r.share() == null ? 0 : r.share() * 100, r.shareMin() * 100, r.shareMax() * 100));
		}
		return out;
	}

	/**
	 * The build's hold on its plan, let go (a region realised from it keeps it; a build that ended has no more use for it): the pin on its approved artifact;
	 * a flat village's plan never realised is discarded (Architect close-out A), so it doesn't wait for the sweep.
	 */
	private void releasePin() {
		if (settlement == null || vPlanId == null) return;
		String sha = dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId);
		try {
			if (sha != null) ArchitectApi.get().regions().unpinArtifact(sha, settlement.owner());
			else if (vRegionId == null) {
				String planId = vPlanId;
				ArchitectApi.get().regions().discardPlan(planId).whenComplete((done, err) -> {
					if (err != null) Steward.LOGGER.info("village plan {} of {} not discarded: {}", planId, settlement.id(), message(err));
					else if (Boolean.TRUE.equals(done)) Steward.LOGGER.info("discarded the village plan {} of {}'s ended build {}", planId, settlement.id(), buildId);
				});
			}
		} catch (UnsupportedOperationException | LinkageError e) {
			// an Architect without discardPlan: its sweep takes the plan
		} catch (RuntimeException e) {
			Steward.LOGGER.warn("could not let go of the plan {} for {}", vPlanId, settlement.id(), e);
		}
	}

	/**
	 * Pins an approved artifact under this build's realise key (Architect close-out A): the realise under the key ends the pin by itself, and a pin unused
	 * for 30 days is swept; on an older Architect, a plain pin.
	 */
	private void pin(String sha) {
		var regions = ArchitectApi.get().regions();
		try {
			regions.pinArtifact(sha, settlement.owner(), opKey("vrealise"));
		} catch (UnsupportedOperationException | LinkageError e) {
			regions.pinArtifact(sha, settlement.owner());
		}
	}

	/**
	 * At a world start, a resumed village build uses its plan again, so Architect's sweep (by last use, close-out A) never takes a plan a build still waits on
	 * (the player's approvals may take weeks): its artifact pinned again, a flat village's plan looked up by its key.
	 */
	private void touchPlan() {
		if (vPlanId == null || vRegionId != null || settlement == null) return;
		try {
			String sha = dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId);
			if (sha != null) pin(sha);
			else ArchitectAccess.api().regions().planByKey(settlement.owner(), opKey(growth() && growKey != null ? growKey : "vplan"));
			Steward.LOGGER.info("resume {}: plan {} used again (kept from Architect's sweep)", settlement.id(), vPlanId);
		} catch (RuntimeException | LinkageError e) {
			Steward.LOGGER.warn("could not use the plan {} of {} again", vPlanId, settlement.id(), e);
		}
	}

	/** Architect's answer to the village's realise: the region taken as this build's placement, or the build stops. */
	private void realised(@org.jspecify.annotations.Nullable String id, @org.jspecify.annotations.Nullable Throwable err, int session) {
		if (!Session.is(session)) return;
		queueInFlight = false;
		if (worldStopped(err)) return;
		// (batch-077 review P1-1) settlements disabled since it was asked: the answer is neither acted on nor taken up (a take-up removes and refunds
		// with no history saved); the region stands, found by its key or its build tag when the build resumes next session (or by that session's sweep)
		if (Settlements.disabled()) {
			if (err == null) keptForNextSession(id);
			return;
		}
		// the build ended (cancelled while this was asked, twice, or replaced): a village realised for it after all is taken up, not left without a history
		if (!alive() || state == null || switch (state.phase()) { case DONE, FAILED, CANCELLED -> true; default -> false; }) {
			if (err == null) takeUpOrphan(id);
			return;
		}
		if (err != null && growth()) {
			if ((uncertain(err) || keyConflict(err)) && findVillage()) return;
			// (QD, S-M5-9) the bill at put-down was past the approved bound: nothing was written or taken (refused before the revision or its key
			// is recorded), so it is quoted again and put to the player; their approval realises it under the same key
			if (billCapped(err) && moduleBuild() && state.phase() == Pipeline.Phase.PLACING) {
				Steward.LOGGER.info("module {} realise {} refused at put-down: {}", settlement.id(), vPlanId, message(err));
				// (QD-2) saved before the quote is asked for: a restart puts the next quote to the player too, never realises it on its own
				quoteCleared = null;
				// (pin-073 batch review, P2) the refusal not saved: a restart would find the build placing as approved and could realise it on its own,
				// so it is neither quoted again nor realised here; the build stops (nothing was written or taken), said
				// (FENCE-TESTS-4) and the unsaved refusal is rolled back, in the runner and the shared store: the build stops as saved
				if (!saveOrders(refusedAtPutDown(moduleOrders))) {
					say("Not placed: the materials needed went past " + dev.larattalabs.steward.module.ModuleBound.limitOf(moduleOrders.values())
						+ ", and that could not be saved, so I won't ask again or build it. Nothing was taken from the stockpile; ask for it again.");
					feed(new Pipeline.Cancel());
					return;
				}
				say("Not placed yet: the materials needed went past " + dev.larattalabs.steward.module.ModuleBound.limitOf(moduleOrders.values())
					+ ". Nothing was taken from the stockpile; I'll ask you again.");
				realiseVillage();
				return;
			}
			// another change of the town was accepted while the lots were designed: planned once more with the same lots, the designs kept by lot id
			String m = message(err);
			// cancelled meanwhile: a stale realise wrote nothing, so the cancel ends here
			if (state.phase() == Pipeline.Phase.CANCELLING) { feed(new Pipeline.CancelConfirmed()); return; }
			if ((m.contains("PLAN_STALE") || m.contains("parent_moved")) && !"vgrow3".equals(growKey) && growRegion != null) {
				say("The town changed while the designs were made: finding room for it again...");
				String stale = growKey;
				planGrowth(null, 0, growRegion, List.of(), stale);
				return;
			}
			// (slice A2) the world's mode changed while it was designed, or the town is being built from materials again: plain words
			if (m.contains("survival_unfinished")) say("Not placed: the town is being built from the stockpile again; it can grow once it's built.");
			else if (m.contains("plan it again") && (m.contains("in survival") || m.contains("in creative") || m.contains("not in survival")))
				say("Not placed: the world's mode changed while it was designed (the town grows only the way it was built). Start it again.");
			else say("Architect could not place it in the town: " + m);
			feed(new Pipeline.Cancel());
			return;
		}
		if (err != null) {
			// a lost answer, or the key asked with another body (the player who placed it changed): looked up by its key
			if ((uncertain(err) || keyConflict(err)) && findVillage()) return;
			// a district's realise re-checks the land under its plan (D3 4.4): planned again once, under a second key, as a stale artifact is
			if (replanDistrict(err)) return;
			// (QD-2, M5b) a composed town's realise refused at put-down (BILL_CAP, under a cap): nothing was written or taken; its orders are marked
			// refused and saved before it is quoted again, so the next quote is put to the player, also after a restart; unsaved, the build stops
			// (LIFT-S, Architect LCAP) its detail says which cap: a module lot's own (lot:<id>, named), one it can't read (UNNAMED: no region cap is sent), or a lot the realise doesn't
			// bind (unknown_lot:<id>: Steward's error, the build stops, never asked again)
			// (LIFT-S GPT review P2) sent from READY_TO_PLACE too: a town placed without the player's word realises from there
			if (billCapped(err) && composedModules() && (state.phase() == Pipeline.Phase.PLACING || state.phase() == Pipeline.Phase.READY_TO_PLACE)) {
				var refusal = dev.larattalabs.steward.module.ComposedModules.CapRefusal.of(capDetail(err));
				Steward.LOGGER.info("composed modules {} realise refused at put-down ({}): {}", settlement.id(), refusal, message(err));
				quoteCleared = null;
				composedCaps = null;
				var orders = moduleOrders;
				switch (ComposedSteps.refusedAtPutDown(refusal, orders, o -> moduleOrders = o, this::persist)) {
					case STOP_UNKNOWN_LOT -> {
						say(dev.larattalabs.steward.module.ComposedModules.putDownWords(refusal, orders));
						feed(new Pipeline.Cancel());
					}
					case STOP_UNSAVED -> {
						say("Not placed: the materials needed went past " + dev.larattalabs.steward.module.ModuleBound.limitOf(orders.values())
							+ ", and that could not be saved, so I won't ask again or build it. Nothing was taken from the stockpile; start it again.");
						feed(new Pipeline.Cancel());
					}
					case REASK -> {
						say(dev.larattalabs.steward.module.ComposedModules.putDownWords(refusal, orders));
						realiseVillage();
					}
				}
				return;
			}
			// (slice SD) a district's region from materials: the world's mode changed since it was planned, or the stockpile is gone, in plain words
			String plain = districtId == null ? null : materialsRefusal(message(err));
			if (plain != null) { say("Not placed: " + plain); feed(new Pipeline.Cancel()); return; }
			Steward.LOGGER.warn("{}: the village was not placed: {}", settlement.id(), message(err), err);
			say("Architect could not place the village: " + RefusalWords.of(err) + ".");
			feed(new Pipeline.Cancel());
			return;
		}
		adoptVillage(id);
	}

	/** Takes the realised region as this build's placement. */
	private void adoptVillage(String regionId) {
		if (growth()) { adoptGrowth(regionId); return; }
		vRegionId = regionId;
		// a plan's village: the region keeps its artifact now (Architect writes it before answering)
		releasePin();
		siteGroupId = dev.larattalabs.steward.gateway.VPlan.REGION_PREFIX + regionId;
		persist();
		if (state.phase() == Pipeline.Phase.CANCELLING) { cancelVillage(); return; }
		if (state.phase() == Pipeline.Phase.READY_TO_PLACE) feed(new Pipeline.BatchQueued(regionId));
		watchVillage();
	}

	/**
	 * Takes a growth's realise as this build's placement: the revision is the one realised from this build's plan (by its plan id, never by tagged sites,
	 * which it may not have yet); its rid names the history entry.
	 */
	private void adoptGrowth(String regionId) {
		// taken already (a late answer after the lookup found it): not twice
		if (regionId.equals(vRegionId)) return;
		var rv = ArchitectAccess.api().regions().get(regionId).flatMap(v -> Growths.byPlan(v, vPlanId));
		if (rv.isPresent()) {
			growRid = rv.get().rid();
			growPosition = rv.get().position();
		}
		vRegionId = regionId;
		siteGroupId = Growths.tag(regionId, growRid);
		persist();
		if (state.phase() == Pipeline.Phase.CANCELLING) { cancelVillage(); return; }
		if (state.phase() == Pipeline.Phase.READY_TO_PLACE) feed(new Pipeline.BatchQueued(regionId));
		watchVillage();
	}

	/** A growth realised under its keys: the town region, when the revision realised from this build's plan is in it. */
	private java.util.Optional<String> foundGrowth() {
		var regions = ArchitectAccess.api().regions();
		return regions.realiseByKey(settlement.owner(), opKey("vgrowrealise"))
			.or(() -> regions.realiseByKey(settlement.owner(), opKey("vgrowrealise3")))
			.or(() -> growRegion == null ? java.util.Optional.empty() : regions.get(growRegion))
			.filter(v -> v.state() != dev.larattalabs.architect.api.RegionState.REMOVED && vPlanId != null && Growths.byPlan(v, vPlanId).isPresent())
			.map(dev.larattalabs.architect.api.RegionView::id);
	}

	/**
	 * This build's village region (a restart came, or an answer was lost, before its id was saved): by its realise key, or (a village realised before keys)
	 * by its owner and the build's tag. A removed one (its tombstone) is not this build's placement.
	 */
	private boolean findVillage() {
		if (growth()) {
			var g = foundGrowth();
			if (g.isEmpty()) return false;
			say("Architect had it placed in the town: picking it up.");
			adoptGrowth(g.get());
			return true;
		}
		var regions = ArchitectAccess.api().regions();
		var found = regions.realiseByKey(settlement.owner(), opKey("vrealise"))
			// a plan's village realised from its re-checked artifact (plan v1, after a stale one) is under the second key
			.or(() -> regions.realiseByKey(settlement.owner(), opKey("vrealise2")))
			.or(() -> regions.list(settlement.owner()).stream().filter(v -> buildId.equals(extString(v.ext(), BatchPlanner.BUILD_EXT))).findFirst())
			.filter(v -> v.state() != dev.larattalabs.architect.api.RegionState.REMOVED);
		if (found.isEmpty()) return false;
		say("Architect had the village: picking it up.");
		adoptVillage(found.get().id());
		return true;
	}

	/**
	 * A region realised for a build that has ended: logged under its region first (a restart, or undo, finds it by that entry), then taken up whole as an
	 * undo would; when that stops, the entry stays and undo takes it up once it is clear.
	 */
	private void takeUpOrphan(String regionId) {
		// (batch-077 review P1-1) never while settlements are disabled: nothing of it could be logged, so it stands for the next session
		if (Settlements.disabled()) { keptForNextSession(regionId); return; }
		if (growth()) { takeUpOrphanGrowth(regionId); return; }
		Steward.LOGGER.warn("village region {} was realised for {}'s ended build {}: taking it up", regionId, settlement.id(), buildId);
		Settlements.store().get(settlement.id()).ifPresent(saved -> VillageSweep.takeUp(server, saved, regionId, this::say));
	}

	/** (batch-077 review P1-1) A region realised while settlements are disabled: left standing, logged and said; the next session resumes or sweeps it. */
	private void keptForNextSession(@org.jspecify.annotations.Nullable String regionId) {
		Steward.LOGGER.warn("village region {} of {} (build {}) was realised while settlements are disabled: left standing for the next session", regionId,
			claimedId, buildId);
		say("Architect finished placing part of " + (settlement == null ? "the build" : settlement.name()) + ", but settlements are disabled this session, so"
			+ " nothing is recorded or taken up: the build waits for the next session.");
	}

	/** (TEST) Architect's answer to this build's realise, as it arrives on the server thread ({@link #realised}). */
	void realisedForTest(@org.jspecify.annotations.Nullable String id, @org.jspecify.annotations.Nullable Throwable err) {
		realised(id, err, Session.current());
	}

	/** (TEST) This build's resume after a restart ({@link #resync}). */
	void resyncForTest() {
		resync();
	}

	/**
	 * A growth realised for a build that has ended: logged under its revision first (undo finds it by that entry), then taken up by its revision, never by
	 * removing the town; when that is refused or waits, the entry stays and undo takes it up later.
	 */
	private void takeUpOrphanGrowth(String regionId) {
		var regions = ArchitectApi.get().regions();
		var rv = regions.get(regionId).flatMap(v -> Growths.byPlan(v, vPlanId));
		if (rv.isEmpty()) {
			Steward.LOGGER.warn("growth of {} for ended build {}: no revision from plan {} in {}", settlement.id(), buildId, vPlanId, regionId);
			return;
		}
		var ref = new Growths.Ref(regionId, rv.get().rid());
		// (slice A2L review 2) a late answer for a growth already taken up and logged (a cancel finished first): nothing logged again
		if (Growths.completed(Settlements.store().get(settlement.id()).orElse(settlement), ref.tag())) return;
		// (slice A2) still being placed: its sites aren't all known yet; the growth sweep logs it once it has ended and takes it up (VillageSweep.growths)
		if (!Growths.ended(rv.get().state()) && !"UNDONE".equalsIgnoreCase(rv.get().state()) && !"STALE".equalsIgnoreCase(rv.get().state())
			&& !"UNDOING".equalsIgnoreCase(rv.get().state())) {
			Steward.LOGGER.warn("growth {} was realised for {}'s ended build {} and is still being placed: logged and taken up once it has ended", ref.tag(), settlement.id(), buildId);
			// the note is the obligation (durable): the sweep finds the growth by it, whatever its sites say, also when it ends with none
			var note = VillageSweep.orphanNote(ref.tag());
			if (!Settlements.log(settlement.id(), note).ok()) {
				Steward.LOGGER.warn("{}: the note for growth {} could not be saved; the sweep saves it again", settlement.id(), ref.tag());
				VillageSweep.retryNote(settlement.id(), note);
			}
			return;
		}
		Steward.LOGGER.warn("growth {} was realised for {}'s ended build {}: taking it up", ref.tag(), settlement.id(), buildId);
		siteGroupId = ref.tag();
		List<String> sites = rv.get().sites() == null ? List.of() : List.copyOf(rv.get().sites());
		if (!sites.isEmpty() && !logPlaced(sites, sites.size(), 0, "after its build ended")) { logFailed(); return; }
		var entry = growthEntry(ref.tag());
		var saved = Settlements.store().get(settlement.id()).orElse(settlement);
		// (slice A2) built from materials: watched, so the steward says when it is built (and undo can take it up)
		if (regions.get(regionId).map(Growths::fromMaterials).orElse(false)) MaterialRequests.watchBuild(settlement.id(), regionId);
		if (entry.isPresent()) Undo.growth(server, saved, entry.get(), ref, Map.of(), this::say, ok -> { });
		// nothing of it shows: taken up now; when that can't be done, the history names the revision so it is not lost
		else Growths.undo(server, settlement.owner(), ref, this::say, ok -> {
			if (Boolean.FALSE.equals(ok)) Settlements.log(settlement.id(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE,
				"An addition realised after its build ended could not be taken up: the town's change " + ref.tag() + " stands with nothing placed", List.of()));
		});
	}

	/** The history entry that placed this growth (its tag), if it is logged. */
	private java.util.Optional<Settlement.LogEntry> growthEntry(String tag) {
		return Settlements.store().get(settlement.id()).flatMap(s -> {
			for (int i = s.log().size() - 1; i >= 0; i--) {
				var e = s.log().get(i);
				if (e.kind() == Settlement.Kind.PROJECT_PLACED && tag.equals(e.siteGroup())) return java.util.Optional.of(e);
			}
			return java.util.Optional.empty();
		});
	}

	/** The region's sites as Steward records them: its streets and buildings, or (when it left nothing else) the ground tiles it wrote. */
	private List<String> regionSites(String regionId) {
		return VillageSweep.sitesOf(server, settlement.owner(), regionId);
	}

	/** When the realise now in flight was sent (epoch ms). */
	private long inFlightSince;
	private long lastSettleLook;
	/** How long an ended build's unanswered realise waits before Architect is asked for it by its key, and before it counts as lost. */
	static final long LOST_LOOK_MS = 3 * 60_000, LOST_GIVE_UP_MS = 15 * 60_000;

	/**
	 * An ended build whose realise was never answered (the review's "an answer that never comes"): after {@link #LOST_LOOK_MS} Architect is asked for it by
	 * its keys, and by the district and build tags on a region; one found is taken up as the orphan it is. None found by {@link #LOST_GIVE_UP_MS}: the
	 * request never reached Architect, so the build lets go (and a district it held is only reserved again). Server thread.
	 */
	void settleLostRealise(long now) {
		if (!queueInFlight || inFlightSince <= 0 || now - inFlightSince < LOST_LOOK_MS || now - lastSettleLook < 30_000 || settlement == null) return;
		lastSettleLook = now;
		try {
			var regions = ArchitectApi.get().regions();
			// a growth: only the revision realised from this build's plan (never a region found by the build tag: the town has its first build's)
			var found = growth() ? foundGrowth().flatMap(regions::get) : regions.realiseByKey(settlement.owner(), opKey("vrealise"))
				.or(() -> regions.realiseByKey(settlement.owner(), opKey("vrealise2")))
				.or(() -> regions.list(settlement.owner()).stream().filter(v -> buildId.equals(extString(v.ext(), BatchPlanner.BUILD_EXT))).findFirst())
				.filter(v -> v.state() != dev.larattalabs.architect.api.RegionState.REMOVED);
			if (found.isPresent()) {
				Steward.LOGGER.warn("{}: the realise of ended build {} was never answered; Architect has region {}: taking it up", settlement.id(), buildId, found.get().id());
				queueInFlight = false;
				takeUpOrphan(found.get().id());
				return;
			}
		} catch (RuntimeException | LinkageError e) {
			// can't tell: still in flight
			return;
		}
		if (now - inFlightSince >= LOST_GIVE_UP_MS) {
			Steward.LOGGER.warn("{}: the realise of ended build {} was never answered and Architect has nothing for it: let go", settlement.id(), buildId);
			queueInFlight = false;
			releaseDistrict();
		}
	}

	/** The card's lots of a district build (its plan's specs) as asked of Architect, for a re-plan; saved with the build. */
	private @org.jspecify.annotations.Nullable List<VillageLayout.LotSpec> districtSpecs;
	private boolean districtReplanned;

	/**
	 * A district's realise refused by D3's re-check (the land under its plan changed: {@code OVERLAP}, {@code OVERLAP_BUSY}, or the town gone or moved,
	 * {@code OTHER}): planned again once beside the town under the second key ({@code vplan2}), with the same lots and their designs, then realised under
	 * {@code vrealise2}. True when the re-plan started (the build goes on); false to end the build as before.
	 */
	private boolean replanDistrict(Throwable err) {
		if (districtId == null || districtReplanned || settlement == null || plan == null || growth()) return false;
		// a cancel, or the build's end, while the first realise was asked: no second region
		if (state == null || state.phase().terminal() || state.phase() == Pipeline.Phase.CANCELLING || !alive()) return false;
		// the card's lots: this session's, else (a restored build) read back from its plan's lots and the ones it left out
		if (districtSpecs == null) {
			List<VillageLayout.LotSpec> specs = new ArrayList<>();
			// (saved before builds kept them) a lot facing east or west lies across: its x span is its depth, so the spec's width is its z span
			for (VillageLayout.Lot l : plan.lots()) {
				boolean across = l.front() == VillageLayout.Front.EAST || l.front() == VillageLayout.Front.WEST;
				specs.add(new VillageLayout.LotSpec(l.id(), l.type(), across ? l.sizeZ() : l.sizeX(), across ? l.sizeX() : l.sizeZ(), l.role(), l.notes(), l.landmark(), l.placement()));
			}
			specs.addAll(plan.unplaced());
			districtSpecs = List.copyOf(specs);
		}
		Throwable e = err.getCause() != null ? err.getCause() : err;
		if (!(e instanceof dev.larattalabs.architect.api.ArchitectRefused rf)) return false;
		var why = rf.reason();
		if (why != dev.larattalabs.architect.api.Reason.OVERLAP && why != dev.larattalabs.architect.api.Reason.OVERLAP_BUSY && why != dev.larattalabs.architect.api.Reason.OTHER) {
			return false;
		}
		var now = Settlements.store().get(settlement.id()).orElse(settlement);
		var district = now.lands().district(districtId).orElse(null);
		if (district == null || district.square() || district.parent() == null || districtConflict() != null) return false;
		var regions = ArchitectApi.get().regions();
		var town = regions.get(district.parent());
		if (town.isEmpty() || town.get().state() != dev.larattalabs.architect.api.RegionState.PLACED) return false;
		districtReplanned = true;
		var b = town.get().claim();
		int[] request = districtRequest(Settlements.store().get(settlement.id()).orElse(settlement), district, new int[] {b.minX(), b.minZ(), b.maxX(), b.maxZ()});
		List<JsonObject> lots = new ArrayList<>();
		for (var x : dev.larattalabs.steward.gateway.VPlan.params(districtSpecs, settlement.id(), buildId).getAsJsonArray("lots")) lots.add(x.getAsJsonObject());
		Steward.LOGGER.info("{}: the land beside {} changed: {}", settlement.id(), district.name(), message(err));
		say("The land beside " + district.name() + " changed while its buildings were designed: laying it out again.");
		int session = Session.current();
		if (regionMaterials()) districtPlannedMaterials = true;
		regions.plan(Districts.besideRequest(level(), settlement, town.get().id(), request, district.id(), lots, opKey("vplan2"), buildId, district.kind(), district.follow()))
			.whenComplete((rp, perr) -> server.execute(() -> {
				if (!Session.is(session) || !alive() || worldStopped(perr)) return;
				// cancelled while the land was laid out again: nothing more is placed
				// (cancelVillage confirmed the cancel already: no realise was in flight and no region stood)
				if (state == null || state.phase().terminal() || state.phase() == Pipeline.Phase.CANCELLING) return;
				if (perr != null) {
					Steward.LOGGER.info("{}: the new plan of {} failed: {}", settlement.id(), district.name(), message(perr));
					say("Architect could not lay " + district.name() + " out again: " + Districts.besideRefusal(perr));
					feed(new Pipeline.Cancel());
					return;
				}
				vPlanId = rp.planId();
				plan = dev.larattalabs.steward.gateway.DPlan.toPlan(rp.lots(), districtSpecs);
				// the new plan saved with the build before anything of it is placed: a restart after this realises it, never the old one
				if (!persist()) {
					say("Could not save the build, so the new layout isn't placed. Cancel it (/steward cancel " + settlement.id() + ").");
					return;
				}
				realiseVillage();
			}));
		return true;
	}

	/** The claim a district plans in: a beside district's reservation and seam, or a quarter's strip and seam (DistrictRules). */
	private static int[] districtRequest(Settlement s, Settlement.District d, int[] region) {
		if (Settlement.District.BESIDE.equals(d.kind())) return dev.larattalabs.steward.model.DistrictRules.request(region, d.claim(), d.direction());
		// a quarter: its saved strip (held while built) and the way back into the town region, never one moved by a later Expand
		if (d.savedLand() != null && d.region() == null) return dev.larattalabs.steward.model.DistrictRules.quarterRequest(region, d.savedLand(), d.direction());
		var others = s.lands().districts().stream().filter(o -> !o.id().equals(d.id())).toList();
		var q = dev.larattalabs.steward.model.DistrictRules.quarter(s.claim(), region, others, List.of(), List.of(), d.direction(),
			Settlement.District.OUTPOST.equals(d.kind()) ? dev.larattalabs.steward.model.DistrictRules.OUTPOST_DEPTH : dev.larattalabs.steward.model.DistrictRules.ALONG_DEPTH, d.name());
		return q.ok() ? q.request() : dev.larattalabs.steward.model.DistrictRules.request(region, d.claim(), d.direction());
	}

	/** What a growth grows, in words: the district (slice DG) or the town. */
	private String growingName() {
		if (districtId == null || settlement == null) return "the town";
		return Settlements.store().get(settlement.id()).orElse(settlement).lands().district(districtId).map(Settlement.District::name).orElse("the district");
	}

	/**
	 * Why this build's district region may not be placed now (another district's land in its request's claim), or null; not a district build: null. A
	 * growth of a standing district (slice DG) is checked by {@link Districts#growthConflict} instead: its region stands, so its founding envelope says nothing.
	 */
	private @org.jspecify.annotations.Nullable String districtConflict() {
		if (districtId == null || settlement == null) return null;
		if (growth()) return Districts.growthConflict(Settlements.store().get(settlement.id()).orElse(settlement), districtId, growRegion);
		var now = Settlements.store().get(settlement.id()).orElse(settlement);
		var district = now.lands().district(districtId).orElse(null);
		if (district == null || district.square() || district.parent() == null) return null;
		var town = ArchitectApi.get().regions().get(district.parent());
		if (town.isEmpty()) return null;
		var b = town.get().claim();
		int[] request = districtRequest(Settlements.store().get(settlement.id()).orElse(settlement), district, new int[] {b.minX(), b.minZ(), b.maxX(), b.maxZ()});
		var envelope = dev.larattalabs.steward.model.Land.ofRect(district.claim().dimension(), request[0], request[1], request[2], request[3]);
		return now.lands().districts().stream().filter(o -> !o.id().equals(district.id()) && o.land().overlaps(envelope)).findFirst()
			.map(o -> district.name() + "'s ground now reaches " + o.name() + "'s land.").orElse(null);
	}

	/** A stage of the village's region Architect holds for the player's word (the land changed under it since the plan), or null. */
	private @org.jspecify.annotations.Nullable String heldStage;
	/** When the player's usage limit, which holds this build's design group, resets (epoch ms; 0 when not held). From the group's updates, so a restart reads it again. */
	private long heldUntil;

	/** What the settlement asks of the player while this build runs ({@link dev.larattalabs.steward.inbox.Attention}). */
	dev.larattalabs.steward.inbox.Attention attention(boolean updateAsks, String doing) {
		return dev.larattalabs.steward.inbox.Attention.of(state, heldUntil, heldStage, updateAsks, doing, System.currentTimeMillis());
	}
	/** The region waits already said (a held stage, a place to come nearer to), so each is said once. */
	private final Set<String> saidRegionWaits = new java.util.HashSet<>();

	/** Reads the village's state while it is placed; once placed (wholly or in part), its sites are logged under the region and the build ends. */
	void watchVillage() {
		// a growth cancelled while its realise was in flight, whose answer was lost: looked up by its keys and plan, and taken up (adopting it cancels it)
		if (growth() && vRegionId == null && queueInFlight && state != null && state.phase() == Pipeline.Phase.CANCELLING && alive()
			&& inFlightSince > 0 && System.currentTimeMillis() - inFlightSince >= LOST_LOOK_MS && System.currentTimeMillis() - lastSettleLook >= 30_000) {
			lastSettleLook = System.currentTimeMillis();
			var g = foundGrowth();
			if (g.isPresent()) { queueInFlight = false; adoptGrowth(g.get()); }
			// nothing by its keys and plan after the give-up time (the request never reached Architect, or the town is gone): nothing was placed
			else if (System.currentTimeMillis() - inFlightSince >= LOST_GIVE_UP_MS) {
				Steward.LOGGER.warn("{}: the cancelled growth's realise was never answered and Architect has nothing for it: let go", settlement.id());
				queueInFlight = false;
				feed(new Pipeline.CancelConfirmed());
			}
			return;
		}
		if (!village() || vRegionId == null || state == null || !alive()) return;
		// a growth cancelled while it was still being placed: asked again each second until it can be taken up
		if (growth() && state.phase() == Pipeline.Phase.CANCELLING && !growthTakingUp) { cancelGrowth(); return; }
		if (state.phase() != Pipeline.Phase.PLACING && state.phase() != Pipeline.Phase.READY_TO_PLACE) return;
		if (growth()) { watchGrowth(); return; }

		var v = ArchitectAccess.api().regions().get(vRegionId);
		if (v.isEmpty()) return;
		// what the region waits for that the player can act on (Architect's wait actions): a held stage, a place to come nearer to
		String held = null;
		for (var a : v.get().actions()) {
			switch (a.kind()) {
				case APPROVE_STAGE -> held = a.detail() != null ? a.detail() : "next";
				case MOVE_CLOSER -> {
					if (a.target() != null && saidRegionWaits.add("near " + a.target().getX() + "," + a.target().getZ()))
						say("The village waits for its ground to load: come nearer to " + a.target().getX() + ", " + a.target().getZ() + ".");
				}
				default -> {
				}
			}
		}
		heldStage = held;
		if (held != null && saidRegionWaits.add("stage " + held)) say("The land changed under the plan since it was checked, so Architect holds the " + held
			+ " stage. /steward approve " + settlement.id() + " builds it as planned; undo takes the village up.");
		var st = v.get().state();
		if (st != dev.larattalabs.architect.api.RegionState.PLACED && st != dev.larattalabs.architect.api.RegionState.PARTIAL
			&& st != dev.larattalabs.architect.api.RegionState.FAILED) return;
		long placed = v.get().lots().stream().filter(l -> "placed".equalsIgnoreCase(String.valueOf(l.state()))).count();
		long skipped = v.get().lots().size() - placed;
		boolean whole = st == dev.larattalabs.architect.api.RegionState.PLACED;
		String note = whole ? null : "the village is " + st.name().toLowerCase() + ": " + placed + " of " + Words.count(v.get().lots().size(), "lot", "lots") + " placed";
		if (note != null) say("The " + note.substring(4) + ".");
		// water Architect had to drain (H: it leaked or escaped), said by name
		for (String line : RegionReports.drained(v.get().ext())) say(line);
		// the region's sites (its street and buildings; its ground when it left nothing else): undo removes the region whole, ground, lanes, square and props too
		List<String> sites = regionSites(vRegionId);
		if (sites.isEmpty()) {
			// nothing of it is in the world: its record is dropped, so it does not count as something standing; the history says it failed
			ArchitectApi.get().regions().remove(vRegionId, new RemoveOptions(false, settlement.owner()));
			Settlements.log(settlement.id(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, "The village was not placed ("
				+ (note != null ? note : "nothing of it stands") + ")", List.of()));
		} else if (!logPlaced(sites, (int) placed, (int) skipped, note)) { logFailed(); return; }
		feed(new Pipeline.BatchDone((int) placed, (int) skipped, note == null ? null : note.substring(4)));
		say(String.format("Settlement spend: $%.2f of $%.0f.", state.spentUsd(), state.budgetUsd()));
		// built from materials (Architect RS): placed means its construction sites stand; it's built as the stockpile feeds it
		var build = v.get().build();
		if (build != null) {
			if (!build.done()) say(String.format("Its construction sites stand: it's built as the stockpile feeds it (%.0f%% so far). I'll say when it's done.", build.percent()));
			// (slice 8aS) the ground first (Architect 8a ST: staged terrain, free, at the world's rate): how long it takes
			if (!build.done()) sayTerrainTime(build);
			// watched even when it is already done (a stockpile fed in time: GM-c's drawbridge run): the watch says it fully built on its next read
			MaterialRequests.watchBuild(settlement.id(), vRegionId);
		}
	}

	/**
	 * A growth while it is placed: its revision (by rid) is read, not the town's state (which reads the town's own again once the revision ends); once
	 * the revision has ended, its own sites are logged under {@code revision:<region>:<rid>} and the build ends. One that placed nothing is taken up.
	 */
	private void watchGrowth() {
		var regions = ArchitectAccess.api().regions();
		var v = regions.get(vRegionId);
		if (v.isEmpty()) return;
		var rv = Growths.revision(v.get(), growRid).or(() -> Growths.byPlan(v.get(), vPlanId));
		if (rv.isEmpty()) return;
		String st = rv.get().state();
		if ("UNDONE".equalsIgnoreCase(st) || "STALE".equalsIgnoreCase(st)) {
			say("The town's change was taken up before it was logged: nothing of it stands.");
			feed(new Pipeline.BatchDone(0, plan.lots().size(), "taken up"));
			return;
		}
		// its own empty take-up, stopped part way (Architect leaves it UNDOING): the same call again
		if ("UNDOING".equalsIgnoreCase(st)) {
			var ref = new Growths.Ref(vRegionId, rv.get().rid());
			takeUpEmpty(ref, "nothing of it stands", plan.lots().size(), () -> feed(new Pipeline.BatchDone(0, plan.lots().size(), "nothing of it stands")));
			return;
		}
		if (!Growths.ended(st)) return;
		List<String> added = rv.get().lotsAdded() == null ? List.of() : rv.get().lotsAdded();
		long placed = v.get().lots().stream().filter(l -> added.contains(l.id()) && "placed".equalsIgnoreCase(String.valueOf(l.state()))).count();
		long skipped = Math.max(0, added.size() - placed);
		String note = "PLACED".equalsIgnoreCase(st) ? null : "the town's change is " + st.toLowerCase(java.util.Locale.ROOT) + ": " + placed + " of "
			+ Words.count(added.size(), "lot", "lots") + " placed";
		if (note != null) say("The " + note.substring(4) + ".");
		List<String> sites = rv.get().sites() == null ? List.of() : List.copyOf(rv.get().sites());
		var ref = new Growths.Ref(vRegionId, rv.get().rid());
		siteGroupId = ref.tag();
		if (sites.isEmpty()) {
			String why = note != null ? note : "nothing of it stands";
			takeUpEmpty(ref, why, (int) skipped, () -> feed(new Pipeline.BatchDone(0, (int) skipped, why)));
			return;
		} else if (!logPlaced(sites, (int) placed, (int) skipped, note)) { logFailed(); return; }
		feed(new Pipeline.BatchDone((int) placed, (int) skipped, note == null ? null : note.substring(4)));
		say(String.format("Settlement spend: $%.2f of $%.0f.", state.spentUsd(), state.budgetUsd()));
		watchMaterials(v.get());
	}

	/**
	 * (slice A2) A growth of a town built from materials: placed means its construction sites stand; what it still needs is said, and the town is watched
	 * until all of it is built again (the steward says so, once).
	 */
	private void watchMaterials(dev.larattalabs.architect.api.RegionView v) {
		var b = v.build();
		if (b == null) return;
		if (!b.built()) {
			// the town's outstanding bill (Architect sums its targets', not one revision's): said as what the stockpile still owes
			String owed = b.outstanding().isEmpty() ? "" : " (the stockpile still owes " + Stockpiles.items(b.outstanding().entrySet().stream().toList(), 6)
				+ " for what is being built)";
			say("The addition's construction sites stand and are built from the stockpile as it's fed" + owed + ". I'll say when it's done.");
			sayTerrainTime(b);
		}
		MaterialRequests.watchBuild(settlement.id(), v.id());
	}

	/**
	 * A growth with nothing of it in the world: its revision is taken up (never the town) before the build ends, so its record is never lost, asked again
	 * each second while it waits (or its take-up stopped part way). One that can't be taken up at all (a newer change stands on it, or Architect has no such
	 * revision) is noted in the history, naming it; the build ends ({@code end}) only once it is taken up or that note is saved.
	 */
	private void takeUpEmpty(Growths.Ref ref, String why, int skipped, Runnable end) {
		if (growthTakingUp) return;
		// (slice A2L review 1) completed already (a restart after its note, before the build's save): ended, the note not written twice
		if (Growths.completed(Settlements.store().get(settlement.id()).orElse(settlement), ref.tag())) { end.run(); return; }
		growthTakingUp = true;
		// (slice A2L) a growth cancelled while Architect still wrote it may hold targets its revision does not list yet: what they drew is refunded, said,
		// with the stockpile's state after it (review 7; since OVF a keyed refund never overflows, it can take the stock past capacity)
		var said = Undo.reportingOverCapacity(server, Settlements.store().get(settlement.id()).orElse(settlement), this::say);
		java.util.concurrent.atomic.AtomicReference<dev.larattalabs.architect.api.RemoveResult> answer = new java.util.concurrent.atomic.AtomicReference<>();
		Growths.undo(server, settlement.owner(), ref, said, ok -> {
			if (ok == null) return;
			growthTakingUp = false;
			if (!alive() || state == null || state.phase().terminal()) return;
			if (ok) {
				boolean refunded = answer.get() != null && !answer.get().refund().isEmpty();
				// (GPT diff review) the town's build watch dropped here too when only the growth kept it unbuilt
				MaterialRequests.forgetIfBuilt(ref.region());
				// the completion, tagged with the growth (Growths.completed), checked on a fresh read; the build ends only once it is saved
				var now = Settlements.store().get(settlement.id()).orElse(settlement);
				// (OVF-S3) TODO(T4c): owed from the answer's RemoveResult.owed() once the pin has it
				boolean done = Growths.completed(now, ref.tag()) || Settlements.log(settlement.id(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE,
					Growths.notPlacedNote(why, refunded, false), List.of(), ref.tag())).ok();
				// (GPT diff review) a known empty answer is said too, in a Supplied town
				if (Growths.reportsRefund(now, answer.get())) {
					said.accept(Growths.refundLine(answer.get().refund()));
					said.accept("Undone: the town's change is taken up, the land restored.");
				}
				if (done) end.run();
				return;
			}
			var d = Growths.decide(ArchitectApi.get().regions().get(ref.region()), ref.rid());
			if (d.step() != Growths.Step.NEWER && d.step() != Growths.Step.UNKNOWN) return;
			var saved = Settlements.log(settlement.id(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, "The addition was not placed ("
				+ why + "); the town's change " + ref.tag() + " stands with nothing of it placed: " + d.why(), List.of()));
			if (saved.ok()) end.run();
		}, answer::set, () -> {
			// (T4S-PRE) decided and held (said once by Growths.undo): not refused; the next pass reads the revision again (a re-ask joins it)
			growthTakingUp = false;
		});
	}

	/**
	 * Cancelling a growth once realised: its revision is logged first (a restart, or undo, finds it by that entry), then taken up by its revision, never by
	 * removing the town. Still placing, or refused: the entry stays, said, and undo takes it up later.
	 */
	/** A growth's take-up is in flight (cancel): not asked twice. */
	private boolean growthTakingUp;

	private void cancelGrowth() {
		var regions = ArchitectApi.get().regions();
		var v = regions.get(vRegionId);
		var rv = v.flatMap(x -> Growths.revision(x, growRid).or(() -> Growths.byPlan(x, vPlanId)));
		if (rv.isEmpty()) { vRegionId = null; feed(new Pipeline.CancelConfirmed()); return; }
		var ref = new Growths.Ref(vRegionId, rv.get().rid());
		siteGroupId = ref.tag();
		// (slice A2L) taken up and logged already (a restart after its completion, before the build's save): the cancel ends, nothing logged twice
		var fresh0 = Settlements.store().get(settlement.id()).orElse(settlement);
		if (Growths.completed(fresh0, ref.tag()) && ("UNDONE".equalsIgnoreCase(rv.get().state()) || "STALE".equalsIgnoreCase(rv.get().state()))) {
			vRegionId = null;
			feed(new Pipeline.CancelConfirmed());
			return;
		}
		// still being placed: waits (the cancel stays CANCELLING and saved; asked again on the next world start or tick), unless Architect's undo cancels
		// it as still writing (slice A2L, RU: a town from materials, its head revision)
		boolean writing = Growths.decide(v, rv.get().rid(), null).step() == Growths.Step.UNDO;
		if (!writing && !Growths.ended(rv.get().state()) && !"UNDONE".equalsIgnoreCase(rv.get().state()) && !"STALE".equalsIgnoreCase(rv.get().state())
			&& !"UNDOING".equalsIgnoreCase(rv.get().state())) {
			if (saidRegionWaits.add("cancel " + ref.tag())) say("The addition is still being placed: it is taken up once it is in.");
			return;
		}
		List<String> sites = rv.get().sites() == null ? List.of() : List.copyOf(rv.get().sites());
		var entry = growthEntry(ref.tag());
		// nothing of it shows and nothing logged: taken up before the cancel ends (asked again each second), or noted when it can't be
		if (entry.isEmpty() && sites.isEmpty()) {
			takeUpEmpty(ref, "cancelled", 0, () -> { vRegionId = null; feed(new Pipeline.CancelConfirmed()); });
			return;
		}
		if (entry.isEmpty()) {
			if (!logPlaced(sites, sites.size(), 0, "being cancelled")) { logFailed(); return; }
			entry = growthEntry(ref.tag());
		}
		// taken up and logged already (a crash after the removal's entry, before the build's save): the cancel ends, nothing logged twice
		if (entry.isPresent() && Undo.retired(Settlements.store().get(settlement.id()).orElse(settlement), entry.get())
			&& "UNDONE".equalsIgnoreCase(rv.get().state())) { vRegionId = null; feed(new Pipeline.CancelConfirmed()); return; }
		growthTakingUp = true;
		java.util.function.Consumer<Boolean> after = ok -> {
			if (ok == null) return;
			growthTakingUp = false;
			if (!alive()) return;
			if (ok) {
				// (slice A2L review 1) the checkpoint stays until the completion is saved: a failed save leaves the cancel CANCELLING, tried again
				if (!Growths.completed(Settlements.store().get(settlement.id()).orElse(settlement), ref.tag())) return;
				vRegionId = null;
				feed(new Pipeline.CancelConfirmed());
				return;
			}
			say("What the addition placed stays; /steward undo " + settlement.id() + " takes it up once it is clear.");
			feed(new Pipeline.BatchDone(sites.size(), 0));
		};
		var saved = Settlements.store().get(settlement.id()).orElse(settlement);
		Undo.growth(server, saved, entry.get(), ref, Map.of(), Undo.reportingOverCapacity(server, saved, this::say), after, () -> {
			// (T4S-PRE) decided and held (said by Growths.undo): the cancel stays CANCELLING and ends once the revision reads UNDONE
			growthTakingUp = false;
			if (alive()) cancelHeld = true;
		});
	}

	/**
	 * Cancelling a village: one not realised yet ends at once. One realised is logged under its region first (a restart, or undo, finds it by that entry),
	 * then taken up whole as an undo would (what it placed goes, the land restored); when that stops, the entry stays, undo takes it up once it is clear,
	 * and the cancel ends.
	 */
	private void cancelVillage() {
		if (vRegionId == null) {
			if (!queueInFlight) later.add(new Pipeline.CancelConfirmed());
			return;
		}
		if (growth()) { cancelGrowth(); return; }
		String id = vRegionId;
		var regions = ArchitectApi.get().regions();
		var v = regions.get(id);
		int placed = v.map(x -> (int) x.lots().stream().filter(l -> "placed".equalsIgnoreCase(String.valueOf(l.state()))).count()).orElse(0);
		int skipped = v.map(x -> x.lots().size()).orElse(0) - placed;
		var entry = regionEntry(id);
		if (entry.isEmpty()) {
			List<String> sites = regionSites(id);
			if (!sites.isEmpty()) {
				if (!logPlaced(sites, placed, skipped, "being cancelled")) { logFailed(); return; }
				entry = regionEntry(id);
			}
		}
		if (entry.isEmpty()) {
			// nothing of it shows in the world: its record is removed (a tombstone, or no region, is gone already)
			Undo.asking(server, () -> regions.remove(id, new RemoveOptions(false, settlement.owner()))).whenComplete((r, err) -> server.execute(() -> {
				if (!alive() || worldStopped(err)) return;
				// (T4S-PRE) decided and held: it finishes by itself; the cancel waits for REMOVED (never ends on an absent record or a guess)
				if (siteBusy(err).decided()) {
					cancelHeld = true;
					Steward.LOGGER.info("{}: the cancel's take-up of {} is decided and held: {}", settlement.id(), id, message(err));
					if (saidRegionWaits.add("cancel held " + id)) say(RemovalStops.underWay(siteBusy(err)));
					return;
				}
				var after = regions.get(id);
				boolean gone = takenUp(err == null && r.removed(), after.map(x -> x.state()).orElse(null), after.isPresent(), Undo.healthy(server));
				// (raw-text) Architect's error or blockers are logged, never said: its typed stop cause says it in the steward's words
				if (!gone) {
					Steward.LOGGER.info("{}: the cancel's take-up of {} stopped: {}", settlement.id(), id, err != null ? message(err) : String.join(", ", r.blockers()));
					say(cancelStopped(err != null, err != null ? null : RemovalStops.cause(id), settlement.id()));
					return;
				}
				vRegionId = null;
				feed(new Pipeline.CancelConfirmed());
			}));
			return;
		}
		var saved = Settlements.store().get(settlement.id()).orElse(settlement);
		Undo.region(server, saved, entry.get(), id, Map.of(), Undo.reportingOverCapacity(server, saved, this::say), ok -> {
			if (ok == null || !alive()) return;
			if (ok) {
				vRegionId = null;
				feed(new Pipeline.CancelConfirmed());
				return;
			}
			say("What the village placed stays; /steward undo " + settlement.id() + " takes it up once it is clear.");
			feed(new Pipeline.BatchDone(placed, skipped));
		}, false, () -> {
			// (T4S-PRE) decided and held (said by Undo.region): the cancel stays CANCELLING and ends once Architect's removal finishes
			if (alive()) cancelHeld = true;
		});
	}

	/**
	 * (raw-text) What a cancel says when the village's record could not be taken up: {@code error} (refused, its message logged) or blocked, said by its
	 * typed cause ({@link RemovalStops#causeWords}, the generic words for OTHER or none) and when to cancel again ({@link RemovalStops#again}). Pure.
	 */
	static String cancelStopped(boolean error, dev.larattalabs.architect.api.@org.jspecify.annotations.Nullable RemovalStopCause cause, String settlementId) {
		String command = "/steward cancel " + settlementId;
		if (error) return "The village could not be taken up just now. Run " + command + " again.";
		String said = RemovalStops.causeWords(cause);
		return "The village could not be taken up yet (" + (said != null ? said : RemovalStops.IN_THE_WAY) + "). " + RemovalStops.again(cause, command);
	}

	/** The history entry that placed this region (its newest), whichever entry is the newest undoable one. */
	private java.util.Optional<Settlement.LogEntry> regionEntry(String regionId) {
		return Settlements.store().get(settlement.id()).flatMap(s -> VillageSweep.entryOf(s, regionId));
	}

	/** A queued item of this build waits for a temporary blocker (Architect fires it when the reason changes; it waits up to 10 minutes). */
	private void onItemWaiting(dev.larattalabs.architect.api.ItemEvent e) {
		// a batch build's by its batch; a region's by the build tag on its lots, or by the region its tiles and roads name (Architect close-out A; 8b's
		// typed ItemEvent.region)
		String region = dev.larattalabs.steward.gateway.Typed.region(e);
		boolean ours = batchId != null && batchId.equals(e.batchId()) || buildId != null && buildId.equals(extString(e.ext(), BatchPlanner.BUILD_EXT))
			|| vRegionId != null && vRegionId.equals(region);
		if (!ours || e.reason().isEmpty()) return;
		// (8bS) the wait read typed (Architect 8b's ItemEvent.status; the home of waitsFor): Architect's say is never parsed or shown
		ItemWait w = ItemWait.of(dev.larattalabs.steward.gateway.Typed.status(e), e.ext());
		String plan = PlanStore.get(settlement.id()).map(PlanStore.Entry::plan).orElse(null);
		String part = MaterialRequests.partWords(plan, dev.larattalabs.steward.gateway.Typed.part(e), settlement.name());
		// (QD, BC-c) a part whose whole charge (a gate mechanism) would pass the approved bound: said in the steward's words, not Architect's
		// (pin-073 batch review) cancel while this growth's build runs; else undo only when it would take this growth's own operation
		if (w != null && w.capped()) {
			int rid = w.rid() > 0 ? w.rid() : dev.larattalabs.steward.gateway.Typed.rid(e);
			String capped = capStageWait(w, part, settlement.id(), cancelTakesUp() ? MaterialRequests.TakeUp.CANCEL : MaterialRequests.takeUp(settlement, region, rid));
			if (saidWaits.add(capped)) say(capped);
			return;
		}
		// a stage or terrain wait (built from materials: a part waits for the parts it stands on, or for its land): said once, what it waits for named
		// in the steward's words (a hidden part by its host)
		// the waiting item in words: a lot's role, else its part (typed), else the street or its key
		String role = extString(e.ext(), "steward_mc:role");
		String what = role != null ? role : part != null ? part : BatchPlanner.isStreet(e.itemKey()) ? "street" : e.itemKey().replace('_', ' ');
		// (slice 8aS, Architect 8a ST) a part waiting for the ground under it, staged terrain built over time: in the steward's words, once per wait
		var terrain = TerrainBuild.terrainWait(e.ext());
		if (terrain != null) {
			String line = TerrainBuild.waitLine(e.ext(), what);
			if (line != null && saidWaits.add(TerrainBuild.waitKey(terrain))) say(line);
			return;
		}
		String stage = stageWait(w, what, w == null ? null : waitsOnName(w));
		if (stage != null) {
			if (saidWaits.add(stage)) say(stage);
			return;
		}
		// (8bS) a whole charge the stockpile lacks (materials), or a held item (fill_blocked, txn_held, approval): in the steward's words, once
		String other = otherWait(w, what);
		if (other != null) {
			if (saidWaits.add(other)) say(other);
			return;
		}
		String in = e.message().replaceFirst("(?s).*in the box: ", "").replaceFirst("\\s*\\(.*", "").strip();
		switch (e.reason().get()) {
			case PLAYER_IN_BOX -> say("The " + what + " waits: someone is standing on its lot. Step off it and it goes up.");
			case OCCUPIED -> say("The " + what + " waits: " + (in.isEmpty() ? "something is in its lot" : in + " is in its lot") + ". Move it out and it goes up"
				+ " (Architect waits up to 10 minutes).");
			case NOT_LOADED -> say("The " + what + " waits for its ground to load: come nearer to it.");
			default -> {
			}
		}
	}

	/** What a stage wait waits for, in the steward's words (a site's: its part, its host when hidden, its role), else null. */
	private @org.jspecify.annotations.Nullable String waitsOnName(ItemWait w) {
		if (w.waitsOn() == null || !dev.larattalabs.architect.api.BuildStatus.STAGE.equals(w.reason())) return null;
		try {
			return ArchitectApi.get().sites(server).get(w.waitsOn()).map(v -> MaterialRequests.targetName(settlement, v)).orElse(null);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** (slice 8aS) How long a build's staged terrain takes, said when there is some left and Architect builds it over time ({@link TerrainBuild#FEATURE}). */
	private void sayTerrainTime(dev.larattalabs.architect.api.RegionBuild b) {
		boolean feature;
		try {
			feature = ArchitectApi.get().features().contains(TerrainBuild.FEATURE);
		} catch (RuntimeException | LinkageError e) {
			feature = false;
		}
		String line = TerrainBuild.timeLine(b, feature);
		if (line != null) say(line);
	}

	/** Stage waits said this session (each sentence once: several items of a part wait alike). */
	private final Set<String> saidWaits = new java.util.HashSet<>();

	/**
	 * (8bS) A queued item's wait, typed: its reason ({@code BuildStatus} reasons), what it waits on (a target or part id), the units over a cap (by item id),
	 * the cap's scope and the revision. From Architect 8b's {@code ItemEvent.status}; for an event without one, from the structured fields of ext
	 * {@code architect_mc:waitsFor} ({@code kind}, {@code target}, {@code part}, {@code missing}, {@code cap}), never its {@code say}.
	 */
	record ItemWait(@org.jspecify.annotations.Nullable String reason, @org.jspecify.annotations.Nullable String waitsOn, List<Map.Entry<String, Integer>> over,
		int moreUnits, @org.jspecify.annotations.Nullable String capScope, int rid) {
		boolean capped() {
			return dev.larattalabs.architect.api.BuildStatus.BILL_CAP.equals(reason);
		}

		/** From a typed status (needs by registry id) or the ext's structured fields; null when the item names no wait. */
		static @org.jspecify.annotations.Nullable ItemWait of(dev.larattalabs.architect.api.@org.jspecify.annotations.Nullable BuildStatus st,
			com.google.gson.@org.jspecify.annotations.Nullable JsonObject ext) {
			if (st != null) {
				if (!dev.larattalabs.architect.api.BuildStatus.WAITING.equals(st.state()) || st.reason() == null) return null;
				List<Map.Entry<String, Integer>> over = new ArrayList<>();
				st.needs().forEach((item, n) -> {
					if (n != null && n > 0) over.add(Map.entry(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(), n));
				});
				return new ItemWait(st.reason(), st.waitsOn(), over, st.moreUnits(), st.capScope(), st.revision());
			}
			return ofExt(ext);
		}

		/** From ext {@code architect_mc:waitsFor}'s structured fields alone (an event from before Architect 8b). Pure. */
		static @org.jspecify.annotations.Nullable ItemWait ofExt(com.google.gson.@org.jspecify.annotations.Nullable JsonObject ext) {
			if (ext == null || !(ext.get("architect_mc:waitsFor") instanceof JsonObject w)) return null;
			String kind = w.get("kind") instanceof com.google.gson.JsonPrimitive k ? k.getAsString() : null;
			List<Map.Entry<String, Integer>> over = new ArrayList<>();
			if (w.get("missing") instanceof JsonObject m) for (var x : m.entrySet()) {
				if (x.getValue() instanceof com.google.gson.JsonPrimitive n && n.isNumber() && n.getAsInt() > 0) over.add(Map.entry(x.getKey(), n.getAsInt()));
			}
			String cap = w.get("cap") instanceof com.google.gson.JsonPrimitive c ? c.getAsString() : null;
			String target = w.get("target") instanceof com.google.gson.JsonPrimitive t ? t.getAsString() : null;
			String part = w.get("part") instanceof com.google.gson.JsonPrimitive t ? t.getAsString() : null;
			int rid = dev.larattalabs.steward.gateway.Typed.rid(ext);
			if (dev.larattalabs.architect.api.SiteWait.BILL_CAP.equals(kind)) return new ItemWait(kind, target, over, 0, cap, rid);
			if (dev.larattalabs.architect.api.BuildStatus.TERRAIN.equals(kind)) return new ItemWait(kind, part, List.of(), 0, null, rid);
			// a whole charge the stockpile lacks: Architect 8b's materials
			if ("bill".equals(kind)) return new ItemWait(dev.larattalabs.architect.api.BuildStatus.MATERIALS, target, over, 0, null, rid);
			// any other waitsFor is a stage wait: the target it waits for
			return new ItemWait(dev.larattalabs.architect.api.BuildStatus.STAGE, target, List.of(), 0, null, rid);
		}
	}

	/**
	 * (8bS) A stage or terrain wait in the steward's words: "The street waits for the town wall to be built." ({@code waitsOn}: what it waits for in words,
	 * else "an earlier part"), "The market hall waits for its land to be shaped."; else null. Never Architect's own sentence. Pure.
	 */
	static @org.jspecify.annotations.Nullable String stageWait(@org.jspecify.annotations.Nullable ItemWait w, String what,
		@org.jspecify.annotations.Nullable String waitsOn) {
		if (w == null || w.reason() == null) return null;
		return switch (w.reason()) {
			case dev.larattalabs.architect.api.BuildStatus.STAGE -> "The " + what + " waits for " + (waitsOn == null || waitsOn.isBlank() ? "an earlier part"
				: "the " + waitsOn) + " to be built.";
			case dev.larattalabs.architect.api.BuildStatus.TERRAIN -> "The " + what + " waits for its land to be shaped.";
			default -> null;
		};
	}

	/**
	 * (8bS) A queued item's other typed waits in the steward's words: a whole charge the stockpile lacks ({@code materials}, its items by id: "The
	 * gatehouse waits for materials: 3 observer. They go in the stockpile."), its containers to be emptied, an unfinished change, the player's approval;
	 * else null (a stage, terrain or cap wait is said by its own words, a box's wait by Architect's typed reason). Pure.
	 */
	static @org.jspecify.annotations.Nullable String otherWait(@org.jspecify.annotations.Nullable ItemWait w, String what) {
		if (w == null || w.reason() == null) return null;
		return switch (w.reason()) {
			case dev.larattalabs.architect.api.BuildStatus.MATERIALS -> MaterialRequests.waitWords(what, dev.larattalabs.steward.module.ModuleBound.overWords(w.over(),
				// (batch-080 merge) WORDS-079's signature: a pure item wait reads no stockpile, so no full-pile claim (Pile.UNREAD; the id is only for that line)
				w.moreUnits()), MaterialRequests.Pile.UNREAD, "");
			case dev.larattalabs.architect.api.BuildStatus.FILL_BLOCKED, dev.larattalabs.architect.api.BuildStatus.TXN_HELD,
				dev.larattalabs.architect.api.BuildStatus.APPROVAL -> "The " + what + " is " + dev.larattalabs.steward.view.SettlementView.statusWords(
					dev.larattalabs.architect.api.BuildStatus.WAITING, w.reason())[1] + ".";
			default -> null;
		};
	}

	/**
	 * (QD, Architect BC-c v0.73.0; 8bS typed) A stage wait held by the realise's {@code maxBill} ({@code bill_cap}: a gate mechanism's whole charge would
	 * pass the approved bound; the over-cap units by item) in the steward's words, as a site's {@code bill_cap} wait is ({@link MaterialRequests#overCapWords});
	 * else null. Its subject is the item's part in words ({@code part}, typed: never cut out of Architect's say), else "build". Pure.
	 */
	static @org.jspecify.annotations.Nullable String capStageWait(@org.jspecify.annotations.Nullable ItemWait w, @org.jspecify.annotations.Nullable String part,
		String settlementId, MaterialRequests.TakeUp how) {
		if (w == null || !w.capped()) return null;
		String what = part == null || part.isBlank() ? "build" : Character.toLowerCase(part.charAt(0)) + part.substring(1);
		// (LIFT-S, Architect LCAP-Q1) held by its lot's own cap (capScope "lot:<id>"): its limit, not what the player approved for the town
		if (MaterialRequests.capLot(w.capScope()) != null) return MaterialRequests.lotOverCapWords(what, settlementId, w.over(), w.moreUnits(), how,
			MaterialRequests.capBoth(w.capScope()));
		return MaterialRequests.overCapWords(what, settlementId, w.over(), w.moreUnits(), how);
	}

	private void onBatchDone(BatchView b) {
		if (batchId == null || !batchId.equals(b.id())) return;
		// buildings only: the street is a road, reported on its own when it fails
		long placed = b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.PLACED && !BatchPlanner.isStreet(i.itemKey())).count();
		long failed = b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.FAILED && !BatchPlanner.isStreet(i.itemKey())).count();
		b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.FAILED).forEach(i -> {
			Steward.LOGGER.info("batch {} of {}: {} not placed ({}): {}", b.id(), settlement.id(), i.itemKey(), i.reason().map(Enum::name).orElse("?"), i.message());
			say(itemNotPlacedLine(i.itemKey(), i.reason().orElse(null)));
		});
		// every site of the build, street included, so an undo removes all of it
		if (siteGroupId == null) siteGroupId = b.group();
		List<String> placedIds = b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.PLACED).flatMap(i -> i.siteId().stream()).toList();
		if (!logPlaced(placedIds, (int) placed, (int) failed)) {
			logFailed();
			return;
		}
		batchId = null;
		boolean cancelled = state.phase() == Pipeline.Phase.CANCELLING;
		feed(new Pipeline.BatchDone((int) placed, (int) failed));
		say(String.format("Settlement spend: $%.2f of $%.0f.", state.spentUsd(), state.budgetUsd()));
		// a cancelled build from materials: what it started building would go on taking the stockpile's materials, so it is taken up (what it used comes
		// back), as an undo would; what stands finished stays with the rest
		// only this build's own sites: the newest undoable entry must be exactly what this batch placed (else nothing is taken up, and the player is told)
		if (cancelled && !placedIds.isEmpty() && unfinished(server, placedIds) > 0) {
			Settlements.store().get(settlement.id()).ifPresent(saved -> {
				var last = saved.lastUndoable();
				if (last.isPresent() && new java.util.HashSet<>(last.get().siteIds()).equals(new java.util.HashSet<>(placedIds))) {
					// frozen first (Architect 0.22 Sites.pause): they take nothing more from the stockpile while the removal waits (a player standing in one)
					var sites = ArchitectApi.get().sites(server);
					for (String id : placedIds) {
						try {
							sites.pause(id, true);
						} catch (RuntimeException e) {
							// built already, or being removed: nothing to freeze
						}
					}
					say("The cancelled build's construction sites are taken up; what they used goes back to the stockpile.");
					Undo.lastProject(server, saved, this::say);
				} else say(nothingToCancel(server, saved));
			});
		}
	}

	/**
	 * What a build from materials needs in all, in words: each placed lot's full bill (its template, foundation and approach; the template's when Architect
	 * could not plan the rest) and each road's. Null when nothing is known.
	 */
	static @org.jspecify.annotations.Nullable String billOf(dev.larattalabs.architect.api.Batch batch, Map<String, LotFit> fits, dev.larattalabs.architect.api.Sites sites) {
		Map<net.minecraft.world.item.Item, Integer> total = new HashMap<>();
		boolean exact = true;
		for (var item : batch.items()) {
			if (item.road() != null) {
				try {
					sites.checkRoad(item.road()).bom().forEach((k, v) -> total.merge(k, v, Integer::sum));
				} catch (RuntimeException e) {
					exact = false;
				}
				continue;
			}
			LotFit f = fits.get(item.itemKey());
			if (f == null) continue;
			var v = f.verdict();
			var bom = v.bomFull() != null && !v.bomFull().isEmpty() ? v.bomFull() : v.bom();
			if (!v.bomExact()) exact = false;
			bom.forEach((k, n) -> total.merge(k, n, Integer::sum));
		}
		if (total.isEmpty()) return null;
		int units = total.values().stream().mapToInt(Integer::intValue).sum();
		List<Map.Entry<net.minecraft.world.item.Item, Integer>> list = new java.util.ArrayList<>(total.entrySet());
		list.sort((a, b) -> b.getValue() - a.getValue());
		return "From materials it needs " + (exact ? "" : "about ") + Words.count(units, "item", "items") + " of " + Words.count(total.size(), "kind", "kinds") + ": " + Stockpiles.items(list, 6) + ".";
	}

	/**
	 * The pieces an addition's street needs past the ends of the street as it stands ({@code street}), along the plan's line: none when the plan is on another
	 * line or axis. (slice NS2) Either axis: a north-south plan's spans run along z. Pure.
	 */
	static List<int[]> extensions(dev.larattalabs.steward.model.DistrictRules.@org.jspecify.annotations.Nullable Street street, VillageLayout.Plan plan) {
		List<int[]> spans = new java.util.ArrayList<>();
		if (street == null || street.northSouth() != plan.northSouth() || street.z() != plan.streetZ()) return spans;
		if (plan.streetX0() < street.x0()) spans.add(new int[] {plan.streetX0(), street.x0() - 1});
		if (plan.streetX1() > street.x1()) spans.add(new int[] {street.x1() + 1, plan.streetX1()});
		return spans;
	}

	/**
	 * The cells a stockpile crate may take: off this build's lots and street (a margin of 1 round a lot, 3 round the street's line), and off the town's
	 * street as it stands (a crate there would block what is built, and nobody but its owner can move it).
	 */
	static java.util.function.BiPredicate<Integer, Integer> clearOf(VillageLayout.Plan plan, List<dev.larattalabs.architect.api.SiteView> standing, Settlement s) {
		var street = Districts.streetEitherAxis(standing, s, null);
		return (x, z) -> {
			for (VillageLayout.Lot l : plan.lots()) if (x >= l.x() - 1 && x <= l.maxX() + 1 && z >= l.z() - 1 && z <= l.maxZ() + 1) return false;
			int[] b = plan.spanBox(plan.streetX0() - 3, plan.streetX1() + 3, 3);
			if (x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]) return false;
			return street == null || !street.near(x, z, 3);
		};
	}

	/** "Nothing to cancel", and when buildings are still being built from the stockpile, how to take them up (an undo refunds what they used). */
	static String nothingToCancel(MinecraftServer server, @org.jspecify.annotations.Nullable Settlement s) {
		if (s == null || unfinishedConstruction(server, s) == 0) return "Nothing to cancel.";
		return "Nothing to cancel: the placed buildings are being built from the stockpile. /steward undo " + s.id() + " takes them up and gives back what they used"
			+ " (an addition to the town once it's built).";
	}

	/** How many of these sites are not built yet. */
	static int unfinished(MinecraftServer server, List<String> siteIds) {
		var sites = ArchitectApi.get().sites(server);
		int n = 0;
		for (String id : siteIds) if (sites.get(id).filter(v -> !"BUILT".equals(v.state().name())).isPresent()) n++;
		return n;
	}

	/** How many of the settlement's construction sites (buildings and roads) are not built yet. */
	static int unfinishedConstruction(MinecraftServer server, Settlement s) {
		int n = 0;
		for (var v : ArchitectApi.get().sites(server).list(s.owner())) if (!"BUILT".equals(v.state().name())) n++;
		return n;
	}

	private void say(String text) {
		Steward.LOGGER.info("settlement: {}", text);
		// (S-7c-4) everything the build tells the player, Architect's reasons included, without a hidden room's name
		if (settlement != null) {
			String told = Secrets.ofSettlement(settlement.id()).mask(text);
			text = told.isBlank() ? "Architect's reason concerns a part of the plan kept private." : told;
		}
		if (!Session.is(session) || server == null) return;
		if (settlement != null) StewardVoice.say(server, settlement.id(), text);
		ServerPlayer p = server.getPlayerList().getPlayer(playerId);
		if (p != null) p.sendSystemMessage(Component.literal(text));
		else if (unread.size() < 50) unread.add(text);
	}
}
