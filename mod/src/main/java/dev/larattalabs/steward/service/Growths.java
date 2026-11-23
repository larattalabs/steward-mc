package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.RegionView;
import dev.larattalabs.architect.api.RevisionView;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.layout.VillageLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * A region town's growth through Architect's revisions (docs/GROWTH.md, slice A): an addition to a standing region town is planned as a revision of it
 * ({@code Regions.planRevision}, the {@code revise} program's {@code grow}), realised into it, logged under {@code revision:<region>:<rid>} and taken up by
 * {@code Regions.undoRevision}, never by removing the region. A revision is known by its rid, never by its position: positions are reused after an undo,
 * and an undo to a position takes every revision above it.
 */
public final class Growths {
	private Growths() {
	}

	/** The history tag of a growth: {@code revision:<region>:<rid>}. */
	public static final String PREFIX = "revision:";
	/** Architect's feature for a revision's {@code grow} (6d GR). */
	public static final String FEATURE = "growDistrict";
	/** The program a growth is planned with (6d RV's {@code revise}, its {@code spec.grow}). */
	public static final String PROGRAM = "revise";

	public record Ref(String region, int rid) {
		public String tag() {
			return PREFIX + region + ":" + rid;
		}
	}

	public static String tag(String region, int rid) {
		return new Ref(region, rid).tag();
	}

	/** The growth a history tag names, or empty (not a growth's tag, or malformed). */
	public static Optional<Ref> parse(@Nullable String siteGroup) {
		if (siteGroup == null || !siteGroup.startsWith(PREFIX)) return Optional.empty();
		String rest = siteGroup.substring(PREFIX.length());
		int at = rest.lastIndexOf(':');
		if (at <= 0 || at == rest.length() - 1) return Optional.empty();
		try {
			int rid = Integer.parseInt(rest.substring(at + 1));
			return rid > 0 ? Optional.of(new Ref(rest.substring(0, at), rid)) : Optional.empty();
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	public static boolean isGrowth(@Nullable String siteGroup) {
		return parse(siteGroup).isPresent();
	}

	/**
	 * A growth's lots renamed so their ids are unique in the region (Architect refuses an id the region has; {@code ProgramPlanner} restarts {@code house_1}
	 * for every build): {@code g<build>_<n>}, the build id's letters and digits, lowercase, the whole at most 48 and matching {@code [a-z][a-z0-9_]{0,47}}.
	 * Pure; the same build gives the same ids.
	 */
	public static List<VillageLayout.LotSpec> rename(List<VillageLayout.LotSpec> specs, String buildId) {
		String b = buildId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		String suffixMax = "_" + specs.size();
		int room = 48 - 1 - suffixMax.length();
		if (b.length() > room) b = b.substring(b.length() - room);
		List<VillageLayout.LotSpec> out = new ArrayList<>(specs.size());
		for (int i = 0; i < specs.size(); i++) {
			var s = specs.get(i);
			out.add(new VillageLayout.LotSpec("g" + b + "_" + (i + 1), s.type(), s.sizeX(), s.sizeZ(), s.role(), s.notes(), s.landmark(), s.placement()));
		}
		return List.copyOf(out);
	}

	/** What taking a growth up does now. */
	public enum Step {
		/** Nothing to take: the revision is undone already, or the region is gone. */
		GONE,
		/** A newer revision stands above it (not taken: the undo would take that one too). */
		NEWER,
		/** The region is busy with something else, or the revision is still being placed: asked again later. */
		WAIT,
		/** {@code undoRevision(region, toPosition)}: the revision is the head, or its own take-up was cut short and resumes. */
		UNDO,
		/** The region has no revision with that rid (Architect lost it, or the tag is wrong): nothing is asked. */
		UNKNOWN,
		/**
		 * (slice A2) Kept for its words only: since Architect's RU (v0.66.0) {@link #decide} never answers it, as {@code undoRevision} cancels a survival
		 * revision's unbuilt targets. A {@code survival_building} refusal (a stray answer) is still said in A2's words ({@link #buildingRefusal}).
		 */
		BUILDING
	}

	public record Decision(Step step, int toPosition, String why) {}

	/**
	 * Pure. {@code regionGone}: no region, or one removed or being removed; {@code busy}: the region's busy note; {@code head}: its position;
	 * {@code revState}/{@code revPosition}: the revision with the rid ({@code revState} null when there is none).
	 */
	public static Decision decide(boolean regionGone, @Nullable String busy, int head, @Nullable String revState, int revPosition) {
		return decide(regionGone, false, busy, head, revState, revPosition);
	}

	/** {@code removing}: the town is being removed (it may stop and stand again): later, never gone. */
	public static Decision decide(boolean regionGone, boolean removing, @Nullable String busy, int head, @Nullable String revState, int revPosition) {
		return decide(regionGone, removing, busy, head, revState, revPosition, false);
	}

	/**
	 * {@code writing} (slice A2L, Architect RU): the revision is the survival town's head and Architect is still writing it ({@link #writing}): its undo
	 * cancels its batch first, so it is taken up now, not waited for. A town from materials whose revision has ended with targets unbuilt is taken up
	 * too (RU removes them with their stages, each refunding what of it stands paid).
	 */
	public static Decision decide(boolean regionGone, boolean removing, @Nullable String busy, int head, @Nullable String revState, int revPosition,
		boolean writing) {
		if (regionGone) return new Decision(Step.GONE, 0, "the town is gone");
		if (removing) return new Decision(Step.WAIT, 0, "the town is being taken up");
		if (revState == null) return new Decision(Step.UNKNOWN, 0, "Architect has no such revision of the town");
		String st = revState.toUpperCase(Locale.ROOT);
		if (st.equals("UNDONE") || st.equals("STALE")) return new Decision(Step.GONE, 0, "it was taken up already");
		// its own take-up, cut short by a stop or a restart: the same call resumes it (Architect: a same-target call)
		if (st.equals("UNDOING")) return new Decision(Step.UNDO, revPosition - 1, "resuming its take-up");
		if (writing && head == revPosition) return new Decision(Step.UNDO, revPosition - 1, "cancelling what is still being placed from materials");
		if (busy != null && !busy.isBlank()) return new Decision(Step.WAIT, 0, "the town is busy (" + busy + ")");
		if (st.equals("PLACING") || st.equals("PLANNED") || st.equals("ACCEPTED")) return new Decision(Step.WAIT, 0, "it is still being placed");
		if (head != revPosition) return new Decision(Step.NEWER, 0, head > revPosition ? "a newer change to the town stands above it" : "the town's history moved below it");
		return new Decision(Step.UNDO, revPosition - 1, "");
	}

	public static Decision decide(Optional<RegionView> v, int rid) {
		return decide(v, rid, null);
	}

	/** {@code sites} is no longer read (slice A2L: a revision still building is taken up); kept for its callers. */
	public static Decision decide(Optional<RegionView> v, int rid, dev.larattalabs.architect.api.@Nullable Sites sites) {
		boolean gone = v.isEmpty() || v.get().state() == dev.larattalabs.architect.api.RegionState.REMOVED;
		boolean removing = v.isPresent() && v.get().state() == dev.larattalabs.architect.api.RegionState.REMOVING;
		RevisionView r = v.flatMap(x -> revision(x, rid)).orElse(null);
		boolean writing = r != null && v.isPresent() && writing(fromMaterials(v.get()), v.get().busy(), v.get().state() == dev.larattalabs.architect.api.RegionState.PLACING,
			rid, v.get().position(), r.position());
		return decide(gone, removing, v.map(RegionView::busy).orElse(null), v.map(RegionView::position).orElse(0), r == null ? null : r.state(), r == null ? 0 : r.position(),
			writing);
	}

	/**
	 * (slice A2L) Whether Architect's undo would cancel this revision as still writing (its {@code Revisions.writingToCancel}): a town from materials,
	 * busy {@code revising <rid>} for this rid, the region PLACING, the revision its head. Pure.
	 */
	public static boolean writing(boolean fromMaterials, @Nullable String busy, boolean regionPlacing, int rid, int head, int revPosition) {
		if (!fromMaterials || !regionPlacing || busy == null || !busy.startsWith("revising ")) return false;
		try {
			return Integer.parseInt(busy.substring("revising ".length()).trim()) == rid && head == revPosition;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/** (slice A2L) The completion a growth's empty take-up logs (a NOTE with the growth's tag): {@link #completed} reads it. */
	public static final String NOT_PLACED = "The addition was not placed";

	/**
	 * (slice A2L) Whether the growth with this tag has its completion in the history already: a {@code PROJECT_REMOVED} carrying the tag, or the
	 * {@link #NOT_PLACED} note with it. Every path that ends a growth's take-up (an undo, a cancel, a late answer, the sweep's reconcile) checks it on a
	 * fresh read right before it appends, so a replay after a restart never logs twice. Older removals (no tag) are read by {@code Undo.retired}. Pure.
	 */
	public static boolean completed(dev.larattalabs.steward.model.Settlement s, String tag) {
		for (var e : s.log()) {
			if (!tag.equals(e.siteGroup())) continue;
			if (e.kind() == dev.larattalabs.steward.model.Settlement.Kind.PROJECT_REMOVED) return true;
			if (e.kind() == dev.larattalabs.steward.model.Settlement.Kind.NOTE && e.text().startsWith(NOT_PLACED)) return true;
		}
		return false;
	}

	/** (slice A2L) What a take-up refunded, said: Architect's refund (stockpile items, and any cargo found), or that nothing had been drawn. Pure. */
	/** (slice A2L, GPT diff review) Architect's answer is reported in a Supplied town even when nothing came back, and anywhere when something did. */
	public static boolean reportsRefund(dev.larattalabs.steward.model.Settlement s, dev.larattalabs.architect.api.@Nullable RemoveResult r) {
		return r != null && (s.difficulty() == dev.larattalabs.steward.model.Difficulty.SUPPLIED || !r.refund().isEmpty() || !OwedParts.owedTo(s.owner(), r).isEmpty());
	}

	/** (T4RD-S) A take-up's answer said: the refund, and what is owed back to the settlement apart ({@link OwedParts}: never the player's own blocks). */
	public static String refundLine(dev.larattalabs.steward.model.Settlement s, dev.larattalabs.architect.api.RemoveResult r) {
		return refundLine(r.refund(), OwedParts.owedTo(s.owner(), r));
	}

	public static String refundLine(java.util.Map<net.minecraft.world.item.Item, Integer> refund) {
		return refundLine(refund, java.util.Map.of());
	}

	/**
	 * (OVF-S3, T4S-PRE) As above, with what Architect owes ({@code RemoveResult.owed()}, resolved to items: T2b's owed entries, listed apart from the refund
	 * totals, never in them). Owed items mean something did come back, so "nothing came back" is never said then.
	 */
	public static String refundLine(java.util.Map<net.minecraft.world.item.Item, Integer> refund, java.util.Map<net.minecraft.world.item.Item, Integer> owed) {
		return refundLine(refund, owed, m -> Stockpiles.items(m.entrySet().stream().map(e -> java.util.Map.entry(e.getKey(), e.getValue())).toList(), 8));
	}

	/** {@link #refundLine(java.util.Map, java.util.Map)} over any item key, {@code items} saying a map ("12 oak planks, 3 glass"). Pure. */
	static <T> String refundLine(java.util.Map<T, Integer> refund, java.util.Map<T, Integer> owed, java.util.function.Function<java.util.Map<T, Integer>, String> items) {
		String owes = owed.isEmpty() ? "" : "Owed to you: " + items.apply(owed) + " (take them from the stockpile's Take, or /architect collect; they wait for you).";
		if (refund.isEmpty()) return owed.isEmpty() ? NOTHING_BACK : owes;
		return "Refunded: " + items.apply(refund) + "." + (owes.isEmpty() ? "" : " " + owes);
	}

	/**
	 * (OVF-S3) The completion note of a growth's empty take-up: {@code refunded} (its answer's refund not empty) and {@code owed} (its owed not empty) both
	 * mean something came back. Starts with {@link #NOT_PLACED} ({@link #completed} reads it). Pure.
	 */
	public static String notPlacedNote(String why, boolean refunded, boolean owed) {
		String back = refunded && owed ? "; what it drew was refunded, and some is owed to you" : refunded ? "; what it drew was refunded" : owed ? "; what it drew is owed to you" : "";
		return NOT_PLACED + " (" + why + back + ")";
	}

	/** What a take-up that drew nothing says. */
	static final String NOTHING_BACK = "Nothing had been drawn from the stockpile for it, so nothing came back.";

	/**
	 * (slice A2) How many of the revision's own sites are construction targets not built yet (BUILDING or PLACING), as Architect's undo preflight reads its
	 * suffix: never the town's other targets (an update of an older building makes the town's build unfinished again without touching the revision). A site
	 * Architect no longer has ({@code state} null) counts as built. Pure over {@code state}.
	 */
	public static int unbuilt(RevisionView r, java.util.function.Function<String, dev.larattalabs.architect.api.@Nullable State> state) {
		int n = 0;
		for (String id : r.sites()) {
			var st = state.apply(id);
			if (st != null && st != dev.larattalabs.architect.api.State.BUILT) n++;
		}
		return n;
	}

	/** (slice A2) Whether the revision's own construction targets are not all built (the town builds from materials). */
	public static boolean building(MinecraftServer server, Ref ref) {
		var v = ArchitectAccess.api().regions().get(ref.region());
		var r = v.flatMap(x -> revision(x, ref.rid()));
		if (r.isEmpty() || !fromMaterials(v.get())) return false;
		var sites = ArchitectAccess.api().sites(server);
		return unbuilt(r.get(), id -> sites.get(id).map(dev.larattalabs.architect.api.SiteView::state).orElse(null)) > 0;
	}

	/** Whether the town builds from materials (Architect RS): a region's mode is fixed when it is realised, and a revision is realised only in it. */
	public static boolean fromMaterials(RegionView v) {
		return v.build() != null;
	}

	/**
	 * (slice A2) Why a growth of the town may not be asked now, or null. Pure. Architect plans a revision in the world's mode and realises it only in the
	 * town's own (a mismatch is refused at the realise, after the designs are paid for), and a town built from materials grows only once all of it is built
	 * ({@code survival_unfinished}). {@code townFromMaterials}: the town's mode; {@code worldFromMaterials}: the world's now (the toggle on, the default game
	 * type not creative); {@code built}/{@code percent}: the town's build (from materials only).
	 */
	public static @Nullable String modeRefusal(String town, boolean townFromMaterials, boolean worldFromMaterials, boolean built, double percent) {
		if (townFromMaterials != worldFromMaterials) return town + " was built " + (townFromMaterials ? "from materials" : "at once") + " and this world builds "
			+ (worldFromMaterials ? "from materials" : "at once") + " now: Architect grows a town only the way it was built.";
		// (slice SST) never "100%" while it isn't built (run 2: a lot nearly done rounded up)
		if (townFromMaterials && !built) return String.format(Locale.ROOT, "%s is still being built from the stockpile (%d%%): it can grow once it's built.", town,
			(int) Math.min(99, Math.floor(percent)));
		return null;
	}

	/** (slice A2) What the player is told when a growth can't be undone yet because it is still being built from the stockpile. */
	public static String buildingLine(int unbuilt, int all) {
		String left = all <= 0 ? "" : " (" + unbuilt + " of its " + all + " construction sites still to build)";
		return "The addition is still being built from the stockpile" + left + ": it can be undone once it's built. Fill the stockpile to finish it.";
	}

	static String buildingLine(MinecraftServer server, Ref ref) {
		var r = ArchitectAccess.api().regions().get(ref.region()).flatMap(v -> revision(v, ref.rid())).orElse(null);
		if (r == null) return buildingLine(0, 0);
		var sites = ArchitectAccess.api().sites(server);
		return buildingLine(unbuilt(r, id -> sites.get(id).map(dev.larattalabs.architect.api.SiteView::state).orElse(null)), r.sites().size());
	}

	/** (raw-text) What a growth's take-up Architect refused (not waiting) says: a storage hall's words, else the steward's generic words. Pure. */
	static String refusedLine(@Nullable String held, boolean error) {
		if (held != null) return "The town's change could not be taken up: " + held + ".";
		if (error) return "The town's change could not be taken up just now. Ask again in a moment.";
		return "The town's change could not be taken up: " + RemovalStops.IN_THE_WAY + ". Ask again " + RemovalStops.tail(null) + ".";
	}

	/** Whether Architect's refusal is its survival_building one (a target still building: undo refused, nothing written). */
	public static boolean buildingRefusal(@Nullable String message) {
		return message != null && message.contains("survival_building");
	}

	public static Optional<RevisionView> revision(RegionView v, int rid) {
		return v.revisions() == null ? Optional.empty() : v.revisions().stream().filter(r -> r.rid() == rid).findFirst();
	}

	/** The revision a plan was realised as (its plan id), for an answer that was lost: never one found by tagged sites (it may have none yet). */
	public static Optional<RevisionView> byPlan(RegionView v, String planId) {
		return v.revisions() == null ? Optional.empty() : v.revisions().stream().filter(r -> planId.equals(r.planId())).findFirst();
	}

	/** Whether a revision of the region stands or is in flight (any not undone or stale): the town itself may not be removed then. */
	public static boolean hasRevisions(RegionView v) {
		return v.revisions() != null && v.revisions().stream().anyMatch(r -> !"UNDONE".equalsIgnoreCase(r.state()) && !"STALE".equalsIgnoreCase(r.state()));
	}

	/**
	 * Why the town itself may not be removed now, or null (docs/GROWTH.md): busy, a revision in flight (whatever it has written, sites or not), or one of
	 * Steward's own growths of it still standing (logged in the settlement's history and not undone: undone newest first). A revision Steward never
	 * logged (another mod's, a dev revision) goes with the town, as Architect's removal takes the whole region (6d RV, Steward's RC).
	 */
	public static @Nullable String blocksTownRemoval(RegionView v, dev.larattalabs.steward.model.Settlement s) {
		return blocksTownRemoval(v, s, null);
	}

	/** {@code sites}: Architect's sites, to read a revision's sites' tags; null skips that check. */
	public static @Nullable String blocksTownRemoval(RegionView v, dev.larattalabs.steward.model.Settlement s, dev.larattalabs.architect.api.@Nullable Sites sites) {
		String history = blocksRemoval(v.id(), v.busy(), v.revisions(), s);
		if (history != null) return history;
		if (v.revisions() == null) return null;
		for (RevisionView r : v.revisions()) {
			String st = r.state() == null ? "" : r.state().toUpperCase(Locale.ROOT);
			if (st.equals("UNDONE") || st.equals("STALE")) continue;
			String t = tag(v.id(), r.rid());
			// Steward's own growth that was never logged (its log failed, or it placed nothing): its sites carry the settlement's tag (the realise's ext
			// goes on every revision item), so it is not taken with the town unasked
			if (sites != null && r.sites() != null && r.sites().stream().anyMatch(id -> sites.get(id).map(x -> s.id().equals(stringExt(x.ext(), "steward_mc:settlement")))
				.orElse(false))) return "the village has an addition of this settlement's not in its history (" + t + "); it is noted, not taken with the town";
		}
		return null;
	}

	/**
	 * Pure core of {@link #blocksTownRemoval}: a region (a town's, or since slice DG a district's own) busy, with a revision in flight, or with one of the
	 * settlement's logged growths still standing is never removed whole.
	 */
	static @Nullable String blocksRemoval(String region, @Nullable String busy, @Nullable List<RevisionView> revisions, dev.larattalabs.steward.model.Settlement s) {
		if (busy != null && !busy.isBlank()) return "the village is busy (" + busy + "); try again once it has settled";
		if (revisions == null) return null;
		for (RevisionView r : revisions) {
			String st = r.state() == null ? "" : r.state().toUpperCase(Locale.ROOT);
			if (st.equals("UNDONE") || st.equals("STALE")) continue;
			if (!ended(st)) return "a change to the village is still being made (" + tag(region, r.rid()) + "); try again once it has settled";
			String t = tag(region, r.rid());
			if (s.log().stream().anyMatch(e -> e.kind() == dev.larattalabs.steward.model.Settlement.Kind.PROJECT_PLACED && t.equals(e.siteGroup())))
				return "the village has grown since (" + t + "); undo its newer changes first";
		}
		return null;
	}

	private static @Nullable String stringExt(com.google.gson.@Nullable JsonObject ext, String key) {
		return ext != null && ext.has(key) && ext.get(key).isJsonPrimitive() ? ext.get(key).getAsString() : null;
	}

	/** Whether a revision state is ended (the revision's outcome is known). */
	public static boolean ended(@Nullable String state) {
		if (state == null) return false;
		return switch (state.toUpperCase(Locale.ROOT)) {
			case "PLACED", "PARTIAL", "FAILED" -> true;
			default -> false;
		};
	}

	/**
	 * Takes a growth up: {@code done} gets true when it is undone (or was already, or the town is gone), false when it is not taken now (said why; the
	 * history entry stays, so it can be asked again), null when the world closed meanwhile. Never removes the region.
	 */
	public static void undo(MinecraftServer server, String owner, Ref ref, Consumer<String> say, Consumer<@Nullable Boolean> done) {
		undo(server, owner, ref, say, done, r -> { });
	}

	/**
	 * As above; {@code result} (slice A2L) gets Architect's answer to this call's {@code undoRevision} when it took the growth up (its refund), before
	 * {@code done}; nothing when there was none to read (taken up already, or the world closed).
	 */
	public static void undo(MinecraftServer server, String owner, Ref ref, Consumer<String> say, Consumer<@Nullable Boolean> done,
		Consumer<dev.larattalabs.architect.api.RemoveResult> result) {
		undo(server, owner, ref, say, done, result, null);
	}

	/** (T4S-PRE) The growth take-ups whose held, decided words were said this session (a re-ask answered held again says nothing more). */
	private static final java.util.Set<String> HELD_SAID = java.util.concurrent.ConcurrentHashMap.newKeySet();

	static {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> HELD_SAID.clear());
	}

	/**
	 * As above; {@code onHeld} (T4S-PRE) runs instead of {@code done} when Architect decided the take-up and holds it ({@code SITE_BUSY held(decided)}): said
	 * once as under way (it carries on by itself; never "ask again"), the revision stays UNDOING and the growth sweep logs it once it reads UNDONE. Null:
	 * {@code done(false)}. A take-up refused {@code held(other)} (nothing changed) is asked again a moment later ({@link Undo#asking}).
	 */
	public static void undo(MinecraftServer server, String owner, Ref ref, Consumer<String> say, Consumer<@Nullable Boolean> done,
		Consumer<dev.larattalabs.architect.api.RemoveResult> result, @Nullable Runnable onHeld) {
		var regions = ArchitectAccess.api().regions();
		Decision d = decide(regions.get(ref.region()), ref.rid(), ArchitectAccess.api().sites(server));
		switch (d.step()) {
			case GONE -> {
				// (T4S-PRE) a town read as gone only because Architect has no record of it, on a store that did not load: never taken for gone
				if (regions.get(ref.region()).isEmpty() && !Undo.healthy(server)) {
					say.accept("The town's change can't be taken up now: " + RemovalStops.RECORDS_UNREADABLE + ".");
					done.accept(false);
					return;
				}
				done.accept(true);
				return;
			}
			case UNKNOWN, NEWER, WAIT -> {
				say.accept("The town's change can't be taken up now: " + d.why() + ".");
				done.accept(false);
				return;
			}
			case BUILDING -> {
				say.accept(buildingLine(server, ref));
				done.accept(false);
				return;
			}
			case UNDO -> { }
		}
		// (M4, Q3) a storage hall of this growth holding items: refused at once and said (Architect's removal would wait on the cargo as a blocker)
		var rv = regions.get(ref.region()).flatMap(v -> v.revisions().stream().filter(x -> x.rid() == ref.rid()).findFirst());
		String hall = rv.map(x -> HallCargo.before(server, owner, x.sites())).orElse(null);
		if (hall != null) {
			say.accept("The town's change could not be taken up: " + hall + ".");
			done.accept(false);
			return;
		}
		int session = Session.current();
		Steward.LOGGER.info("undo growth {} of {}: undoRevision to {}", ref.tag(), owner, d.toPosition());
		Undo.asking(server, () -> regions.undoRevision(ref.region(), d.toPosition(), owner)).whenComplete((r, err) -> ArchitectAccess.onServer(server, () -> {
			if (!Session.is(session) || SettlementRunner.worldStopped(err)) { done.accept(null); return; }
			// (T4S-PRE) decided and held: under way, carrying on by itself; nothing refused, nothing to ask again
			var hold = SiteBusy.of(err);
			if (hold.decided()) {
				Steward.LOGGER.info("undo growth {} of {} is decided and held: {}", ref.tag(), owner, SettlementRunner.message(err));
				if (HELD_SAID.add(ref.tag())) say.accept(RemovalStops.growthUnderWay(hold));
				if (onHeld != null) onHeld.run();
				else done.accept(false);
				return;
			}
			if (err == null && r.removed()) { result.accept(r); done.accept(true); return; }
			// (T4RD-S) refused as busy before it began: said by its typed cause (never Architect's text); the player asks again
			if (hold.busyAtAll()) {
				Steward.LOGGER.info("undo growth {} of {} was refused as busy: {}", ref.tag(), owner, SettlementRunner.message(err));
				say.accept(RemovalStops.growthBusy(hold, RemovalStops.blocker(server, hold, owner)));
				done.accept(false);
				return;
			}
			// answered not removed, or refused: read again (a lost answer of an undo that went through reads UNDONE)
			var again = decide(regions.get(ref.region()), ref.rid());
			if (again.step() == Step.GONE && (regions.get(ref.region()).isPresent() || Undo.healthy(server))) { done.accept(true); return; }
			// (slice A2) a target still building (a race past the check above): said plainly, never Architect's text
			if (err != null && buildingRefusal(SettlementRunner.message(err))) { say.accept(buildingLine(server, ref)); done.accept(false); return; }
			// (M4, Q3) refused for a storage hall holding items (Architect throws NOT_ALLOWED naming the sites to empty): said as the steward says
			// it, with the count and the chests, then Architect's own list
			String why = err != null ? SettlementRunner.message(err) : "blocked by " + String.join(", ", r.blockers());
			String held = HallCargo.cargoRefusal(why) ? HallCargo.words(server, owner, ref.region(), why) : null;
			// (slice DRS) left UNDOING with a stage wait (Architect resumes it at a same-target call or the next world start): waiting, never refused;
			// (8bS) its text is never shown (a refused save's raw text names chunk files); (RVC) what it waits for from the revision's typed cause
			var after = regions.get(ref.region()).flatMap(v -> v.revisions().stream().filter(x -> x.rid() == ref.rid()).findFirst()).orElse(null);
			// (T4S-PRE) on Architect's transactions (T4) a revision left UNDOING waits on a decided hold, which carries on by itself
			if (held == null && RemovalStops.growthWaits(after)) { say.accept(RemovalStops.growthWaiting(RemovalStops.revisionCause(after), Undo.txnRemovals())); done.accept(false); return; }
			// (raw-text) Architect's text is logged, never said: the hall's own words, else (no typed cause when not waiting) the generic line
			Steward.LOGGER.info("undo growth {} of {} stopped: {}", ref.tag(), owner, why);
			say.accept(refusedLine(held, err != null));
			done.accept(false);
		}));
	}
}
