package dev.larattalabs.steward.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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

/**
 * The settlements of one world, persisted as one JSON file written atomically (temp file, then move). Older formats are migrated when read (one step per
 * format, {@link #MIGRATIONS}); the file as it was is kept beside it as {@code <name>.v<format>.bak} before anything is written over it. A newer format than
 * this build knows is refused, and the file left untouched.
 */
public final class SettlementStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** 2 (2026-10-09): the change log holds operations (op ids, version changes, outcomes). */
	public static final int FORMAT = 2;

	/** Step {@code i} upgrades format {@code i + 1} to {@code i + 2}, on the file's JSON. */
	private static final List<java.util.function.Consumer<JsonObject>> MIGRATIONS = List.of(SettlementStore::v1ToV2);

	/** The format the file had when read (equal to {@link #FORMAT} unless it was migrated). */
	private int readFormat = FORMAT;

	/** {@code nextSerial} is absent (0) in files written before it existed; ids are then derived from the settlements present. */
	private record FileShape(int format, List<Settlement> settlements, int nextSerial) {}

	private final Map<String, Settlement> byId = new LinkedHashMap<>();
	/** The next id's number. It only grows, so a removed settlement's id (its Architect owner string, its tagged steward) is never handed to a new one. */
	private int nextSerial = 1;

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
		nextSerial = Math.max(nextSerial, serialOf(s.id()) + 1);
	}

	public void remove(String id) {
		byId.remove(id);
	}

	/** The id for a new settlement, {@code set_<n>}. Taking it does not reserve it; {@link #put} does. */
	public String nextId() {
		return "set_" + nextSerial;
	}

	private static int serialOf(String id) {
		if (!id.startsWith("set_")) return 0;
		try {
			return Integer.parseInt(id.substring(4));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** The settlement whose claim contains a point, if any. */
	public Optional<Settlement> at(String dim, int x, int y, int z) {
		return byId.values().stream().filter(s -> s.claim().contains(dim, x, y, z)).findFirst();
	}

	public String toJson() {
		return GSON.toJson(new FileShape(FORMAT, new ArrayList<>(byId.values()), nextSerial));
	}

	public static SettlementStore fromJson(String json) {
		JsonElement root = JsonParser.parseString(json);
		if (!root.isJsonObject() || !root.getAsJsonObject().has("format")) throw new JsonParseException("not a Steward settlements file");
		JsonObject o = root.getAsJsonObject();
		int format = o.get("format").getAsInt();
		if (format < 1 || format > FORMAT) throw new JsonParseException("unsupported settlements format " + format + " (this build reads 1.." + FORMAT + ")");
		for (int v = format; v < FORMAT; v++) MIGRATIONS.get(v - 1).accept(o);
		o.addProperty("format", FORMAT);
		FileShape f = GSON.fromJson(o, new TypeToken<FileShape>() {});
		if (f == null || f.settlements == null) throw new JsonParseException("not a Steward settlements file");
		SettlementStore s = new SettlementStore();
		for (Settlement x : f.settlements) s.put(x);
		s.nextSerial = Math.max(s.nextSerial, f.nextSerial);
		s.readFormat = format;
		return s;
	}

	public int readFormat() {
		return readFormat;
	}

	private static final java.util.regex.Pattern UPDATED_NOTE = java.util.regex.Pattern.compile("Updated (.+) to version (\\d+).*");

	/**
	 * Format 1 to 2: every log entry gets its op id ({@code op_<n>}, by position); the notes that were operations become them ("Claim is now ..." a claim
	 * change, "Updated X to version N" an update to N from an unrecorded version).
	 */
	static void v1ToV2(JsonObject file) {
		for (JsonElement se : file.getAsJsonArray("settlements")) {
			JsonArray log = se.getAsJsonObject().getAsJsonArray("log");
			if (log == null) continue;
			for (int i = 0; i < log.size(); i++) {
				JsonObject e = log.get(i).getAsJsonObject();
				if (!e.has("op")) e.addProperty("op", "op_" + (i + 1));
				if (!"NOTE".equals(e.has("kind") ? e.get("kind").getAsString() : null) || !e.has("text")) continue;
				String text = e.get("text").getAsString();
				if (text.startsWith("Claim is now ")) {
					e.addProperty("kind", "CLAIM_CHANGED");
					continue;
				}
				var m = UPDATED_NOTE.matcher(text);
				JsonArray sites = e.getAsJsonArray("siteIds");
				if (m.matches() && sites != null && sites.size() == 1) {
					e.addProperty("kind", "UPDATED");
					JsonObject c = new JsonObject();
					c.addProperty("siteId", sites.get(0).getAsString());
					c.addProperty("lot", m.group(1));
					c.addProperty("from", -1);
					c.addProperty("to", Integer.parseInt(m.group(2)));
					JsonArray changes = new JsonArray();
					changes.add(c);
					e.add("changes", changes);
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
	public static SettlementStore load(Path file) throws IOException {
		if (!Files.exists(file)) return new SettlementStore();
		SettlementStore s = fromJson(Files.readString(file, StandardCharsets.UTF_8));
		// migrated: keep the file as it was before anything is written over it (once; an existing backup is the older one, kept)
		if (s.readFormat < FORMAT) {
			Path bak = file.resolveSibling(file.getFileName() + ".v" + s.readFormat + ".bak");
			if (!Files.exists(bak)) Files.copy(file, bak);
		}
		return s;
	}
}
