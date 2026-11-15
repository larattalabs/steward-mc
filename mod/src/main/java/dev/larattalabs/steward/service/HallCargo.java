package dev.larattalabs.steward.service;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.module.ModuleKind;
import dev.larattalabs.steward.module.ModuleRef;
import dev.larattalabs.steward.module.StorageHallLot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * What a storage hall still holds, for the steward's words when its undo is refused (docs/MODULES.md section 6 and section 9 Q3: refused until it is
 * emptied; the steward names the containers and the count). Architect decides the refusal (a filled container is cargo, not ours); this only counts
 * and says. Server thread; reads loaded chunks only.
 */
public final class HallCargo {
	private HallCargo() {}

	/**
	 * The cargo of one hall: items, the containers holding any (a double chest once), and whether every cell of its box was loaded. {@code items}
	 * and {@code containers} are the hall's own declared chests (its input chest and bulk pairs, {@link #declared}); {@code otherItems} and
	 * {@code others} any other container standing in its box (a barrel or a chest a player added): Architect's removal waits on those too.
	 */
	public record Count(int items, int containers, int otherItems, int others, boolean complete) {
		public Count(int items, int containers, boolean complete) {
			this(items, containers, 0, 0, complete);
		}
	}

	/**
	 * The world cells of a hall's declared containers (the input chest, then each bulk pair's halves: {@link StorageHallLot.Layout#containers}), its
	 * template cells taken through the site's rotation into its box: the vanilla template rotation about the template's corner, then moved so the
	 * rotated template's minimum corner is the box's (SiteFrame: origin = the rotated box's minimum corner). Null when the params are not a branch
	 * this version builds or the box is not the template's size (then every container counts as the hall's, as before).
	 */
	static java.util.@Nullable Set<BlockPos> declared(@Nullable JsonObject params, BoundingBox box, @Nullable Rotation rotation) {
		StorageHallLot.Layout l;
		try {
			if (params == null || !params.has("slots") || !params.has("bulk")) return null;
			l = StorageHallLot.layout(params.get("slots").getAsInt(), params.get("bulk").getAsInt());
		} catch (RuntimeException e) {
			return null;
		}
		Rotation r = rotation == null ? Rotation.NONE : rotation;
		boolean turned = r == Rotation.CLOCKWISE_90 || r == Rotation.COUNTERCLOCKWISE_90;
		if (box.getXSpan() != (turned ? l.d() : l.w()) || box.getZSpan() != (turned ? l.w() : l.d())) return null;
		// the rotated template's minimum corner, from its two extreme corners
		int[] a = turn(0, 0, r), b = turn(l.w() - 1, l.d() - 1, r);
		int mx = Math.min(a[0], b[0]), mz = Math.min(a[1], b[1]);
		java.util.Set<BlockPos> out = new java.util.LinkedHashSet<>();
		for (int[] c : l.containers()) {
			int[] t = turn(c[0], c[2], r);
			out.add(new BlockPos(box.minX() + t[0] - mx, box.minY() + c[1], box.minZ() + t[1] - mz));
		}
		return out;
	}

	/** (dev) A hall site's declared container cells in the world, as [[x, y, z], ...]; JSON null when unknown. */
	public static com.google.gson.JsonElement declaredJson(MinecraftServer server, String siteId) {
		var v = ArchitectAccess.api().sites(server).get(siteId).orElse(null);
		if (v == null) return com.google.gson.JsonNull.INSTANCE;
		var ref = ModuleRef.of(v.ext());
		var cells = declared(ref == null ? null : ref.params(), v.box(), v.rotation());
		if (cells == null) return com.google.gson.JsonNull.INSTANCE;
		com.google.gson.JsonArray out = new com.google.gson.JsonArray();
		for (BlockPos c : cells) {
			com.google.gson.JsonArray a = new com.google.gson.JsonArray();
			a.add(c.getX());
			a.add(c.getY());
			a.add(c.getZ());
			out.add(a);
		}
		return out;
	}

	/** (x, z) through a vanilla template rotation about the origin ({@code StructureTemplate.transform} with no mirror and pivot 0, 0, 0). */
	static int[] turn(int x, int z, Rotation r) {
		return switch (r) {
			case COUNTERCLOCKWISE_90 -> new int[] {z, -x};
			case CLOCKWISE_90 -> new int[] {-z, x};
			case CLOCKWISE_180 -> new int[] {-x, -z};
			default -> new int[] {x, z};
		};
	}

	/** The cargo in a site's box: its declared chests ({@link #declared}) and any other container (a double chest's two halves are one chest). */
	public static Count count(ServerLevel level, SiteView v) {
		var ref = ModuleRef.of(v.ext());
		return count(level, v.box(), declared(ref == null ? null : ref.params(), v.box(), v.rotation()));
	}

