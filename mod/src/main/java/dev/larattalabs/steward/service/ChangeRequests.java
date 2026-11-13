package dev.larattalabs.steward.service;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.BlockSize;
import dev.larattalabs.architect.api.Design;
import dev.larattalabs.architect.api.DesignRequest;
import dev.larattalabs.architect.api.SiteEvents;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.LotBrief;
import dev.larattalabs.steward.model.Settlement;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Change requests (docs/PLAN.md phase 3c): free text about a standing building becomes the next version of its design (Architect 0b's
 * {@code DesignRequest.versionOf}, which installs head + 1). From there Steward's updates take over: the new version is offered in the inbox (or applied,
 * by the permission level), previewed as a delta ghost, applied keeping the player's edits, and revertible. A change is paid as one design and always
 * waits for the player. Until Architect ships {@code versionOf} (it is found by reflection), changes are noted in the history instead. Server thread.
 */
public final class ChangeRequests {
	/** A change design in flight: whose, which site, who asked. */
	private record Pending(String settlementId, String siteId, String building, UUID player, int session) {}

	private static final Map<String, Pending> PENDING = new HashMap<>();
	/** Sites with a change design in flight (one at a time per building). */
	private static final Map<String, String> BY_SITE = new HashMap<>();
	private static volatile @Nullable MinecraftServer server;
	private static final @Nullable Method VERSION_OF = find();

	private ChangeRequests() {
	}

	private static @Nullable Method find() {
		try {
			return DesignRequest.class.getMethod("versionOf", String.class, String.class);
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	/** Whether this Architect can design a building's next version (0b's {@code versionOf}). */
	public static boolean available() {
		return VERSION_OF != null;
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			server = null;
			PENDING.clear();
			BY_SITE.clear();
		});
		SiteEvents.DESIGN_DONE.register(d -> {
			var srv = server;
			if (srv != null) onDone(srv, d);
		});
	}

	/** The settlement's building a player named ("the tavern"): its site, by role or lot; empty when none or several match. */
	public static Optional<SiteView> building(MinecraftServer server, Settlement s, String name) {
		String n = name.toLowerCase().replaceFirst("^the ", "").strip();
		List<SiteView> all = ArchitectApi.get().sites(server).list(s.owner()).stream().filter(v -> "building".equals(v.kind())).toList();
		var exact = all.stream().filter(v -> Updates.lotOf(v).equalsIgnoreCase(n) || v.id().equals(name)).toList();
		if (exact.size() == 1) return Optional.of(exact.get(0));
		var partial = all.stream().filter(v -> Updates.lotOf(v).toLowerCase().contains(n)).toList();
		if (partial.size() == 1) return Optional.of(partial.get(0));
		// several of one family (a source and its copies, docs/FOLLOW.md): the source is the one meant, and its copies follow it
		var family = familySource(exact.size() > 1 ? exact : partial);
		return family.flatMap(id -> all.stream().filter(v -> v.id().equals(id)).findFirst());
	}

	/** Of several buildings, the source of their one family; empty when they are not one family. */
	static Optional<String> familySource(List<SiteView> matches) {
		if (matches.size() < 2) return Optional.empty();
		var library = ArchitectApi.get().library();
		List<dev.larattalabs.steward.gateway.FollowPlanner.Match> m = new java.util.ArrayList<>();
		for (SiteView v : matches) {
			if (v.blueprintId() == null) return Optional.empty();
			String copyOf = copySource(library, v.blueprintId());
			m.add(new dev.larattalabs.steward.gateway.FollowPlanner.Match(v.id(), v.blueprintId(), copyOf));
		}
		return dev.larattalabs.steward.gateway.FollowPlanner.familySource(m);
	}

	/** The building a change to {@code v} is asked of: its source's, when {@code v} is a copy and the source stands in the settlement; else {@code v}. */
	public static SiteView sourceFor(MinecraftServer server, Settlement s, SiteView v) {
		if (v.blueprintId() == null) return v;
		String copyOf = copySource(ArchitectApi.get().library(), v.blueprintId());
		if (copyOf == null) return v;
		return ArchitectApi.get().sites(server).list(s.owner()).stream().filter(x -> "building".equals(x.kind()) && copyOf.equals(x.blueprintId())).findFirst().orElse(v);
	}

	/** The entry a library entry is a COPY of (Architect 0b), else null. */
	static @Nullable String copySource(dev.larattalabs.architect.api.Library library, String entryId) {
		return library.get(entryId).flatMap(e -> e.derivation()).filter(d -> d.kind() == dev.larattalabs.architect.api.Library.Derivation.Kind.COPY)
			.map(dev.larattalabs.architect.api.Library.Derivation::source).orElse(null);
	}

	/** Whether a change of this site is being designed. */
	public static boolean designing(String siteId) {
		return BY_SITE.containsKey(siteId);
	}

