package dev.larattalabs.steward.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.pipeline.Pipeline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The unfinished builds of one world ({@code <world>/steward-builds.json}, written atomically like the settlements file): what a {@link SettlementRunner}
 * needs to pick its build up again after a restart. A finished, failed or cancelled build is dropped (its outcome goes to the settlement's change log).
 * Pure (no Minecraft types), so it round-trips in unit tests.
 */
public final class BuildStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/**
	 * 2 (2026-10-24): moves with the settlements file's format 4 (a build's settlement copy has districts with kinds and land), so an older Steward refuses
	 * the file rather than dropping their new members. A format-1 file reads as it is (its districts as standing squares).
	 */
	static final int FORMAT = 2;

	/**
	 * One build. {@code settlement} is a copy (a dev build's settlement is not in the settlements file); {@code siteGroupId} is the Architect site group the
	 * placement batch made, kept because finished batches are not kept across restarts.
	 */
	public record Saved(String settlementId, String buildId, UUID playerId, String dimension, Permission permission, int landmarks, Settlement settlement, Pipeline.State state,
		VillageLayout.Plan plan, @Nullable String bibleJobId, @Nullable String groupId, @Nullable String batchId, @Nullable String siteGroupId, List<String> landmarkIds,
		List<String> decided, List<String> lastAwaiting, boolean addition, @Nullable String districtId, @Nullable Boolean street, @Nullable Boolean keyed,
		@Nullable String vPlan, @Nullable String vRegion) {
		/** As saved before Architect's flat village: Steward's own layout. */
		public Saved(String settlementId, String buildId, UUID playerId, String dimension, Permission permission, int landmarks, Settlement settlement,
			Pipeline.State state, VillageLayout.Plan plan, @Nullable String bibleJobId, @Nullable String groupId, @Nullable String batchId, @Nullable String siteGroupId,
			List<String> landmarkIds, List<String> decided, List<String> lastAwaiting, boolean addition, @Nullable String districtId, @Nullable Boolean street,
			@Nullable Boolean keyed) {
			this(settlementId, buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId, landmarkIds,
				decided, lastAwaiting, addition, districtId, street, keyed, null, null);
		}

		/** As saved before operation keys: such a build's requests were sent without one, so they cannot be looked up by it. */
		public Saved(String settlementId, String buildId, UUID playerId, String dimension, Permission permission, int landmarks, Settlement settlement,
			Pipeline.State state, VillageLayout.Plan plan, @Nullable String bibleJobId, @Nullable String groupId, @Nullable String batchId, @Nullable String siteGroupId,
			List<String> landmarkIds, List<String> decided, List<String> lastAwaiting, boolean addition, @Nullable String districtId, @Nullable Boolean street) {
			this(settlementId, buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId, landmarkIds,
				decided, lastAwaiting, addition, districtId, street, null, null, null);
		}

		/** Whether its requests carry operation keys (C9): an interrupted one is then found again by its key. */
		public boolean usesKeys() {
			return keyed != null && keyed;
		}

		/**
		 * As saved before districts: a build in the town ({@code districtId} null), laying a street unless it is an addition ({@code street} null reads
		 * as that).
		 */
		public Saved(String settlementId, String buildId, UUID playerId, String dimension, Permission permission, int landmarks, Settlement settlement,
			Pipeline.State state, VillageLayout.Plan plan, @Nullable String bibleJobId, @Nullable String groupId, @Nullable String batchId, @Nullable String siteGroupId,
			List<String> landmarkIds, List<String> decided, List<String> lastAwaiting, boolean addition) {
			this(settlementId, buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId, landmarkIds,
				decided, lastAwaiting, addition, null, null, null);
		}

		/** Whether the build lays a street. */
		public boolean laysStreet() {
			return street != null ? street : !addition;
		}

		/** Builds saved before additions (a proposal's one building, no new street) read as whole settlements. */
		public Saved(String settlementId, String buildId, UUID playerId, String dimension, Permission permission, int landmarks, Settlement settlement,
			Pipeline.State state, VillageLayout.Plan plan, @Nullable String bibleJobId, @Nullable String groupId, @Nullable String batchId, @Nullable String siteGroupId,
			List<String> landmarkIds, List<String> decided, List<String> lastAwaiting) {
			this(settlementId, buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId, landmarkIds,
				decided, lastAwaiting, false);
		}

		public Saved {
			landmarkIds = landmarkIds == null ? List.of() : List.copyOf(landmarkIds);
			decided = decided == null ? List.of() : List.copyOf(decided);
			lastAwaiting = lastAwaiting == null ? List.of() : List.copyOf(lastAwaiting);
		}
	}

	private record FileShape(int format, List<Saved> builds) {}

	private final Map<String, Saved> byId = new LinkedHashMap<>();
	private int readFormat = FORMAT;

	public List<Saved> all() {
		return List.copyOf(byId.values());
	}

	public void put(Saved s) {
		byId.put(s.settlementId(), s);
	}

	public void remove(String settlementId) {
		byId.remove(settlementId);
	}

	/** The format the file had when read. */
	public int readFormat() {
		return readFormat;
	}

	public String toJson() {
		return GSON.toJson(new FileShape(FORMAT, new ArrayList<>(byId.values())));
	}

	public static BuildStore fromJson(String json) {
		FileShape f = GSON.fromJson(json, new TypeToken<FileShape>() {});
		if (f == null || f.builds == null) throw new JsonParseException("not a Steward builds file");
		if (f.format < 1 || f.format > FORMAT) throw new JsonParseException("unsupported builds format " + f.format + " (this build reads 1.." + FORMAT + ")");
		BuildStore s = new BuildStore();
		s.readFormat = f.format;
		for (Saved b : f.builds) {
			if (b == null || b.settlementId() == null || b.buildId() == null || b.state() == null || b.plan() == null || b.settlement() == null || b.playerId() == null
				|| b.dimension() == null || b.permission() == null || b.state().phase() == null || b.plan().lots() == null) {
				throw new JsonParseException("incomplete build entry");
			}
			s.put(b);
		}
		return s;
	}

	public void save(Path file) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Loads a store, or an empty one when the file does not exist. A corrupt file is an error, never silently emptied. */
	public static BuildStore load(Path file) throws IOException {
		if (!Files.exists(file)) return new BuildStore();
		BuildStore s = fromJson(Files.readString(file, StandardCharsets.UTF_8));
		// migrated: keep the file as it was before anything is written over it (once), as the settlements file does
		if (s.readFormat < FORMAT) {
			Path bak = file.resolveSibling(file.getFileName() + ".v" + s.readFormat + ".bak");
			if (!Files.exists(bak)) Files.copy(file, bak);
		}
		return s;
	}
}
