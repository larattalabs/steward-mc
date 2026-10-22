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
import java.util.List;
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

	/** Whether settlements are disabled this session (their file could not be read): nothing that needs them may finish. */
	public static boolean disabled() {
		return file == null;
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
		// in a survival world nothing is built free: a new settlement is Supplied until its card says otherwise
		boolean survival = level.getServer() != null && dev.larattalabs.steward.gateway.WorldMode.survival(level.getServer()).orElse(false);
		Settlement s = Settlement.founded(store.nextId(), name, claim, Permission.PROPOSALS, Difficulty.forWorld(Difficulty.PATRON, survival), now);
		try {
			return commit(Optional.empty(), s, "");
		} catch (IllegalArgumentException e) {
			return Result.fail("This overlaps another settlement's claim (" + e.getMessage() + ").");
		}
	}

	/** Stores the card and sizes the claim from it (it only grows, and only where it overlaps no one); {@link Result#note()} says how the claim came out. */
	public static Result describe(String id, ConceptCard card, long now) {
		return describe(id, card, now, false);
	}

	/** As {@link #describe(String, ConceptCard, long)}, the card's difficulty taken by the world's rule ({@link Difficulty#forWorld}). */
	public static Result describe(String id, ConceptCard card, long now, boolean worldSurvival) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		Difficulty asked = card.difficultyOr(s.get().difficulty());
		// until phase 4 (the stockpile and the steward's requests) is built, a creative world builds free whatever the card asks
		Difficulty d = Difficulty.effective(asked, worldSurvival);
		Settlement n = s.get().withCard(card, now).withDifficulty(d, now);
		String rule = d == asked ? "" : worldSurvival ? " In a survival world nothing is built free, so " + n.name() + " is Supplied: the steward asks for its materials."
			: " Building from your materials (" + asked.name().toLowerCase(java.util.Locale.ROOT) + ") comes with a later version; " + n.name() + " builds free for now.";
		ClaimRules.Outcome o = ClaimRules.growTo(n.claim(), ClaimRules.radiusFor(card.site().size()), others(id));
		if (o.changed(n.claim())) n = n.withClaim(ClaimRules.withRadius(n.claim(), o.radius()), now);
		String note = o.note().isEmpty() ? "Claim: " + ClaimRules.side(n.claim().radius()) + " x " + ClaimRules.side(n.claim().radius()) + " (" + ClaimRules.sizeOf(n.claim().radius())
			+ ")." : o.note();
		return commit(s, n, note + rule);
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

	/** The other settlements' claims and districts. */
	public static java.util.List<Claim> others(String id) {
		return store.all().stream().filter(x -> !x.id().equals(id)).flatMap(x -> x.claims().stream()).toList();
	}

	/** Adds a district ({@code make} gets its new id, {@code dst_<n>}) and logs its founding (its road the operation's site); saved, or nothing changes. */
	public static Result addDistrict(String id, java.util.function.Function<String, Settlement.District> make, String text, long now) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		Settlement.Lands l = s.get().lands().next();
		Settlement.District d = make.apply("dst_" + l.serial());
		Settlement n = s.get().withLands(l.withDistrict(d)).withLog(new Settlement.LogEntry(now, Settlement.Kind.DISTRICT_FOUNDED, text,
			d.roads().stream().map(Settlement.Road::siteId).toList()));
		try {
			return commit(s, n, "");
		} catch (IllegalArgumentException e) {
			return Result.fail("The district overlaps another settlement (" + e.getMessage() + ").");
		}
	}

	/** Replaces a district (a road added or dropped); logged when {@code entry} is given. */
	public static Result updateDistrict(String id, Settlement.District d, Settlement.@org.jspecify.annotations.Nullable LogEntry entry) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		Settlement n = s.get().withLands(s.get().lands().withDistrict(d));
		return commit(s, entry == null ? n : n.withLog(entry), "");
	}

	/** Drops a district (logged). */
	public static Result removeDistrict(String id, String districtId, String text, long now) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		if (s.get().lands().district(districtId).isEmpty()) return Result.fail("No district " + districtId + ".");
		return commit(s, s.get().withLands(s.get().lands().withoutDistrict(districtId)).withLog(new Settlement.LogEntry(now, Settlement.Kind.DISTRICT_REMOVED, text, List.of())), "");
	}

	/** Protects an area ({@code pa_<n>}, logged). */
	public static Result protect(String id, String label, String dim, int x0, int z0, int x1, int z1, long now) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		Settlement.Lands l = s.get().lands().next();
		Settlement.Area a = new Settlement.Area("pa_" + l.serial(), label, dim, x0, z0, x1, z1, now);
		Settlement n = s.get().withLands(l.withArea(a)).withLog(new Settlement.LogEntry(now, Settlement.Kind.PROTECTED_CHANGED, "Protected \"" + a.label() + "\" ("
			+ (a.x1() - a.x0() + 1) + " x " + (a.z1() - a.z0() + 1) + " at " + a.x0() + "," + a.z0() + ")", List.of()));
		return commit(s, n, "Protected \"" + a.label() + "\": the steward will not build there.");
	}

	/** Reshapes a protected area in one commit (logged): its id and label kept, its corners the new ones. */
	public static Result reshapeArea(String id, String areaId, int x0, int z0, int x1, int z1, long now) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		var a = s.get().lands().protect().stream().filter(x -> x.id().equals(areaId)).findFirst();
		if (a.isEmpty()) return Result.fail("No protected area " + areaId + ".");
		Settlement.Area b = new Settlement.Area(a.get().id(), a.get().label(), a.get().dimension(), x0, z0, x1, z1, now);
		Settlement n = s.get().withLands(s.get().lands().withoutArea(areaId).withArea(b)).withLog(new Settlement.LogEntry(now, Settlement.Kind.PROTECTED_CHANGED,
			"Protected \"" + b.label() + "\" grown (" + (b.x1() - b.x0() + 1) + " x " + (b.z1() - b.z0() + 1) + " at " + b.x0() + "," + b.z0() + ")", List.of()));
		return commit(s, n, "");
	}

	/** Clears a protected area (logged). */
	public static Result unprotect(String id, String areaId, long now) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		var a = s.get().lands().protect().stream().filter(x -> x.id().equals(areaId)).findFirst();
		if (a.isEmpty()) return Result.fail("No protected area " + areaId + ".");
		Settlement n = s.get().withLands(s.get().lands().withoutArea(areaId)).withLog(new Settlement.LogEntry(now, Settlement.Kind.PROTECTED_CHANGED,
			"No longer protected: \"" + a.get().label() + "\"", List.of()));
		return commit(s, n, "\"" + a.get().label() + "\" is no longer protected.");
	}

	/** Replaces a saved settlement as a whole (saved, or nothing changes). */
	public static Result replace(Settlement n) {
		Optional<Settlement> s = store.get(n.id());
		if (s.isEmpty()) return Result.fail("No such settlement: " + n.id());
		return commit(s, n, "");
	}

	/** Sets the permission level (logged). */
	public static Result permission(String id, dev.larattalabs.steward.model.Permission p, long now) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		if (s.get().permission() == p) return Result.ok(s.get());
		return commit(s, s.get().withPermission(p, now), "");
	}

	/** Replaces what the steward may spend on its own, and what it spent. */
	public static Result autonomy(String id, Settlement.Autonomy a) {
		Optional<Settlement> s = store.get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id);
		return commit(s, s.get().withAutonomy(a), "");
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
