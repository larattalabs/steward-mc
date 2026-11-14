package dev.larattalabs.steward.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.ProposalRules;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.module.Canon;
import dev.larattalabs.steward.module.McBlockFacts;
import dev.larattalabs.steward.module.ModuleBound;
import dev.larattalabs.steward.module.StyleBound;
import dev.larattalabs.steward.module.ModuleCatalog;
import dev.larattalabs.steward.module.ModuleDev;
import dev.larattalabs.steward.module.ModuleGate;
import dev.larattalabs.steward.module.ModuleKind;
import dev.larattalabs.steward.module.ModuleOffer;
import dev.larattalabs.steward.module.ModuleOrder;
import dev.larattalabs.steward.module.ModuleRef;
import dev.larattalabs.steward.module.PaletteCheck;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Module offers in the world (docs/MODULES.md 4.1, 4.3, 4.4): which catalog modules may be offered, how the town would grow to take one, the
 * offer itself, and the check again at Accept. The decisions are {@link ModuleOffer}'s and {@link ProposalRules}'; this reads the world. Server
 * thread.
 *
 * <p><b>The source.</b> The live catalog ships in the jar ({@code steward/modules/}). In a development environment only, the dev hook
 * ({@code /steward dev modules <json>}) puts a catalog directory and one module id in its place, with fixtures allowed: M3's e2e offers M1's
 * {@code fixture_plot}, measured on Architect 0.62.0, as a measuring run does (its measurement certifies the catalog's shape, not a working
 * module); the dev JSON gives its bounds. Nothing of it reaches a player's game.
 */
public final class ModuleOffers {
	private ModuleOffers() {}

	/** The dev source: a catalog directory, the one module offered, whether its gate is a measuring one, and bounds for its params. */
	public record DevSource(Path dir, String id, boolean measuring, JsonArray bounds, boolean override) {}

