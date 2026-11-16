package dev.larattalabs.steward.service;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.module.ModuleBound;
import dev.larattalabs.steward.module.ModuleGate;
import dev.larattalabs.steward.module.ModuleKind;
import dev.larattalabs.steward.module.ModuleOffer;
import dev.larattalabs.steward.module.ModuleOrder;
import dev.larattalabs.steward.module.ModuleRef;
import dev.larattalabs.steward.module.StorageHallLot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /steward hall <settlement>} and {@code /steward hall <settlement> items <slot>=<key> ...} (MEASURE-M4 12.2, 12.4; the slot labels for v1,
 * rule 13): the slot map of each storage hall standing in the settlement, read from its lot's params, and the items of a waiting sorting-hall offer
 * changed before it is accepted (the dependent rule checked, the bound and the words made again). Server thread.
 */
public final class HallCommand {
	private HallCommand() {}

	/** Each standing hall's slot map: its slots' items, its input and overflow chests, in world positions. */
	public static Actions.Result list(MinecraftServer server, String id) {
		Optional<Settlement> s = Settlements.store().get(id);
		if (s.isEmpty()) return Actions.Result.fail("No such settlement: " + id + " (see /steward settlements).");
		List<String> lines = new ArrayList<>();
		for (SiteView v : ArchitectAccess.api().sites(server).list(s.get().owner())) {
			var ref = ModuleRef.of(v.ext());
			if (ref == null || !ModuleKind.StorageHall.ID.equals(ref.id())) continue;
			lines.add(describe(v, ref.params()));
		}
		for (Proposal p : s.get().proposals().open()) {
			ModuleOrder o = p.module();
			if (o != null && ModuleKind.StorageHall.ID.equals(o.moduleId())) lines.add("The offer waiting in the inbox (" + p.key() + "): " + slots(o.params())
				+ ". Change them with /steward hall " + id + " items <slot>=<item> ...");
		}
		if (lines.isEmpty()) return Actions.Result.ok("No storage hall stands in " + id + ", and none is offered.");
		return Actions.Result.ok(String.join("\n", lines));
	}

	static String describe(SiteView v, JsonObject params) {
		var cells = HallCargo.declaredCells(params, v.box(), v.rotation());
		StringBuilder b = new StringBuilder("Storage hall " + v.id() + ": " + slots(params));
		if (cells != null) {
			for (var e : cells.entrySet()) {
				var k = e.getValue().kind();
				if (k == StorageHallLot.Kind.INPUT) b.append("; the input chest at ").append(at(e.getKey()));
				else if (k == StorageHallLot.Kind.OVERFLOW) b.append("; overflow at ").append(at(e.getKey()));
				else if (k == StorageHallLot.Kind.SLOT) b.append("; slot ").append(e.getValue().slot() + 1).append(" at ").append(at(e.getKey()));
			}
		}
		return b.append('.').toString();
	}

