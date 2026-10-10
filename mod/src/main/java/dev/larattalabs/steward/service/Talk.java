package dev.larattalabs.steward.service;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.Job;
import dev.larattalabs.architect.api.SiteEvents;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.TalkJob;
import dev.larattalabs.steward.model.DistrictRules;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.ProposalRules;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.TalkAnswer;
import dev.larattalabs.steward.model.TalkRules;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Talking to the steward (docs/PLAN.md phase 3b "conversation"): the player's words get an answer in its speech bubble and in chat. Greetings, thanks,
 * "what are you doing" and "help" are answered by {@link TalkRules} for $0; anything else goes to the talk job (a few cents), whose answer is a reply and
 * at most one thing to do: a building or a district put in the inbox as a proposal, or a change request noted in the history. Nothing is built from
 * here: what is proposed waits for the player like any proposal. Host only (checked by {@link Actions}). Server thread.
 */
public final class Talk {
	/** Talk jobs a settlement may start in an hour (each a few cents). */
	static final int MAX_PER_HOUR = 30;
	/** The most proposals open at once when the player asked for them (the steward's own stop at {@link ProposalRules#MAX_OPEN}). */
	static final int MAX_OPEN_ASKED = 6;

	/** A talk job in flight: whose settlement, who said it, what. */
	private record Pending(String settlementId, UUID player, String said, int session) {}

	private static final Map<String, Pending> PENDING = new HashMap<>();
	private static final Set<String> THINKING = new HashSet<>();
	private static final Map<String, ArrayDeque<Long>> RECENT = new HashMap<>();
	/** The server of the world that is running (talk jobs finish on its thread). */
	private static volatile @Nullable MinecraftServer server;

