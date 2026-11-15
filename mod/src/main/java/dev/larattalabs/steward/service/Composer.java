package dev.larattalabs.steward.service;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.PlanCheck;
import dev.larattalabs.architect.api.PlanRequest;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.PlanWriterJob;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The composition loop for a described settlement (docs/COMPOSITION.md, plan v0): Claude writes a plan (the plan writer), Architect checks it
 * ({@code Regions.checkPlan}), and while parts are refused or malformed Claude repairs it, up to {@link #ROUNDS} repairs and {@link #CAP_USD} in all
 * (Noah: about $1 a plan). The plan, its answer and the parts that wait are kept ({@link PlanStore}); the steward tells the player the plan and what waits,
 * in Claude's words. Building still takes today's paths (plan v0). Server thread.
 */
public final class Composer {
	static final double CAP_USD = 1.00;
	static final int ROUNDS = 2;
	/** The anchors Steward supplies to a check (plan/0's caller-supplied anchors). */
	static final List<String> ANCHORS = List.of("stone", "player");
	/** Settlements being composed (one at a time each), with the world session they belong to (ids repeat across worlds). */
	private static final Map<String, Integer> COMPOSING = new HashMap<>();

	private Composer() {
	}

	/** (Architect RS, API 1.41) Whether this Architect builds regions from materials: a survival check, a region's build progress. */
	public static boolean survivalAvailable() {
		try {
			return dev.larattalabs.architect.api.PlanCheck.class.getMethod("survival") != null && dev.larattalabs.architect.api.RegionView.class.getMethod("build") != null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}

	/**
	 * A survival check's bill ({@code cost.bom}: item id to count) in words, the largest first: "64 stone bricks, 40 oak planks and 5 more kinds (186 items)";
	 * null when there is none.
	 */
	static @org.jspecify.annotations.Nullable String billLine(@org.jspecify.annotations.Nullable JsonObject cost) {
		if (cost == null || !cost.has("bom") || !cost.get("bom").isJsonObject()) return null;
		List<Map.Entry<String, Integer>> items = new ArrayList<>();
		for (var e : cost.getAsJsonObject("bom").entrySet()) {
			if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber() && e.getValue().getAsInt() > 0) items.add(Map.entry(e.getKey(), e.getValue().getAsInt()));
		}
		if (items.isEmpty()) return null;
		items.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
		int total = items.stream().mapToInt(Map.Entry::getValue).sum();
		List<String> named = new ArrayList<>();
		for (var e : items.subList(0, Math.min(3, items.size()))) named.add(e.getValue() + " " + e.getKey().replaceFirst("^minecraft:", "").replace('_', ' '));
		String more = items.size() > 3 ? " and " + (items.size() - 3) + " more kind" + (items.size() - 3 == 1 ? "" : "s") : "";
		return (more.isEmpty() && named.size() > 1 ? String.join(", ", named.subList(0, named.size() - 1)) + " and " + named.get(named.size() - 1) : String.join(", ", named) + more)
			+ " (" + total + " item" + (total == 1 ? "" : "s") + ")";
	}

	/** Whether this Architect has the plan check (P0, API 1.23): building from a kept plan needs it. */
	public static boolean available() {
		try {
			return ArchitectAccess.api().jobs().available() && ArchitectAccess.api().regions().getClass().getMethod("checkPlan", PlanRequest.class) != null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** (CA) Whether this Architect composes plans ({@code Regions.compose}, feature {@code compose}, 7c CO/AU) and checks them. */
	public static boolean composeAvailable() {
		try {
			return available() && ArchitectAccess.api().features().contains("compose");
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/**
	 * (CA2) Whether the loaded Architect has CP (API 1.63.0, v0.71.0): no template pick, the block families, the player's words, the level and owner on
	 * the request. Steward composes only with it (fabric.mod.json asks for 0.71.0 already; an older one is refused here, nothing spent).
	 */
	public static boolean cpAvailable() {
		try {
			return dev.larattalabs.steward.gateway.ComposeJob.cp(ArchitectAccess.api().apiVersion());
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** What the player is told when the loaded Architect is older than CP: nothing is composed or spent. */
	static final String NEEDS_CP = "A plan is composed only with Architect 0.71.0 or later: the build takes today's layout.";

	/** Settlement designs already taken (world session + design id): a design ends once, but its answer can come both from DESIGN_DONE and a get. */
	private static final java.util.Set<String> HANDLED = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Settlement id to the design whose plan Steward is checking now (between onDone and keep or done). */
	private static final Map<String, String> CHECKING = new java.util.concurrent.ConcurrentHashMap<>();
	private static boolean registered;
	private static volatile @Nullable MinecraftServer server;

	/** Listens for settlement designs ending (a restart's too: the helper carries a compose on, and its request names the settlement). */
	public static void init() {
		if (registered) return;
		registered = true;
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(srv -> server = srv);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(srv -> srv.execute(() -> reconcile(srv)));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
			server = null;
			forgetWorld();
		});
		dev.larattalabs.architect.api.SiteEvents.DESIGN_DONE.register(d -> {
			MinecraftServer srv = server;
			if (srv != null) srv.execute(() -> onDone(srv, d));
		});
	}

	/**
	 * Composes {@code s}'s plan from its card ({@code cardJson}) and the land round its stone through Architect's composer (docs/COMPOSITION.md section 13);
	 * tells {@code player} when it is done. One compose at a time a settlement; the same card again adopts the first compose (its key), so it is paid once.
	 */
	public static void compose(MinecraftServer server, UUID player, Settlement s, String cardJson, @Nullable String land) {
		int session = Session.current();
		if (!composeAvailable() || Integer.valueOf(session).equals(COMPOSING.get(s.id()))) return;
		if (!cpAvailable()) {
			tell(server, player, s, List.of(NEEDS_CP));
			return;
		}
		var level = Districts.level(server, s.claim().dimension());
		if (level == null) return;
		// (CP) the level goes with the request: Architect answers another level than the Overworld NO_PLAN at once, at $0, in its own words (onDone)
		int cx = s.claim().centerX(), cz = s.claim().centerZ(), r = s.claim().radius();
		var claim = new BoundingBox(cx - r, level.getMinY(), cz - r, cx + r, level.getMaxY(), cz + r);
		Map<String, BlockPos> anchors = anchors(server, s, player, level);
		String sha = cardSha(s, cardJson);
		String world = world(server);
		if (world == null) {
			tell(server, player, s, List.of("A plan can't be composed in this world (its id could not be saved): the build takes today's layout."));
			return;
		}
		var req = dev.larattalabs.steward.gateway.ComposeJob.request(s.id(), cardJson, land, claim, anchors, player.toString(), level, s.owner());
		// the key carries this settlement's card sha (not the passed JSON's): the staleness check reads the saved card the same way
		req = new dev.larattalabs.architect.api.ComposeRequest(req.brief(), req.claim(), req.styleId(), req.images(), req.budgetUsd(),
			dev.larattalabs.steward.gateway.ComposeJob.opKey(world + "." + s.id(), sha), withCard(req.ext(), sha, claimKey(s), world), req.level(), req.owner());
		COMPOSING.put(s.id(), session);
		// the old card's plan (if any) must not build this card, whatever this compose comes to
		PlanStore.remove(s.id());
		Steward.LOGGER.info("plan writer: compose (Architect's Regions.compose, key {}, cap ${}, skipPick {}, composeVocab {}, level {}, owner {})", req.opKey(), req.budgetUsd(),
			req.ext().get(dev.larattalabs.architect.api.ComposeRequest.EXT_SKIP_PICK), req.ext().get(dev.larattalabs.architect.api.ComposeRequest.EXT_VOCAB),
			level.dimension().identifier(), req.owner());
		ArchitectAccess.api().regions().compose(req).whenComplete((id, err) -> server.execute(() -> {
			if (!Session.is(session)) return;
			if (err != null) {
				Throwable c = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
				boolean conflict = c instanceof dev.larattalabs.architect.api.RegionRefused rr && rr.reason() == dev.larattalabs.architect.api.Reason.OP_KEY_CONFLICT;
				done(s, conflict ? "This card was composed already, over the land as it was then: describe the settlement again to compose it afresh."
					: "The plan could not be composed (" + c.getMessage() + "): the build takes today's layout.", server, player);
				return;
			}
			Steward.LOGGER.info("plan for {}: composing as design {}", s.id(), id);
			// a key that adopted a compose already ended answers at once (DESIGN_DONE fired before)
			ArchitectAccess.api().designs().get(id).filter(d -> d.status().isFinal()).ifPresent(d -> {
				// taken again even if this session kept it before (the plan was dropped when this compose started), unless it is being checked now
				if (d.id().equals(CHECKING.get(s.id()))) return;
				HANDLED.remove(Session.current() + "#" + d.id());
				onDone(server, d);
			});
		}));
	}

	/** A recording's claim: this far each side of the player (the strange asks were recorded without a settlement). */
	static final int RECORD_RADIUS = 48;

	/**
	 * Dev ({@code /steward dev record_plan}, tools/record-plans.mjs, a paid run Noah approved): Architect's composer on a recorded card over a claim of
	 * {@link #RECORD_RADIUS} round {@code at} in the Overworld, the player's cell as both anchors, under {@code capUsd} (the run's remaining cap, at most a
	 * compose's $1.00). Written to {@code out} once the design ends: {@code {answer: {plan, waits, summary}, outcome, rounds, stopped, missing,
	 * hiddenMissing, usd}} or {@code {error, usd}}; a failed or stopped design's cost is written too.
	 */
	public static void record(MinecraftServer server, String label, String cardJson, BlockPos at, double capUsd, java.nio.file.Path out) {
		var level = server.overworld();
		var claim = new BoundingBox(at.getX() - RECORD_RADIUS, level.getMinY(), at.getZ() - RECORD_RADIUS, at.getX() + RECORD_RADIUS, level.getMaxY(), at.getZ() + RECORD_RADIUS);
		String sha = dev.larattalabs.steward.gateway.ComposeJob.cardSha(cardJson);
		var base = dev.larattalabs.steward.gateway.ComposeJob.request("record_" + label, cardJson, null, claim, Map.of("stone", at, "player", at), null, level, null);
		var ext = base.ext().deepCopy();
		// not a settlement's: no Steward member, so onDone leaves it
		ext.remove(dev.larattalabs.steward.gateway.ComposeJob.EXT_SETTLEMENT);
		var req = new dev.larattalabs.architect.api.ComposeRequest(base.brief(), claim, null, false, Math.min(capUsd, dev.larattalabs.steward.gateway.ComposeJob.BUDGET_USD),
			dev.larattalabs.steward.gateway.ComposeJob.opKey(world(server) + ".record." + label, sha), ext, level, null);
		JsonObject card = com.google.gson.JsonParser.parseString(cardJson).getAsJsonObject();
		java.util.function.Consumer<JsonObject> write = o -> {
			try {
				java.nio.file.Files.createDirectories(out.getParent());
				java.nio.file.Files.writeString(out, new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(o));
			} catch (java.io.IOException e) {
				Steward.LOGGER.warn("record_plan {}: {}", label, e.toString());
			}
			Steward.LOGGER.info("record_plan {}: {}", label, o.has("error") ? o.get("error").getAsString() + String.format(java.util.Locale.ROOT, " ($%.4f)", o.get("usd").getAsDouble())
				: String.format(java.util.Locale.ROOT, "$%.4f", o.get("usd").getAsDouble()));
		};
		ArchitectAccess.api().regions().compose(req).whenComplete((id, err) -> server.execute(() -> {
			if (err != null) {
				JsonObject o = new JsonObject();
				Throwable c = err.getCause() != null ? err.getCause() : err;
				o.addProperty("error", String.valueOf(c.getMessage()));
				// a typed refusal spent nothing; anything else (a lost acknowledgement) may have started spending: counted at its cap, and the run stops
				boolean refused = c instanceof dev.larattalabs.architect.api.RegionRefused || c instanceof IllegalArgumentException;
				o.addProperty("usd", refused ? 0.0 : req.budgetUsd());
				if (!refused) o.addProperty("ambiguous", true);
				write.accept(o);
				return;
			}
			Steward.LOGGER.info("record_plan {}: composing as design {} (cap ${})", label, id, req.budgetUsd());
			java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
			java.util.function.Consumer<dev.larattalabs.architect.api.Design> finish = d -> {
				if (!d.id().equals(id) || !d.status().isFinal() || !once.compareAndSet(false, true)) return;
				write.accept(recorded(d, card));
			};
			dev.larattalabs.architect.api.SiteEvents.DESIGN_DONE.register(d -> server.execute(() -> finish.accept(d)));
			ArchitectAccess.api().designs().get(id).ifPresent(finish);
		}));
	}

	/** A recorded compose as the record tools keep it (the old plan-writer answer's shape, with Architect's outcome). Pure. */
	static JsonObject recorded(dev.larattalabs.architect.api.Design d, JsonObject card) {
		var o = dev.larattalabs.steward.gateway.ComposeJob.interpret(d);
		JsonObject out = new JsonObject();
		out.addProperty("usd", o.usd());
		out.addProperty("outcome", o.outcome());
		out.addProperty("rounds", o.rounds());
		if (o.stopped() != null) out.addProperty("stopped", o.stopped());
		JsonObject plan = o.kind() == dev.larattalabs.steward.gateway.ComposeJob.Kind.PICKED
			? dev.larattalabs.steward.gateway.ComposeJob.pickedPlan(slug(dev.larattalabs.steward.gateway.ComposeJob.str(card, "name")), o.program(), o.params()) : o.plan();
		if (plan == null) {
			out.addProperty("error", o.error() == null ? "no plan" : o.error());
			return out;
		}
		if (o.error() != null) out.addProperty("error", o.error());
		JsonObject answer = new JsonObject();
		answer.add("plan", plan);
		com.google.gson.JsonArray waits = new com.google.gson.JsonArray();
		var r = d.result().orElse(new JsonObject());
		var notes = notes(plan);
		if (r.has("waits") && r.get("waits").isJsonArray()) for (var w : r.getAsJsonArray("waits")) {
			if (!w.isJsonObject() || !w.getAsJsonObject().has("part")) continue;
			String part = w.getAsJsonObject().get("part").getAsString();
			JsonObject x = new JsonObject();
			x.addProperty("part", part);
			x.addProperty("words", o.says().getOrDefault(part, notes.getOrDefault(part, part.replace('_', ' ') + " waits (" + dev.larattalabs.steward.gateway.ComposeJob.str(w.getAsJsonObject(), "class") + ")")));
			waits.add(x);
		}
		answer.add("waits", waits);
		answer.addProperty("summary", o.reason().isBlank() ? summaryOf(plan) : o.reason());
		out.add("answer", answer);
		com.google.gson.JsonArray missing = new com.google.gson.JsonArray();
		dev.larattalabs.steward.gateway.ComposeJob.missing(card, plan).forEach(missing::add);
		out.add("missing", missing);
		com.google.gson.JsonArray left = new com.google.gson.JsonArray();
		o.leftOut().forEach(left::add);
		out.add("leftOut", left);
		out.addProperty("hiddenMissing", dev.larattalabs.steward.gateway.ComposeJob.asksHidden(card) && !dev.larattalabs.steward.gateway.ComposeJob.hasHiddenRoom(plan));
		return out;
	}

	/** The ext with Steward's card sha and claim. */
	private static com.google.gson.JsonObject withCard(com.google.gson.JsonObject ext, String sha, String claim, String world) {
		var e = ext.deepCopy();
		e.addProperty(dev.larattalabs.steward.gateway.ComposeJob.EXT_CARD, sha);
		e.addProperty(EXT_CLAIM, claim);
		e.addProperty(EXT_WORLD, world);
		return e;
	}

	/** The world a compose was asked from ({@link #world}): the helper's designs are the game dir's, shared by every world in it. */
	static final String EXT_WORLD = "steward:world";

	/** Whether the design's request still matches the settlement as it is now: this world, the same card and the same claim. */
	static boolean current(MinecraftServer server, JsonObject ext, @Nullable Settlement now) {
		if (now == null || now.card() == null) return false;
		String sha = ext.has(dev.larattalabs.steward.gateway.ComposeJob.EXT_CARD) ? ext.get(dev.larattalabs.steward.gateway.ComposeJob.EXT_CARD).getAsString() : "";
		String claim = ext.has(EXT_CLAIM) ? ext.get(EXT_CLAIM).getAsString() : "";
		String world = ext.has(EXT_WORLD) ? ext.get(EXT_WORLD).getAsString() : "";
		return world.equals(world(server)) && cardSha(now, "").equals(sha) && claimKey(now).equals(claim);
	}

	/** The claim a compose was over, as Steward names it (dimension, centre, radius): a design over another claim is not kept. */
	static final String EXT_CLAIM = "steward:claim";

	static String claimKey(Settlement s) {
		return s.claim().dimension() + "@" + s.claim().centerX() + "," + s.claim().centerZ() + "r" + s.claim().radius();
	}

	/** The world id file: a random id made once a world, so a new world at the same path (a re-made "New World") is another world. */
	static final String WORLD_ID_FILE = "steward-world-id";
	private static @Nullable String worldId;
	private static java.nio.file.@Nullable Path worldIdAt;

	/**
	 * This world's short name for a compose key (settlement ids and recorded cards repeat across worlds, and Architect's keys are the game dir's):
	 * a random id kept in the world's folder ({@link #WORLD_ID_FILE}), made the first time. Not the path: a world deleted and made again at the same
	 * path would meet its predecessor's keys with another body (OP_KEY_CONFLICT; stub e2e 11-14, whose world is re-made each run).
	 */
	static synchronized @Nullable String world(MinecraftServer server) {
		java.nio.file.Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
		if (worldId != null && dir.equals(worldIdAt)) return worldId;
		java.nio.file.Path f = dir.resolve(WORLD_ID_FILE);
		String id = null;
		try {
			if (java.nio.file.Files.exists(f)) id = java.nio.file.Files.readString(f).strip();
		} catch (java.io.IOException e) {
			Steward.LOGGER.warn("could not read {}: {}", f, e.toString());
		}
		if (id == null || !id.matches("[a-f0-9]{8,32}")) {
			id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
			try {
				java.nio.file.Files.writeString(f, id + "\n");
			} catch (java.io.IOException e) {
				// an id that isn't kept would change at the next start: the compose's key with it, so a retry could pay again. None: nothing is composed
				Steward.LOGGER.warn("could not write {}: {}; no plan is composed in this world", f, e.toString());
				return null;
			}
		}
		worldId = id;
		worldIdAt = dir;
		return id;
	}

	/** The world closed: its id is read again from the next world's folder (a world re-made at the same path has a new one). */
	static synchronized void forgetWorld() {
		worldId = null;
		worldIdAt = null;
	}

	/**
	 * After the world opens, the settlement designs that ended while nothing was listening (a crash between the end and the keep): each is kept once,
	 * as {@link #onDone} keeps one (its kept plan, newer than the design, is the receipt).
	 */
	static void reconcile(MinecraftServer server) {
		try {
			for (var d : ArchitectAccess.api().designs().list(null)) if (d.status().isFinal()) onDone(server, d);
		} catch (RuntimeException | LinkageError e) {
			Steward.LOGGER.info("plan writer: no designs to reconcile ({})", e.toString());
		}
	}

	/** The saved card's sha (the card the settlement holds now), else the passed JSON's. */
	static String cardSha(Settlement s, String cardJson) {
		return dev.larattalabs.steward.gateway.ComposeJob.cardSha(s.card() != null ? Actions.cardJson(s.card()) : cardJson);
	}

	/** Steward's anchors: the stone (its surface), and the player when in the claim (else the stone). */
	static Map<String, BlockPos> anchors(MinecraftServer server, Settlement s, UUID player, net.minecraft.server.level.ServerLevel level) {
		int cx = s.claim().centerX(), cz = s.claim().centerZ();
		BlockPos stone = new BlockPos(cx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz), cz);
		Map<String, BlockPos> anchors = new HashMap<>();
		anchors.put("stone", stone);
		var p = server.getPlayerList().getPlayer(player);
		anchors.put("player", p != null && s.contains(p.level().dimension().identifier().toString(), p.getBlockX(), p.getBlockY(), p.getBlockZ()) ? p.blockPosition() : stone);
		return anchors;
	}

	/**
	 * A settlement design ended (server thread): Steward's compose is kept when its settlement still holds the card it was composed from (a design for
	 * an older card, or a gone settlement, is left: nothing more is spent). A plan (COMPOSED, or NO_PLAN's best round) is checked again by Steward at $0
	 * (its dimension, owner, anchors and load policy, with the light preview), the street fix applied, then kept; a PICKED template is kept as a one-zone
	 * plan; anything else is said.
	 */
	static void onDone(MinecraftServer server, dev.larattalabs.architect.api.Design d) {
		var ext = d.request().has("ext") && d.request().get("ext").isJsonObject() ? d.request().getAsJsonObject("ext") : null;
		if (ext == null || !ext.has(dev.larattalabs.steward.gateway.ComposeJob.EXT_SETTLEMENT)) return;
		// compose's template pick is a REGION design of its own carrying the same ext: only the settlement design's end is the compose's
		if (!dev.larattalabs.steward.gateway.ComposeJob.isCompose(d)) return;
		if (!HANDLED.add(Session.current() + "#" + d.id())) return;
		String sid = ext.get(dev.larattalabs.steward.gateway.ComposeJob.EXT_SETTLEMENT).getAsString();
		var found = Settlements.store().get(sid);
		if (!current(server, ext, found.orElse(null))) {
			Steward.LOGGER.info("plan for {}: design {} was for another world, card or claim (or the settlement is gone): not kept", sid, d.id());
			if (found.isPresent()) COMPOSING.remove(sid, Session.current());
			return;
		}
		Settlement s = found.get();
		// kept already (its plan is newer than the design): the receipt, so a design is kept once across restarts
		if (PlanStore.get(sid).map(e -> e.at() >= d.updatedAt()).orElse(false)) return;
		UUID player;
		try {
			player = UUID.fromString(ext.get(dev.larattalabs.steward.gateway.ComposeJob.EXT_PLAYER).getAsString());
		} catch (RuntimeException e) {
			try {
				player = UUID.fromString(s.owner());
			} catch (RuntimeException e2) {
				player = new UUID(0, 0);
			}
		}
		var o = dev.larattalabs.steward.gateway.ComposeJob.interpret(d);
		Steward.LOGGER.info("plan for {}: Architect's compose {} ({} round(s){}{}), ${}", s.id(), o.outcome(), o.rounds(), o.stopped() != null ? ", stopped: " + o.stopped() : "",
			o.leftOut().isEmpty() ? "" : ", street round left out: " + o.leftOut(), String.format(java.util.Locale.ROOT, "%.3f", o.usd()));
		if (!o.reason().isBlank()) Steward.LOGGER.info("plan for {}: Architect's summary: {}", s.id(), o.reason());
		int session = Session.current();
		JsonObject plan;
		String summary;
		switch (o.kind()) {
			case NONE -> {
				// (CP) a compose refused at once says why in Architect's words ("regions can only be built in the Overworld for now")
				done(s, o.reason().isBlank() ? "The plan could not be composed (" + o.error() + "): the build takes today's layout."
					: "No plan was composed: " + o.reason() + ". The build takes today's layout.", server, player);
				return;
			}
			// a compose asked before CA2 (with Architect's template pick) that ends after the update: its template is kept as a one-zone plan
			case PICKED -> {
				plan = dev.larattalabs.steward.gateway.ComposeJob.pickedPlan(slug(s.name()), o.program(), o.params());
				summary = "Architect matched its " + o.program().replace('_', ' ') + " layout to the card: the layout places its own buildings, not the card's list.";
			}
			default -> {
				plan = o.plan();
				summary = o.reason();
			}
		}
		withGateMechanisms(plan);
		final UUID who = player;
		Composed composed = new Composed(o, summary, d.request().has("ext") ? ext : new JsonObject());
		CHECKING.put(s.id(), d.id());
		check(server, s, plan, who).whenComplete((c, cerr) -> server.execute(() -> {
			if (!Session.is(session)) return;
			if (cerr != null) {
				done(s, "Architect could not check the plan (" + cerr.getMessage() + "): the build takes today's layout.", server, who);
				return;
			}
			checked(server, who, s, session, plan, composed, c);
		}));
	}

	/** What Architect's compose gave: its outcome, the summary to show, and the request's ext (its hints hold the card). */
	record Composed(dev.larattalabs.steward.gateway.ComposeJob.Outcome outcome, String summary, JsonObject ext) {
		@Nullable JsonObject card() {
			var h = ext.has(dev.larattalabs.architect.api.ComposeRequest.EXT_HINTS) && ext.get(dev.larattalabs.architect.api.ComposeRequest.EXT_HINTS).isJsonObject()
				? ext.getAsJsonObject(dev.larattalabs.architect.api.ComposeRequest.EXT_HINTS) : null;
			return h != null && h.has("card") && h.get("card").isJsonObject() ? h.getAsJsonObject("card") : null;
		}
	}

	/** Architect GM's portcullis default on the composed plan (Steward's extension, CONTRACT CO), applied before Steward's own check. */
	static void withGateMechanisms(JsonObject plan) {
		dev.larattalabs.steward.gateway.PlanWriterJob.gateMechanisms(plan);
	}

	static String slug(String name) {
		String s = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		return s.isEmpty() ? "town" : s.length() > 48 ? s.substring(0, 48) : s;
	}

	/**
	 * After Steward's check, the plan is kept. Architect's compose did the paid repairs and (CP) its own $0 street round, so Steward runs neither; the lots
	 * that street round dropped are the result's {@code leftOut}.
	 */
	private static void checked(MinecraftServer server, UUID player, Settlement s, int session, JsonObject plan, Composed composed, PlanCheck c) {
		// the card, claim or world may have changed during the checks: kept only while the design still matches
		var now = Settlements.store().get(s.id()).orElse(null);
		if (!current(server, composed.ext(), now)) {
			Steward.LOGGER.info("plan for {}: the settlement changed during the check: not kept", s.id());
			COMPOSING.remove(s.id(), Session.current());
			CHECKING.remove(s.id());
			return;
		}
		keep(server, player, now, plan, composed, c, composed.outcome().leftOut());
	}

	/** Architect's check of {@code plan} over {@code s}'s claim, the stone and (when in the claim) the player as anchors. */
	static java.util.concurrent.CompletableFuture<PlanCheck> check(MinecraftServer server, Settlement s, JsonObject plan, UUID player) {
		var level = Districts.level(server, s.claim().dimension());
		if (level == null) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("the settlement's dimension is not loaded"));
		int cx = s.claim().centerX(), cz = s.claim().centerZ(), r = s.claim().radius();
		var claim = new BoundingBox(cx - r, level.getMinY(), cz - r, cx + r, level.getMaxY(), cz + r);
		Map<String, BlockPos> anchors = anchors(server, s, player, level);
		// a part whose check reads the rock under the land (a tunnel, roots, stalactites: the catalogue's limits.volume, S-F3-3) may load (and generate) the
		// chunks it needs (T3, S-T3-6): else an unloaded volume answers incomplete VOLUME
		var load = needsVolume(plan, volumeBlocks()) ? dev.larattalabs.architect.api.LoadPolicy.LOAD_BOUNDED(TUNNEL_CHUNKS) : null;
		return ArchitectAccess.api().regions().checkPlan(new PlanRequest(plan, level, claim, anchors, s.owner(), null, null, null, null, new JsonObject(), load));
	}

	/** At most this many chunks a check of a plan with a tunnel loads for its survey and volume pass (Architect's caps bound the cells as well). */
	static final int TUNNEL_CHUNKS = 512;

	/** The catalogue's block ids whose check reads a frozen volume ({@code limits.volume: true}, Architect F3's S-F3-3); empty when this Architect can't say. */
	static java.util.Set<String> volumeBlocks() {
		java.util.Set<String> out = new java.util.HashSet<>();
		try {
			JsonObject c = ArchitectAccess.api().regions().planCatalogue();
			if (c != null && c.has("entries") && c.get("entries").isJsonArray()) {
				for (var e : c.getAsJsonArray("entries")) {
					if (!e.isJsonObject() || !e.getAsJsonObject().has("id") || !e.getAsJsonObject().has("limits") || !e.getAsJsonObject().get("limits").isJsonObject()) continue;
					var v = e.getAsJsonObject().getAsJsonObject("limits").get("volume");
					if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean()) out.add(e.getAsJsonObject().get("id").getAsString());
				}
			}
		} catch (RuntimeException | LinkageError e) {
			// an Architect without the live catalogue: the tunnel rule below
		}
		return out;
	}

	/** The plan sections whose parts' kinds name a catalogue block as {@code <prefix><kind>}. */
	private static final Map<String, String> SECTIONS = Map.of("connectors", "connector.", "forms", "form.");

	/**
	 * Whether a plan's check reads the rock under the land: a part whose block the catalogue flags ({@code volume}); with no flags (an Architect before F3), a
	 * tunnel.
	 */
	static boolean needsVolume(JsonObject plan, java.util.Set<String> volume) {
		if (volume.isEmpty()) return hasTunnel(plan);
		for (var sec : SECTIONS.entrySet()) {
			if (!plan.has(sec.getKey()) || !plan.get(sec.getKey()).isJsonArray()) continue;
			for (var p : plan.getAsJsonArray(sec.getKey())) {
				if (p.isJsonObject() && p.getAsJsonObject().has("kind") && p.getAsJsonObject().get("kind").isJsonPrimitive()
					&& volume.contains(sec.getValue() + p.getAsJsonObject().get("kind").getAsString())) return true;
			}
		}
		return false;
	}

	/** Whether a plan holds a tunnel (before F3's catalogue flag, the only part whose check read the rock under the land). */
	static boolean hasTunnel(JsonObject plan) {
		if (!plan.has("connectors") || !plan.get("connectors").isJsonArray()) return false;
		for (var c : plan.getAsJsonArray("connectors")) {
			if (c.isJsonObject() && c.getAsJsonObject().has("kind") && "tunnel".equals(c.getAsJsonObject().get("kind").getAsString())) return true;
		}
		return false;
	}

	/**
	 * (Architect close-out A) A part blocked because it ends on a lot or district its zone couldn't place ({@code DEPENDENCY {kind: unplaced}}): that piece in
	 * words ("market hall 1"); else null.
	 */
	static @Nullable String unplacedEnd(JsonObject part) {
		if (!part.has("reasons") || !part.get("reasons").isJsonArray()) return null;
		for (var r : part.getAsJsonArray("reasons")) {
			if (!r.isJsonObject() || !r.getAsJsonObject().has("evidence") || !r.getAsJsonObject().get("evidence").isJsonObject()) continue;
			var ev = r.getAsJsonObject().getAsJsonObject("evidence");
			if (!ev.has("kind") || !"unplaced".equals(ev.get("kind").getAsString())) continue;
			String piece = ev.has("lot") ? ev.get("lot").getAsString() : ev.has("district") ? ev.get("district").getAsString() : null;
			if (piece != null) return piece.replace('_', ' ');
		}
		return null;
	}

	/**
	 * Keeps the plan and tells the player: the summary, then the buildings left out for room ({@code leftOut}), then what waits in Claude's words, then what
	 * still didn't fit in Architect's.
	 */
	private static void keep(MinecraftServer server, UUID player, Settlement s, JsonObject plan, Composed composed, PlanCheck c, List<String> leftOut) {
		double cost = composed.outcome().usd();
		// the paid repairs (Architect's $0 street rounds aren't repairs)
		int rounds = composed.outcome().repairs();
		// a waiting part's words for the player: Architect's (CP's waits[].say, $0 from its check), else the part's own notes (a design from before CP)
		Map<String, String> words = new HashMap<>(notes(plan));
		words.putAll(composed.outcome().says());
		List<PlanStore.Pending> pending = new ArrayList<>();
		List<String> unfit = new ArrayList<>();
		// (S-7c-4) the plan's hidden rooms are never named to the player: kept (a waiting one stays pending, so the build doesn't require it), never said
		Secrets secrets = Secrets.of(plan, artifactBody(c));
		boolean secretBuilt = false;
		for (JsonObject part : c.parts()) {
			String id = part.has("part") ? part.get("part").getAsString() : "?";
			boolean required = part.has("required") && part.get("required").getAsBoolean();
			if (secrets.hides(id)) {
				switch (cls(part)) {
					case "unsupported", "blocked" -> pending.add(new PlanStore.Pending(id, part.has("block") ? part.get("block").getAsString() : "", required, ""));
					case "fits", "adjusted" -> secretBuilt = true;
					default -> {
					}
				}
				continue;
			}
			switch (cls(part)) {
				// an unsupported kind waits for Architect (in the part's own words when it has them); a blocked part waits on another part
				case "unsupported" -> pending.add(new PlanStore.Pending(id, part.has("block") ? part.get("block").getAsString() : "", required,
					words.getOrDefault(id, id.replace('_', ' ') + " waits for a later Architect release")));
				case "blocked" -> pending.add(new PlanStore.Pending(id, part.has("block") ? part.get("block").getAsString() : "", required,
					unplacedEnd(part) != null ? id.replace('_', ' ') + " waits on " + unplacedEnd(part) + ", which there wasn't room to place"
						: composed.outcome().says().getOrDefault(id, id.replace('_', ' ') + " waits on another part of the plan that can't be built yet")));
				// the part by Architect's name for it (CP's waits[].say, "<label> didn't fit here", under "Didn't fit here:"), else its id; with its reason code
				case "refused", "malformed" -> unfit.add(composed.outcome().says().containsKey(id)
					? composed.outcome().says().get(id).replaceFirst(" didn't fit here$", "") + (code(part) != null ? " (" + code(part) + ")" : "")
					: id.replace('_', ' ') + (code(part) != null ? " (" + code(part) + ")" : ""));
				// (CP's "couldn't be checked yet") a part the check stopped on is said, never silently left out
				case "incomplete" -> unfit.add(composed.outcome().says().getOrDefault(id, id.replace('_', ' ') + " (not checked yet)"));
				default -> {
				}
			}
		}
		// (never drop) what the card asked for that the plan has no part for: a building by its role, something hidden only as "one part"
		JsonObject card = composed.card();
		List<String> missing = card == null ? List.of() : dev.larattalabs.steward.gateway.ComposeJob.missing(card, plan);
		boolean hiddenMissing = card != null && dev.larattalabs.steward.gateway.ComposeJob.asksHidden(card) && !dev.larattalabs.steward.gateway.ComposeJob.hasHiddenRoom(plan);
		// Claude's summary and what waits, without a sentence that names a secret. A card asking for something hidden that the plan doesn't write gets the
		// plan's own plain summary instead of Claude's, which may describe it as built (hidden_village, 11-13); what is missing is said after it.
		// (CP) result.summary, COMPOSED's and NO_PLAN's alike; a plain one from the plan only when no round answered
		String told = composed.summary().isBlank() || hiddenMissing ? summaryOf(plan) : composed.summary();
		List<String> honest = new ArrayList<>();
		if (!missing.isEmpty()) honest.add("Not in the plan: " + words(missing.stream().map(m -> secrets.scrub(m).isBlank() || dev.larattalabs.steward.gateway.ComposeJob.secretish(m)
			? "one part of the ask" : m).distinct().toList()) + ".");
		if (hiddenMissing) honest.add("One part of the ask isn't in the plan yet.");
		if ("oscillation".equals(composed.outcome().stopped())) honest.add("Architect stopped repairing the plan: the same parts kept coming back unfit.");
		if ("budget".equals(composed.outcome().error())) honest.add("Architect stopped at the plan's spending cap.");
		String summary = (secrets.scrub(told) + (honest.isEmpty() ? "" : " " + String.join(" ", honest))).strip();
		pending.replaceAll(x -> secrets.hides(x.part()) ? x : new PlanStore.Pending(x.part(), x.block(), x.required(), secrets.scrub(x.words()).isBlank()
			? x.part().replace('_', ' ') + " waits for a later Architect release" : secrets.scrub(x.words())));
		PlanStore.put(new PlanStore.Entry(s.id(), plan.toString(), c.overall().name(), c.artifactSha(), summary, List.copyOf(pending), cost, System.currentTimeMillis(), rounds,
			crossings(c.preview())));
		Steward.LOGGER.info("plan for {}: {} after {} repair(s), {} pending, {} unfit, ${}", s.id(), c.overall(), rounds, pending.size(), unfit.size(),
			String.format("%.3f", cost));
		List<String> out = new ArrayList<>();
		// what composing cost, with the card's cost already shown: the player asked for it, so it is theirs, not the steward's weekly allowance
		if (!summary.isBlank()) out.add("The plan: " + summary + String.format(java.util.Locale.ROOT, " (composing it cost $%.2f)", cost));
		if (!leftOut.isEmpty()) out.add("Left out for room (laid out on a street instead): " + words(leftOut) + ".");
		if (!missing.isEmpty() || hiddenMissing) Steward.LOGGER.info("plan for {}: not in the plan: {}{}", s.id(), missing, hiddenMissing ? " and the hidden part" : "");
		for (var p : shown(pending, secrets)) out.add("Not yet: " + p.words());
		if (!unfit.isEmpty()) out.add("Didn't fit here: " + String.join(", ", unfit) + ".");
		if (secretBuilt) out.add(Secrets.HINT);
		CHECKING.remove(s.id());
		COMPOSING.remove(s.id(), Session.current());
		tell(server, player, s, out);
		// a card screen open on it shows the plan now
		var p = server.getPlayerList().getPlayer(player);
		if (p != null) Settlements.store().get(s.id()).ifPresent(now -> Actions.sendCard(p, now, true));
	}

	/** The plan's parts' own notes, by part id (every section): a waiting part's words for the player. Pure. */
	static Map<String, String> notes(JsonObject plan) {
		Map<String, String> out = new HashMap<>();
		for (var e : plan.entrySet()) {
			if (!e.getValue().isJsonArray()) continue;
			for (var p : e.getValue().getAsJsonArray()) {
				if (!p.isJsonObject()) continue;
				JsonObject o = p.getAsJsonObject();
				if (o.has("id") && o.get("id").isJsonPrimitive() && o.has("notes") && o.get("notes").isJsonPrimitive() && !o.get("notes").getAsString().isBlank())
					out.put(o.get("id").getAsString(), o.get("notes").getAsString().strip());
			}
		}
		return out;
	}

	/**
	 * A plain summary from the plan itself, for a compose with none in the composer's words (NO_PLAN, a picked template): its buildings by label or
	 * type, the most first ("A settlement of 10 buildings: 4 hidden dwellings, 2 cottage homes and 4 more."). Pure.
	 */
	static String summaryOf(JsonObject plan) {
		Map<String, Integer> n = new java.util.LinkedHashMap<>();
		if (plan.has("zones") && plan.get("zones").isJsonArray()) for (var z : plan.getAsJsonArray("zones")) {
			if (!z.isJsonObject() || !z.getAsJsonObject().has("program") || !z.getAsJsonObject().get("program").isJsonObject()) continue;
			var prog = z.getAsJsonObject().getAsJsonObject("program");
			List<com.google.gson.JsonArray> lots = new ArrayList<>();
			if (prog.has("lots") && prog.get("lots").isJsonArray()) lots.add(prog.getAsJsonArray("lots"));
			if (prog.has("params") && prog.get("params").isJsonObject() && prog.getAsJsonObject("params").has("lots") && prog.getAsJsonObject("params").get("lots").isJsonArray())
				lots.add(prog.getAsJsonObject("params").getAsJsonArray("lots"));
			if (prog.has("districts") && prog.get("districts").isJsonArray()) for (var d : prog.getAsJsonArray("districts"))
				if (d.isJsonObject() && d.getAsJsonObject().has("lots") && d.getAsJsonObject().get("lots").isJsonArray()) lots.add(d.getAsJsonObject().getAsJsonArray("lots"));
			for (var a : lots) for (var l : a) {
				if (!l.isJsonObject()) continue;
				String name = str(l.getAsJsonObject(), "label").isEmpty() ? str(l.getAsJsonObject(), "type").replace('_', ' ') : str(l.getAsJsonObject(), "label");
				if (!name.isEmpty()) n.merge(name, 1, Integer::sum);
			}
		}
		int total = n.values().stream().mapToInt(Integer::intValue).sum();
		if (total == 0) return "";
		List<Map.Entry<String, Integer>> es = new ArrayList<>(n.entrySet());
		es.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
		List<String> named = new ArrayList<>();
		int shown = 0;
		for (var e : es.subList(0, Math.min(3, es.size()))) {
			named.add(e.getValue() + " " + e.getKey());
			shown += e.getValue();
		}
		if (total > shown) named.add((total - shown) + " more");
		// two sentences: the scrub (S-7c-4) can drop the one naming a secret's host, never the count (paid proof 11-14: an empty summary)
		return "A settlement of " + total + " building" + (total == 1 ? "" : "s") + ". Among them: " + words(named) + ".";
	}

	/** The waiting parts the player is told of: all but the secret ones (S-7c-4). */
	public static List<PlanStore.Pending> shown(List<PlanStore.Pending> pending, Secrets secrets) {
		return secrets.any() ? pending.stream().filter(p -> !secrets.hides(p.part())).toList() : pending;
	}

	/** The check's approved artifact's body (its {@code $.secrets[]}); null without one, or from an Architect that can't say. */
	static @Nullable JsonObject artifactBody(PlanCheck c) {
		if (c.artifactSha() == null) return null;
		try {
			return ArchitectAccess.api().regions().artifact(c.artifactSha()).map(a -> a.body()).orElse(null);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static void done(Settlement s, String why, MinecraftServer server, UUID player) {
		COMPOSING.remove(s.id(), Session.current());
		CHECKING.remove(s.id());
		Steward.LOGGER.info("plan for {}: {}", s.id(), why);
		tell(server, player, s, List.of(why));
	}

	/** One line to the player and from the steward (as a plan's own lines). */
	static void tellNow(MinecraftServer server, UUID player, Settlement s, String line) {
		tell(server, player, s, List.of(line));
	}

	private static void tell(MinecraftServer server, UUID player, Settlement s, List<String> lines) {
		var p = server.getPlayerList().getPlayer(player);
		for (String l : lines) {
			if (p != null) p.sendSystemMessage(net.minecraft.network.chat.Component.literal("[" + s.name() + "] " + l));
		}
		if (!lines.isEmpty()) StewardVoice.say(server, s.id(), lines.get(0));
	}

	/**
	 * The plan's bridges and tunnels as Architect resolved them (the light preview's siteplan edges with a {@code crossing}: {type, form, over, span or
	 * length, lands}), a line each: "a stone arch over water, 14 long, to gate east_gate"; a drawbridge (movable) as "a drawbridge over water, 11 long".
	 */
	static List<String> crossings(@org.jspecify.annotations.Nullable JsonObject preview) {
		List<String> out = new ArrayList<>();
		if (preview == null || !preview.has("siteplan") || !preview.get("siteplan").isJsonObject()) return out;
		var sp = preview.getAsJsonObject("siteplan");
		if (!sp.has("edges") || !sp.get("edges").isJsonArray()) return out;
		for (var e : sp.getAsJsonArray("edges")) {
			if (!e.isJsonObject() || !e.getAsJsonObject().has("crossing") || !e.getAsJsonObject().get("crossing").isJsonObject()) continue;
			JsonObject x = e.getAsJsonObject().getAsJsonObject("crossing");
			// (Architect GM-c) a drawbridge's crossing is a bridge with deck: trapdoor and movable: true
			boolean draw = x.has("movable") && x.get("movable").isJsonPrimitive() && x.get("movable").getAsBoolean();
			String type = draw ? "drawbridge" : str(x, "type").replace('_', ' ');
			String form = draw ? "" : str(x, "form");
			StringBuilder b = new StringBuilder("a ");
			if (!form.isEmpty() && !type.contains(form)) b.append(form.replace('_', ' ')).append(' ');
			b.append(type.isEmpty() ? "crossing" : type);
			if (!str(x, "over").isEmpty()) b.append(" over ").append(str(x, "over").replace('_', ' '));
			if (!str(x, "through").isEmpty()) b.append(" through ").append(str(x, "through").replace('_', ' '));
			String len = !str(x, "span").isEmpty() ? str(x, "span") : str(x, "length");
			if (!len.isEmpty()) b.append(", ").append(len).append(" long");
			if (!str(x, "lands").isEmpty()) b.append(", to ").append(str(x, "lands").replace('_', ' '));
			String line = b.toString();
			// "an arch bridge", "a tunnel"
			if (line.length() > 2 && "aeiou".indexOf(line.charAt(2)) >= 0) line = "an " + line.substring(2);
			out.add(line);
			if (out.size() >= 8) break;
		}
		return out;
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}

	/** The check's answers, one line a part, for a repair round. */
	static String answerLines(PlanCheck c) {
		StringBuilder b = new StringBuilder();
		for (JsonObject p : c.parts()) b.append("- ").append(p.toString()).append('\n');
		return b.toString();
	}

	/** "a, b and c". */
	static String words(List<String> items) {
		return items.size() < 2 ? String.join("", items) : String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
	}

	/** A part's first reason code; else null. */
	private static @Nullable String code(JsonObject part) {
		if (!part.has("reasons") || !part.get("reasons").isJsonArray() || part.getAsJsonArray("reasons").isEmpty()) return null;
		var r = part.getAsJsonArray("reasons").get(0);
		return r.isJsonObject() && r.getAsJsonObject().has("code") ? r.getAsJsonObject().get("code").getAsString() : null;
	}

	private static String cls(JsonObject part) {
		return part.has("class") ? part.get("class").getAsString() : "";
	}

	private static long count(PlanCheck c, String cls) {
		return c.parts().stream().filter(p -> cls.equals(cls(p))).count();
	}
}
