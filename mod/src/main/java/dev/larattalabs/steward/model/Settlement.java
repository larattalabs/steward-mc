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
	Autonomy autonomy,
	Lands lands
) {
	public enum Kind {
		FOUNDED, CARD_EDITED, RESKIN, RELAYOUT, PROJECT_PROPOSED, PROJECT_APPROVED, PROJECT_PLACED, PROJECT_REMOVED, PERMISSION_CHANGED, NOTE,
		/** A placed building moved to another version of its design (an update; later a change request). */
		UPDATED,
		/** A building moved back to an earlier version. */
		REVERTED,
		/** The claim's size changed. */
		CLAIM_CHANGED,
		/** A district was founded: its road is the operation's site (undone like a project; the district goes with it once nothing else stands there). */
		DISTRICT_FOUNDED,
		/** A district was given up (its road undone and nothing standing in it). */
		DISTRICT_REMOVED,
		/** A road the steward laid (a district's street linked to its edge). */
		ROAD_PLACED,
		/** A protected area was marked or cleared. */
		PROTECTED_CHANGED,
		/**
		 * A player's own build taken on as a building of the settlement (Architect PT2): its site wrote no block, and undoing it only lets the build go
		 * (Architect un-adopts; the house stays).
		 */
		BUILD_ADOPTED;

		/** Whether an operation of this kind put sites in the world that an undo removes (an adopted build's site: removed, the build stays). */
		public boolean placesSites() {
			return this == PROJECT_PLACED || this == DISTRICT_FOUNDED || this == ROAD_PLACED || this == BUILD_ADOPTED;
		}
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
	 * changes or outcome: they read as done with no recorded versions. {@code district} (format 4): the district an operation founded or gave up without
	 * a site of its own (a reservation, docs/DISTRICTS.md section 4), so its undo knows what to give up.
	 */
	public record LogEntry(long at, Kind kind, String text, List<String> siteIds, @Nullable String siteGroup, @Nullable String op, List<SiteChange> changes,
		@Nullable Outcome outcome, @Nullable String district) {
		public LogEntry {
			kind = kind == null ? Kind.NOTE : kind;
			siteIds = siteIds == null ? List.of() : List.copyOf(siteIds);
			changes = changes == null ? List.of() : List.copyOf(changes);
			outcome = outcome == null ? Outcome.DONE : outcome;
			text = text == null ? "" : text;
		}

		public LogEntry(long at, Kind kind, String text, List<String> siteIds, @Nullable String siteGroup, @Nullable String op, List<SiteChange> changes,
			@Nullable Outcome outcome) {
			this(at, kind, text, siteIds, siteGroup, op, changes, outcome, null);
		}

		/** An operation on a district that has no site of its own (founding or giving up a reservation). */
		public static LogEntry ofDistrict(long at, Kind kind, String text, String district) {
			return new LogEntry(at, kind, text, List.of(), null, null, List.of(), Outcome.DONE, district);
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
				case PROJECT_PLACED, DISTRICT_FOUNDED, ROAD_PLACED -> Recovery.UNDO_PROJECT;
				case UPDATED, REVERTED -> changes.stream().anyMatch(c -> c.from() > 0) ? Recovery.REVERT_VERSION : Recovery.NONE;
				default -> Recovery.NONE;
			};
		}

		LogEntry withOp(String id) {
			return new LogEntry(at, kind, text, siteIds, siteGroup, id, changes, outcome, district);
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
		lands = lands == null ? Lands.NONE : lands;
	}

	/**
	 * Land beyond the settlement's claim and land it must leave alone (docs/PLAN.md phase 3f): its districts, the areas the player protected, and the
	 * serial their ids come from ({@code dst_<n>}, {@code pa_<n>}; it only grows, so an id is never reused). Files from before districts read with none.
	 */
	public record Lands(List<District> districts, List<Area> protect, int serial) {
		public static final Lands NONE = new Lands(List.of(), List.of(), 0);

		public Lands {
			districts = districts == null ? List.of() : List.copyOf(districts);
			protect = protect == null ? List.of() : List.copyOf(protect);
			serial = Math.max(0, serial);
		}

		public java.util.Optional<District> district(String id) {
			return districts.stream().filter(d -> d.id().equals(id)).findFirst();
		}

		public Lands withDistrict(District d) {
			List<District> l = new ArrayList<>(districts.stream().filter(x -> !x.id().equals(d.id())).toList());
			l.add(d);
			return new Lands(l, protect, serial);
		}

		public Lands withoutDistrict(String id) {
			return new Lands(districts.stream().filter(x -> !x.id().equals(id)).toList(), protect, serial);
		}

		public Lands withArea(Area a) {
			List<Area> l = new ArrayList<>(protect);
			l.add(a);
			return new Lands(districts, l, serial);
		}

		public Lands withoutArea(String id) {
			return new Lands(districts, protect.stream().filter(x -> !x.id().equals(id)).toList(), serial);
		}

		/** With one more id used ({@code serial + 1} is the id just taken). */
		public Lands next() {
			return new Lands(districts, protect, serial + 1);
		}
	}

	/** A point on the ground (x, z). */
	public record Pt(int x, int z) {}

	/**
	 * A road the steward laid: its Architect site, its centre line's corners and its width (layouts keep off it); {@code link}: the road from a district's
	 * edge to its own street (else the founding road from the town).
	 */
	public record Road(String siteId, List<Pt> points, int width, boolean link) {
		public Road {
			points = points == null ? List.of() : List.copyOf(points);
		}

		public Road(String siteId, List<Pt> points, int width) {
			this(siteId, points, width, false);
		}
	}

	/**
	 * A district (phase 3f; docs/DISTRICTS.md): land of the settlement's beyond the town, with a purpose, built like the town and joined to it.
	 * <ul>
	 * <li>{@code direction}: where it lies from the stone (N, E, S, W); {@code claim}: its square, or for land that isn't a square its bounding square
	 * (never a licence to build: {@link #land()} is);</li>
	 * <li>{@code roads}: the roads Steward laid for a square district, the founding road first; {@code linkUndone}: the player undid its link road, so it
	 * is not laid again by itself (a later build in the district lays it);</li>
	 * <li>since format 4: {@code kind} ({@link #SQUARE}, {@link #BESIDE}, {@link #ALONG}, {@link #OUTPOST}), {@code state} ({@link #RESERVED},
	 * {@link #BUILDING}, {@link #STANDING}), {@code parent} (the town region it was founded beside), {@code region} (its own region, once planned),
	 * {@code savedLand} (saved as {@code land}; null for a square, whose land is its claim), {@code follow} (the boundary an along district follows) and {@code opKey} (the stem
	 * of its plan and realise keys). A format-3 district reads as a standing square.</li>
	 * </ul>
	 */
	public record District(String id, String name, String direction, Claim claim, long at, List<Road> roads, boolean linkUndone, String kind, String state,
		@org.jspecify.annotations.Nullable String parent, @org.jspecify.annotations.Nullable String region, @com.google.gson.annotations.SerializedName("land") @org.jspecify.annotations.Nullable Land savedLand,
		@org.jspecify.annotations.Nullable Follow follow, @org.jspecify.annotations.Nullable String opKey) {
		public static final String SQUARE = "square", BESIDE = "beside", ALONG = "along", OUTPOST = "outpost";
		public static final String RESERVED = "reserved", BUILDING = "building", STANDING = "standing";
		private static final java.util.Set<String> KINDS = java.util.Set.of(SQUARE, BESIDE, ALONG, OUTPOST);
		private static final java.util.Set<String> STATES = java.util.Set.of(RESERVED, BUILDING, STANDING);

		public District {
			if (id == null || claim == null) throw new com.google.gson.JsonParseException("a district needs an id and a claim");
			name = name == null || name.isBlank() ? id : name;
			direction = direction == null ? "E" : direction;
			roads = roads == null ? List.of() : List.copyOf(roads);
			kind = kind == null ? SQUARE : kind;
			state = state == null ? STANDING : state;
			// an unknown word is a newer Steward's: refused (a newer format is refused before this; this guards a hand-edited file)
			if (!KINDS.contains(kind)) throw new com.google.gson.JsonParseException("district " + id + " has an unknown kind " + kind);
			if (!STATES.contains(state)) throw new com.google.gson.JsonParseException("district " + id + " has an unknown state " + state);
			if (savedLand != null && !savedLand.dimension().equals(claim.dimension())) throw new com.google.gson.JsonParseException("district " + id + "'s land is in another dimension");
			if (!SQUARE.equals(kind) && savedLand == null) throw new com.google.gson.JsonParseException("district " + id + " (" + kind + ") has no land");
		}

		public District(String id, String name, String direction, Claim claim, long at, List<Road> roads, boolean linkUndone) {
			this(id, name, direction, claim, at, roads, linkUndone, SQUARE, STANDING, null, null, null, null, null);
		}

		public District(String id, String name, String direction, Claim claim, long at, List<Road> roads) {
			this(id, name, direction, claim, at, roads, false);
		}

		/** The boundary an along district follows: {@code shore}, {@code ridge} or {@code biome_edge}, the two block families or biomes between, its side. */
		public record Follow(String boundary, List<String> between, @org.jspecify.annotations.Nullable String side) {
			public Follow {
				if (boundary == null || boundary.isBlank()) throw new com.google.gson.JsonParseException("a followed boundary needs its kind");
				between = between == null ? List.of() : List.copyOf(between);
			}
		}

		/** The columns it owns: its land, or for a square its claim. Every "is this the district's" test reads this, never {@link #claim()}. */
		public Land land() {
			return savedLand != null ? savedLand : Land.of(claim);
		}

		public boolean square() {
			return SQUARE.equals(kind);
		}

		/** The road it was founded with, while it stands in the record. */
		public java.util.Optional<Road> founding() {
			return roads.stream().filter(r -> !r.link()).findFirst();
		}

		public boolean linked() {
			return roads.stream().anyMatch(Road::link);
		}

		public District withRoads(List<Road> l, boolean linkUndone) {
			return new District(id, name, direction, claim, at, l, linkUndone, kind, state, parent, region, savedLand, follow, opKey);
		}

		/** With a road added (a link clears {@code linkUndone}). */
		public District withRoad(Road r) {
			List<Road> l = new ArrayList<>(roads);
			l.add(r);
			return withRoads(l, linkUndone && !r.link());
		}
	}

	/** An area the player protected: the steward never builds on it (columns x0..x1, z0..z1, every height). */
	public record Area(String id, String label, String dimension, int x0, int z0, int x1, int z1, long at) {
		public Area {
			if (id == null || dimension == null) throw new com.google.gson.JsonParseException("a protected area needs an id and a dimension");
			label = label == null || label.isBlank() ? id : label;
			int ax = Math.min(x0, x1), bx = Math.max(x0, x1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
			x0 = ax;
			x1 = bx;
			z0 = az;
			z1 = bz;
		}

		public boolean contains(String dim, int x, int z) {
			return dimension.equals(dim) && x >= x0 && x <= x1 && z >= z0 && z <= z1;
		}

		/** Whether it shares a column with the box x0..x1, z0..z1. */
		public boolean overlaps(String dim, int ax, int az, int bx, int bz) {
			return dimension.equals(dim) && Math.min(ax, bx) <= x1 && Math.max(ax, bx) >= x0 && Math.min(az, bz) <= z1 && Math.max(az, bz) >= z0;
		}
	}

	/** The claim and every district's claim (a district's bounding square when its land isn't one): for bounds and a conservative test; land tests read {@link #ground()}. */
	public List<Claim> claims() {
		List<Claim> l = new ArrayList<>();
		l.add(claim);
		for (District d : lands.districts()) l.add(d.claim());
		return l;
	}

	/** Whether a point lies in the claim or one of its districts' land (at the claim's heights). */
	public boolean contains(String dim, int x, int y, int z) {
		if (claim.contains(dim, x, y, z)) return true;
		if (y < claim.minY() || y > claim.maxY()) return false;
		return lands.districts().stream().anyMatch(d -> d.land().contains(dim, x, z));
	}

	/** The town's claim and every district's land: what another settlement must not overlap. */
	public List<Land> ground() {
		List<Land> l = new ArrayList<>();
		l.add(Land.of(claim));
		for (District d : lands.districts()) l.add(d.land());
		return l;
	}

	/** The district a point lies in, if any (the town's own square comes first: a district inside a big claim still counts as the district). */
	public java.util.Optional<District> districtAt(String dim, int x, int z) {
		return lands.districts().stream().filter(d -> d.land().contains(dim, x, z)).findFirst();
	}

	/** The protected area a point lies in, if any. */
	public java.util.Optional<Area> protectedAt(String dim, int x, int z) {
		return lands.protect().stream().filter(a -> a.contains(dim, x, z)).findFirst();
	}

	/**
	 * The claim (the town's, or a district's whose land holds the box's every column) a box lies wholly in, else the town's claim (a box that straddles them
	 * is checked against it).
	 */
	public Claim claimFor(int x0, int z0, int x1, int z1) {
		if (claim.contains(claim.dimension(), Math.min(x0, x1), claim.minY(), Math.min(z0, z1)) && claim.contains(claim.dimension(), Math.max(x0, x1), claim.minY(), Math.max(z0, z1))) return claim;
		for (District d : lands.districts()) {
			if (d.land().containsBox(claim.dimension(), x0, z0, x1, z1)) return d.claim();
		}
		return claim;
	}

	public Settlement withLands(Lands l) {
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, log, proposals, bible, autonomy, l);
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
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, log, proposals, bible, a, lands);
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
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, log, p, bible, autonomy, lands);
	}

	/**
	 * The settlement's style bible (Architect's id and version), kept from its first build: later builds (a proposal, an addition) reuse it, so they match
	 * the settlement and do not pay for a new one. Null until a build made one.
	 */
	public record BibleRef(String id, int version, int styleVersion) {}

	public Settlement withBible(BibleRef b) {
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, log, proposals, b, autonomy, lands);
	}

	/** A claim the player has marked with the Founding Stone but not yet described: no concept card until they do. */
	public static Settlement founded(String id, String name, Claim claim, Permission permission, Difficulty difficulty, long now) {
		return new Settlement(id, name, null, claim, 1, 1, 1, permission, difficulty, List.of(new LogEntry(now, Kind.FOUNDED, "Claimed " + name, List.of())), Proposals.NONE, null, Autonomy.DEFAULT, Lands.NONE);
	}

	public boolean described() {
		return card != null;
	}

	/** The player's description became a card (and, if the card names the settlement, its name). */
	public Settlement withCard(ConceptCard c, long now) {
		String n = c.name() == null || c.name().isBlank() ? name : c.name();
		// a new description is a new style: its bible is made afresh
		return new Settlement(id, n, c, claim, siteVersion, styleVersion + 1, purposeVersion, permission, difficulty, log, proposals, null, autonomy, lands)
			.withLog(new LogEntry(now, Kind.CARD_EDITED, "Described as: " + c.site().text() + ", " + c.style().text(), List.of()));
	}

	public static Settlement found(String id, ConceptCard card, Claim claim, Permission permission, Difficulty difficulty, long now) {
		String name = card.name() == null || card.name().isBlank() ? id : card.name();
		return new Settlement(id, name, card, claim, 1, 1, 1, permission, difficulty,
			List.of(new LogEntry(now, Kind.FOUNDED, "Founded " + name, List.of())), Proposals.NONE, null, Autonomy.DEFAULT, Lands.NONE);
	}

	/** The Architect owner string for this settlement's sites: {@code steward_mc:settlement/<id>}. */
	public String owner() {
		return "steward_mc:settlement/" + id;
	}

	/** Appends an operation, giving it the next {@code op_<n>} id when it has none. */
	public Settlement withLog(LogEntry e) {
		List<LogEntry> l = new ArrayList<>(log);
		l.add(e.op() == null ? e.withOp("op_" + (l.size() + 1)) : e);
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, permission, difficulty, l, proposals, bible, autonomy, lands);
	}

	/** A re-skin changes only the style (and the card's style field); layout and purpose versions stay. */
	public Settlement reskin(ConceptCard.Field style, long now) {
		if (card == null) throw new IllegalStateException("describe the settlement first");
		ConceptCard c = new ConceptCard(card.name(), card.site(), style, card.purpose(), card.story(), card.constraints(), card.avoid(),
			card.interpretation(), card.contradictions(), card.assumptions(), card.program());
		return new Settlement(id, name, c, claim, siteVersion, styleVersion + 1, purposeVersion, permission, difficulty, log, proposals, null, autonomy, lands)
			.withLog(new LogEntry(now, Kind.RESKIN, "Style is now: " + style.text(), List.of()));
	}

	/** The claim grown (or set by the card's size); the change log says to what. */
	public Settlement withClaim(Claim c, long now) {
		return new Settlement(id, name, card, c, siteVersion, styleVersion, purposeVersion, permission, difficulty, log, proposals, bible, autonomy, lands)
			.withLog(new LogEntry(now, Kind.CLAIM_CHANGED, "Claim is now " + ClaimRules.side(c.radius()) + " x " + ClaimRules.side(c.radius()), List.of()));
	}

	/** The settlement's difficulty (from its card, by the world's rule): noted in the history when it changes. */
	public Settlement withDifficulty(Difficulty d, long now) {
		if (d == difficulty) return this;
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, permission, d, log, proposals, bible, autonomy, lands)
			.withLog(new LogEntry(now, Kind.NOTE, "Difficulty: " + d.name().charAt(0) + d.name().substring(1).toLowerCase(java.util.Locale.ROOT), List.of()));
	}

	public Settlement withPermission(Permission p, long now) {
		return new Settlement(id, name, card, claim, siteVersion, styleVersion, purposeVersion, p, difficulty, log, proposals, bible, autonomy, lands)
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
			if (e.kind().placesSites()) {
				List<String> left = e.siteIds().stream().filter(id -> !removed.contains(id)).toList();
				// the group only while the project is whole: after a partial undo the rest goes site by site
				if (!left.isEmpty()) return java.util.Optional.of(new LogEntry(e.at(), e.kind(), e.text(), left, left.size() == e.siteIds().size() ? e.siteGroup() : null, e.op(),
					e.changes().stream().filter(c -> left.contains(c.siteId())).toList(), e.outcome(), e.district()));
			}
			// a reservation (a district founded with no site): undoable while it is still only reserved; once built, its build's entry is the newer one
			if (e.kind() == Kind.DISTRICT_FOUNDED && e.siteIds().isEmpty() && e.district() != null
				&& lands.district(e.district()).filter(d -> District.RESERVED.equals(d.state())).isPresent()) return java.util.Optional.of(e);
		}
		return java.util.Optional.empty();
	}

	/**
	 * The version a site was last reverted away from, while that revert is its newest version change (an update after it clears it); 0 when none. Updates up
	 * to that version are not offered again: the player went back from it.
	 */
	public int revertedFrom(String siteId) {
		// the highest version reverted away from since the site's last update (3 -> 2 -> 1 holds 3, not 2)
		int held = 0;
		for (int i = log.size() - 1; i >= 0; i--) {
			LogEntry e = log.get(i);
			if (e.outcome() == Outcome.FAILED || (e.kind() != Kind.REVERTED && e.kind() != Kind.UPDATED)) continue;
			for (SiteChange c : e.changes()) {
				if (!c.siteId().equals(siteId)) continue;
				if (e.kind() == Kind.UPDATED) return held;
				held = Math.max(held, c.from());
			}
		}
		return held;
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
