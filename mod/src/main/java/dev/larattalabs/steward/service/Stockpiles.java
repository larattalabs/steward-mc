package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.Stockpile;
import dev.larattalabs.architect.api.StockpileRequest;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * A Supplied settlement's stockpile (docs/PLAN.md phase 4; Architect C24, API 1.17): one store for every build, road, update and refund of the settlement,
 * its first crate beside the founding stone, more where the player puts them. Steward makes it before a build is placed from materials, and folds in the
 * crates older builds left. Server thread.
 */
public final class Stockpiles {
	/** How far from the founding stone the first crates may go. */
	private static final int REACH = 8;

	/** The cells around the stone (the claim's centre), nearest first, from 2 out to {@link #REACH}. */
	static List<int[]> around() {
		List<int[]> out = new ArrayList<>();
		for (int dx = -REACH; dx <= REACH; dx++) for (int dz = -REACH; dz <= REACH; dz++) if (Math.max(Math.abs(dx), Math.abs(dz)) >= 2) out.add(new int[] {dx, dz});
		out.sort(Comparator.comparingInt((int[] o) -> o[0] * o[0] + o[1] * o[1]).thenComparingInt(o -> o[0]).thenComparingInt(o -> o[1]));
		return out;
	}

	private Stockpiles() {
	}

	/** The label of the protected area over a settlement's stockpile crates (one per settlement, grown when crates are added). */
	static final String AREA_LABEL = "stockpile";

	/**
	 * Keeps a plan off the stockpile (Architect RS, docs/COMPOSITION.md section 12): a protected area over its crates and a ring of 1 round them (Architect's
	 * check treats protected areas as keep-out; a crate site alone it does not). The settlement's own "stockpile" area is made, or grown to cover new crates,
	 * and synced to Architect. Null when done, else why not.
	 */
	public static @Nullable String protectCrates(MinecraftServer server, Settlement s, Stockpile sp) {
		var rect = ringed(sp.crates());
		if (rect == null) return "the stockpile has no crate";
		var have = s.lands().protect().stream().filter(a -> AREA_LABEL.equals(a.label()) && a.dimension().equals(s.claim().dimension())).findFirst();
		if (have.isPresent() && have.get().x0() <= rect[0] && have.get().z0() <= rect[1] && have.get().x1() >= rect[2] && have.get().z1() >= rect[3]) return null;
		long now = System.currentTimeMillis();
		if (have.isPresent()) {
			var a = have.get();
			rect = new int[] {Math.min(rect[0], a.x0()), Math.min(rect[1], a.z0()), Math.max(rect[2], a.x1()), Math.max(rect[3], a.z1())};
			var off = Settlements.unprotect(s.id(), a.id(), now);
			if (!off.ok()) return off.error();
		}
		var r = Settlements.protect(s.id(), AREA_LABEL, s.claim().dimension(), rect[0], rect[1], rect[2], rect[3], now);
		if (!r.ok()) return r.error();
		return Protection.sync(server, s.id()) ? null : "Architect would not protect it";
	}

	/** The crates' columns grown by 1 as {x0, z0, x1, z1}, or null when there is none. */
	static int @Nullable [] ringed(List<BlockPos> crates) {
		if (crates.isEmpty()) return null;
		int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		for (BlockPos p : crates) {
			x0 = Math.min(x0, p.getX());
			z0 = Math.min(z0, p.getZ());
			x1 = Math.max(x1, p.getX());
			z1 = Math.max(z1, p.getZ());
		}
		return new int[] {x0 - 1, z0 - 1, x1 + 1, z1 + 1};
	}

	/** The settlement's stockpile, or empty (none yet, or an Architect without stockpiles). */
	public static Optional<Stockpile> of(MinecraftServer server, Settlement s) {
		try {
			return ArchitectApi.get().sites(server).stockpile(s.owner());
		} catch (UnsupportedOperationException | LinkageError e) {
			return Optional.empty();
		}
	}

	/**
	 * The settlement's stockpile, made when there is none: its first crate on the ground beside the founding stone (the first cell Architect takes), and the
	 * crates older builds left folded into it. Null (having said why) when it can't be made; the build then keeps its own crate.
	 */
	public static @Nullable Stockpile ensure(MinecraftServer server, Settlement s, Consumer<String> say) {
		return ensure(server, s, say, (x, z) -> true);
	}

