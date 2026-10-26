package dev.larattalabs.steward.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.PlanWriterJob;
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
 * whose block is now supported, in a settlement whose town stands as a region, becomes a proposal. Accepted, Claude writes a plan holding just that part,
 * placed against the town as it stands (the plan writer, at most {@link #ROUNDS} repairs and {@link #CAP_USD}); Architect checks it and realises it as a
 * region of its own, recorded like a placement, so an undo takes the part away and leaves the town.
 *
 * <p>Each part is its own proposal ({@code part_<id>}, saved as before), but the inbox shows a settlement's parts as one row, "Waiting parts (N)"
 * ({@link #GROUP_KEY}): building it fits one part ({@link #pick}), and the row offers the rest once that one stands; "Not now" hides the row for the rest of
 * this session (the parts are offered again when the world next starts). Server thread.
 */
public final class PendingOffers {
	static final double CAP_USD = 1.00;
	static final int ROUNDS = 2;
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
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				var said = notice(server, null);
				if (!said.isEmpty()) Steward.LOGGER.info("waiting parts offered: {}", said);
			} catch (RuntimeException | LinkageError e) {
				Steward.LOGGER.warn("could not look for waiting parts Architect can build now", e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			WATCHING.clear();
			BUSY.clear();
			CHECKED.clear();
			FAILED.clear();
			NOT_NOW.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 != 0 || WATCHING.isEmpty()) return;
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
			for (var p : entry.get().pending()) {
				if (!"supported".equals(states.get(p.block())) || !offerable(p.block())) continue;
				String key = "part_" + p.part();
				if (open.stream().anyMatch(x -> x.key().equals(key)) || s.proposals().declined().contains(key) || s.proposals().accepted().contains(key)) continue;
				String what = name(partOf(plan, p.part()).orElse(null), p.part());
				open.add(Proposal.part(p.part(), Character.toUpperCase(what.charAt(0)) + what.substring(1) + " can be built now",
					"It can be built now. Building it fits it round " + s.name() + " as it stands (up to $1); it goes up as a part of its own, which an undo takes"
						+ " away on its own. If it has to change to fit, you see how and build it once more.", now));
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
		if (!offerable(pending.get().block())) return "It can't be built on its own: it waits for the part it belongs to.";
		if (s.proposals().accepted().contains(p.key())) return "It is built already.";
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
		round(server, player, s, p, pending.get().block(), partJson.get().toString(), region.get(), others(server, s, region.get()), what, Session.current(), 0, 0, null,
			null, null);
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

	private static void round(MinecraftServer server, UUID player, Settlement s, Proposal p, String block, String partJson, String region, List<String> others,
		String what, int session, int round, double spent, @Nullable String previous, @Nullable String answers, @Nullable String last) {
		var entry = PlanStore.get(s.id()).orElse(null);
		if (entry == null) { fail(server, player, s, p, what, "the kept plan went"); return; }
		String card = Actions.cardJson(s.card());
		PlanWriter.writePart(card, entry.plan(), partJson, block, others, s.owner(), previous, answers).whenComplete((r, err) -> server.execute(() -> guarded(server, player,
			s, p, what, () -> {
			if (!Session.is(session)) return;
			if (err != null || r.answer() == null || !r.answer().has("plan") || !r.answer().get("plan").isJsonObject()) {
				fail(server, player, s, p, what, err != null ? String.valueOf(err.getMessage()) : r.error() != null ? r.error() : "no plan in the answer");
				return;
			}
			double cost = spent + r.usd();
			// the town by its region id (Claude names it by a stand-in, so the same answer serves any world)
			JsonObject written = r.answer().getAsJsonObject("plan");
			JsonObject checked = JsonParser.parseString(written.toString().replace("\"" + PlanWriterJob.TOWN + "\"", "\"" + region + "\"")).getAsJsonObject();
			String summary = r.answer().has("summary") && r.answer().get("summary").isJsonPrimitive() ? r.answer().get("summary").getAsString() : what;
			// only the part asked for is ever built: anything more goes back to Claude
			String scope = outOfScope(checked, p.pendingPart(), block);
			if (scope != null) {
				String lines = "- {\"part\":\"" + p.pendingPart() + "\",\"class\":\"malformed\",\"reasons\":[{\"code\":\"SCOPE\",\"message\":\"" + scope + "\"}]}\n";
				if (round < ROUNDS && cost < CAP_USD - PlanWriterJob.BUDGET_USD && !lines.equals(last)) {
					round(server, player, s, p, block, partJson, region, others, what, session, round + 1, cost, written.toString(), lines, lines);
				} else fail(server, player, s, p, what, scope);
				return;
			}
			var now = Settlements.store().get(s.id()).orElse(s);
			Composer.check(server, now, checked, player).whenComplete((c, cerr) -> server.execute(() -> guarded(server, player, s, p, what, () -> {
				if (!Session.is(session)) return;
				if (cerr != null) { fail(server, player, s, p, what, "Architect could not check it (" + cerr.getMessage() + ")"); return; }
				var answer = c.parts().stream().filter(x -> x.has("part") && p.pendingPart().equals(x.get("part").getAsString())).findFirst();
				String cls = answer.map(x -> x.has("class") ? x.get("class").getAsString() : "").orElse("missing");
				if (c.artifactSha() != null && cls.equals("fits")) {
					realise(server, player, s, p, what, c.artifactSha(), summary, cost);
					return;
				}
				if (c.artifactSha() != null && cls.equals("adjusted")) {
					// Architect changed it to fit: shown, and built when the player accepts it once more
					String how = answer.map(x -> x.has("deviations") ? x.get("deviations").toString() : "").orElse("");
					CHECKED.put(s.id() + "/" + p.pendingPart(), new Checked(c.artifactSha(), summary, cost));
					BUSY.remove(s.id() + "/" + p.pendingPart());
					Composer.tellNow(server, player, s, summary + " Architect changed it to fit" + (how.isEmpty() ? "" : " (" + clip(how, 200) + ")")
						+ ": accept it once more in the inbox to build it.");
					return;
				}
				String lines = Composer.answerLines(c);
				boolean repairable = cls.equals("refused") || cls.equals("malformed") || cls.equals("missing");
				if (repairable && round < ROUNDS && cost < CAP_USD - PlanWriterJob.BUDGET_USD && !lines.equals(last)) {
					Steward.LOGGER.info("part {} of {}: round {} {} (${})", p.pendingPart(), s.id(), round, cls, String.format("%.3f", cost));
					round(server, player, s, p, block, partJson, region, others, what, session, round + 1, cost, written.toString(), lines, lines);
					return;
				}
				String why = answer.flatMap(x -> x.has("reasons") && x.get("reasons").isJsonArray() && !x.getAsJsonArray("reasons").isEmpty()
					&& x.getAsJsonArray("reasons").get(0).isJsonObject() && x.getAsJsonArray("reasons").get(0).getAsJsonObject().has("message")
					? Optional.of(x.getAsJsonArray("reasons").get(0).getAsJsonObject().get("message").getAsString()) : Optional.empty()).orElse(cls + ", " + c.overall());
				fail(server, player, s, p, what, "it doesn't fit round " + s.name() + " yet: " + why);
			})));
		})));
	}

	/** Runs a step of the fitting; anything it throws (an answer of an unexpected shape) stops it as a failure, so the settlement is never left busy. */
	private static void guarded(MinecraftServer server, UUID player, Settlement s, Proposal p, String what, Runnable step) {
		try {
			step.run();
		} catch (RuntimeException e) {
			Steward.LOGGER.warn("part {} of {} stopped", p.pendingPart(), s.id(), e);
			fail(server, player, s, p, what, "its plan could not be read (" + e.getMessage() + ")");
		}
	}

	/** The checked part plan realised as a region of its own, then watched until it is placed. */
	private static void realise(MinecraftServer server, UUID player, Settlement s, Proposal p, String what, String artifactSha, String summary, double cost) {
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
				if (err != null) { fail(server, player, s, p, what, "Architect could not build it (" + SettlementRunner.message(err) + ")"); return; }
				Steward.LOGGER.info("part {} of {}: region {} (composing ${})", p.pendingPart(), s.id(), id, String.format("%.3f", cost));
				Composer.tellNow(server, player, s, summary + String.format(java.util.Locale.ROOT, " Raising it now (fitting it cost $%.2f)...", cost));
				WATCHING.put(id, new Watch(s.id(), p.pendingPart(), what, player, p));
			}));
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
	private static void fail(MinecraftServer server, UUID player, Settlement s, Proposal p, String what, String why) {
		BUSY.remove(s.id() + "/" + p.pendingPart());
		FAILED.add(s.id() + "/" + p.pendingPart());
		Steward.LOGGER.info("part {} of {}: {}", p.pendingPart(), s.id(), why);
		// the offer stays in the inbox (it was never recorded as accepted): accepted again, it is fitted again
		Composer.tellNow(server, player, s, Character.toUpperCase(what.charAt(0)) + what.substring(1) + " waits: " + why + ".");
	}

	private static String clip(String t, int n) {
		return t.length() <= n ? t : t.substring(0, n - 1) + "…";
	}
}
