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

	/** The cargo of one hall: items, and the container cells holding any. */
	public record Count(int items, int containers) {}

	/** The cargo in a site's box (every container block entity in it). */
	public static Count count(ServerLevel level, SiteView v) {
		int items = 0, containers = 0;
		var b = v.box();
		for (BlockPos pos : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
			if (!level.isLoaded(pos)) continue;
			if (!(level.getBlockEntity(pos) instanceof Container c)) continue;
			int n = 0;
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack st = c.getItem(i);
				if (!st.isEmpty()) n += st.getCount();
			}
			if (n > 0) {
				items += n;
				containers++;
			}
		}
		return new Count(items, containers);
	}

	/**
	 * The words for a refused undo in this region, when a storage hall of the owner's in it holds items: "the storage hall still holds 1,240 items in
	 * 9 chests; empty it first". Null when none does.
	 */
	public static @Nullable String words(MinecraftServer server, String owner, @Nullable String region) {
		if (region == null) return null;
		int items = 0, containers = 0;
		for (SiteView v : ArchitectApi.get().sites(server).list(owner)) {
			if (!region.equals(v.region())) continue;
			var ref = ModuleRef.of(v.ext());
			if (ref == null || !ModuleKind.StorageHall.ID.equals(ref.id())) continue;
			ServerLevel level = server.getLevel(v.dimension());
			if (level == null) continue;
			Count c = count(level, v);
			items += c.items();
			containers += c.containers();
		}
		if (items == 0) return null;
		return "the storage hall still holds " + String.format(java.util.Locale.ROOT, "%,d", items) + " item" + (items == 1 ? "" : "s") + " in " + containers
			+ (containers == 1 ? " chest" : " chests") + "; empty it first";
	}
}
