package dev.larattalabs.steward.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.larattalabs.steward.Steward;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Each settlement's composed plan (docs/COMPOSITION.md, plan v0), in its own world file ({@code steward-plans.json}): the plan Claude wrote, Architect's
 * answer to it, Claude's summary, and the parts that wait for a building block (Noah: kept pending, offered when the block ships). A plan is made again
 * whenever the settlement is described, so the file is a cache with no migrations; an unreadable one is set aside. Server thread.
 */
public final class PlanStore {
	/** A part that waits: its id, the building block it needs, whether the plan requires it, and Claude's words for the player. */
	public record Pending(String part, String block, boolean required, String words) {}

	/**
	 * A settlement's plan: the plan as JSON, the check's overall answer and artifact, the summary, what waits, what it cost, when; {@code crossings}: its
	 * bridges and tunnels as Architect resolved them, a line each (empty in a plan kept before they were).
	 */
	public record Entry(String settlementId, String plan, String overall, @Nullable String artifact, String summary, List<Pending> pending, double usd, long at,
		int rounds, List<String> crossings) {
		public Entry {
			crossings = crossings == null ? List.of() : List.copyOf(crossings);
		}

		public Entry(String settlementId, String plan, String overall, @Nullable String artifact, String summary, List<Pending> pending, double usd, long at,
			int rounds) {
			this(settlementId, plan, overall, artifact, summary, pending, usd, at, rounds, List.of());
		}
	}

	private record FileShape(int format, Map<String, Entry> plans) {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Map<String, Entry> plans = new LinkedHashMap<>();
	private static @Nullable Path file;

	private PlanStore() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			file = server.getWorldPath(LevelResource.ROOT).resolve("steward-plans.json");
			plans = new LinkedHashMap<>();
			if (!Files.exists(file)) return;
			try {
				FileShape f = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<FileShape>() {}.getType());
				if (f != null && f.plans() != null) plans.putAll(f.plans());
			} catch (IOException | RuntimeException e) {
				// a cache: set aside, and plans are made again when settlements are described
				Steward.LOGGER.warn("could not read {} ({}); it is set aside", file, e.toString());
				try {
					Files.move(file, file.resolveSibling(file.getFileName() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ignored) {
					// left as it is
				}
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			plans = new LinkedHashMap<>();
			file = null;
		});
	}

	public static Optional<Entry> get(String settlementId) {
		return Optional.ofNullable(plans.get(settlementId));
	}

	public static List<Entry> all() {
		return List.copyOf(plans.values());
	}

	public static void put(Entry e) {
		plans.put(e.settlementId(), e);
		save();
	}

	/** (CA) The settlement's kept plan dropped (a new card is being composed: the old card's plan must not build it). */
	public static void remove(String settlementId) {
		if (plans.remove(settlementId) != null) save();
	}

