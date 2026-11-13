package dev.larattalabs.steward.module;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * What a built module is (docs/MODULES.md section 1): {@code steward_mc:module {id, version, programSha, params}} in its lot's ext, so the
 * screens, growth, land check and undo know it. Its identity is {@code <id>@<version>} and the program's sha. Pure.
 */
public record ModuleRef(String id, int version, String programSha, JsonObject params) {
	/** The ext key. */
	public static final String EXT = "steward_mc:module";

	public ModuleRef {
		params = params == null ? new JsonObject() : Canon.sorted(params).getAsJsonObject();
	}

	public String key() {
		return id + "@" + version;
	}

	/** The ext value. */
	public JsonObject json() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("version", version);
		o.addProperty("programSha", programSha);
		o.add("params", params.deepCopy());
		return o;
	}

	/** Adds this module to a lot's ext (a copy; the ext given is not changed). */
	public JsonObject into(@Nullable JsonObject ext) {
		JsonObject o = ext == null ? new JsonObject() : ext.deepCopy();
		o.add(EXT, json());
		return o;
	}

	/** The module a lot's ext names, if any. A malformed value reads as none (and is never acted on as a module). */
	public static @Nullable ModuleRef of(@Nullable JsonObject ext) {
		if (ext == null || !ext.has(EXT) || !ext.get(EXT).isJsonObject()) return null;
		JsonObject o = ext.getAsJsonObject(EXT);
		try {
			String id = o.get("id").getAsString();
			Integer v = exactInt(o.get("version"));
			if (v == null) return null;
			int version = v;
			String sha = o.get("programSha").getAsString();
			if (!id.matches(ModuleCatalog.ID_RE) || version < 1 || !sha.matches("[0-9a-f]{64}") || !o.has("params") || !o.get("params").isJsonObject()) return null;
			return new ModuleRef(id, version, sha, o.getAsJsonObject("params"));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** A JSON number written as a plain integer within int range ({@code 1}, not {@code 1.0}, {@code 1e0} or {@code 4294967297}), else null. */
	public static @Nullable Integer exactInt(com.google.gson.@Nullable JsonElement e) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
		String t = e.getAsJsonPrimitive().getAsNumber().toString();
		if (!t.matches("-?(0|[1-9][0-9]{0,9})")) return null;
		long l = Long.parseLong(t);
		return l < Integer.MIN_VALUE || l > Integer.MAX_VALUE ? null : (int) l;
	}
}
