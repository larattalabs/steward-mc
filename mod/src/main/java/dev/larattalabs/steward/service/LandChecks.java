package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.ArchitectRefused;
import dev.larattalabs.architect.api.LandReport;
import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.architect.api.RegionView;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.LandWords;
import dev.larattalabs.steward.inbox.InboxModel;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The steward looks over its town and districts (docs/LAND.md, Architect LC): on demand ({@code /steward check}, "what changed?") and now and then while a
 * player is in the claim. It reads Architect's land check (read-only, $0), words it ({@link LandWords}) and keeps one inbox row per settlement. Nothing
 * here writes the world or the save: the row and what was last told live in this session. Server thread.
 */
public final class LandChecks {
	/** Architect's feature for {@code Regions.landCheck}. */
	public static final String FEATURE = "landCheck";
	/** The inbox row's decision. */
	public static final String DECISION = "LAND";
	/** A settlement is looked over by itself at most this often (20 minutes). */
	static final long AUTO_EVERY_MS = 20 * 60_000L;
	/** How often the automatic looks are considered (every 20 s). */
	static final int SCAN_TICKS = 400;

	/**
	 * Asked again within this long, a settlement is not looked over again (the command and talk share it). Its last look is not reused: the player may
	 * have just broken something.
	 */
	static final long AGAIN_MS = 3_000L;
	/** At most this many of a look's lines in its inbox row (the chat has them all). */
	static final int ROW_LINES = 6;

	/** A look and when it was made. */
	record Kept(LandWords.Note note, long at) {}

	/** The latest look per settlement shown in the inbox. */
	private static final Map<String, Kept> ROWS = new HashMap<>();
	/** The last look per settlement, for a repeated ask. */
	private static final Map<String, Kept> LAST = new HashMap<>();
	/** The damage signature last told (or dismissed) per settlement: an automatic look speaks only when it differs. */
	private static final Map<String, String> TOLD = new HashMap<>();
	/** When each settlement was last looked over. */
	private static final Map<String, Long> LOOKED = new HashMap<>();
	/** One look at a time across all settlements (a land check shares Architect's tick budget). */
	private static boolean inFlight;
	private static int tick;

