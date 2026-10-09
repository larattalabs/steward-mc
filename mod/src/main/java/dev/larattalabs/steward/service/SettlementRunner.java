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
import dev.larattalabs.steward.layout.Grid;
import dev.larattalabs.steward.layout.TerrainGrid;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.pipeline.Pipeline;
import dev.larattalabs.steward.pipeline.Pipeline.Command;
import dev.larattalabs.steward.pipeline.Pipeline.Event;
import dev.larattalabs.steward.pipeline.Pipeline.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The live side of the generation pipeline for ONE settlement (a developer-driven runner until the Founding Stone exists): it turns {@link Pipeline}
 * commands into Architect API calls and Architect events into pipeline events. All callbacks arrive on the server thread. Dev scope: in memory only
 * (nothing is persisted), one village street layout, creative worlds (Patron), landmarks configurable.
 */
public final class SettlementRunner {
	private static final String[][] ARCHETYPES = {{"tavern", "22", "18"}, {"house", "14", "13"}, {"shop", "14", "12"}, {"cottage", "13", "12"}, {"smithy", "15", "13"}, {"house", "13", "12"},
		{"cabin", "12", "11"}, {"house", "14", "12"}, {"chapel", "14", "18"}, {"cottage", "12", "12"}, {"shop", "13", "12"}, {"house", "13", "13"}};
	private static final int CLAIM_RADIUS = 64;

	private static final Map<String, SettlementRunner> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();

	/** The runner of a settlement that is being built in this session (not persisted yet), or null. */
	public static SettlementRunner active(String settlementId) {
		return ACTIVE.get(settlementId);
	}

	/** One line for the steward to say: the pipeline phase, what each building is doing and the spend. */
	public String statusLine() {
		if (state == null) return "Getting started.";
		return String.format("%s. Buildings: %s. Spent $%.2f of $%.0f.", state.phase().name().toLowerCase().replace('_', ' '), Pipeline.stageCounts(state), state.spentUsd(), state.budgetUsd());
	}

	private final MinecraftServer server;
	private final ServerLevel level;
	private final ServerPlayer player;
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
	private boolean registered;
	/** lots already approved or redirected: Architect re-sends the awaiting status while an approval is in flight, and a second approve of the same item fails. */
	private final Set<String> decided = new java.util.HashSet<>();

	public SettlementRunner(MinecraftServer server, ServerLevel level, ServerPlayer player, Permission permission, CardService cards, int landmarks) {
		this.server = server;
		this.level = level;
		this.player = player;
		this.permission = permission;
		this.cards = cards;
		this.landmarks = landmarks;
	}

	public State state() {
		return state;
	}

	/** Steps: concept card, survey and layout, then the pipeline. */
	public void start(String prompt, int buildings, double budgetUsd) {
		say("Reading your description...");
		cards.submit(prompt, Map.of(), new ConceptCardJob.Settings(false, "patron", CLAIM_RADIUS), null).whenComplete((r, err) -> {
			if (err != null) { say("Card failed: " + err.getMessage()); return; }
			if (!r.ok()) { say("Card failed: " + r.error()); return; }
			for (String l : CardResult.lines(r.card(), r.cost())) say(l);
			surveyAndStart(r, buildings, budgetUsd);
		});
	}

	/** Dev: continue a paused group from a previous run (the sidecar kept it): rebuild the card from its job, re-survey the same spot, raise the budget and resume. */
	public void resume(String groupIdToResume, String cardJobId, int buildings, double newBudgetUsd) {
		var api = ArchitectApi.get();
		Group g = api.designs().group(groupIdToResume).orElse(null);
		var job = api.jobs().get(cardJobId).orElse(null);
		if (g == null || job == null) { say("Unknown group or card job."); return; }
		CardResult r = CardResult.interpret(job);
		if (!r.ok()) { say("Card job unreadable: " + r.error()); return; }
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
		this.existing = s;
		surveyAndStart(new CardResult(s.card(), null, new dev.larattalabs.architect.api.Cost(0, 0, 0, 0, 0, 0)), buildings, budgetUsd);
	}

