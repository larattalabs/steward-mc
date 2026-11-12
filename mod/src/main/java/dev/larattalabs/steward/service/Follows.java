package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.Designs;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.FollowPlanner;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Copies follow their source (docs/FOLLOW.md, Architect FS): once a source's own lot stands at a newer version than its copies were made from, the
 * copies are re-made from that version at $0 ({@code Designs.followAll}) and each copy's lot becomes an ordinary update ({@link Updates}, which always
 * asks for one). A refused copy stays as it is and is told once. Nothing here writes the world. Run from the update scan. Server thread.
 */
public final class Follows {
	/** Architect's feature for {@code followers}, {@code follow} and {@code followAll}. */
	public static final String FEATURE = "followSource";

	/** Keys sent or answered this session; a lost answer takes its key out again, so the next scan asks again (Architect resumes or replays it). */
	private static final Set<String> ASKED = new HashSet<>();
	/** Sources with a {@code followAll} on its way (one per source, whatever settlement asked). */
	private static final Set<String> IN_FLIGHT = new HashSet<>();
	/** Ticks until the scan that asks again after a lost answer (a lost request brings no version event); 0 when none is due. */
	private static int retryIn;
	/** Ticks a lost answer waits before it is asked again (30 s). */
	static final int RETRY_TICKS = 600;

