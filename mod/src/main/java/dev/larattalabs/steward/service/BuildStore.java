package dev.larattalabs.steward.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.module.ModuleBinding;
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
	static final int FORMAT = 5;
	// 5 (2026-11-12, phase 5 slice M1; docs/MODULES.md 4.2 step 4): a build may hold module lots' bindings (moduleBindings: lotId -> ModuleBinding,
	// with the exact opKey and owner its variant was asked under), saved before the realise. A format-4 build has none and reads with none; an
	// older Steward refuses the file rather than dropping them (a restart would make a second variant, or realise a module lot as a pad).
	// 4 (2026-11-11): moves with the settlements file's format 6 (a history entry may be a growth, revision:<region>:<rid>); a build may be a region
	// town's growth (growRegion, growRid, growPosition, growKey; docs/GROWTH.md). A format-3 build has none and reads as before.
	// 3 (2026-10-26): moves with the settlements file's format 5 (a build's settlement copy may hold a quarter proposal); a district build keeps its card's
	// lots ({@code districtSpecs}), as asked of Architect, for a re-plan after a restart; a build's state may hold a wall lot's repair (Architect K3: the
	// REPAIRING phase, Pipeline.State.repair and repairs)

	/**
	 * One build. {@code settlement} is a copy (a dev build's settlement is not in the settlements file); {@code siteGroupId} is the Architect site group the
	 * placement batch made, kept because finished batches are not kept across restarts.
	 */
	public record Saved(String settlementId, String buildId, UUID playerId, String dimension, Permission permission, int landmarks, Settlement settlement, Pipeline.State state,
		VillageLayout.Plan plan, @Nullable String bibleJobId, @Nullable String groupId, @Nullable String batchId, @Nullable String siteGroupId, List<String> landmarkIds,
		List<String> decided, List<String> lastAwaiting, boolean addition, @Nullable String districtId, @Nullable Boolean street, @Nullable Boolean keyed,
		@Nullable String vPlan, @Nullable String vRegion, @Nullable List<VillageLayout.LotSpec> districtSpecs, @Nullable String growRegion, @Nullable Integer growRid,
		@Nullable Integer growPosition, @Nullable String growKey, Map<String, ModuleBinding> moduleBindings) {
		/** As saved before module lots (format 4): no bindings. */
		public Saved(String settlementId, String buildId, UUID playerId, String dimension, Permission permission, int landmarks, Settlement settlement,
			Pipeline.State state, VillageLayout.Plan plan, @Nullable String bibleJobId, @Nullable String groupId, @Nullable String batchId, @Nullable String siteGroupId,
			List<String> landmarkIds, List<String> decided, List<String> lastAwaiting, boolean addition, @Nullable String districtId, @Nullable Boolean street,
			@Nullable Boolean keyed, @Nullable String vPlan, @Nullable String vRegion, @Nullable List<VillageLayout.LotSpec> districtSpecs, @Nullable String growRegion,
			@Nullable Integer growRid, @Nullable Integer growPosition, @Nullable String growKey) {
			this(settlementId, buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId, landmarkIds,
				decided, lastAwaiting, addition, districtId, street, keyed, vPlan, vRegion, districtSpecs, growRegion, growRid, growPosition, growKey, Map.of());
		}

		/** The same build with these module bindings. */
		public Saved withModuleBindings(Map<String, ModuleBinding> bindings) {
			return new Saved(settlementId, buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId,
				landmarkIds, decided, lastAwaiting, addition, districtId, street, keyed, vPlan, vRegion, districtSpecs, growRegion, growRid, growPosition, growKey, bindings);
		}

		/** As saved before a build could be a region town's growth (format 3). */
		public Saved(String settlementId, String buildId, UUID playerId, String dimension, Permission permission, int landmarks, Settlement settlement,
			Pipeline.State state, VillageLayout.Plan plan, @Nullable String bibleJobId, @Nullable String groupId, @Nullable String batchId, @Nullable String siteGroupId,
			List<String> landmarkIds, List<String> decided, List<String> lastAwaiting, boolean addition, @Nullable String districtId, @Nullable Boolean street,
			@Nullable Boolean keyed, @Nullable String vPlan, @Nullable String vRegion, @Nullable List<VillageLayout.LotSpec> districtSpecs) {
			this(settlementId, buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId, landmarkIds,
				decided, lastAwaiting, addition, districtId, street, keyed, vPlan, vRegion, districtSpecs, null, null, null, null);
		}

		/** As saved before a district build kept its card's lots (format 3): a re-plan after a restart reads them back from the plan. */
		public Saved(String settlementId, String buildId, UUID playerId, String dimension, Permission permission, int landmarks, Settlement settlement,
			Pipeline.State state, VillageLayout.Plan plan, @Nullable String bibleJobId, @Nullable String groupId, @Nullable String batchId, @Nullable String siteGroupId,
			List<String> landmarkIds, List<String> decided, List<String> lastAwaiting, boolean addition, @Nullable String districtId, @Nullable Boolean street,
			@Nullable Boolean keyed, @Nullable String vPlan, @Nullable String vRegion) {
			this(settlementId, buildId, playerId, dimension, permission, landmarks, settlement, state, plan, bibleJobId, groupId, batchId, siteGroupId, landmarkIds,
				decided, lastAwaiting, addition, districtId, street, keyed, vPlan, vRegion, null);
		}

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
			moduleBindings = moduleBindings == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(moduleBindings));
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
		strictBindings(json);
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
			for (var mb : b.moduleBindings().entrySet()) {
				ModuleBinding v = mb.getValue();
				if (mb.getKey() == null || mb.getKey().isBlank() || v == null || v.moduleId() == null || v.version() < 1 || v.programSha() == null
					|| !v.programSha().matches("[0-9a-f]{64}") || v.params() == null || v.geometrySha() == null || v.opKey() == null || v.opKey().isBlank()
					|| (v.bibleId() != null && v.bibleVersion() < 1)) {
					throw new JsonParseException("incomplete module binding for lot " + mb.getKey() + " in build " + b.buildId());
				}
			}
			s.put(b);
		}
		return s;
	}

	/**
	 * Module bindings' integers are read exactly: Gson would read {@code 1.0000000000000001} or {@code 1e0} as 1 (another module version than
	 * was saved). Any binding whose {@code version} or {@code bibleVersion} is not a plain integer refuses the file.
	 */
	private static void strictBindings(String json) {
		com.google.gson.JsonElement root;
		try {
			root = com.google.gson.JsonParser.parseString(json);
		} catch (RuntimeException e) {
			throw new JsonParseException("not a Steward builds file: " + e.getMessage());
		}
		if (!root.isJsonObject() || !root.getAsJsonObject().has("builds") || !root.getAsJsonObject().get("builds").isJsonArray()) return;
		for (var b : root.getAsJsonObject().getAsJsonArray("builds")) {
			if (!b.isJsonObject() || !b.getAsJsonObject().has("moduleBindings")) continue;
			var mb = b.getAsJsonObject().get("moduleBindings");
			if (mb.isJsonNull()) continue;
			if (!mb.isJsonObject()) throw new JsonParseException("moduleBindings is not an object");
			for (var e : mb.getAsJsonObject().entrySet()) {
				if (!e.getValue().isJsonObject()) throw new JsonParseException("module binding " + e.getKey() + " is not an object");
				var o = e.getValue().getAsJsonObject();
				for (String k : List.of("version", "bibleVersion")) {
					if (o.has(k) && dev.larattalabs.steward.module.ModuleRef.exactInt(o.get(k)) == null) {
						throw new JsonParseException("module binding " + e.getKey() + ": " + k + " " + o.get(k) + " is not an integer");
					}
				}
			}
		}
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