	private LandChecks() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			ROWS.clear();
			LAST.clear();
			TOLD.clear();
			LOOKED.clear();
			inFlight = false;
			tick = 0;
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (++tick % SCAN_TICKS == 0) autoLook(s, System.currentTimeMillis());
		});
	}

	static boolean available() {
		return ArchitectApi.get().features().contains(FEATURE);
	}

	/** A region to look over: its id and its label. */
	record Target(String region, String label, boolean district) {}

	/** The settlement's standing town and district regions (a region revising or being removed is left for later). */
	static List<Target> targets(Settlement s) {
		var regions = ArchitectApi.get().regions();
		List<Target> out = new ArrayList<>();
		TownRegion.of(s).filter(id -> stands(regions.get(id))).ifPresent(id -> out.add(new Target(id, s.name(), false)));
		for (Settlement.District d : s.lands().districts()) {
			if (d.region() != null && stands(regions.get(d.region()))) out.add(new Target(d.region(), d.name(), true));
		}
		return out;
	}

	static boolean stands(Optional<RegionView> v) {
		if (v.isEmpty()) return false;
		RegionState st = v.get().state();
		return (st == RegionState.PLACED || st == RegionState.PARTIAL || st == RegionState.FAILED) && (v.get().busy() == null || v.get().busy().isBlank());
	}

	/**
	 * Looks the settlement over on the player's asking and answers {@code reply} with the note's headline then its lines. Refused (an answer at once) when
	 * Architect has no land check, the town is no region, or another look is running.
	 */
	public static void ask(MinecraftServer server, Settlement s, Consumer<List<String>> reply) {
		if (!available()) {
			reply.accept(List.of("I can't look over the land with this Architect (no land check)."));
			return;
		}
		List<Target> targets = targets(s);
		if (targets.isEmpty()) {
			reply.accept(List.of(TownRegion.of(s).isPresent() ? "I'll look over " + s.name() + " once the work there is done."
				: "I can only look over ground Architect laid out as a region; " + s.name() + "'s street isn't one yet."));
			return;
		}
		Kept last = LAST.get(s.id());
		if (last != null && System.currentTimeMillis() - last.at() < AGAIN_MS) {
			reply.accept(List.of("I just looked over " + s.name() + ": ask me again in a moment."));
			return;
		}
		if (inFlight) {
			reply.accept(List.of("I'm looking over the land already: ask me again in a moment."));
			return;
		}
		look(server, s, targets, true, note -> reply.accept(said(note)));
	}

	private static List<String> said(LandWords.Note note) {
		List<String> out = new ArrayList<>();
		out.add(note.headline());
		out.addAll(note.lines());
		return out;
	}

	/** The automatic look: a settlement due, with a player in its claim (its chunks loaded), not building. At most one per scan. */
	static void autoLook(MinecraftServer server, long now) {
		if (inFlight || !available()) return;
		for (Settlement s : Settlements.store().all()) {
			if (now - LOOKED.getOrDefault(s.id(), 0L) < AUTO_EVERY_MS) continue;
			if (!playerIn(server, s) || SettlementRunner.busy(s.id())) continue;
			List<Target> targets = targets(s);
			if (targets.isEmpty()) continue;
			look(server, s, targets, false, note -> {
			});
			return;
		}
	}

	static boolean playerIn(MinecraftServer server, Settlement s) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			String dim = p.level().dimension().identifier().toString();
			if (inXZ(s.claim(), dim, p.getBlockX(), p.getBlockZ())) return true;
			for (Settlement.District d : s.lands().districts()) if (d.claim() != null && inXZ(d.claim(), dim, p.getBlockX(), p.getBlockZ())) return true;
		}
		return false;
	}

	private static boolean inXZ(Claim c, String dim, int x, int z) {
		return c.dimension().equals(dim) && Math.abs(x - c.centerX()) <= c.radius() && Math.abs(z - c.centerZ()) <= c.radius();
	}

	/** Looks the targets over one after the other, then words and keeps the note (on the server thread). */
	private static void look(MinecraftServer server, Settlement s, List<Target> targets, boolean onDemand, Consumer<LandWords.Note> done) {
		inFlight = true;
		LOOKED.put(s.id(), System.currentTimeMillis());
		int session = Session.current();
		List<LandWords.Look> looks = new ArrayList<>();
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
		for (Target t : targets) {
			chain = chain.thenCompose(x -> one(t)).thenAccept(looks::add);
		}
		chain.whenComplete((x, err) -> server.execute(() -> {
			if (!Session.is(session)) return;
			inFlight = false;
			if (err != null) {
				Steward.LOGGER.warn("land check {} failed", s.id(), err);
				if (onDemand) done.accept(new LandWords.Note("I couldn't look over " + s.name() + " just now.", List.of(), 0, 0, false, false, ""));
				return;
			}
			var current = Settlements.store().get(s.id());
			if (current.isEmpty()) return;
			// a region undone, removed or revised while it was looked over: the look is of land that is no longer the settlement's
			if (!same(targets, targets(current.get()), looks)) {
				Steward.LOGGER.info("land check {}: its regions changed while it looked; dropped", s.id());
				if (onDemand) done.accept(new LandWords.Note("The land changed while I looked: ask me again.", List.of(), 0, 0, false, false, ""));
				return;
			}
			LandWords.Note note = LandWords.note(s.name(), looks, siteId -> buildingName(server, siteId), onDemand, Secrets.ofSettlement(s.id())::hides);
			Steward.LOGGER.info("land check {}: {} ({} lines, damage {}, placed {}{})", s.id(), note.headline(), note.lines().size(), note.damage(), note.placed(),
				onDemand ? ", asked" : "");
			LAST.put(s.id(), new Kept(note, System.currentTimeMillis()));
			keep(server, current.get(), note, onDemand);
			done.accept(note);
		}));
	}

	/** Whether the settlement's regions are those looked over, and none was revised meanwhile (its position moved). */
	static boolean same(List<Target> before, List<Target> now, List<LandWords.Look> looks) {
		if (!before.stream().map(Target::region).toList().equals(now.stream().map(Target::region).toList())) return false;
		for (LandWords.Look l : looks) {
			if (l.report() == null) continue;
			var v = ArchitectApi.get().regions().get(l.report().region());
			if (v.isEmpty() || v.get().position() != l.report().position()) return false;
		}
		return true;
	}

	/** One region's look; a typed refusal is a look too (its words), anything else fails the whole look. */
	private static CompletableFuture<LandWords.Look> one(Target t) {
		CompletableFuture<LandReport> f;
		try {
			f = ArchitectApi.get().regions().landCheck(t.region(), null);
		} catch (RuntimeException e) {
			f = CompletableFuture.failedFuture(e);
		}
		return f.handle((r, err) -> {
			if (err == null) return LandWords.Look.of(t.label(), t.district(), r);
			Throwable e = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
			if (e instanceof ArchitectRefused ar) return LandWords.Look.refused(t.label(), t.district(), ar.reason().name(), String.valueOf(ar.getMessage()));
			throw new java.util.concurrent.CompletionException(e);
		});
	}

	/**
	 * Keeps the note as the settlement's inbox row (docs/LAND.md rule 5). An automatic look puts a row up and speaks only when the damage differs from what
	 * was last told; the player's own blocks alone never speak. On demand the row is always replaced (the asker hears the note itself).
	 */
	static void keep(MinecraftServer server, Settlement s, LandWords.Note note, boolean onDemand) {
		Decision d = decide(note, ROWS.containsKey(s.id()), TOLD.get(s.id()), onDemand);
		if (d.row()) ROWS.put(s.id(), new Kept(note, System.currentTimeMillis()));
		else if (d.clear()) ROWS.remove(s.id());
		if (d.told() != null) TOLD.put(s.id(), d.told());
		if (d.say()) {
			String said = note.headline() + " See the inbox (Y).";
			server.getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(Component.literal("[" + s.name() + "] " + said)));
			StewardVoice.say(server, s.id(), said);
		}
		if (d.row() || d.clear()) server.getPlayerList().getPlayers().forEach(p -> SettlementRunner.sendInbox(server, p.getUUID()));
	}

	/** What a look does: put the row up (or update it), clear it, what signature counts as told, and whether the steward speaks. */
	record Decision(boolean row, boolean clear, @Nullable String told, boolean say) {}

	/**
	 * Pure: the rule of {@link #keep} (docs/LAND.md rule 5). A look with a gap is unknown, not zero: an automatic one changes nothing, an asked one
	 * replaces the row but is not counted as told.
	 */
	static Decision decide(LandWords.Note note, boolean rowUp, @Nullable String told, boolean onDemand) {
		if (onDemand) {
			String t = note.complete() ? note.signature() : told;
			// what it saw replaces the row; nothing seen with a gap leaves a known row as it is; nothing at all, seen whole, clears it
			if (note.real()) return new Decision(true, false, t, false);
			return new Decision(rowUp && !note.complete(), rowUp && note.complete(), t, false);
		}
		if (!note.complete()) return new Decision(rowUp, false, told, false);
		if (!note.real() || note.signature().isEmpty()) {
			// no damage: a row of damage goes; the player's own blocks alone put none up and tell nothing
			return note.real() ? new Decision(rowUp, false, "", false) : new Decision(false, rowUp, "", false);
		}
		if (!note.signature().equals(told)) return new Decision(true, false, note.signature(), true);
		return new Decision(rowUp, false, told, false);
	}

	/** "Got it": the row goes; its damage counts as told, so the same look does not come back by itself. */
	public static String dismiss(MinecraftServer server, String settlementId) {
		Kept k = ROWS.remove(settlementId);
		// an incomplete look dismissed counts as told for what it saw
		if (k != null) TOLD.put(settlementId, k.note().signature());
		server.getPlayerList().getPlayers().forEach(p -> SettlementRunner.sendInbox(server, p.getUUID()));
		return k == null ? "Nothing to dismiss." : "Noted.";
	}

	/** One {@code LAND} row per settlement with a look to show; it needs no one. */
	public static List<InboxModel.Entry> inboxEntries() {
		List<InboxModel.Entry> out = new ArrayList<>();
		for (var e : ROWS.entrySet()) {
			var s = Settlements.store().get(e.getKey());
			if (s.isEmpty()) continue;
			out.add(InboxModel.land(s.get().id(), s.get().name(), e.getValue().note().headline(), rowLines(e.getValue())));
		}
		return out;
	}

	/** The row's lines: when it was looked, then at most {@link #ROW_LINES} of the look's. */
	static List<String> rowLines(Kept k) {
		List<String> out = new ArrayList<>();
		out.add("Looked over at " + java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ROOT).format(java.time.Instant.ofEpochMilli(k.at())
			.atZone(java.time.ZoneId.systemDefault())).toLowerCase(java.util.Locale.ROOT) + ".");
		List<String> l = k.note().lines();
		if (l.size() <= ROW_LINES) out.addAll(l);
		else {
			out.addAll(l.subList(0, ROW_LINES - 1));
			out.add("And " + (l.size() - (ROW_LINES - 1)) + " more: ask me what changed.");
		}
		return out;
	}

	/** A lot site's building as Steward calls it (its role), or null. */
	private static @Nullable String buildingName(MinecraftServer server, String siteId) {
		if (siteId == null) return null;
		return ArchitectApi.get().sites(server).get(siteId).map(Updates::lotOf).filter(n -> !n.equals(siteId)).orElse(null);
	}
}
