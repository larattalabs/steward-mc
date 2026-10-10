package dev.larattalabs.steward.model;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One settlement in a world: its concept card, claim, versions, settings and change log. Immutable; every change returns a new
 * value (the store swaps it in). Site, style and purpose are versioned separately so a re-skin never redoes the layout
 * (docs/PLAN.md "Concept card").
 */
public record Settlement(
	String id,
	String name,
	@Nullable ConceptCard card,
	Claim claim,
	int siteVersion,
	int styleVersion,
	int purposeVersion,
	Permission permission,
	Difficulty difficulty,
	List<LogEntry> log,
	Proposals proposals,
	@Nullable BibleRef bible,
	Autonomy autonomy
) {
	public enum Kind {
		FOUNDED, CARD_EDITED, RESKIN, RELAYOUT, PROJECT_PROPOSED, PROJECT_APPROVED, PROJECT_PLACED, PROJECT_REMOVED, PERMISSION_CHANGED, NOTE,
		/** A placed building moved to another version of its design (an update; later a change request). */
		UPDATED,
		/** A building moved back to an earlier version. */
		REVERTED,
		/** The claim's size changed. */
		CLAIM_CHANGED
	}

	/** How an operation ended. */
	public enum Outcome { DONE, PARTIAL, FAILED }

	/** How an operation is undone: a placed project is removed, an updated building goes back to its earlier version; the rest have nothing to undo. */
	public enum Recovery { UNDO_PROJECT, REVERT_VERSION, NONE }

	/**
	 * What one operation did to one site: from version {@code from} to {@code to}. 0 = the site did not stand (placed: from 0) or stands no more (removed: to
	 * 0); -1 = not recorded (entries migrated from before operations).
	 */
	public record SiteChange(String siteId, @Nullable String lot, int from, int to) {}

	/**
	 * One operation in the change log (docs/PLAN.md "Reviews (2026-10-09)": change history before autonomy). {@code siteIds} are the Architect sites it
	 * touched; {@code siteGroup} the site group a placed project's batch made (undo removes it whole); {@code op} its id in the settlement ({@code op_<n>},
	 * given when logged); {@code changes} the version change of each site; {@code outcome} how it ended. Entries written before operations have no op,
	 * changes or outcome: they read as done with no recorded versions.
	 */
	public record LogEntry(long at, Kind kind, String text, List<String> siteIds, @Nullable String siteGroup, @Nullable String op, List<SiteChange> changes,
		@Nullable Outcome outcome) {
		public LogEntry {
			siteIds = siteIds == null ? List.of() : List.copyOf(siteIds);
			changes = changes == null ? List.of() : List.copyOf(changes);
			outcome = outcome == null ? Outcome.DONE : outcome;
			text = text == null ? "" : text;
		}

		public LogEntry(long at, Kind kind, String text, List<String> siteIds, @Nullable String siteGroup) {
			this(at, kind, text, siteIds, siteGroup, null, List.of(), Outcome.DONE);
		}

		public LogEntry(long at, Kind kind, String text, List<String> siteIds) {
			this(at, kind, text, siteIds, null);
		}

		/** An operation with its version changes; its sites are the changes' sites. */
		public static LogEntry of(long at, Kind kind, String text, List<SiteChange> changes, @Nullable String siteGroup, Outcome outcome) {
			return new LogEntry(at, kind, text, changes.stream().map(SiteChange::siteId).toList(), siteGroup, null, changes, outcome);
		}

		public Recovery recovery() {
			if (outcome == Outcome.FAILED) return Recovery.NONE;
			return switch (kind) {
				case PROJECT_PLACED -> Recovery.UNDO_PROJECT;
				case UPDATED, REVERTED -> changes.stream().anyMatch(c -> c.from() > 0) ? Recovery.REVERT_VERSION : Recovery.NONE;
				default -> Recovery.NONE;
			};
		}

		LogEntry withOp(String id) {
			return new LogEntry(at, kind, text, siteIds, siteGroup, id, changes, outcome);
		}
	}

	/** Also what a saved settlement is read through (Gson calls it): a missing id or claim is refused, missing settings take their safe defaults. */
	public Settlement {
		if (id == null || claim == null) throw new com.google.gson.JsonParseException("a settlement needs an id and a claim");
		name = name == null || name.isBlank() ? id : name;
		permission = permission == null ? Permission.PROPOSALS : permission;
		difficulty = difficulty == null ? Difficulty.PATRON : difficulty;
		// every operation has an id: entries made without one (the founding entry) get theirs by position
		List<LogEntry> l = new ArrayList<>(log == null ? List.of() : log);
		for (int i = 0; i < l.size(); i++) if (l.get(i).op() == null) l.set(i, l.get(i).withOp("op_" + (i + 1)));
		log = List.copyOf(l);
		siteVersion = Math.max(1, siteVersion);
		styleVersion = Math.max(1, styleVersion);
		purposeVersion = Math.max(1, purposeVersion);
		proposals = proposals == null ? Proposals.NONE : proposals;
		autonomy = autonomy == null ? Autonomy.DEFAULT : autonomy;
	}

	/**
	 * What the steward may spend on its own (docs/PLAN.md "Reviews (2026-10-09)", Noah: $500 a week by default): at Autonomous and Full it builds its
	 * proposals itself while the past week's own spending and the next build's budget fit {@code weeklyUsd}. {@code spends}: what it started on its own, newest
	 * last (only the past week counts).
	 */
	public record Autonomy(double weeklyUsd, List<Spend> spends) {
		public static final double DEFAULT_WEEKLY = 500;
		public static final Autonomy DEFAULT = new Autonomy(DEFAULT_WEEKLY, List.of());
		public static final long WEEK_MS = 7L * 24 * 3600_000;

		public Autonomy {
			weeklyUsd = weeklyUsd <= 0 || Double.isNaN(weeklyUsd) ? DEFAULT_WEEKLY : weeklyUsd;
			spends = spends == null ? List.of() : List.copyOf(spends);
		}

		/** What the steward started on its own in the week before {@code now}. */
		public double spentInWeek(long now) {
			return spends.stream().filter(x -> now - x.at() < WEEK_MS).mapToDouble(Spend::usd).sum();
		}

		/** Whether a build of {@code usd} fits what is left of the week's allowance. */
		public boolean allows(double usd, long now) {
			return spentInWeek(now) + usd <= weeklyUsd + 1e-9;
		}

		/** With a spend added (spends older than a week are dropped). */
		public Autonomy with(Spend s) {
			List<Spend> l = new ArrayList<>(spends.stream().filter(x -> s.at() - x.at() < WEEK_MS).toList());
			l.add(s);
			return new Autonomy(weeklyUsd, l);
		}
	}

	/** One build the steward started on its own: when, its budget, what. */
	public record Spend(long at, double usd, String what) {}

	public Settlement withAutonomy(Autonomy a) {
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, log, proposals, bible, a);
	}

	/**
	 * The steward's proposals for this settlement (phase 3e): those waiting for the player, the keys the player declined (never offered again) or accepted,
	 * and when the last was made (the cooldown). Saved with the settlement; files from before proposals read with none.
	 */
	public record Proposals(List<Proposal> open, List<String> declined, List<String> accepted, long lastAt) {
		public static final Proposals NONE = new Proposals(List.of(), List.of(), List.of(), 0);

		public Proposals {
			open = open == null ? List.of() : List.copyOf(open);
			declined = declined == null ? List.of() : List.copyOf(declined);
			accepted = accepted == null ? List.of() : List.copyOf(accepted);
		}
	}

	/** The same settlement with its proposals replaced. */
	public Settlement withProposals(Proposals p) {
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, log, p, bible, autonomy);
	}

	/**
	 * The settlement's style bible (Architect's id and version), kept from its first build: later builds (a proposal, an addition) reuse it, so they match
	 * the settlement and do not pay for a new one. Null until a build made one.
	 */
	public record BibleRef(String id, int version) {}

	public Settlement withBible(BibleRef b) {
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, log, proposals, b, autonomy);
	}

	/** A claim the player has marked with the Founding Stone but not yet described: no concept card until they do. */
	public static Settlement founded(String id, String name, Claim claim, Permission permission, Difficulty difficulty, long now) {
		return new Settlement(id, name, null, claim, 1, 1, 1, permission, difficulty, List.of(new LogEntry(now, Kind.FOUNDED, "Claimed " + name, List.of())), Proposals.NONE, null, Autonomy.DEFAULT);
	}

	public boolean described() {
		return card != null;
	}

	/** The player's description became a card (and, if the card names the settlement, its name). */
	public Settlement withCard(ConceptCard c, long now) {
		String n = c.name() == null || c.name().isBlank() ? name : c.name();
		return new Settlement(id, n, c, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, log, proposals, bible, autonomy)
			.withLog(new LogEntry(now, Kind.CARD_EDITED, "Described as: " + c.site().text() + ", " + c.style().text(), List.of()));
	}

	public static Settlement found(String id, ConceptCard card, Claim claim, Permission permission, Difficulty difficulty, long now) {
		String name = card.name() == null || card.name().isBlank() ? id : card.name();
		return new Settlement(id, name, card, claim, 1, 1, 1, permission, difficulty,
			List.of(new LogEntry(now, Kind.FOUNDED, "Founded " + name, List.of())), Proposals.NONE, null, Autonomy.DEFAULT);
	}

	/** The Architect owner string for this settlement's sites: {@code steward_mc:settlement/<id>}. */
	public String owner() {
		return "steward_mc:settlement/" + id;
	}

	/** Appends an operation, giving it the next {@code op_<n>} id when it has none. */
	public Settlement withLog(LogEntry e) {
		List<LogEntry> l = new ArrayList<>(log);
		l.add(e.op() == null ? e.withOp("op_" + (l.size() + 1)) : e);
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, l, proposals, bible, autonomy);
	}

	/** A re-skin changes only the style (and the card's style field); layout and purpose versions stay. */
	public Settlement reskin(ConceptCard.Field style, long now) {
		if (card == null) throw new IllegalStateException("describe the settlement first");
		ConceptCard c = new ConceptCard(card.name(), card.site(), style, card.purpose(), card.story(), card.constraints(), card.avoid(),
			card.interpretation(), card.contradictions(), card.assumptions(), card.program());
		return new Settlement(id, name, c, claim, siteVersion, styleVersion + 1, purposeVersion, permission, difficulty, log, proposals, bible, autonomy)
			.withLog(new LogEntry(now, Kind.RESKIN, "Style is now: " + style.text(), List.of()));
	}

	/** The claim grown (or set by the card's size); the change log says to what. */
	public Settlement withClaim(Claim c, long now) {
		return new Settlement(id, name, card, c, siteVersion, styleVersion, purposeVersion, permission, difficulty, log, proposals, bible, autonomy)
			.withLog(new LogEntry(now, Kind.CLAIM_CHANGED, "Claim is now " + ClaimRules.side(c.radius()) + " x " + ClaimRules.side(c.radius()), List.of()));
	}

	public Settlement withPermission(Permission p, long now) {
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, p, difficulty, log, proposals, bible, autonomy)
			.withLog(new LogEntry(now, Kind.PERMISSION_CHANGED, "Permission: " + p, List.of()));
	}

	/**
	 * The newest placed project that is not undone yet: a PROJECT_PLACED entry with a site a later PROJECT_REMOVED did not remove (an undo stopped by the
	 * player's things in a box leaves the rest of its project undoable). Empty when there is nothing to undo.
	 */
	public java.util.Optional<LogEntry> lastUndoable() {
		java.util.Set<String> removed = new java.util.HashSet<>();
		for (int i = log.size() - 1; i >= 0; i--) {
			LogEntry e = log.get(i);
			if (e.kind() == Kind.PROJECT_REMOVED) removed.addAll(e.siteIds());
			if (e.kind() == Kind.PROJECT_PLACED) {
				List<String> left = e.siteIds().stream().filter(id -> !removed.contains(id)).toList();
				// the group only while the project is whole: after a partial undo the rest goes site by site
				if (!left.isEmpty()) return java.util.Optional.of(new LogEntry(e.at(), e.kind(), e.text(), left, left.size() == e.siteIds().size() ? e.siteGroup() : null, e.op(),
					e.changes().stream().filter(c -> left.contains(c.siteId())).toList(), e.outcome()));
			}
		}
		return java.util.Optional.empty();
	}

	/**
	 * The version a site was last reverted away from, while that revert is its newest version change (an update after it clears it); 0 when none. Updates up
	 * to that version are not offered again: the player went back from it.
	 */
	public int revertedFrom(String siteId) {
		for (int i = log.size() - 1; i >= 0; i--) {
			LogEntry e = log.get(i);
			if (e.outcome() == Outcome.FAILED || (e.kind() != Kind.REVERTED && e.kind() != Kind.UPDATED)) continue;
			for (SiteChange c : e.changes()) {
				if (c.siteId().equals(siteId)) return e.kind() == Kind.REVERTED ? Math.max(0, c.from()) : 0;
			}
		}
		return 0;
	}

	/** Site ids recorded in the log for a kind, newest first (what an "undo last project" would remove). */
	public List<String> siteIdsOf(Kind kind) {
		List<String> out = new ArrayList<>();
		for (int i = log.size() - 1; i >= 0; i--) {
			if (log.get(i).kind() == kind) out.addAll(log.get(i).siteIds());
		}
		return out;
	}
}
