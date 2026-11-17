package dev.larattalabs.steward.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Module lots of a composed plan (docs/MODULES.md "M5 design", slice M5): the composer's vocabulary (which modules it may name and how), the lots a
 * plan names, the order each gets (or why not, in words), the lot as Steward builds it, its geometry from the approved artifact, and its share of a
 * quote. Pure: the caller reads the catalog, the gate and the world.
 */
public final class ComposedModules {
	private ComposedModules() {}

	/** The plan lot's ext key the composer writes ({@code {id, params, for}}); Steward's built lot holds the full {@link ModuleRef} under it. */
	public static final String EXT = ModuleRef.EXT;

	/** A module the composer may name: its kind and the catalog entry the gate offers now. */
	public record Offered(ModuleKind kind, ModuleCatalog.Entry entry) {}

	/**
	 * The hints' {@code modules}: each offered module's id, lot type, role, title, whether it is watered and needs a warm biome, its sizes (the size
	 * params and the lot's {@code [W, D]}) and its one free choice (the crop, the bulk chests), from the catalog's measured branches. Empty when
	 * nothing is offered. Pure.
	 */
	public static JsonArray vocabulary(List<Offered> offered) {
		JsonArray out = new JsonArray();
		for (Offered o : offered) {
			ModuleKind k = o.kind();
			JsonObject m = new JsonObject();
			m.addProperty("id", k.id());
			m.addProperty("lotType", k.lotType());
			m.addProperty("role", k.role());
			m.addProperty("title", k.title());
			m.addProperty("irrigate", k.irrigate());
			m.addProperty("warmOnly", k.warmOnly());
			m.addProperty("does", does(k, o.entry()));
			List<JsonElement> choices = o.entry().envelope().branches().getOrDefault(k.cropParam(), List.of());
			String first = choices.isEmpty() ? null : choices.get(0).getAsString();
			JsonArray sizes = new JsonArray();
			for (JsonObject s : k.sizes()) {
				int[] f;
				try {
					f = k.frame(first == null ? s : k.params(s, first));
				} catch (RuntimeException e) {
					continue;
				}
				JsonObject size = new JsonObject();
				size.add("params", Canon.sorted(s));
				JsonArray wd = new JsonArray();
				wd.add(f[0]);
				wd.add(f[1]);
				size.add("size", wd);
				sizes.add(size);
			}
			m.add("sizes", sizes);
			JsonObject choose = new JsonObject();
			choose.addProperty("param", k.cropParam());
			JsonArray values = new JsonArray();
			for (JsonElement v : choices) values.add(v.deepCopy());
			choose.add("values", values);
			m.add("choose", choose);
			out.add(m);
		}
		return out;
	}

	/**
	 * Whether a composed town's hall may be a sorting hall. Not yet (batch-074 GPT review, the manager's call): a composed hall is built from the
	 * kind's sizes, which are the plain hall's ({@code slots 0}), and its filter items aren't chosen, so sorting in a planned town is slice M5b's
	 * (FK-aware cargo). Until then the composed vocabulary and words say a plain hall, and a plan asking for sorting gets the plain hall, said.
	 */
	public static final boolean COMPOSED_HALL_SORTS = false;

	/** The words of a plain hall, as the composer and the player read them. */
	static final String PLAIN_HALL = "a storage hall with an input chest by the door and double chests along its back wall; it does not sort";