	static Count count(ServerLevel level, BoundingBox b, java.util.@Nullable Set<BlockPos> declared) {
		int items = 0, otherItems = 0;
		boolean complete = true;
		java.util.Set<BlockPos> holding = new java.util.HashSet<>(), other = new java.util.HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
			if (!level.isLoaded(pos)) { complete = false; continue; }
			if (!(level.getBlockEntity(pos) instanceof Container c)) continue;
			int n = 0;
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack st = c.getItem(i);
				if (!st.isEmpty()) n += st.getCount();
			}
			if (n == 0) continue;
			// a double chest's halves are one chest: counted at the half with the lower position
			BlockPos key = pos.immutable();
			var state = level.getBlockState(pos);
			boolean chest = state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock;
			if (chest && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
				BlockPos o = pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
				if (o.compareTo(key) < 0) key = o.immutable();
			}
			// the hall's own: a chest standing on one of its declared cells (any container, when the declared cells are unknown)
			if (declared == null || (chest && state.getBlock() == net.minecraft.world.level.block.Blocks.CHEST && declared.contains(pos))) {
				items += n;
				holding.add(key);
			} else {
				otherItems += n;
				other.add(key);
			}
		}
		return new Count(items, holding.size(), otherItems, other.size(), complete);
	}

	private static final java.util.regex.Pattern CARGO = java.util.regex.Pattern.compile("Move these out of \\S+ first");
	private static final java.util.regex.Pattern AT = java.util.regex.Pattern.compile("\\b(chest|trapped_chest|trapped chest|barrel|hopper|dropper|dispenser|shulker box|[a-z_]*shulker_box|furnace|smoker|blast furnace|blast_furnace) at (-?\\d{1,9}), (-?\\d{1,4}), (-?\\d{1,9})\\b");

	/** The positions an Architect cargo refusal names ("Move these out of s17 first (...): chest at 105, 66, 578 (595 items), ..."). */
	static java.util.List<BlockPos> namedCells(@Nullable String message) {
		java.util.List<BlockPos> out = new java.util.ArrayList<>();
		if (!cargoRefusal(message)) return out;
		// only container entries ("chest at ...", "barrel at ..."), each coordinate parsed safely: a refusal also names entities, whose custom names
		// are the player's text
		var m = AT.matcher(message);
		while (m.find()) {
			try {
				out.add(new BlockPos(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
			} catch (NumberFormatException e) {
				// not a position: skipped
			}
		}
		return out;
	}

	/** Whether an Architect removal refusal is for things in the site that are not ours (cargo), not another reason. */
	public static boolean cargoRefusal(@Nullable String message) {
		return message != null && CARGO.matcher(message).find();
	}

	/**
	 * (Q3) The words for a storage hall among these sites (a revision's, {@code RevisionView.sites}) that holds items, before its removal is asked:
	 * Architect's removal waits on cargo as a blocker rather than answering, so the steward refuses at once and says what to empty. Null when none
	 * of them is a hall holding anything.
	 */
	public static @Nullable String before(MinecraftServer server, String owner, java.util.Collection<String> siteIds) {
		if (siteIds.isEmpty()) return null;
		int items = 0, containers = 0, otherItems = 0, others = 0;
		boolean complete = true;
		for (SiteView v : ArchitectAccess.api().sites(server).list(owner)) {
			if (!siteIds.contains(v.id())) continue;
			var ref = ModuleRef.of(v.ext());
			if (ref == null || !ModuleKind.StorageHall.ID.equals(ref.id())) continue;
			ServerLevel level = server.getLevel(v.dimension());
			if (level == null) { complete = false; continue; }
			Count c = count(level, v);
			items += c.items();
			containers += c.containers();
			otherItems += c.otherItems();
			others += c.others();
			complete &= c.complete();
		}
		return items == 0 && otherItems == 0 ? null : say(items, containers, otherItems, others, complete);
	}

	static String say(int items, int containers, boolean complete) {
		return say(items, containers, 0, 0, complete);
	}

	/**
	 * The words: the hall's own chests as before ("the storage hall still holds 74 items in 1 chest; empty it first"); a container a player added in
	 * its lot (a barrel) is said apart, never counted as the hall's chests, and still refuses the undo (Architect's removal waits on it too).
	 */
	static String say(int items, int containers, int otherItems, int others, boolean complete) {
		String least = complete ? "" : "at least ";
		String rest = otherItems == 0 ? "" : least + n(otherItems) + " item" + (otherItems == 1 ? "" : "s") + " in " + others
			+ (others == 1 ? " other container" : " other containers") + " that " + (others == 1 ? "is" : "are") + " not the hall's";
		if (items == 0) return "the storage hall's lot still holds " + rest + "; empty " + (others == 1 ? "it" : "them") + " first";
		return "the storage hall still holds " + least + n(items) + " item" + (items == 1 ? "" : "s") + " in " + containers + (containers == 1 ? " chest" : " chests")
			+ (rest.isEmpty() ? "; empty it first" : ", and " + rest + "; empty them first");
	}

	private static String n(int v) {
		return String.format(java.util.Locale.ROOT, "%,d", v);
	}

	/**
	 * The words for an undo Architect refused for cargo ({@code message}: its refusal, naming each container to empty by its position), when a storage
	 * hall of the owner's holding one of them holds items: "the storage hall still holds 1,240 items in 9 chests; empty it first" ("at least" when part of a hall is not
	 * loaded). Null when the refusal names no hall, or none of them holds anything.
	 */
	public static @Nullable String words(MinecraftServer server, String owner, @Nullable String region, @Nullable String message) {
		var cells = namedCells(message);
		if (cells.isEmpty()) return null;
		int items = 0, containers = 0, otherItems = 0, others = 0;
		boolean complete = true;
		for (SiteView v : ArchitectAccess.api().sites(server).list(owner)) {
			if (region != null && !region.equals(v.region())) continue;
			var ref = ModuleRef.of(v.ext());
			if (ref == null || !ModuleKind.StorageHall.ID.equals(ref.id())) continue;
			// a hall the refusal names: one of its cells is a container to empty
			if (cells.stream().noneMatch(v.box()::isInside)) continue;
			ServerLevel level = server.getLevel(v.dimension());
			if (level == null) { complete = false; continue; }
			Count c = count(level, v);
			items += c.items();
			containers += c.containers();
			otherItems += c.otherItems();
			others += c.others();
			complete &= c.complete();
		}
		return items == 0 && otherItems == 0 ? null : say(items, containers, otherItems, others, complete);
	}
}
