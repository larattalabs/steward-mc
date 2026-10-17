package dev.larattalabs.steward.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.PlanCheck;
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
 * region of its own, recorded like a placement, so an undo takes the part away and leaves the town. Server thread.
 */
public final class PendingOffers {
	static final double CAP_USD = 1.00;
	static final int ROUNDS = 2;
	/** A part's region being placed: read once a second until it is placed, then recorded. */
	private record Watch(String settlementId, String part, String name, UUID player, Proposal proposal) {}
	private static final Map<String, Watch> WATCHING = new HashMap<>();
	/** Settlement and part pairs being written, checked or placed, so one is not started twice. */
	private static final Set<String> BUSY = new HashSet<>();

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

	/** The settlement's town as it stands: the oldest region it placed that stands still (a part's own region is newer). */
	static Optional<String> townRegion(Settlement s) {
		var regions = ArchitectApi.get().regions();
		for (var e : s.log()) {
			if (e.kind() != Settlement.Kind.PROJECT_PLACED) continue;
			String id = VPlan.regionOf(e.siteGroup());
			if (id == null) continue;
			var v = regions.get(id);
			if (v.isPresent() && v.get().state() != dev.larattalabs.architect.api.RegionState.REMOVED
				&& v.get().state() != dev.larattalabs.architect.api.RegionState.REMOVING) return Optional.of(id);
		}
		return Optional.empty();
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
			if (townRegion(s).isEmpty()) continue;
			JsonObject plan = JsonParser.parseString(entry.get().plan()).getAsJsonObject();
			List<Proposal> open = new ArrayList<>(s.proposals().open());
			boolean added = false;
			for (var p : entry.get().pending()) {
				if (!"supported".equals(states.get(p.block()))) continue;
				String key = "part_" + p.part();
				if (open.stream().anyMatch(x -> x.key().equals(key)) || s.proposals().declined().contains(key) || s.proposals().accepted().contains(key)) continue;
				String what = name(partOf(plan, p.part()).orElse(null), p.part());
				open.add(Proposal.part(p.part(), Character.toUpperCase(what.charAt(0)) + what.substring(1) + " can be built now",
					"Architect can build it now (" + p.block() + "). Accepting has Claude fit it round " + s.name() + " as it stands (up to $1); it goes up as a part of its"
						+ " own, which an undo takes away on its own.", now));
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

	/** Accepts an offered part: written, checked and built as its own region. Returns null when started, else why not. */
	static @Nullable String accept(MinecraftServer server, UUID player, Settlement s, Proposal p) {
		String part = p.pendingPart();
		var entry = PlanStore.get(s.id());
		if (entry.isEmpty()) return "The settlement's plan is not kept any more: describe it again.";
		var pending = entry.get().pending().stream().filter(x -> x.part().equals(part)).findFirst();
		if (pending.isEmpty()) return "That part no longer waits.";
		JsonObject plan = JsonParser.parseString(entry.get().plan()).getAsJsonObject();
		var partJson = partOf(plan, part);
		if (partJson.isEmpty()) return "That part is not in the kept plan.";
		var region = townRegion(s);
		if (region.isEmpty()) return "Nothing of " + s.name() + " stands as a region for it to go round.";
		String busy = s.id() + "/" + part;
		if (!BUSY.add(busy)) return "It is being made already.";
		if (!Proposals.recordAccepted(s.id(), p.key(), null)) {
			BUSY.remove(busy);
			return "The settlement's history could not be saved: nothing was started.";
		}
		String what = name(partJson.get(), part);
		Composer.tellNow(server, player, s, "Fitting " + what + " round " + s.name() + " as it stands...");
		round(server, player, s, p, pending.get().block(), partJson.get().toString(), region.get(), what, Session.current(), 0, 0, null, null, null);
		return null;
	}

	private static void round(MinecraftServer server, UUID player, Settlement s, Proposal p, String block, String partJson, String region, String what, int session,
		int round, double spent, @Nullable String previous, @Nullable String answers, @Nullable String last) {
		var entry = PlanStore.get(s.id()).orElse(null);
		if (entry == null) { fail(server, player, s, p, what, "the kept plan went"); return; }
		String card = Actions.cardJson(s.card());
		PlanWriter.writePart(card, entry.plan(), partJson, block, s.owner(), previous, answers).whenComplete((r, err) -> server.execute(() -> {
			if (!Session.is(session)) return;
			if (err != null || r.answer() == null || !r.answer().has("plan") || !r.answer().get("plan").isJsonObject()) {
				fail(server, player, s, p, what, err != null ? String.valueOf(err.getMessage()) : r.error() != null ? r.error() : "no plan in the answer");
				return;
			}
			double cost = spent + r.usd();
			// the town by its region id (Claude names it by a stand-in, so the same answer serves any world)
			JsonObject written = r.answer().getAsJsonObject("plan");
			JsonObject checked = JsonParser.parseString(written.toString().replace("\"" + PlanWriterJob.TOWN + "\"", "\"" + region + "\"")).getAsJsonObject();
			var now = Settlements.store().get(s.id()).orElse(s);
			Composer.check(server, now, checked, player).whenComplete((c, cerr) -> server.execute(() -> {
				if (!Session.is(session)) return;
				if (cerr != null) { fail(server, player, s, p, what, "Architect could not check it (" + cerr.getMessage() + ")"); return; }
				var answer = c.parts().stream().filter(x -> x.has("part") && p.pendingPart().equals(x.get("part").getAsString())).findFirst();
				String cls = answer.map(x -> x.has("class") ? x.get("class").getAsString() : "").orElse("missing");
				if (c.artifactSha() != null && (cls.equals("fits") || cls.equals("adjusted"))) {
					realise(server, player, s, p, what, c, cost);
					return;
				}
				String lines = Composer.answerLines(c);
				boolean repairable = cls.equals("refused") || cls.equals("malformed") || cls.equals("missing");
				if (repairable && round < ROUNDS && cost < CAP_USD - PlanWriterJob.BUDGET_USD && !lines.equals(last)) {
					Steward.LOGGER.info("part {} of {}: round {} {} (${})", p.pendingPart(), s.id(), round, cls, String.format("%.3f", cost));
					round(server, player, s, p, block, partJson, region, what, session, round + 1, cost, written.toString(), lines, lines);
					return;
				}
				String why = answer.flatMap(x -> x.has("reasons") && x.get("reasons").isJsonArray() && !x.getAsJsonArray("reasons").isEmpty()
					&& x.getAsJsonArray("reasons").get(0).isJsonObject() && x.getAsJsonArray("reasons").get(0).getAsJsonObject().has("message")
					? Optional.of(x.getAsJsonArray("reasons").get(0).getAsJsonObject().get("message").getAsString()) : Optional.empty()).orElse(cls + ", " + c.overall());
				fail(server, player, s, p, what, "it doesn't fit round " + s.name() + " yet: " + why);
			}));
		}));
	}

	/** The checked part plan realised as a region of its own, then watched until it is placed. */
	private static void realise(MinecraftServer server, UUID player, Settlement s, Proposal p, String what, PlanCheck c, double cost) {
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:part", p.pendingPart());
		var actor = server.getPlayerList().getPlayer(player);
		int session = Session.current();
		ArchitectApi.get().regions().realiseApproved(new dev.larattalabs.architect.api.ApprovedRealise(c.artifactSha(), dev.larattalabs.architect.api.Mode.INSTANT, actor,
			Map.of(), "steward.part." + s.id() + "." + p.pendingPart(), ext)).whenComplete((id, err) -> server.execute(() -> {
				if (!Session.is(session)) return;
				if (err != null) { fail(server, player, s, p, what, "Architect could not build it (" + SettlementRunner.message(err) + ")"); return; }
				Steward.LOGGER.info("part {} of {}: region {} (composing ${})", p.pendingPart(), s.id(), id, String.format("%.3f", cost));
				Composer.tellNow(server, player, s, "Raising " + what + String.format(java.util.Locale.ROOT, " (fitting it cost $%.2f)...", cost));
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
		var entry = Settlement.LogEntry.of(System.currentTimeMillis(), Settlement.Kind.PROJECT_PLACED, "Built " + w.name(), changes, VPlan.REGION_PREFIX + regionId,
			whole ? Settlement.Outcome.DONE : Settlement.Outcome.PARTIAL);
		boolean logged = Settlements.log(w.settlementId(), entry).ok();
		// it no longer waits: the kept plan's pending list without it
		PlanStore.get(w.settlementId()).ifPresent(e -> PlanStore.put(new PlanStore.Entry(e.settlementId(), e.plan(), e.overall(), e.artifact(), e.summary(),
			e.pending().stream().filter(x -> !x.part().equals(w.part())).toList(), e.usd(), e.at(), e.rounds())));
		BUSY.remove(w.settlementId() + "/" + w.part());
		Composer.tellNow(server, w.player(), s.get(), (whole ? "Built " : "Built in part: ") + w.name() + ". An undo takes it away and leaves the town."
			+ (logged ? "" : " " + Undo.UNLOGGED));
	}

	/** It stops: the offer goes back to the inbox (after a while), and the part still waits. */
	private static void fail(MinecraftServer server, UUID player, Settlement s, Proposal p, String what, String why) {
		BUSY.remove(s.id() + "/" + p.pendingPart());
		Steward.LOGGER.info("part {} of {}: {}", p.pendingPart(), s.id(), why);
		Proposals.giveBack(s.id(), p, null);
		Proposals.backOff(s.id(), p.key());
		Composer.tellNow(server, player, s, Character.toUpperCase(what.charAt(0)) + what.substring(1) + " waits: " + why + ".");
	}
}
