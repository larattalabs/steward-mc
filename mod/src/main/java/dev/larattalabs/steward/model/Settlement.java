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
	List<LogEntry> log
) {
	public enum Kind { FOUNDED, CARD_EDITED, RESKIN, RELAYOUT, PROJECT_PROPOSED, PROJECT_APPROVED, PROJECT_PLACED, PROJECT_REMOVED, PERMISSION_CHANGED, NOTE }

	/**
	 * One line of the change log. {@code siteIds} are Architect site ids a later undo can remove; {@code siteGroup} is the Architect site group a placed
	 * project's batch made (undo removes it whole with {@code Sites.removeGroup}), null for older entries and other kinds.
	 */
	public record LogEntry(long at, Kind kind, String text, List<String> siteIds, @Nullable String siteGroup) {
		public LogEntry(long at, Kind kind, String text, List<String> siteIds) {
			this(at, kind, text, siteIds, null);
		}
	}

	public Settlement {
		log = List.copyOf(log);
	}

	/** A claim the player has marked with the Founding Stone but not yet described: no concept card until they do. */
	public static Settlement founded(String id, String name, Claim claim, Permission permission, Difficulty difficulty, long now) {
		return new Settlement(id, name, null, claim, 1, 1, 1, permission, difficulty, List.of(new LogEntry(now, Kind.FOUNDED, "Claimed " + name, List.of())));
	}

	public boolean described() {
		return card != null;
	}

	/** The player's description became a card (and, if the card names the settlement, its name). */
	public Settlement withCard(ConceptCard c, long now) {
		String n = c.name() == null || c.name().isBlank() ? name : c.name();
		return new Settlement(id, n, c, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, log)
			.withLog(new LogEntry(now, Kind.CARD_EDITED, "Described as: " + c.site().text() + ", " + c.style().text(), List.of()));
	}

	public static Settlement found(String id, ConceptCard card, Claim claim, Permission permission, Difficulty difficulty, long now) {
		String name = card.name() == null || card.name().isBlank() ? id : card.name();
		return new Settlement(id, name, card, claim, 1, 1, 1, permission, difficulty,
			List.of(new LogEntry(now, Kind.FOUNDED, "Founded " + name, List.of())));
	}

	/** The Architect owner string for this settlement's sites: {@code steward_mc:settlement/<id>}. */
	public String owner() {
		return "steward_mc:settlement/" + id;
	}

	public Settlement withLog(LogEntry e) {
		List<LogEntry> l = new ArrayList<>(log);
		l.add(e);
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, l);
	}

	/** A re-skin changes only the style (and the card's style field); layout and purpose versions stay. */
	public Settlement reskin(ConceptCard.Field style, long now) {
		if (card == null) throw new IllegalStateException("describe the settlement first");
		ConceptCard c = new ConceptCard(card.name(), card.site(), style, card.purpose(), card.story(), card.constraints(), card.avoid(),
			card.interpretation(), card.contradictions(), card.assumptions(), card.program());
		return new Settlement(id, name, c, claim, siteVersion, styleVersion + 1, purposeVersion, permission, difficulty, log)
			.withLog(new LogEntry(now, Kind.RESKIN, "Style is now: " + style.text(), List.of()));
	}

	/** The claim grown (or set by the card's size); the change log says to what. */
	public Settlement withClaim(Claim c, long now) {
		return new Settlement(id, name, card, c, siteVersion, styleVersion, purposeVersion, permission, difficulty, log)
			.withLog(new LogEntry(now, Kind.NOTE, "Claim is now " + ClaimRules.side(c.radius()) + " x " + ClaimRules.side(c.radius()), List.of()));
	}

	public Settlement withPermission(Permission p, long now) {
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, p, difficulty, log)
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
				if (!left.isEmpty()) return java.util.Optional.of(new LogEntry(e.at(), e.kind(), e.text(), left, left.size() == e.siteIds().size() ? e.siteGroup() : null));
			}
		}
		return java.util.Optional.empty();
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