	/**
	 * As above, its crates only on cells {@code free} allows (a build's lots and street, and protected ground, are not: a crate there would block what is
	 * being built). With a stockpile already, older crates are folded in again (idempotent), so one adoption that failed is retried.
	 */
	public static @Nullable Stockpile ensure(MinecraftServer server, Settlement s, Consumer<String> say, java.util.function.BiPredicate<Integer, Integer> free) {
		var have = of(server, s);
		if (have.isPresent()) return adopt(server, s, have.get(), say);
		ServerLevel level = level(server, s);
		if (level == null) return null;
		var sites = ArchitectApi.get().sites(server);
		String last = null;
		for (int[] o : around()) {
			int x = s.claim().centerX() + o[0], z = s.claim().centerZ() + o[1];
			if (!free.test(x, z) || !s.lands().protect().stream().noneMatch(a -> a.contains(s.claim().dimension(), x, z))) continue;
			BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			try {
				Stockpile sp = sites.createStockpile(new StockpileRequest(s.owner(), level.dimension(), List.of(at)));
				// a second crate beside it: one holds 27 stacks, and a small build already wants about 30 kinds (its refunds would not fit)
				for (int[] d : new int[][] {{0, 1}, {1, 0}, {0, -1}, {-1, 0}}) {
					int x2 = x + d[0], z2 = z + d[1];
					if (!free.test(x2, z2) || s.lands().protect().stream().anyMatch(a -> a.contains(s.claim().dimension(), x2, z2))) continue;
					try {
						sp = sites.addStockpileCrate(s.owner(), level.dimension(), new BlockPos(x2, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x2, z2), z2));
						break;
					} catch (RuntimeException e) {
						// that cell is taken: the next
					}
				}
				sp = adopt(server, s, sp, say);
				say.accept(s.name() + "'s stockpile is " + (sp.crates().size() == 1 ? "the crate" : "the " + sp.crates().size() + " crates") + " at " + at.getX() + ", " + at.getY() + ", " + at.getZ()
					+ ": every build draws its materials from it. Fill it by hand or with hoppers; add crates with /steward stockpile " + s.id() + " add.");
				return sp;
			} catch (UnsupportedOperationException | LinkageError e) {
				return null;
			} catch (RuntimeException e) {
				last = SettlementRunner.message(e);
			}
		}
		say.accept("No stockpile could be made beside the founding stone (" + last + "): this build keeps its own crate.");
		return null;
	}

	/**
	 * Folds the crates older builds left into the stockpile (Architect's adoptCrates: idempotent). What did not fit is dropped at a crate: the player is told
	 * how much, and where.
	 */
	private static Stockpile adopt(MinecraftServer server, Settlement s, Stockpile sp, Consumer<String> say) {
		int droppedBefore = sp.dropped().values().stream().mapToInt(Integer::intValue).sum();
		try {
			Stockpile after = ArchitectApi.get().sites(server).adoptCrates(s.owner());
			int dropped = after.dropped().values().stream().mapToInt(Integer::intValue).sum() - droppedBefore;
			if (dropped > 0 && !after.crates().isEmpty()) {
				BlockPos c = after.crates().get(0);
				say.accept(dropped + " items from older builds' crates did not fit in the stockpile and lie beside its crate at " + c.getX() + ", " + c.getY() + ", " + c.getZ()
					+ ": pick them up, or add a crate (/steward stockpile " + s.id() + " add) and put them in.");
			}
			return after;
		} catch (RuntimeException e) {
			Steward.LOGGER.warn("stockpile of {}: older crates not adopted: {}", s.id(), e.toString());
			return sp;
		}
	}

	/**
	 * Before a change built from materials outside a build (an update, a revert): in a survival world, the settlement's stockpile, made when there is none
	 * (off the town's street), so the change draws from it rather than a crate of its own. Nothing in a world without survival.
	 */
	public static boolean forConstruction(MinecraftServer server, Settlement s, Consumer<String> say,
		net.minecraft.world.level.levelgen.structure.@Nullable BoundingBox changing) {
		if (!dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false)) return true;
		var street = Districts.streetOf(ArchitectApi.get().sites(server).list(s.owner()), s, null);
		// off the town's street, and well clear of the building that changes (a new version may be larger than the one that stands)
		return ensure(server, s, say, (x, z) -> (street == null || !(Math.abs(z - street.z()) <= 3 && x >= street.x0() - 3 && x <= street.x1() + 3))
			&& (changing == null || x < changing.minX() - CHANGE_MARGIN || x > changing.maxX() + CHANGE_MARGIN || z < changing.minZ() - CHANGE_MARGIN
				|| z > changing.maxZ() + CHANGE_MARGIN)) != null;
	}

	/**
	 * The ground a change of {@code siteId} to {@code version} covers: the building as it stands and the box Architect's check says the change writes
	 * ({@code DeltaVerdict.box}), together; the standing box alone when the check cannot say.
	 */
	public static net.minecraft.world.level.levelgen.structure.@Nullable BoundingBox changing(MinecraftServer server, Settlement s, String siteId, int version) {
		var sites = ArchitectApi.get().sites(server);
		var standing = sites.get(siteId).map(dev.larattalabs.architect.api.SiteView::box).orElse(null);
		try {
			var writes = sites.checkDelta(dev.larattalabs.steward.gateway.UpdatePlanner.request(siteId, version, dev.larattalabs.steward.gateway.UpdatePlanner.editsFor(s.permission()), s.owner())).box();
			if (writes == null) return standing;
			return standing == null ? writes : net.minecraft.world.level.levelgen.structure.BoundingBox.encapsulatingBoxes(List.of(standing, writes)).orElse(standing);
		} catch (RuntimeException e) {
			return standing;
		}
	}

	/** How far round a changing building a crate keeps (a change may add a wing or a storey: Steward's change requests allow 4 more each way). */
	static final int CHANGE_MARGIN = 6;

	/** Adds a crate at {@code at} (where the player stands): more room in the store. Returns what to tell the player. */
	public static String addCrate(MinecraftServer server, Settlement s, BlockPos at) {
		ServerLevel level = level(server, s);
		if (level == null) return "The dimension of " + s.name() + " is not loaded.";
		var sites = ArchitectApi.get().sites(server);
		try {
			boolean fresh = of(server, s).isEmpty();
			Stockpile sp = fresh ? sites.createStockpile(new StockpileRequest(s.owner(), level.dimension(), List.of(at))) : sites.addStockpileCrate(s.owner(), level.dimension(), at);
			// a new stockpile takes in the crates older builds left (what did not fit is said with the answer)
			StringBuilder dropped = new StringBuilder();
			if (fresh) sp = adopt(server, s, sp, m -> dropped.append(' ').append(m));
			return s.name() + "'s stockpile has " + sp.crates().size() + (sp.crates().size() == 1 ? " crate" : " crates") + " (" + sp.capacityStacks() + " stacks)." + dropped;
		} catch (UnsupportedOperationException | LinkageError e) {
			return "This Architect has no stockpiles (it needs 0.20).";
		} catch (RuntimeException e) {
			return "No crate there: " + SettlementRunner.message(e);
		}
	}

	/** What the stockpile holds and what the builds still need, in a few lines (the most needed first). */
	public static List<String> bill(Stockpile sp, int lines) {
		List<String> out = new ArrayList<>();
		out.add("Stockpile: " + sp.crates().size() + (sp.crates().size() == 1 ? " crate" : " crates") + ", " + sp.usedStacks() + " of " + sp.capacityStacks() + " stacks used.");
		var short_ = shortfall(sp);
		if (short_.isEmpty()) out.add(sp.outstanding().isEmpty() ? "Nothing is being built from it." : "It holds all that the builds still need.");
		else {
			out.add("Still needed: " + items(short_, lines) + ".");
		}
		return out;
	}

	/** Per item, what the builds still need beyond what the stockpile holds, most first. */
	public static List<Map.Entry<Item, Integer>> shortfall(Stockpile sp) {
		List<Map.Entry<Item, Integer>> out = new ArrayList<>();
		for (var e : sp.outstanding().entrySet()) {
			int lack = e.getValue() - sp.stock().getOrDefault(e.getKey(), 0);
			if (lack > 0) out.add(Map.entry(e.getKey(), lack));
		}
		out.sort(Comparator.comparing((Map.Entry<Item, Integer> e) -> -e.getValue()));
		return out;
	}

	/**
	 * The stockpile's ledger as JSON (dev: the e2e check): crates, stock, the builds' outstanding bill and what is still needed beyond the stock, the
	 * ledger's delivered, consumed and returned, and each of the settlement's sites with its state. Items by id.
	 */
	public static com.google.gson.JsonObject json(MinecraftServer server, Settlement s, Stockpile sp) {
		var o = new com.google.gson.JsonObject();
		o.addProperty("crates", sp.crates().size());
		o.add("stock", items(sp.stock()));
		o.add("outstanding", items(sp.outstanding()));
		var need = new java.util.LinkedHashMap<Item, Integer>();
		for (var e : shortfall(sp)) need.put(e.getKey(), e.getValue());
		o.add("need", items(need));
		o.add("delivered", items(sp.delivered()));
		o.add("consumed", items(sp.consumed()));
		o.add("returned", items(sp.returned()));
		o.add("refundOverflow", items(sp.refundOverflow()));
		var sites = new com.google.gson.JsonArray();
		for (var v : ArchitectApi.get().sites(server).list(s.owner())) {
			var x = new com.google.gson.JsonObject();
			x.addProperty("id", v.id());
			x.addProperty("kind", v.kind());
			x.addProperty("state", v.state().name());
			sites.add(x);
		}
		o.add("sites", sites);
		return o;
	}

	private static com.google.gson.JsonObject items(Map<Item, Integer> m) {
		var o = new com.google.gson.JsonObject();
		for (var e : m.entrySet()) if (e.getValue() != 0) o.addProperty(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), e.getValue());
		return o;
	}

	/** "12 oak planks, 4 glass and 3 more" for the first {@code n}. */
	public static String items(List<Map.Entry<Item, Integer>> list, int n) {
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < Math.min(n, list.size()); i++) parts.add(list.get(i).getValue() + " " + name(list.get(i).getKey()));
		String more = list.size() > n ? " and " + (list.size() - n) + " more" : "";
		return String.join(", ", parts) + more;
	}

	/** An item's name as a player reads it ("oak planks"). */
	public static String name(Item item) {
		return new net.minecraft.world.item.ItemStack(item).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
	}

	private static @Nullable ServerLevel level(MinecraftServer server, Settlement s) {
		ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(s.claim().dimension()));
		return server.getLevel(key);
	}
}
