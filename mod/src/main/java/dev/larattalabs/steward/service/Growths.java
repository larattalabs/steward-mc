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
		UNKNOWN
	}

	public record Decision(Step step, int toPosition, String why) {}

	/**
	 * Pure. {@code regionGone}: no region, or one removed or being removed; {@code busy}: the region's busy note; {@code head}: its position;
	 * {@code revState}/{@code revPosition}: the revision with the rid ({@code revState} null when there is none).
	 */
	public static Decision decide(boolean regionGone, @Nullable String busy, int head, @Nullable String revState, int revPosition) {
		if (regionGone) return new Decision(Step.GONE, 0, "the town is gone");
		if (revState == null) return new Decision(Step.UNKNOWN, 0, "Architect has no such revision of the town");
		String st = revState.toUpperCase(Locale.ROOT);
		if (st.equals("UNDONE") || st.equals("STALE")) return new Decision(Step.GONE, 0, "it was taken up already");
		// its own take-up, cut short by a stop or a restart: the same call resumes it (Architect: a same-target call)
		if (st.equals("UNDOING")) return new Decision(Step.UNDO, revPosition - 1, "resuming its take-up");
		if (busy != null && !busy.isBlank()) return new Decision(Step.WAIT, 0, "the town is busy (" + busy + ")");
		if (st.equals("PLACING") || st.equals("PLANNED") || st.equals("ACCEPTED")) return new Decision(Step.WAIT, 0, "it is still being placed");
		if (head != revPosition) return new Decision(Step.NEWER, 0, head > revPosition ? "a newer change to the town stands above it" : "the town's history moved below it");
		return new Decision(Step.UNDO, revPosition - 1, "");
	}

	public static Decision decide(Optional<RegionView> v, int rid) {
		boolean gone = v.isEmpty() || v.get().state() == dev.larattalabs.architect.api.RegionState.REMOVED
			|| v.get().state() == dev.larattalabs.architect.api.RegionState.REMOVING;
		RevisionView r = v.flatMap(x -> revision(x, rid)).orElse(null);
		return decide(gone, v.map(RegionView::busy).orElse(null), v.map(RegionView::position).orElse(0), r == null ? null : r.state(), r == null ? 0 : r.position());
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
		Decision d = decide(regions.get(ref.region()), ref.rid());
		switch (d.step()) {
			case GONE -> { done.accept(true); return; }
			case UNKNOWN, NEWER, WAIT -> {
				say.accept("The town's change can't be taken up now: " + d.why() + ".");
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
			say.accept("The town's change could not be taken up: " + (err != null ? SettlementRunner.message(err) : "blocked by " + String.join(", ", r.blockers())) + ".");
			done.accept(false);
		}));
	}
}