	private static @Nullable DevSource devSource;
	private static @Nullable ModuleCatalog live;

	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> devSource = null);
	}

	/** Sets (or with null clears) the dev source. Development environment only. */
	public static String dev(@Nullable JsonObject req) {
		if (!ModuleDev.allowed()) return "The module source is changed only in a development environment.";
		if (req == null || !req.has("id")) {
			devSource = null;
			return "modules: the live catalog";
		}
		devSource = new DevSource(Path.of(req.get("dir").getAsString()), req.get("id").getAsString(), req.has("measuring") && req.get("measuring").getAsBoolean(),
			req.has("bounds") && req.get("bounds").isJsonArray() ? req.getAsJsonArray("bounds").deepCopy() : new JsonArray(),
			req.has("override") && req.get("override").getAsBoolean());
		return "modules: " + devSource.id() + " from " + devSource.dir() + (devSource.measuring() ? " (measuring gate)" : "");
	}

	/** The catalog modules are offered from now. */
	public static ModuleCatalog catalog() {
		DevSource d = devSource;
		if (d != null) return ModuleCatalog.dir(d.dir(), true);
		if (live == null) live = ModuleCatalog.live(Steward.class.getClassLoader());
		return live;
	}

	/** The gate's environment now (fixtures and measuring only from the dev source). */
	public static ModuleGate.Env env() {
		DevSource d = devSource;
		return ModuleDev.env(d != null, d != null && d.measuring());
	}

	/** The module ids that may be offered now, each with a kind Steward knows. */
	public static List<ModuleKind> kinds() {
		DevSource d = devSource;
		if (d != null) return ModuleKind.of(d.id()).map(List::of).orElse(List.of());
		List<ModuleKind> out = new ArrayList<>();
		for (var e : catalog().entries()) ModuleKind.of(e.id()).filter(k -> !out.contains(k)).ifPresent(out::add);
		return out;
	}

	/** The gate's verdict on a module's newest version. */
	public static ModuleGate.Verdict verdict(String id) {
		return ModuleGate.offer(catalog(), id, env());
	}

	/** The bound for these params in the measured Styles' blocks (no town): see {@link #bound(ModuleCatalog.Entry, JsonObject, Map)}. */
	public static @Nullable JsonObject bound(ModuleCatalog.Entry e, JsonObject params) {
		return bound(e, params, null);
	}

	/**
	 * The bound for these params: the measurement's, in the town's items ({@code town}: {@link #townItems}; null: as measured), else (dev
	 * source only) the dev JSON's.
	 */
	public static @Nullable JsonObject bound(ModuleCatalog.Entry e, JsonObject params, @Nullable Map<String, String> town) {
		JsonObject b = ModuleBound.of(e.measured(), params, town);
		DevSource d = devSource;
		// the dev source's bounds stand in for a measurement's only when it has none, or (dev, override) to hold a measured module's bill on purpose
		if (d == null || (b != null && !d.override())) return b;
		JsonObject raw = new JsonObject();
		raw.add(ModuleBound.MEMBER, d.bounds());
		return ModuleBound.of(new ModuleCatalog.Measured("", "", "", "", List.of(), null, null, false, raw), params, town);
	}

	/** The town's item for each role a measured bound re-expresses (StyleBound), from the settlement's bible; null when it can't be read. */
	public static @Nullable Map<String, String> townItems(Settlement s, ModuleCatalog.Entry e) {
		var b = s.bible();
		if (b == null || b.styleVersion() != s.styleVersion()) return null;
		var bible = ArchitectApi.get().bibles().get(b.id(), b.version());
		if (bible.isEmpty()) return null;
		var pc = PaletteCheck.check(e.palette(), bible.get().roles(), McBlockFacts.INSTANCE);
		return StyleBound.townItems(bible.get().roles(), pc.lamp(), ModuleOffers::itemOfBlock);
	}

	/** A block's own item in this game (a wall torch: the torch), or null when it has none. */
	static @Nullable String itemOfBlock(@Nullable String block) {
		if (block == null) return null;
		var id = net.minecraft.resources.Identifier.tryParse(block);
		if (id == null) return null;
		var b = BuiltInRegistries.BLOCK.getOptional(id);
		if (b.isEmpty()) return null;
		var item = b.get().asItem();
		if (item == net.minecraft.world.item.Items.AIR) return null;
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	/** The bound function for this settlement's offers. */
	public static java.util.function.BiFunction<ModuleCatalog.Entry, JsonObject, @Nullable JsonObject> bounds(Settlement s) {
		return (e, p) -> bound(e, p, townItems(s, e));
	}

	/** Whether a module of this id stands in the settlement (module identity: a standing site whose lot ext names it). */
	public static boolean standing(MinecraftServer server, Settlement s, String id) {
		for (var v : ArchitectApi.get().sites(server).list(s.owner())) {
			var ref = ModuleRef.of(v.ext());
			if (ref != null && ref.id().equals(id)) return true;
		}
		return false;
	}

	/** How the town would grow to take a module now (docs/MODULES.md 4.2, 4.3): the same checks the addition's survey makes, before anything is spent. */
	public static ModuleOffer.Town town(MinecraftServer server, Settlement s, @Nullable ServerPlayer p, boolean district) {
		var api = ArchitectApi.get();
		boolean worldSurvival = dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false);
		boolean regionMaterials = worldSurvival && server.getDefaultGameType() != net.minecraft.world.level.GameType.CREATIVE;
		ModuleOffer.Path path = ModuleOffer.Path.NONE;
		String why = null;
		boolean built = true;
		int percent = 100;
		ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
			net.minecraft.resources.Identifier.parse(s.claim().dimension())));
		boolean overworld = level != null && level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
		if (!api.features().contains(Growths.FEATURE)) why = "this Architect can't grow a town by revisions";
		else {
			var town = TownRegion.of(s).flatMap(id -> api.regions().get(id));
			if (town.isPresent()) {
				var v = town.get();
				boolean materials = Growths.fromMaterials(v);
				if (materials != regionMaterials) why = Growths.modeRefusal(s.name(), materials, regionMaterials, true, 100);
				else {
					path = materials ? ModuleOffer.Path.REGION_MATERIALS : ModuleOffer.Path.REGION;
					built = !materials || (v.build() != null && v.build().built());
					percent = v.build() == null ? 100 : (int) v.build().percent();
				}
			} else if (api.features().contains(Adoption.FEATURE) && s.difficulty() == dev.larattalabs.steward.model.Difficulty.PATRON && !worldSurvival && overworld
				&& !dev.larattalabs.steward.gateway.VPlan.isLegacy(s.id()) && Adoption.inventory(server, s).isPresent()) {
				path = ModuleOffer.Path.ADOPT;
			} else {
				why = "this street town can't be taken on as one region (it is built from materials, Supplied, or its street turns), and a working module is placed only by growing a region";
			}
		}
		boolean freezing = false;
		if (level != null) {
			BlockPos at = new BlockPos(s.claim().centerX(), level.getSeaLevel(), s.claim().centerZ());
			freezing = level.getBiome(at).value().coldEnoughToSnow(at, level.getSeaLevel());
		}
		Map<String, Integer> seeds = new HashMap<>();
		if (p != null) for (ItemStack st : p.getInventory()) if (!st.isEmpty()) seeds.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
		Map<String, Integer> stock = new HashMap<>();
		Stockpiles.of(server, s).ifPresent(sp -> sp.stock().forEach((item, n) -> stock.put(BuiltInRegistries.ITEM.getKey(item).toString(), n)));
		return new ModuleOffer.Town(path, why, built, percent, district, freezing, seeds, stock);
	}

	/** The lamp the settlement's bible gives the module, or why the bible is refused (PaletteCheck, before Architect is asked). */
	public record Lamp(boolean fallback, @Nullable String refused) {}

	public static Lamp lamp(Settlement s, ModuleCatalog.Entry e) {
		var b = s.bible();
		if (b == null || b.styleVersion() != s.styleVersion()) return new Lamp(false, null);
		var bible = ArchitectApi.get().bibles().get(b.id(), b.version());
		if (bible.isEmpty()) return new Lamp(false, null);
		var pc = PaletteCheck.check(e.palette(), bible.get().roles(), McBlockFacts.INSTANCE);
		if (!pc.ok()) return new Lamp(false, "the town's style can't dress it: " + String.join("; ", pc.broken()));
		return new Lamp(pc.lampFallback(), null);
	}

	/** The module offer for this decision: the lot as its building, the order as approved, the words. */
	public static Proposal proposal(ModuleKind kind, ModuleOffer.Decision d, Settlement s, boolean materials, boolean lampFallback, String key, String trigger, long now) {
		ModuleOrder o = d.order().withLamp(lampFallback);
		int[] f = kind.frame(o.params());
		var lot = new ConceptCard.Building(kind.role(), kind.lotType(), 1, "L", false, "A working module (" + o.moduleId() + "@" + o.version() + "), " + f[0] + " by " + f[1]
			+ ": its blocks come from Steward's measured catalog, not a design.", null);
		String title = Character.toUpperCase(kind.title().charAt(0)) + kind.title().substring(1);
		return Proposal.module(key, title, ModuleOffer.why(kind, d, materials, lampFallback), lot, now, trigger, o);
	}

	/** The catalog's modules as the proposal rules see them now: their kind, whether one stands, and their offer when eligible. */
	public static List<ProposalRules.ModuleCandidate> candidates(MinecraftServer server, ServerPlayer p, Settlement s, boolean district, long now) {
		List<ProposalRules.ModuleCandidate> out = new ArrayList<>();
		ModuleOffer.Town town = null;
		for (ModuleKind k : kinds()) {
			var v = verdict(k.id());
			if (town == null) town = town(server, s, p, district);
			var d = ModuleOffer.decide(k, v, town, null, bounds(s));
			Proposal offer = null;
			String why = d.why();
			if (d.ok()) {
				var lamp = lamp(s, v.entry());
				if (lamp.refused() != null) why = lamp.refused();
				else offer = proposal(k, d, s, town.path() == ModuleOffer.Path.REGION_MATERIALS, lamp.fallback(), k.rule(), "seeds", now);
			}
			if (offer == null) Steward.LOGGER.debug("module {} not offered to {}: {}", k.id(), s.id(), why);
			out.add(new ProposalRules.ModuleCandidate(k, standing(server, s, k.id()), offer, why));
		}
		return out;
	}

	/**
	 * At Accept (4.1): the module is checked again (the town may have changed). The offer stands only when it is still eligible, the catalog still
	 * offers the same version and program, and a town built from materials still has the bound the player approved. Null: it stands; else why not.
	 */
	public static @Nullable String recheck(MinecraftServer server, @Nullable ServerPlayer p, Settlement s, Proposal pr) {
		ModuleOrder o = pr.module();
		var kind = ModuleKind.of(o.moduleId());
		if (kind.isEmpty()) return "Steward no longer knows how to place " + o.moduleId() + ".";
		var v = verdict(o.moduleId());
		if (!v.ok()) return v.message();
		if (v.entry().version() != o.version() || !v.entry().programSha().equals(o.programSha()))
			return "the catalog's " + o.moduleId() + " has changed since it was offered (" + v.entry().key() + " now), so what you'd approve isn't what would be built";
		if (standing(server, s, o.moduleId())) return "a working " + kind.get().role() + " stands already";
		var town = town(server, s, p, pr.district() != null);
		// the params as offered: checked again against the envelope (the seeds may be gone, but the size was approved)
		var d = ModuleOffer.decide(kind.get(), v, town, o.params(), bounds(s));
		if (!d.ok()) return d.why();
		boolean materials = town.path() == ModuleOffer.Path.REGION_MATERIALS;
		if (materials && o.bound() == null) return "the town builds from materials now, and the offer had no bill to approve";
		if (!Canon.sorted(d.order().params()).equals(Canon.sorted(o.params()))) return "its size isn't one that was measured any more";
		var lamp = lamp(s, v.entry());
		if (lamp.refused() != null) return lamp.refused();
		return null;
	}

	/**
	 * The priced decorative substitute (4.1): a working module can't go here, so the old decorative rule's building is offered as its own proposal,
	 * at its own price, never a silent switch. Not when the player declined it, or it waits already. Returns what the steward says.
	 */
	public static String substitute(MinecraftServer server, ServerPlayer player, Settlement s, ModuleKind kind, String why) {
		String base = "A working " + kind.role() + " can't go here: " + why + ".";
		var now = Settlements.store().get(s.id()).orElse(s);
		for (String rule : kind.decorative().stream().sorted().toList()) {
			if (now.proposals().declined().contains(rule) || now.proposals().open().stream().anyMatch(x -> x.key().equals(rule))) continue;
			Proposal deco = ProposalRules.find(rule);
			if (deco == null) continue;
			int usd = Proposals.estimate(deco);
			deco = new Proposal(deco.key(), deco.title(), base + " A decorative " + deco.building().role() + " (designed, not working) would cost about $" + usd + ".", deco.building(),
				System.currentTimeMillis(), "a working one can't go here");
			var ps = now.proposals();
			List<Proposal> all = new ArrayList<>(ps.open());
			all.add(deco);
			if (!Settlements.proposals(s.id(), new Settlement.Proposals(all, ps.declined(), ps.accepted(), ps.lastAt())).ok()) return base;
			SettlementRunner.sendInbox(server, player.getUUID());
			return base + " A decorative " + deco.building().role() + " would cost about $" + usd + ": it waits in the inbox as its own proposal.";
		}
		return base;
	}

	/** A module offer that no longer stands leaves the inbox (not declined: offered again once it is eligible). */
	public static void withdraw(Settlement s, String key) {
		var now = Settlements.store().get(s.id()).orElse(s);
		var ps = now.proposals();
		if (ps.open().stream().noneMatch(x -> x.key().equals(key))) return;
		Settlements.proposals(s.id(), new Settlement.Proposals(ps.open().stream().filter(x -> !x.key().equals(key)).toList(), ps.declined(), ps.accepted(), ps.lastAt()));
	}

	/** The kind of an order (empty: Steward doesn't know it). */
	public static Optional<ModuleKind> kind(ModuleOrder o) {
		return ModuleKind.of(o.moduleId());
	}
}
