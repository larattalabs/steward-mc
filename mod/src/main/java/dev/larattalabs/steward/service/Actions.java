package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.SiteEvents;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.CardResult;
import dev.larattalabs.steward.gateway.ConceptCardJob;
import dev.larattalabs.steward.gateway.ProgramPlanner;
import dev.larattalabs.steward.gateway.WorldMode;
import dev.larattalabs.steward.model.BudgetPolicy;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.net.StewardNet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * What a player can do with a settlement, whether from a chat command or a screen: describe it, start building it, and decide what a build waits for. Every
 * check lives here (the settlement exists, it is the player's build, lengths and numbers are in range), so the screen path cannot skip what the command
 * tree enforces. Answers that come later (the card, undo progress) go to the player by UUID, never to a command source. Server thread.
 */
public final class Actions {
	/** {@code /steward start}'s ranges, held by the screens too. */
	public static final int MIN_BUILDINGS = 1, MAX_BUILDINGS = 12;
	public static final double MIN_BUDGET = 1, MAX_BUDGET = 200, MAX_RAISE = 500;
	public static final int MAX_NOTES = 2000;

	private static CardService cards;
	/** Settlements whose description is being read, with the request's token: one at a time per settlement, and only the newest may store its card. */
	private static final Map<String, Reading> describing = new java.util.HashMap<>();
	/** How long a description may be read before another may replace it (a job that never ends does not lock the settlement). */
	private static final long READING_MS = 10 * 60_000;

	private record Reading(long token, long since) {}
	private static long nextToken;
	private static final com.google.gson.Gson CARD_GSON = new com.google.gson.Gson();

	private Actions() {
	}

	/** Registers the client-to-server payload handlers (Fabric runs them on the server thread). */
	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> describing.clear());
		ServerPlayNetworking.registerGlobalReceiver(StewardNet.Describe.TYPE, (p, ctx) -> reply(ctx.player(), describe(ctx.player(), p.settlementId(), p.text())));
		ServerPlayNetworking.registerGlobalReceiver(StewardNet.Start.TYPE, (p, ctx) -> reply(ctx.player(), start(ctx.player(), p.settlementId(), p.buildings(), p.budgetUsd())));
		ServerPlayNetworking.registerGlobalReceiver(StewardNet.Decide.TYPE, (p, ctx) -> {
			reply(ctx.player(), decide(ctx.player(), p.settlementId(), p.action(), p.lot(), p.text(), p.amount()));
			// always answer with the inbox: the screen waits for it, and a refused decision must show as still waiting
			SettlementRunner.sendInbox(ctx.player().level().getServer(), ctx.player().getUUID());
		});
	}

	/** An immediate answer: what to show the player now. */
	public record Result(boolean ok, String message) {
		static Result ok(String m) {
			return new Result(true, m);
		}

		static Result fail(String m) {
			return new Result(false, m);
		}
	}

	/**
	 * Who may direct a world's steward: singleplayer (and LAN) is the supported mode, so only the world's host, whose Claude budget a build spends; on a dedicated
	 * server, operators. Null when {@code p} may; else why not.
	 */
	public static String refusal(ServerPlayer p) {
		MinecraftServer server = p.level().getServer();
		if (server.isDedicatedServer()) return server.getPlayerList().isOp(p.nameAndId()) ? null : "Only an operator can direct the steward on this server.";
		return server.isSingleplayerOwner(p.nameAndId()) ? null : "Only the host of this world can direct its steward (its builds spend the host's Claude budget).";
	}

	private static void reply(ServerPlayer p, Result r) {
		if (!r.message().isEmpty()) p.sendSystemMessage(Component.literal(r.message()));
	}

	public static synchronized CardService cards() {
		if (cards == null) {
			cards = new CardService(ArchitectApi.get().jobs());
			SiteEvents.JOB_DONE.register(cards::onDone);
			Steward.LOGGER.info("card service ready");
		}
		return cards;
	}

	// ------------------------------------------------------------------ describe

	/** Starts the card parse for a settlement; the card arrives later as chat lines and as a card for the screen. */
	public static Result describe(ServerPlayer player, String id, String text) {
		Optional<Settlement> s = Settlements.store().get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id + " (see /steward settlements).");
		String no = refusal(player);
		if (no != null) return Result.fail(no);
		if (SettlementRunner.busy(id)) return Result.fail(id + " is being built; describe it again once the build is done or cancelled.");
		if (reading(id)) return Result.fail("The steward is still reading your last description of " + id + ".");
		String words = text == null ? "" : text.strip();
		if (words.isEmpty()) return Result.fail("Say what to build first.");
		if (words.length() > ConceptCardJob.MAX_PROMPT) return Result.fail("That is longer than " + ConceptCardJob.MAX_PROMPT + " characters.");
		MinecraftServer server = player.level().getServer();
		UUID who = player.getUUID();
		boolean survival = WorldMode.survival(server).orElse(false);
		long token = ++nextToken;
		int session = Session.current();
		describing.put(id, new Reading(token, System.currentTimeMillis()));
		cards().submit(words, Map.of(), new ConceptCardJob.Settings(survival, survival ? "supplied" : "patron", Settlements.DEFAULT_RADIUS), s.get().owner())
			.whenComplete((r, err) -> {
				// a world closed since (its settlement ids repeat in the next world), or a newer request: this result is not stored
				if (!Session.is(session) || (describing.get(id) == null || describing.get(id).token() != token)) return;
				describing.remove(id);
				ServerPlayer p = server.getPlayerList().getPlayer(who);
				if (err != null || !r.ok()) {
					String why = err != null ? String.valueOf(err.getCause() != null ? err.getCause().getMessage() : err.getMessage()) : r.error();
					if (p != null) {
						p.sendSystemMessage(Component.literal("The card could not be made: " + why));
						// back to the words, so the player can try again without retyping
						ServerPlayNetworking.send(p, new StewardNet.OpenDescribe(id, s.get().name(), words));
					}
					return;
				}
				// start is refused while a description is read, so no build began meanwhile; checked again all the same
				if (SettlementRunner.busy(id)) {
					if (p != null) p.sendSystemMessage(Component.literal(id + " started building while the description was read; the new card was not saved."));
					return;
				}
				var res = Settlements.describe(id, r.card(), System.currentTimeMillis());
				if (p == null) return;
				if (!res.ok()) {
					p.sendSystemMessage(Component.literal(res.error()));
					return;
				}
				for (String line : CardResult.lines(r.card(), r.cost())) p.sendSystemMessage(Component.literal(line));
				p.sendSystemMessage(Component.literal(res.note()));
				p.sendSystemMessage(Component.literal(startHint(id, r.card())));
				sendCard(p, res.settlement());
			});
		return Result.ok("Interpreting your description...");
	}

	private static boolean reading(String id) {
		Reading r = describing.get(id);
		return r != null && System.currentTimeMillis() - r.since() < READING_MS;
	}

	// ------------------------------------------------------------------ start

	public static Result start(ServerPlayer player, String id, int buildings, double budgetUsd) {
		Optional<Settlement> s = Settlements.store().get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id + " (see /steward settlements).");
		String no = refusal(player);
		if (no != null) return Result.fail(no);
		if (!s.get().described()) return Result.fail("Describe " + id + " first.");
		if (SettlementRunner.busy(id)) return Result.fail(id + " is already being built.");
		if (reading(id)) return Result.fail("The steward is still reading the description of " + id + "; start once its card is in.");
		if (!SettlementRunner.canSave()) return Result.fail("Builds cannot be saved in this world (steward-builds.json could not be read; see the log), so none is started.");
		if (buildings < MIN_BUILDINGS || buildings > MAX_BUILDINGS) return Result.fail("Buildings must be " + MIN_BUILDINGS + " to " + MAX_BUILDINGS + ".");
		if (!(budgetUsd >= MIN_BUDGET && budgetUsd <= MAX_BUDGET)) return Result.fail(String.format("The budget must be $%.0f to $%.0f.", MIN_BUDGET, MAX_BUDGET));
		String size = s.get().card().site().size();
		// the settlement's own dimension, wherever the player stands
		MinecraftServer server = player.level().getServer();
		var level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
			net.minecraft.resources.Identifier.parse(s.get().claim().dimension())));
		if (level == null) return Result.fail("The dimension of " + id + " (" + s.get().claim().dimension() + ") is not loaded.");
		new SettlementRunner(server, level, player, s.get().permission(), cards(), BudgetPolicy.maxLandmarks(size, buildings))
			.startExisting(s.get(), buildings, budgetUsd);
		return Result.ok(String.format("Starting %s: %d buildings, budget $%.0f.", s.get().name(), buildings, budgetUsd));
	}

	// ------------------------------------------------------------------ decide

	/** A decision on the player's own build: approve, redirect, raise, cancel, show, hide; "card" shows the settlement's card again. */
	public static Result decide(ServerPlayer player, String id, String action, String lot, String text, double amount) {
		// looking is open to everyone; deciding is the host's
		if (!List.of("card", "show", "hide", "update_preview", "settlement", "building").contains(action == null ? "" : action)) {
			String no = refusal(player);
			if (no != null) return Result.fail(no);
		}
		switch (action == null ? "" : action) {
			case "update_apply" -> {
				return Result.ok(Updates.approve(player.level().getServer(), id, lot == null ? "" : lot));
			}
			case "update_skip" -> {
				return Result.ok(Updates.skip(player.level().getServer(), id, lot == null ? "" : lot));
			}
			case "update_preview" -> {
				var p = Updates.pending(id).stream().filter(x -> x.siteId().equals(lot)).findFirst();
				if (p.isEmpty()) return Result.fail("No update waiting for " + lot + ".");
				ServerPlayNetworking.send(player, new StewardNet.PreviewDelta(Updates.previewKey(lot), lot, p.get().to()));
				return Result.ok("Showing what changes on " + p.get().lot() + " (added, removed, changed and kept blocks).");
			}
			default -> {
			}
		}
		if ("expand".equals(action)) {
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			var res = Settlements.expand(id, System.currentTimeMillis());
			if (!res.ok()) return Result.fail(res.error());
			// back to the screen it came from: the settlement screen ("settlement"), else the card
			if ("settlement".equals(lot)) sendSettlement(player, res.settlement(), "");
			else if (res.settlement().described()) sendCard(player, res.settlement());
			return Result.ok(res.note());
		}
		if ("settlement".equals(action) || "building".equals(action)) {
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			sendSettlement(player, s.get(), "building".equals(action) && lot != null ? lot : "");
			return Result.ok("");
		}
		if ("card".equals(action)) {
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty() || !s.get().described()) return Result.fail("No card for " + id + ".");
			sendCard(player, s.get());
			return Result.ok("");
		}
		SettlementRunner r = SettlementRunner.active(id);
		if (r == null) return Result.fail("Nothing is being built for " + id + " in this session (see /steward settlements).");
		if (!r.playerId().equals(player.getUUID())) return Result.fail("That build belongs to another player.");
		return switch (action == null ? "" : action) {
			case "approve" -> Result.ok(lot == null || lot.isEmpty() ? r.approve() : r.approveLot(lot));
			case "redirect" -> {
				String notes = text == null ? "" : text.strip();
				if (notes.isEmpty()) yield Result.fail("Say what should change.");
				if (notes.length() > MAX_NOTES) yield Result.fail("Notes are limited to " + MAX_NOTES + " characters.");
				yield Result.ok(r.redirect(lot, notes));
			}
			case "raise" -> amount >= MIN_BUDGET && amount <= MAX_RAISE ? Result.ok(r.raise(amount)) : Result.fail(String.format("A budget must be $%.0f to $%.0f.", MIN_BUDGET, MAX_RAISE));
			case "cancel" -> Result.ok(r.cancel());
			case "show" -> Result.ok(r.showMassings(true));
			case "hide" -> {
				r.hideMassings();
				yield Result.ok("Hidden.");
			}
			default -> Result.fail("Unknown action: " + action);
		};
	}

	// ------------------------------------------------------------------ screens

	/**
	 * What a right-click on the steward or the Founding Stone opens: describe an undescribed settlement, the inbox while it is being built, its card while
	 * nothing is built yet, else the settlement screen.
	 */
	public static void openFor(ServerPlayer p, Settlement s) {
		if (!s.described()) ServerPlayNetworking.send(p, new StewardNet.OpenDescribe(s.id(), s.name(), ""));
		else if (SettlementRunner.busy(s.id())) {
			SettlementRunner.sendInbox(p.level().getServer(), p.getUUID());
			ServerPlayNetworking.send(p, new StewardNet.OpenInbox(s.id()));
		} else if (s.log().stream().noneMatch(e -> e.kind() == Settlement.Kind.PROJECT_PLACED)) sendCard(p, s);
		else sendSettlement(p, s, "");
	}

	/** Opens the settlement screen (or, with a site id, that building's panel) with the settlement's buildings as Architect has them now. Viewing is open to everyone. */
	public static void sendSettlement(ServerPlayer p, Settlement s, String siteId) {
		var sites = ArchitectApi.get().sites(p.level().getServer()).list(s.owner()).stream().map(Actions::site).toList();
		var view = dev.larattalabs.steward.view.SettlementView.of(s, SettlementRunner.busy(s.id()), sites);
		ServerPlayNetworking.send(p, new StewardNet.SettlementPanel(view.toJson(), siteId == null ? "" : siteId));
	}

	private static dev.larattalabs.steward.view.SettlementView.Site site(dev.larattalabs.architect.api.SiteView v) {
		var ext = v.ext();
		java.util.function.Function<String, String> str = k -> ext != null && ext.has(k) && ext.get(k).isJsonPrimitive() ? ext.get(k).getAsString() : null;
		var b = v.box();
		return new dev.larattalabs.steward.view.SettlementView.Site(v.id(), v.kind(), v.itemKey(), str.apply("steward_mc:role"), str.apply("steward_mc:lot"), v.blueprintId(),
			v.version(), v.headVersion(), v.deviations(), v.state().name().toLowerCase(), v.updating(), b.minX(), b.minZ(), b.maxX(), b.maxZ());
	}

	public static void sendCard(ServerPlayer p, Settlement s) {
		// the whole card as JSON: the screen lays it out in sections (it is common code, the client parses it with ConceptCard.parse)
		ServerPlayNetworking.send(p, new StewardNet.Card(s.id(), s.name(), CARD_GSON.toJson(s.card()), SettlementRunner.busy(s.id()), s.claim().radius()));
	}

	/** The start command to type for a freshly described card: its program's size and a budget at the high estimate, rounded up to $5. */
	public static String startHint(String id, ConceptCard c) {
		int n = Math.min(MAX_BUILDINGS, ProgramPlanner.total(c));
		int flagged = c.hasProgram() ? (int) c.program().stream().filter(ConceptCard.Building::landmark).count() : 0;
		var e = BudgetPolicy.estimate(n, BudgetPolicy.landmarksFor(c.site().size(), n, flagged));
		int budget = (int) (Math.ceil(e.usdHigh() / 5.0) * 5);
		return String.format("Saved. Start building: /steward start %s %d %d (%d buildings, about $%.0f-%.0f and %d-%d minutes)", id, n, budget, n, e.usdLow(), e.usdHigh(),
			e.minutesLow(), e.minutesHigh());
	}
}
