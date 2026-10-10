package dev.larattalabs.steward.service;

import dev.larattalabs.steward.inbox.InboxModel;
import dev.larattalabs.steward.model.Progress;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.ProposalRules;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;

/**
 * The steward's proposals (docs/PLAN.md phase 3e): once a minute, for a host standing in their described settlement while nothing is being built, it reads
 * the signs (their tier, the seeds and saplings they carry, animals gathering near them in the claim) and asks {@link ProposalRules}; a new proposal is
 * saved with the settlement, said by the steward, and waits in the inbox to be built or declined. Server thread.
 */
public final class Proposals {
	/** Ticks between readings of the signs. */
	static final int EVERY = 20 * 60;
	/** Animals counted within this distance of the player (and inside the claim). */
	static final int ANIMALS_NEAR = 24;

	private Proposals() {
	}

	public static void init() {
		// settlement ids repeat across worlds: back-offs are one world's
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			BACKOFF.clear();
			FULL.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % EVERY != 0) return;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				try {
					consider(server, p, System.currentTimeMillis());
				} catch (RuntimeException e) {
					dev.larattalabs.steward.Steward.LOGGER.warn("proposals for {} failed", p.getName().getString(), e);
				}
			}
		});
	}

	static void consider(MinecraftServer server, ServerPlayer p, long now) {
		if (Actions.refusal(p) != null) return;
		var here = Settlements.at(p.level(), p.blockPosition());
		if (here.isEmpty()) return;
		// a district road an interrupted founding or link left, or a link not laid yet (its way was not loaded), is put right while the host is here; it says
		// so in the log (a link that keeps failing would otherwise repeat in chat every minute)
		Districts.reconcile(server, here.get().id(), m -> dev.larattalabs.steward.Steward.LOGGER.info("{}: {}", here.get().id(), m));
		// proposals are additions: only for a settlement that has something standing (an addition faces its street)
		if (!here.get().described() || SettlementRunner.busy(here.get().id()) || here.get().lastUndoable().isEmpty()) return;
		Settlement s = here.get();
		var open = s.proposals().open();
		// Autonomous and Full: a proposal already waiting is built once the allowance allows (raised, renewed, or the level just changed)
		if (!s.permission().needsApproval(dev.larattalabs.steward.model.Permission.Action.NEW_PROJECT) && !open.isEmpty()) {
			// the first one not backing off (one that found no room waits a while, so it does not hold the others up); a new district always waits for the player
			// what the player asked for in talk waits for them too (the steward told them so)
			var waiting = open.stream().filter(x -> !x.foundsDistrict() && !"asked".equals(x.trigger()) && BACKOFF.getOrDefault(s.id() + "#" + x.key(), 0L) <= now).findFirst();
			if (waiting.isPresent() && s.autonomy().allows(estimate(waiting.get()), now) && autonomously(server, p, s, waiting.get(), now)) return;
		}
		// where a new building goes: the town while its street has room, else a district with room; when none has, a new district is proposed
		var counts = Districts.counts(server, s);
		var where = ProposalRules.where(counts.town(), counts.districts(), full(s.id(), now));
		List<Proposal> fresh;
		if (where.full()) {
			fresh = districtProposal(server, s, open, now);
		} else {
			fresh = ProposalRules.propose(signs(server, p, s), types(s), new HashSet<>(s.proposals().declined()), open, s.proposals().lastAt(), now).stream()
				.map(x -> x.in(where.district())).toList();
		}
		if (fresh.isEmpty()) return;
		List<Proposal> all = new ArrayList<>(open);
		all.addAll(fresh);
		var res = Settlements.proposals(s.id(), new Settlement.Proposals(all, s.proposals().declined(), s.proposals().accepted(), now));
		if (!res.ok()) return;
		Proposal first = fresh.get(0);
		// Autonomous and Full build their own proposals while the week's allowance holds (Noah: $500 a week by default); else the player decides
		var saved = res.settlement();
		if (!first.foundsDistrict() && !saved.permission().needsApproval(dev.larattalabs.steward.model.Permission.Action.NEW_PROJECT) && saved.autonomy().allows(estimate(first), now)
			&& autonomously(server, p, saved, first, now)) return;
		// in the settlement's own words when Claude can word it (the steward says it when they come), else in the rule's
		if (ProposalWords.start(server, saved, first, p.getUUID())) return;
		StewardVoice.say(server, s.id(), "I have an idea: " + first.title().toLowerCase() + ". " + first.why() + " See the inbox (Y).");
		SettlementRunner.sendInbox(server, p.getUUID());
	}

	/** Lands (the town: "", or a district's id) where an addition found no room lately, per settlement: they count as full for a while. */
	private static final Map<String, Long> FULL = new HashMap<>();
	/** How long a land that had no room counts as full (it may have been cleared or expanded since). */
	static final long FULL_MS = 60 * 60_000L;

	/** An addition found no room in the town ({@code districtId} null) or a district. */
	static void full(String settlementId, @org.jspecify.annotations.Nullable String districtId) {
		FULL.put(settlementId + "#" + (districtId == null ? "" : districtId), System.currentTimeMillis());
	}

	/** The settlement's lands that had no room within {@link #FULL_MS}: "" the town, else district ids. */
	static Set<String> full(String settlementId, long now) {
		Set<String> out = new HashSet<>();
		String prefix = settlementId + "#";
		FULL.forEach((k, at) -> {
			if (k.startsWith(prefix) && now - at < FULL_MS) out.add(k.substring(prefix.length()));
		});
		return out;
	}

	/** Adds a district proposal towards {@code way} now (dev: the e2e check, without filling a street first). */
	public static String proposeDistrict(String settlementId, String way, long now) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return "No such settlement: " + settlementId;
		List<Proposal> all = new ArrayList<>(s.get().proposals().open().stream().filter(x -> !x.key().equals("district_" + way)).toList());
		all.add(Proposal.district(way, "The town's street is full (dev).", now));
		var ps = s.get().proposals();
		var res = Settlements.proposals(settlementId, new Settlement.Proposals(all, ps.declined().stream().filter(k -> !k.equals("district_" + way)).toList(), ps.accepted(), ps.lastAt()));
		return res.ok() ? "proposed district_" + way : res.error();
	}

	/** A district proposal (phase 3f) when the town's street and every district's are full: towards the first way one fits, not one the player declined. */
	static List<Proposal> districtProposal(MinecraftServer server, Settlement s, List<Proposal> open, long now) {
		if (!ProposalRules.room(open, s.proposals().lastAt(), now) || open.stream().anyMatch(Proposal::foundsDistrict)) return List.of();
		if (s.lands().districts().size() >= dev.larattalabs.steward.model.DistrictRules.DIRECTIONS.size()) return List.of();
		Set<String> skip = new HashSet<>();
		for (String d : dev.larattalabs.steward.model.DistrictRules.DIRECTIONS) if (s.proposals().declined().contains("district_" + d)) skip.add(d);
		String way = Districts.firstWay(server, s, skip);
		if (way == null) return List.of();
		return List.of(Proposal.district(way, "The town's street is full: a district to the " + dev.larattalabs.steward.model.DistrictRules.directionName(way)
			+ " gives the next buildings a street of their own. Founding it lays a road and costs nothing.", now));
	}

	/** The building types the settlement has or has planned: its card's program and the proposals it accepted. */
	static Set<String> types(Settlement s) {
		Set<String> out = new HashSet<>();
		if (s.card() != null && s.card().hasProgram()) s.card().program().forEach(b -> out.add(b.type()));
		for (String k : s.proposals().accepted()) {
			String t = ProposalRules.typeOf(k);
			if (t != null) out.add(t);
		}
		// and what waits in the inbox (a building the player asked for in talk is not proposed again by the rules)
		for (Proposal p : s.proposals().open()) if (p.type() != null) out.add(p.type());
		return out;
	}

	/** What the steward sees of the player: their tier, the seeds and saplings they carry, animals gathering in the claim near them. */
	static ProposalRules.Signs signs(MinecraftServer server, ServerPlayer p, Settlement s) {
		Set<String> done = new HashSet<>();
		for (String id : Progress.advancements()) {
			var adv = server.getAdvancements().get(Identifier.parse(id));
			if (adv != null && p.getAdvancements().getOrStartProgress(adv).isDone()) done.add(id);
		}
		int seeds = 0, saplings = 0;
		for (ItemStack st : p.getInventory()) {
			if (st.isEmpty()) continue;
			if (BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().endsWith("_seeds")) seeds += st.getCount();
			if (st.is(ItemTags.SAPLINGS)) saplings += st.getCount();
		}
		Map<String, Integer> animals = new HashMap<>();
		String dim = p.level().dimension().identifier().toString();
		for (Animal a : p.level().getEntitiesOfClass(Animal.class, p.getBoundingBox().inflate(ANIMALS_NEAR))) {
			if (!s.contains(dim, a.getBlockX(), a.getBlockY(), a.getBlockZ())) continue;
			animals.merge(BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).toString(), 1, Integer::sum);
		}
		return new ProposalRules.Signs(Progress.tier(done), seeds, saplings, animals);
	}

	/** Starts a proposal on the steward's own authority; the spend is recorded as the build starts ({@link #recordAccepted}). */
	private static boolean autonomously(MinecraftServer server, ServerPlayer p, Settlement s, Proposal pr, long now) {
		int estimate = estimate(pr);
		var started = Actions.acceptProposal(p, s, pr.key(), true);
		if (!started.ok()) return false;
		StewardVoice.say(server, s.id(), String.format("I am building %s on my own: %s It takes up to $%d of the week's $%.0f allowance.", pr.title().toLowerCase(), pr.why(),
			estimate, s.autonomy().weeklyUsd()));
		SettlementRunner.sendInbox(server, p.getUUID());
		return true;
	}

	/**
	 * As an accepted proposal's build starts: the proposal leaves the open ones for the accepted, and an autonomous build's budget is recorded against the week's
	 * allowance, in one save. False when it cannot be saved (the build then does not start).
	 */
	static boolean recordAccepted(String settlementId, String key, Settlement.@org.jspecify.annotations.Nullable Spend spend) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return false;
		var ps = s.get().proposals();
		// checked again now, after the survey: the player may have declined it or lowered the allowance meanwhile
		if (ps.open().stream().noneMatch(x -> x.key().equals(key))) return false;
		if (spend != null && !s.get().autonomy().allows(spend.usd(), System.currentTimeMillis())) return false;
		List<String> accepted = new ArrayList<>(ps.accepted());
		if (!accepted.contains(key)) accepted.add(key);
		Settlement n = s.get().withProposals(new Settlement.Proposals(ps.open().stream().filter(x -> !x.key().equals(key)).toList(), ps.declined(), accepted, ps.lastAt()));
		if (spend != null) n = n.withAutonomy(n.autonomy().with(spend));
		return Settlements.replace(n).ok();
	}
	/** Settlement#key -> when a proposal that could not be started (no room, a failed survey) may be tried on its own again. This session. */
	private static final Map<String, Long> BACKOFF = new HashMap<>();
	static final long BACKOFF_MS = 30 * 60_000L;

	/** A proposal's build never started: the steward tries another before this one again. */
	static void backOff(String settlementId, String key) {
		BACKOFF.put(settlementId + "#" + key, System.currentTimeMillis() + BACKOFF_MS);
	}

	/** A proposal's build ended before Architect took any paid request of it: the proposal waits again and its recorded spend is taken off. */
	static void giveBack(String settlementId, Proposal given, Settlement.@org.jspecify.annotations.Nullable Spend spend) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return;
		String key = given.key();
		var ps = s.get().proposals();
		// the proposal as it was accepted (an asked-for building, its district), not one made again from the rules
		var proposal = ps.open().stream().anyMatch(x -> x.key().equals(key)) ? null : given;
		List<Proposal> open = new ArrayList<>(ps.open());
		if (proposal != null) open.add(proposal);
		Settlement n = s.get().withProposals(new Settlement.Proposals(open, ps.declined(), ps.accepted().stream().filter(k -> !k.equals(key)).toList(), ps.lastAt()));
		// only the spend this acceptance recorded (a build the player accepted recorded none)
		var spends = new ArrayList<>(n.autonomy().spends());
		if (spend != null) spends.remove(spend);
		if (!Settlements.replace(n.withAutonomy(new Settlement.Autonomy(n.autonomy().weeklyUsd(), spends))).ok()) {
			dev.larattalabs.steward.Steward.LOGGER.warn("could not give proposal {} of {} back (the settlement could not be saved)", key, settlementId);
		}
	}

	/** One inbox entry per settlement with proposals waiting. */
	public static List<InboxModel.Entry> inboxEntries() {
		List<InboxModel.Entry> out = new ArrayList<>();
		for (Settlement s : Settlements.store().all()) {
			if (s.proposals().open().isEmpty()) continue;
			// the cost first, where the row never cuts it
			var ideas = s.proposals().open().stream().map(p -> new InboxModel.Lot(p.key(), p.title(), "proposal", false, true, (p.foundsDistrict() ? "free" : "about $" + estimate(p))
				+ (p.district() == null ? "" : " · in " + s.lands().district(p.district()).map(Settlement.District::name).orElse("a district")) + " · " + p.why())).toList();
			out.add(InboxModel.proposals(s.id(), s.name(), ideas));
		}
		return out;
	}

	/** About what building a proposal costs (the high estimate of one building, rounded up to $5): its budget when accepted. A district costs nothing. */
	public static int estimate(Proposal p) {
		if (p.foundsDistrict()) return 0;
		var e = dev.larattalabs.steward.model.BudgetPolicy.estimate(1, 0);
		return (int) (Math.ceil(e.usdHigh() / 5.0) * 5);
	}

	/** Declines a proposal: it leaves the inbox and is never made again. */
	public static String decline(MinecraftServer server, Settlement s, String key) {
		var p = s.proposals();
		if (p.open().stream().noneMatch(x -> x.key().equals(key))) return "No proposal " + key + " waits.";
		List<String> declined = new ArrayList<>(p.declined());
		declined.add(key);
		var res = Settlements.proposals(s.id(), new Settlement.Proposals(p.open().stream().filter(x -> !x.key().equals(key)).toList(), declined, p.accepted(), p.lastAt()));
		return res.ok() ? "Noted: the steward will not propose that again." : res.error();
	}
}