	/**
	 * What a module does, in the composer's words, so it is written only for what it does (GPT review 10): the crop farm is harvested by the player;
	 * a composed hall is a plain hall that does not sort, whatever its catalog version measured ({@link #COMPOSED_HALL_SORTS}). Pure.
	 */
	public static String does(ModuleKind k, ModuleCatalog.Entry e) {
		if (k.sign() == ModuleKind.Sign.STORES) {
			boolean sorts = false;
			if (COMPOSED_HALL_SORTS)
				for (JsonElement v : e.envelope().branches().getOrDefault("slots", List.of())) if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsInt() > 0) sorts = true;
			return sorts ? "a storage hall with an input chest by the door that sorts the items it is given into their own chests" : PLAIN_HALL;
		}
		if (k.irrigate()) return "a watered, lit field of one crop; the player harvests and replants it (no automatic harvest)";
		return k.title();
	}

	/**
	 * What the vocabulary names, for the compose key: each offered module's id, version and program sha, sorted by id. A catalog change under the same
	 * card is another compose (Architect refuses a key with another body). Empty string when nothing is offered. Pure.
	 */
	public static String identity(List<Offered> offered) {
		List<String> ids = new ArrayList<>();
		for (Offered o : offered) ids.add(o.entry().id() + "@" + o.entry().version() + ":" + o.entry().programSha());
		ids.sort(String::compareTo);
		return String.join(",", ids);
	}

	/** A module lot a plan names: its lot id, the module id, the params as written (may be empty), and the card type it stands for (or null). */
	public record Asked(String lotId, String moduleId, JsonObject params, @Nullable String forType) {}

	/** Every lot of {@code lots} whose ext names a module ({@code steward_mc:module} with an {@code id}), in order. Pure. */
	public static List<Asked> read(List<JsonObject> lots) {
		List<Asked> out = new ArrayList<>();
		for (JsonObject l : lots) {
			Asked a = asked(l);
			if (a != null) out.add(a);
		}
		return out;
	}

	/** The module id an ask names when its marker can't be read: such a lot is refused, never built as an ordinary (designed) lot. */
	public static final String UNREADABLE = "";

	/**
	 * The module a plan lot names, or null when it names none. Fails closed (GPT review 3): a lot whose ext has {@code steward_mc:module} at all, or
	 * whose type is a module's lot type ({@code lotTypes}), is a module ask; one whose id can't be read asks for {@link #UNREADABLE}, which
	 * {@link #decide} refuses, so it is left out rather than designed.
	 */
	public static @Nullable Asked asked(JsonObject lot, Set<String> lotTypes) {
		if (!lot.has("id") || !lot.get("id").isJsonPrimitive()) return null;
		JsonObject ext = lot.has("ext") && lot.get("ext").isJsonObject() ? lot.getAsJsonObject("ext") : null;
		boolean marked = ext != null && ext.has(EXT);
		String type = lot.has("type") && lot.get("type").isJsonPrimitive() ? lot.get("type").getAsString() : "";
		if (!marked) return lotTypes.contains(type) ? new Asked(lot.get("id").getAsString(), moduleOfType(type), new JsonObject(), null) : null;
		if (!ext.get(EXT).isJsonObject()) return new Asked(lot.get("id").getAsString(), UNREADABLE, new JsonObject(), null);
		Asked a = asked(lot);
		return a != null ? a : new Asked(lot.get("id").getAsString(), UNREADABLE, new JsonObject(), null);
	}

	/** The module id of a module lot type (the kinds Steward knows), or {@link #UNREADABLE}. */
	static String moduleOfType(String type) {
		for (String id : List.of(ModuleKind.CropFarm.ID, ModuleKind.StorageHall.ID)) if (ModuleKind.of(id).map(ModuleKind::lotType).filter(type::equals).isPresent()) return id;
		return UNREADABLE;
	}

	/** The lot types of the modules Steward knows ({@code crop_farm}, {@code storage_hall}): a lot of one of these types is read as a module ask. */
	public static final Set<String> LOT_TYPES = Set.of(ModuleKind.CropFarm.INSTANCE.lotType(), ModuleKind.StorageHall.INSTANCE.lotType());

	/** Every module ask of {@code lots}, failing closed ({@link #asked(JsonObject, Set)} over {@link #LOT_TYPES}). Pure. */
	public static List<Asked> readAll(List<JsonObject> lots) {
		return readAll(lots, LOT_TYPES);
	}

	/**
	 * Every module ask of {@code lots}: a lot with a module marker (readable or not), or a lot of one of {@code lotTypes} (the modules offered now: a bare
	 * {@code storage_hall} lot of a town whose catalog offers none is an ordinary lot, as before M5). Pure.
	 */
	public static List<Asked> readAll(List<JsonObject> lots, Set<String> lotTypes) {
		List<Asked> out = new ArrayList<>();
		for (JsonObject l : lots) {
			Asked a = asked(l, lotTypes);
			if (a != null) out.add(a);
		}
		return out;
	}

	/** The module a plan lot's marker names when it is readable, or null (no ext, no id, or not a string id). */
	public static @Nullable Asked asked(JsonObject lot) {
		if (!lot.has("id") || !lot.get("id").isJsonPrimitive()) return null;
		JsonObject ext = lot.has("ext") && lot.get("ext").isJsonObject() ? lot.getAsJsonObject("ext") : null;
		if (ext == null || !ext.has(EXT) || !ext.get(EXT).isJsonObject()) return null;
		JsonObject m = ext.getAsJsonObject(EXT);
		if (!m.has("id") || !m.get("id").isJsonPrimitive() || !m.get("id").getAsJsonPrimitive().isString()) return null;
		String id = m.get("id").getAsString().strip().toLowerCase(Locale.ROOT);
		if (id.isEmpty()) return null;
		JsonObject params = m.has("params") && m.get("params").isJsonObject() ? m.getAsJsonObject("params").deepCopy() : new JsonObject();
		String forType = m.has("for") && m.get("for").isJsonPrimitive() && !m.get("for").getAsString().isBlank()
			? m.get("for").getAsString().strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "") : null;
		return new Asked(lot.get("id").getAsString(), id, params, forType == null || forType.isEmpty() ? null : forType);
	}

	/** What the build sees of the town when it decides: the biome freezes water; it is built from materials. */
	public record Town(boolean freezing, boolean materials) {}

	/**
	 * A decision: an order ({@code why} null) with {@code changed} the words for params Steward chose over the plan's, or why not, in the steward's
	 * words (a whole sentence about the module, without the "left out" tail the caller adds).
	 */
	public record Decision(@Nullable ModuleOrder order, @Nullable String why, @Nullable String changed) {
		public boolean ok() {
			return why == null && order != null;
		}

		static Decision no(String why) {
			return new Decision(null, why, null);
		}
	}

	/**
	 * The order for an asked lot, or why it can't be built (docs/MODULES.md "M5 design" 2): a kind Steward knows, the gate's verdict now, one lot of each
	 * module ({@code taken}: the ids given an order already), a warm biome for a warm-only module, and from materials a measured bound for its params.
	 * The params: as written when they are a measured branch; else the measured size whose frame is the lot's size either way round
	 * ({@code lotSize}, null when the lot has none), else the smallest; its choice (crop, bulk) as written when measured, else the kind's default.
	 * {@code bounds}: the measured bound for an entry's params (as measured; the town's blocks are applied at the bind). Pure.
	 */
	public static Decision decide(Asked a, @Nullable ModuleKind kind, ModuleGate.Verdict verdict, Town town, Set<String> taken, int @Nullable [] lotSize,
		BiFunction<ModuleCatalog.Entry, JsonObject, @Nullable JsonObject> bounds) {
		return decide(a, kind, verdict, town, taken, lotSize, bounds, MATERIALS_WAIT_FOR_LOT_CAP);
	}

	/**
	 * (batch review blocker, the manager's rule-13 call, 2026-11-15; docs/MODULES.md "M5 design", "The guard") The single guard: while it is on, a composed
	 * module lot in a town built from materials is left out of the town, said. Architect 0.72.0's {@code realiseApproved} takes no cap scoped to a lot,
	 * so an inexact quote the player approved could consume past the module's measured bound while it builds, and the steward's "its measured limit"
	 * would not be enforced; a region-wide {@code maxBill} would outlive its revision (BC-d) until v0.73.0. It comes off when a cap scoped to the module
	 * lands (QD after BC-d, S-M5-16); the path behind it is kept and unit-tested. A town built at once is unchanged.
	 */
	public static final boolean MATERIALS_WAIT_FOR_LOT_CAP = true;

	/** As above, with the guard given (unit tests of the path behind it). */
	static Decision decide(Asked a, @Nullable ModuleKind kind, ModuleGate.Verdict verdict, Town town, Set<String> taken, int @Nullable [] lotSize,
		BiFunction<ModuleCatalog.Entry, JsonObject, @Nullable JsonObject> bounds, boolean materialsGuard) {
		String name = kind == null ? a.moduleId().replace('_', ' ') : kind.role();
		if (a.moduleId().equals(UNREADABLE)) return Decision.no("the plan wrote it as a working module Steward couldn't read");
		if (kind == null) return Decision.no("Steward has no working " + name + " in its catalog");
		if (!verdict.ok() || verdict.entry() == null) return Decision.no("a working " + name + " can't be built here: " + verdict.message());
		if (town.materials() && materialsGuard)
			return Decision.no("a working " + name + " can't be held to its measured limit in a town built from materials yet; it's left out for now");
		if (taken.contains(kind.id())) return Decision.no("the plan has a working " + name + " already, and a town gets one of each for now");
		if (kind.warmOnly() && town.freezing()) return Decision.no("a working " + name + " can't be built here: the water would freeze in this biome");
		var e = verdict.entry();
		List<String> notes = new ArrayList<>();
		// (batch-074 review) a hall asked to sort (slots other than 0, or filter items): the plain hall, said; the sizes below are the plain hall's
		if (kind.sign() == ModuleKind.Sign.STORES && !COMPOSED_HALL_SORTS && asksToSort(a.params()))
			notes.add("a planned town's storage hall doesn't sort yet, so a plain hall");
		// the choice (crop or bulk): as written when it is a measured branch, else the default
		List<JsonElement> choices = e.envelope().branches().getOrDefault(kind.cropParam(), List.of());
		JsonElement want = a.params().get(kind.cropParam());
		JsonElement choice = want != null && choices.contains(want) ? want : null;
		if (choice == null) {
			var p = e.params().get(kind.cropParam());
			choice = p != null && p.def() != null && (choices.isEmpty() || choices.contains(p.def())) ? p.def() : choices.isEmpty() ? null : choices.get(0);
			if (want != null && choice != null) notes.add(kind.cropParam() + " " + plain(want) + " isn't measured, so " + plain(choice));
		}
		if (choice == null) return Decision.no("a working " + name + " has no measured " + kind.cropParam());
		// the size: as written, else the size whose frame is the lot's, else the smallest
		JsonObject size = null;
		JsonObject asked = kind.sizeOf(a.params());
		for (JsonObject s : kind.sizes()) if (asked.size() > 0 && Canon.sorted(s).equals(Canon.sorted(asked))) size = s;
		if (size == null && lotSize != null) {
			for (JsonObject s : kind.sizes()) {
				int[] f = frameOf(kind, s, choice);
				if (f != null && ((f[0] == lotSize[0] && f[1] == lotSize[1]) || (f[0] == lotSize[1] && f[1] == lotSize[0]))) { size = s; break; }
			}
		}
		if (size == null) {
			List<JsonObject> all = kind.sizes();
			if (all.isEmpty()) return Decision.no("a working " + name + " has no measured size");
			size = all.get(all.size() - 1);
			if (asked.size() > 0 || lotSize != null) notes.add("the size asked isn't measured, so the smallest");
		}
		JsonObject params = size.deepCopy();
		params.add(kind.cropParam(), choice.deepCopy());
		var p = ModuleGate.params(e, params);
		if (!p.ok()) return Decision.no("a working " + name + "'s size isn't one that was measured: " + p.refused());
		if (town.materials() && bounds.apply(e, p.values()) == null)
			return Decision.no("a working " + name + "'s bill from the stockpile wasn't measured for that size, so there is no bound to hold it to");
		var order = new ModuleOrder(e.id(), e.version(), e.programSha(), p.values(), null, ModuleOffer.rate(e.measured(), p.values()), false, false);
		return new Decision(order, null, notes.isEmpty() ? null : String.join("; ", notes));
	}

	/** Whether a hall's params as the plan wrote them ask for sorting: a slots member other than the whole number 0, or any filter item but none. */
	static boolean asksToSort(JsonObject params) {
		for (var en : params.entrySet()) {
			if (en.getKey().equals("slots")) {
				Integer n = ModuleRef.exactInt(en.getValue());
				if (n == null || n != 0) return true;
			} else if (en.getKey().matches("item\\d+") && !(en.getValue().isJsonPrimitive() && "none".equals(en.getValue().getAsString()))) return true;
		}
		return false;
	}

	private static int @Nullable [] frameOf(ModuleKind kind, JsonObject size, JsonElement choice) {
		try {
			JsonObject p = size.deepCopy();
			p.add(kind.cropParam(), choice.deepCopy());
			return kind.frame(p);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String plain(JsonElement v) {
		return v.isJsonPrimitive() ? v.getAsString().replace('_', ' ') : v.toString();
	}

	/** A plan lot's {@code size} {@code [W, D]}, or null when it has none (or not two integers). */
	public static int @Nullable [] size(JsonObject lot) {
		if (!lot.has("size") || !lot.get("size").isJsonArray() || lot.getAsJsonArray("size").size() != 2) return null;
		JsonArray s = lot.getAsJsonArray("size");
		Integer w = ModuleRef.exactInt(s.get(0)), d = ModuleRef.exactInt(s.get(1));
		return w == null || d == null ? null : new int[] {w, d};
	}

	/**
	 * The lot as the build builds it: the kind's own lot for the order ({@code ModuleKind.growLot}: type, exact size, {@code irrigate} and FC's
	 * {@code field} where the kind has them), the plan lot's id, role, label, brief, landmark and placement kept, its ext kept but for the module
	 * member, which is the order's full {@link ModuleRef}. Pure.
	 */
	public static JsonObject builtLot(JsonObject planLot, ModuleKind kind, ModuleOrder order) {
		String id = planLot.get("id").getAsString();
		JsonObject out = kind.growLot(id, order.params());
		for (String k : List.of("role", "label", "brief", "landmark", "placement")) if (planLot.has(k)) out.add(k, planLot.get(k).deepCopy());
		JsonObject ext = planLot.has("ext") && planLot.get("ext").isJsonObject() ? planLot.getAsJsonObject("ext").deepCopy() : new JsonObject();
		ext.remove(EXT);
		out.add("ext", order.ref().into(ext));
		return out;
	}

	/**
	 * The plan as built: each lot with an order replaced by {@link #builtLot}, each lot in {@code leftOut} removed from its zone (or district), a zone or
	 * district left with no lot keeping its other members. A copy; deterministic. Pure.
	 */
	public static JsonObject apply(JsonObject plan, Map<String, ModuleOrder> orders, Set<String> leftOut) {
		JsonObject out = plan.deepCopy();
		if (!out.has("zones") || !out.get("zones").isJsonArray()) return out;
		for (JsonElement z : out.getAsJsonArray("zones")) {
			if (!z.isJsonObject() || !z.getAsJsonObject().has("program") || !z.getAsJsonObject().get("program").isJsonObject()) continue;
			JsonObject program = z.getAsJsonObject().getAsJsonObject("program");
			if (program.has("districts") && program.get("districts").isJsonArray()) {
				for (JsonElement d : program.getAsJsonArray("districts")) if (d.isJsonObject()) rewrite(d.getAsJsonObject(), "lots", orders, leftOut);
				continue;
			}
			if (program.has("lots") && program.get("lots").isJsonArray()) rewrite(program, "lots", orders, leftOut);
			else if (program.has("params") && program.get("params").isJsonObject()) rewrite(program.getAsJsonObject("params"), "lots", orders, leftOut);
		}
		return out;
	}

	private static void rewrite(JsonObject holder, String member, Map<String, ModuleOrder> orders, Set<String> leftOut) {
		if (!holder.has(member) || !holder.get(member).isJsonArray()) return;
		JsonArray next = new JsonArray();
		for (JsonElement l : holder.getAsJsonArray(member)) {
			if (!l.isJsonObject() || !l.getAsJsonObject().has("id")) { next.add(l); continue; }
			JsonObject lot = l.getAsJsonObject();
			String id = lot.get("id").getAsString();
			if (leftOut.contains(id)) continue;
			ModuleOrder o = orders.get(id);
			var kind = o == null ? java.util.Optional.<ModuleKind>empty() : ModuleKind.of(o.moduleId());
			next.add(kind.isPresent() ? builtLot(lot, kind.get(), o) : lot);
		}
		holder.add(member, next);
	}

	/** An artifact lot's front ({@code south} when it names none). */
	public static String front(JsonObject artifactLot) {
		String f = artifactLot.has("front") && artifactLot.get("front").isJsonPrimitive() ? artifactLot.get("front").getAsString().toLowerCase(Locale.ROOT) : "south";
		return switch (f) {
			case "north", "east", "west" -> f;
			default -> "south";
		};
	}

	/** The artifact lot's box {@code [x0, y0, z0, x1, y1, z1]}, or null when it has none. */
	static int @Nullable [] box(JsonObject artifactLot) {
		if (!artifactLot.has("box") || !artifactLot.get("box").isJsonArray() || artifactLot.getAsJsonArray("box").size() != 6) return null;
		int[] b = new int[6];
		for (int i = 0; i < 6; i++) {
			Integer v = ModuleRef.exactInt(artifactLot.getAsJsonArray("box").get(i));
			if (v == null) return null;
			b[i] = v;
		}
		return b;
	}

	/** The artifact lot's frame {@code [W, D]} seen from its front (its front to the south): the box's spans, turned for an east or west front. */
	public static int @Nullable [] frame(JsonObject artifactLot) {
		int[] b = box(artifactLot);
		if (b == null) return null;
		int xs = b[3] - b[0] + 1, zs = b[5] - b[2] + 1;
		String f = front(artifactLot);
		return f.equals("east") || f.equals("west") ? new int[] {zs, xs} : new int[] {xs, zs};
	}

	/**
	 * Why the artifact lot can't hold this module's frame, or null: a box that isn't exactly the frame (W x D from its front), or an irrigation
	 * frame that differs from it (the variant is made for that frame; Architect's S-M5-14). Pure.
	 */
	public static @Nullable String frameProblem(JsonObject artifactLot, int[] moduleFrame) {
		int[] f = frame(artifactLot);
		if (f == null) return "the plan gave its lot no box";
		if (f[0] != moduleFrame[0] || f[1] != moduleFrame[1])
			return "its lot is " + f[0] + " by " + f[1] + ", and the module is made for exactly " + moduleFrame[0] + " by " + moduleFrame[1];
		JsonObject irr = irrigation(artifactLot);
		if (irr != null && irr.has("frame") && irr.get("frame").isJsonArray() && irr.getAsJsonArray("frame").size() == 2) {
			Integer w = ModuleRef.exactInt(irr.getAsJsonArray("frame").get(0)), d = ModuleRef.exactInt(irr.getAsJsonArray("frame").get(1));
			if (w == null || d == null || w != moduleFrame[0] || d != moduleFrame[1]) return "its water was laid for another frame than the module's";
		}
		return null;
	}

	/**
	 * Why the artifact lot can't hold this module, or null (GPT review 4): its frame ({@link #frameProblem}); a watered module's lot without IRR's
	 * water; a lot Architect sites on the land or on a wall's line (a module lot is a plain lot). Checked before any design is paid for, and again at the
	 * bind. Pure.
	 */
	public static @Nullable String lotProblem(JsonObject artifactLot, ModuleKind kind, ModuleOrder order) {
		if (artifactLot.has("site") || artifactLot.has("onWall")) return "Architect placed its lot on the land's own features or a wall, and a module lot is a plain lot";
		String f = frameProblem(artifactLot, kind.frame(order.params()));
		if (f != null) return f;
		JsonObject irr = irrigation(artifactLot);
		if (kind.irrigate() && (irr == null || !irr.has("lotCells") || !irr.get("lotCells").isJsonArray() || irr.getAsJsonArray("lotCells").isEmpty()))
			return "Architect couldn't water its field there";
		return null;
	}

	/** IRR's record on the artifact lot ({@code lots[].irrigation}), or null. */
	public static @Nullable JsonObject irrigation(JsonObject artifactLot) {
		return artifactLot.has("irrigation") && artifactLot.get("irrigation").isJsonObject() ? artifactLot.getAsJsonObject("irrigation") : null;
	}

	/**
	 * The artifact lot's geometry in M3's canonical shape ({@code ModulePlacement.geometry}): box, floor (the box's floor), front, frame (IRR's, else
	 * the box's from its front) and the irrigation cells. A re-check that moves any of it makes the variant again. Null when it has no box. Pure.
	 */
	public static @Nullable JsonObject geometry(JsonObject artifactLot) {
		int[] b = box(artifactLot);
		if (b == null) return null;
		JsonObject g = new JsonObject();
		JsonArray box = new JsonArray();
		for (int v : b) box.add(v);
		g.add("box", box);
		g.addProperty("floorY", b[1]);
		g.addProperty("front", front(artifactLot));
		JsonObject irr = irrigation(artifactLot);
		JsonArray frame = new JsonArray();
		if (irr != null && irr.has("frame") && irr.get("frame").isJsonArray()) frame = irr.getAsJsonArray("frame").deepCopy();
		else for (int v : frame(artifactLot)) frame.add(v);
		g.add("frame", frame);
		if (irr != null) {
			JsonObject cells = new JsonObject();
			if (irr.has("lotCells")) cells.add("lotCells", irr.get("lotCells").deepCopy());
			if (irr.has("cells")) cells.add("cells", irr.get("cells").deepCopy());
			g.add("irrigation", cells);
		}
		return Canon.sorted(g).getAsJsonObject();
	}

	/**
	 * A module lot's share of a quote: its own pieces of {@code bomParts} ({@code lot:<id>}, the bound entry's construction bill, and {@code fill:<id>},
	 * FK's filled containers), summed per item; empty when the quote has neither. Null when a piece isn't a bill of items. Pure.
	 */
	public static @Nullable JsonObject share(JsonObject bomParts, String lotId) {
		Map<String, Integer> sum = new java.util.TreeMap<>();
		for (String piece : List.of("lot:" + lotId, "fill:" + lotId)) {
			if (!bomParts.has(piece)) continue;
			if (!bomParts.get(piece).isJsonObject()) return null;
			JsonObject bill = ModuleBound.clean(bomParts.getAsJsonObject(piece));
			if (bill == null) return null;
			for (var e : bill.entrySet()) sum.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum);
		}
		JsonObject out = new JsonObject();
		sum.forEach(out::addProperty);
		return out;
	}

	/** Whether the quote billed this lot at all (a {@code lot:<id>} piece): a module lot the quote doesn't name is not held to anything. Pure. */
	public static boolean billed(JsonObject bomParts, String lotId) {
		return bomParts.has("lot:" + lotId);
	}

	/**
	 * What a quote holds the module lots to: the orders (each holding its share when asked, cleared otherwise), and either why the quote can't hold
	 * them ({@code refused}), or the question for the player ({@code ask}), or neither (the realise goes on).
	 */
	public record Held(Map<String, ModuleOrder> orders, @Nullable String refused, @Nullable String ask, @Nullable String said) {}

	/**
	 * (docs/MODULES.md "M5 design" 6) Each module lot's share of a quote ({@link #share}) against its bound ({@link ModuleBound#over}, every item exact).
	 * A lot the quote doesn't bill, a share that isn't a bill or an order with no bound refuses. Past a bound, or a quote that may differ
	 * ({@code inexact}: {@code ModulePlacement.QuoteCheck.ask}) while some order hasn't had an inexact bill approved, every order holds its share for the
	 * player's word ({@link ModuleOrder#holding}; approving makes each share its bound). Pure.
	 */
	public static Held held(JsonObject bomParts, Map<String, ModuleOrder> orders, @Nullable String inexact) {
		Map<String, JsonObject> shares = new LinkedHashMap<>();
		List<String> over = new ArrayList<>(), bills = new ArrayList<>();
		boolean unapproved = false;
		for (var e : orders.entrySet()) {
			String name = ModuleKind.of(e.getValue().moduleId()).map(ModuleKind::role).orElse(e.getKey().replace('_', ' '));
			if (!billed(bomParts, e.getKey())) return new Held(orders, "Architect's quote doesn't bill the working " + name + "'s lot", null, null);
			JsonObject share = share(bomParts, e.getKey());
			if (share == null) return new Held(orders, "Architect's quote of the working " + name + " is not a bill of items", null, null);
			if (e.getValue().bound() == null) return new Held(orders, "the working " + name + " has no bound to hold its bill to", null, null);
			shares.put(e.getKey(), share);
			var ov = ModuleBound.over(share, e.getValue().bound());
			if (!ov.isEmpty()) over.add("the " + name + "'s " + ModuleBound.words(ov, 100));
			if (!e.getValue().inexactApproved()) unapproved = true;
			bills.add("the " + name + ": " + (share.isEmpty() ? "nothing" : ModuleBound.bill(share, 1000)));
		}
		boolean ask = !over.isEmpty() || (inexact != null && unapproved);
		Map<String, ModuleOrder> next = new LinkedHashMap<>();
		for (var e : orders.entrySet()) next.put(e.getKey(), e.getValue().holding(ask ? shares.get(e.getKey()) : null));
		if (!ask) return new Held(next, null, null, "From materials, " + String.join("; ", bounded(orders, shares)) + ".");
		String why = !over.isEmpty() ? "The working modules' bills came to more than their measured bounds: " + String.join("; ", over) + "."
			: "Architect can't quote the working modules' bills exactly (" + inexact + "), so I ask first.";
		return new Held(next, null, why + " Their bills: " + String.join("; ", bills) + ".", null);
	}

	/** Each module's bill with its limit, for the words (Noah, 2026-11-15: the limit is shown with the quote). */
	private static List<String> bounded(Map<String, ModuleOrder> orders, Map<String, JsonObject> shares) {
		List<String> out = new ArrayList<>();
		for (var e : orders.entrySet()) {
			String name = ModuleKind.of(e.getValue().moduleId()).map(ModuleKind::role).orElse(e.getKey().replace('_', ' '));
			JsonObject share = shares.get(e.getKey());
			out.add("the working " + name + " takes " + (share == null || share.isEmpty() ? "nothing" : ModuleBound.bill(share, 1000)) + " (its measured limit: "
				+ ModuleBound.bill(e.getValue().bound(), 1000) + ")");
		}
		return out;
	}

	/** What the steward says of a module lot left out of the town ({@code name}: the lot's own words; {@code why}: {@link Decision#why}). Pure. */
	public static String leftOutWords(String name, String why) {
		String w = why.endsWith(".") ? why.substring(0, why.length() - 1) : why;
		return "The plan's " + name + " is left out of the town: " + w + ". It isn't designed as an ordinary building in its place.";
	}

	/** What the steward says of a module lot the town will build from the catalog, with the params it chose over the plan's, and in survival its bound's rule. Pure. */
	public static String boundWords(String name, ModuleKind kind, ModuleOrder o, @Nullable String changed, boolean materials) {
		int[] f = kind.frame(o.params());
		return "The plan's " + name + " is a working " + kind.role() + " from Steward's measured catalog (" + o.moduleId() + "@" + o.version() + ", " + f[0] + " by " + f[1]
			+ (o.rate() == null ? "" : ", " + o.rate()) + "): no design, $0." + (changed == null ? "" : " (" + changed + ".)")
			+ (kind.sign() == ModuleKind.Sign.STORES && !sorts(o) ? " It's a plain hall: it doesn't sort." : "")
			+ (materials ? " From materials, its bill is held to its measured bound: if it would take more, I ask first." : "")
			+ (ModuleOffer.namesLateCargo(kind) ? " " + ModuleOffer.LATE_CARGO_WORDS : "");
	}

	/** Whether an order's hall sorts (its slots a whole number above 0). */
	private static boolean sorts(ModuleOrder o) {
		Integer n = ModuleRef.exactInt(o.params().get("slots"));
		return n != null && n > 0;
	}

	/** The orders keyed by lot id, in the plan's lot order (what is saved with the build). */
	public static Map<String, ModuleOrder> ordered(List<Asked> asked, Map<String, ModuleOrder> orders) {
		Map<String, ModuleOrder> out = new LinkedHashMap<>();
		for (Asked a : asked) if (orders.containsKey(a.lotId())) out.put(a.lotId(), orders.get(a.lotId()));
		return out;
	}
}
