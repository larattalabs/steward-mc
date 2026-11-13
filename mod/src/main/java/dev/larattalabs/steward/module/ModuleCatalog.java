package dev.larattalabs.steward.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Steward's module catalog (docs/MODULES.md sections 1, 2 and 8, slice M1): verified building programs, as data, that Architect runs as
 * given-program designs ({@code Designs.fromProgram} / {@code variant}) at $0.
 *
 * <p>The catalog root holds {@code catalog.json} ({@code {format: 1, modules: [{id, version}]}}) and, per entry,
 * {@code <id>/<version>/}:
 * <ul>
 * <li>{@code program.mjs}: the kit program's source, sent exactly as stored; its sha256 is the entry's identity;</li>
 * <li>{@code module.json}: id, version, title, {@code programSha}, the parameter schema ({@code params}, the program's own
 * {@code export const params}), the tested envelope ({@code envelope}: its discrete {@code branches}, facings, bibles), the palette
 * constraints ({@code palette}) and the pinned kit and checker versions ({@code pins});</li>
 * <li>{@code measured.json}: what was measured, on which Minecraft and Architect versions (section 2.1: no measurement, no offer).</li>
 * </ul>
 * Loading never throws on a bad entry: the entry is kept with its {@link Entry#problems()}, and {@link ModuleGate} never offers it.
 * The live catalog ships in the jar ({@code steward/modules/}, from the repository's {@code modules/}); fixture modules live in
 * {@code fixtures/modules/catalog/} and load only from a directory (tests, the dev hook), and the live loader refuses them. Pure.
 */
public final class ModuleCatalog {
	public static final int FORMAT = 1;
	/** Architect's {@code Limits.PROGRAM_SOURCE_MAX} and {@code PROGRAM_PARAMS_MAX} (API 1.55.0), held equal by ModuleCatalogTest. */
	public static final int PROGRAM_SOURCE_MAX = 262144;
	public static final int PROGRAM_PARAMS_MAX = 16384;
	public static final String ID_RE = "[a-z][a-z0-9_]{0,39}";
	/** Where the live catalog sits in the jar. */
	public static final String RESOURCE_ROOT = "steward/modules/";

	/** A parameter of the program's schema (kit/lib/params.mjs): {@code int} (min..max), {@code bool} or {@code enum} (options). */
	public record Param(String type, @Nullable Integer min, @Nullable Integer max, List<String> options, JsonElement def) {
		public Param {
			options = options == null ? List.of() : List.copyOf(options);
		}

		/** Whether a value is in this parameter's domain. */
		public boolean admits(JsonElement v) {
			if (v == null || !v.isJsonPrimitive()) return false;
			var p = v.getAsJsonPrimitive();
			return switch (type) {
				case "int" -> p.isNumber() && isWhole(p.getAsDouble()) && (min == null || p.getAsInt() >= min) && (max == null || p.getAsInt() <= max);
				case "bool" -> p.isBoolean();
				case "enum" -> p.isString() && options.contains(p.getAsString());
				default -> false;
			};
		}

		private static boolean isWhole(double d) {
			return d == Math.rint(d) && Math.abs(d) < Integer.MAX_VALUE;
		}
	}

	/** The lamp rule: the {@code role} must give block light {@code minLight} or more, else {@code fallback} is used and said. */
	public record Lamp(String role, int minLight, String fallback) {}

	/** Palette constraints for the roles that touch the core (section 2.2). */
	public record PaletteRules(@Nullable Lamp lamp, List<String> noGravity, List<String> nonConductive) {
		public PaletteRules {
			noGravity = noGravity == null ? List.of() : List.copyOf(noGravity);
			nonConductive = nonConductive == null ? List.of() : List.copyOf(nonConductive);
		}
	}

	/**
	 * The certification envelope (section 2.2): every discrete branch ({@code branches}: param -> its measured values; the cartesian
	 * product is the branch set), the facings and the bibles it was checked with.
	 */
	public record Envelope(Map<String, List<JsonElement>> branches, List<String> facings, List<String> bibles) {
		public Envelope {
			branches = branches == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(branches));
			facings = facings == null ? List.of() : List.copyOf(facings);
			bibles = bibles == null ? List.of() : List.copyOf(bibles);
		}

		/** Every branch: one param -> value map per combination, in declaration order. */
		public List<Map<String, JsonElement>> combinations() {
			List<Map<String, JsonElement>> out = new ArrayList<>();
			out.add(new LinkedHashMap<>());
			for (var e : branches.entrySet()) {
				List<Map<String, JsonElement>> next = new ArrayList<>();
				for (var partial : out) for (JsonElement v : e.getValue()) {
					var m = new LinkedHashMap<>(partial);
					m.put(e.getKey(), v);
					next.add(m);
				}
				out = next;
			}
			return out;
		}
	}

	/** What was measured (measured.json): the versions it holds for, and its bars and results as recorded. */
	public record Measured(String programSha, String minecraft, List<String> architect, @Nullable String kit, @Nullable String checker, boolean fixture,
		JsonObject raw) {
		public Measured {
			architect = architect == null ? List.of() : List.copyOf(architect);
		}
	}

	/** One catalog version. {@code problems} non-empty means it is never offered. */
	public record Entry(String id, int version, String title, boolean fixture, String source, String programSha, Map<String, Param> params,
		Envelope envelope, PaletteRules palette, @Nullable String kitPin, @Nullable String checkerPin, @Nullable Measured measured, List<String> problems) {
		public Entry {
			params = params == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params));
			problems = problems == null ? List.of() : List.copyOf(problems);
		}

		/** {@code <id>@<version>}. */
		public String key() {
			return id + "@" + version;
		}

		public boolean valid() {
			return problems.isEmpty();
		}
	}

	private final List<Entry> entries;
	private final List<String> problems;

	private ModuleCatalog(List<Entry> entries, List<String> problems) {
		this.entries = List.copyOf(entries);
		this.problems = List.copyOf(problems);
	}

	public List<Entry> entries() {
		return entries;
	}

	/** Problems with the catalog itself (its index), not with one entry. */
	public List<String> problems() {
		return problems;
	}

	public Optional<Entry> get(String id, int version) {
		return entries.stream().filter(e -> e.id().equals(id) && e.version() == version).findFirst();
	}

	/** The newest version of a module, whatever its state: gating decides whether it is offered (never an older one in its place). */
	public Optional<Entry> newest(String id) {
		return entries.stream().filter(e -> e.id().equals(id)).max(Comparator.comparingInt(Entry::version));
	}

	/** An empty catalog. */
	public static ModuleCatalog empty() {
		return new ModuleCatalog(List.of(), List.of());
	}

	/** The live catalog, from the jar. Fixture entries are refused here (they are test data). */
	public static ModuleCatalog live(ClassLoader cl) {
		return load(rel -> {
			try (InputStream in = cl.getResourceAsStream(RESOURCE_ROOT + rel)) {
				return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			} catch (IOException e) {
				return Optional.empty();
			}
		}, false);
	}

	/** A catalog in a directory (tests and the dev hook); {@code fixtures} allows fixture entries. */
	public static ModuleCatalog dir(Path root, boolean fixtures) {
		return load(rel -> {
			Path f = root.resolve(rel).normalize();
			if (!f.startsWith(root.normalize()) || !Files.isRegularFile(f)) return Optional.empty();
			try {
				return Optional.of(Files.readString(f, StandardCharsets.UTF_8));
			} catch (IOException e) {
				return Optional.empty();
			}
		}, fixtures);
	}

	/** Loads a catalog through {@code read} (a path relative to the root, to its text). */
	public static ModuleCatalog load(Function<String, Optional<String>> read, boolean fixtures) {
		List<String> problems = new ArrayList<>();
		List<Entry> entries = new ArrayList<>();
		Optional<String> index = read.apply("catalog.json");
		if (index.isEmpty()) return new ModuleCatalog(List.of(), List.of("no catalog.json"));
		JsonObject idx;
		try {
			idx = JsonParser.parseString(index.get()).getAsJsonObject();
		} catch (RuntimeException e) {
			return new ModuleCatalog(List.of(), List.of("catalog.json is not a JSON object: " + e.getMessage()));
		}
		int format = intOr(idx, "format", -1);
		if (format != FORMAT) return new ModuleCatalog(List.of(), List.of("catalog.json format " + format + " (this build reads " + FORMAT + ")"));
		JsonArray mods = idx.has("modules") && idx.get("modules").isJsonArray() ? idx.getAsJsonArray("modules") : new JsonArray();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (JsonElement m : mods) {
			if (!m.isJsonObject()) { problems.add("a catalog row is not an object"); continue; }
			String id = strOr(m.getAsJsonObject(), "id", null);
			int version = intOr(m.getAsJsonObject(), "version", -1);
			if (id == null || !id.matches(ID_RE) || version < 1) { problems.add("a catalog row has a bad id or version: " + m); continue; }
			if (!seen.add(id + "@" + version)) { problems.add("duplicate catalog row " + id + "@" + version); continue; }
			Entry e = entry(id, version, read);
			if (e.fixture() && !fixtures) { problems.add(e.key() + " is a fixture module: not part of the live catalog"); continue; }
			entries.add(e);
		}
		return new ModuleCatalog(entries, problems);
	}

	private static Entry entry(String id, int version, Function<String, Optional<String>> read) {
		String dir = id + "/" + version + "/";
		List<String> problems = new ArrayList<>();
		String source = read.apply(dir + "program.mjs").orElse(null);
		JsonObject mod = json(read.apply(dir + "module.json"), "module.json", problems);
		JsonObject meas = json(read.apply(dir + "measured.json"), "measured.json", problems);
		if (source == null) problems.add("no program.mjs");
		else {
			if (source.isEmpty()) problems.add("program.mjs is empty");
			if (source.length() > PROGRAM_SOURCE_MAX) problems.add("program.mjs is " + source.length() + " characters (at most " + PROGRAM_SOURCE_MAX + ")");
		}
		String sha = source == null ? "" : Canon.sha256(source);
		String title = id;
		boolean fixture = false;
		String declaredSha = null, kitPin = null, checkerPin = null;
		Map<String, Param> params = new LinkedHashMap<>();
		Envelope env = new Envelope(Map.of(), List.of(), List.of());
		PaletteRules palette = new PaletteRules(null, List.of(), List.of());
		if (mod != null) try {
			if (!id.equals(strOr(mod, "id", null)) || version != intOr(mod, "version", -1)) problems.add("module.json names another module than " + id + "@" + version);
			title = strOr(mod, "title", id);
			fixture = mod.has("fixture") && mod.get("fixture").isJsonPrimitive() && mod.get("fixture").getAsBoolean();
			declaredSha = strOr(mod, "programSha", null);
			if (declaredSha == null) problems.add("module.json has no programSha");
			else if (source != null && !declaredSha.equals(sha)) problems.add("program.mjs is not the program module.json pins (sha " + shortSha(sha) + ", pinned " + shortSha(declaredSha) + ")");
			params = params(mod, problems);
			env = envelope(mod, params, problems);
			palette = palette(mod, problems);
			JsonObject pins = mod.has("pins") && mod.get("pins").isJsonObject() ? mod.getAsJsonObject("pins") : new JsonObject();
			kitPin = strOr(pins, "kit", null);
			checkerPin = strOr(pins, "checker", null);
		} catch (RuntimeException ex) {
			problems.add("module.json is malformed: " + ex.getMessage());
		}
		Measured measured = null;
		if (meas != null) {
			measured = new Measured(strOr(meas, "programSha", ""), strOr(meas, "minecraft", ""), strings(meas, "architect"), strOr(meas, "kit", null),
				strOr(meas, "checker", null), meas.has("fixture") && meas.get("fixture").isJsonPrimitive() && meas.get("fixture").getAsBoolean(), meas);
			if (!id.equals(strOr(meas, "id", null)) || version != intOr(meas, "version", -1)) problems.add("measured.json names another module than " + id + "@" + version);
			if (!measured.programSha().equals(sha)) problems.add("measured.json measured another program (sha " + shortSha(measured.programSha()) + ")");
			if (measured.minecraft().isEmpty()) problems.add("measured.json names no Minecraft version");
			if (measured.architect().isEmpty()) problems.add("measured.json names no Architect version");
			if (measured.fixture() != fixture) problems.add("measured.json and module.json disagree on whether it is a fixture");
			if (kitPin != null && measured.kit() != null && !kitPin.equals(measured.kit())) problems.add("measured on kit " + measured.kit() + ", pinned " + kitPin);
		}
		return new Entry(id, version, title, fixture, source == null ? "" : source, sha, params, env, palette, kitPin, checkerPin, measured, problems);
	}

	private static Map<String, Param> params(JsonObject mod, List<String> problems) {
		Map<String, Param> out = new LinkedHashMap<>();
		if (!mod.has("params") || !mod.get("params").isJsonObject()) {
			problems.add("module.json has no params schema");
			return out;
		}
		for (var e : mod.getAsJsonObject("params").entrySet()) {
			if (!e.getKey().matches("[a-zA-Z][a-zA-Z0-9_]{0,31}") || !e.getValue().isJsonObject()) { problems.add("bad param " + e.getKey()); continue; }
			JsonObject p = e.getValue().getAsJsonObject();
			String type = strOr(p, "type", "");
			Integer min = p.has("min") ? p.get("min").getAsInt() : null, max = p.has("max") ? p.get("max").getAsInt() : null;
			Param param = new Param(type, min, max, strings(p, "options"), p.has("default") ? p.get("default") : null);
			if (!List.of("int", "bool", "enum").contains(type)) problems.add("param " + e.getKey() + " has type '" + type + "' (int, bool or enum)");
			else if (type.equals("enum") && param.options().isEmpty()) problems.add("enum param " + e.getKey() + " has no options");
			else if (param.def() == null || !param.admits(param.def())) problems.add("param " + e.getKey() + "'s default is outside its domain");
			out.put(e.getKey(), param);
		}
		return out;
	}

	private static Envelope envelope(JsonObject mod, Map<String, Param> params, List<String> problems) {
		if (!mod.has("envelope") || !mod.get("envelope").isJsonObject()) {
			problems.add("module.json has no envelope");
			return new Envelope(Map.of(), List.of(), List.of());
		}
		JsonObject env = mod.getAsJsonObject("envelope");
		Map<String, List<JsonElement>> branches = new LinkedHashMap<>();
		JsonObject b = env.has("branches") && env.get("branches").isJsonObject() ? env.getAsJsonObject("branches") : new JsonObject();
		for (var e : b.entrySet()) {
			Param p = params.get(e.getKey());
			if (p == null) { problems.add("envelope branch " + e.getKey() + " is not a param"); continue; }
			if (!e.getValue().isJsonArray() || e.getValue().getAsJsonArray().isEmpty()) { problems.add("envelope branch " + e.getKey() + " has no values"); continue; }
			List<JsonElement> vs = new ArrayList<>();
			for (JsonElement v : e.getValue().getAsJsonArray()) {
				if (!p.admits(v)) problems.add("envelope branch " + e.getKey() + " value " + v + " is outside its param's domain");
				else if (vs.contains(v)) problems.add("envelope branch " + e.getKey() + " repeats " + v);
				else vs.add(v);
			}
			branches.put(e.getKey(), vs);
		}
		// every param is either a branch (its measured values) or fixed at its default: nothing unmeasured can be asked for
		List<String> facings = strings(env, "facings");
		List<String> bibles = strings(env, "bibles");
		if (bibles.isEmpty()) problems.add("the envelope names no bibles");
		return new Envelope(branches, facings, bibles);
	}

	private static PaletteRules palette(JsonObject mod, List<String> problems) {
		JsonObject p = mod.has("palette") && mod.get("palette").isJsonObject() ? mod.getAsJsonObject("palette") : null;
		if (p == null) {
			problems.add("module.json has no palette constraints");
			return new PaletteRules(null, List.of(), List.of());
		}
		Lamp lamp = null;
		if (p.has("lamp") && p.get("lamp").isJsonObject()) {
			JsonObject l = p.getAsJsonObject("lamp");
			lamp = new Lamp(strOr(l, "role", "light"), intOr(l, "minLight", 14), strOr(l, "fallback", "minecraft:lantern"));
			if (lamp.minLight() < 0 || lamp.minLight() > 15) problems.add("lamp minLight " + lamp.minLight() + " is not a light level");
		}
		return new PaletteRules(lamp, strings(p, "noGravity"), strings(p, "nonConductive"));
	}

	private static @Nullable JsonObject json(Optional<String> text, String name, List<String> problems) {
		if (text.isEmpty()) {
			problems.add("no " + name);
			return null;
		}
		try {
			return JsonParser.parseString(text.get()).getAsJsonObject();
		} catch (RuntimeException e) {
			problems.add(name + " is not a JSON object");
			return null;
		}
	}

	static String shortSha(@Nullable String sha) {
		return sha == null ? "none" : sha.substring(0, Math.min(12, sha.length()));
	}

	private static List<String> strings(JsonObject o, String k) {
		List<String> out = new ArrayList<>();
		if (o.has(k) && o.get(k).isJsonArray()) for (JsonElement e : o.getAsJsonArray(k)) if (e.isJsonPrimitive()) out.add(e.getAsString());
		return out;
	}

	private static @Nullable String strOr(JsonObject o, String k, @Nullable String d) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d;
	}

	private static int intOr(JsonObject o, String k, int d) {
		try {
			return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : d;
		} catch (NumberFormatException e) {
			return d;
		}
	}
}
