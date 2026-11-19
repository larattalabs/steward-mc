package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.DiagnoseScope;
import dev.larattalabs.architect.api.Diagnosis;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.architect.api.RepairResult;
import dev.larattalabs.architect.api.RepairSuspect;
import dev.larattalabs.architect.api.SiteEvents;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.Words;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * (slice DRS) Steward's side of Architect DR (diagnose and repair, Architect 0.78.0): after an unclean stop, the parts of a settlement that Architect's
 * auto-diagnose found stuck ({@code Sites.suspects()}, feature {@code repairSuspects}) are said to the settlement, once each per world session, in the
 * steward's words. Only an operator can repair them ({@code /steward repair <id>}: a look and a dry run, then {@code confirm}), with the op as the actor;
 * anyone else is told an operator can. Steward never repairs on its own. A repair is said once ({@code SITE_REPAIRED} fires once per site: one line per
 * repair). Nothing here charges or refunds; Architect's repair does its own booking.
 */
public final class StuckSites {
	/** Architect's cached auto-diagnose result. */
	public static final String FEATURE = "repairSuspects";
	/** Architect's diagnose and repair calls. */
	public static final String REPAIR_FEATURE = "diagnoseRepair";
	/** How often the suspects are read (cheap: a cached list). */
	static final int EVERY = 200;
	/** At most this many parts named in one line. */
	static final int NAMED = 3;
	/** At most this many diagnose calls per scope (each reads up to 256 chunks; the rest comes as a continuation). */
	static final int MAX_READS = 8;

	/** Suspects said this session ({@link #key}). */
	private static final Set<String> SAID = ConcurrentHashMap.newKeySet();
	/** Repairs said this session (their plan ids): one line per repair, however many sites it repaired. */
	private static final Set<String> REPAIRED = ConcurrentHashMap.newKeySet();
	/** The last look an operator made, by settlement: its plans, for {@code confirm}. */
	private static final Map<String, Look> LOOKS = new ConcurrentHashMap<>();
	/** The settlement each repair this session was asked for (its plan id): a repaired stuck removal's site is gone by its event. */
	private static final Map<String, String> PLAN_OF = new ConcurrentHashMap<>();

	/** An operator's look at a settlement's stuck parts: the plan ids a confirm applies, and who looked. */
	record Look(java.util.UUID op, List<String> plans) {
	}

