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
		if (here.isEmpty() || !here.get().described() || SettlementRunner.busy(here.get().id())) return;
		Settlement s = here.get();
		var open = s.proposals().open();
		List<Proposal> fresh = ProposalRules.propose(signs(server, p, s), types(s), new HashSet<>(s.proposals().declined()), open, s.proposals().lastAt(), now);
		if (fresh.isEmpty()) return;
		List<Proposal> all = new ArrayList<>(open);
		all.addAll(fresh);
		var res = Settlements.proposals(s.id(), new Settlement.Proposals(all, s.proposals().declined(), s.proposals().accepted(), now));
		if (!res.ok()) return;
		Proposal first = fresh.get(0);
		// Autonomous and Full build their own proposals while the week's allowance holds (Noah: $500 a week by default); else the player decides
		int estimate = estimate(first);
		var saved = res.settlement();
		if (!saved.permission().needsApproval(dev.larattalabs.steward.model.Permission.Action.NEW_PROJECT) && saved.autonomy().allows(estimate, now)) {
			var started = Actions.acceptProposal(p, saved, first.key());
			if (started.ok()) {
				var after = Settlements.store().get(s.id()).orElse(saved);
				Settlements.autonomy(s.id(), after.autonomy().with(new Settlement.Spend(now, estimate, first.title())));
				StewardVoice.say(server, s.id(), String.format("I am building %s: %s $%d of the week's $%.0f.", first.title().toLowerCase(), first.why(),
					(int) Math.round(after.autonomy().spentInWeek(now) + estimate), after.autonomy().weeklyUsd()));
				SettlementRunner.sendInbox(server, p.getUUID());
				return;
			}
		}
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

	/** One inbox entry per settlement with proposals waiting. */
	public static List<InboxModel.Entry> inboxEntries() {
		List<InboxModel.Entry> out = new ArrayList<>();
		for (Settlement s : Settlements.store().all()) {
			if (s.proposals().open().isEmpty()) continue;
			var ideas = s.proposals().open().stream().map(p -> new InboxModel.Lot(p.key(), p.title(), "proposal", false, true, p.why() + " About $" + estimate(p) + ".")).toList();
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
