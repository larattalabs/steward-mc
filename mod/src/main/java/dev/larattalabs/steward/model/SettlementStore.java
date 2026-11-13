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
	/**
	 * 2 (2026-10-09): the change log holds operations (op ids, version changes, outcomes). 3 (2026-10-10): settlements have lands (districts, protected
	 * areas); a format-2 file reads with none. 4 (2026-10-24): a
	 * district has a kind, a state and its land (docs/DISTRICTS.md section 9); a format-3 district reads as a standing square. An older Steward refuses a
	 * format-4 file rather than dropping the new members on its next save.
	 */
	/** 5 (2026-10-26): a proposal may carry a quarter (Noah's Q2); an older Steward would drop it and found a free square district instead, so it refuses. */
	public static final int FORMAT = 7;
	// 7 (2026-11-13, phase 5 slice M3; docs/MODULES.md 4.1): a proposal may offer a module (its catalog version, program, params and in survival the
	// bound the player approves); an older Steward would drop it and design the lot as a paid decorative building, so it refuses the file
	// 6 (2026-11-11): a history entry may be a region town's growth (siteGroup revision:<region>:<rid>, docs/GROWTH.md), undone by Architect's undoRevision;
	// an older Steward would take it for a site group and remove its sites one by one, leaving the town's revision standing, so it refuses the file

	/** Step {@code i} upgrades format {@code i + 1} to {@code i + 2}, on the file's JSON. */
	private static final List<java.util.function.Consumer<JsonObject>> MIGRATIONS = List.of(SettlementStore::v1ToV2, SettlementStore::v2ToV3, SettlementStore::v3ToV4, SettlementStore::v4ToV5, SettlementStore::v5ToV6, SettlementStore::v6ToV7);

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

	/** Adds or replaces. A settlement whose claim or districts overlap another's claim or districts in the same dimension is refused. */
	public void put(Settlement s) {
		for (Settlement o : byId.values()) {
			if (o.id().equals(s.id())) continue;
			for (Land a : s.ground()) for (Land b : o.ground()) {
				if (a.overlaps(b)) throw new IllegalArgumentException("claim overlaps settlement " + o.id());
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

	/** The settlement whose claim or one of whose districts contains a point, if any. */
	public Optional<Settlement> at(String dim, int x, int y, int z) {
		return byId.values().stream().filter(s -> s.contains(dim, x, y, z)).findFirst();
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
		strictModules(o);
		FileShape f = GSON.fromJson(o, new TypeToken<FileShape>() {});
		if (f == null || f.settlements == null) throw new JsonParseException("not a Steward settlements file");
		SettlementStore s = new SettlementStore();
		for (Settlement x : f.settlements) {
			// (slice M3) a module offer is whole or the file is refused: a module rule's proposal without its order would be built as a paid design
			for (var p : x.proposals() == null ? List.<Proposal>of() : x.proposals().open()) {
				if (p.key().startsWith(dev.larattalabs.steward.module.ModuleKind.RULE_PREFIX) && p.module() == null)
					throw new JsonParseException("module offer " + p.key() + " of " + x.id() + " has no order");
				if (p.module() != null && !p.module().valid()) throw new JsonParseException("module offer " + p.key() + " of " + x.id() + " is incomplete");
			}
			s.put(x);
		}
		s.nextSerial = Math.max(s.nextSerial, f.nextSerial);
		s.readFormat = format;
		return s;
	}

	public int readFormat() {
		return readFormat;
	}

	/**
	 * (slice M3) Module offers' integers and bills are read exactly, as BuildStore's orders are: Gson would read {@code 1.0000000000000001} as version
	 * 1, and a bound it can't read would become none. Either refuses the file.
	 */
	private static void strictModules(JsonObject file) {
		if (!file.has("settlements") || !file.get("settlements").isJsonArray()) return;
		for (JsonElement st : file.getAsJsonArray("settlements")) {
			if (!st.isJsonObject() || !st.getAsJsonObject().has("proposals") || !st.getAsJsonObject().get("proposals").isJsonObject()) continue;
			var ps = st.getAsJsonObject().getAsJsonObject("proposals");
			if (!ps.has("open") || !ps.get("open").isJsonArray()) continue;
			for (JsonElement p : ps.getAsJsonArray("open")) {
				if (!p.isJsonObject() || !p.getAsJsonObject().has("module") || p.getAsJsonObject().get("module").isJsonNull()) continue;
				if (!p.getAsJsonObject().get("module").isJsonObject()) throw new JsonParseException("a module offer is not an object");
				var m = p.getAsJsonObject().getAsJsonObject("module");
				if (!m.has("version") || dev.larattalabs.steward.module.ModuleRef.exactInt(m.get("version")) == null) throw new JsonParseException("a module offer's version is not an integer");
				for (String k : List.of("bound", "held")) {
					if (m.has(k) && !m.get(k).isJsonNull() && (!m.get(k).isJsonObject() || dev.larattalabs.steward.module.ModuleBound.clean(m.getAsJsonObject(k)) == null))
						throw new JsonParseException("a module offer's " + k + " is not item -> count");
				}
			}
		}
	}

	private static final java.util.regex.Pattern UPDATED_NOTE = java.util.regex.Pattern.compile("Updated (.+) to version (\\d+).*");

	/**
	 * Format 1 to 2: every log entry gets its op id ({@code op_<n>}, by position); the notes that were operations become them ("Claim is now ..." a claim
	 * change, "Updated X to version N" an update to N from an unrecorded version).
	 */
	static void v1ToV2(JsonObject file) {
		for (JsonElement se : file.getAsJsonArray("settlements")) {
			if (!se.isJsonObject() || !se.getAsJsonObject().has("log") || !se.getAsJsonObject().get("log").isJsonArray()) continue;
			JsonArray log = se.getAsJsonObject().getAsJsonArray("log");
			for (int i = 0; i < log.size(); i++) {
				if (!log.get(i).isJsonObject()) continue;
				JsonObject e = log.get(i).getAsJsonObject();
				if (!e.has("op")) e.addProperty("op", "op_" + (i + 1));
				if (!e.has("kind") || !e.get("kind").isJsonPrimitive() || !"NOTE".equals(e.get("kind").getAsString()) || !e.has("text") || !e.get("text").isJsonPrimitive()) continue;
				String text = e.get("text").getAsString();
				if (text.startsWith("Claim is now ")) {
					e.addProperty("kind", "CLAIM_CHANGED");
					continue;
				}
				var m = UPDATED_NOTE.matcher(text);
				JsonArray sites = e.has("siteIds") && e.get("siteIds").isJsonArray() ? e.getAsJsonArray("siteIds") : null;
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

	/** Format 2 to 3: nothing to rewrite; a settlement without {@code lands} reads with no districts and no protected areas. */
	static void v2ToV3(JsonObject file) {
	}

	/** Format 5 to 6: nothing to rewrite (a format-5 history has no growth). */
	static void v5ToV6(JsonObject file) {
	}

	/** Format 6 to 7: nothing to rewrite (a proposal without a module reads as before). */
	static void v6ToV7(JsonObject file) {
	}

	/** Format 4 to 5: nothing to rewrite (a proposal without a quarter reads as before). */
	static void v4ToV5(JsonObject file) {
	}

	/** Format 3 to 4: every district is a standing square (what format 3 knew); its land is its claim, so none is written. */
	static void v3ToV4(JsonObject file) {
		for (JsonElement se : file.getAsJsonArray("settlements")) {
			if (!se.isJsonObject() || !se.getAsJsonObject().has("lands") || !se.getAsJsonObject().get("lands").isJsonObject()) continue;
			JsonObject lands = se.getAsJsonObject().getAsJsonObject("lands");
			if (!lands.has("districts") || !lands.get("districts").isJsonArray()) continue;
			for (JsonElement de : lands.getAsJsonArray("districts")) {
				if (!de.isJsonObject()) continue;
				JsonObject d = de.getAsJsonObject();
				d.addProperty("kind", "square");
				d.addProperty("state", "standing");
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