	private void surveyAndStart(CardResult r, int buildings, double budgetUsd) {
		int cx = existing != null ? existing.claim().centerX() : player.blockPosition().getX();
		int cz = existing != null ? existing.claim().centerZ() : player.blockPosition().getZ();
		BoundingBox area = new BoundingBox(cx - CLAIM_RADIUS, level.getMinY(), cz - CLAIM_RADIUS, cx + CLAIM_RADIUS, level.getMaxY(), cz + CLAIM_RADIUS);
		say("Surveying the land...");
		ArchitectApi.get().survey().sample(level, area, 1, LoadPolicy.LOADED_ONLY).whenComplete((sample, err) -> {
			if (err != null) { say("Survey failed: " + err.getMessage()); return; }
			Grid grid = TerrainGrid.fromSample(sample);
			int wet = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
			for (int z = grid.z0(); z < grid.z0() + grid.depth(); z++) for (int x = grid.x0(); x < grid.x0() + grid.width(); x++) {
				if (grid.waterAt(x, z)) { wet++; continue; }
				lo = Math.min(lo, grid.heightAt(x, z)); hi = Math.max(hi, grid.heightAt(x, z));
			}
			Steward.LOGGER.info("survey: {}x{} columns, {} unusable (water or unloaded), {} loaded chunks, ground y {}..{}, trees {}", grid.width(), grid.depth(), wet, sample.chunksLoaded(), lo, hi, sample.tree().cardinality());
			Claim claim = new Claim(level.dimension().identifier().toString(), cx, cz, CLAIM_RADIUS, level.getMinY(), level.getMaxY());
			settlement = existing != null ? existing : Settlement.found("set_dev", r.card(), claim, permission, Difficulty.PATRON, System.currentTimeMillis());
			List<VillageLayout.LotSpec> specs = new ArrayList<>();
			for (int i = 0; i < buildings; i++) {
				String[] a = ARCHETYPES[i % ARCHETYPES.length];
				specs.add(new VillageLayout.LotSpec("lot_" + i, a[0], Integer.parseInt(a[1]), Integer.parseInt(a[2]) + dev.larattalabs.steward.gateway.LotBrief.APPROACH_MARGIN));
			}
			plan = VillageLayout.plan(claim, grid, specs, VillageLayout.Rules.defaults());
			say("Layout: " + plan.lots().size() + " lots on the street" + (plan.unplaced().isEmpty() ? "" : " (" + plan.unplaced().size() + " did not fit)"));
			if (plan.lots().isEmpty()) { say("No dry, flat room here. Try another spot."); return; }
			register();
			ACTIVE.put(settlement.id(), this);
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

	private void register() {
		if (registered) return;
		registered = true;
		SiteEvents.BIBLE_DONE.register(this::onBibleDone);
		SiteEvents.GROUP_UPDATED.register(this::onGroup);
		SiteEvents.GROUP_DONE.register(this::onGroup);
		SiteEvents.BATCH_DONE.register(this::onBatchDone);
	}

	private void apply(Pipeline.Step step) {
		state = step.next();
		for (Command c : step.commands()) execute(c);
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
					else bibleJobId = job.id();
				});
			}
			case Pipeline.RequestGroup g -> {
				GroupPlanner.Options o = new GroupPlanner.Options(g.bibleId(), g.bibleVersion(), g.budgetUsd(), 3, 3, landmarks);
				GroupPlanner.Built built = GroupPlanner.build(settlement, plan, o);
				landmarkIds = built.request().items().stream().filter(i -> i.role() == GroupRequest.Role.LANDMARK).map(GroupRequest.Item::itemKey).collect(Collectors.toSet());
				say("Designing " + built.request().items().size() + " buildings (massings first)...");
				api.designs().requestGroup(built.request()).whenComplete((id, err) -> {
					if (err != null) { say("Group failed: " + err.getMessage()); feed(new Pipeline.Cancel()); }
					else groupId = id;
				});
			}
			case Pipeline.ApproveGroup a -> {
				List<String> approve = a.approve().stream().filter(decided::add).toList();
				Map<String, String> redirect = new LinkedHashMap<>();
				a.redirect().forEach((k, v) -> { if (decided.add(k)) redirect.put(k, v); });
				List<String> cancel = a.cancel().stream().filter(decided::add).toList();
				if (approve.isEmpty() && redirect.isEmpty() && cancel.isEmpty()) break;
				api.designs().approveGroup(a.groupId(), approve, redirect, cancel, settlement.owner())
					.whenComplete((r, err) -> { if (err != null) say("Approve failed: " + err.getMessage()); });
			}
			case Pipeline.ExtendAndResumeGroup e -> api.designs().extendGroup(e.groupId(), e.newBudgetUsd()).thenCompose(v -> api.designs().resumeGroup(e.groupId()));
			case Pipeline.CancelGroup g -> api.designs().cancelGroup(g.groupId());
			case Pipeline.FitAndQueue f -> fitAndQueue(f.autoApprove());
			case Pipeline.Notify n -> say(n.text());
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
		for (VillageLayout.Lot lot : plan.lots()) {
			String entry = entries.get(lot.id());
			if (entry == null) continue;
			BoundingBox box = BatchPlanner.lotBox(lot, lot.sizeX() > 20 ? 40 : 24);
			LotFit fit = sites.fitToLot(entry, box, BatchPlanner.streetSide(lot), FitOptions.DEFAULT.withLevel(level));
			fits.put(lot.id(), fit);
			if (!fit.ok()) say("Fit " + lot.id() + " (" + entry + ") refused: " + fit.verdict().refusals().stream().map(r -> r.reason() + " " + r.message()).collect(Collectors.joining("; ")));
		}
		BatchPlanner.Result res = BatchPlanner.build(settlement, plan, landmarkIds, entries, fits, level, false, autoApprove, true);
		if (res.note() != null) say(res.note());
		if (!res.skippedLotIds().isEmpty()) say("Skipped lots (no design or no fit): " + res.skippedLotIds());
		say("Placing " + res.batch().items().size() + " items...");
		sites.queue(res.batch()).whenComplete((id, err) -> {
			if (err != null) { say("Queue failed: " + err.getMessage()); feed(new Pipeline.Cancel()); return; }
			batchId = id;
			feed(new Pipeline.BatchDone(0, 0)); // READY_TO_PLACE -> PLACING
		});
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
		String held = g.usageLimitUntil() > 0 ? "until " + new java.util.Date(g.usageLimitUntil()) : null;
		feed(new Pipeline.GroupUpdate(g.id(), g.status().wire(), items, g.awaiting(), g.cost().usd(), held));
	}

	private void onBatchDone(BatchView b) {
		if (batchId == null || !batchId.equals(b.id())) return;
		long placed = b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.PLACED).count();
		long failed = b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.FAILED).count();
		b.items().stream().filter(i -> i.status() == BatchView.ItemStatus.FAILED).forEach(i -> say("Not placed: " + i.itemKey() + " (" + i.reason().map(Enum::name).orElse("?") + ") " + i.message()));
		batchId = null;
		feed(new Pipeline.BatchDone((int) placed, (int) failed));
		say(String.format("Settlement spend: $%.2f of $%.0f.", state.spentUsd(), state.budgetUsd()));
	}

	private void say(String text) {
		Steward.LOGGER.info("settlement: {}", text);
		player.sendSystemMessage(Component.literal(text));
	}
}