	/**
	 * Designs the next version of {@code siteId}'s building from {@code notes}, at most {@code budgetUsd}. Returns null when it was sent, else why not.
	 * {@code accept} saves the acceptance (the proposal leaves the inbox) before anything is sent: nothing is spent unless that is saved. A definite refusal
	 * gives the proposal back ({@code giveBack}); a request whose answer was lost may still be designed, so it is not given back (no second charge).
	 */
	/** Why a site can't be changed by a redesign: a working module (its lot ext names one), or null. */
	public static @Nullable String moduleRefusal(SiteView v) {
		var ref = dev.larattalabs.steward.module.ModuleRef.of(v.ext());
		return ref == null ? null : "That is a working module (" + ref.key() + "): it isn't redesigned, since a redesign would be unmeasured. A second one is a new module; "
			+ "undo it to take it up.";
	}

	public static @Nullable String start(MinecraftServer server, UUID player, Settlement s, String siteId, String notes, double budgetUsd,
		java.util.function.BooleanSupplier accept, Runnable giveBack) {
		if (VERSION_OF == null) return "Changing a building that stands needs Architect 0b.";
		if (BY_SITE.containsKey(siteId)) return "A change of that building is already being designed.";
		var view = ArchitectApi.get().sites(server).get(siteId);
		if (view.isEmpty()) return "That building no longer stands.";
		SiteView v = view.get();
		if (v.owner() == null || !v.owner().equals(s.owner())) return "That building is not " + s.name() + "'s.";
		// (docs/MODULES.md section 1, slice M3) a working module never changes by an ordinary path: a redesign would be unmeasured
		String module = moduleRefusal(v);
		if (module != null) return module;
		// a change keeps the settlement's look: its style bible, as approved; after a new description there is none until a build makes one
		// a copy changes only by following its source (Architect FS): the change is asked of the source, before anything is saved or spent
		String copyOf = v.blueprintId() == null ? null : copySource(ArchitectApi.get().library(), v.blueprintId());
		if (copyOf != null) {
			String sourceLot = ArchitectApi.get().sites(server).list(s.owner()).stream().filter(x -> copyOf.equals(x.blueprintId())).findFirst().map(Updates::lotOf)
				.orElse(copyOf);
			return dev.larattalabs.steward.gateway.FollowPlanner.copyChangeText(Updates.lotOf(v), sourceLot);
		}
		if (s.bible() == null || s.bible().styleVersion() != s.styleVersion()) return s.name() + "'s style changed since its bible was made: build something first, so the steward has the new style, then ask again.";
		String name = the(v);
		DesignRequest req;
		try {
			req = request(s, v, notes, budgetUsd);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Steward.LOGGER.warn("change request for {} could not be made", siteId, e);
			return "The change could not be put to Architect (see the log).";
		}
		// saved before anything is sent: an acceptance that cannot be saved spends nothing
		if (!accept.getAsBoolean()) return "Could not save the settlement, so the change is not designed.";
		int session = Session.current();
		BY_SITE.put(siteId, "");
		ArchitectApi.get().designs().request(req).whenComplete((id, err) -> {
			if (!Session.is(session)) return;
			if (err != null) {
				BY_SITE.remove(siteId);
				if (definite(err)) {
					giveBack.run();
					tell(server, player, s.id(), "Architect would not design the change to " + name + ": " + refusal(err) + ". It is back in the inbox.");
				} else {
					// the request may have arrived and be designed anyway: not given back, so it cannot be paid twice; its version shows as an update if it comes
					Settlements.log(s.id(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, "Asked for a change to " + name
						+ "; Architect's answer was lost (" + refusal(err) + ")", List.of(siteId)));
					tell(server, player, s.id(), "I lost Architect's answer about the change to " + name + ". If it was designed, it shows as an update; if none comes, ask me again.");
				}
				return;
			}
			BY_SITE.put(siteId, id);
			PENDING.put(id, new Pending(s.id(), siteId, name, player, session));
			Settlements.log(s.id(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, "Designing a change to " + name + " (design " + id
				+ "): " + clip(notes, 160), List.of(siteId)));
			// it may already be done
			ArchitectApi.get().designs().get(id).filter(d -> d.status().isFinal()).ifPresent(d -> onDone(server, d));
		});
		return null;
	}

