package dev.larattalabs.steward.module;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.BiblePin;
import dev.larattalabs.architect.api.DesignRequest;
import dev.larattalabs.steward.Steward;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The module catalog's dev hook ({@code /steward dev module <label> <request JSON>}, game masters in a development environment only;
 * tools/module-catalog.mjs): it drives
 * Steward's own catalog, gate and installs on a catalog directory (fixtures allowed), and writes the answer to
 * {@code <gameDir>/steward-modules/<label>.json} (and logs {@code module dev <label> ...}). Requests ({@code op}):
 * <ul>
 * <li>{@code env}: the gating environment (Minecraft and Architect versions, features);</li>
 * <li>{@code gate {dir, id, version?}}: the catalog's verdict (the newest version when none is given) and the entry's problems;</li>
 * <li>{@code install {dir, id, version}}: the catalog entry by its key ({@link ModuleInstalls#install});</li>
 * <li>{@code variant {dir, id, version, params?, bible? {id, version}, reserved? {front, size [w, d], cells}, key, owner?}}: made from the
 * entry's own catalog install;</li>
 * <li>{@code measuring: true} on gate, install or variant: a measurement run (an uncertified entry may be installed).</li>
 * <li>{@code byKey {key, owner?}}.</li>
 * <li>{@code cells {cells: [[x, y, z]...]}} (M2, the measurement): per cell {@code [block, age | -1, moisture | -1, block light]}, read on the
 * server thread in the overworld (an unloaded cell reads {@code null}).</li>
 * <li>{@code growLot {id, width, depth}} (M2): crop_farm@1's grow lot as {@link CropFarmLot#growLot} makes it.</li>
 * <li>{@code takeItems {box: [x0, y0, z0, x1, y1, z1]}} (M2, the harvest count): the dropped item entities in the box, counted per item
 * and discarded.</li>
 * <li>{@code mobs {box: [x0, y0, z0, x1, y1, z1]}} (M2, the exclusion test): the living non-player entities in the box: {@code [type, x, y,
 * z, uuid]}.</li>
 * <li>{@code walk {from: [x, y, z], to: [x, y, z], steps?}} (M2, the exclusion test): a fake player (Fabric's) set at {@code from} walks in
 * straight steps of 0.2 towards {@code to} with vanilla's collision ({@code Entity.move(SELF, ...)}, gravity each step); answers where it ended.</li>
 * </ul>
 */
public final class ModuleDev {
	private ModuleDev() {}

	/** Whether this game may run the hook at all: a development environment only (the dev client), never a released game. */
	public static boolean allowed() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	/**
	 * The gating environment of this game (fixtures and measuring only in a development environment), with the running kit and checker the
	 * caller read from Architect ({@code ModuleOffers.env()}).
	 */
	public static ModuleGate.Env env(boolean fixtures, boolean measuring, Set<String> features, String kit, String checker) {
		boolean dev = allowed();
		return new ModuleGate.Env(modVersion("minecraft"), modVersion("architect_mc"), features, fixtures && dev, measuring && dev, kit, checker);
	}

	/** {@link #env(boolean, boolean, Set, String, String)} over {@code ArchitectApi.get()} (the dev hook's own gate). */
	public static ModuleGate.Env env(boolean fixtures, boolean measuring) {
		var api = ArchitectApi.get();
		var r = running(api);
		return env(fixtures, measuring, api.features(), r.kit(), r.checker());
	}

	/** The running kit and checker versions. */
	public record Running(String kit, String checker) {}

	/**
	 * (slice BCU) The kit Architect bundles and the checker its helper runs now: {@code kitVersion()} and {@code checkerVersion()} when Architect has
	 * feature {@code kitVersion}, else (or with no helper connected) {@link ModuleGate#UNKNOWN_KIT}, which no module pins.
	 */
	public static Running running(ArchitectApi api) {
		if (!api.features().contains("kitVersion")) return new Running(ModuleGate.UNKNOWN_KIT, ModuleGate.UNKNOWN_KIT);
		return new Running(api.kitVersion(), api.checkerVersion().orElse(ModuleGate.UNKNOWN_KIT));
	}

	private static String modVersion(String mod) {
		return FabricLoader.getInstance().getModContainer(mod).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("none");
	}

	/** Runs one request; completes (on any thread) with its answer, already written. */
	public static CompletableFuture<JsonObject> run(MinecraftServer server, String label, JsonObject req) {
		CompletableFuture<JsonObject> f;
		try {
			f = allowed() ? op(server, req) : CompletableFuture.completedFuture(error("the module hook runs only in a development environment"));
		} catch (RuntimeException e) {
			f = CompletableFuture.completedFuture(error(e.toString()));
		}
		return f.exceptionally(e -> error(String.valueOf(e))).thenApply(o -> {
			o.addProperty("label", label);
			o.add("request", req);
			Path out = server.getServerDirectory().resolve("steward-modules").resolve(label + ".json");
			try {
				Files.createDirectories(out.getParent());
				Files.writeString(out, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(o));
			} catch (IOException e) {
				Steward.LOGGER.warn("module dev {}: {}", label, e.toString());
			}
			Steward.LOGGER.info("module dev {} {}", label, o.has("error") ? "error " + o.get("error").getAsString() : o.has("entryId") ? o.get("entryId") : "done");
			return o;
		});
	}

	private static CompletableFuture<JsonObject> op(MinecraftServer server, JsonObject req) {
		String op = req.get("op").getAsString();
		switch (op) {
			case "cells" -> {
				return CompletableFuture.supplyAsync(() -> cells(server, req.getAsJsonArray("cells")), server);
			}
			case "takeItems" -> {
				return CompletableFuture.supplyAsync(() -> takeItems(server, req.getAsJsonArray("box")), server);
			}
			case "mobs" -> {
				return CompletableFuture.supplyAsync(() -> mobs(server, req.getAsJsonArray("box")), server);
			}
			case "walk" -> {
				return CompletableFuture.supplyAsync(() -> walk(server, req), server);
			}
			case "hallLot" -> {
				JsonObject a = new JsonObject();
				a.add("lot", StorageHallLot.growLot(req.get("id").getAsString(), req.get("slots").getAsInt(), req.get("bulk").getAsInt()));
				return CompletableFuture.completedFuture(a);
			}
			case "hallBefore" -> {
				// (M4b) Steward's own pre-check of a growth's undo (Growths.undo): the words for its halls' cargo, or null when they hold none of it
				return CompletableFuture.supplyAsync(() -> {
					JsonObject a = new JsonObject();
					String region = req.get("region").getAsString();
					int rid = req.get("rid").getAsInt();
					var rv = ArchitectApi.get().regions().get(region)
						.flatMap(v -> v.revisions().stream().filter(x -> x.rid() == rid).findFirst());
					a.addProperty("words", rv.map(x -> dev.larattalabs.steward.service.HallCargo.before(server, req.get("owner").getAsString(), x.sites())).orElse(null));
					return a;
				}, server);
			}
			case "hallCargo" -> {
				return CompletableFuture.supplyAsync(() -> {
					JsonObject a = new JsonObject();
					a.addProperty("words", dev.larattalabs.steward.service.HallCargo.words(server, req.get("owner").getAsString(), req.has("region") ? req.get("region").getAsString() : null, req.get("message").getAsString()));
					// (M4-L) a hall site's declared container cells in the world, and the words its undo would be refused with now (HallCargo.before)
					if (req.has("site")) {
						String sid = req.get("site").getAsString();
						a.add("declared", dev.larattalabs.steward.service.HallCargo.declaredJson(server, sid));
						a.addProperty("before", dev.larattalabs.steward.service.HallCargo.before(server, req.get("owner").getAsString(), java.util.List.of(sid)));
					}
					return a;
				}, server);
			}
			case "containers" -> {
				return CompletableFuture.supplyAsync(() -> containers(server, req.getAsJsonArray("box")), server);
			}
			case "putItems" -> {
				return CompletableFuture.supplyAsync(() -> putItems(server, req), server);
			}
			case "clearContainers" -> {
				return CompletableFuture.supplyAsync(() -> clearContainers(server, req.getAsJsonArray("box")), server);
			}
			case "growLot" -> {
				JsonObject a = new JsonObject();
				a.add("lot", CropFarmLot.growLot(req.get("id").getAsString(), req.get("width").getAsInt(), req.get("depth").getAsInt()));
				return CompletableFuture.completedFuture(a);
			}
			case "env" -> {
				return CompletableFuture.completedFuture(envJson(env(true, false)));
			}
			case "byKey" -> {
				String owner = str(req, "owner", ModuleInstalls.CATALOG_OWNER);
				return ModuleInstalls.byKey(req.get("key").getAsString(), owner).thenApply(o -> {
					JsonObject a = new JsonObject();
					a.addProperty("found", o.isPresent());
					if (o.isPresent()) a.addProperty("entryId", o.get());
					return a;
				});
			}
			default -> {
			}
		}
		ModuleCatalog cat = ModuleCatalog.dir(Path.of(req.get("dir").getAsString()), true);
		String id = req.get("id").getAsString();
		// measuring: a dev measurement run installs an entry whose measurement is missing or stale (the run that measures it)
		var env = env(true, req.has("measuring") && req.get("measuring").getAsBoolean());
		if (op.equals("gate")) {
			var v = req.has("version") ? cat.get(id, req.get("version").getAsInt()).map(e -> ModuleGate.offer(e, env)).orElse(new ModuleGate.Verdict(null,
				ModuleGate.Why.UNKNOWN, "no such version")) : ModuleGate.offer(cat, id, env);
			JsonObject a = envJson(env);
			a.addProperty("ok", v.ok());
			a.addProperty("why", v.why() == null ? null : v.why().name());
			a.addProperty("message", v.message());
			if (v.entry() != null) {
				a.addProperty("entry", v.entry().key());
				a.addProperty("programSha", v.entry().programSha());
				a.add("problems", strings(v.entry().problems()));
				a.add("uncertified", strings(v.entry().uncertified()));
				a.addProperty("moduleSha", v.entry().moduleSha());
				a.addProperty("key", ModuleInstalls.catalogKey(v.entry()));
				JsonArray combos = new JsonArray();
				for (var c : v.entry().envelope().combinations()) {
					JsonObject o = new JsonObject();
					c.forEach(o::add);
					combos.add(o);
				}
				a.add("branches", combos);
			}
			a.add("catalogProblems", strings(cat.problems()));
			return CompletableFuture.completedFuture(a);
		}
		var e = cat.get(id, req.get("version").getAsInt()).orElseThrow(() -> new IllegalArgumentException("no " + id + "@" + req.get("version") + " in " + req.get("dir")));
		CompletableFuture<ModuleInstalls.Outcome> out = switch (op) {
			case "install" -> ModuleInstalls.install(e, env);
			case "variant" -> {
				BiblePin bible = null;
				if (req.has("bible") && req.get("bible").isJsonObject()) {
					JsonObject b = req.getAsJsonObject("bible");
					bible = new BiblePin(b.get("id").getAsString(), b.has("version") ? b.get("version").getAsInt() : 0);
				}
				yield ModuleInstalls.variant(e, env, req.has("params") ? req.getAsJsonObject("params") : null, bible,
					reserved(req), req.get("key").getAsString(), str(req, "owner", ModuleInstalls.CATALOG_OWNER), McBlockFacts.INSTANCE);
			}
			default -> throw new IllegalArgumentException("unknown op " + op);
		};
		String key = op.equals("install") ? ModuleInstalls.catalogKey(e) : req.get("key").getAsString();
		return out.thenApply(o -> {
			JsonObject a = new JsonObject();
			a.addProperty("entry", e.key());
			a.addProperty("programSha", e.programSha());
			a.addProperty("key", key);
			a.addProperty("ok", o.ok());
			a.addProperty("entryId", o.entryId());
			a.addProperty("detail", o.detail());
			a.addProperty("message", o.message());
			a.add("findings", strings(o.findings()));
			a.addProperty("baseEntryId", o.baseEntryId());
			if (o.bible() != null) {
				JsonObject b = new JsonObject();
				b.addProperty("id", o.bible().id());
				b.addProperty("version", o.bible().version());
				a.add("bible", b);
			}
			a.addProperty("lamp", o.lamp());
			a.addProperty("lampFallback", o.lampFallback());
			return a;
		});
	}

	/** Each cell's block, crop age, farmland moisture and block light (the measurement's reads; the server thread). */
	private static JsonObject cells(MinecraftServer server, JsonArray cells) {
		ServerLevel level = server.overworld();
		JsonArray out = new JsonArray();
		for (JsonElement c : cells) {
			var a = c.getAsJsonArray();
			BlockPos pos = new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
			if (!level.hasChunkAt(pos)) {
				out.add(com.google.gson.JsonNull.INSTANCE);
				continue;
			}
			BlockState st = level.getBlockState(pos);
			JsonArray r = new JsonArray();
			r.add(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
			r.add(st.hasProperty(BlockStateProperties.AGE_7) ? st.getValue(BlockStateProperties.AGE_7) : st.hasProperty(BlockStateProperties.AGE_3) ? st.getValue(BlockStateProperties.AGE_3) : -1);
			r.add(st.hasProperty(BlockStateProperties.MOISTURE) ? st.getValue(BlockStateProperties.MOISTURE) : -1);
			r.add(level.getBrightness(LightLayer.BLOCK, pos));
			out.add(r);
		}
		JsonObject o = new JsonObject();
		o.add("cells", out);
		o.addProperty("gameTime", level.getGameTime());
		return o;
	}

	private static BlockPos at(JsonArray a) {
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
	}

	/** A stack's key: its item id, with {@code #<name>} when it carries a custom name, and {@code +} when it carries any other added component. */
	static String stackKey(ItemStack st) {
		String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
		var name = st.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
		String k = name == null ? id : id + "#" + name.getString();
		return st.getComponentsPatch().isEmpty() || name != null && st.getComponentsPatch().size() == 1 ? k : k + "+";
	}

	/**
	 * (M4) Every container block entity in a box (loaded chunks only): {@code [{at, block, state, size, slots: [[slot, key, count]]}]}, and the
	 * box's totals per stack key. The measurement reads a hall's containers with it, slot by slot.
	 */
	private static JsonObject containers(MinecraftServer server, JsonArray b) {
		ServerLevel level = server.overworld();
		JsonArray out = new JsonArray();
		JsonObject totals = new JsonObject();
		int unloaded = 0;
		for (BlockPos pos : BlockPos.betweenClosed(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(), b.get(3).getAsInt(), b.get(4).getAsInt(), b.get(5).getAsInt())) {
			if (!level.hasChunkAt(pos)) { unloaded++; continue; }
			if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.Container c)) continue;
			JsonObject o = new JsonObject();
			JsonArray p = new JsonArray();
			p.add(pos.getX());
			p.add(pos.getY());
			p.add(pos.getZ());
			o.add("at", p);
			BlockState st = level.getBlockState(pos);
			o.addProperty("block", BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
			o.addProperty("state", st.toString());
			o.addProperty("size", c.getContainerSize());
			JsonArray slots = new JsonArray();
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack it = c.getItem(i);
				if (it.isEmpty()) continue;
				JsonArray r = new JsonArray();
				r.add(i);
				String k = stackKey(it);
				r.add(k);
				r.add(it.getCount());
				slots.add(r);
				totals.addProperty(k, (totals.has(k) ? totals.get(k).getAsInt() : 0) + it.getCount());
			}
			o.add("slots", slots);
			out.add(o);
		}
		JsonObject a = new JsonObject();
		a.add("containers", out);
		a.add("totals", totals);
		a.addProperty("unloaded", unloaded);
		return a;
	}

	/**
	 * (M4) Puts stacks into one container: {@code {at, stacks: [{slot, id, count, name?}]}} ({@code name}: a custom name, a component stack). A slot
	 * that holds anything refuses the whole request, so nothing is overwritten.
	 */
	private static JsonObject putItems(MinecraftServer server, JsonObject req) {
		ServerLevel level = server.overworld();
		BlockPos pos = at(req.getAsJsonArray("at"));
		if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.Container c)) return error("no container at " + pos.toShortString());
		JsonArray stacks = req.getAsJsonArray("stacks");
		for (JsonElement e : stacks) {
			int slot = e.getAsJsonObject().get("slot").getAsInt();
			if (slot < 0 || slot >= c.getContainerSize() || !c.getItem(slot).isEmpty()) return error("slot " + slot + " at " + pos.toShortString() + " is not an empty slot");
		}
		int n = 0;
		for (JsonElement e : stacks) {
			JsonObject s = e.getAsJsonObject();
			var item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.parse(s.get("id").getAsString()));
			if (item.isEmpty()) return error("unknown item " + s.get("id").getAsString());
			ItemStack st = new ItemStack(item.get(), s.get("count").getAsInt());
			if (s.has("name")) st.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(s.get("name").getAsString()));
			c.setItem(s.get("slot").getAsInt(), st);
			n += st.getCount();
		}
		c.setChanged();
		JsonObject a = new JsonObject();
		a.addProperty("ok", true);
		a.addProperty("items", n);
		return a;
	}

	/** (M4) Empties every container in a box; answers what was taken, per stack key. */
	private static JsonObject clearContainers(MinecraftServer server, JsonArray b) {
		ServerLevel level = server.overworld();
		JsonObject taken = new JsonObject();
		for (BlockPos pos : BlockPos.betweenClosed(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(), b.get(3).getAsInt(), b.get(4).getAsInt(), b.get(5).getAsInt())) {
			if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof net.minecraft.world.Container c)) continue;
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack it = c.getItem(i);
				if (it.isEmpty()) continue;
				String k = stackKey(it);
				taken.addProperty(k, (taken.has(k) ? taken.get(k).getAsInt() : 0) + it.getCount());
				c.setItem(i, ItemStack.EMPTY);
			}
			c.setChanged();
		}
		JsonObject a = new JsonObject();
		a.add("taken", taken);
		return a;
	}

	/** The dropped items in a box, counted per item id, then discarded (the harvest's count). */
	private static JsonObject takeItems(MinecraftServer server, JsonArray b) {
		ServerLevel level = server.overworld();
		var box = new net.minecraft.world.phys.AABB(b.get(0).getAsDouble(), b.get(1).getAsDouble(), b.get(2).getAsDouble(), b.get(3).getAsDouble() + 1, b.get(4).getAsDouble() + 1, b.get(5).getAsDouble() + 1);
		JsonObject items = new JsonObject();
		int entities = 0;
		for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box, x -> true)) {
			var st = e.getItem();
			String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
			items.addProperty(id, (items.has(id) ? items.get(id).getAsInt() : 0) + st.getCount());
			entities++;
			e.discard();
		}
		JsonObject o = new JsonObject();
		o.add("items", items);
		o.addProperty("entities", entities);
		return o;
	}

	/** The living non-player entities in a box: [type, x, y, z, uuid]. */
	private static JsonObject mobs(MinecraftServer server, JsonArray b) {
		ServerLevel level = server.overworld();
		var box = new net.minecraft.world.phys.AABB(b.get(0).getAsDouble(), b.get(1).getAsDouble(), b.get(2).getAsDouble(), b.get(3).getAsDouble() + 1, b.get(4).getAsDouble() + 1, b.get(5).getAsDouble() + 1);
		JsonArray out = new JsonArray();
		for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box, x -> !(x instanceof net.minecraft.world.entity.player.Player))) {
			JsonArray r = new JsonArray();
			r.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
			r.add(e.getX());
			r.add(e.getY());
			r.add(e.getZ());
			r.add(e.getStringUUID());
			out.add(r);
		}
		JsonObject o = new JsonObject();
		o.add("mobs", out);
		return o;
	}

	/** A fake player walks from {@code from} towards {@code to} with vanilla's collision; where it ended and whether it reached {@code to}. */
	private static JsonObject walk(MinecraftServer server, JsonObject req) {
		ServerLevel level = server.overworld();
		var f = req.getAsJsonArray("from");
		var t = req.getAsJsonArray("to");
		Vec3 to = new Vec3(t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.get(2).getAsDouble());
		int steps = req.has("steps") ? req.get("steps").getAsInt() : 400;
		var p = net.fabricmc.fabric.api.entity.FakePlayer.get(level);
		p.setPos(f.get(0).getAsDouble(), f.get(1).getAsDouble(), f.get(2).getAsDouble());
		for (int i = 0; i < steps; i++) {
			Vec3 at = p.position();
			Vec3 d = new Vec3(to.x - at.x, 0, to.z - at.z);
			if (d.length() < 0.25) break;
			Vec3 step = d.normalize().scale(0.2);
			p.move(MoverType.SELF, new Vec3(step.x, -0.08, step.z));
			if (p.horizontalCollision) {
				// a one-block step up (a path's 15/16 to a full block): vanilla's step height covers it; a fence (1.5) does not
				p.move(MoverType.SELF, new Vec3(0, 0.6, 0));
				p.move(MoverType.SELF, new Vec3(step.x, 0, step.z));
				p.move(MoverType.SELF, new Vec3(0, -0.6, 0));
			}
		}
		Vec3 end = p.position();
		JsonObject o = new JsonObject();
		JsonArray e = new JsonArray();
		e.add(end.x);
		e.add(end.y);
		e.add(end.z);
		o.add("end", e);
		o.addProperty("reached", Math.hypot(end.x - to.x, end.z - to.z) < 0.5);
		return o;
	}

	private static DesignRequest.Reserved reserved(JsonObject req) {
		if (!req.has("reserved") || !req.get("reserved").isJsonObject()) return null;
		JsonObject r = req.getAsJsonObject("reserved");
		List<BlockPos> cells = new ArrayList<>();
		for (JsonElement c : r.getAsJsonArray("cells")) {
			var a = c.getAsJsonArray();
			cells.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
		}
		var size = r.getAsJsonArray("size");
		return new DesignRequest.Reserved(r.get("front").getAsString(), size.get(0).getAsInt(), size.get(1).getAsInt(), cells);
	}

	private static JsonObject envJson(ModuleGate.Env env) {
		JsonObject a = new JsonObject();
		a.addProperty("minecraft", env.minecraft());
		a.addProperty("architect", env.architect());
		a.addProperty("givenPrograms", env.features().contains(ModuleGate.FEATURE));
		a.addProperty("measuring", env.measuring());
		return a;
	}

	private static JsonObject error(String m) {
		JsonObject a = new JsonObject();
		a.addProperty("ok", false);
		a.addProperty("error", m);
		return a;
	}

	private static JsonArray strings(List<String> l) {
		JsonArray a = new JsonArray();
		l.forEach(a::add);
		return a;
	}

	private static String str(JsonObject o, String k, String d) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d;
	}
}