	private StuckSites() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			SAID.clear();
			REPAIRED.clear();
			LOOKS.clear();
			PLAN_OF.clear();
		});
		try {
			// fired on the server thread, once per repaired site
			SiteEvents.SITE_REPAIRED.register((siteId, regionId, result) -> {
				MinecraftServer srv = server;
				if (srv != null) onRepaired(srv, siteId, regionId, result);
			});
		} catch (LinkageError e) {
			Steward.LOGGER.info("this Architect has no repair events (it needs 0.78.0): stuck builds are not said");
			return;
		}
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (s.getTickCount() % EVERY != 0) return;
			try {
				raise(s);
			} catch (RuntimeException | LinkageError e) {
				Steward.LOGGER.warn("could not read Architect's stuck sites", e);
			}
		});
	}

	private static volatile @Nullable MinecraftServer server;

	// ------------------------------------------------------------------ raising the suspects

	/** Says each settlement's new suspects to it, once each this session. Nothing until the auto-diagnose is done. Server thread. */
	static void raise(MinecraftServer srv) {
		var api = ArchitectAccess.api();
		if (!api.features().contains(FEATURE)) return;
		var sites = api.sites(srv);
		if (sites.suspectsPending()) return;
		List<RepairSuspect> all = sites.suspects();
		if (all.isEmpty()) return;
		Map<String, List<RepairSuspect>> fresh = new LinkedHashMap<>();
		for (RepairSuspect r : all) {
			if (SAID.contains(key(r))) continue;
			String sid = settlementOf(ownerOf(srv, r));
			if (sid == null || Settlements.store().get(sid).isEmpty()) {
				SAID.add(key(r)); // a suspect of no settlement (another mod's site) is looked at once
				continue;
			}
			fresh.computeIfAbsent(sid, k -> new ArrayList<>()).add(r);
		}
		for (var e : fresh.entrySet()) {
			Settlement s = Settlements.store().get(e.getKey()).orElse(null);
			if (s == null) continue;
			List<String> lines = new ArrayList<>();
			for (RepairSuspect r : e.getValue()) lines.add(line(r.reason(), what(srv, r)));
			String text = said(lines, s.id());
			// said once it is heard: with nobody in the claim yet (a world just started) it waits for the next read (the list is cached)
			if (!anyoneIn(srv, s)) continue;
			Steward.LOGGER.info("stuck {}: {}", s.id(), text);
			tell(srv, s, text);
			for (RepairSuspect r : e.getValue()) SAID.add(key(r));
		}
	}

	/** What makes a suspect said once: its site or region, and its reason. Pure. */
	static String key(RepairSuspect r) {
		return (r.siteId() != null ? r.siteId() : "") + "|" + (r.regionId() != null ? r.regionId() : "") + "|" + r.reason();
	}

	/** The Architect owner of a suspect: its site's, else its region's (a stuck removal's site may be gone). */
	private static @Nullable String ownerOf(MinecraftServer srv, RepairSuspect r) {
		var api = ArchitectAccess.api();
		if (r.siteId() != null) {
			var v = api.sites(srv).get(r.siteId());
			if (v.isPresent()) return v.get().owner();
		}
		if (r.regionId() != null) {
			var v = api.regions().get(r.regionId());
			if (v.isPresent()) return v.get().owner();
		}
		return null;
	}

	/** The settlement id an Architect owner string names ({@code steward_mc:settlement/<id>}), or null. Pure. */
	static @Nullable String settlementOf(@Nullable String owner) {
		String prefix = Settlement.ownerOf("");
		return owner == null || !owner.startsWith(prefix) || owner.length() == prefix.length() ? null : owner.substring(prefix.length());
	}

	/** The part a suspect is about, as the steward names it. */
	private static String what(MinecraftServer srv, RepairSuspect r) {
		if (r.siteId() != null) {
			var v = ArchitectAccess.api().sites(srv).get(r.siteId());
			if (v.isPresent()) return "The " + Updates.lotOf(v.get());
		}
		return r.regionId() != null ? "Part of the town" : "A building";
	}

	/** One suspect in the steward's words, by Architect's reason ({@code stuck}, {@code lagging}, {@code custody}, {@code repair_open}, {@code unread}). Pure. */
	static String line(String reason, String what) {
		return switch (reason == null ? "" : reason) {
			case "stuck" -> what + " stopped part way when the world closed suddenly, and looks stuck";
			case "lagging" -> what + " came back with blocks missing after the world closed suddenly";
			case "custody" -> what + " came back with its stored items not accounted for after the world closed suddenly";
			case "repair_open" -> "A repair of " + lower(what) + " was cut off before it finished";
			case "unread" -> what + " couldn't be checked after the world closed suddenly";
			default -> what + " may not have come back right after the world closed suddenly";
		};
	}

	private static String lower(String what) {
		return what.isEmpty() ? what : Character.toLowerCase(what.charAt(0)) + what.substring(1);
	}

	/** The whole line: the first {@link #NAMED} parts, how many more, and that an operator can look it over and repair it. Pure. */
	static String said(List<String> lines, String settlementId) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < lines.size() && i < NAMED; i++) {
			if (i > 0) b.append("; ");
			b.append(lines.get(i));
		}
		if (lines.size() > NAMED) b.append("; and ").append(lines.size() - NAMED).append(lines.size() - NAMED == 1 ? " more part" : " more parts");
		return b.append(". An operator can look it over and repair it: /steward repair ").append(settlementId).append('.').toString();
	}

	private static void tell(MinecraftServer srv, Settlement s, String text) {
		StewardVoice.say(srv, s.id(), text);
		for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
			if (within(s, p)) p.sendSystemMessage(net.minecraft.network.chat.Component.literal("[" + s.name() + "] " + text));
		}
	}

	private static boolean anyoneIn(MinecraftServer srv, Settlement s) {
		for (ServerPlayer p : srv.getPlayerList().getPlayers()) if (within(s, p)) return true;
		return false;
	}

	private static boolean within(Settlement s, ServerPlayer p) {
		var at = p.blockPosition();
		return s.contains(p.level().dimension().identifier().toString(), at.getX(), at.getY(), at.getZ());
	}

	// ------------------------------------------------------------------ the operator's repair

	/** Why {@code op} may not repair, in the steward's words, or null when it may (an operator only; the op is the repair's actor). Pure. */
	static @Nullable String gate(boolean op, String settlementId) {
		return op ? null : "Only an operator can repair a build. Ask one to run /steward repair " + settlementId + ".";
	}

	/** The scopes to look over for a settlement's suspects: each region once, else each site. Pure. */
	static List<DiagnoseScope> scopes(List<RepairSuspect> suspects) {
		java.util.LinkedHashSet<String> regions = new java.util.LinkedHashSet<>();
		java.util.LinkedHashSet<String> sites = new java.util.LinkedHashSet<>();
		for (RepairSuspect r : suspects) {
			if (r.regionId() != null) regions.add(r.regionId());
			else if (r.siteId() != null) sites.add(r.siteId());
		}
		List<DiagnoseScope> out = new ArrayList<>();
		for (String r : regions) out.add(DiagnoseScope.region(r));
		for (String s : sites) out.add(DiagnoseScope.site(s));
		return List.copyOf(out);
	}

	/** A repair's refusal in the steward's words. Pure. */
	static String refused(@Nullable Reason why, String name, String settlementId) {
		if (why == Reason.PERMISSION) return gate(false, settlementId);
		if (why == Reason.REPAIR_STALE) return name + " changed since the look. Run /steward repair " + settlementId + " again to look afresh.";
		if (why == Reason.SITE_BUSY) return "Part of " + name + " is still being worked on. Repair it once that is done: /steward repair " + settlementId + ".";
		return "The repair of " + name + " didn't go through" + (why != null ? " (" + why.name().toLowerCase(java.util.Locale.ROOT) + ")" : "") + ".";
	}

	/** What a dry run would do, in the steward's words. Pure. */
	static String wouldDo(String name, String settlementId, int written, int left, int parts) {
		if (written == 0) return "Nothing of " + name + " can be put back by a repair" + (left > 0 ? " (" + Words.count(left, "block", "blocks") + " there are someone's own changes)" : "") + ".";
		return "A repair of " + name + " would put back " + Words.count(written, "block", "blocks") + (parts > 1 ? " over " + parts + " parts" : "")
			+ (left > 0 ? " and leave " + Words.count(left, "block", "blocks") + " that are someone's own" : "")
			+ ". It is final (no undo). Run /steward repair " + settlementId + " confirm to repair it.";
	}

	/** A finished repair in the steward's words (said once per repair). Pure. */
	static String repairedLine(int written, int left) {
		return "Repaired: " + Words.count(written, "block", "blocks") + " put back where the world lost them"
			+ (left > 0 ? ", " + Words.count(left, "block", "blocks") + " left as someone changed them" : "") + ".";
	}

	/**
	 * {@code /steward repair <id> [confirm]}: open to everyone; anyone but an operator is told an operator can. Without {@code confirm}: diagnoses the
	 * settlement's stuck parts and says what a repair would do (a dry run, nothing written). With it: applies the operator's last look, the op as the actor.
	 * The answers come later, through {@code say}.
	 */
	public static Actions.Result repair(MinecraftServer srv, @Nullable ServerPlayer player, boolean op, String settlementId, boolean confirm, Consumer<String> say) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return Actions.Result.fail("No settlement " + settlementId + ".");
		String no = gate(op && player != null, settlementId);
		if (no != null) return Actions.Result.fail(no);
		var api = ArchitectAccess.api();
		if (!api.features().contains(REPAIR_FEATURE) || !api.features().contains(FEATURE)) return Actions.Result.fail("This Architect can't look over or repair builds (it needs 0.78.0).");
		var sites = api.sites(srv);
		if (sites.suspectsPending()) return Actions.Result.fail("The steward is still looking the world over after its start. Try again in a moment.");
		String name = s.get().name();
		if (confirm) {
			Look look = LOOKS.get(settlementId);
			if (look == null || !look.op().equals(player.getUUID())) return Actions.Result.fail("Look first: /steward repair " + settlementId + ".");
			LOOKS.remove(settlementId);
			for (String plan : look.plans()) PLAN_OF.put(plan, settlementId);
			int session = Session.current();
			apply(sites, look.plans(), player, 0, 0, 0).whenComplete((r, err) -> ArchitectAccess.onServer(srv, () -> {
				if (!Session.is(session) || SettlementRunner.worldStopped(err)) return;
				if (err != null) { say.accept("The repair of " + name + " didn't go through: " + SettlementRunner.message(err)); return; }
				if (r.refused() != null) say.accept(refused(r.refused(), name, settlementId));
				else if (r.written() == 0) say.accept("Nothing of " + name + " needed putting back.");
				// SITE_REPAIRED says it once to the settlement (onRepaired); the op hears it here only from outside the claim
				else if (!within(s.get(), player)) say.accept(repairedLine(r.written(), r.left()));
			}));
			return Actions.Result.ok("Repairing " + name + "...");
		}
		List<RepairSuspect> mine = new ArrayList<>();
		for (RepairSuspect r : sites.suspects()) if (settlementId.equals(settlementOf(ownerOf(srv, r)))) mine.add(r);
		List<DiagnoseScope> scopes = scopes(mine);
		if (scopes.isEmpty()) return Actions.Result.ok("Nothing of " + name + " looks stuck.");
		int session = Session.current();
		CompletableFuture<List<Diagnosis>> chain = CompletableFuture.completedFuture(List.of());
		for (DiagnoseScope sc : scopes) chain = chain.thenCompose(done -> read(sites, sc, 0).thenApply(more -> concat(done, more)));
		chain.thenCompose(ds -> {
			List<String> plans = ds.stream().filter(d -> !d.findings().isEmpty()).map(Diagnosis::planId).toList();
			return dry(sites, plans, player, 0, 0, 0).thenApply(r -> Map.entry(plans, r));
		}).whenComplete((e, err) -> ArchitectAccess.onServer(srv, () -> {
			if (!Session.is(session) || SettlementRunner.worldStopped(err)) return;
			if (err != null) { say.accept("The look over " + name + " didn't go through: " + SettlementRunner.message(err)); return; }
			RepairResult r = e.getValue();
			if (e.getKey().isEmpty()) { say.accept("Nothing of " + name + " lags any more: it came back right after all."); return; }
			if (r.refused() != null) { say.accept(refused(r.refused(), name, settlementId)); return; }
			if (r.written() > 0) LOOKS.put(settlementId, new Look(player.getUUID(), e.getKey()));
			say.accept(wouldDo(name, settlementId, r.written(), r.left(), e.getKey().size()));
		}));
		return Actions.Result.ok("Looking over " + name + "...");
	}

	private static <T> List<T> concat(List<T> a, List<T> b) {
		List<T> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	/** One scope's diagnoses, following its continuation up to {@link #MAX_READS} calls. */
	private static CompletableFuture<List<Diagnosis>> read(dev.larattalabs.architect.api.Sites sites, DiagnoseScope scope, int n) {
		return sites.diagnose(scope).thenCompose(d -> d.continuation() == null || n + 1 >= MAX_READS ? CompletableFuture.completedFuture(List.of(d))
			: read(sites, scope.withContinuation(d.continuation()), n + 1).thenApply(more -> concat(List.of(d), more)));
	}

	/** The plans' dry runs summed; the first refusal stops. */
	private static CompletableFuture<RepairResult> dry(dev.larattalabs.architect.api.Sites sites, List<String> plans, ServerPlayer op, int i, int written, int left) {
		return run(sites, plans, op, false, i, written, left);
	}

	/** The plans applied in turn, summed; the first refusal stops. */
	private static CompletableFuture<RepairResult> apply(dev.larattalabs.architect.api.Sites sites, List<String> plans, ServerPlayer op, int i, int written, int left) {
		return run(sites, plans, op, true, i, written, left);
	}

	private static CompletableFuture<RepairResult> run(dev.larattalabs.architect.api.Sites sites, List<String> plans, ServerPlayer op, boolean apply, int i, int written,
		int left) {
		if (i >= plans.size()) return CompletableFuture.completedFuture(total(plans, apply, written, left, null));
		return sites.repair(plans.get(i), op, apply).thenCompose(r -> r.refused() != null ? CompletableFuture.completedFuture(r)
			: run(sites, plans, op, apply, i + 1, written + r.written(), left + r.left()));
	}

	private static RepairResult total(List<String> plans, boolean applied, int written, int left, @Nullable Reason refused) {
		return new RepairResult(String.join(",", plans), applied, written, Map.of(), List.of(), left, null, refused, "", List.of());
	}

	// ------------------------------------------------------------------ a repair landed

	/** Whether a repair is said now: once per repair (its plan id), however many sites it repaired. */
	static boolean firstOf(String planId) {
		return REPAIRED.add(planId);
	}

	private static void onRepaired(MinecraftServer srv, String siteId, @Nullable String regionId, RepairResult result) {
		if (!firstOf(result.planId())) return;
		// the settlement Steward's own repair was asked for, else the site's or its region's owner (an op's /architect repair)
		String sid = PLAN_OF.get(result.planId());
		if (sid == null) {
			String owner = ArchitectAccess.api().sites(srv).get(siteId).map(dev.larattalabs.architect.api.SiteView::owner).orElse(null);
			if (owner == null && regionId != null) owner = ArchitectAccess.api().regions().get(regionId).map(dev.larattalabs.architect.api.RegionView::owner).orElse(null);
			sid = settlementOf(owner);
		}
		Optional<Settlement> s = Optional.ofNullable(sid).flatMap(id -> Settlements.store().get(id));
		if (s.isEmpty()) return;
		String text = repairedLine(result.written(), result.left());
		Steward.LOGGER.info("repaired {}: {}", s.get().id(), text);
		tell(srv, s.get(), text);
	}

	/** (tests) Forgets this session's lines. */
	static void resetForTest() {
		SAID.clear();
		REPAIRED.clear();
		LOOKS.clear();
		PLAN_OF.clear();
	}
}
