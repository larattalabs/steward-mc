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
import net.minecraft.core.BlockPos;
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
	private record Pending(String settlementId, UUID player, String said, int session, long sentAt) {}

	private static final Map<String, Pending> PENDING = new HashMap<>();
	/**
	 * When each settlement's talk job was sent: one at a time. It frees when the job's answer comes, else after {@link #HOLD_MS} (a job that never reports,
	 * or an acknowledgement that was lost: it may still run, so the next one waits its time out rather than overlap it at once).
	 */
	private static final Map<String, Long> THINKING = new HashMap<>();
	static final long HOLD_MS = 3 * 60_000L;
	/** Talk jobs sent in the past hour, per world and settlement; kept across a world's reopening in this game, so leaving and coming back resets nothing. */
	private static final Map<String, ArrayDeque<Long>> RECENT = new HashMap<>();
	/** The server of the world that is running (talk jobs finish on its thread). */
	private static volatile @Nullable MinecraftServer server;

	private Talk() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			server = null;
			dev.larattalabs.steward.gateway.SimAnswers.clear();
			PENDING.clear();
			THINKING.clear();
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
		long now = System.currentTimeMillis();
		if (thinking(s.id(), now)) return Actions.Result.fail("The steward is still thinking about what you said last.");
		// the world by its folder (names repeat between saves, settlement ids too)
		String world = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString();
		var recent = RECENT.computeIfAbsent(world + "#" + s.id(), k -> new ArrayDeque<>());
		while (!recent.isEmpty() && now - recent.peekFirst() > 3600_000L) recent.pollFirst();
		if (recent.size() >= MAX_PER_HOUR) return Actions.Result.fail("That is a lot of talk for one hour: give the steward a while before the next question.");
		var jobs = ArchitectApi.get().jobs();
		if (!jobs.available()) {
			answer(server, p.getUUID(), s.id(), "I cannot think that through right now: Architect's Claude link is off. The settlement screen still works.");
			return Actions.Result.ok("");
		}
		dev.larattalabs.architect.api.JobSpec spec;
		try {
			spec = TalkJob.build(said, context(server, s), s.owner(), dev.larattalabs.steward.gateway.SimAnswers.ext(dev.larattalabs.steward.gateway.SimAnswers.TALK));
		} catch (RuntimeException e) {
			Steward.LOGGER.warn("talk job for {} could not be made", s.id(), e);
			return Actions.Result.fail("The steward could not take that in (see the log).");
		}
		recent.addLast(now);
		THINKING.put(s.id(), now);
		int session = Session.current();
		Pending pending = new Pending(s.id(), p.getUUID(), said, session, now);
		jobs.run(spec).whenComplete((id, err) -> {
			if (!Session.is(session)) return;
			if (err != null) {
				// the job may have started though its acknowledgement was lost: the slot stays held until its time is up, so a retry cannot overlap it at once
				answer(server, p.getUUID(), s.id(), "I could not think that through: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage())
					+ ". Give me a few minutes.");
				return;
			}
			PENDING.put(id, pending);
			// it may have finished between the ack and now
			jobs.get(id).filter(Job::finished).ifPresent(j -> onDone(server, j));
		});
		StewardVoice.say(server, s.id(), "Let me think about that.");
		return Actions.Result.ok("");
	}

	private static void onDone(MinecraftServer server, Job job) {
		Pending p = PENDING.remove(job.id());
		if (p == null || !Session.is(p.session())) return;
		// frees the slot only if it is still this job's (a newer one may hold it after this one's time ran out)
		THINKING.remove(p.settlementId(), p.sentAt());
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
			case DISTRICT -> {
				// the side may need a look at the land first: it answers when it has chosen
				proposeDistrict(server, player, s, said, a, now);
				return;
			}
			case CHANGE -> reply = proposeChange(server, s, said, a, now);
			default -> {
			}
		}
		answer(server, player, settlementId, reply);
		SettlementRunner.sendInbox(server, player);
	}

	/** A building asked for: a proposal in the inbox (in the town, a district with room, or the district named); when there is no room, a district first. */
	private static String proposeBuilding(MinecraftServer server, Settlement s, String said, TalkAnswer a, long now) {
		String district = null;
		if (a.district() != null) {
			// the district named: by its name, else the one name that contains it; none or several, and the steward asks
			var named = s.lands().districts().stream().filter(d -> d.name().equalsIgnoreCase(a.district())).toList();
			if (named.isEmpty()) named = s.lands().districts().stream().filter(d -> d.name().toLowerCase().contains(a.district().toLowerCase())).toList();
			if (named.size() != 1) return named.isEmpty() ? "I do not know a district called " + a.district() + ". Which one did you mean?"
				: "More than one district goes by " + a.district() + ": which one?";
			if (!named.get(0).square()) return named.get(0).name() + " is reserved beside the town: its first building builds it, and that isn't ready yet.";
			district = named.get(0).id();
		}
		if (district == null) {
			var counts = Districts.counts(server, s);
			var where = ProposalRules.where(counts.town(), counts.districts(), Proposals.full(s.id(), now));
			if (where.full()) {
				// a district first: the one already waiting, else the first way the player has not declined
				var waiting = s.proposals().open().stream().filter(Proposal::foundsDistrict).findFirst();
				if (waiting.isPresent()) return "The town's street is full, and " + waiting.get().title().toLowerCase() + " waits in the inbox: found it, then ask me again for the "
					+ a.building().role() + ".";
				java.util.Set<String> declined = new HashSet<>();
				for (String d : DistrictRules.DIRECTIONS) if (s.proposals().declined().contains("district_" + d)) declined.add(d);
				String way = Districts.firstWay(server, s, declined);
				if (way == null) return "There is no room left in the town or its districts, and no way left to found another. Expand the claim first.";
				String added = addProposal(s, Proposal.district(way, "You asked for " + a.building().role() + ", and the town's street is full.", now));
				if (added != null) return added;
				return "The town's street is full, so I proposed a district to the " + DistrictRules.directionName(way) + " first. Once it stands, ask me again for the "
					+ a.building().role() + ".";
			}
			district = where.district();
		}
		String role = a.building().role();
		Proposal p = new Proposal("asked_" + Long.toString(now, 36) + "_" + a.building().type(), Character.toUpperCase(role.charAt(0)) + role.substring(1),
			"You asked: \"" + clip(said, 120) + "\"", a.building(), now, "asked", district, null);
		String added = addProposal(s, p);
		return added != null ? added : a.reply();
	}

	/**
	 * A change to a standing building: with Architect's {@code versionOf}, a change proposal in the inbox (designing it is paid, so the player accepts it);
	 * before that, noted in the history.
	 */
	private static String proposeChange(MinecraftServer server, Settlement s, String said, TalkAnswer a, long now) {
		if (!ChangeRequests.available() || "settlement".equalsIgnoreCase(a.changeTarget())) {
			String note = ("Asked to change " + a.changeTarget() + ": " + a.changeRequest()).replaceAll("\\s+", " ");
			if (!Settlements.log(s.id(), new Settlement.LogEntry(now, Settlement.Kind.NOTE, note, List.of())).ok()) return "I could not note that down (the settlement could not be saved).";
			return ChangeRequests.available() ? "I have noted it. Changing the whole settlement at once comes later: ask me about one building at a time." : a.reply();
		}
		var site = ChangeRequests.building(server, s, a.changeTarget());
		if (site.isEmpty()) return "Which building do you mean? I could not tell which one is the " + a.changeTarget() + ".";
		String name = ChangeRequests.the(site.get());
		if (ChangeRequests.designing(site.get().id())) return "A change to " + name + " is being designed already.";
		Proposal p = Proposal.change(site.get().id(), name, a.changeRequest(), said, now);
		String added = addProposal(s, p);
		if (added != null) return added;
		return "A change to " + name + ": I have drawn it up and put it in the inbox (about $" + Proposals.estimate(p) + " to design). Once designed, you preview it before it is applied.";
	}

	/** A district asked for: a proposal in the inbox, towards the way named or the first that fits. */
	/** "Here", "where I stand": the player points at the place. */
	private static final java.util.regex.Pattern HERE = java.util.regex.Pattern.compile("\\b(here|where i('m| am)( standing)?|where i stand)\\b");

	/**
	 * A district asked for: a proposal in the inbox, on the side said, else the side nearest where the player stands (when they say "here"), else the free
	 * side whose ground suits what they asked for ("a harbour": by the water; docs/PLAN.md "Zones", first step), else the first free side. The reply comes
	 * once the side is chosen (a look at the land can take a moment).
	 */
	private static void proposeDistrict(MinecraftServer server, UUID player, Settlement s, String said, TalkAnswer a, long now) {
		if (s.lands().districts().size() >= DistrictRules.DIRECTIONS.size()) { answer(server, player, s.id(), "There is a district every way already."); return; }
		String way = a.way();
		if (way != null) {
			var placed = DistrictRules.place(s.claim(), Districts.townStreet(server, s), s.lands().districts(), Settlements.others(s.id()), s.lands().protect(), way,
				DistrictRules.RADII[0]);
			if (!placed.ok()) { answer(server, player, s.id(), placed.note()); return; }
			answer(server, player, s.id(), offerDistrict(s, said, a, way, "", now));
			SettlementRunner.sendInbox(server, player);
			return;
		}
		// any side the player has not declined (a district already waiting is replaced: "no, put it by the lake" places it again)
		java.util.Set<String> skip = new HashSet<>();
		for (String d : DistrictRules.DIRECTIONS) if (s.proposals().declined().contains("district_" + d)) skip.add(d);
		var site = dev.larattalabs.steward.model.DistrictSite.fromWords(said + " " + (a.district() == null ? "" : a.district()));
		var p = server.getPlayerList().getPlayer(player);
		BlockPos standing = p != null && HERE.matcher(said.toLowerCase(java.util.Locale.ROOT)).find() ? p.blockPosition() : null;
		Districts.choose(server, s, site, standing, skip, c -> {
			var now2 = Settlements.store().get(s.id());
			if (now2.isEmpty()) return;
			String reply = c.way() == null ? "There is no way left to found a district that fits." : offerDistrict(now2.get(), said, a, c.way(), c.why(), now);
			answer(server, player, s.id(), reply);
			SettlementRunner.sendInbox(server, player);
		});
	}

	/**
	 * The district's proposal, on {@code way}, saying why that side ({@code why}, "" for none); returns the reply. One district waits at a time: a new ask
	 * replaces the one waiting (keeping its name when the new ask gives none), so the player can move it by saying where.
	 */
	private static String offerDistrict(Settlement s, String said, TalkAnswer a, String way, String why, long now) {
		String side = DistrictRules.directionName(way);
		var waiting = s.proposals().open().stream().filter(Proposal::foundsDistrict).findFirst();
		if (waiting.isPresent()) {
			String name = a.district() != null ? a.district() : waiting.get().district();
			a = new TalkAnswer(a.reply(), a.intent(), a.building(), a.way(), name, a.changeTarget(), a.changeRequest());
			var ps = s.proposals();
			var rest = ps.open().stream().filter(x -> !x.foundsDistrict()).toList();
			if (!Settlements.proposals(s.id(), new Settlement.Proposals(rest, ps.declined(), ps.accepted(), ps.lastAt())).ok()) return "I could not save that (the settlement could not be saved).";
			s = Settlements.store().get(s.id()).orElse(s);
		}
		Proposal p = Proposal.district(way, "You asked: \"" + clip(said, 120) + "\"" + (why.isEmpty() ? "" : ". To the " + side + ": " + why), now);
		if (a.district() != null) p = new Proposal(p.key(), Character.toUpperCase(a.district().charAt(0)) + a.district().substring(1) + " (" + side + ")", p.why(), null, now,
			p.trigger(), a.district(), way);
		String added = addProposal(s, p);
		if (added != null) return added;
		String moved = waiting.isPresent() && !way.equals(waiting.get().found()) ? " (instead of to the " + DistrictRules.directionName(waiting.get().found()) + ")" : "";
		return why.isEmpty() ? a.reply() + moved : a.reply() + " I would put it to the " + side + moved + ", " + why + ".";
	}

	/**
	 * Adds a proposal the player asked for (no cooldown: they asked; a declined key asked for again by name is forgiven), at most {@link #MAX_OPEN_ASKED}
	 * open. Null when it is saved, else what to tell the player.
	 */
	private static @Nullable String addProposal(Settlement s, Proposal p) {
		var ps = s.proposals();
		// a waiting part of the plan takes none of the room
		if (ps.open().stream().noneMatch(x -> x.key().equals(p.key())) && ps.open().stream().filter(x -> !x.offersPart()).count() >= MAX_OPEN_ASKED) {
			return "The inbox already holds " + ps.open().size() + " ideas: decide on some of those first, then ask me again.";
		}
		List<Proposal> open = new ArrayList<>(ps.open().stream().filter(x -> !x.key().equals(p.key())).toList());
		open.add(p);
		boolean ok = Settlements.proposals(s.id(), new Settlement.Proposals(open, ps.declined().stream().filter(k -> !k.equals(p.key())).toList(), ps.accepted(), ps.lastAt())).ok();
		return ok ? null : "I could not save that idea (the settlement could not be saved).";
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
		return thinking(settlementId, System.currentTimeMillis());
	}

	private static boolean thinking(String settlementId, long now) {
		Long at = THINKING.get(settlementId);
		return at != null && now - at < HOLD_MS;
	}
}
