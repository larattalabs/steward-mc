package dev.larattalabs.steward.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The settlements of one world, persisted as one JSON file written atomically (temp file, then move). */
public final class SettlementStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int FORMAT = 1;

	private record FileShape(int format, List<Settlement> settlements) {}

	private final Map<String, Settlement> byId = new LinkedHashMap<>();

	public List<Settlement> all() {
		return List.copyOf(byId.values());
	}

	public Optional<Settlement> get(String id) {
		return Optional.ofNullable(byId.get(id));
	}

	/** Adds or replaces. A new settlement whose claim overlaps another's in the same dimension is refused. */
	public void put(Settlement s) {
		for (Settlement o : byId.values()) {
			if (!o.id().equals(s.id()) && o.claim().overlaps(s.claim())) {
				throw new IllegalArgumentException("claim overlaps settlement " + o.id());
			}
		}
		byId.put(s.id(), s);
	}

	public void remove(String id) {
		byId.remove(id);
	}

	public String nextId() {
		for (int i = byId.size() + 1; ; i++) {
			String id = "set_" + i;
			if (!byId.containsKey(id)) return id;
		}
	}

	/** The settlement whose claim contains a point, if any. */
	public Optional<Settlement> at(String dim, int x, int y, int z) {
		return byId.values().stream().filter(s -> s.claim().contains(dim, x, y, z)).findFirst();
	}

	public String toJson() {
		return GSON.toJson(new FileShape(FORMAT, new ArrayList<>(byId.values())));
	}

	public static SettlementStore fromJson(String json) {
		FileShape f = GSON.fromJson(json, new TypeToken<FileShape>() {});
		if (f == null || f.settlements == null) throw new JsonParseException("not a Steward settlements file");
		if (f.format != FORMAT) throw new JsonParseException("unsupported settlements format " + f.format);
		SettlementStore s = new SettlementStore();
		for (Settlement x : f.settlements) s.put(x);
		return s;
	}

	public void save(Path file) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Loads a store, or an empty one when the file does not exist. A corrupt file is an error, never silently emptied. */
	public static SettlementStore load(Path file) throws IOException {
		if (!Files.exists(file)) return new SettlementStore();
		return fromJson(Files.readString(file, StandardCharsets.UTF_8));
	}
}
