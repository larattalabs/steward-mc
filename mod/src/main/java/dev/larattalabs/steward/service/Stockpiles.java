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
	/** Where the first crate may go, around the founding stone (the claim's centre): beside it, then further out. */
	private static final int[][] FIRST_CRATE = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}, {4, 0}, {-4, 0}, {0, 4}, {0, -4}};

	private Stockpiles() {
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
		var have = of(server, s);
		if (have.isPresent()) return have.get();
		ServerLevel level = level(server, s);
		if (level == null) return null;
		var sites = ArchitectApi.get().sites(server);
		String last = null;
		for (int[] o : FIRST_CRATE) {
			int x = s.claim().centerX() + o[0], z = s.claim().centerZ() + o[1];
			BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			try {
				Stockpile sp = sites.createStockpile(new StockpileRequest(s.owner(), level.dimension(), List.of(at)));
				// the crates builds placed before the stockpile: their stock joins it
				try {
					sp = sites.adoptCrates(s.owner());
				} catch (RuntimeException e) {
					Steward.LOGGER.warn("stockpile of {}: older crates not adopted: {}", s.id(), e.toString());
				}
				say.accept(s.name() + "'s stockpile is the crate at " + at.getX() + ", " + at.getY() + ", " + at.getZ()
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

	/** Adds a crate at {@code at} (where the player stands): more room in the store. Returns what to tell the player. */
	public static String addCrate(MinecraftServer server, Settlement s, BlockPos at) {
		ServerLevel level = level(server, s);
		if (level == null) return "The dimension of " + s.name() + " is not loaded.";
		var sites = ArchitectApi.get().sites(server);
		try {
			Stockpile sp = of(server, s).isPresent() ? sites.addStockpileCrate(s.owner(), level.dimension(), at)
				: sites.createStockpile(new StockpileRequest(s.owner(), level.dimension(), List.of(at)));
			return s.name() + "'s stockpile has " + sp.crates().size() + (sp.crates().size() == 1 ? " crate" : " crates") + " (" + sp.capacityStacks() + " stacks).";
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
