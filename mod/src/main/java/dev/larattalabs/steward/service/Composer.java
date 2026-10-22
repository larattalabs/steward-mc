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

	/** Whether this Architect has the plan check (P0, API 1.23). */
	public static boolean available() {
		try {
			return ArchitectApi.get().jobs().available() && ArchitectApi.get().regions().getClass().getMethod("checkPlan", PlanRequest.class) != null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Composes {@code s}'s plan from its card ({@code cardJson}) and the land round its stone; tells {@code player} when it is done. */
	public static void compose(MinecraftServer server, UUID player, Settlement s, String cardJson, @Nullable String land) {
		int session = Session.current();
		if (!available() || Integer.valueOf(session).equals(COMPOSING.get(s.id()))) return;
		COMPOSING.put(s.id(), session);
		round(server, player, s, cardJson, land, session, 0, 0, null, null, null);
	}

	private static void round(MinecraftServer server, UUID player, Settlement s, String cardJson, @Nullable String land, int session, int round, double spent,
		@Nullable String previous, @Nullable String answers, @Nullable String lastAnswers) {
		var builds = PlayerBuilds.promptLines(server, s, PlayerBuilds.in(server, s));
		PlanWriter.write(cardJson, land, ANCHORS, builds, s.owner(), previous, answers).whenComplete((r, err) -> server.execute(() -> {
			if (!Session.is(session)) return;
			if (err != null || r.answer() == null || !r.answer().has("plan") || !r.answer().get("plan").isJsonObject()) {
				done(s, "The plan could not be composed (" + (err != null ? String.valueOf(err.getMessage()) : r.error() != null ? r.error() : "no plan in the answer") + "): the build takes today's layout.", server, player);
				return;
			}
			double cost = spent + r.usd();
			JsonObject plan = r.answer().getAsJsonObject("plan");
			check(server, s, plan, player).whenComplete((c, cerr) -> server.execute(() -> {
				if (!Session.is(session)) return;
				if (cerr != null) {
					done(s, "Architect could not check the plan (" + cerr.getMessage() + "): the build takes today's layout.", server, player);
					return;
				}
				String lines = answerLines(c);
				boolean repairable = c.parts().stream().anyMatch(p -> "refused".equals(cls(p)) || "malformed".equals(cls(p)))
					|| c.overall() == PlanCheck.Overall.MALFORMED;
				// repair while something can be fixed, within the rounds and the cap, and stop when the answer repeats (an oscillation)
				if (repairable && round < ROUNDS && cost < CAP_USD - PlanWriterJob.BUDGET_USD && !lines.equals(lastAnswers)) {
					Steward.LOGGER.info("plan for {}: round {} {} ({} parts to repair), ${}", s.id(), round, c.overall(), count(c, "refused") + count(c, "malformed"),
						String.format("%.3f", cost));
					round(server, player, s, cardJson, land, session, round + 1, cost, plan.toString(), lines, lines);
					return;
				}
				keep(server, player, s, plan, r.answer(), c, cost, round);
			}));
		}));
	}

	/** Architect's check of {@code plan} over {@code s}'s claim, the stone and (when in the claim) the player as anchors. */
	static java.util.concurrent.CompletableFuture<PlanCheck> check(MinecraftServer server, Settlement s, JsonObject plan, UUID player) {
		var level = Districts.level(server, s.claim().dimension());
		if (level == null) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("the settlement's dimension is not loaded"));
		int cx = s.claim().centerX(), cz = s.claim().centerZ(), r = s.claim().radius();
		var claim = new BoundingBox(cx - r, level.getMinY(), cz - r, cx + r, level.getMaxY(), cz + r);
		BlockPos stone = new BlockPos(cx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz), cz);
		Map<String, BlockPos> anchors = new HashMap<>();
		anchors.put("stone", stone);
		var p = server.getPlayerList().getPlayer(player);
		anchors.put("player", p != null && s.contains(p.level().dimension().identifier().toString(), p.getBlockX(), p.getBlockY(), p.getBlockZ()) ? p.blockPosition() : stone);
		// a part whose check reads the rock under the land (a tunnel, roots, stalactites: the catalogue's limits.volume, S-F3-3) may load (and generate) the
		// chunks it needs (T3, S-T3-6): else an unloaded volume answers incomplete VOLUME
		var load = needsVolume(plan, volumeBlocks()) ? dev.larattalabs.architect.api.LoadPolicy.LOAD_BOUNDED(TUNNEL_CHUNKS) : null;
		return ArchitectApi.get().regions().checkPlan(new PlanRequest(plan, level, claim, anchors, s.owner(), null, null, null, null, new JsonObject(), load));
	}

	/** At most this many chunks a check of a plan with a tunnel loads for its survey and volume pass (Architect's caps bound the cells as well). */
	static final int TUNNEL_CHUNKS = 512;

	/** The catalogue's block ids whose check reads a frozen volume ({@code limits.volume: true}, Architect F3's S-F3-3); empty when this Architect can't say. */
	static java.util.Set<String> volumeBlocks() {
		java.util.Set<String> out = new java.util.HashSet<>();
		try {
			JsonObject c = ArchitectApi.get().regions().planCatalogue();
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

	/** Keeps the plan and tells the player: the summary, then what waits in Claude's words, then what still didn't fit in Architect's. */
	private static void keep(MinecraftServer server, UUID player, Settlement s, JsonObject plan, JsonObject answer, PlanCheck c, double cost, int rounds) {
		Map<String, String> words = new HashMap<>();
		if (answer.has("waits") && answer.get("waits").isJsonArray()) for (var w : answer.getAsJsonArray("waits")) if (w.isJsonObject() && w.getAsJsonObject().has("part") && w.getAsJsonObject().has("words")) words.put(w.getAsJsonObject().get("part").getAsString(), w.getAsJsonObject().get("words").getAsString());
		List<PlanStore.Pending> pending = new ArrayList<>();
		List<String> unfit = new ArrayList<>();
		for (JsonObject part : c.parts()) {
			String id = part.has("part") ? part.get("part").getAsString() : "?";
			boolean required = part.has("required") && part.get("required").getAsBoolean();
			switch (cls(part)) {
				case "unsupported", "blocked" -> pending.add(new PlanStore.Pending(id, part.has("block") ? part.get("block").getAsString() : "", required,
					words.getOrDefault(id, id.replace('_', ' ') + " waits for a later Architect release")));
				case "refused", "malformed" -> unfit.add(id.replace('_', ' ') + (part.has("reason") ? " (" + part.get("reason") + ")" : ""));
				default -> {
				}
			}
		}
		String summary = answer.has("summary") ? answer.get("summary").getAsString() : "";
		PlanStore.put(new PlanStore.Entry(s.id(), plan.toString(), c.overall().name(), c.artifactSha(), summary, List.copyOf(pending), cost, System.currentTimeMillis(), rounds,
			crossings(c.preview())));
		Steward.LOGGER.info("plan for {}: {} after {} repair(s), {} pending, {} unfit, ${}", s.id(), c.overall(), rounds, pending.size(), unfit.size(),
			String.format("%.3f", cost));
		List<String> out = new ArrayList<>();
		// what composing cost, with the card's cost already shown: the player asked for it, so it is theirs, not the steward's weekly allowance
		if (!summary.isBlank()) out.add("The plan: " + summary + String.format(java.util.Locale.ROOT, " (composing it cost $%.2f)", cost));
		for (var p : pending) out.add("Not yet: " + p.words());
		if (!unfit.isEmpty()) out.add("Didn't fit here: " + String.join(", ", unfit) + ".");
		COMPOSING.remove(s.id(), Session.current());
		tell(server, player, s, out);
		// a card screen open on it shows the plan now
		var p = server.getPlayerList().getPlayer(player);
		if (p != null) Settlements.store().get(s.id()).ifPresent(now -> Actions.sendCard(p, now, true));
	}

	private static void done(Settlement s, String why, MinecraftServer server, UUID player) {
		COMPOSING.remove(s.id(), Session.current());
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
	 * length, lands}), a line each: "a stone arch over water, 14 long, to gate east_gate".
	 */
	static List<String> crossings(@org.jspecify.annotations.Nullable JsonObject preview) {
		List<String> out = new ArrayList<>();
		if (preview == null || !preview.has("siteplan") || !preview.get("siteplan").isJsonObject()) return out;
		var sp = preview.getAsJsonObject("siteplan");
		if (!sp.has("edges") || !sp.get("edges").isJsonArray()) return out;
		for (var e : sp.getAsJsonArray("edges")) {
			if (!e.isJsonObject() || !e.getAsJsonObject().has("crossing") || !e.getAsJsonObject().get("crossing").isJsonObject()) continue;
			JsonObject x = e.getAsJsonObject().getAsJsonObject("crossing");
			String type = str(x, "type").replace('_', ' ');
			String form = str(x, "form");
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

	private static String cls(JsonObject part) {
		return part.has("class") ? part.get("class").getAsString() : "";
	}

	private static long count(PlanCheck c, String cls) {
		return c.parts().stream().filter(p -> cls.equals(cls(p))).count();
	}
}
