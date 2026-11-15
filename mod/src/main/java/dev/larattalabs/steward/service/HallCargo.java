package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.module.ModuleKind;
import dev.larattalabs.steward.module.ModuleRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What a storage hall still holds, for the steward's words when its undo is refused (docs/MODULES.md section 6 and section 9 Q3: refused until it is
 * emptied; the steward names the containers and the count). Architect decides the refusal (a filled container is cargo, not ours); this only counts
 * and says. Server thread; reads loaded chunks only.
 */
public final class HallCargo {
	private HallCargo() {}

	/** The cargo of one hall: items, the containers holding any (a double chest once), and whether every cell of its box was loaded. */
	public record Count(int items, int containers, boolean complete) {}

	/** The cargo in a site's box (every container block entity in it; a double chest's two halves are one chest). */
	public static Count count(ServerLevel level, SiteView v) {
		int items = 0;
		boolean complete = true;
		java.util.Set<BlockPos> holding = new java.util.HashSet<>();
		var b = v.box();
		for (BlockPos pos : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
			if (!level.isLoaded(pos)) { complete = false; continue; }
			if (!(level.getBlockEntity(pos) instanceof Container c)) continue;
			int n = 0;
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack st = c.getItem(i);
				if (!st.isEmpty()) n += st.getCount();
			}
			if (n == 0) continue;
			items += n;
			// a double chest's halves are one chest: counted at the half with the lower position
			BlockPos key = pos.immutable();
			var state = level.getBlockState(pos);
			if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
				&& state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
				BlockPos other = pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
				if (other.compareTo(key) < 0) key = other.immutable();
			}
			holding.add(key);
		}
		return new Count(items, holding.size(), complete);
	}

	private static final java.util.regex.Pattern CARGO = java.util.regex.Pattern.compile("Move these out of \\S+ first");
	private static final java.util.regex.Pattern AT = java.util.regex.Pattern.compile("\\b[a-z_]+ at (-?\\d+), (-?\\d+), (-?\\d+)");

	/** The positions an Architect cargo refusal names ("Move these out of s17 first (...): chest at 105, 66, 578 (595 items), ..."). */
	static java.util.List<BlockPos> namedCells(@Nullable String message) {
		java.util.List<BlockPos> out = new java.util.ArrayList<>();
		if (!cargoRefusal(message)) return out;
		var m = AT.matcher(message);
		while (m.find()) out.add(new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
		return out;
	}

	/** Whether an Architect removal refusal is for things in the site that are not ours (cargo), not another reason. */
	public static boolean cargoRefusal(@Nullable String message) {
		return message != null && CARGO.matcher(message).find();
	}

	/**
	 * The words for an undo Architect refused for cargo ({@code message}: its refusal, naming each container to empty by its position), when a storage
	 * hall of the owner's holding one of them holds items: "the storage hall still holds 1,240 items in 9 chests; empty it first" ("at least" when part of a hall is not
	 * loaded). Null when the refusal names no hall, or none of them holds anything.
	 */
	public static @Nullable String words(MinecraftServer server, String owner, @Nullable String message) {
		var cells = namedCells(message);
		if (cells.isEmpty()) return null;
		int items = 0, containers = 0;
		boolean complete = true;
		for (SiteView v : ArchitectApi.get().sites(server).list(owner)) {
			var ref = ModuleRef.of(v.ext());
			if (ref == null || !ModuleKind.StorageHall.ID.equals(ref.id())) continue;
			// a hall the refusal names: one of its cells is a container to empty
			if (cells.stream().noneMatch(v.box()::isInside)) continue;
			ServerLevel level = server.getLevel(v.dimension());
			if (level == null) { complete = false; continue; }
			Count c = count(level, v);
			items += c.items();
			containers += c.containers();
			complete &= c.complete();
		}
		if (items == 0) return null;
		return "the storage hall still holds " + (complete ? "" : "at least ") + String.format(java.util.Locale.ROOT, "%,d", items) + " item" + (items == 1 ? "" : "s")
			+ " in " + containers + (containers == 1 ? " chest" : " chests") + "; empty it first";
	}
}
