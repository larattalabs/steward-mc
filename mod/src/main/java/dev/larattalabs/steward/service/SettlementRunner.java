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
			List.copyOf(ACTIVE.values()).forEach(r -> r.deliverUnread(handler.getPlayer())));
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

	/** Sends one awaiting massing back with the player's notes (the others stay waiting). */
	public String redirect(String lot, String notes) {
		if (state == null || Pipeline.awaiting(state) != Pipeline.Decision.MASSINGS) return "No massing is waiting for approval.";
		if (!lastAwaiting.contains(lot)) return "Lot " + lot + " is not waiting; waiting: " + String.join(", ", lastAwaiting) + ".";
		feed(new Pipeline.MassingDecision(List.of(), Map.of(lot, notes), List.of()));
		return "Sent " + lot + " back with your notes.";
	}

	/** Raises the design budget and resumes a group paused at its soft budget. */
	public String raise(double newBudgetUsd) {
		if (state == null || state.groupId() == null) return "There is no design group to fund yet.";
		if (newBudgetUsd <= state.budgetUsd()) return String.format("The budget is already $%.0f; give a higher one.", state.budgetUsd());
		feed(new Pipeline.BudgetRaised(newBudgetUsd));
		return String.format("Budget raised to $%.0f.", newBudgetUsd);
	}

	public String cancel() {
		if (state == null || state.phase().terminal()) return "Nothing to cancel.";
		feed(new Pipeline.Cancel());
		return "Cancelled. What is already placed stays (remove it with /steward undo " + settlement.id() + ").";
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
	/** lots already approved or redirected: Architect re-sends the awaiting status while an approval is in flight, and a second approve of the same item fails. */
	private final Set<String> decided = new java.util.HashSet<>();
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
	private void persist() {
		if (settlement == null || state == null || plan == null) return;
		if (state.phase().terminal()) builds.remove(settlement.id());
		else builds.put(new BuildStore.Saved(settlement.id(), buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId,
			List.copyOf(landmarkIds), List.copyOf(decided), lastAwaiting));
		if (buildsFile == null) return;
		try {
			builds.save(buildsFile);
		} catch (java.io.IOException e) {
			Steward.LOGGER.warn("could not save {}", buildsFile, e);
		}
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
		ResyncRules.Action action = ResyncRules.decide(state.phase(), bibleJobId != null, groupId != null, batchId != null, seen, running.isPresent(), stages, placed.size());
		Steward.LOGGER.info("resume {}: phase {}, batch {}, stages {}, placed {} -> {}", settlement.id(), state.phase(), seen, stages.size(), placed.size(), action);
		switch (action) {
			case NONE, WAIT_FOR_BATCH -> { }
			case REREAD_GROUP -> api.designs().group(groupId).ifPresentOrElse(this::onGroup, () -> interrupted("design group " + groupId + " (Architect no longer has it)"));
			case ADOPT_RUNNING_BATCH -> {
				batchId = running.get().id();
				siteGroupId = running.get().group();
				if (state.phase() == Pipeline.Phase.READY_TO_PLACE) feed(new Pipeline.BatchQueued(batchId));
				else persist();
			}
			case FINISH_FROM_BATCH -> onBatchDone(batch.get());
			case FINISH_FROM_SITES -> {
				logPlaced(placedSites(sites, true), placed.size());
				batchId = null;
				feed(new Pipeline.BatchDone(placed.size(), Math.max(0, plan.lots().size() - placed.size())));
			}
			case REQUEUE -> fitAndQueue(!permission.needsApproval(Permission.Action.NEW_PROJECT));
			case INTERRUPTED -> interrupted(switch (state.phase()) {
				case BIBLE_RUNNING -> "the style bible request";
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

	/** Records the placed sites in the settlement's change log ({@code /steward undo} removes them). Dev builds are not saved settlements. */
	private void logPlaced(List<String> siteIds, int buildings) {
		if (siteIds.isEmpty() || Settlements.store().get(settlement.id()).isEmpty()) return;
		Settlements.log(settlement.id(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.PROJECT_PLACED, "Placed " + buildings + " buildings", siteIds, siteGroupId));
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
		BoundingBox area = new BoundingBox(cx - CLAIM_RADIUS, level.getMinY(), cz - CLAIM_RADIUS, cx + CLAIM_RADIUS, level.getMaxY(), cz + CLAIM_RADIUS);
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
			Claim claim = new Claim(level.dimension().identifier().toString(), cx, cz, CLAIM_RADIUS, level.getMinY(), level.getMaxY());
			settlement = existing != null ? existing : Settlement.found(DEV_ID, r.card(), claim, permission, Difficulty.PATRON, System.currentTimeMillis());
			// landmarks run on the dearer model: the size's typical count, and at most one per four buildings
			int maxLandmarks = Math.min(dev.larattalabs.steward.model.BudgetPolicy.typicalLandmarks(r.card().site().size()), Math.max(1, buildings / 4));
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
				d.extendGroup(groupId, resumeBudget).thenCompose(v -> d.resumeGroup(groupId)).whenComplete((v, e) -> { if (e != null) say("Resume failed: " + e.getMessage()); });
				for (Group.Item it : resumeGroup.items()) if (it.status() == dev.larattalabs.architect.api.Design.Status.DONE || it.stage().isPresent()) decided.add(it.itemKey());
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
	}

	private void apply(Pipeline.Step step) {
		Pipeline.Phase before = state == null ? null : state.phase();
		state = step.next();
		// the massings show as ghosts on their lots while they wait for the player, and go when decided
		if (state.phase() == Pipeline.Phase.AWAITING_MASSING_APPROVAL && before != Pipeline.Phase.AWAITING_MASSING_APPROVAL) showMassings(true);
		if (before == Pipeline.Phase.AWAITING_MASSING_APPROVAL && state.phase() != Pipeline.Phase.AWAITING_MASSING_APPROVAL) hideMassings();
		for (Command c : step.commands()) execute(c);
		persist();
	}

	/** The composite key the settlement's massing ghosts show under. */
	private String massingKey() {
		return "steward_mc:massings/" + settlement.id();
	}

	/**
	 * Sends the awaiting massings (or, when none wait, every massing of the group) to the player as ghosts on their lots, and with {@code describe} lists
	 * each in chat: lot, role, size and named parts. Returns what to tell a command caller.
	 */
	public String showMassings(boolean describe) {
		if (groupId == null || plan == null) return "No massings yet.";
		var designs = ArchitectApi.get().designs();
		Group g = designs.group(groupId).orElse(null);
		if (g == null) return "Architect no longer has the design group.";
		Set<String> waiting = Set.copyOf(lastAwaiting);
		List<StewardNet.Layer> layers = new ArrayList<>();
		List<String> lines = new ArrayList<>();
		for (Group.Item it : g.items()) {
			if (it.massing().isEmpty() || (!waiting.isEmpty() && !waiting.contains(it.itemKey()))) continue;
			VillageLayout.Lot lot = plan.lots().stream().filter(l -> l.id().equals(it.itemKey())).findFirst().orElse(null);
			var m = designs.massing(it.massing().get().id(), it.massing().get().version()).orElse(null);
			if (lot == null || m == null) continue;
			MassingPlacement at = MassingPlacement.on(lot, m.size().x(), m.size().z());
			layers.add(new StewardNet.Layer(m.id() + "@" + m.version(), at.x(), at.y(), at.z(), at.rotation(), "MASSING"));
			lines.add(lot.id() + ": " + lot.role() + ", " + m.size().x() + "x" + m.size().z() + ", " + m.size().y() + " tall"
				+ (m.parts().isEmpty() ? "" : " (" + String.join(", ", m.parts().keySet()) + ")"));
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
		if (state == null) return;
		apply(Pipeline.step(state, e, permission));
	}

	private void execute(Command c) {
		var api = ArchitectApi.get();
		switch (c) {
			case Pipeline.RequestBible b -> {
				say("Designing the style bible...");
				BibleRequest req = new BibleRequest(b.prompt(), b.name(), settlement.owner(), new JsonObject(), null, b.budgetUsd(), List.of(), null, null);
				api.bibles().request(req).whenComplete((job, err) -> {
					if (err != null) feed(new Pipeline.BibleDone(false, null, 0, 0, err.getMessage()));
					else {
						bibleJobId = job.id();
						persist();
						// the job as it stood at the ack: a fast or cached bible may already be done, and BIBLE_DONE fired before we knew its id
						if (job.finished()) onBibleDone(job);
					}
				});
			}
			case Pipeline.RequestGroup g -> {
				GroupPlanner.Options o = new GroupPlanner.Options(g.bibleId(), g.bibleVersion(), g.budgetUsd(), 3, 3, landmarks);
				GroupPlanner.Built built = GroupPlanner.build(settlement, plan, o);
				landmarkIds = built.request().items().stream().filter(i -> i.role() == GroupRequest.Role.LANDMARK).map(GroupRequest.Item::itemKey).collect(Collectors.toSet());
				say("Designing " + built.request().items().size() + " buildings (massings first)...");
				api.designs().requestGroup(built.request()).whenComplete((id, err) -> {
					if (err != null) { say("Group failed: " + err.getMessage()); feed(new Pipeline.Cancel()); }
					else {
						groupId = id;
						persist();
						// catch up on updates that fired before we knew the id (repeats are harmless: approvals are deduplicated)
						api.designs().group(id).ifPresent(this::onGroup);
					}
				});
			}
			case Pipeline.ApproveGroup a -> {
				List<String> approve = a.approve().stream().filter(decided::add).toList();
				Map<String, String> redirect = new LinkedHashMap<>();
				a.redirect().forEach((k, v) -> { if (decided.add(k)) redirect.put(k, v); });
				List<String> cancel = a.cancel().stream().filter(decided::add).toList();
				if (approve.isEmpty() && redirect.isEmpty() && cancel.isEmpty()) break;
				api.designs().approveGroup(a.groupId(), approve, redirect, cancel, settlement.owner()).whenComplete((r, err) -> {
					if (err == null) return;
					say("Architect did not take that decision: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()));
					// automatic approvals are not retried (a refusal would repeat on every update)
					if (!permission.needsApproval(Permission.Action.NEW_PROJECT)) return;
					// the player's decision: forget it and re-read the group, so the pipeline waits for it again instead of moving on
					approve.forEach(decided::remove);
					redirect.keySet().forEach(decided::remove);
					cancel.forEach(decided::remove);
					api.designs().group(a.groupId()).ifPresent(this::onGroup);
				});
			}
			case Pipeline.ExtendAndResumeGroup e -> api.designs().extendGroup(e.groupId(), e.newBudgetUsd()).thenCompose(v -> api.designs().resumeGroup(e.groupId()));
			case Pipeline.CancelGroup g -> api.designs().cancelGroup(g.groupId());
			case Pipeline.FitAndQueue f -> fitAndQueue(f.autoApprove());
			case Pipeline.ApproveStages a -> approveStages();
			case Pipeline.CancelBatch cb -> { if (batchId != null) api.sites(server).cancelBatch(batchId); }
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
		sites.queue(res.batch()).whenComplete((id, err) -> {
			if (err != null) { say("Queue failed: " + err.getMessage()); feed(new Pipeline.Cancel()); return; }
			batchId = id;
			siteGroupId = sites.batch(id).map(BatchView::group).orElse(null);
			feed(new Pipeline.BatchQueued(id));
			// a batch that finished before we knew its id (everything placed in the queue's first pass)
			sites.batch(id).filter(b -> !b.running()).ifPresent(this::onBatchDone);
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
		Map<String, String> items = new LinkedHashMap<>();
		for (Group.Item it : g.items()) {
			String stage = it.stage().map(Group.Stage::wire).orElse(it.status().name().toLowerCase());
			items.put(it.itemKey(), stage);
		}
		lastAwaiting = List.copyOf(g.awaiting());
		String held = g.usageLimitUntil() > 0 ? "until " + new java.util.Date(g.usageLimitUntil()) : null;
		feed(new Pipeline.GroupUpdate(g.id(), g.status().wire(), items, g.awaiting(), g.cost().usd(), held));
	}

	private void onBatchDone(BatchView b) {
		if (batchId == null || !batchId.equals(b.id())) return;
		long placed = b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.PLACED).count();
		long failed = b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.FAILED).count();
		b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.FAILED).forEach(i -> say("Not placed: " + i.itemKey() + " (" + i.reason().map(Enum::name).orElse("?") + ") " + i.message()));
		// every site of the build, street included, so an undo removes all of it
		if (siteGroupId == null) siteGroupId = b.group();
		logPlaced(b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.PLACED).flatMap(i -> i.siteId().stream()).toList(), (int) b.items().stream()
			.filter(i -> i.status() == BatchView.ItemStatus.PLACED && !BatchPlanner.STREET_KEY.equals(i.itemKey())).count());
		batchId = null;
		feed(new Pipeline.BatchDone((int) placed, (int) failed));
		say(String.format("Settlement spend: $%.2f of $%.0f.", state.spentUsd(), state.budgetUsd()));
	}

	private void say(String text) {
		Steward.LOGGER.info("settlement: {}", text);
		ServerPlayer p = server.getPlayerList().getPlayer(playerId);
		if (p != null) p.sendSystemMessage(Component.literal(text));
		else if (unread.size() < 50) unread.add(text);
	}
}
