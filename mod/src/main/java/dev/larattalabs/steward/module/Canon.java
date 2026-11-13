package dev.larattalabs.steward.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Canonical JSON and hashes for the module catalog (docs/MODULES.md 4.2): a program's sha is sha256 of its exact UTF-8 bytes, as Architect
 * records it ({@code givenProgram.sha}); params and geometry are compared and hashed in canonical form (object keys sorted, recursively),
 * because Gson keeps insertion order. Pure.
 */
public final class Canon {
	private Canon() {}

	/** sha256 of the exact UTF-8 bytes, lower-case hex. */
	public static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** A deep copy with every object's keys sorted. */
	public static JsonElement sorted(@Nullable JsonElement e) {
		if (e == null || e.isJsonNull()) return JsonNull.INSTANCE;
		if (e.isJsonObject()) {
			Map<String, JsonElement> m = new TreeMap<>();
			for (var en : e.getAsJsonObject().entrySet()) m.put(en.getKey(), sorted(en.getValue()));
			JsonObject o = new JsonObject();
			m.forEach(o::add);
			return o;
		}
		if (e.isJsonArray()) {
			JsonArray a = new JsonArray();
			for (JsonElement x : e.getAsJsonArray()) a.add(sorted(x));
			return a;
		}
		return e.deepCopy();
	}

	/** The canonical text (sorted keys, no whitespace). */
	public static String text(@Nullable JsonElement e) {
		return sorted(e).toString();
	}

	/** sha256 of the canonical text: e.g. a lot's {@code geometrySha} over its box, frame and irrigation cells. */
	public static String sha(@Nullable JsonElement e) {
		return sha256(text(e));
	}
}
