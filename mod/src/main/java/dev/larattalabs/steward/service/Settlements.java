package dev.larattalabs.steward.service;

import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.SettlementStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/** The settlements of the running world: loaded at server start from {@code <world>/steward-settlements.json}, saved on every change (atomic). */
public final class Settlements {
	public static final int DEFAULT_RADIUS = 64;
	private static SettlementStore store = new SettlementStore();
	private static Path file;

	private Settlements() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(Settlements::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> { store = new SettlementStore(); file = null; });
	}

	static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("steward-settlements.json");
		try {
			store = SettlementStore.load(file);
			Steward.LOGGER.info("loaded {} settlement(s) from {}", store.all().size(), file);
		} catch (IOException | RuntimeException e) {
			// a corrupt file is never silently emptied: keep working in memory, do not overwrite it
			Steward.LOGGER.error("could not read {}: {}. Settlements are disabled for this session (the file is left untouched).", file, e.toString());
			store = new SettlementStore();
			file = null;
		}
	}

	public static SettlementStore store() {
		return store;
	}

	public static Optional<Settlement> at(Level level, BlockPos p) {
		return store.at(level.dimension().identifier().toString(), p.getX(), p.getY(), p.getZ());
	}

	/** Claims a square around {@code center}; fails (empty with a reason) when it overlaps another claim or saving is disabled. */
	public static Result found(Level level, BlockPos center, String name, long now) {
		if (file == null) return Result.fail("Settlements are disabled in this world (its settlements file could not be read).");
		String dim = level.dimension().identifier().toString();
		Claim claim = new Claim(dim, center.getX(), center.getZ(), DEFAULT_RADIUS, level.getMinY(), level.getMaxY());
		Settlement s = Settlement.founded(store.nextId(), name, claim, Permission.PROPOSALS, Difficulty.PATRON, now);
		try {
			store.put(s);
		} catch (IllegalArgumentException e) {
			return Result.fail("This overlaps another settlement's claim (" + e.getMessage() + ").");
		}
		return save() ? Result.ok(s) : Result.fail("Could not save the settlement.");
	}

	public static Result describe(String id, ConceptCard card, long now) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		Settlement n = s.get().withCard(card, now);
		store.put(n);
		return save() ? Result.ok(n) : Result.fail("Could not save the settlement.");
	}

	private static boolean save() {
		if (file == null) return false;
		try {
			store.save(file);
			return true;
		} catch (IOException e) {
			Steward.LOGGER.error("could not save settlements: {}", e.toString());
			return false;
		}
	}

	public record Result(Settlement settlement, String error) {
		static Result ok(Settlement s) { return new Result(s, null); }
		static Result fail(String e) { return new Result(null, e); }
		public boolean ok() { return settlement != null; }
	}
}