	private Follows() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			ASKED.clear();
			IN_FLIGHT.clear();
			retryIn = 0;
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (retryIn > 0 && --retryIn == 0) Updates.rescan();
		});
	}

	static boolean available() {
		return ArchitectApi.get().features().contains(FEATURE);
	}

	/** The settlement's standing buildings (in its own dimension). */
	static List<SiteView> buildings(MinecraftServer server, Settlement s) {
		return ArchitectApi.get().sites(server).list(s.owner()).stream().filter(v -> "building".equals(v.kind()) && v.blueprintId() != null
			&& v.dimension().identifier().toString().equals(s.claim().dimension())).toList();
	}

	/** Entry id -> the highest version a building of it stands at in the settlement. */
	static Map<String, Integer> standing(List<SiteView> buildings) {
		Map<String, Integer> out = new HashMap<>();
		for (SiteView v : buildings) out.merge(v.blueprintId(), v.version(), Math::max);
		return out;
	}

	/** Re-makes the copies whose source stands at a newer version, at most once per key this session. */
	static void scan(MinecraftServer server, Settlement s) {
		if (!available()) return;
		List<SiteView> buildings = buildings(server, s);
		var library = ArchitectApi.get().library();
		for (FollowPlanner.Source src : FollowPlanner.toFollow(standing(buildings), library::followers)) {
			String key = FollowPlanner.opKey(src.entryId(), src.target(), src.heads());
			if (IN_FLIGHT.contains(src.entryId()) || ASKED.contains(key)) continue;
			ASKED.add(key);
			IN_FLIGHT.add(src.entryId());
			String sourceLot = buildings.stream().filter(v -> v.blueprintId().equals(src.entryId()) && v.version() == src.target()).findFirst().map(Updates::lotOf)
				.orElse(src.entryId());
			Steward.LOGGER.info("follow {}: {} v{} -> copies {} ({})", s.id(), src.entryId(), src.target(), src.copies(), key);
			int session = Session.current();
			try {
				ArchitectApi.get().designs().followAll(src.entryId(), src.target(), key).whenComplete((results, err) -> {
					if (!Session.is(session)) return;
					IN_FLIGHT.remove(src.entryId());
					if (err != null) failed(server, s.id(), key, sourceLot, src.target(), err);
					else answered(server, s.id(), src, sourceLot, results);
				});
			} catch (RuntimeException e) {
				// thrown at once (an Architect without the call): not asked again this session
				IN_FLIGHT.remove(src.entryId());
				Steward.LOGGER.warn("follow {}: {} could not be asked", s.id(), key, e);
			}
		}
	}

	private static void answered(MinecraftServer server, String settlementId, FollowPlanner.Source src, String sourceLot, List<Designs.FollowResult> results) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return;
		List<SiteView> buildings = buildings(server, s.get());
		List<String> followedLots = new ArrayList<>();
		List<String> followedSites = new ArrayList<>();
		for (Designs.FollowResult r : results) {
			List<SiteView> at = buildings.stream().filter(v -> v.blueprintId().equals(r.entryId())).toList();
			List<String> where = at.stream().map(Follows::where).toList();
			Steward.LOGGER.info("follow {}: {} {} (v{} -> v{}, source v{}){}", settlementId, r.entryId(), r.followed() ? "followed" : r.alreadyFollowed() ? "already followed"
				: "refused " + r.refused(), r.fromVersion(), r.newVersion(), r.sourceVersion(), r.message() == null || r.message().isBlank() ? "" : ": " + r.message());
			if (r.followed()) {
				followedLots.addAll(where.isEmpty() ? List.of(r.entryId()) : where);
				at.forEach(v -> followedSites.add(v.id()));
			} else if (FollowPlanner.refused(r)) {
				String copyLot = at.isEmpty() ? r.entryId() : Updates.lotOf(at.get(0)) + " (" + String.join(", ", where) + ")";
				note(server, settlementId, FollowPlanner.refusalText(copyLot, sourceLot, src.target(), r), at.stream().map(SiteView::id).toList());
			}
		}
		if (!followedLots.isEmpty()) note(server, settlementId, FollowPlanner.followedText(sourceLot, followedLots, src.target()), followedSites);
		// the copies' lots are outdated now: offered by the next scan
		Updates.rescan();
	}

	private static void failed(MinecraftServer server, String settlementId, String key, String sourceLot, int target, Throwable err) {
		Throwable e = err.getCause() != null ? err.getCause() : err;
		if (ChangeRequests.definite(err)) {
			// a typed refusal of the whole call: said once; the key stays asked (a replay would answer the same)
			Steward.LOGGER.warn("follow {}: {} refused: {}", settlementId, key, e.getMessage());
			note(server, settlementId, FollowPlanner.callRefusedText(sourceLot, target, String.valueOf(e.getMessage())), List.of());
		} else {
			// a lost answer: Architect resumes the saved operation; the next scan asks again with the same key, which replays it
			Steward.LOGGER.warn("follow {}: {} lost its answer ({}); asked again on the next scan", settlementId, key, e.getMessage());
			ASKED.remove(key);
			retryIn = RETRY_TICKS;
		}
	}

	/** The lot a building stands on, in words: its lot id, else its site id. */
	static String where(SiteView v) {
		String lot = Updates.lotIdOf(v);
		return lot != null ? lot.replace('_', ' ') : v.id();
	}

	/** A note in the history and said by the steward, once: a note with the same text already there (a replay, a restart) says nothing. */
	private static void note(MinecraftServer server, String settlementId, String text, List<String> siteIds) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty() || told(s.get(), text)) return;
		var logged = Settlements.log(settlementId, new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, text, siteIds));
		if (!logged.ok()) Steward.LOGGER.warn("follow {}: the note could not be saved: {}", settlementId, logged.error());
		String said = text;
		server.getPlayerList().getPlayers().forEach(pl -> pl.sendSystemMessage(Component.literal("Steward (" + s.get().name() + "): " + said)));
		StewardVoice.say(server, settlementId, said);
	}

	/** Whether the history already holds this note. */
	static boolean told(Settlement s, String text) {
		return s.log().stream().anyMatch(e -> e.kind() == Settlement.Kind.NOTE && e.text().equals(text));
	}

	/**
	 * For an update to a COPY's version (by following, or a library revert of the entry: docs/FOLLOW.md, review 6): its source entry and the source
	 * version it follows; null when that version is not a copy's.
	 */
	static FollowOf followOf(String entryId, int version) {
		var d = ArchitectApi.get().library().entry(entryId, version).flatMap(e -> e.derivation()).filter(x -> x.kind() == dev.larattalabs.architect.api.Library.Derivation.Kind.COPY);
		return d.map(x -> new FollowOf(x.source(), x.sourceVersion())).orElse(null);
	}

	/** Whether an update of {@code siteId} to {@code version} may be applied now: not a copy's, or its source stands at the version it follows. */
	static boolean stillOffered(MinecraftServer server, Settlement s, String siteId, int version) {
		if (!available()) return true;
		var v = ArchitectApi.get().sites(server).get(siteId);
		if (v.isEmpty() || v.get().blueprintId() == null) return true;
		FollowOf f = followOf(v.get().blueprintId(), version);
		return f == null || FollowPlanner.offered(standing(buildings(server, s)).get(f.source()), f.sourceVersion());
	}

	record FollowOf(String source, int sourceVersion) {}
}