	private Talk() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			server = null;
			PENDING.clear();
			THINKING.clear();
			RECENT.clear();
		});
		SiteEvents.JOB_DONE.register(job -> {
			var srv = server;
			if (srv != null) onDone(srv, job);
		});
	}

	/** The player said {@code text} to the steward of {@code s}. Returns what to show at once (the answer itself may come later). */
	public static Actions.Result say(MinecraftServer server, ServerPlayer p, Settlement s, String text) {
		String said = text == null ? "" : text.strip();
		if (said.length() > TalkRules.MAX_SAID) return Actions.Result.fail("Say it in at most " + TalkRules.MAX_SAID + " characters.");
		var local = TalkRules.local(said, facts(server, s));
		if (local.isPresent()) {
			answer(server, p.getUUID(), s.id(), local.get());
			return Actions.Result.ok("");
		}
		if (!s.described()) {
			answer(server, p.getUUID(), s.id(), "Describe " + s.name() + " to me first, then we can talk about what it needs.");
			return Actions.Result.ok("");
		}
		if (THINKING.contains(s.id())) return Actions.Result.fail("The steward is still thinking about what you said last.");
		long now = System.currentTimeMillis();
		var recent = RECENT.computeIfAbsent(s.id(), k -> new ArrayDeque<>());
		while (!recent.isEmpty() && now - recent.peekFirst() > 3600_000L) recent.pollFirst();
		if (recent.size() >= MAX_PER_HOUR) return Actions.Result.fail("That is a lot of talk for one hour: give the steward a while before the next question.");
		var jobs = ArchitectApi.get().jobs();
		if (!jobs.available()) {
			answer(server, p.getUUID(), s.id(), "I cannot think that through right now: Architect's Claude link is off. The settlement screen still works.");
			return Actions.Result.ok("");
		}
		recent.addLast(now);
		THINKING.add(s.id());
		int session = Session.current();
		Pending pending = new Pending(s.id(), p.getUUID(), said, session);
		jobs.run(TalkJob.build(said, context(server, s), s.owner(), simAnswer())).whenComplete((id, err) -> {
			if (!Session.is(session)) return;
			if (err != null) {
				THINKING.remove(s.id());
				answer(server, p.getUUID(), s.id(), "I could not think that through: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()));
				return;
			}
			PENDING.put(id, pending);
			// it may have finished between the ack and now
			jobs.get(id).filter(Job::finished).ifPresent(j -> onDone(server, j));
		});
		StewardVoice.say(server, s.id(), "Let me think about that.");
		return Actions.Result.ok("");
	}

	/** The scripted answer the $0 checks give Architect's sim (system property {@code steward.talk.simAnswer}), or an empty ext. */
	private static JsonObject simAnswer() {
		JsonObject ext = new JsonObject();
		String sim = System.getProperty("steward.talk.simAnswer");
		if (sim != null && !sim.isBlank()) ext.add("architect:simAnswer", com.google.gson.JsonParser.parseString(sim));
		return ext;
	}

	private static void onDone(MinecraftServer server, Job job) {
		Pending p = PENDING.remove(job.id());
		if (p == null || !Session.is(p.session())) return;
		THINKING.remove(p.settlementId());
		var r = TalkJob.interpret(job);
		if (r.answer() == null) {
			Steward.LOGGER.warn("talk job {} for {}: {}", job.id(), p.settlementId(), r.error());
			answer(server, p.player(), p.settlementId(), "Sorry, I lost my thread there. Say it again?");
			return;
		}
		act(server, p.player(), p.settlementId(), p.said(), r.answer());
	}

	/** Does what the answer asks (at most one thing) and says the reply. Also the dev path for the $0 checks. */
	public static void act(MinecraftServer server, UUID player, String settlementId, String said, TalkAnswer a) {
		var found = Settlements.store().get(settlementId);
		if (found.isEmpty()) return;
		Settlement s = found.get();
		long now = System.currentTimeMillis();
		String reply = a.reply();
		switch (a.intent()) {
			case BUILDING -> reply = proposeBuilding(server, s, said, a, now);
			case DISTRICT -> reply = proposeDistrict(server, s, said, a, now);
			case CHANGE -> {
				// change requests to a standing building come with Architect 0b: noted in the history meanwhile
				Settlements.log(settlementId, new Settlement.LogEntry(now, Settlement.Kind.NOTE, "Asked to change " + a.changeTarget() + ": " + a.changeRequest(), List.of()));
			}
			default -> {
			}
		}
		answer(server, player, settlementId, reply);
		SettlementRunner.sendInbox(server, player);
	}

	/** A building asked for: a proposal in the inbox (in the town, a district with room, or the district named); when there is no room, a district first. */
	private static String proposeBuilding(MinecraftServer server, Settlement s, String said, TalkAnswer a, long now) {
		var ps = s.proposals();
		if (ps.open().size() >= MAX_OPEN_ASKED) return "The inbox already holds " + ps.open().size() + " ideas: decide on some of those first, then ask me again.";
		String district = null;
		if (a.district() != null) {
			district = s.lands().districts().stream().filter(d -> d.name().equalsIgnoreCase(a.district()) || d.name().toLowerCase().contains(a.district().toLowerCase()))
				.map(Settlement.District::id).findFirst().orElse(null);
		}
		if (district == null) {
			var counts = Districts.counts(server, s);
			var where = ProposalRules.where(counts.town(), counts.districts(), Proposals.full(s.id(), now));
			if (where.full()) {
				String way = Districts.firstWay(server, s, List.of());
				if (way == null) return "There is no room left in the town or its districts, and no way to found another. Expand the claim first.";
				addProposal(s, Proposal.district(way, "You asked for " + a.building().role() + ", and the town's street is full.", now));
				return "The town's street is full, so I proposed a district to the " + DistrictRules.directionName(way) + " first. Once it stands, ask me again for the "
					+ a.building().role() + ".";
			}
			district = where.district();
		}
		String role = a.building().role();
		Proposal p = new Proposal("asked_" + Long.toString(now, 36), Character.toUpperCase(role.charAt(0)) + role.substring(1), "You asked: \"" + clip(said, 120) + "\"",
			a.building(), now, "asked", district, null);
		return addProposal(s, p) ? a.reply() : "I could not save that idea; try again.";
	}

	/** A district asked for: a proposal in the inbox, towards the way named or the first that fits. */
	private static String proposeDistrict(MinecraftServer server, Settlement s, String said, TalkAnswer a, long now) {
		if (s.lands().districts().size() >= DistrictRules.DIRECTIONS.size()) return "There is a district every way already.";
		String way = a.way();
		if (way != null) {
			var placed = DistrictRules.place(s.claim(), Districts.townStreet(server, s), s.lands().districts(), Settlements.others(s.id()), s.lands().protect(), way,
				DistrictRules.RADII[0]);
			if (!placed.ok()) return placed.note();
		} else {
			way = Districts.firstWay(server, s, List.of());
			if (way == null) return "There is no way left to found a district that fits.";
		}
		String finalWay = way;
		if (s.proposals().open().stream().anyMatch(x -> finalWay.equals(x.found()))) return "That district already waits in the inbox.";
		Proposal p = Proposal.district(way, "You asked: \"" + clip(said, 120) + "\"", now);
		if (a.district() != null) p = new Proposal(p.key(), Character.toUpperCase(a.district().charAt(0)) + a.district().substring(1) + " (" + DistrictRules.directionName(way)
			+ ")", p.why(), null, now, p.trigger(), a.district(), way);
		return addProposal(s, p) ? a.reply() : "I could not save that idea; try again.";
	}

	/** Adds a proposal the player asked for (no cooldown: they asked), replacing one with the same key; a declined key asked for again is forgiven. */
	private static boolean addProposal(Settlement s, Proposal p) {
		var ps = s.proposals();
		List<Proposal> open = new ArrayList<>(ps.open().stream().filter(x -> !x.key().equals(p.key())).toList());
		open.add(p);
		return Settlements.proposals(s.id(), new Settlement.Proposals(open, ps.declined().stream().filter(k -> !k.equals(p.key())).toList(), ps.accepted(), ps.lastAt())).ok();
	}

	/** The steward says it: its speech bubble, and a chat line to the player. */
	private static void answer(MinecraftServer server, UUID player, String settlementId, String text) {
		StewardVoice.say(server, settlementId, text);
		var p = server.getPlayerList().getPlayer(player);
		var s = Settlements.store().get(settlementId);
		if (p != null) p.sendSystemMessage(Component.literal("[" + (s.isPresent() ? s.get().name() : "Steward") + "] " + text));
	}

	static TalkRules.Facts facts(MinecraftServer server, Settlement s) {
		var counts = Districts.counts(server, s);
		int buildings = counts.town() + counts.districts().values().stream().mapToInt(Integer::intValue).sum();
		String doing = StewardStatus.of(s.id(), "looking after the place").activity();
		return new TalkRules.Facts(s.name(), doing, buildings, s.lands().districts().size(), s.proposals().open().size());
	}

	static TalkJob.Context context(MinecraftServer server, Settlement s) {
		List<String> town = new ArrayList<>();
		Map<String, List<String>> per = new HashMap<>();
		for (SiteView v : ArchitectApi.get().sites(server).list(s.owner())) {
			if (!"building".equals(v.kind())) continue;
			var b = v.box();
			var in = s.districtAt(s.claim().dimension(), (b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2);
			String role = Updates.lotOf(v);
			if (in.isPresent()) per.computeIfAbsent(in.get().id(), k -> new ArrayList<>()).add(role);
			else town.add(role);
		}
		List<String> districts = s.lands().districts().stream().map(d -> d.name() + " (" + DistrictRules.directionName(d.direction()) + "): "
			+ (per.containsKey(d.id()) ? String.join(", ", per.get(d.id())) : "nothing built yet")).toList();
		var card = s.card();
		var counts = Districts.counts(server, s);
		boolean full = ProposalRules.where(counts.town(), new java.util.LinkedHashMap<>(), Proposals.full(s.id(), System.currentTimeMillis())).full();
		return new TalkJob.Context(s.name(), card.site().text(), card.style().text(), card.purpose().text(), town, districts,
			s.proposals().open().stream().map(Proposal::title).toList(), s.permission().name().toLowerCase(), full);
	}

	private static String clip(String t, int max) {
		return t.length() <= max ? t : t.substring(0, max - 1) + "…";
	}

	/** Whether a talk job of the settlement is in flight (for the screens). */
	public static boolean thinking(String settlementId) {
		return THINKING.contains(settlementId);
	}
}
