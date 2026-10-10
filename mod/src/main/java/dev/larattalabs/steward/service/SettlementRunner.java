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
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			{
				List.copyOf(ACTIVE.values()).forEach(r -> r.deliverUnread(handler.getPlayer()));
				sendInbox(server, handler.getPlayer().getUUID());
			});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ACTIVE.clear();
			resyncPending = false;
			builds = new BuildStore();
			buildsFile = null;
		});
	}

	private static void restoreAll(MinecraftServer server) {
		buildsFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("steward-builds.json");
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
	public static boolean busy(String settlementId) {
		SettlementRunner r = ACTIVE.get(settlementId);
		return r != null && (r.state == null || !r.state.phase().terminal());
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
		if (state == null || state.phase().terminal()) return "Nothing to cancel.";
		boolean second = state.phase() == Pipeline.Phase.CANCELLING;
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
		if (state.phase().terminal()) builds.remove(settlement.id());
		else builds.put(new BuildStore.Saved(settlement.id(), buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId,
			List.copyOf(landmarkIds), List.copyOf(decided), lastAwaiting));
		if (buildsFile == null) return false;
		try {
			builds.save(buildsFile);
			return true;
		} catch (java.io.IOException e) {
			Steward.LOGGER.warn("could not save {}", buildsFile, e);
			return false;
		}
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
		var running = sites.batches(settlement.owner()).stream().filter(BatchView::running).filter(b -> buildId.equals(extString(b.ext(), BatchPlanner.BUILD_EXT))
			|| b.items().stream().anyMatch(i -> plan.lots().stream().anyMatch(l -> l.id().equals(i.itemKey())))).findFirst();
		List<ResyncRules.StageSeen> stages = siteGroupId == null ? List.of() : sites.group(siteGroupId).map(g -> g.stages().stream()
			.filter(st -> batchId == null || batchId.equals(st.batchId())).map(st -> new ResyncRules.StageSeen(st.name(), st.state().terminal())).toList()).orElse(List.of());
		List<String> placed = placedSites(sites);
		// no decision is in flight after a restart: one that reached Architect is no longer awaiting, one that did not is asked for (or made) again
		decided.clear();
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
			case REQUEUE -> fitAndQueue(!permission.needsApproval(Permission.Action.NEW_PROJECT));
			case INTERRUPTED -> interrupted(switch (state.phase()) {
				case BIBLE_RUNNING -> "the style bible request (it may still be made and charged)";
				case GROUP_RUNNING, AWAITING_MASSING_APPROVAL -> "the design group request";
				default -> "the placement";
			});
		}
	}

	/** The building sites this build placed: tagged with its build id (the street road is not a building). */
	private List<String> placedSites(dev.larattalabs.architect.api.Sites sites) {
		return placedSites(sites, false);
	}

	private List<String> placedSites(dev.larattalabs.architect.api.Sites sites, boolean withStreet) {
		return sites.list(settlement.owner()).stream().filter(v -> buildId.equals(extString(v.ext(), BatchPlanner.BUILD_EXT)) && (withStreet || !BatchPlanner.STREET_KEY.equals(v.itemKey())))
			.map(dev.larattalabs.architect.api.SiteView::id).toList();
	}

	private static String extString(JsonObject ext, String key) {
		return ext != null && ext.has(key) && ext.get(key).isJsonPrimitive() ? ext.get(key).getAsString() : null;
	}

	private void interrupted(String what) {
		say("The restart interrupted " + what + ". Cancel this build with /steward cancel " + settlement.id() + " and start it again.");
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
		if (siteIds.isEmpty() || Settlements.store().get(settlement.id()).isEmpty()) return true;
		var sites = ArchitectApi.get().sites(server);
		List<Settlement.SiteChange> changes = siteIds.stream().map(id -> {
			var v = sites.get(id);
			String lot = v.map(x -> extString(x.ext(), "steward_mc:role")).orElse(null);
			return new Settlement.SiteChange(id, lot, 0, v.map(dev.larattalabs.architect.api.SiteView::version).orElse(1));
		}).toList();
		return Settlements.log(settlement.id(), Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.PROJECT_PLACED, "Placed " + buildings + " buildings"
			+ (notPlaced > 0 ? " (" + notPlaced + " not placed)" : ""), changes, siteGroupId, notPlaced > 0 ? Settlement.Outcome.PARTIAL : Settlement.Outcome.DONE)).ok();
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
	private Group resumeGroup;
	private double resumeBudget;

	/** Start from a settlement the player claimed with the Founding Stone and described: its claim and card are used, no card job runs. */
	public void startExisting(Settlement s, int buildings, double budgetUsd) {
		if (!s.described()) { say("Describe " + s.id() + " first: /steward describe " + s.id() + " <your words>"); return; }
		if (!claim(s.id())) return;
		this.existing = s;
		surveyAndStart(new CardResult(s.card(), null, new dev.larattalabs.architect.api.Cost(0, 0, 0, 0, 0, 0)), buildings, budgetUsd);
	}

	private void surveyAndStart(CardResult r, int buildings, double budgetUsd) {
		int cx = existing != null ? existing.claim().centerX() : origin.getX();
		int cz = existing != null ? existing.claim().centerZ() : origin.getZ();
		ServerLevel level = level();
		// the settlement's own claim (sized by its card, grown by Expand); a dev build uses the default
		// a one-street village stays near the stone even in a big claim (the rest of a big claim is room for districts and regions)
		int rad = Math.min(dev.larattalabs.steward.model.ClaimRules.VILLAGE_RADIUS, existing != null ? existing.claim().radius() : CLAIM_RADIUS);
		BoundingBox area = new BoundingBox(cx - rad, level.getMinY(), cz - rad, cx + rad, level.getMaxY(), cz + rad);
		say("Surveying the land...");
		ArchitectApi.get().survey().sample(level, area, 1, LoadPolicy.LOADED_ONLY).whenComplete((sample, err) -> {
			if (err != null) { abandon("Survey failed: " + err.getMessage()); return; }
			Grid grid = TerrainGrid.fromSample(sample);
			int wet = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
			for (int z = grid.z0(); z < grid.z0() + grid.depth(); z++) for (int x = grid.x0(); x < grid.x0() + grid.width(); x++) {
				if (grid.waterAt(x, z)) { wet++; continue; }
				lo = Math.min(lo, grid.heightAt(x, z)); hi = Math.max(hi, grid.heightAt(x, z));
			}
			Steward.LOGGER.info("survey: {}x{} columns, {} unusable (water or unloaded), {} loaded chunks, ground y {}..{}, trees {}", grid.width(), grid.depth(), wet, sample.chunksLoaded(), lo, hi, sample.tree().cardinality());
			Claim claim = existing != null ? dev.larattalabs.steward.model.ClaimRules.withRadius(existing.claim(), rad)
				: new Claim(level.dimension().identifier().toString(), cx, cz, CLAIM_RADIUS, level.getMinY(), level.getMaxY());
			settlement = existing != null ? existing : Settlement.found(DEV_ID, r.card(), claim, permission, Difficulty.PATRON, System.currentTimeMillis());
			// landmarks run on the dearer model: the size's typical count, and at most one per four buildings
			int maxLandmarks = dev.larattalabs.steward.model.BudgetPolicy.maxLandmarks(r.card().site().size(), buildings);
			ProgramPlanner.Result program = ProgramPlanner.lots(r.card(), buildings, maxLandmarks);
			if (!program.fromCard()) say("This card has no building program (it was described before programs existed), so this is a generic village. Describe it again to get buildings of its own.");
			if (!program.leftOut().isEmpty()) say("Left out at " + buildings + " buildings: " + String.join(", ", program.leftOut()) + ".");
			List<VillageLayout.LotSpec> specs = program.specs();
			plan = VillageLayout.plan(claim, grid, specs, VillageLayout.Rules.defaults());
			say("Layout: " + plan.lots().size() + " lots on the street" + (plan.unplaced().isEmpty() ? "" : " (" + plan.unplaced().size() + " did not fit)"));
			if (plan.lots().isEmpty()) { abandon("No dry, flat room here. Try another spot."); return; }
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
			state = State.start(settlement.id(), r.card(), budgetUsd);
			apply(Pipeline.step(state, new Pipeline.CardApproved(r.card()), permission));
		});
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
	private void abandon(String why) {
		say(why);
		if (claimedId != null) ACTIVE.remove(claimedId, this);
		sendInbox(server, playerId);
	}

	private void apply(Pipeline.Step step) {
		Pipeline.Phase before = state == null ? null : state.phase();
		state = step.next();
		// the massings show as ghosts on their lots while they wait for the player, and go when decided
		if (state.phase() == Pipeline.Phase.AWAITING_MASSING_APPROVAL && before != Pipeline.Phase.AWAITING_MASSING_APPROVAL) showMassings(true);
		if (before == Pipeline.Phase.AWAITING_MASSING_APPROVAL && state.phase() != Pipeline.Phase.AWAITING_MASSING_APPROVAL) hideMassings();
		// saved before anything is asked of Architect: a crash after a request then restores a build that knows it asked (and says it was interrupted)
		// rather than one that asks, and pays, again
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
		var home = new net.minecraft.core.BlockPos(settlement.claim().centerX(), plan.streetY(), settlement.claim().centerZ());
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

	/** Architect's helper refused a cancel because the thing is unknown or already final (its words, 1.8). */
	private static boolean refusedAsOver(Throwable err) {
		String m = String.valueOf(message(err)).toLowerCase();
		return m.contains("no group") || m.contains("already");
	}

	private static String message(Throwable err) {
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

	/** This build's inbox entry, or null before it has a settlement and a state (still reading the card or surveying) or once it is finished. */
	public dev.larattalabs.steward.inbox.InboxModel.Entry inboxEntry() {
		if (settlement == null || state == null || state.phase().terminal()) return null;
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
					details.getOrDefault(l.id(), l.sizeX() + "x" + (l.sizeZ() - dev.larattalabs.steward.gateway.LotBrief.APPROACH_MARGIN) + " lot")));
			}
		}
		return dev.larattalabs.steward.inbox.InboxModel.entry(settlement.id(), settlement.name(), state, lots);
	}

	/** Sends a player their inbox: every unfinished build of theirs (an empty inbox clears the client's). */
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
			layers.add(new StewardNet.Layer(m.id() + "@" + m.version(), at.x(), at.y(), at.z(), at.rotation(), "MASSING"));
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
				BibleRequest req = new BibleRequest(b.prompt(), b.name(), settlement.owner(), new JsonObject(), null, b.budgetUsd(), List.of(), null, null);
				bibleInFlight = true;
				api.bibles().request(req).whenComplete((job, err) -> {
					bibleInFlight = false;
					if (err != null) { feed(new Pipeline.BibleDone(false, null, 0, 0, err.getMessage())); return; }
					// cancelled while the request was on its way (or this runner belongs to a closed world): stop the job now
					if (!alive() || state.phase().terminal()) { if (!job.finished()) api.bibles().cancel(job.id()); return; }
					bibleJobId = job.id();
					persist();
					if (state.phase() == Pipeline.Phase.CANCELLING && !job.finished()) api.bibles().cancel(job.id());
					// the job as it stood at the ack: a fast or cached bible may already be done, and BIBLE_DONE fired before we knew its id
					if (job.finished()) onBibleDone(job);
				});
			}
			case Pipeline.RequestGroup g -> {
				GroupPlanner.Options o = new GroupPlanner.Options(g.bibleId(), g.bibleVersion(), g.budgetUsd(), 3, 3, landmarks);
				GroupPlanner.Built built = GroupPlanner.build(settlement, plan, o);
				landmarkIds = built.request().items().stream().filter(i -> i.role() == GroupRequest.Role.LANDMARK).map(GroupRequest.Item::itemKey).collect(Collectors.toSet());
				say("Designing " + built.request().items().size() + " buildings (massings first)...");
				groupInFlight = true;
				api.designs().requestGroup(built.request()).whenComplete((id, err) -> {
					groupInFlight = false;
					if (err != null) {
						say("Group failed: " + err.getMessage());
						// nothing reached Architect: the cancel finishes at once
						feed(new Pipeline.Cancel());
						return;
					}
					if (!alive() || state.phase().terminal()) { api.designs().cancelGroup(id); return; }
					groupId = id;
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
				} else if (!bibleInFlight) later.add(new Pipeline.CancelConfirmed());
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
				else if (!groupInFlight) later.add(new Pipeline.CancelConfirmed());
			}
			case Pipeline.FitAndQueue f -> fitAndQueue(f.autoApprove());
			case Pipeline.ApproveStages a -> approveStages();
			case Pipeline.CancelBatch cb -> {
				if (batchId != null) cancelBatch(batchId);
				else if (!queueInFlight) later.add(new Pipeline.CancelConfirmed());
			}
			case Pipeline.Notify n -> {
				String hint = n.needsDecision() ? hint() : null;
				say(hint == null ? n.text() : n.text() + " " + hint);
			}
		}
	}

	private void fitAndQueue(boolean autoApprove) {
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
		BatchPlanner.Result res = BatchPlanner.build(settlement, plan, landmarkIds, entries, fits, level,
			dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false), autoApprove, true, buildId);
		if (res.note() != null) say(res.note());
		if (!res.skippedLotIds().isEmpty()) say("Skipped lots (no design or no fit): " + res.skippedLotIds());
		say("Placing " + res.batch().items().size() + " items...");
		queueInFlight = true;
		sites.queue(res.batch()).whenComplete((id, err) -> {
			queueInFlight = false;
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

	private void onBatchDone(BatchView b) {
		if (batchId == null || !batchId.equals(b.id())) return;
		// buildings only: the street is a road, reported on its own when it fails
		long placed = b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.PLACED && !BatchPlanner.STREET_KEY.equals(i.itemKey())).count();
		long failed = b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.FAILED && !BatchPlanner.STREET_KEY.equals(i.itemKey())).count();
		b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.FAILED).forEach(i -> say("Not placed: " + i.itemKey() + " (" + i.reason().map(Enum::name).orElse("?") + ") " + i.message()));
		// every site of the build, street included, so an undo removes all of it
		if (siteGroupId == null) siteGroupId = b.group();
		if (!logPlaced(b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.PLACED).flatMap(i -> i.siteId().stream()).toList(), (int) placed, (int) failed)) {
			logFailed();
			return;
		}
		batchId = null;
		feed(new Pipeline.BatchDone((int) placed, (int) failed));
		say(String.format("Settlement spend: $%.2f of $%.0f.", state.spentUsd(), state.budgetUsd()));
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
