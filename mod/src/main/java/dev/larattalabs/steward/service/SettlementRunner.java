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
		// a build that ended while its realise is still unanswered stays busy: the answer may yet bring a region (a district's is held until it does)
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
			case PLACEMENT -> { feed(new Pipeline.PlacementApproved()); yield "Approved; placing the settlement stage by stage."; }
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
	}

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
			List.copyOf(landmarkIds), List.copyOf(decided), lastAwaiting, addition, districtId, newStreet, keyed, vPlanId, vRegionId));
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
				case READY_TO_PLACE -> { if (vRegionId != null) watchVillage(); else if (!findVillage()) queueVillage(!permission.needsApproval(Permission.Action.NEW_PROJECT)); }
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
		cards.submit(prompt, Map.of(), new ConceptCardJob.Settings(false, "patron", CLAIM_RADIUS), null).whenComplete((r, err) -> {
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
		this.districtId = districtId;
		var c = s.card();
		var card = new dev.larattalabs.steward.model.ConceptCard(c.name(), c.site(), c.style(), c.purpose(), c.story(), c.constraints(), c.avoid(), c.interpretation(),
			c.contradictions(), c.assumptions(), List.of(building));
		this.addition = true;
		startExisting(new Settlement(s.id(), s.name(), card, s.claim(), s.siteVersion(), s.styleVersion(), s.purposeVersion(), s.permission(), s.difficulty(), s.log(),
			s.proposals(), s.bible(), s.autonomy(), s.lands()), 1, budgetUsd);
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
			List<VillageLayout.LotSpec> specs = program.specs();
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
				: line != null ? VillageLayout.plan(claim, grid, specs, rules, line) : VillageLayout.plan(claim, grid, specs, rules);
			boolean worldSurvival = dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false);
			boolean overworld = level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
			// a district beside a region town (docs/DISTRICTS.md section 9): its first building plans the district's own region beside the town
			if (district != null && !district.square()) {
				planDistrict(r, budgetUsd, district, specs);
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
		if (!Settlement.District.BESIDE.equals(district.kind()) || !Settlement.District.RESERVED.equals(district.state())) {
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
		int[] request = dev.larattalabs.steward.model.DistrictRules.request(new int[] {b.minX(), b.minZ(), b.maxX(), b.maxZ()}, district.claim(), district.direction());
		// Architect may place the district anywhere in the request's claim: if another district's land is there now, nothing is planned
		var envelope = dev.larattalabs.steward.model.Land.ofRect(district.claim().dimension(), request[0], request[1], request[2], request[3]);
		var sibling = settlement.lands().districts().stream().filter(o -> !o.id().equals(district.id()) && o.land().overlaps(envelope)).findFirst();
		if (sibling.isPresent()) {
			Settlements.districtState(settlement.id(), district.id(), Settlement.District.RESERVED);
			abandon(district.name() + "'s ground now reaches " + sibling.get().name() + "'s land: undo the reservation and found it again.");
			return;
		}
		List<JsonObject> lots = new ArrayList<>();
		for (var e : dev.larattalabs.steward.gateway.VPlan.params(specs, settlement.id(), buildId).getAsJsonArray("lots")) lots.add(e.getAsJsonObject());
		var req = Districts.besideRequest(level, settlement, town.get().id(), request, district.id(), lots, opKey("vplan"), buildId);
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
		List<String> stuck = new ArrayList<>();
		for (JsonObject part : c.parts()) {
			String cls = part.has("class") ? part.get("class").getAsString() : "";
			boolean required = part.has("required") && part.get("required").getAsBoolean();
			if (required && !cls.equals("fits") && !cls.equals("adjusted")) stuck.add((part.has("part") ? part.get("part").getAsString() : "?").replace('_', ' ') + " (" + cls
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
	private static List<String> leftOut(dev.larattalabs.architect.api.PlanCheck c) {
		List<String> out = new ArrayList<>();
		for (JsonObject part : c.parts()) {
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
				String no = unbuildable(c);
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
				var out = leftOut(c);
				if (!out.isEmpty()) say("Left out of what is built (they didn't fit here): " + String.join(", ", out) + ".");
				var waits = PlanStore.get(settlement.id()).map(PlanStore.Entry::pending).orElse(List.of());
				if (!waits.isEmpty()) say("Built now without " + String.join(", ", waits.stream().map(w -> w.part().replace('_', ' ')).toList()) + ": "
					+ (waits.size() == 1 ? "it waits" : "they wait") + " for Architect.");
				pin(c.artifactSha());
				vPlanId = dev.larattalabs.steward.gateway.PPlan.ARTIFACT_PREFIX + c.artifactSha();
				plan = dev.larattalabs.steward.gateway.PPlan.toPlan(lots.get(), built);
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
			for (int[] sp : spans) if (a.overlaps(dim, sp[0], plan.streetZ() - 2, sp[1], plan.streetZ() + 2)) return "the protected area \"" + a.label() + "\"";
		}
		for (Settlement.District d : s.lands().districts()) {
			if (d.id().equals(own)) continue;
			List<dev.larattalabs.steward.model.Land> off = new ArrayList<>(List.of(d.land()));
			if (d.region() != null) ArchitectApi.get().regions().get(d.region())
				.flatMap(v -> dev.larattalabs.steward.gateway.DistrictLand.inside(v.ext(), dim)).ifPresent(off::add);
			for (int[] sp : spans) {
				var band = dev.larattalabs.steward.model.Land.ofRect(dim, sp[0], plan.streetZ() - 2, sp[1], plan.streetZ() + 2);
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
			.ifPresent(d -> Settlements.districtState(settlement.id(), d.id(), Settlement.District.RESERVED));
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
		if (before != null && !before.terminal() && state.phase().terminal() && state.phase() != Pipeline.Phase.DONE && !paidRequestAcked && onNothingSpent != null) {
			onNothingSpent.run();
			onNothingSpent = null;
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
			case READY_TO_PLACE, AWAITING_PLACEMENT_APPROVAL, PLACING -> new net.minecraft.core.BlockPos(plan.streetX1() + 4, plan.streetY(), plan.streetZ());
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
			|| c instanceof Pipeline.ApproveStages || c instanceof Pipeline.ApproveGroup;
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
				GroupPlanner.Built built = GroupPlanner.build(settlement, plan, o, reservedCells());
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
			case Pipeline.ApproveStages a -> { if (village()) realiseVillage(); else approveStages(); }
			case Pipeline.CancelBatch cb -> {
				if (village()) cancelVillage();
				else if (batchId != null) cancelBatch(batchId);
				else if (!queueInFlight && !recovering) { if (keyed) cancelByKeys(); else later.add(new Pipeline.CancelConfirmed()); }
			}
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
			if (street != null && street.z() == plan.streetZ()) {
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
		if (state != null && state.phase() == Pipeline.Phase.CANCELLING && (batchId != null || queueInFlight)) return;
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
		Group g = groupId == null ? null : api.designs().group(groupId).orElse(null);
		if (g == null) { say("The group disappeared."); return; }
		Map<String, String> entries = new LinkedHashMap<>();
		for (Group.Item it : g.items()) it.entryId().ifPresent(e -> entries.put(it.itemKey(), e));
		// only the planned lots, each with its design (a copy included); a lot with none stays a pad
		Map<String, String> lotEntries = new LinkedHashMap<>();
		for (VillageLayout.Lot l : plan.lots()) if (entries.containsKey(l.id())) lotEntries.put(l.id(), entries.get(l.id()));
		var missing = plan.lots().stream().map(VillageLayout.Lot::id).filter(id -> !lotEntries.containsKey(id)).toList();
		if (!missing.isEmpty()) say("Left as pads (no design): " + String.join(", ", missing));
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", settlement.id());
		ext.addProperty(BatchPlanner.BUILD_EXT, buildId);
		// a district's region names its district, so a region whose answer was lost still holds the district (releaseDistrict, Districts.reconcile)
		if (districtId != null) ext.addProperty(Districts.DISTRICT_EXT, districtId);
		say("Placing the village (" + lotEntries.size() + " buildings, its street, lanes and square)...");
		queueInFlight = true;
		ServerPlayer actor = server.getPlayerList().getPlayer(playerId);
		int session = Session.current();
		String artifact = dev.larattalabs.steward.gateway.PPlan.artifactOf(vPlanId);
		if (artifact != null) {
			realiseArtifact(artifact, lotEntries, actor, ext, !dev.larattalabs.steward.gateway.PPlan.retried(vPlanId), session);
			return;
		}
		// keyed: asked again (a restart, a lost answer) it is the same realise, never a second village
		api.regions().realise(new dev.larattalabs.architect.api.RealiseRequest(vPlanId, Mode.INSTANT, actor, lotEntries, null, true, null, false, ext, 0, opKey("vrealise")))
			.whenComplete((id, err) -> server.execute(() -> realised(id, err, session)));
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
		Steward.LOGGER.info("realise {}: artifact {} under key {} ({})", settlement.id(), sha, opKey("vrealise"), first ? "first" : "after a re-check");
		regions.realiseApproved(new dev.larattalabs.architect.api.ApprovedRealise(sha, realiseMode(sha), actor, fitting, opKey("vrealise"), ext))
			.whenComplete((id, err) -> server.execute(() -> {
				if (!Session.is(session)) return;
				Throwable e = err == null ? null : err.getCause() != null ? err.getCause() : err;
				boolean stale = e instanceof dev.larattalabs.architect.api.ArchitectRefused rf && rf.reason() == dev.larattalabs.architect.api.Reason.ARTIFACT_STALE;
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
					String no = cerr != null ? "Architect could not check it again (" + message(cerr) + ")" : unbuildable(c);
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
			else ArchitectApi.get().regions().planByKey(settlement.owner(), opKey("vplan"));
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
		if (err != null) {
			// a lost answer, or the key asked with another body (the player who placed it changed): looked up by its key
			if ((uncertain(err) || keyConflict(err)) && findVillage()) return;
			say("Architect could not place the village: " + message(err));
			feed(new Pipeline.Cancel());
			return;
		}
		adoptVillage(id);
	}

	/** Takes the realised region as this build's placement. */
	private void adoptVillage(String regionId) {
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
	 * This build's village region (a restart came, or an answer was lost, before its id was saved): by its realise key, or (a village realised before keys)
	 * by its owner and the build's tag. A removed one (its tombstone) is not this build's placement.
	 */
	private boolean findVillage() {
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
		Steward.LOGGER.warn("village region {} was realised for {}'s ended build {}: taking it up", regionId, settlement.id(), buildId);
		Settlements.store().get(settlement.id()).ifPresent(saved -> VillageSweep.takeUp(server, saved, regionId, this::say));
	}

	/** The region's sites as Steward records them: its streets and buildings, or (when it left nothing else) the ground tiles it wrote. */
	private List<String> regionSites(String regionId) {
		return VillageSweep.sitesOf(server, settlement.owner(), regionId);
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
		if (!village() || vRegionId == null || state == null || !alive()) return;
		if (state.phase() != Pipeline.Phase.PLACING && state.phase() != Pipeline.Phase.READY_TO_PLACE) return;
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
		if (build != null && !build.done()) {
			say(String.format("Its construction sites stand: it's built as the stockpile feeds it (%.0f%% so far). I'll say when it's done.", build.percent()));
			MaterialRequests.watchBuild(settlement.id(), vRegionId);
		}
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
			if (Math.abs(z - plan.streetZ()) <= 3 && x >= plan.streetX0() - 3 && x <= plan.streetX1() + 3) return false;
			return street == null || !(Math.abs(z - street.z()) <= 3 && x >= street.x0() - 3 && x <= street.x1() + 3);
		};
	}

	/** "Nothing to cancel", and when buildings are still being built from the stockpile, how to take them up (an undo refunds what they used). */
	static String nothingToCancel(MinecraftServer server, @org.jspecify.annotations.Nullable Settlement s) {
		if (s == null || unfinishedConstruction(server, s) == 0) return "Nothing to cancel.";
		return "Nothing to cancel: the placed buildings are being built from the stockpile. /steward undo " + s.id() + " takes them up and gives back what they used.";
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
		if (!Session.is(session)) return;
		if (settlement != null) StewardVoice.say(server, settlement.id(), text);
		ServerPlayer p = server.getPlayerList().getPlayer(playerId);
		if (p != null) p.sendSystemMessage(Component.literal(text));
		else if (unread.size() < 50) unread.add(text);
	}
}