	/** The request: the building's own kind and size, the settlement's style and bible, the change in the notes, as the next version of its entry. */
	static DesignRequest request(Settlement s, SiteView v, String notes, double budgetUsd) throws ReflectiveOperationException {
		var c = s.card();
		String lotId = Updates.lotIdOf(v);
		String lot = lotId != null ? lotId : "custom";
		String type = lot.replaceFirst("_\\d+$", "");
		if (!dev.larattalabs.steward.model.ConceptCard.TYPE_SLUG.matcher(type).matches()) type = "custom";
		var b = v.box();
		// room to grow a little: a change may add a wing or a storey
		BlockSize size = new BlockSize(clamp(b.getXSpan() + 4, LotBrief.MIN_XZ, LotBrief.MAX_XZ), clamp(b.getYSpan() + 6, LotBrief.MIN_Y, LotBrief.MAX_Y),
			clamp(b.getZSpan() + 4, LotBrief.MIN_XZ, LotBrief.MAX_XZ));
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:lot", lot);
		ext.addProperty("steward_mc:change", true);
		String text = "A change to " + the(v) + " that stands in " + s.name() + ": " + notes.strip()
			+ (adopted(v) ? " This is the player's own build, which the steward looks after: keep every block they placed as it is, in its own style, and add or"
				+ " change only what the change asks for."
				: (c == null ? "" : " Keep it in the settlement's style (" + c.style().text() + ").") + " Keep its entrance and footprint unless the change needs otherwise.");
		DesignRequest base = new DesignRequest(type, c == null ? "rustic" : LotBrief.styleLabel(c), null, List.of(), size, null,
			text.length() > LotBrief.MAX_NOTES ? text.substring(0, LotBrief.MAX_NOTES) : text, null, s.owner(), ext, null, budgetUsd, null, null)
			// the settlement's own bible, at the version it kept (not the newest)
			.withBible(s.bible().id(), s.bible().version());
		// a player's own build keeps its own style: no settlement bible (its palette would replace theirs)
		if (adopted(v)) base = base.withBible(null, null);
		return (DesignRequest) VERSION_OF.invoke(base, v.blueprintId(), v.id());
	}

	/** "the scholars' library", but "noahlaratta's house": a player's own build is named by its owner, without an article. */
	static String the(SiteView v) {
		return adopted(v) ? Updates.lotOf(v) : "the " + Updates.lotOf(v);
	}

	/** A player's build the steward took on (Architect PT2): its site carries Architect's adoption mark. */
	static boolean adopted(SiteView v) {
		return v.ext() != null && v.ext().has("architect_mc:adopted");
	}

	private static void onDone(MinecraftServer server, Design d) {
		Pending p = PENDING.remove(d.id());
		if (p == null || !Session.is(p.session())) return;
		BY_SITE.remove(p.siteId());
		String cost = String.format(" ($%.2f)", d.cost().usd());
		if (d.status() == Design.Status.DONE) {
			Settlements.log(p.settlementId(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, "Designed a change to " + p.building()
				+ cost + ": its next version is offered as an update", List.of(p.siteId())));
			// the new version makes the building outdated: the updates offer it (preview, apply keeping edits, revert)
			Updates.rescan();
			tell(server, p.player(), p.settlementId(), "The change to " + p.building() + " is designed. It waits in the inbox as an update: preview it, then apply it.");
		} else {
			String why = d.error().orElse(d.status().name().toLowerCase());
			Settlements.log(p.settlementId(), new Settlement.LogEntry(System.currentTimeMillis(), Settlement.Kind.NOTE, "The change to " + p.building()
				+ " could not be designed" + cost + ": " + clip(why, 160), List.of(p.siteId())));
			tell(server, p.player(), p.settlementId(), "The change to " + p.building() + " could not be designed: " + why + ".");
		}
	}

	/** Architect's refusal in words: its reason (and detail) when typed. */
	private static String refusal(Throwable err) {
		Throwable e = err.getCause() != null ? err.getCause() : err;
		try {
			Object reason = e.getClass().getMethod("reason").invoke(e);
			String detail = "";
			try {
				Object dt = e.getClass().getMethod("detail").invoke(e);
				if (dt != null) detail = " (" + dt + ")";
			} catch (NoSuchMethodException ignored) {
				// no detail before 0b
			}
			return reason + detail + ": " + e.getMessage();
		} catch (ReflectiveOperationException x) {
			return String.valueOf(e.getMessage());
		}
	}

	private static void tell(MinecraftServer server, UUID player, String settlementId, String text) {
		StewardVoice.say(server, settlementId, text);
		var pl = server.getPlayerList().getPlayer(player);
		var s = Settlements.store().get(settlementId);
		if (pl != null) pl.sendSystemMessage(net.minecraft.network.chat.Component.literal("[" + s.map(Settlement::name).orElse("Steward") + "] " + text));
	}

	/** Architect refused for certain (a typed refusal: it has a reason), as against an answer that was lost (a timeout, a dropped link). */
	static boolean definite(Throwable err) {
		Throwable e = err.getCause() != null ? err.getCause() : err;
		try {
			// WORLD_STOPPED (0a) is typed too, but the helper's work goes on: the design may still be made, so it is not a refusal
			Object reason = e.getClass().getMethod("reason").invoke(e);
			return reason != null && !"WORLD_STOPPED".equals(String.valueOf(reason));
		} catch (ReflectiveOperationException x) {
			return false;
		}
	}

	private static String clip(String t, int max) {
		String one = t.replaceAll("\\s+", " ").strip();
		return one.length() <= max ? one : one.substring(0, max - 1) + "…";
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
