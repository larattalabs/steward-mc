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
	private static final int FORMAT = 1;

	/**
	 * One build. {@code settlement} is a copy (a dev build's settlement is not in the settlements file); {@code siteGroupId} is the Architect site group the
	 * placement batch made, kept because finished batches are not kept across restarts.
	 */
	public record Saved(String settlementId, String buildId, UUID playerId, String dimension, Permission permission, int landmarks, Settlement settlement, Pipeline.State state,
		VillageLayout.Plan plan, @Nullable String bibleJobId, @Nullable String groupId, @Nullable String batchId, @Nullable String siteGroupId, List<String> landmarkIds,
		List<String> decided, List<String> lastAwaiting) {
		public Saved {
			landmarkIds = landmarkIds == null ? List.of() : List.copyOf(landmarkIds);
			decided = decided == null ? List.of() : List.copyOf(decided);
			lastAwaiting = lastAwaiting == null ? List.of() : List.copyOf(lastAwaiting);
		}
	}

	private record FileShape(int format, List<Saved> builds) {}

	private final Map<String, Saved> byId = new LinkedHashMap<>();

	public List<Saved> all() {
		return List.copyOf(byId.values());
	}

	public void put(Saved s) {
		byId.put(s.settlementId(), s);
	}

	public void remove(String settlementId) {
		byId.remove(settlementId);
	}

	public String toJson() {
		return GSON.toJson(new FileShape(FORMAT, new ArrayList<>(byId.values())));
	}

	public static BuildStore fromJson(String json) {
		FileShape f = GSON.fromJson(json, new TypeToken<FileShape>() {});
		if (f == null || f.builds == null) throw new JsonParseException("not a Steward builds file");
		if (f.format != FORMAT) throw new JsonParseException("unsupported builds format " + f.format);
		BuildStore s = new BuildStore();
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
		return fromJson(Files.readString(file, StandardCharsets.UTF_8));
	}
}