	private static void save() {
		if (file == null) return;
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(new FileShape(1, plans)), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Steward.LOGGER.warn("could not save {}: {}", file, e.toString());
		}
	}

	/**
	 * Dev (the e2e check): {@code partJson} kept as waiting for {@code block}, as a plan kept before that block shipped: added to the settlement's kept plan
	 * (its connectors, forms or rules by its kind; else its zones) and its pending list. Returns what was done.
	 */
	public static String pend(String settlementId, String block, String partJson) {
		var e = plans.get(settlementId);
		if (e == null) return "No plan kept for " + settlementId + ".";
		JsonObject part = com.google.gson.JsonParser.parseString(partJson).getAsJsonObject();
		JsonObject plan = com.google.gson.JsonParser.parseString(e.plan()).getAsJsonObject();
		String list = block.startsWith("connector.") ? "connectors" : block.startsWith("form.") ? "forms" : block.startsWith("rule.") ? "rules" : "zones";
		if (!plan.has(list)) plan.add(list, new com.google.gson.JsonArray());
		plan.getAsJsonArray(list).add(part);
		String id = part.get("id").getAsString();
		List<Pending> pending = new java.util.ArrayList<>(e.pending());
		pending.add(new Pending(id, block, part.has("required") && part.get("required").getAsBoolean(), id.replace('_', ' ') + " waits for Architect"));
		put(new Entry(e.settlementId(), plan.toString(), e.overall(), e.artifact(), e.summary(), List.copyOf(pending), e.usd(), e.at(), e.rounds()));
		return "pending " + id + " on " + block + " for " + settlementId;
	}

	/**
	 * Dev (the e2e check, slice PV1): {@code lotJson} added to zone {@code zone}'s lots in the settlement's kept plan, as a plan whose lot was left out
	 * of the first build (it didn't fit). The entry's time is kept, so the plan stays the one the town was built from. Returns what was done.
	 */
	public static String addLot(String settlementId, String zone, String lotJson) {
		var e = plans.get(settlementId);
		if (e == null) return "No plan kept for " + settlementId + ".";
		JsonObject lot = com.google.gson.JsonParser.parseString(lotJson).getAsJsonObject();
		JsonObject plan = com.google.gson.JsonParser.parseString(e.plan()).getAsJsonObject();
		if (!plan.has("zones") || !plan.get("zones").isJsonArray()) return "The plan has no zones.";
		for (var z : plan.getAsJsonArray("zones")) {
			if (!z.isJsonObject() || !z.getAsJsonObject().has("id") || !zone.equals(z.getAsJsonObject().get("id").getAsString())) continue;
			JsonObject program = z.getAsJsonObject().getAsJsonObject("program");
			com.google.gson.JsonArray lots = program.has("lots") && program.get("lots").isJsonArray() ? program.getAsJsonArray("lots")
				: program.has("params") && program.getAsJsonObject("params").has("lots") ? program.getAsJsonObject("params").getAsJsonArray("lots") : null;
			if (lots == null) return "Zone " + zone + " has no lot list.";
			lots.add(lot);
			put(new Entry(e.settlementId(), plan.toString(), e.overall(), e.artifact(), e.summary(), e.pending(), e.usd(), e.at(), e.rounds(), e.crossings()));
			return "plan lot " + lot.get("id").getAsString() + " added to " + zone + " for " + settlementId;
		}
		return "No zone " + zone + " in the plan.";
	}

	/**
	 * Dev (the e2e check, slice PD): {@code districtJson} added as a D1 district of zone {@code zone}'s districts program in the settlement's kept plan
	 * (the zone made, with that program, when the plan has none of that id), as a plan whose district the founding didn't build. The entry's time is kept.
	 */
	public static String addDistrict(String settlementId, String zone, String districtJson) {
		var e = plans.get(settlementId);
		if (e == null) return "No plan kept for " + settlementId + ".";
		JsonObject d = com.google.gson.JsonParser.parseString(districtJson).getAsJsonObject();
		JsonObject plan = com.google.gson.JsonParser.parseString(e.plan()).getAsJsonObject();
		if (!plan.has("zones") || !plan.get("zones").isJsonArray()) return "The plan has no zones.";
		JsonObject program = null;
		for (var z : plan.getAsJsonArray("zones")) {
			if (z.isJsonObject() && z.getAsJsonObject().has("id") && zone.equals(z.getAsJsonObject().get("id").getAsString())) program = z.getAsJsonObject().getAsJsonObject("program");
		}
		if (program == null) {
			JsonObject z = new JsonObject();
			z.addProperty("id", zone);
			z.addProperty("kind", "standard");
			z.addProperty("required", false);
			program = new JsonObject();
			program.add("districts", new com.google.gson.JsonArray());
			z.add("program", program);
			plan.getAsJsonArray("zones").add(z);
		}
		if (!program.has("districts") || !program.get("districts").isJsonArray()) return "Zone " + zone + " is not a districts program.";
		program.getAsJsonArray("districts").add(d);
		put(new Entry(e.settlementId(), plan.toString(), e.overall(), e.artifact(), e.summary(), e.pending(), e.usd(), e.at(), e.rounds(), e.crossings()));
		return "plan district " + d.get("id").getAsString() + " added to " + zone + " for " + settlementId;
	}

	/** The plan as JSON, for the screens (unused fields left out). */
	static JsonObject json(Entry e) {
		return GSON.toJsonTree(e).getAsJsonObject();
	}
}
