package dev.larattalabs.steward.service;

import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.ClaimRules;
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
		// at STARTING, like the builds: a restored build may log to its settlement during Architect's catch-up
		ServerLifecycleEvents.SERVER_STARTING.register(Settlements::load);
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
			return commit(Optional.empty(), s, "");
		} catch (IllegalArgumentException e) {
			return Result.fail("This overlaps another settlement's claim (" + e.getMessage() + ").");
		}
	}

	/** Stores the card and sizes the claim from it (it only grows, and only where it overlaps no one); {@link Result#note()} says how the claim came out. */
	public static Result describe(String id, ConceptCard card, long now) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		Settlement n = s.get().withCard(card, now);
		ClaimRules.Outcome o = ClaimRules.growTo(n.claim(), ClaimRules.radiusFor(card.site().size()), others(id));
		if (o.changed(n.claim())) n = n.withClaim(ClaimRules.withRadius(n.claim(), o.radius()), now);
		String note = o.note().isEmpty() ? "Claim: " + ClaimRules.side(n.claim().radius()) + " x " + ClaimRules.side(n.claim().radius()) + " (" + ClaimRules.sizeOf(n.claim().radius())
			+ ")." : o.note();
		return commit(s, n, note);
	}

	/** Grows the claim one size step (Expand); refused, with the reason, past XL or onto a neighbour. */
	public static Result expand(String id, long now) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		ClaimRules.Outcome o = ClaimRules.expand(s.get().claim(), others(id));
		if (!o.changed(s.get().claim())) return Result.fail(o.note());
		Settlement n = s.get().withClaim(ClaimRules.withRadius(s.get().claim(), o.radius()), now);
		return commit(s, n, "The claim is now " + ClaimRules.side(o.radius()) + " x " + ClaimRules.side(o.radius()) + " (" + ClaimRules.sizeOf(o.radius())
			+ "). The next build surveys the new land.");
	}

	/** Puts {@code n} in the store and saves; when saving fails the store goes back to {@code before}, so memory never holds what the file does not. */
	private static Result commit(Optional<Settlement> before, Settlement n, String note) {
		if (file == null) return Result.fail("Settlements are disabled in this world (its settlements file could not be read).");
		store.put(n);
		if (save()) return new Result(n, null, note);
		if (before.isPresent()) store.put(before.get());
		else store.remove(n.id());
		return Result.fail("Could not save the settlement.");
	}

	private static java.util.List<Claim> others(String id) {
		return store.all().stream().filter(x -> !x.id().equals(id)).map(Settlement::claim).toList();
	}

	/** Keeps the settlement's style bible (from its first build) for later builds. */
	public static Result bible(String id, Settlement.BibleRef b) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		return commit(s, s.get().withBible(b), "");
	}

	/** Replaces a settlement's proposals (saved, or nothing changes). */
	public static Result proposals(String id, Settlement.Proposals p) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		return commit(s, s.get().withProposals(p), "");
	}

	/** Appends a change-log entry to a saved settlement. */
	public static Result log(String id, Settlement.LogEntry e) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		return commit(s, s.get().withLog(e), "");
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

	public record Result(Settlement settlement, String error, String note) {
		static Result ok(Settlement s) { return new Result(s, null, ""); }
		static Result fail(String e) { return new Result(null, e, ""); }
		public boolean ok() { return settlement != null; }
	}
}
