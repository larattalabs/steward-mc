package dev.larattalabs.steward.gateway;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.larattalabs.steward.Steward;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The dev layouts a host set ({@code /steward dev layout}: the e2e check), kept over a restart in {@code <world>/steward-dev-layouts.json}, so a settlement
 * held to Steward's own layout stays so after its world is opened again. Dev only: no player's world has the file, and it is not part of the settlements
 * format (a missing or unreadable file is no layout set).
 */
public final class DevLayouts {
	private static final String FILE = "steward-dev-layouts.json";
	private static @Nullable Path file;

	private DevLayouts() {
	}

	/** At world start, before any build resumes: the layouts set before the restart. */
	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
		if (!Files.exists(file)) return;
		try {
			var o = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
			if (o == null) return;
			if (o.has("legacy")) for (var e : o.getAsJsonArray("legacy")) VPlan.legacy(e.getAsString(), true);
			if (o.has("villageOnly")) for (var e : o.getAsJsonArray("villageOnly")) PPlan.villageOnly(e.getAsString(), true);
			if (o.has("northSouth")) for (var e : o.getAsJsonArray("northSouth")) VPlan.northSouthStreet(e.getAsString(), true);
			Steward.LOGGER.info("dev layouts kept over the restart: {}", o);
		} catch (Exception e) {
			Steward.LOGGER.warn("could not read {} (no dev layout kept)", file, e);
		}
	}

	/** After a host changed one: written whole (atomically). */
	public static void save() {
		Path f = file;
		if (f == null) return;
		var o = new JsonObject();
		var legacy = new JsonArray();
		VPlan.legacyIds().stream().sorted().forEach(legacy::add);
		var village = new JsonArray();
		PPlan.villageOnlyIds().stream().sorted().forEach(village::add);
		var ns = new JsonArray();
		VPlan.northSouthIds().stream().sorted().forEach(ns::add);
		o.add("legacy", legacy);
		o.add("villageOnly", village);
		o.add("northSouth", ns);
		try {
			Path tmp = f.resolveSibling(FILE + ".tmp");
			Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Steward.LOGGER.warn("could not write {} (the dev layout holds until the world stops)", f, e);
		}
	}

	public static void clear() {
		file = null;
	}
}
