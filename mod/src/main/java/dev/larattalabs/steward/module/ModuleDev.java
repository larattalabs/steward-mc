package dev.larattalabs.steward.module;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.BiblePin;
import dev.larattalabs.architect.api.DesignRequest;
import dev.larattalabs.steward.Steward;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * The module catalog's dev hook ({@code /steward dev module <label> <request JSON>}, game masters in a development environment only;
 * tools/module-catalog.mjs): it drives
 * Steward's own catalog, gate and installs on a catalog directory (fixtures allowed), and writes the answer to
 * {@code <gameDir>/steward-modules/<label>.json} (and logs {@code module dev <label> ...}). Requests ({@code op}):
 * <ul>
 * <li>{@code env}: the gating environment (Minecraft and Architect versions, features);</li>
 * <li>{@code gate {dir, id, version?}}: the catalog's verdict (the newest version when none is given) and the entry's problems;</li>
 * <li>{@code install {dir, id, version}}: the catalog entry by its key ({@link ModuleInstalls#install});</li>
 * <li>{@code variant {dir, id, version, params?, bible? {id, version}, reserved? {front, size [w, d], cells}, key, owner?}}: made from the
 * entry's own catalog install;</li>
 * <li>{@code measuring: true} on gate, install or variant: a measurement run (an uncertified entry may be installed).</li>
 * <li>{@code byKey {key, owner?}}.</li>
 * </ul>
 */
public final class ModuleDev {
	private ModuleDev() {}

	/** Whether this game may run the hook at all: a development environment only (the dev client), never a released game. */
	public static boolean allowed() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	/** The gating environment of this game (fixtures and measuring only in a development environment). */
	public static ModuleGate.Env env(boolean fixtures, boolean measuring) {
		boolean dev = allowed();
		return new ModuleGate.Env(modVersion("minecraft"), modVersion("architect_mc"), ArchitectApi.get().features(), fixtures && dev, measuring && dev);
	}

	private static String modVersion(String mod) {
		return FabricLoader.getInstance().getModContainer(mod).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("none");
	}

	/** Runs one request; completes (on any thread) with its answer, already written. */
	public static CompletableFuture<JsonObject> run(MinecraftServer server, String label, JsonObject req) {
		CompletableFuture<JsonObject> f;
		try {
			f = allowed() ? op(req) : CompletableFuture.completedFuture(error("the module hook runs only in a development environment"));
		} catch (RuntimeException e) {
			f = CompletableFuture.completedFuture(error(e.toString()));
		}
		return f.exceptionally(e -> error(String.valueOf(e))).thenApply(o -> {
			o.addProperty("label", label);
			o.add("request", req);
			Path out = server.getServerDirectory().resolve("steward-modules").resolve(label + ".json");
			try {
				Files.createDirectories(out.getParent());
				Files.writeString(out, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(o));
			} catch (IOException e) {
				Steward.LOGGER.warn("module dev {}: {}", label, e.toString());
			}
			Steward.LOGGER.info("module dev {} {}", label, o.has("error") ? "error " + o.get("error").getAsString() : o.has("entryId") ? o.get("entryId") : "done");
			return o;
		});
	}

	private static CompletableFuture<JsonObject> op(JsonObject req) {
		String op = req.get("op").getAsString();
		switch (op) {
			case "env" -> {
				return CompletableFuture.completedFuture(envJson(env(true, false)));
			}
			case "byKey" -> {
				String owner = str(req, "owner", ModuleInstalls.CATALOG_OWNER);
				return ModuleInstalls.byKey(req.get("key").getAsString(), owner).thenApply(o -> {
					JsonObject a = new JsonObject();
					a.addProperty("found", o.isPresent());
					if (o.isPresent()) a.addProperty("entryId", o.get());
					return a;
				});
			}
			default -> {
			}
		}
		ModuleCatalog cat = ModuleCatalog.dir(Path.of(req.get("dir").getAsString()), true);
		String id = req.get("id").getAsString();
		// measuring: a dev measurement run installs an entry whose measurement is missing or stale (the run that measures it)
		var env = env(true, req.has("measuring") && req.get("measuring").getAsBoolean());
		if (op.equals("gate")) {
			var v = req.has("version") ? cat.get(id, req.get("version").getAsInt()).map(e -> ModuleGate.offer(e, env)).orElse(new ModuleGate.Verdict(null,
				ModuleGate.Why.UNKNOWN, "no such version")) : ModuleGate.offer(cat, id, env);
			JsonObject a = envJson(env);
			a.addProperty("ok", v.ok());
			a.addProperty("why", v.why() == null ? null : v.why().name());
			a.addProperty("message", v.message());
			if (v.entry() != null) {
				a.addProperty("entry", v.entry().key());
				a.addProperty("programSha", v.entry().programSha());
				a.add("problems", strings(v.entry().problems()));
				a.add("uncertified", strings(v.entry().uncertified()));
				a.addProperty("moduleSha", v.entry().moduleSha());
				a.addProperty("key", ModuleInstalls.catalogKey(v.entry()));
				JsonArray combos = new JsonArray();
				for (var c : v.entry().envelope().combinations()) {
					JsonObject o = new JsonObject();
					c.forEach(o::add);
					combos.add(o);
				}
				a.add("branches", combos);
			}
			a.add("catalogProblems", strings(cat.problems()));
			return CompletableFuture.completedFuture(a);
		}
		var e = cat.get(id, req.get("version").getAsInt()).orElseThrow(() -> new IllegalArgumentException("no " + id + "@" + req.get("version") + " in " + req.get("dir")));
		CompletableFuture<ModuleInstalls.Outcome> out = switch (op) {
			case "install" -> ModuleInstalls.install(e, env);
			case "variant" -> {
				BiblePin bible = null;
				if (req.has("bible") && req.get("bible").isJsonObject()) {
					JsonObject b = req.getAsJsonObject("bible");
					bible = new BiblePin(b.get("id").getAsString(), b.has("version") ? b.get("version").getAsInt() : 0);
				}
				yield ModuleInstalls.variant(e, env, req.has("params") ? req.getAsJsonObject("params") : null, bible,
					reserved(req), req.get("key").getAsString(), str(req, "owner", ModuleInstalls.CATALOG_OWNER), McBlockFacts.INSTANCE);
			}
			default -> throw new IllegalArgumentException("unknown op " + op);
		};
		String key = op.equals("install") ? ModuleInstalls.catalogKey(e) : req.get("key").getAsString();
		return out.thenApply(o -> {
			JsonObject a = new JsonObject();
			a.addProperty("entry", e.key());
			a.addProperty("programSha", e.programSha());
			a.addProperty("key", key);
			a.addProperty("ok", o.ok());
			a.addProperty("entryId", o.entryId());
			a.addProperty("detail", o.detail());
			a.addProperty("message", o.message());
			a.add("findings", strings(o.findings()));
			a.addProperty("baseEntryId", o.baseEntryId());
			if (o.bible() != null) {
				JsonObject b = new JsonObject();
				b.addProperty("id", o.bible().id());
				b.addProperty("version", o.bible().version());
				a.add("bible", b);
			}
			a.addProperty("lamp", o.lamp());
			a.addProperty("lampFallback", o.lampFallback());
			return a;
		});
	}

	private static DesignRequest.Reserved reserved(JsonObject req) {
		if (!req.has("reserved") || !req.get("reserved").isJsonObject()) return null;
		JsonObject r = req.getAsJsonObject("reserved");
		List<BlockPos> cells = new ArrayList<>();
		for (JsonElement c : r.getAsJsonArray("cells")) {
			var a = c.getAsJsonArray();
			cells.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
		}
		var size = r.getAsJsonArray("size");
		return new DesignRequest.Reserved(r.get("front").getAsString(), size.get(0).getAsInt(), size.get(1).getAsInt(), cells);
	}

	private static JsonObject envJson(ModuleGate.Env env) {
		JsonObject a = new JsonObject();
		a.addProperty("minecraft", env.minecraft());
		a.addProperty("architect", env.architect());
		a.addProperty("givenPrograms", env.features().contains(ModuleGate.FEATURE));
		a.addProperty("measuring", env.measuring());
		return a;
	}

	private static JsonObject error(String m) {
		JsonObject a = new JsonObject();
		a.addProperty("ok", false);
		a.addProperty("error", m);
		return a;
	}

	private static JsonArray strings(List<String> l) {
		JsonArray a = new JsonArray();
		l.forEach(a::add);
		return a;
	}

	private static String str(JsonObject o, String k, String d) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d;
	}
}
