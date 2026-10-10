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
	private static final com.google.gson.Gson CARD_GSON = new com.google.gson.Gson();

	private Actions() {
	}

	/** Registers the client-to-server payload handlers (Fabric runs them on the server thread). */
	public static void init() {
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
		if (SettlementRunner.busy(id)) return Result.fail(id + " is being built; describe it again once the build is done or cancelled.");
		String words = text == null ? "" : text.strip();
		if (words.isEmpty()) return Result.fail("Say what to build first.");
		if (words.length() > ConceptCardJob.MAX_PROMPT) return Result.fail("That is longer than " + ConceptCardJob.MAX_PROMPT + " characters.");
		MinecraftServer server = player.level().getServer();
		UUID who = player.getUUID();
		boolean survival = WorldMode.survival(server).orElse(false);
		cards().submit(words, Map.of(), new ConceptCardJob.Settings(survival, survival ? "supplied" : "patron", Settlements.DEFAULT_RADIUS), s.get().owner())
			.whenComplete((r, err) -> {
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
				var res = Settlements.describe(id, r.card(), System.currentTimeMillis());
				if (p == null) return;
				if (!res.ok()) {
					p.sendSystemMessage(Component.literal(res.error()));
					return;
				}
				for (String line : CardResult.lines(r.card(), r.cost())) p.sendSystemMessage(Component.literal(line));
				p.sendSystemMessage(Component.literal(startHint(id, r.card())));
				sendCard(p, res.settlement());
			});
		return Result.ok("Interpreting your description...");
	}

	// ------------------------------------------------------------------ start

	public static Result start(ServerPlayer player, String id, int buildings, double budgetUsd) {
		Optional<Settlement> s = Settlements.store().get(id);
		if (s.isEmpty()) return Result.fail("No such settlement: " + id + " (see /steward settlements).");
		if (!s.get().described()) return Result.fail("Describe " + id + " first.");
		if (SettlementRunner.busy(id)) return Result.fail(id + " is already being built.");
		if (buildings < MIN_BUILDINGS || buildings > MAX_BUILDINGS) return Result.fail("Buildings must be " + MIN_BUILDINGS + " to " + MAX_BUILDINGS + ".");
		if (!(budgetUsd >= MIN_BUDGET && budgetUsd <= MAX_BUDGET)) return Result.fail(String.format("The budget must be $%.0f to $%.0f.", MIN_BUDGET, MAX_BUDGET));
		String size = s.get().card().site().size();
		new SettlementRunner(player.level().getServer(), player.level(), player, s.get().permission(), cards(), BudgetPolicy.maxLandmarks(size, buildings))
			.startExisting(s.get(), buildings, budgetUsd);
		return Result.ok(String.format("Starting %s: %d buildings, budget $%.0f.", s.get().name(), buildings, budgetUsd));
	}

	// ------------------------------------------------------------------ decide

	/** A decision on the player's own build: approve, redirect, raise, cancel, show, hide; "card" shows the settlement's card again. */
	public static Result decide(ServerPlayer player, String id, String action, String lot, String text, double amount) {
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

	/** What a right-click on the steward or the Founding Stone opens: describe an undescribed settlement, the inbox while it is being built, else its card. */
	public static void openFor(ServerPlayer p, Settlement s) {
		if (!s.described()) ServerPlayNetworking.send(p, new StewardNet.OpenDescribe(s.id(), s.name(), ""));
		else if (SettlementRunner.busy(s.id())) {
			SettlementRunner.sendInbox(p.level().getServer(), p.getUUID());
			ServerPlayNetworking.send(p, new StewardNet.OpenInbox(s.id()));
		} else sendCard(p, s);
	}

	public static void sendCard(ServerPlayer p, Settlement s) {
		// the whole card as JSON: the screen lays it out in sections (it is common code, the client parses it with ConceptCard.parse)
		ServerPlayNetworking.send(p, new StewardNet.Card(s.id(), s.name(), CARD_GSON.toJson(s.card()), SettlementRunner.busy(s.id())));
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
