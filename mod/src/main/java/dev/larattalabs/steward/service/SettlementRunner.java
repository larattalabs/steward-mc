package dev.larattalabs.steward.service;

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
		SiteEvents.BIBLE_DONE.register(job -> List.copyOf(ACTIVE.values()).forEach(r -> r.onBibleDone(job)));
		SiteEvents.GROUP_UPDATED.register(g -> List.copyOf(ACTIVE.values()).forEach(r -> r.onGroup(g)));
		SiteEvents.GROUP_DONE.register(g -> List.copyOf(ACTIVE.values()).forEach(r -> r.onGroup(g)));
		SiteEvents.BATCH_DONE.register(b -> List.copyOf(ACTIVE.values()).forEach(r -> r.onBatchDone(b)));
		SiteEvents.DESIGN_DONE.register(d -> List.copyOf(ACTIVE.values()).forEach(r -> r.onRepairDone(d)));
		// an item held up by something in its way (an animal, a player, ground not loaded): the steward says so, or it looks like nothing happens
		SiteEvents.ITEM_WAITING.register(e -> List.copyOf(ACTIVE.values()).forEach(r -> r.onItemWaiting(e)));
		ServerLifecycleEvents.SERVER_STARTING.register(SettlementRunner::restoreAll);
		// queued, so it runs after every SERVER_STARTED listener: Architect loads its sites there and fires its catch-up
		// on the first tick, after every SERVER_STARTED listener (Architect loads its sites and queue and fires its catch-up there); server.execute from the
		// server thread would run inline, inside this listener
		ServerLifecycleEvents.SERVER_STARTED.register(server -> resyncPending = true);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(server -> {
			if (!resyncPending) return;
			resyncPending = false;
			for (SettlementRunner r : List.copyOf(ACTIVE.values())) {
				try {
					r.resync();
				} catch (RuntimeException e) {
					Steward.LOGGER.error("could not resume the build of {}", r.claimedId, e);
				}
			}
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
			for (SettlementRunner r : List.copyOf(ACTIVE.values())) {
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
			resyncPending = false;
			builds = new BuildStore();
			buildsFile = null;
		});
	}

	private static void restoreAll(MinecraftServer server) {
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
		return buildsFile != null;
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
			case MASSINGS -> "Waiting for you: /steward approve " + id + " (all " + lastAwaiting.size() + " massings), or /steward redirect " + id + " <lot> <what to change> (lots: "
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
				if (err != null) say("Architect would not go on with the " + stage + " stage: " + message(err));
				else if (!r.done()) say("Architect would not go on with the " + stage + " stage: " + r.message());
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
				if (moduleBuild()) approveHeld();
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

	public String cancel() {
		if (state == null || state.phase().terminal()) return nothingToCancel(server, settlement);
		boolean second = state.phase() == Pipeline.Phase.CANCELLING;
		// a placement still rolling back: a second cancel would end the build before what it placed is recorded (no undo could reach it, and from
		// materials it would go on using the stockpile), so it waits for the rollback
		if (second && batchId != null) return "Still stopping: what the build placed is being recorded first, so it can be undone. It ends on its own.";
		// a growth realised or in flight: the same, its revision is recorded and taken up first (never dropped with the build's save)
		if (second && growth() && (vRegionId != null || queueInFlight)) return "Still stopping: the town's change is being recorded and taken up first. It ends on its own.";
		// (slice A2) a growth placed from materials can't be stopped part way (nothing cancels a revision's construction targets): it goes on, is logged
		// once put down, and undo takes it up once it is built
		if (!second && growth() && vRegionId != null && growRegion != null && ArchitectApi.get().regions().get(growRegion).map(Growths::fromMaterials).orElse(false))
			return "The addition is being placed from materials and can't be stopped part way: it's built as the stockpile feeds it, and /steward undo "
				+ settlement.id() + " takes it up once it's built.";
		feed(new Pipeline.Cancel());
		if (second) return "Stopped waiting for Architect.";
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
		boolean ended = state.phase().terminal();
		if (ended) builds.remove(settlement.id());
		else builds.put(new BuildStore.Saved(settlement.id(), buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId,
			List.copyOf(landmarkIds), List.copyOf(decided), lastAwaiting, addition, districtId, newStreet, keyed, vPlanId, vRegionId, districtSpecs, growRegion,
			growth() ? growRid : null, growth() ? growPosition : null, growKey, moduleBindings, moduleOrders));
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

	/** An approved plan's irrigated lots' reserved cells (Architect IRR), from its artifact; none for any other build. */
	/** The approved artifact's wall lots' {@code architect:wallLot} exts (Architect K3), for their design requests; none without an artifact. */
	private Map<String, JsonObject> wallLotExts() {
		String sha = dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId);
		if (sha == null) return Map.of();
		var body = ArchitectApi.get().regions().artifact(sha).map(dev.larattalabs.architect.api.PlanArtifact::body).orElse(null);
		if (body == null || !body.has("lots") || !body.get("lots").isJsonArray()) return Map.of();
		return dev.larattalabs.steward.gateway.PPlan.wallLots(body.getAsJsonArray("lots"));
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
		return Session.is(session) && claimedId != null && ACTIVE.get(claimedId) == this;
	}

	/** After a restart: gather what Architect kept, let {@link ResyncRules} decide, and carry it out. Bible and group results also arrive as Architect's catch-up events. */
	private void resync() {
		var api = ArchitectApi.get();
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
	 * A region town's growth (docs/GROWTH.md): the town region, the revision's rid and position as planned, and the key that planned it ({@code vgrow},
	 * {@code vgrow2}, {@code vgrow3}). Saved with the build; null for any other build.
	 */
	private @org.jspecify.annotations.Nullable String growRegion, growKey;
	private int growRid, growPosition;
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

	/** Whether this build places module lots (slice M3): its lots come from the catalog, never a design. */
	private boolean moduleBuild() {
		return pendingModule != null || !moduleOrders.isEmpty();
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
		if (attempt + 1 >= RECOVER_TRIES) { interrupted(what + ": " + message(err)); return; }
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
						say("The cancel is waiting for Architect (" + (err == null ? "no answer" : message(err)) + "); the build stays saved. Cancel again later.");
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
		// logged already (a restart came between the log and dropping the checkpoint): not twice
		java.util.Set<String> ids = new java.util.HashSet<>(siteIds);
		if (saved.get().log().stream().anyMatch(e -> e.kind() == Settlement.Kind.PROJECT_PLACED && new java.util.HashSet<>(e.siteIds()).equals(ids))) {
			// logged before a restart: the district's link may still be missing (a restart before it was laid is also put right when the world starts)
			if (districtId != null && saved.get().lands().district(districtId).map(Settlement.District::square).orElse(false)) Districts.link(server, settlement.id(), districtId, this::say, true);
			return true;
		}
		var sites = ArchitectApi.get().sites(server);
		List<Settlement.SiteChange> changes = siteIds.stream().map(id -> {
			var v = sites.get(id);
			// a village lot's site carries Architect's lot id only: its role is the plan's
			String lot = v.map(x -> extString(x.ext(), "steward_mc:role")).orElse(null);
			if (lot == null) {
				String lotId = v.map(x -> extString(x.ext(), "architect_mc:lot")).orElse(null);
				if (lotId != null && plan != null) lot = plan.lots().stream().filter(l -> l.id().equals(lotId)).map(VillageLayout.Lot::role).findFirst().orElse(lotId);
			}
			return new Settlement.SiteChange(id, lot, 0, v.map(dev.larattalabs.architect.api.SiteView::version).orElse(1));
		}).toList();
		var district = districtId == null ? null : saved.get().lands().district(districtId).orElse(null);
		String where = district == null ? "" : " in " + district.name();
		var entry = Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.PROJECT_PLACED, "Placed " + buildings + " buildings"
			+ where + (notPlaced > 0 ? " (" + notPlaced + " not placed)" : "") + (note != null ? " (" + note + ")" : ""), changes, siteGroupId,
			notPlaced > 0 || note != null ? Settlement.Outcome.PARTIAL : Settlement.Outcome.DONE);
		// a district's own region: the district names it, owns what it took outside the town and stands, saved with the entry (docs/DISTRICTS.md section 4)
		if (district != null && !district.square() && vRegionId != null) {
			var land = ArchitectApi.get().regions().get(vRegionId).flatMap(v -> dev.larattalabs.steward.gateway.DistrictLand.outside(v.ext(), district.claim().dimension()));
			var res = Settlements.placeDistrict(settlement.id(), district.id(), vRegionId, land.orElse(null), entry);
			if (!res.ok()) Steward.LOGGER.warn("placing district {} of {}: {}", district.id(), settlement.id(), res.error());
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
			if (err != null) { abandon("Card failed: " + err.getMessage()); return; }
			if (!r.ok()) { abandon("Card failed: " + r.error()); return; }
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
		if (!r.ok()) { abandon("Card job unreadable: " + r.error()); return; }
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
	 * (docs/MODULES.md 4.2, slice M3) Places an accepted module offer: one module lot, the catalog's program run at $0, as an addition on growth (A, A2,
	 * or B then A). Never a design, never Steward's street pieces: a town that can't grow that way ends the build before anything is spent.
	 */
	public void startModule(Settlement s, dev.larattalabs.steward.model.ConceptCard.Building lot, dev.larattalabs.steward.module.ModuleOrder order) {
		this.pendingModule = order;
		startAddition(s, lot, 0, null);
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
			if (err != null) { abandon("Survey failed: " + err.getMessage()); return; }
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
			ProgramPlanner.Result program = ProgramPlanner.lots(r.card(), buildings, maxLandmarks);
			if (!program.fromCard()) say("This card has no building program (it was described before programs existed), so this is a generic village. Describe it again to get buildings of its own.");
			if (!program.leftOut().isEmpty()) say("Left out at " + buildings + " buildings: " + String.join(", ", program.leftOut()) + ".");
			List<VillageLayout.LotSpec> specs = moduleBuild() && pendingModule != null ? moduleSpecs(r.card().program().get(0)) : program.specs();
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
			java.util.function.Supplier<VillageLayout.Plan> own = () -> !newStreet ? VillageLayout.plan(claim, grid, specs, rules, streetZ)
				: line != null ? VillageLayout.plan(claim, grid, specs, rules, line)
				: VillageLayout.plan(claim, grid, specs, rules, district == null && dev.larattalabs.steward.gateway.VPlan.northSouthStreet(settlement.id()));
			boolean worldSurvival = dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false);
			boolean overworld = level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
			// a district beside a region town (docs/DISTRICTS.md section 9): its first building plans the district's own region beside the town
			if (district != null && !district.square()) {
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
					var v = town.get();
					boolean inFlight = v.revisions() != null && v.revisions().stream().anyMatch(x -> !Growths.ended(x.state())
						&& !"UNDONE".equalsIgnoreCase(x.state()) && !"STALE".equalsIgnoreCase(x.state()));
					// a town being changed waits, whatever it is built from (never Steward's street pieces beside a change in flight)
					if ((v.busy() != null && !v.busy().isBlank()) || inFlight) {
						abandon("The town is being changed right now; ask again once it has settled.");
						return;
					}
					// (slice A2) the town grows only in the mode it was built in (Architect plans in the world's mode, realises in the town's), and a town
					// built from materials only once all of it is built: else nothing is spent
					boolean regionMaterials = worldSurvival && server.getDefaultGameType() != net.minecraft.world.level.GameType.CREATIVE;
					String no = Growths.modeRefusal(now.name(), Growths.fromMaterials(v), regionMaterials, v.build() != null && v.build().built(),
						v.build() == null ? 0 : v.build().percent());
					if (no != null) { abandon(no); return; }
					// built from materials: its targets draw from the settlement's stockpile, made again if it went
					if (Growths.fromMaterials(v)) {
						var sp = Stockpiles.ensure(server, now, this::say);
						if (sp == null) {
							abandon("A town built from materials grows from its stockpile, and none could be made by the founding stone. Clear a spot beside it and start again.");
							return;
						}
						// the growth keeps off the crates and the ring round them (a protected area, which Architect's growth keeps clear of), as planComposed
						String held = Stockpiles.protectCrates(server, Settlements.store().get(now.id()).orElse(now), sp, true);
						if (held != null) { abandon("The stockpile's ground could not be kept clear of the growth (" + held + "). Start again."); return; }
					}
					planGrowth(r, budgetUsd, v.id(), specs, null);
					return;
				}
				// a street town, built free: taken on as one region first, then grown (docs/GROWTH.md, slice B); anything that can't be keeps today's path
				else if (ArchitectApi.get().features().contains(Adoption.FEATURE) && existing.difficulty() == dev.larattalabs.steward.model.Difficulty.PATRON
					&& !worldSurvival && overworld && !dev.larattalabs.steward.gateway.VPlan.isLegacy(existing.id())) {
					var read = Adoption.read(server, existing);
					var inv = read.inventory();
					// a street that can't be taken on (bent): today's street pieces, and the player is told why
					if (inv.isEmpty() && read.why() != null) {
						Steward.LOGGER.info("adopt {}: not taken on: {}", existing.id(), read.why());
						say("The town isn't taken on as one region: " + read.why() + ". It grows along its street as before.");
					}
					if (inv.isPresent()) {
						java.util.function.Supplier<VillageLayout.Plan> legacyPlan = own;
						adoptTown(r, budgetUsd, specs, inv.get(), () -> {
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
				abandon("A working module is placed only by growing the town through Architect, and this town can't grow that way now (a district, a bent or "
					+ "Supplied street town, or an Architect without growth). Nothing was spent.");
				return;
			}
			if (existing != null && dev.larattalabs.steward.gateway.VPlan.applies(existing.id(), existing.difficulty(), worldSurvival, overworld, addition, districtId,
				r.card().site().terrain(), stands, lo, hi)) {
				// the settlement's composed plan, when it has one (plan v1); else Architect's flat village from the card
				if (composed(existing)) planComposed(existing, r, buildings, budgetUsd, district, claim, specs, own);
				else planVillage(r, buildings, budgetUsd, district, claim, specs, own);
				return;
			}
			// (Architect RS) a Supplied settlement's composed plan, built from the stockpile; else Steward's own layout as before
			// Architect builds a region from materials only with the toggle on and the world's default mode not creative
			boolean regionSurvival = worldSurvival && server.getDefaultGameType() != net.minecraft.world.level.GameType.CREATIVE;
			if (existing != null && composed(existing) && dev.larattalabs.steward.gateway.VPlan.appliesSupplied(existing.id(), existing.difficulty(), regionSurvival,
				Composer.survivalAvailable(), overworld, addition, districtId, r.card().site().terrain(), stands, lo, hi)) {
				supplied = true;
				planComposed(existing, r, buildings, budgetUsd, district, claim, specs, own);
				return;
			}
			plan = own.get();
			continueWithPlan(r, budgetUsd, district);
		}
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

	private void adoptTown(CardResult r, double budgetUsd, List<VillageLayout.LotSpec> specs, Adoption.Inventory inv, Runnable legacy) {
		var saved = Settlements.store().get(settlement.id()).orElse(null);
		if (saved == null) { legacy.run(); return; }
		if (!Adoption.reconcile(server, saved, true)) { abandon("The settlement's history could not be saved; try again."); return; }
		saved = Settlements.store().get(settlement.id()).orElse(saved);
		// an adoption that stands but whose town could not be read (or one just closed as standing): nothing more is asked now
		if (Adoption.standing(saved).isPresent() || Adoption.openIntent(saved).isPresent()) {
			abandon("The town is being changed right now; ask again once it has settled.");
			return;
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
					Steward.LOGGER.info("adopt {}: region {}", settlement.id(), v.id());
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
					if (!Settlements.log(settlement.id(), Adoption.released(intent.siteGroup(), "Not taken on as one region: " + why, System.currentTimeMillis())).ok()) {
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
		List<VillageLayout.LotSpec> lots = stale != null && districtSpecs != null ? districtSpecs : Growths.rename(specs, buildId);
		districtSpecs = lots;
		growRegion = region;
		// (slice M3) the module lot's order under its region lot id, saved with the build from its plan on
		if (pendingModule != null && stale == null) {
			var ids = lots.stream().map(VillageLayout.LotSpec::id).toList();
			moduleOrders = Map.of(ids.get(0), pendingModule);
			pendingModule = null;
		}
		if (stale == null) say("The town grows: Architect finds room on its streets...");
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
		JsonObject p = new JsonObject();
		p.add("grow", grow);
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", settlement.id());
		ext.addProperty(BatchPlanner.BUILD_EXT, buildId);
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
					if (full) Proposals.full(settlement.id(), null);
					growthNotPlanned(r, full ? "No room left in the town for it: its streets are full. A district could take it."
						: "Architect could not find room for it in the town: " + m);
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
				if (err != null) { abandon("Architect could not lay out the village: " + message(err)); return; }
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
		if (dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false)) {
			abandon(district.name() + " is a district beside the town, which isn't built from materials yet.");
			return;
		}
		var regions = ArchitectApi.get().regions();
		var town = district.parent() == null ? java.util.Optional.<dev.larattalabs.architect.api.RegionView>empty() : regions.get(district.parent());
		if (town.isEmpty() || town.get().state() == dev.larattalabs.architect.api.RegionState.REMOVED || town.get().state() == dev.larattalabs.architect.api.RegionState.REMOVING) {
			abandon("The town " + district.name() + " was reserved beside is gone: undo the reservation and found the district again.");
			return;
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
		regions.plan(req).whenComplete((rp, err) -> server.execute(() -> {
			if (!Session.is(session) || !alive()) return;
			if (worldStopped(err)) return;
			if (err != null) {
				abandon("Architect could not lay out " + district.name() + ": " + Districts.besideRefusal(null, message(err)));
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
		var entry = PlanStore.get(settlement.id()).orElseThrow();
		java.util.Set<String> waiting = new java.util.HashSet<>();
		for (var p : entry.pending()) waiting.add(p.part());
		return dev.larattalabs.steward.gateway.PPlan.forBuild(com.google.gson.JsonParser.parseString(entry.plan()).getAsJsonObject(), waiting, settlement.id(), buildId);
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
		List<String> stuck = new ArrayList<>();
		for (JsonObject part : c.parts()) {
			String cls = part.has("class") ? part.get("class").getAsString() : "";
			boolean required = part.has("required") && part.get("required").getAsBoolean();
			String id = part.has("part") ? part.get("part").getAsString() : "?";
			if (required && !cls.equals("fits") && !cls.equals("adjusted")) stuck.add((secrets.hides(id) ? "one part" : id.replace('_', ' ')) + " (" + cls
				+ (part.has("reasons") && part.get("reasons").isJsonArray() && !part.getAsJsonArray("reasons").isEmpty()
					&& part.getAsJsonArray("reasons").get(0).isJsonObject() && part.getAsJsonArray("reasons").get(0).getAsJsonObject().has("code")
					? " " + part.getAsJsonArray("reasons").get(0).getAsJsonObject().get("code").getAsString() : "") + ")");
		}
		if (!stuck.isEmpty()) return "the plan's " + String.join(", ", stuck) + " can't be built here"
			// a tunnel past the volume pass's caps: the rock under it couldn't all be read
			+ (stuck.stream().anyMatch(x -> x.contains("VOLUME")) ? " (Architect couldn't read all the rock a tunnel goes through: stand nearer it and start again)" : "");
		if (c.artifactSha() == null) return "Architect's check came back " + c.overall();
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
	 * the artifact ({@link #realiseVillage}). A plan that can't be built here stops the build before anything is spent; one Architect can't check, or whose
	 * street runs north-south, falls back to the flat village.
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
		JsonObject built = builtPlan();
		int session = Session.current();
		// a plan Architect can't check, or without lots: the flat village for a Patron town; a Supplied one stops before anything is spent (its survey was taken
		// before its stockpile and that ground's protection, so Steward's own layout over it could pay for designs that then don't fit), and is started again
		Runnable otherwise = () -> {
			if (supplied) abandon("The plan couldn't be used from materials this time: start the build again.");
			else planVillage(r, buildings, budgetUsd, district, claim, specs, own);
		};
		Composer.check(server, checked, built, playerId).whenComplete((c, err) -> server.execute(() -> {
			if (!Session.is(session) || !alive()) return;
			if (worldStopped(err)) return;
			if (err != null) {
				say("Architect could not check the plan (" + message(err) + ")" + (supplied ? "." : ": the village is laid out as usual."));
				otherwise.run();
				return;
			}
			try {
				String no = unbuildable(c, Secrets.of(built, null));
				if (no != null) { abandon("The plan can't be built as it stands: " + no + ". Describe the settlement again for a new plan."); return; }
				// Architect builds a region from materials only in a survival world (its toggle on, the world's default mode not creative): its check says
				// whether it ran in survival; otherwise the town would be refused at the realise, so Steward's own layout builds it from materials
				// the check's mode is this build's: built from materials for a Supplied build, at once for a Patron one; the world's mode changed while it was
				// surveyed: nothing is spent, and a new Start plans in the mode the world is in now
				if (c.survival() != supplied) {
					abandon("The world's mode changed while the land was surveyed (" + (c.survival() ? "it builds from materials now" : "it no longer builds from materials")
						+ "): start the build again.");
					return;
				}
				var art = ArchitectApi.get().regions().artifact(c.artifactSha());
				var lots = art.flatMap(a -> java.util.Optional.ofNullable(a.body())).filter(b -> b.has("lots") && b.get("lots").isJsonArray()).map(b -> b.getAsJsonArray("lots"));
				if (lots.isEmpty() || lots.get().isEmpty()) { say("The plan's check has no lots to build on" + (supplied ? "." : ": the village is laid out as usual.")); otherwise.run(); return; }
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
				pin(c.artifactSha());
				vPlanId = dev.larattalabs.steward.gateway.PPlan.ARTIFACT_PREFIX + c.artifactSha();
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
			say("Layout: " + plan.lots().size() + " lots on the street" + (plan.unplaced().isEmpty() ? "" : " (" + plan.unplaced().size() + " did not fit)"));
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
					.whenComplete((v, e) -> { if (e != null) say("Resume failed: " + e.getMessage()); });
				noteMassings(resumeGroup);
				for (Group.Item it : resumeGroup.items()) if (it.status() == dev.larattalabs.architect.api.Design.Status.DONE || it.stage().isPresent()) decided.add(decisionKey(it.itemKey()));
				return;
			}
			// an accepted proposal records itself now (acceptance, spend), or does not start
			if (onStart != null && !onStart.getAsBoolean()) { abandon("Could not save the settlement, so nothing is built."); return; }
			// (slice M3) a module lot is never designed: its entry is the catalog's variant, bound before the realise (docs/MODULES.md 4.2)
			if (moduleBuild()) {
				var bible = existing != null && existing.bible() != null && existing.bible().styleVersion() == existing.styleVersion() ? existing.bible() : null;
				state = new State(Pipeline.Phase.READY_TO_PLACE, settlement.id(), r.card(), bible == null ? null : bible.id(), bible == null ? null : bible.version(), null,
					Map.of(), 0, 0, 0, false, null, null);
				persist();
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
					.flatMap(v -> dev.larattalabs.steward.gateway.DistrictLand.inside(v.ext(), dim)).ifPresent(off::add);
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
		// the street of where the build goes: the district's own, or the town's (the rule founding and links use too)
		var street = Districts.streetOf(all, s, district);
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
				.flatMap(v -> dev.larattalabs.steward.gateway.DistrictLand.inside(v.ext(), dim)).ifPresent(off::add);
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
		return m.contains("connection lost") || m.contains("not connected") || m.contains("timed out") || m.startsWith("timeout") || m.contains("sidecar link lost");
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
			say(layers.size() + " massings are shown as blue ghosts on their lots (hide them: /steward hide " + settlement.id() + "):");
			lines.forEach(l -> say("  " + l));
		}
		return "Showing " + layers.size() + " massings on their lots.";
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
		var api = ArchitectApi.get();
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
					if (err != null) { feed(new Pipeline.BibleDone(false, null, 0, 0, err.getMessage())); return; }
					// cancelled while the request was on its way (or this runner belongs to a closed world): stop the job now
					if (!alive() || state.phase().terminal()) { if (!job.finished()) api.bibles().cancel(job.id()); return; }
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
				GroupPlanner.Built built = GroupPlanner.build(settlement, plan, o, reservedCells(), wallLotExts());
				landmarkIds = built.request().items().stream().filter(i -> i.role() == GroupRequest.Role.LANDMARK).map(GroupRequest.Item::itemKey).collect(Collectors.toSet());
				say("Designing " + built.request().items().size() + " buildings (massings first)...");
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
						say("Group failed: " + err.getMessage());
						// nothing reached Architect: the cancel finishes at once
						feed(new Pipeline.Cancel());
						return;
					}
					if (!alive() || state.phase().terminal()) { api.designs().cancelGroup(id); return; }
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
					say("Architect did not take that decision: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()));
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
						feed(new Pipeline.BudgetRaiseFailed(e.previousBudgetUsd(), message(err), false));
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
					else if (err != null) say("Could not reach Architect to stop the design group (" + message(err) + "). It is cancelled when Architect answers; cancel again to stop waiting.");
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
			if (!fit.ok()) say("Fit " + lot.id() + " (" + entry + ") refused: " + fit.verdict().refusals().stream().map(r -> r.reason() + " " + r.message()).collect(Collectors.joining("; ")));
		}
		// the street: all of it for a new one; for an addition, the pieces its lots reach past the street's ends (the street as it stands now)
		var now = Settlements.store().get(settlement.id()).orElse(settlement);
		var district = districtId == null ? null : now.lands().district(districtId).orElse(null);
		List<int[]> spans = new java.util.ArrayList<>();
		if (newStreet) spans.add(new int[] {plan.streetX0(), plan.streetX1()});
		else {
			var street = Districts.streetOf(sites.list(settlement.owner()), now, district);
			// an addition's plan runs east-west on the street as it stands (Steward's own layout lays a north-south street only new)
			if (street != null && !plan.northSouth() && street.z() == plan.streetZ()) {
				if (plan.streetX0() < street.x0()) spans.add(new int[] {plan.streetX0(), street.x0() - 1});
				if (plan.streetX1() > street.x1()) spans.add(new int[] {street.x1() + 1, plan.streetX1()});
			}
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
		say("Placing " + res.batch().items().size() + " items...");
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
			if (err != null) { say("Queue failed: " + err.getMessage()); feed(new Pipeline.Cancel()); return; }
			if (!alive() || state.phase().terminal()) { sites.cancelBatch(id); return; }
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
				say("Stage " + st.name() + " could not be approved: " + e.getMessage());
			}
		}
	}

	// ----------------------------------------------------------------- Architect events

	private void onBibleDone(BibleJob job) {
		if (bibleJobId == null || !bibleJobId.equals(job.id())) return;
		bibleJobId = null;
		boolean ok = job.status() == BibleJob.Status.DONE;

		feed(new Pipeline.BibleDone(ok, ok ? job.bibleId() : null, job.version(), job.cost().usd(), job.error().orElse(job.status().name())));
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
		if (autoApprove) realiseVillage();
		else feed(new Pipeline.BatchQueued(VPLAN_PENDING));
	}

	/** What the pipeline holds as the batch of a village waiting for the player's approval to be placed. */
	static final String VPLAN_PENDING = "village:pending";

	private void realiseVillage() {
		if (vRegionId != null || queueInFlight) return;
		var api = ArchitectApi.get();
		Map<String, String> entries = new LinkedHashMap<>();
		// (slice M3) module lots: their bound variants, made (or found by key) and their bill quoted first; the realise waits until then
		if (moduleBuild()) {
			if (!modulesReady()) return;
			moduleBindings.forEach((lot, b) -> { if (b.entryId() != null && moduleOrders.containsKey(lot)) entries.put(lot, b.entryId()); });
		}
		Group g = groupId == null ? null : api.designs().group(groupId).orElse(null);
		if (g == null && !moduleBuild()) { say("The group disappeared."); return; }
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
		if (!missing.isEmpty()) say("Left as pads (no design): " + String.join(", ", missing));
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", settlement.id());
		ext.addProperty(BatchPlanner.BUILD_EXT, buildId);
		// a district's region names its district, so a region whose answer was lost still holds the district (releaseDistrict, Districts.reconcile)
		if (districtId != null) ext.addProperty(Districts.DISTRICT_EXT, districtId);
		// a district's: its region may not take another district's land (a build saved before planning checked that, or a district founded since)
		String conflict = districtConflict();
		if (conflict != null) {
			say("Not placed: " + conflict + " The build ends here; undo the reservation and found the district again.");
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
		say(growth() ? "Placing it in the town (" + lotEntries.size() + (lotEntries.size() == 1 ? " building" : " buildings") + ")..."
			: "Placing the village (" + lotEntries.size() + " buildings, its street, lanes and square)...");
		queueInFlight = true;
		inFlightSince = System.currentTimeMillis();
		ServerPlayer actor = server.getPlayerList().getPlayer(playerId);
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
		Mode mode = growth() && growRegion != null && api.regions().get(growRegion).map(Growths::fromMaterials).orElse(false) ? Mode.CONSTRUCTION : Mode.INSTANT;
		api.regions().realise(new dev.larattalabs.architect.api.RealiseRequest(vPlanId, mode, actor, lotEntries, null, true, null, false, ext, 0, key))
			.whenComplete((id, err) -> server.execute(() -> realised(id, err, session)));
	}

	// ----------------------------------------------------------------- module lots (slice M3, docs/MODULES.md 4.2)

	/** The growth plan's own lots (box, frame, IRR's report), read back by the key that planned it; null when Architect has it no more. */
	private @org.jspecify.annotations.Nullable List<dev.larattalabs.architect.api.LotSpec> growthPlanLots() {
		if (vPlanId == null || growKey == null) return null;
		return ArchitectApi.get().regions().planByKey(settlement.owner(), opKey(growKey)).filter(p -> vPlanId.equals(p.planId()))
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
				bindModule(e.getKey(), e.getValue(), spec.get());
				return false;
			}
		}
		if (growRegion != null && ArchitectApi.get().regions().get(growRegion).map(Growths::fromMaterials).orElse(false)) {
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
	private void bindModule(String lotId, dev.larattalabs.steward.module.ModuleOrder order, dev.larattalabs.architect.api.LotSpec spec) {
		var entry = ModuleOffers.catalog().get(order.moduleId(), order.version()).filter(x -> x.programSha().equals(order.programSha()));
		if (entry.isEmpty()) {
			say("Not placed: the catalog no longer holds " + order.moduleId() + "@" + order.version() + " as it was offered. Nothing was placed.");
			feed(new Pipeline.Cancel());
			return;
		}
		JsonObject geometry = ModulePlacement.geometry(spec);
		var old = moduleBindings.get(lotId);
		dev.larattalabs.steward.module.ModuleBinding b;
		if (ModulePlacement.holds(old, order, geometry)) b = old;
		else {
			var bible = settlement.bible() != null && settlement.bible().styleVersion() == settlement.styleVersion() ? settlement.bible() : null;
			String key = ModulePlacement.nextKey(old, buildId, lotId);
			if (old != null) say("The town changed under the plan: the module is made again for its new lot.");
			b = new dev.larattalabs.steward.module.ModuleBinding(order.moduleId(), order.version(), order.programSha(), order.params(), null,
				bible == null ? null : bible.id(), bible == null ? 0 : bible.version(), dev.larattalabs.steward.module.Canon.sha(geometry), key, settlement.owner());
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
			ModuleInstalls.variant(entry.get(), ModuleOffers.env(), bound.params(), pin, ModulePlacement.reserved(spec), bound.opKey(), bound.owner(),
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
						say("Not placed: the module couldn't be made for its lot (" + (o == null ? message(err2) : o.message()) + "). Nothing was placed.");
						feed(new Pipeline.Cancel());
						return;
					}
					moduleBound(lotId, bound, o.entryId());
				}));
		}));
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
		moduleBusy = true;
		int session = Session.current();
		String key = ModulePlacement.cleared(vPlanId, entries);
		ArchitectApi.get().regions().quote(vPlanId, entries).whenComplete((q, err) -> server.execute(() -> {
			moduleBusy = false;
			if (!placingModules(session)) return;
			if (err != null || q == null) {
				say("Not placed: the bill couldn't be quoted (" + message(err) + "), and nothing is consumed unchecked.");
				feed(new Pipeline.Cancel());
				return;
			}
			JsonObject bill = dev.larattalabs.steward.module.ModuleBound.clean(q.bom());
			if (bill == null) { say("Not placed: Architect's quote is not a bill of items. Nothing was consumed."); feed(new Pipeline.Cancel()); return; }
			var now = moduleOrders.get(lot);
			Steward.LOGGER.info("module {} quote {}: {} (bound {}, exact {}, notes {})", settlement.id(), vPlanId, bill, now.bound(), q.exact(), q.notes());
			var check = ModulePlacement.quoteCheck(q.exact(), q.notes());
			// a quote that leaves a part unbilled can't hold the build to anything: refused, nothing consumed
			if (check.refused() != null) {
				say("Not placed: Architect couldn't quote the whole bill (" + check.refused() + "), so nothing is consumed unchecked.");
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
			String why = !over.isEmpty() ? "The bill came to more than you approved: " + dev.larattalabs.steward.module.ModuleBound.words(over, 100) + "."
				: !heavy.isEmpty() ? "The whole bill takes more than half of the stockpile's " + heavy.stream().map(h -> dev.larattalabs.steward.module.ModuleBound.plain(h.item())
					+ " (" + h.bill() + " of " + h.stock() + ")").collect(Collectors.joining(", ")) + ", so I ask first."
				: check.ask() != null && !now.inexactApproved() ? "Architect can't quote the bill exactly (" + check.ask() + "), so I ask first." : null;
			if (why != null) {
				// held, saved with the build: the approval binds to exactly this bill, also after a restart
				moduleOrders = Map.of(lot, now.holding(bill));
				persist();
				feed(new Pipeline.HoldBill(why + " Nothing has been taken from the stockpile. The whole bill: " + dev.larattalabs.steward.module.ModuleBound.bill(bill, 1000)
					+ ". Approve to build it with this bill (it becomes the bound), or cancel."));
				return;
			}
			if (now.held() != null) { moduleOrders = Map.of(lot, now.holding(null)); persist(); }
			quoteCleared = key;
			realiseVillage();
		}));
	}

	/**
	 * The player approved a held bill (the approve that ends AWAITING_PLACEMENT_APPROVAL): the bill they were shown becomes the bound, saved before
	 * anything is quoted again, so the next quote within it goes on, also after a restart. Nothing held (an approve after a hold that cleared): unchanged.
	 */
	private void approveHeld() {
		var next = new LinkedHashMap<String, dev.larattalabs.steward.module.ModuleOrder>();
		moduleOrders.forEach((lot, o) -> next.put(lot, o.held() == null ? o : o.approvedHeld()));
		moduleOrders = java.util.Collections.unmodifiableMap(next);
		quoteCleared = null;
		persist();
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
					o.programSha(), o.params(), o.bound(), o.rate(), o.lampFallback(), false));
			Proposals.giveBack(settlement.id(), p, null);
		}
	}

	private static JsonObject sum(JsonObject a, JsonObject b) {
		JsonObject o = a.deepCopy();
		for (var e : b.entrySet()) o.addProperty(e.getKey(), (o.has(e.getKey()) ? o.get(e.getKey()).getAsInt() : 0) + e.getValue().getAsInt());
		return o;
	}

	/** (slice A2) Why a growth may not be realised now, or null: the mode gate again, and a town built from materials keeps its stockpile. */
	private @org.jspecify.annotations.Nullable String growthRealiseRefusal() {
		var town = growRegion == null ? java.util.Optional.<dev.larattalabs.architect.api.RegionView>empty() : ArchitectApi.get().regions().get(growRegion);
		if (town.isEmpty()) return null;
		var v = town.get();
		boolean world = dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false) && server.getDefaultGameType() != net.minecraft.world.level.GameType.CREATIVE;
		boolean built = v.build() != null && v.build().built();
		if (Growths.fromMaterials(v) == world && Growths.fromMaterials(v) && !built)
			return "the town is being built from the stockpile again (an update or another change); it can grow once it's built. Start it again then.";
		String no = Growths.modeRefusal(settlement.name(), Growths.fromMaterials(v), world, built, v.build() == null ? 0 : v.build().percent());
		if (no != null) return no + " Start it again.";
		if (Growths.fromMaterials(v) && Stockpiles.of(server, Settlements.store().get(settlement.id()).orElse(settlement)).isEmpty())
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
		var regions = ArchitectApi.get().regions();
		Map<String, String> fitting = palette(sha, fitting(sha, lotEntries));
		// (Architect K3) a wall lot left without a design (none, or its design filtered out above) would leave the wall open where its tower or gatehouse
		// stands: Architect binds nothing there, so the build ends here, naming it
		var open = plan == null ? null : plan.lots().stream().filter(l -> l.exact() && !fitting.containsKey(l.id())).findFirst().orElse(null);
		if (open != null) {
			realised(null, new IllegalStateException("the " + open.role() + " on the wall has no design that fits its box (" + (lotEntries.containsKey(open.id())
				? "its design is past the lot's approved box or rule" : "it was not designed") + "), so the wall would stand open there"), session);
			return;
		}
		Steward.LOGGER.info("realise {}: artifact {} under key {} ({})", settlement.id(), sha, opKey("vrealise"), first ? "first" : "after a re-check");
		regions.realiseApproved(new dev.larattalabs.architect.api.ApprovedRealise(sha, realiseMode(sha), actor, fitting, opKey("vrealise"), ext))
			.whenComplete((id, err) -> server.execute(() -> {
				if (!Session.is(session)) return;
				Throwable e = err == null ? null : err.getCause() != null ? err.getCause() : err;
				boolean stale = e instanceof dev.larattalabs.architect.api.ArchitectRefused rf && rf.reason() == dev.larattalabs.architect.api.Reason.ARTIFACT_STALE;
				// (Architect K3) a wall lot's design leaves the wall open, or its passage unstandable: the design is repaired, not the land re-checked
				String wallLot = stale ? wallLotOf(e.getMessage()) : null;
				if (wallLot != null && alive() && !over()) { wallRefused(lotEntries, session, wallLot, e.getMessage()); return; }
				if (!first || !stale || !alive() || over()) { realised(id, err, session); return; }
				say("The land under the plan changed while the buildings were designed (" + e.getMessage() + "): checking it again...");
				JsonObject frozen = frozenPlan(sha);
				if (frozen == null) { realised(null, new IllegalStateException("the approved plan could not be read again"), session); return; }
				var saved = Settlements.store().get(settlement.id()).orElse(settlement);
				Composer.check(server, saved, frozen, playerId).whenComplete((c, cerr) -> server.execute(() -> {
					if (!Session.is(session)) return;
					// a runner replaced meanwhile (cancelled twice, another build started): nothing more of this one reaches the world
					if (!alive()) return;
					if (over()) {
						queueInFlight = false;
						if (state != null && state.phase() == Pipeline.Phase.CANCELLING) feed(new Pipeline.CancelConfirmed());
						return;
					}
					String no = cerr != null ? "Architect could not check it again (" + message(cerr) + ")" : unbuildable(c, Secrets.of(frozen, null));
					// the world's mode changed while the buildings were designed: built from materials now, or no longer; this build stops, a new Start plans anew
					if (no == null && c.survival() != artifactSurvival(sha)) no = "the world's mode changed while the buildings were designed (" + (c.survival()
						? "it builds from materials now" : "it no longer builds from materials") + "): start the build again";
					if (no != null) { realised(null, new IllegalStateException(no), session); return; }
					String next = c.artifactSha();
					// saved with the build before the old one is let go (a crash between keeps a target to resume from); the same sha keeps its pin
					pin(next);
					String before = vPlanId;
					vPlanId = dev.larattalabs.steward.gateway.PPlan.RETRIED_PREFIX + next;
					if (!persist()) {
						vPlanId = before;
						// the new pin is not the build's (it kept the old one): let go so it isn't orphaned
						if (!next.equals(sha)) regions.unpinArtifact(next, settlement.owner());
						realised(null, new IllegalStateException("the build could not be saved before placing again"), session);
						return;
					}
					if (!next.equals(sha)) regions.unpinArtifact(sha, settlement.owner());
					realiseArtifact(next, lotEntries, actor, ext, false, session);
				}));
			}));
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
			realised(null, new IllegalStateException("Architect refused the " + role + " on the wall (" + why + "), and this Architect cannot repair a design safely"
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
			if (state.bibleId() != null) req = req.withBible(state.bibleId(), state.bibleVersion());
			req = req.versionOf(entry, null).withOpKey(a.key());
		} catch (RuntimeException x) {
			later.add(new Pipeline.RepairEnded(a.key(), Pipeline.RepairEnd.REFUSED, 0, "it could not be put to Architect: " + x.getMessage()));
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
			if (!alive()) { if (id != null) ArchitectApi.get().designs().cancel(id); return; }
			if (err != null) {
				// refused: nothing designed; lost, or its key bound to a request already made: found by its key on the watch (never a second design)
				if (ChangeRequests.definite(err) && !keyConflict(err) && !uncertain(err)) feed(new Pipeline.RepairEnded(a.key(), Pipeline.RepairEnd.REFUSED, 0, "Architect would not redesign it: " + message(err)));
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
				case CANCELLED, ALREADY_FINAL -> o.design().ifPresentOrElse(d -> repairFinal(d, true),
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
				if (d.get().status().isFinal()) repairFinal(d.get(), false);
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
				if (found.get().status().isFinal()) repairFinal(found.get(), false);
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
	private void repairFinal(dev.larattalabs.architect.api.Design d, boolean confirmed) {
		var r = state == null ? null : state.repair();
		if (r == null || (r.designId() != null && !r.designId().equals(d.id()))) return;
		if (d.status() == dev.larattalabs.architect.api.Design.Status.CANCELLED && !confirmed) {
			if (repairStopSent == 0 || System.currentTimeMillis() - repairStopSent > 20_000) execute(new Pipeline.StopRepair(r.key(), d.id()));
			return;
		}
		// its next version is the same entry, which the bind takes again: one that came back as another entry is not bound (it would bind the old one)
		String entry = repairEntry(r.lotId());
		boolean sameEntry = d.entryId().isPresent() && d.entryId().get().equals(entry);
		boolean done = d.status() == dev.larattalabs.architect.api.Design.Status.DONE && sameEntry;
		String why = done ? null : d.status() == dev.larattalabs.architect.api.Design.Status.DONE ? "its repair came back as another design (" + d.entryId().orElse("none") + ")"
			: d.error().orElse(d.status().name().toLowerCase());
		feed(new Pipeline.RepairEnded(r.key(), done ? Pipeline.RepairEnd.DONE : Pipeline.RepairEnd.FAILED, d.cost().usd(), why));
	}

	/** A design finished: this build's repair is looked at. */
	private void onRepairDone(dev.larattalabs.architect.api.Design d) {
		var r = state == null ? null : state.repair();
		if (r != null && d.id().equals(r.designId())) server.execute(() -> repairFinal(d, false));
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
		var body = ArchitectApi.get().regions().artifact(sha).map(dev.larattalabs.architect.api.PlanArtifact::body).orElse(null);
		return body != null && body.has("survival") && body.get("survival").isJsonPrimitive() && body.get("survival").getAsBoolean();
	}

	/** Whether this build is cancelling or has ended (nothing more is placed for it). */
	private boolean over() {
		return state == null || switch (state.phase()) { case CANCELLING, DONE, FAILED, CANCELLED -> true; default -> false; };
	}

	/** The plan as approved: the one Architect froze in the artifact (what the designs were made for), else the kept plan as this build builds it. */
	private @org.jspecify.annotations.Nullable JsonObject frozenPlan(String sha) {
		var body = ArchitectApi.get().regions().artifact(sha).map(dev.larattalabs.architect.api.PlanArtifact::body).orElse(null);
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
		var body = ArchitectApi.get().regions().artifact(sha).map(dev.larattalabs.architect.api.PlanArtifact::body).orElse(null);
		if (body == null || !body.has("lots") || !body.get("lots").isJsonArray()) return lotEntries;
		Map<String, JsonObject> lots = new java.util.HashMap<>();
		for (var l : body.getAsJsonArray("lots")) if (l.isJsonObject() && l.getAsJsonObject().has("id")) lots.put(l.getAsJsonObject().get("id").getAsString(), l.getAsJsonObject());
		var library = ArchitectApi.get().library();
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
		var regions = ArchitectApi.get().regions();
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
			else ArchitectApi.get().regions().planByKey(settlement.owner(), opKey(growth() && growKey != null ? growKey : "vplan"));
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
		// the build ended (cancelled while this was asked, twice, or replaced): a village realised for it after all is taken up, not left without a history
		if (!alive() || state == null || switch (state.phase()) { case DONE, FAILED, CANCELLED -> true; default -> false; }) {
			if (err == null) takeUpOrphan(id);
			return;
		}
		if (err != null && growth()) {
			if ((uncertain(err) || keyConflict(err)) && findVillage()) return;
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
			say("Architect could not place the village: " + message(err));
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
		var rv = ArchitectApi.get().regions().get(regionId).flatMap(v -> Growths.byPlan(v, vPlanId));
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
		var regions = ArchitectApi.get().regions();
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
		var regions = ArchitectApi.get().regions();
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
		if (growth()) { takeUpOrphanGrowth(regionId); return; }
		Steward.LOGGER.warn("village region {} was realised for {}'s ended build {}: taking it up", regionId, settlement.id(), buildId);
		Settlements.store().get(settlement.id()).ifPresent(saved -> VillageSweep.takeUp(server, saved, regionId, this::say));
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
		if (districtId == null || districtReplanned || settlement == null || plan == null) return false;
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
		say("The land beside " + district.name() + " changed while its buildings were designed (" + message(err) + "): laying it out again.");
		int session = Session.current();
		regions.plan(Districts.besideRequest(level(), settlement, town.get().id(), request, district.id(), lots, opKey("vplan2"), buildId, district.kind(), district.follow()))
			.whenComplete((rp, perr) -> server.execute(() -> {
				if (!Session.is(session) || !alive() || worldStopped(perr)) return;
				// cancelled while the land was laid out again: nothing more is placed
				// (cancelVillage confirmed the cancel already: no realise was in flight and no region stood)
				if (state == null || state.phase().terminal() || state.phase() == Pipeline.Phase.CANCELLING) return;
				if (perr != null) {
					say("Architect could not lay " + district.name() + " out again: " + Districts.besideRefusal(null, message(perr)));
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

	/** Why this build's district region may not be placed now (another district's land in its request's claim), or null; not a district build: null. */
	private @org.jspecify.annotations.Nullable String districtConflict() {
		if (districtId == null || settlement == null) return null;
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

		var v = ArchitectApi.get().regions().get(vRegionId);
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
		String note = whole ? null : "the village is " + st.name().toLowerCase() + ": " + placed + " of " + v.get().lots().size() + " lots placed";
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
			// watched even when it is already done (a stockpile fed in time: GM-c's drawbridge run): the watch says it fully built on its next read
			MaterialRequests.watchBuild(settlement.id(), vRegionId);
		}
	}

	/**
	 * A growth while it is placed: its revision (by rid) is read, not the town's state (which reads the town's own again once the revision ends); once
	 * the revision has ended, its own sites are logged under {@code revision:<region>:<rid>} and the build ends. One that placed nothing is taken up.
	 */
	private void watchGrowth() {
		var regions = ArchitectApi.get().regions();
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
		String note = "PLACED".equalsIgnoreCase(st) ? null : "the town's change is " + st.toLowerCase(java.util.Locale.ROOT) + ": " + placed + " of " + added.size()
			+ " lots placed";
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
		growthTakingUp = true;
		Growths.undo(server, settlement.owner(), ref, this::say, ok -> {
			growthTakingUp = false;
			if (ok == null || !alive() || state == null || state.phase().terminal()) return;
			if (ok) {
				Settlements.log(settlement.id(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, "The addition was not placed (" + why + ")",
					List.of()));
				end.run();
				return;
			}
			var d = Growths.decide(ArchitectApi.get().regions().get(ref.region()), ref.rid());
			if (d.step() != Growths.Step.NEWER && d.step() != Growths.Step.UNKNOWN) return;
			var saved = Settlements.log(settlement.id(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, "The addition was not placed ("
				+ why + "); the town's change " + ref.tag() + " stands with nothing of it placed: " + d.why(), List.of()));
			if (saved.ok()) end.run();
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
		// still being placed: waits (the cancel stays CANCELLING and saved; asked again on the next world start or tick)
		if (!Growths.ended(rv.get().state()) && !"UNDONE".equalsIgnoreCase(rv.get().state()) && !"STALE".equalsIgnoreCase(rv.get().state())
			&& !"UNDOING".equalsIgnoreCase(rv.get().state())) {
			if (saidRegionWaits.add("cancel " + ref.tag())) say(v.map(Growths::fromMaterials).orElse(false)
				? "The addition is being placed from materials and can't be stopped part way: it's put down as the stockpile feeds it, then left standing; /steward undo "
					+ settlement.id() + " takes it up once it's built."
				: "The addition is still being placed: it is taken up once it is in.");
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
			growthTakingUp = false;
			if (ok == null || !alive()) return;
			if (ok) {
				vRegionId = null;
				feed(new Pipeline.CancelConfirmed());
				return;
			}
			if (Growths.building(server, ref)) {
				// (slice A2) still being built from the stockpile: it can't be taken up until it is built (nothing cancels its targets)
				say("The addition's construction sites stand and are built as the stockpile feeds them; it can't be taken up until it's built. /steward undo "
					+ settlement.id() + " takes it up then.");
				MaterialRequests.watchBuild(settlement.id(), ref.region());
			} else say("What the addition placed stays; /steward undo " + settlement.id() + " takes it up once it is clear.");
			feed(new Pipeline.BatchDone(sites.size(), 0));
		};
		var saved = Settlements.store().get(settlement.id()).orElse(settlement);
		Undo.growth(server, saved, entry.get(), ref, Map.of(), Undo.reportingOverflow(server, saved, this::say), after);
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
			regions.remove(id, new RemoveOptions(false, settlement.owner())).whenComplete((r, err) -> server.execute(() -> {
				if (!alive() || worldStopped(err)) return;
				boolean gone = (err == null && r.removed()) || regions.get(id).map(x -> x.state() == dev.larattalabs.architect.api.RegionState.REMOVED).orElse(true);
				if (!gone) { say("The village could not be taken up yet (" + (err != null ? message(err) : String.join(", ", r.blockers())) + "); cancel again once it is clear."); return; }
				vRegionId = null;
				feed(new Pipeline.CancelConfirmed());
			}));
			return;
		}
		var saved = Settlements.store().get(settlement.id()).orElse(settlement);
		Undo.region(server, saved, entry.get(), id, Map.of(), Undo.reportingOverflow(server, saved, this::say), ok -> {
			if (ok == null || !alive()) return;
			if (ok) {
				vRegionId = null;
				feed(new Pipeline.CancelConfirmed());
				return;
			}
			say("What the village placed stays; /steward undo " + settlement.id() + " takes it up once it is clear.");
			feed(new Pipeline.BatchDone(placed, skipped));
		});
	}

	/** The history entry that placed this region (its newest), whichever entry is the newest undoable one. */
	private java.util.Optional<Settlement.LogEntry> regionEntry(String regionId) {
		return Settlements.store().get(settlement.id()).flatMap(s -> VillageSweep.entryOf(s, regionId));
	}

	/** A queued item of this build waits for a temporary blocker (Architect fires it when the reason changes; it waits up to 10 minutes). */
	private void onItemWaiting(dev.larattalabs.architect.api.ItemEvent e) {
		// a batch build's by its batch; a region's by the build tag on its lots, or by the region its tiles and roads name (Architect close-out A)
		boolean ours = batchId != null && batchId.equals(e.batchId()) || buildId != null && buildId.equals(extString(e.ext(), BatchPlanner.BUILD_EXT))
			|| vRegionId != null && vRegionId.equals(extString(e.ext(), "architect_mc:region"));
		if (!ours || e.reason().isEmpty()) return;
		// a stage wait (built from materials: a part waits for the parts it stands on): Architect's own sentence, said once
		String stage = stageWait(e.ext());
		if (stage != null) {
			if (saidWaits.add(stage)) say(stage + ".");
			return;
		}
		String what = BatchPlanner.isStreet(e.itemKey()) ? "street" : java.util.Optional.ofNullable(extString(e.ext(), "steward_mc:role")).orElse(e.itemKey().replace('_', ' '));
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

	/** Stage waits said this session (each sentence once: several items of a part wait alike). */
	private final Set<String> saidWaits = new java.util.HashSet<>();

	/** A stage wait's sentence ({@code architect_mc:waitsFor.say}: "the street waits for the town wall to be built"), capitalised; else null. */
	static @org.jspecify.annotations.Nullable String stageWait(com.google.gson.@org.jspecify.annotations.Nullable JsonObject ext) {
		if (ext == null || !ext.has("architect_mc:waitsFor") || !ext.get("architect_mc:waitsFor").isJsonObject()) return null;
		var w = ext.getAsJsonObject("architect_mc:waitsFor");
		if (!w.has("say") || !w.get("say").isJsonPrimitive()) return null;
		String t = w.get("say").getAsString().strip();
		return t.isEmpty() ? null : Character.toUpperCase(t.charAt(0)) + t.substring(1);
	}

	private void onBatchDone(BatchView b) {
		if (batchId == null || !batchId.equals(b.id())) return;
		// buildings only: the street is a road, reported on its own when it fails
		long placed = b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.PLACED && !BatchPlanner.isStreet(i.itemKey())).count();
		long failed = b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.FAILED && !BatchPlanner.isStreet(i.itemKey())).count();
		b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.FAILED).forEach(i -> say("Not placed: " + i.itemKey() + " (" + i.reason().map(Enum::name).orElse("?") + ") " + i.message()));
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
		return "From materials it needs " + (exact ? "" : "about ") + units + " items of " + total.size() + " kinds: " + Stockpiles.items(list, 6) + ".";
	}

	/**
	 * The cells a stockpile crate may take: off this build's lots and street (a margin of 1 round a lot, 3 round the street's line), and off the town's
	 * street as it stands (a crate there would block what is built, and nobody but its owner can move it).
	 */
	static java.util.function.BiPredicate<Integer, Integer> clearOf(VillageLayout.Plan plan, List<dev.larattalabs.architect.api.SiteView> standing, Settlement s) {
		var street = Districts.streetOf(standing, s, null);
		return (x, z) -> {
			for (VillageLayout.Lot l : plan.lots()) if (x >= l.x() - 1 && x <= l.maxX() + 1 && z >= l.z() - 1 && z <= l.maxZ() + 1) return false;
			int[] b = plan.spanBox(plan.streetX0() - 3, plan.streetX1() + 3, 3);
			if (x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]) return false;
			return street == null || !(Math.abs(z - street.z()) <= 3 && x >= street.x0() - 3 && x <= street.x1() + 3);
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
		if (!Session.is(session)) return;
		if (settlement != null) StewardVoice.say(server, settlement.id(), text);
		ServerPlayer p = server.getPlayerList().getPlayer(playerId);
		if (p != null) p.sendSystemMessage(Component.literal(text));
		else if (unread.size() < 50) unread.add(text);
	}
}
