package dev.larattalabs.steward.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.ComposeJob;
import dev.larattalabs.steward.gateway.VPlan;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * The parts of a settlement's plan that waited for Architect, offered once Architect can build them (docs/COMPOSITION.md section 11; Noah's 9.2: "Walls can
 * be built now: shall I raise the town wall?"). At a world's start the live catalogue ({@code Regions.planCatalogue}, C38) is read, and each waiting part
 * whose block is now supported, in a settlement whose town stands as a region, becomes a proposal. Accepted, Architect's composer writes a plan holding
 * just that part, placed against the town as it stands ({@code Regions.compose} with no template pick, Steward's part rules, its two repair rounds, at
 * most {@link ComposeJob#PART_BUDGET_USD}; slice CA2); Steward checks it again and Architect realises it as a region of its own, recorded like a
 * placement, so an undo takes the part away and leaves the town.
 *
 * <p>Each part is its own proposal ({@code part_<id>}, saved as before), but the inbox shows a settlement's parts as one row, "Waiting parts (N)"
 * ({@link #GROUP_KEY}): building it fits one part ({@link #pick}), and the row offers the rest once that one stands; "Not now" hides the row for the rest of
 * this session (the parts are offered again when the world next starts). Server thread.
 */
public final class PendingOffers {
	/** A part's compose being waited for, by its design id (this world's session): what to do with its plan when it ends. */
	private record Fitting(String settlementId, String part, String block, String region, String what, UUID player, Proposal proposal, int session, long since) {}
	/**
	 * A compose key that adopted a design this client no longer holds (Architect's reconnect snapshot keeps the last finished ones only) never ends here:
	 * after this long without the design, continuously (a reconnect's snapshot can hide a running one for a moment), it stops as a failure, so the
	 * settlement is never left busy (GPT review, CA2). {@code since}: when the design was last seen (or the accept).
	 */
	static final long UNSEEN_MS = 30_000;
	private static final Map<String, Fitting> FITTING = new java.util.concurrent.ConcurrentHashMap<>();
	private static volatile @Nullable MinecraftServer server;
	/** A part's region being placed: read once a second until it is placed, then recorded. */
	private record Watch(String settlementId, String part, String name, UUID player, Proposal proposal) {}
	private static final Map<String, Watch> WATCHING = new HashMap<>();
	/** Settlement and part pairs being written, checked or placed, so one is not started twice (the settlement counts as busy meanwhile). */
	private static final Set<String> BUSY = new HashSet<>();
	/** A checked part plan Architect adjusted, waiting for the player to accept it once more (this world's session only). */
	private record Checked(String artifactSha, String summary, double usd) {}
	private static final Map<String, Checked> CHECKED = new HashMap<>();
	/** Blocks never offered alone: a gate is part of a wall (a gate-only plan has no wall to sit in). */
	static final Set<String> NOT_ALONE = Set.of("connector.gate");
	/** The build tag on a part's region: the world-start sweep takes up one left without a history entry (a stop before it was recorded). */
	static final String TAG = "part:";
	/** The inbox row of a settlement's waiting parts, all together (its accept and decline act on the parts). */
	public static final String GROUP_KEY = dev.larattalabs.steward.inbox.InboxModel.PARTS_KEY;
	/** Parts whose last fitting failed this session: the group's accept tries the others first, so one that will not fit does not block the rest. */
	private static final Set<String> FAILED = new HashSet<>();
	/** Settlements whose waiting parts the player put off ("Not now"): not shown again this session. */
	private static final Set<String> NOT_NOW = new HashSet<>();

	private PendingOffers() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(srv -> server = srv);
		// a part's compose ended: its plan is checked and built (a compose of the town's own plan is Composer's, never one of these)
		dev.larattalabs.architect.api.SiteEvents.DESIGN_DONE.register(d -> {
			MinecraftServer srv = server;
			if (srv != null && FITTING.containsKey(d.id())) srv.execute(() -> composed(srv, d));
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				var said = notice(server, null);
				if (!said.isEmpty()) Steward.LOGGER.info("waiting parts offered: {}", said);
			} catch (RuntimeException | LinkageError e) {
				Steward.LOGGER.warn("could not look for waiting parts Architect can build now", e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			FITTING.clear();
			WATCHING.clear();
			BUSY.clear();
			CHECKED.clear();
			FAILED.clear();
			NOT_NOW.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 != 0) return;
			if (!FITTING.isEmpty()) unseen(server);
			if (WATCHING.isEmpty()) return;
			for (var e : List.copyOf(WATCHING.entrySet())) {
				try {
					watch(server, e.getKey(), e.getValue());
				} catch (RuntimeException ex) {
					Steward.LOGGER.error("could not read the region of {}'s {}", e.getValue().settlementId(), e.getValue().part(), ex);
				}
			}
		});
	}

	/** Whether a part of this settlement is being written, checked or placed (an undo, a description or a build waits for it). */
	public static boolean busy(String settlementId) {
		return BUSY.stream().anyMatch(k -> k.startsWith(settlementId + "/"));
	}

	/**
	 * Why parts are not offered or built for {@code s} now, or null: Architect builds them INSTANT only (K1), so a survival world or a settlement built from
	 * materials waits for RS.
	 */
	static @Nullable String noInstant(MinecraftServer server, Settlement s) {
		if (dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false) || s.difficulty() != dev.larattalabs.steward.model.Difficulty.PATRON) {
			return "it is built from materials here, which parts can't be yet";
		}
		return null;
	}

	/** The live catalogue's states by block id ({"connector.wall": "supported", ...}); empty when this Architect can't say. */
	static Map<String, String> states() {
		Map<String, String> out = new HashMap<>();
		try {
			JsonObject c = ArchitectApi.get().regions().planCatalogue();
			if (c != null && c.has("entries") && c.get("entries").isJsonArray()) {
				for (JsonElement e : c.getAsJsonArray("entries")) {
					if (e.isJsonObject() && e.getAsJsonObject().has("id") && e.getAsJsonObject().has("state")) {
						out.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject().get("state").getAsString());
					}
				}
			}
		} catch (RuntimeException | LinkageError e) {
			Steward.LOGGER.info("Architect's catalogue can't be read ({}): waiting parts are not offered", e.toString());
		}
		return out;
	}

	/** The settlement's town as it stands ({@link TownRegion}: never a district's or a part's own region). */
	static Optional<String> townRegion(Settlement s) {
		return TownRegion.of(s);
	}

	/** The part {@code id} of a plan, from any of its part lists. */
	static Optional<JsonObject> partOf(JsonObject plan, String id) {
		for (String list : List.of("zones", "connectors", "forms", "rules")) {
			if (!plan.has(list) || !plan.get(list).isJsonArray()) continue;
			for (JsonElement e : plan.getAsJsonArray(list)) {
				if (e.isJsonObject() && e.getAsJsonObject().has("id") && id.equals(e.getAsJsonObject().get("id").getAsString())) return Optional.of(e.getAsJsonObject());
			}
		}
		return Optional.empty();
	}

	/** A part's name for the player: its label, else its id in words. */
	static String name(@Nullable JsonObject part, String id) {
		String label = part != null && part.has("label") && part.get("label").isJsonPrimitive() ? part.get("label").getAsString().strip() : "";
		return label.isEmpty() ? "the " + id.replace('_', ' ') : label;
	}

	/** A waiting part's name for the player: its label in the kept plan, else its id in words. */
	public static String label(String settlementId, String part) {
		JsonObject p = PlanStore.get(settlementId).map(e -> {
			try {
				return partOf(JsonParser.parseString(e.plan()).getAsJsonObject(), part).orElse(null);
			} catch (RuntimeException ex) {
				return null;
			}
		}).orElse(null);
		return name(p, part);
	}

	/**
	 * Which of the waiting parts the group's accept fits: one Architect adjusted that waits for its second accept, else the first whose last fitting did not
	 * fail this session, else the first. Null when there is none.
	 */
	static @Nullable String pick(List<String> parts, Set<String> checked, Set<String> failed) {
		for (String p : parts) if (checked.contains(p)) return p;
		for (String p : parts) if (!failed.contains(p)) return p;
		return parts.isEmpty() ? null : parts.get(0);
	}

	/** The open waiting-part proposals of {@code s}, in their order. */
	public static List<Proposal> offered(Settlement s) {
		return s.proposals().open().stream().filter(Proposal::offersPart).toList();
	}

	/** Whether the player put off {@code settlementId}'s waiting parts this session. */
	public static boolean notNow(String settlementId) {
		return NOT_NOW.contains(settlementId);
	}

	/** "Not now" on the group: hidden until the world next starts (nothing is declined for good), unless one is being fitted or built. */
	public static @Nullable String putOff(Settlement s) {
		if (busy(s.id())) return "One is being built now; the rest wait for it.";
		if (offered(s).isEmpty()) return "No parts wait.";
		NOT_NOW.add(s.id());
		return null;
	}

	/** The group's accept: one part fitted and built ({@link #pick}); the row offers the rest once it stands. Null when started, else why not. */
	static @Nullable String acceptGroup(MinecraftServer server, UUID player, Settlement s) {
		List<Proposal> parts = offered(s);
		Set<String> checked = new HashSet<>(), failed = new HashSet<>();
		for (Proposal p : parts) {
			if (CHECKED.containsKey(s.id() + "/" + p.pendingPart())) checked.add(p.pendingPart());
			if (FAILED.contains(s.id() + "/" + p.pendingPart())) failed.add(p.pendingPart());
		}
		String part = pick(parts.stream().map(Proposal::pendingPart).toList(), checked, failed);
		if (part == null) return "No parts wait.";
		return accept(server, player, s, parts.stream().filter(p -> part.equals(p.pendingPart())).findFirst().orElseThrow());
	}

	/**
	 * Offers each waiting part whose block Architect now supports, in each settlement ({@code only}: one of them) whose town stands as a region, once (a part
	 * open, declined or accepted is not offered again). Returns what was offered, a line each.
	 */
	public static List<String> notice(MinecraftServer server, @Nullable String only) {
		Map<String, String> states = states();
		List<String> out = new ArrayList<>();
		if (states.isEmpty()) return out;
		long now = System.currentTimeMillis();
		for (Settlement s : Settlements.store().all()) {
			if (only != null && !only.equals(s.id())) continue;
			var entry = PlanStore.get(s.id());
			if (entry.isEmpty() || entry.get().pending().isEmpty()) continue;
			// a part built and accepted whose drop from the cache was lost (a stop between the two): dropped now
			for (var p : List.copyOf(entry.get().pending())) if (s.proposals().accepted().contains("part_" + p.part())) dropPending(s.id(), p.part());
			entry = PlanStore.get(s.id());
			if (entry.isEmpty() || entry.get().pending().isEmpty()) continue;
			if (townRegion(s).isEmpty() || noInstant(server, s) != null) continue;
			JsonObject plan = JsonParser.parseString(entry.get().plan()).getAsJsonObject();
			List<Proposal> open = new ArrayList<>(s.proposals().open());
			boolean added = false;
			// (S-7c-4) a waiting hidden room is never offered: an inbox item would name it (and a room in a standing build waits, S-7c-8)
			var secrets = Secrets.of(entry.get());
			for (var p : entry.get().pending()) {
				if (!"supported".equals(states.get(p.block())) || !offerable(p.block()) || secrets.hides(p.part())) continue;
				String key = "part_" + p.part();
				if (open.stream().anyMatch(x -> x.key().equals(key)) || s.proposals().declined().contains(key) || s.proposals().accepted().contains(key)) continue;
				String what = name(partOf(plan, p.part()).orElse(null), p.part());
				open.add(Proposal.part(p.part(), Character.toUpperCase(what.charAt(0)) + what.substring(1) + " can be built now", offerWords(s.name()), now));
				out.add(s.id() + ": " + what + " (" + p.block() + ")");
				added = true;
			}
			if (added) {
				var ps = s.proposals();
				var res = Settlements.proposals(s.id(), new Settlement.Proposals(open, ps.declined(), ps.accepted(), ps.lastAt()));
				if (!res.ok()) Steward.LOGGER.warn("could not offer {}'s waiting parts: {}", s.id(), res.error());
			}
		}
		return out;
	}

	/**
	 * A waiting part's offer, for the player: what accepting it does (fitted round the town, built as a part of its own that an undo takes away alone), and
	 * that a part changed to fit is shown and asked about once more before anything is built ({@link #accept}'s second accept).
	 */
	static String offerWords(String settlement) {
		return "Say yes and I fit it round " + settlement + " as it stands (up to $1), then raise it as a part of its own: an undo takes away just this part and leaves the rest"
			+ " of the town standing. If it has to change to fit, I show you the change and ask you once more before anything is built.";
	}

	/**
	 * Accepts an offered part: written, checked and built as its own region. The offer stays in the inbox until the part is built and recorded (a stop on the
	 * way leaves it to accept again); a part Architect adjusted is shown and built on the second accept. Returns null when started, else why not.
	 */
	static @Nullable String accept(MinecraftServer server, UUID player, Settlement s, Proposal p) {
		String part = p.pendingPart();
		var entry = PlanStore.get(s.id());
		if (entry.isEmpty()) return "The settlement's plan is not kept any more: describe it again.";
		var pending = entry.get().pending().stream().filter(x -> x.part().equals(part)).findFirst();
		if (pending.isEmpty()) return "That part no longer waits.";
		JsonObject plan = JsonParser.parseString(entry.get().plan()).getAsJsonObject();
		var partJson = partOf(plan, part);
		if (partJson.isEmpty()) return "That part is not in the kept plan.";
		if (!offerable(pending.get().block()) || Secrets.of(entry.get()).hides(part)) return "It can't be built on its own: it waits for the part it belongs to.";
		if (s.proposals().accepted().contains(p.key())) return "It is built already.";
		// (CA2) a part is composed by Architect's composer with no template pick (CP, 0.71.0): an older Architect fits none, and nothing is spent
		if (!Composer.cpAvailable()) return "Fitting a part needs Architect 0.71.0 or later.";
		String no = noInstant(server, s);
		if (no != null) return "Not yet: " + no + ".";
		if (SettlementRunner.busy(s.id()) || Updates.applying(server, s)) return s.name() + " is busy (a build, an update, an undo or another part); accept it once that is done.";
		var region = townRegion(s);
		if (region.isEmpty()) return "Nothing of " + s.name() + " stands as a region for it to go round.";
		String busy = s.id() + "/" + part;
		String what = name(partJson.get(), part);
		// accepted again after Architect adjusted it: built as shown (its artifact still stands)
		var checked = CHECKED.remove(busy);
		FAILED.remove(busy);
		if (checked != null && ArchitectApi.get().regions().artifact(checked.artifactSha()).map(a -> !a.removed()).orElse(false)) {
			BUSY.add(busy);
			realise(server, player, s, p, what, checked.artifactSha(), checked.summary(), checked.usd());
			return null;
		}
		if (!BUSY.add(busy)) return "It is being made already.";
		Composer.tellNow(server, player, s, "Fitting " + what + " round " + s.name() + " as it stands...");
		// anything thrown before the compose is asked (a request Architect refuses at once) stops it as a failure: the settlement is never left busy
		guarded(server, player, s, p, what, () -> compose(server, player, s, p, pending.get().block(), partJson.get(), entry.get().plan(), region.get(),
			others(server, s, region.get()), what));
		return null;
	}

	/** What stands outside the town's region (buildings added since): a line each, from the stone, for Claude to fit the part round them too. */
	static List<String> others(MinecraftServer server, Settlement s, String townRegion) {
		var api = ArchitectApi.get().sites(server);
		Set<String> town = new HashSet<>(VillageSweep.sitesOf(server, s.owner(), townRegion));
		List<String> out = new ArrayList<>();
		int cx = s.claim().centerX(), cz = s.claim().centerZ();
		for (var v : api.list(s.owner())) {
			if (town.contains(v.id())) continue;
			var b = v.box();
			int dx = (b.minX() + b.maxX()) / 2 - cx, dz = (b.minZ() + b.maxZ()) / 2 - cz;
			out.add("- " + Updates.lotOf(v) + ": " + b.getXSpan() + "x" + b.getZSpan() + ", " + Math.abs(dz) + (dz < 0 ? " north" : " south") + " and " + Math.abs(dx)
				+ (dx < 0 ? " west" : " east") + " of the stone");
			if (out.size() >= 24) break;
		}
		return out;
	}

	/** Why a written plan is not just the part asked for, of its block ({@code connector.wall}: one connector of kind wall), or null. */
	static @Nullable String outOfScope(JsonObject plan, String part, String block) {
		int dot = block.indexOf('.');
		String family = dot < 0 ? "" : block.substring(0, dot), kind = dot < 0 ? block : block.substring(dot + 1);
		String list = switch (family) {
			case "connector" -> "connectors";
			case "form" -> "forms";
			case "rule" -> "rules";
			default -> "zones";
		};
		int n = 0;
		for (String l : List.of("zones", "connectors", "forms", "rules")) {
			if (!plan.has(l)) continue;
			if (!plan.get(l).isJsonArray()) return "its " + l + " is not a list";
			for (JsonElement e : plan.getAsJsonArray(l)) {
				n++;
				if (!e.isJsonObject() || !e.getAsJsonObject().has("id") || !e.getAsJsonObject().get("id").isJsonPrimitive()
					|| !part.equals(e.getAsJsonObject().get("id").getAsString())) return "the plan holds more than " + part + " (only that part is asked for)";
				if (!l.equals(list)) return part + " is in " + l + ", not " + list + " (it is a " + block + ")";
				var k = e.getAsJsonObject().get("kind");
				if (k == null || !k.isJsonPrimitive() || !kind.equals(k.getAsString())) return part + " is not a " + kind + " (it is a " + block + ")";
			}
		}
		return n == 1 ? null : "the plan holds no " + part;
	}

	/**
	 * (CA2) The part composed by Architect ({@link ComposeJob#partRequest}): the part as the brief, the kept plan and the town's region as the hints, no
	 * template pick, Architect's repair rounds. The same accept again (the same part, plan, town and neighbours) adopts the same compose, so it is paid
	 * once; one that did not fit answers the same again at $0 until the town changes.
	 */
	private static void compose(MinecraftServer server, UUID player, Settlement s, Proposal p, String block, JsonObject partJson, String keptPlan, String region,
		List<String> others, String what) {
		int session = Session.current();
		String world = Composer.world(server);
		var level = Districts.level(server, s.claim().dimension());
		if (world == null || level == null) {
			fail(server, player, s, p, what, world == null ? "this world's id could not be saved" : "the settlement's dimension is not loaded");
			return;
		}
		int cx = s.claim().centerX(), cz = s.claim().centerZ(), r = s.claim().radius();
		var claim = new net.minecraft.world.level.levelgen.structure.BoundingBox(cx - r, level.getMinY(), cz - r, cx + r, level.getMaxY(), cz + r);
		var req = ComposeJob.partRequest(world, s.id(), p.pendingPart(), partJson, block, keptPlan, region, townState(server, s, region), others, claim,
			Composer.anchors(server, s, player, level), level, s.owner());
		Steward.LOGGER.info("part {} of {}: compose (key {}, cap ${}, skipPick {}, composeVocab {})", p.pendingPart(), s.id(), req.opKey(), req.budgetUsd(),
			req.ext().get(dev.larattalabs.architect.api.ComposeRequest.EXT_SKIP_PICK), req.ext().get(dev.larattalabs.architect.api.ComposeRequest.EXT_VOCAB));
		ArchitectAccess.api().regions().compose(req).whenComplete((id, err) -> server.execute(() -> guarded(server, player, s, p, what, () -> {
			if (!Session.is(session)) return;
			if (err != null) {
				Throwable c = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
				boolean conflict = c instanceof dev.larattalabs.architect.api.RegionRefused rr && rr.reason() == dev.larattalabs.architect.api.Reason.OP_KEY_CONFLICT;
				fail(server, player, s, p, what, conflict ? "it was fitted already over the land as it was then (nothing more is spent)"
					: "Architect could not compose it (" + RefusalWords.of(err) + ")");
				if (!conflict) Steward.LOGGER.info("compose of part {} for {} failed: {}", p.pendingPart(), s.id(), c.getMessage());
				return;
			}
			Steward.LOGGER.info("part {} of {}: composing as design {}", p.pendingPart(), s.id(), id);
			FITTING.put(id, new Fitting(s.id(), p.pendingPart(), block, region, what, player, p, session, System.currentTimeMillis()));
			// a key that adopted a compose already ended answers at once (its DESIGN_DONE fired before)
			ArchitectAccess.api().designs().get(id).filter(d -> d.status().isFinal()).ifPresent(d -> composed(server, d));
		})));
	}

	/** Fittings whose design this client hasn't seen for {@link #UNSEEN_MS}: stopped (the offer stays; the part still waits). */
	private static void unseen(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (var e : List.copyOf(FITTING.entrySet())) {
			Fitting f = e.getValue();
			if (ArchitectAccess.api().designs().get(e.getKey()).isPresent()) {
				FITTING.replace(e.getKey(), f, new Fitting(f.settlementId(), f.part(), f.block(), f.region(), f.what(), f.player(), f.proposal(), f.session(), now));
				continue;
			}
			if (now - f.since() < UNSEEN_MS) continue;
			FITTING.remove(e.getKey());
			var s = Settlements.store().get(f.settlementId());
			if (s.isEmpty() || !Session.is(f.session())) {
				BUSY.remove(f.settlementId() + "/" + f.part());
				continue;
			}
			fail(server, f.player(), s.get(), f.proposal(), f.what(), "Architect's answer for it (design " + e.getKey() + ") can't be read here");
		}
	}

	/** A part's compose ended (server thread): its plan, checked again by Steward, is built as it fits, shown when Architect adjusted it, else said. */
	private static void composed(MinecraftServer server, dev.larattalabs.architect.api.Design d) {
		if (!d.status().isFinal()) return;
		Fitting f = FITTING.remove(d.id());
		if (f == null || !Session.is(f.session())) return;
		var found = Settlements.store().get(f.settlementId());
		if (found.isEmpty()) {
			BUSY.remove(f.settlementId() + "/" + f.part());
			return;
		}
		Settlement s = found.get();
		Proposal p = f.proposal();
		String what = f.what();
		UUID player = f.player();
		guarded(server, player, s, p, what, () -> {
			// (STORE-FSYNC, batch-076 gate-verifier) settlements disabled for the session: a fitting already in flight is not checked or built
			if (Settlements.disabled()) { fail(server, player, s, p, what, "settlements are disabled for this session (their file could not be saved; see the log)"); return; }
			var o = ComposeJob.interpret(d);
			double cost = o.usd();
			Steward.LOGGER.info("part {} of {}: Architect's compose {} ({} round(s){}), ${}", f.part(), s.id(), o.outcome(), o.rounds(), o.stopped() != null ? ", stopped: " + o.stopped() : "",
				String.format(java.util.Locale.ROOT, "%.3f", cost));
			if (o.kind() != ComposeJob.Kind.PLAN || o.plan() == null) {
				Steward.LOGGER.info("part {} of {}: no plan ({}): {}", f.part(), s.id(), o.error(), o.reason());
				fail(server, player, s, p, what, noPlanWhy(d.result().orElse(null)));
				return;
			}
			JsonObject plan = o.plan();
			// the part's own words for the player: Architect's summary, else its name
			String summary = o.reason().isBlank() ? Character.toUpperCase(what.charAt(0)) + what.substring(1) + "." : o.reason();
			// only the part asked for is ever built (Architect's rounds can't be asked again here: one that wrote more stops)
			String scope = outOfScope(plan, f.part(), f.block());
			if (scope != null) {
				fail(server, player, s, p, what, "Architect's plan for it " + scope);
				return;
			}
			int session = f.session();
			var now = Settlements.store().get(s.id()).orElse(s);
			Composer.check(server, now, plan, player).whenComplete((c, cerr) -> server.execute(() -> guarded(server, player, s, p, what, () -> {
				if (!Session.is(session)) return;
				if (cerr != null) { Steward.LOGGER.warn("part {} of {}: not checked: {}", f.part(), s.id(), cerr.getMessage()); fail(server, player, s, p, what, "Architect could not check it (" + RefusalWords.of(cerr) + ")"); return; }
				var answer = c.parts().stream().filter(x -> x.has("part") && f.part().equals(x.get("part").getAsString())).findFirst();
				String cls = answer.map(x -> x.has("class") ? x.get("class").getAsString() : "").orElse("missing");
				if (c.artifactSha() != null && cls.equals("fits")) {
					realise(server, player, s, p, what, c.artifactSha(), summary, cost);
					return;
				}
				if (c.artifactSha() != null && cls.equals("adjusted")) {
					// Architect changed it to fit: shown, and built when the player accepts it once more
					String how = ChangeWords.of(answer.map(x -> x.get("deviations")).orElse(null));
					CHECKED.put(s.id() + "/" + f.part(), new Checked(c.artifactSha(), summary, cost));
					BUSY.remove(s.id() + "/" + f.part());
					Composer.tellNow(server, player, s, adjustedWords(summary, how));
					return;
				}
				// Architect's own line for the part (CP's waits[].say), else the check's first reason
				String why = o.says().containsKey(f.part()) ? o.says().get(f.part()) : answer.flatMap(x -> x.has("reasons") && x.get("reasons").isJsonArray() && !x.getAsJsonArray("reasons").isEmpty()
					&& x.getAsJsonArray("reasons").get(0).isJsonObject() && x.getAsJsonArray("reasons").get(0).getAsJsonObject().has("message")
					? Optional.of(x.getAsJsonArray("reasons").get(0).getAsJsonObject().get("message").getAsString()) : Optional.empty()).orElse(cls + ", " + c.overall());
				fail(server, player, s, p, what, notYetWords(s.name(), why));
			})));
		});
	}

	/**
	 * The town as it stands now, for the part's compose key: its sites and their versions (a grown or revised town keeps its region id, so the id alone
	 * would meet the old compose). Sorted, so the same town gives the same key.
	 */
	static String townState(MinecraftServer server, Settlement s, String region) {
		try {
			var sites = ArchitectAccess.api().sites(server);
			// the region's own state (its IR and revisions: a tile-only revision changes no site), then its sites
			String head = ArchitectAccess.api().regions().get(region).map(v -> v.irSha() + "#" + v.revisions().size() + "#" + v.state()).orElse("?");
			return head + ";" + VillageSweep.sitesOf(server, s.owner(), region).stream().sorted().map(id -> id + "@" + sites.get(id).map(dev.larattalabs.architect.api.SiteView::version).orElse(0))
				.collect(java.util.stream.Collectors.joining(","));
		} catch (RuntimeException | LinkageError e) {
			return "";
		}
	}

	/** Runs a step of the fitting; anything it throws (an answer of an unexpected shape) stops it as a failure, so the settlement is never left busy. */
	private static void guarded(MinecraftServer server, UUID player, Settlement s, Proposal p, String what, Runnable step) {
		try {
			step.run();
		} catch (RuntimeException e) {
			Steward.LOGGER.warn("part {} of {} stopped", p.pendingPart(), s.id(), e);
			// (raw-sweep) the exception is logged above, never said
			fail(server, player, s, p, what, "its plan could not be read");
		}
	}

	/** The checked part plan realised as a region of its own, then watched until it is placed. */
	private static void realise(MinecraftServer server, UUID player, Settlement s, Proposal p, String what, String artifactSha, String summary, double cost) {
		// (STORE-FSYNC, batch-076 gate-verifier) settlements disabled for the session: nothing is realised, from either caller
		if (Settlements.disabled()) { fail(server, player, s, p, what, "settlements are disabled for this session (their file could not be saved; see the log)"); return; }
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:part", p.pendingPart());
		// tagged as a build of its own: a region left without its history entry (a stop before it was recorded) is taken up at the next start
		ext.addProperty(dev.larattalabs.steward.gateway.BatchPlanner.BUILD_EXT, TAG + s.id() + ":" + p.pendingPart());
		var actor = server.getPlayerList().getPlayer(player);
		int session = Session.current();
		ArchitectApi.get().regions().realiseApproved(new dev.larattalabs.architect.api.ApprovedRealise(artifactSha, dev.larattalabs.architect.api.Mode.INSTANT, actor,
			Map.of(), "steward.part." + s.id() + "." + p.pendingPart(), ext)).whenComplete((id, err) -> server.execute(() -> {
				if (!Session.is(session)) return;
				if (err != null) { Steward.LOGGER.warn("part of {}: not built: {}", s.id(), SettlementRunner.message(err)); fail(server, player, s, p, what, "Architect could not build it (" + RefusalWords.of(err) + ")"); return; }
				Steward.LOGGER.info("part {} of {}: region {} (composing ${})", p.pendingPart(), s.id(), id, String.format("%.3f", cost));
				Composer.tellNow(server, player, s, summary + String.format(java.util.Locale.ROOT, " Raising it now (fitting it cost $%.2f)...", cost));
				WATCHING.put(id, new Watch(s.id(), p.pendingPart(), what, player, p));
			}));
	}

	/**
	 * (batch-077 review P1-1, FENCE-TESTS) Whether a placed part whose history save failed is left standing (settlements disabled: nothing of a take-up
	 * could be logged, so the next session's sweep takes it up) rather than taken up again now.
	 */
	static boolean standsUnrecorded() {
		return Settlements.disabled();
	}

	/** Reads a part's region while it is placed; once placed (wholly or in part) it is recorded under its region, and the part no longer waits. */
	private static void watch(MinecraftServer server, String regionId, Watch w) {
		var v = ArchitectApi.get().regions().get(regionId);
		if (v.isEmpty()) return;
		var st = v.get().state();
		if (st != dev.larattalabs.architect.api.RegionState.PLACED && st != dev.larattalabs.architect.api.RegionState.PARTIAL
			&& st != dev.larattalabs.architect.api.RegionState.FAILED) return;
		WATCHING.remove(regionId);
		var s = Settlements.store().get(w.settlementId());
		if (s.isEmpty()) return;
		List<String> ids = VillageSweep.sitesOf(server, s.get().owner(), regionId);
		if (ids.isEmpty()) {
			fail(server, w.player(), s.get(), w.proposal(), w.name(), "Architect placed nothing of it (" + st + ")");
			return;
		}
		var sites = ArchitectApi.get().sites(server);
		List<Settlement.SiteChange> changes = ids.stream().map(id -> new Settlement.SiteChange(id, w.part(), 0, sites.get(id).map(dev.larattalabs.architect.api.SiteView::version)
			.orElse(1))).toList();
		boolean whole = st == dev.larattalabs.architect.api.RegionState.PLACED;
		for (String line : RegionReports.drained(v.get().ext())) Composer.tellNow(server, w.player(), s.get(), line);
		var entry = Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.PROJECT_PLACED, "Built " + w.name(), changes, VPlan.REGION_PREFIX + regionId,
			whole ? Settlement.Outcome.DONE : Settlement.Outcome.PARTIAL);
		// one commit: the history entry and the offer accepted together (a failure leaves neither, and the region is taken up again)
		var now = Settlements.store().get(w.settlementId()).orElse(s.get());
		var ps = now.proposals();
		List<String> accepted = new ArrayList<>(ps.accepted());
		if (!accepted.contains(w.proposal().key())) accepted.add(w.proposal().key());
		var done = now.withLog(entry).withProposals(new Settlement.Proposals(ps.open().stream().filter(x -> !x.key().equals(w.proposal().key())).toList(), ps.declined(),
			accepted, ps.lastAt()));
		if (!Settlements.replace(done).ok()) {
			// (batch-077 review P1-1) settlements disabled (before, or by this failed save not written back): never taken up now (its removal and refund
			// would go with no history saved); the region stands, tagged as a build of its own, and the next session's sweep takes it up
			if (standsUnrecorded()) {
				Steward.LOGGER.warn("part {} of {}: region {} placed while settlements are disabled: left standing for the next session", w.part(), w.settlementId(), regionId);
				fail(server, w.player(), s.get(), w.proposal(), w.name(), "it was placed, but settlements are disabled this session, so it isn't recorded and"
					+ " stands until the next session");
				return;
			}
			// without its history entry an undo couldn't reach it: taken up again, and it still waits
			VillageSweep.takeUp(server, s.get(), regionId, m -> Steward.LOGGER.info("{}: {}", w.settlementId(), m));
			fail(server, w.player(), s.get(), w.proposal(), w.name(), "the settlement's history could not be saved, so it was taken up again");
			return;
		}
		// the part no longer waits (the kept plan is a cache: an accepted part is also dropped from it at the next start)
		dropPending(w.settlementId(), w.part());
		BUSY.remove(w.settlementId() + "/" + w.part());
		Composer.tellNow(server, w.player(), s.get(), (whole ? "Built " : "Built in part: ") + w.name() + ". An undo takes it away and leaves the town.");
	}

	/** The kept plan's pending list without {@code part}. */
	static void dropPending(String settlementId, String part) {
		PlanStore.get(settlementId).ifPresent(e -> PlanStore.put(new PlanStore.Entry(e.settlementId(), e.plan(), e.overall(), e.artifact(), e.summary(),
			e.pending().stream().filter(x -> !x.part().equals(part)).toList(), e.usd(), e.at(), e.rounds(), e.crossings())));
	}

	/** Whether a waiting part of {@code block} can be offered and built alone: a connector, form or rule (a zone needs a layout), and not a gate. */
	static boolean offerable(String block) {
		return !NOT_ALONE.contains(block) && (block.startsWith("connector.") || block.startsWith("form.") || block.startsWith("rule."));
	}

	/** Whether {@code part} of {@code settlementId} is being written, checked or placed (it can't be declined meanwhile). */
	public static boolean busy(String settlementId, String part) {
		return BUSY.contains(settlementId + "/" + part);
	}

	/** It stops: the offer stays in the inbox, and the part still waits. */
	/** (place-words) Why a part's compose ended with no plan: its typed {@code refused} code in the steward's words, else the generic line. Pure. */
	static String noPlanWhy(com.google.gson.@org.jspecify.annotations.Nullable JsonObject result) {
		String why = RefusalWords.composeRefused(result);
		return why != null ? why : "Architect composed no plan for it";
	}

	private static void fail(MinecraftServer server, UUID player, Settlement s, Proposal p, String what, String why) {
		BUSY.remove(s.id() + "/" + p.pendingPart());
		FAILED.add(s.id() + "/" + p.pendingPart());
		Steward.LOGGER.info("part {} of {}: {}", p.pendingPart(), s.id(), why);
		// the offer stays in the inbox (it was never recorded as accepted): accepted again, it is fitted again
		Composer.tellNow(server, player, s, Character.toUpperCase(what.charAt(0)) + what.substring(1) + " waits: " + why + ".");
	}

	/**
	 * A part changed to fit, for the player: the change in plain words ({@link ChangeWords#of}, never Architect's JSON), and that nothing is built
	 * until it is accepted once more.
	 */
	static String adjustedWords(String summary, String how) {
		return summary + " It had to change to fit" + (how.isEmpty() ? "" : " (" + clip(how, 200) + ")") + ", so nothing is built yet. Accept it once more in the"
			+ " inbox and I'll build it as shown.";
	}

	/**
	 * Why a part doesn't fit yet, after "X waits: ": Architect's own words kept as they are, in brackets (one colon in the line, not two); a full stop
	 * that ends them is left to the sentence's own.
	 */
	static String notYetWords(String settlement, String say) {
		String why = say.strip();
		if (why.endsWith(".")) why = why.substring(0, why.length() - 1).strip();
		return "it doesn't fit round " + settlement + " yet" + (why.isEmpty() ? "" : " (" + why + ")");
	}

	private static String clip(String t, int n) {
		return t.length() <= n ? t : t.substring(0, n - 1) + "…";
	}
}
