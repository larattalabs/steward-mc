package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.Job;
import dev.larattalabs.architect.api.SiteEvents;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.ProposalWordsJob;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Words the steward's proposals in the settlement's own terms (docs/PLAN.md phase 3e): a proposal the rules made is saved at once with their plain wording,
 * then one small job ({@link ProposalWordsJob}, at most $0.03) rewords its title, reason and building, and the steward puts it to the player. Only for
 * proposals that wait for the player; when the job fails or the Claude link is off, the rule's wording stays and is said. Server thread.
 */
public final class ProposalWords {
	/** A wording job in flight: which proposal, whom to tell. */
	private record Pending(String settlementId, String key, UUID player, int session) {}

	private static final Map<String, Pending> PENDING = new HashMap<>();
	private static volatile @Nullable MinecraftServer server;

	private ProposalWords() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			server = null;
			PENDING.clear();
		});
		SiteEvents.JOB_DONE.register(job -> {
			var srv = server;
			if (srv != null) onDone(srv, job);
		});
	}

	/** Starts wording {@code p}; false when it cannot (no Claude link, a district, or an error): the caller says the rule's wording. */
	static boolean start(MinecraftServer server, Settlement s, Proposal p, UUID player) {
		if (p.foundsDistrict() || p.building() == null || s.card() == null) return false;
		var jobs = ArchitectApi.get().jobs();
		if (!jobs.available()) return false;
		dev.larattalabs.architect.api.JobSpec spec;
		try {
			var c = s.card();
			spec = ProposalWordsJob.build(s.name() + ", " + c.site().text() + ", " + c.style().text() + ", for " + c.purpose().text(), buildings(server, s), p, s.owner());
		} catch (RuntimeException e) {
			Steward.LOGGER.warn("could not word proposal {} of {}", p.key(), s.id(), e);
			return false;
		}
		int session = Session.current();
		Pending pending = new Pending(s.id(), p.key(), player, session);
		jobs.run(spec).whenComplete((id, err) -> {
			if (!Session.is(session)) return;
			if (err != null) {
				say(server, pending, null);
				return;
			}
			PENDING.put(id, pending);
			jobs.get(id).filter(Job::finished).ifPresent(j -> onDone(server, j));
		});
		return true;
	}

	private static void onDone(MinecraftServer server, Job job) {
		Pending p = PENDING.remove(job.id());
		if (p == null || !Session.is(p.session())) return;
		var words = ProposalWordsJob.interpret(job);
		if (words == null) Steward.LOGGER.info("proposal {} of {}: no usable wording ({}), the rule's stays", p.key(), p.settlementId(), job.status());
		say(server, p, words == null ? null : apply(p, words));
	}

	/** The proposal reworded and saved, or null when it is gone or could not be saved. */
	private static @Nullable Proposal apply(Pending p, ProposalWordsJob.Words w) {
		var s = Settlements.store().get(p.settlementId());
		if (s.isEmpty()) return null;
		var ps = s.get().proposals();
		List<Proposal> open = new ArrayList<>(ps.open());
		for (int i = 0; i < open.size(); i++) {
			Proposal x = open.get(i);
			if (!x.key().equals(p.key()) || x.building() == null) continue;
			var b = x.building();
			// the rules decided the building: its type, size and place stay; only its name and description are the settlement's own
			var nb = new ConceptCard.Building(w.role(), b.type(), b.count(), b.footprint(), b.landmark(), w.notes() != null ? w.notes() : b.notes(), b.placement());
			Proposal n = new Proposal(x.key(), w.title(), w.why(), nb, x.at(), x.trigger(), x.district(), x.found());
			open.set(i, n);
			return Settlements.proposals(p.settlementId(), new Settlement.Proposals(open, ps.declined(), ps.accepted(), ps.lastAt())).ok() ? n : null;
		}
		return null;
	}

	/** The steward puts the idea to the player: in its words when they came, else in the rule's. */
	private static void say(MinecraftServer server, Pending p, @Nullable Proposal worded) {
		Proposal shown = worded;
		if (shown == null) {
			var s = Settlements.store().get(p.settlementId());
			shown = s.flatMap(x -> x.proposals().open().stream().filter(y -> y.key().equals(p.key())).findFirst()).orElse(null);
		}
		if (shown == null) return;
		StewardVoice.say(server, p.settlementId(), "I have an idea: " + shown.title() + ". " + shown.why() + " See the inbox (Y).");
		SettlementRunner.sendInbox(server, p.player());
	}

	private static List<String> buildings(MinecraftServer server, Settlement s) {
		List<String> out = new ArrayList<>();
		for (SiteView v : ArchitectApi.get().sites(server).list(s.owner())) if ("building".equals(v.kind())) out.add(Updates.lotOf(v));
		return out;
	}
}