	static String slots(JsonObject params) {
		var items = StorageHallLot.items(params).items();
		if (items.isEmpty()) return "a plain hall (no sorting)";
		List<String> out = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) out.add("slot " + (i + 1) + " " + ModuleBound.plain(items.get(i)));
		return String.join(", ", out);
	}

	private static String at(BlockPos p) {
		return p.getX() + ", " + p.getY() + ", " + p.getZ();
	}

	/**
	 * The waiting sorting-hall offer's items changed: {@code spec} is {@code <slot>=<key>} pairs ({@code 3=coal}). Refused when the result breaks the rule
	 * (distinct domain items for its slots), the catalog no longer offers the version, or the town builds from materials and no bound comes out.
	 */
	public static Actions.Result items(MinecraftServer server, ServerPlayer player, String id, String spec) {
		// (M4b GPT review 9) the offer is the host's: a LAN guest or a non-operator can't change its items or its bill
		String no = Actions.refusal(player);
		if (no != null) return Actions.Result.fail(no);
		Optional<Settlement> s = Settlements.store().get(id);
		if (s.isEmpty()) return Actions.Result.fail("No such settlement: " + id + " (see /steward settlements).");
		Proposal pr = null;
		for (Proposal p : s.get().proposals().open()) if (p.module() != null && ModuleKind.StorageHall.ID.equals(p.module().moduleId())) pr = p;
		if (pr == null) return Actions.Result.fail("No storage hall is offered in " + id + ".");
		ModuleOrder o = pr.module();
		int slots = o.params().has("slots") ? o.params().get("slots").getAsInt() : 0;
		if (slots == 0) return Actions.Result.fail("The hall offered in " + id + " is a plain hall: it sorts nothing.");
		JsonObject want = o.params().deepCopy();
		for (String part : spec.trim().split("\\s+")) {
			String[] kv = part.split("=", 2);
			int slot;
			try {
				slot = Integer.parseInt(kv[0]);
			} catch (NumberFormatException e) {
				return Actions.Result.fail("'" + part + "' is not <slot>=<item> (for example 3=coal).");
			}
			if (kv.length != 2 || slot < 1 || slot > slots) return Actions.Result.fail("'" + part + "': the hall has slots 1 to " + slots + ".");
			String key = kv[1].replaceFirst("^minecraft:", "");
			if (!StorageHallLot.FILTERABLE.contains(key)) return Actions.Result.fail("'" + key + "' can't be a slot's item: the hall sorts " + String.join(", ", StorageHallLot.FILTERABLE) + ".");
			want.addProperty("item" + slot, key);
		}
		var v = ModuleOffers.verdict(o.moduleId());
		if (!v.ok() || v.entry().version() != o.version() || !v.entry().programSha().equals(o.programSha())) return Actions.Result.fail("The catalog's storage hall has changed since it was offered.");
		var p = ModuleGate.params(v.entry(), want);
		if (!p.ok()) return Actions.Result.fail("Those items don't work: " + p.refused() + ".");
		var town = ModuleOffers.town(server, s.get(), player, pr.district());
		boolean materials = town.path().materials(); // (review 10) an adopted town built from materials is billed too
		JsonObject bound = materials ? ModuleOffers.bounds(s.get()).apply(v.entry(), p.values()) : null;
		if (materials && bound == null) return Actions.Result.fail("Its bill for those items wasn't measured, so there is no bound to approve.");
		var d = ModuleOffer.decide(ModuleKind.StorageHall.INSTANCE, v, town, p.values(), ModuleOffers.bounds(s.get()));
		if (!d.ok()) return Actions.Result.fail("The hall can't be offered with those items: " + d.why() + ".");
		if (!d.order().params().equals(p.values())) return Actions.Result.fail("The hall can't be offered with those items here (the steward would offer " + slots(d.order().params()) + ").");
		var lamp = ModuleOffers.lamp(s.get(), v.entry());
		// (review 10) the offer keeps its district: a district's hall is placed in that district
		Proposal next = ModuleOffers.proposal(ModuleKind.StorageHall.INSTANCE, d, s.get(), materials, lamp.fallback(), pr.key(), pr.trigger(), System.currentTimeMillis()).in(pr.district());
		var ps = s.get().proposals();
		List<Proposal> open = new ArrayList<>();
		for (Proposal x : ps.open()) open.add(x.key().equals(pr.key()) ? next : x);
		if (!Settlements.proposals(id, new Settlement.Proposals(open, ps.declined(), ps.accepted(), ps.lastAt())).ok()) return Actions.Result.fail("The offer couldn't be saved.");
		SettlementRunner.sendInbox(server, player.getUUID());
		return Actions.Result.ok("The hall's offer now sorts " + slots(p.values()) + (bound == null ? "." : ". " + dev.larattalabs.steward.inbox.InboxModel.BILL + ModuleBound.bill(bound, Integer.MAX_VALUE) + "."));
	}
}
