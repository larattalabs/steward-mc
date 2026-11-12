package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
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
		 * (slice A2) The town builds from materials and its construction targets are not all built: the revision, the head, is still being built from the
		 * stockpile (a revision is realised only over a fully built town, and nothing newer stands on it), so Architect would refuse its undo
		 * ({@code survival_building}). Nothing is asked; it can be undone once built.
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
	 * {@code building} (slice A2): the town builds from materials and not every construction target of it is built. Decided after a newer revision (which
	 * is said first) and before the take-up; its own interrupted take-up (UNDOING) still resumes.
	 */
	public static Decision decide(boolean regionGone, boolean removing, @Nullable String busy, int head, @Nullable String revState, int revPosition,
		boolean building) {
		if (regionGone) return new Decision(Step.GONE, 0, "the town is gone");
		if (removing) return new Decision(Step.WAIT, 0, "the town is being taken up");
		if (revState == null) return new Decision(Step.UNKNOWN, 0, "Architect has no such revision of the town");
		String st = revState.toUpperCase(Locale.ROOT);
		if (st.equals("UNDONE") || st.equals("STALE")) return new Decision(Step.GONE, 0, "it was taken up already");
		// its own take-up, cut short by a stop or a restart: the same call resumes it (Architect: a same-target call)
		if (st.equals("UNDOING")) return new Decision(Step.UNDO, revPosition - 1, "resuming its take-up");
		if (busy != null && !busy.isBlank()) return new Decision(Step.WAIT, 0, "the town is busy (" + busy + ")");
		if (st.equals("PLACING") || st.equals("PLANNED") || st.equals("ACCEPTED")) return new Decision(Step.WAIT, 0, "it is still being placed");
		if (head != revPosition) return new Decision(Step.NEWER, 0, head > revPosition ? "a newer change to the town stands above it" : "the town's history moved below it");
		if (building) return new Decision(Step.BUILDING, 0, "it is still being built from the stockpile");
		return new Decision(Step.UNDO, revPosition - 1, "");
	}

	public static Decision decide(Optional<RegionView> v, int rid) {
		return decide(v, rid, null);
	}

	/** {@code sites}: Architect's sites, to tell whether the revision's own construction targets are all built (slice A2); null reads none as building. */
	public static Decision decide(Optional<RegionView> v, int rid, dev.larattalabs.architect.api.@Nullable Sites sites) {
		boolean gone = v.isEmpty() || v.get().state() == dev.larattalabs.architect.api.RegionState.REMOVED;
		boolean removing = v.isPresent() && v.get().state() == dev.larattalabs.architect.api.RegionState.REMOVING;
		RevisionView r = v.flatMap(x -> revision(x, rid)).orElse(null);
		boolean building = sites != null && r != null && v.map(Growths::fromMaterials).orElse(false) && unbuilt(r, id -> sites.get(id).map(dev.larattalabs.architect.api.SiteView::state)
			.orElse(null)) > 0;
		return decide(gone, removing, v.map(RegionView::busy).orElse(null), v.map(RegionView::position).orElse(0), r == null ? null : r.state(), r == null ? 0 : r.position(),
			building);
	}

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
		var v = ArchitectApi.get().regions().get(ref.region());
		var r = v.flatMap(x -> revision(x, ref.rid()));
		if (r.isEmpty() || !fromMaterials(v.get())) return false;
		var sites = ArchitectApi.get().sites(server);
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
		if (townFromMaterials && !built) return String.format(Locale.ROOT, "%s is still being built from the stockpile (%.0f%%): it can grow once it's built.", town, percent);
		return null;
	}

	/** (slice A2) What the player is told when a growth can't be undone yet because it is still being built from the stockpile. */
	public static String buildingLine(int unbuilt, int all) {
		String left = all <= 0 ? "" : " (" + unbuilt + " of its " + all + " construction sites still to build)";
		return "The addition is still being built from the stockpile" + left + ": it can be undone once it's built. Fill the stockpile to finish it.";
	}

	static String buildingLine(MinecraftServer server, Ref ref) {
		var r = ArchitectApi.get().regions().get(ref.region()).flatMap(v -> revision(v, ref.rid())).orElse(null);
		if (r == null) return buildingLine(0, 0);
		var sites = ArchitectApi.get().sites(server);
		return buildingLine(unbuilt(r, id -> sites.get(id).map(dev.larattalabs.architect.api.SiteView::state).orElse(null)), r.sites().size());
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
		if (v.busy() != null && !v.busy().isBlank()) return "the village is busy (" + v.busy() + "); try again once it has settled";
		if (v.revisions() == null) return null;
		for (RevisionView r : v.revisions()) {
			String st = r.state() == null ? "" : r.state().toUpperCase(Locale.ROOT);
			if (st.equals("UNDONE") || st.equals("STALE")) continue;
			if (!ended(st)) return "a change to the village is still being made (" + tag(v.id(), r.rid()) + "); try again once it has settled";
			String t = tag(v.id(), r.rid());
			if (s.log().stream().anyMatch(e -> e.kind() == dev.larattalabs.steward.model.Settlement.Kind.PROJECT_PLACED && t.equals(e.siteGroup())))
				return "the village has grown since (" + t + "); undo its newer changes first";
			// Steward's own growth that was never logged (its log failed, or it placed nothing): its sites carry the settlement's tag (the realise's ext
			// goes on every revision item), so it is not taken with the town unasked
			if (sites != null && r.sites() != null && r.sites().stream().anyMatch(id -> sites.get(id).map(x -> s.id().equals(stringExt(x.ext(), "steward_mc:settlement")))
				.orElse(false))) return "the village has an addition of this settlement's not in its history (" + t + "); it is noted, not taken with the town";
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
		var regions = ArchitectApi.get().regions();
		Decision d = decide(regions.get(ref.region()), ref.rid(), ArchitectApi.get().sites(server));
		switch (d.step()) {
			case GONE -> { done.accept(true); return; }
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
		int session = Session.current();
		Steward.LOGGER.info("undo growth {} of {}: undoRevision to {}", ref.tag(), owner, d.toPosition());
		regions.undoRevision(ref.region(), d.toPosition(), owner).whenComplete((r, err) -> server.execute(() -> {
			if (!Session.is(session) || SettlementRunner.worldStopped(err)) { done.accept(null); return; }
			if (err == null && r.removed()) { done.accept(true); return; }
			// answered not removed, or refused: read again (a lost answer of an undo that went through reads UNDONE)
			var again = decide(regions.get(ref.region()), ref.rid());
			if (again.step() == Step.GONE) { done.accept(true); return; }
			// (slice A2) a target still building (a race past the check above): said plainly, never Architect's text
			if (err != null && buildingRefusal(SettlementRunner.message(err))) { say.accept(buildingLine(server, ref)); done.accept(false); return; }
			say.accept("The town's change could not be taken up: " + (err != null ? SettlementRunner.message(err) : "blocked by " + String.join(", ", r.blockers())) + ".");
			done.accept(false);
		}));
	}
}
