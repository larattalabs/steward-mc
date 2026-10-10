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
		// proposals are additions: only for a settlement that has something standing (an addition faces its street)
		if (here.isEmpty() || !here.get().described() || SettlementRunner.busy(here.get().id()) || here.get().lastUndoable().isEmpty()) return;
		Settlement s = here.get();
		var open = s.proposals().open();
		// Autonomous and Full: a proposal already waiting is built once the allowance allows (raised, renewed, or the level just changed)
		if (!s.permission().needsApproval(dev.larattalabs.steward.model.Permission.Action.NEW_PROJECT) && !open.isEmpty()) {
			// the first one not backing off (one that found no room waits a while, so it does not hold the others up)
			var waiting = open.stream().filter(x -> BACKOFF.getOrDefault(s.id() + "#" + x.key(), 0L) <= now).findFirst();
			if (waiting.isPresent() && s.autonomy().allows(estimate(waiting.get()), now) && autonomously(server, p, s, waiting.get(), now)) return;
		}
		List<Proposal> fresh = ProposalRules.propose(signs(server, p, s), types(s), new HashSet<>(s.proposals().declined()), open, s.proposals().lastAt(), now);
		if (fresh.isEmpty()) return;
		List<Proposal> all = new ArrayList<>(open);
		all.addAll(fresh);
		var res = Settlements.proposals(s.id(), new Settlement.Proposals(all, s.proposals().declined(), s.proposals().accepted(), now));
		if (!res.ok()) return;
		Proposal first = fresh.get(0);
		// Autonomous and Full build their own proposals while the week's allowance holds (Noah: $500 a week by default); else the player decides
		var saved = res.settlement();
		if (!saved.permission().needsApproval(dev.larattalabs.steward.model.Permission.Action.NEW_PROJECT) && saved.autonomy().allows(estimate(first), now)
			&& autonomously(server, p, saved, first, now)) return;
		StewardVoice.say(server, s.id(), "I have an idea: " + first.title().toLowerCase() + ". " + first.why() + " See the inbox (Y).");
		SettlementRunner.sendInbox(server, p.getUUID());
	}

	/** The building types the settlement has or has planned: its card's program and the proposals it accepted. */
	static Set<String> types(Settlement s) {
		Set<String> out = new HashSet<>();
		if (s.card() != null && s.card().hasProgram()) s.card().program().forEach(b -> out.add(b.type()));
		for (String k : s.proposals().accepted()) {
			String t = ProposalRules.typeOf(k);
			if (t != null) out.add(t);
		}
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
			if (!s.claim().contains(dim, a.getBlockX(), a.getBlockY(), a.getBlockZ())) continue;
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
	static boolean recordAccepted(String settlementId, String key, double spendUsd, String what) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return false;
		var ps = s.get().proposals();
		// checked again now, after the survey: the player may have declined it or lowered the allowance meanwhile
		if (ps.open().stream().noneMatch(x -> x.key().equals(key))) return false;
		if (spendUsd > 0 && !s.get().autonomy().allows(spendUsd, System.currentTimeMillis())) return false;
		List<String> accepted = new ArrayList<>(ps.accepted());
		if (!accepted.contains(key)) accepted.add(key);
		Settlement n = s.get().withProposals(new Settlement.Proposals(ps.open().stream().filter(x -> !x.key().equals(key)).toList(), ps.declined(), accepted, ps.lastAt()));
		if (spendUsd > 0) n = n.withAutonomy(n.autonomy().with(new Settlement.Spend(System.currentTimeMillis(), spendUsd, what)));
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
	static void giveBack(String settlementId, String key, String what) {
		var s = Settlements.store().get(settlementId);
		if (s.isEmpty()) return;
		var ps = s.get().proposals();
		var proposal = ps.open().stream().anyMatch(x -> x.key().equals(key)) ? null : ProposalRules.find(key);
		List<Proposal> open = new ArrayList<>(ps.open());
		if (proposal != null) open.add(proposal);
		Settlement n = s.get().withProposals(new Settlement.Proposals(open, ps.declined(), ps.accepted().stream().filter(k -> !k.equals(key)).toList(), ps.lastAt()));
		var spends = new ArrayList<>(n.autonomy().spends());
		for (int i = spends.size() - 1; i >= 0; i--) if (spends.get(i).what().equals(what)) { spends.remove(i); break; }
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
			var ideas = s.proposals().open().stream().map(p -> new InboxModel.Lot(p.key(), p.title(), "proposal", false, true, "about $" + estimate(p) + " · " + p.why())).toList();
			out.add(InboxModel.proposals(s.id(), s.name(), ideas));
		}
		return out;
	}

	/** About what building a proposal costs (the high estimate of one building, rounded up to $5): its budget when accepted. */
	public static int estimate(Proposal p) {
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
