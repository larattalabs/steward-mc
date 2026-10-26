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
import java.util.function.Consumer;
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
	/** The weekly allowance's range (what the steward may spend on its own). */
	public static final double MIN_ALLOWANCE = 5, MAX_ALLOWANCE = 5000;

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
	/** A protected area reaches this far around the player (2 x r + 1 across), and a settlement has at most this many. */
	static final int MIN_PROTECT = 2, MAX_PROTECT = 32, MAX_AREAS = 16;

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
		// what the land round the stone is like goes with the words (the jungle side, by the lake), read first; the card job starts when it is
		Land.around(server, s.get(), land -> describeWith(land, words, id, survival, s.get(), server, who, token, session));
		return Result.ok("Interpreting your description...");
	}

	private static void describeWith(@org.jspecify.annotations.Nullable String land, String words, String id, boolean survival, Settlement settlement, MinecraftServer server,
		UUID who, long token, int session) {
		dev.larattalabs.steward.Steward.LOGGER.info("land round {}: {}", id, land == null ? "(not read)" : land);
		var s = Optional.of(settlement);
		cards().submit(words, Map.of(), new ConceptCardJob.Settings(survival, survival ? "supplied" : "patron", Settlements.DEFAULT_RADIUS,
			land), s.get().owner())
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
				var res = Settlements.describe(id, r.card(), System.currentTimeMillis(), dev.larattalabs.steward.gateway.WorldMode.survival(server).orElse(false));
				if (p == null) return;
				if (!res.ok()) {
					p.sendSystemMessage(Component.literal(res.error()));
					return;
				}
				for (String line : CardResult.lines(r.card(), r.cost())) p.sendSystemMessage(Component.literal(line));
				p.sendSystemMessage(Component.literal(res.note()));
				p.sendSystemMessage(Component.literal(startHint(id, r.card())));
				sendCard(p, res.settlement());
				// Claude composes the settlement's plan from the card and the land, Architect checks it (plan v0: kept and told; building takes today's paths)
				Composer.compose(server, who, res.settlement(), cardJson(r.card()), land);
			});
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
		if ("permission".equals(action)) {
			dev.larattalabs.steward.model.Permission p;
			try {
				p = dev.larattalabs.steward.model.Permission.valueOf(lot == null ? "" : lot);
			} catch (IllegalArgumentException e) {
				return Result.fail("Observer, Proposals, Autonomous or Full.");
			}
			// a build in progress keeps the level it started with: change it once that build is done or cancelled
			if (SettlementRunner.busy(id)) return Result.fail(id + " is being built; change its permission once that build is done or cancelled.");
			var res = Settlements.permission(id, p, System.currentTimeMillis());
			if (!res.ok()) return Result.fail(res.error());
			sendSettlement(player, res.settlement(), "");
			return Result.ok("Permission: " + p.name().toLowerCase() + ".");
		}
		if ("allowance".equals(action)) {
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			if (!(amount >= MIN_ALLOWANCE && amount <= MAX_ALLOWANCE)) return Result.fail(String.format("The weekly allowance is $%.0f to $%.0f.", MIN_ALLOWANCE, MAX_ALLOWANCE));
			var res = Settlements.autonomy(id, new Settlement.Autonomy(amount, s.get().autonomy().spends()));
			if (!res.ok()) return Result.fail(res.error());
			sendSettlement(player, res.settlement(), "");
			return Result.ok(String.format("The steward may spend up to $%.0f a week on its own (at Autonomous and Full).", amount));
		}
		if ("talk".equals(action)) {
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			return Talk.say(player.level().getServer(), player, s.get(), text);
		}
		if ("district_quarter".equals(action)) {
			// lot: the way (N, E, S, W, or "auto"); text: "along shore|ridge|biome_edge [first second]" or "outpost"
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			if (!s.get().described() || s.get().card() == null) return Result.fail("Describe " + id + " first.");
			String[] w = (text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT)).split("\\s+");
			dev.larattalabs.steward.model.Proposal.Quarter q;
			try {
				q = w[0].equals("outpost") ? new dev.larattalabs.steward.model.Proposal.Quarter("outpost", null, List.of(), null)
					: new dev.larattalabs.steward.model.Proposal.Quarter("along", w.length > 1 ? w[1] : null, w.length > 3 ? List.of(w[2], w[3]) : List.of(), null);
			} catch (com.google.gson.JsonParseException e) {
				return Result.fail("A quarter is \"along shore\", \"along ridge\", \"along biome_edge <one> <other>\" or \"outpost\".");
			}
			MinecraftServer server = player.level().getServer();
			String way = (lot == null ? "" : lot).toUpperCase(java.util.Locale.ROOT);
			java.util.UUID who = player.getUUID();
			if (way.equals("AUTO")) {
				var site = dev.larattalabs.steward.model.DistrictSite.fromWords("shore".equals(q.boundary()) ? "by the water" : "");
				Districts.choose(server, s.get(), site, null, Districts.quarterMisfits(s.get(), q), c -> {
					String msg = c.way() == null ? "No side of " + s.get().name() + " has room for it." : Proposals.offerQuarter(server, who, s.get(), c.way(), q);
					var p = server.getPlayerList().getPlayer(who);
					if (p != null && msg != null) p.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
				});
				return Result.ok("Looking for the side that suits it...");
			}
			if (!dev.larattalabs.steward.model.DistrictRules.DIRECTIONS.contains(way)) return Result.fail("A quarter goes N, E, S, W, or auto.");
			String no = Proposals.offerQuarter(server, who, s.get(), way, q);
			return no == null ? Result.ok("It waits in the inbox (Y).") : Result.fail(no);
		}
		if ("district_found".equals(action)) {
			// lot: the way from the town and the size, "E" or "E:M"; text: a name ("" names it by its way)
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			if (!s.get().described()) return Result.fail("Describe " + id + " first.");
			String[] parts = (lot == null ? "" : lot).toUpperCase(java.util.Locale.ROOT).split(":");
			String dir = parts[0], size = parts.length > 1 ? parts[1] : "S";
			if (!dev.larattalabs.steward.model.DistrictRules.SIZES.contains(size)) return Result.fail("A district is S (97 across) or M (129 across).");
			String name = text == null ? "" : text.strip();
			if (name.length() > 40) return Result.fail("A district's name is at most 40 characters.");
			MinecraftServer server = player.level().getServer();
			java.util.UUID who = player.getUUID();
			Consumer<String> say = msg -> {
				var p = server.getPlayerList().getPlayer(who);
				if (p != null) p.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
				StewardVoice.say(server, id, msg);
			};
			// "here": the side nearest where the player stands; "auto": the side whose ground suits the district's name best ("the harbour": by the water)
			if (dir.equals("HERE") || dir.equals("AUTO")) {
				var site = dir.equals("AUTO") ? dev.larattalabs.steward.model.DistrictSite.fromWords(name) : dev.larattalabs.steward.model.DistrictSite.ANYWHERE;
				Districts.choose(server, s.get(), site, dir.equals("HERE") ? player.blockPosition() : null, java.util.Set.of(), c -> {
					if (c.way() == null) { say.accept("No side of " + s.get().name() + " has room for a district."); return; }
					say.accept("To the " + dev.larattalabs.steward.model.DistrictRules.directionName(c.way()) + (c.why().isEmpty() ? "." : ": " + c.why() + "."));
					var now = Settlements.store().get(id);
					var p = server.getPlayerList().getPlayer(who);
					String no = now.isEmpty() ? "No such settlement: " + id + "." : Districts.found(server, p, now.get(), c.way(), size, name, say);
					if (no != null) say.accept(no);
				});
				return Result.ok(dir.equals("HERE") ? "Finding the side nearest you..." : "Looking at the land around " + s.get().name() + "...");
			}
			String no = Districts.found(server, player, s.get(), dir, size, name, say);
			return no == null ? Result.ok("Laying the road to the new district...") : Result.fail(no);
		}
		if ("build_adopt".equals(action)) {
			// "look after my house" (Architect PT2): the player's build becomes one of the settlement's buildings, unchanged
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			MinecraftServer server = player.level().getServer();
			UUID who = player.getUUID();
			PlayerBuilds.adopt(server, player, s.get(), lot, line -> {
				var p = server.getPlayerList().getPlayer(who);
				if (p != null) p.sendSystemMessage(Component.literal(line));
				dev.larattalabs.steward.Steward.LOGGER.info("adopt {} {}: {}", id, lot, line);
			});
			return Result.ok("Taking on " + lot + "...");
		}
		if ("builds".equals(action) || "build_claim".equals(action) || "build_release".equals(action)) {
			// the player's own builds in the claim (Architect PT): listed; "claim my build" for one built before tracking; released when it is no longer to be kept
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			MinecraftServer server = player.level().getServer();
			try {
				if ("build_claim".equals(action)) {
					if (!s.get().contains(player.level().dimension().identifier().toString(), player.getBlockX(), player.getBlockY(), player.getBlockZ()))
						return Result.fail("Stand in " + s.get().name() + "'s claim, by the build, to claim it.");
					// from the player's feet up (they stand on its floor): a claim marks every non-air cell, and the ground under the build is not theirs
					int r = (int) amount, x = player.getBlockX(), y = player.getBlockY(), z = player.getBlockZ();
					var b = ArchitectApi.get().sites(server).claimBuild(player.level().dimension(),
						new net.minecraft.world.level.levelgen.structure.BoundingBox(x - r, y, z - r, x + r, y + Math.min(31, 2 * r), z + r), player);
					return Result.ok("Claimed " + PlayerBuilds.name(server, b) + " (" + b.id() + ", " + b.cells() + " blocks): the steward builds round it and never over it.");
				}
				if ("build_release".equals(action)) {
					var b = ArchitectApi.get().sites(server).releaseBuild(lot, player);
					return Result.ok("Released " + b.id() + ": it is no longer kept from builds.");
				}
			} catch (dev.larattalabs.architect.api.ArchitectRefused e) {
				return Result.fail(e.getMessage());
			}
			var builds = PlayerBuilds.in(server, s.get());
			if (builds.isEmpty()) return Result.ok("No builds of yours in " + s.get().name() + "'s claim (little blocks and touched-up ground don't count).");
			return Result.ok("Your builds in " + s.get().name() + " (kept as you built them):\n" + String.join("\n", PlayerBuilds.promptLines(server, s.get(), builds)));
		}
		if ("stockpile".equals(action) || "stockpile_add".equals(action)) {
			// phase 4: what the settlement's stockpile holds and what its builds still need; "add": a crate where the player stands
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			MinecraftServer server = player.level().getServer();
			if ("stockpile_add".equals(action)) {
				var at = player.blockPosition();
				// in the town itself, not a district: a district can be undone and its land released, and a stockpile crate does not go with it
				if (!s.get().claim().contains(player.level().dimension().identifier().toString(), at.getX(), at.getY(), at.getZ())) {
					return Result.fail("Stand in " + s.get().name() + "'s town (not a district) to add a crate to its stockpile.");
				}
				return Result.ok(Stockpiles.addCrate(server, s.get(), at));
			}
			var sp = Stockpiles.of(server, s.get());
			if (sp.isEmpty()) return Result.ok(s.get().name() + " has no stockpile: it is made when a build is placed from materials (a survival world).");
			return Result.ok(String.join("\n", Stockpiles.bill(sp.get(), 8)));
		}
		if ("protect".equals(action)) {
			// a square of 2 x amount + 1 around where the player stands, in this settlement's land; text: its label
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			// a build's queued stages were checked against the areas when queued: protect once it is done
			if (SettlementRunner.busy(id)) return Result.fail(s.get().name() + " is busy; protect ground once its build is done or cancelled.");
			int r = (int) amount;
			if (r < MIN_PROTECT || r > MAX_PROTECT) return Result.fail("A protected area reaches " + MIN_PROTECT + " to " + MAX_PROTECT + " blocks around you.");
			var at = player.blockPosition();
			String dim = player.level().dimension().identifier().toString();
			if (!s.get().contains(dim, at.getX(), at.getY(), at.getZ())) return Result.fail("Stand in " + s.get().name() + " to protect ground there.");
			if (s.get().lands().protect().size() >= MAX_AREAS) return Result.fail("At most " + MAX_AREAS + " protected areas; clear one first.");
			String label = text == null || text.isBlank() ? "around " + at.getX() + "," + at.getZ() : text.strip();
			if (label.length() > 40) return Result.fail("A label is at most 40 characters.");
			var res = Settlements.protect(id, label, dim, at.getX() - r, at.getZ() - r, at.getX() + r, at.getZ() + r, System.currentTimeMillis());
			if (!res.ok()) return Result.fail(res.error());
			// Architect's write bounds too (C17): nothing of the settlement writes there, whatever Steward's own checks miss
			if (!Protection.sync(player.level().getServer(), id)) Steward.LOGGER.warn("protected area of {} saved, but Architect did not take it (see above)", id);
			sendSettlement(player, res.settlement(), "");
			return Result.ok(res.note());
		}
		if ("unprotect".equals(action)) {
			var res = Settlements.unprotect(id, lot == null ? "" : lot, System.currentTimeMillis());
			if (!res.ok()) return Result.fail(res.error());
			Protection.sync(player.level().getServer(), id);
			sendSettlement(player, res.settlement(), "");
			return Result.ok(res.note());
		}
		if ("proposal_decline".equals(action) || "proposal_accept".equals(action)) {
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			if ("proposal_decline".equals(action)) {
				// a waiting part being fitted or built finishes first (declining it then would leave it built and not recorded as accepted)
				// the waiting parts' row: put off for this session
				if (PendingOffers.GROUP_KEY.equals(lot)) {
					String no = PendingOffers.putOff(s.get());
					return no == null ? Result.ok("Not now: the waiting parts are offered again next time.") : Result.fail(no);
				}
				var offer = s.get().proposals().open().stream().filter(x -> x.key().equals(lot) && x.offersPart()).findFirst();
				if (offer.isPresent() && PendingOffers.busy(s.get().id(), offer.get().pendingPart())) return Result.fail("It is being built now; undo it once it stands.");
				return Result.ok(Proposals.decline(player.level().getServer(), s.get(), lot == null ? "" : lot));
			}
			return acceptProposal(player, s.get(), lot == null ? "" : lot);
		}
		if ("revert".equals(action)) {
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty()) return Result.fail("No such settlement: " + id + ".");
			if (SettlementRunner.busy(id)) return Result.fail(id + " is being built; revert once the build is done or cancelled.");
			return Revert.start(player.level().getServer(), s.get(), lot == null ? "" : lot, (int) amount, player.getUUID());
		}
		if ("card".equals(action)) {
			Optional<Settlement> s = Settlements.store().get(id);
			if (s.isEmpty() || !s.get().described()) return Result.fail("No card for " + id + ".");
			sendCard(player, s.get());
			return Result.ok("");
		}
		SettlementRunner r = SettlementRunner.active(id);
		if (r == null && "cancel".equals(action)) {
			// no build running: buildings may still be going up from the stockpile (an undo takes them up)
			var s = Settlements.store().get(id);
			if (s.isPresent() && SettlementRunner.unfinishedConstruction(player.level().getServer(), s.get()) > 0) {
				return Result.fail(SettlementRunner.nothingToCancel(player.level().getServer(), s.get()));
			}
		}
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

	/**
	 * Builds an accepted proposal: one building beside what stands, in the settlement's style, at the proposal's estimate as its budget (the build pauses at
	 * 80% of it as any build does). Checked like a start.
	 */
	static Result acceptProposal(ServerPlayer player, Settlement s, String key) {
		return acceptProposal(player, s, key, false);
	}

	/**
	 * {@code autonomous}: the steward accepts it on its own, within the week's allowance. The acceptance (and an autonomous build's spend) is saved in one commit
	 * as the build starts, and given back if the build ends having spent nothing, so a failed start costs neither the proposal nor the allowance.
	 */
	static Result acceptProposal(ServerPlayer player, Settlement s, String key, boolean autonomous) {
		if (PendingOffers.GROUP_KEY.equals(key)) {
			// the waiting parts' row: one part fitted and built, the rest offered after (always the player's call)
			if (autonomous) return Result.fail("A waiting part waits for the player.");
			if (SettlementRunner.busy(s.id())) return Result.fail(s.name() + " is busy (a build, a road, an undo or another part); build the parts once that is done.");
			String no = PendingOffers.acceptGroup(player.level().getServer(), player.getUUID(), s);
			return no == null ? Result.ok("Fitting one part to what stands (up to $1); the rest wait their turn.") : Result.fail(no);
		}
		var p = s.proposals().open().stream().filter(x -> x.key().equals(key)).findFirst();
		if (p.isEmpty()) return Result.fail("No proposal " + key + " waits.");
		if (SettlementRunner.busy(s.id())) return Result.fail(s.id() + " is being built; build the proposal once that is done.");
		if (p.get().offersPart()) {
			// a part of the plan that waited for Architect, built now (docs/COMPOSITION.md section 11): always the player's call
			if (autonomous) return Result.fail("A waiting part waits for the player.");
			String no = PendingOffers.accept(player.level().getServer(), player.getUUID(), s, p.get());
			return no == null ? Result.ok("Fitting it to what stands (up to $" + Proposals.estimate(p.get()) + ")...") : Result.fail(no);
		}
		if (p.get().changes()) {
			// a change to a standing building: designed as its next version, then offered as an update; always the player's call
			if (autonomous) return Result.fail("A change waits for the player.");
			int budget = Proposals.estimate(p.get());
			String no = ChangeRequests.start(player.level().getServer(), player.getUUID(), s, p.get().changeSite(), p.get().changeNotes(), budget,
				() -> Proposals.recordAccepted(s.id(), key, null), () -> Proposals.giveBack(s.id(), p.get(), null));
			return no == null ? Result.ok(String.format("Designing the change (up to $%d). The steward tells you when it is ready to preview.", budget)) : Result.fail(no);
		}
		if (p.get().foundsQuarter()) {
			// a quarter (along a boundary, or an outpost) is founded and built at once, priced like a build; always the player's call
			if (autonomous) return Result.fail("A new quarter waits for the player.");
			if (!SettlementRunner.canSave()) return Result.fail("Builds cannot be saved in this world (steward-builds.json could not be read; see the log), so none is started.");
			MinecraftServer server = player.level().getServer();
			var level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
				net.minecraft.resources.Identifier.parse(s.claim().dimension())));
			if (level == null) return Result.fail("The dimension of " + s.id() + " is not loaded.");
			String id;
			try {
				id = Districts.foundQuarter(server, s, p.get().found(), p.get().quarter(), p.get().title());
			} catch (IllegalStateException e) {
				return Result.fail(e.getMessage());
			}
			var founded = Settlements.store().get(s.id()).orElse(s);
			int budget = Proposals.estimate(p.get());
			new SettlementRunner(server, level, player, s.permission(), cards(), 0).hooks(() -> Proposals.recordAccepted(s.id(), key, null),
				() -> Proposals.giveBack(s.id(), p.get(), null), () -> Proposals.backOff(s.id(), key)).startAddition(founded, p.get().building(), budget, id,
				Math.max(1, p.get().building().count()));
			return Result.ok(String.format("Laying out %s and its %d buildings, budget $%d.", p.get().title(), Math.max(1, p.get().building().count()), budget));
		}
		if (p.get().foundsDistrict()) {
			// a new district always waits for the player, whatever the permission level
			if (autonomous) return Result.fail("A new district waits for the player.");
			MinecraftServer server = player.level().getServer();
			UUID who = player.getUUID();
			String title = p.get().title();
			String no = Districts.found(server, player, s, p.get().found(), "S", p.get().district() == null ? "" : p.get().district(), msg -> {
				var pl = server.getPlayerList().getPlayer(who);
				if (pl != null) pl.sendSystemMessage(Component.literal(msg));
				StewardVoice.say(server, s.id(), msg);
			}, () -> Proposals.recordAccepted(s.id(), key, null));
			return no == null ? Result.ok("Laying the road to the new district...") : Result.fail(no);
		}
		if (s.lastUndoable().isEmpty()) return Result.fail("Nothing stands in " + s.name() + " yet: an addition goes beside its buildings and street.");
		if (reading(s.id())) return Result.fail("The steward is still reading a description of " + s.id() + ".");
		if (!SettlementRunner.canSave()) return Result.fail("Builds cannot be saved in this world (steward-builds.json could not be read; see the log), so none is started.");
		MinecraftServer server = player.level().getServer();
		var level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
			net.minecraft.resources.Identifier.parse(s.claim().dimension())));
		if (level == null) return Result.fail("The dimension of " + s.id() + " is not loaded.");
		int budget = Proposals.estimate(p.get());
		if (autonomous && !s.autonomy().allows(budget, System.currentTimeMillis())) return Result.fail("Past this week's allowance.");
		String title = p.get().title();
		// in its district while that stands (else the town)
		String district = p.get().district() != null && s.lands().district(p.get().district()).isPresent() ? p.get().district() : null;
		// the spend this acceptance records (autonomous only), and gives back if nothing was spent: this one, not another with the same title
		Settlement.Spend spend = autonomous ? new Settlement.Spend(System.currentTimeMillis(), budget, title) : null;
		new SettlementRunner(server, level, player, s.permission(), cards(), 0).hooks(() -> Proposals.recordAccepted(s.id(), key, spend),
			() -> Proposals.giveBack(s.id(), p.get(), spend), () -> Proposals.backOff(s.id(), key)).startAddition(s, p.get().building(), budget, district);
		return Result.ok(String.format("Building %s for %s: one building beside what stands%s, budget $%d.", title.toLowerCase(), s.name(),
			district == null ? "" : " in " + s.lands().district(district).map(Settlement.District::name).orElse("its district"), budget));
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
		ServerPlayNetworking.send(p, new StewardNet.SettlementPanel(view(p.level().getServer(), s).toJson(), siteId == null ? "" : siteId));
	}

	/** The settlement as its screens show it, with its buildings as Architect has them now. */
	public static dev.larattalabs.steward.view.SettlementView view(MinecraftServer server, Settlement s) {
		var api = ArchitectApi.get().sites(server);
		var sites = api.list(s.owner()).stream().map(v -> site(api, v)).toList();
		// where each district waiting in the inbox would go: survey mode shows it before it is founded
		List<dev.larattalabs.steward.view.SettlementView.DistrictInfo> proposed = new ArrayList<>();
		var street = Districts.townStreet(server, s);
		for (var p : s.proposals().open()) {
			if (p.found() == null) continue;
			var at = dev.larattalabs.steward.model.DistrictRules.place(s.claim(), street, s.lands().districts(), Settlements.others(s.id()), s.lands().protect(), p.found(),
				dev.larattalabs.steward.model.DistrictRules.RADII[0]);
			if (at.ok()) proposed.add(new dev.larattalabs.steward.view.SettlementView.DistrictInfo(p.key(), p.title(), p.found(), at.claim().centerX(), at.claim().centerZ(),
				at.claim().radius(), dev.larattalabs.steward.model.ClaimRules.side(at.claim().radius()), 0, 0));
		}
		List<String> stock = Stockpiles.of(server, s).map(sp -> Stockpiles.bill(sp, 6)).orElse(List.of());
		var builds = PlayerBuilds.in(server, s).stream().filter(b -> b.siteId() == null).map(b -> new dev.larattalabs.steward.view.SettlementView.PlayerBuildInfo(b.id(), PlayerBuilds.title(b), PlayerBuilds.by(server, b),
			b.bounds().minX(), b.bounds().minY(), b.bounds().minZ(), b.bounds().maxX(), b.bounds().maxY(), b.bounds().maxZ(),
			b.state() == dev.larattalabs.architect.api.BuildState.PROVISIONAL)).toList();
		// a district region whose town was undone first: Architect keeps it and its road, which now ends where the town's street was (D3 4.2)
		java.util.Map<String, String> notes = new java.util.HashMap<>();
		for (var d : s.lands().districts()) {
			if (d.region() == null) continue;
			ArchitectApi.get().regions().get(d.region()).ifPresent(v -> {
				var beside = v.ext().has("architect_mc:beside") && v.ext().get("architect_mc:beside").isJsonObject() ? v.ext().getAsJsonObject("architect_mc:beside") : null;
				boolean dangling = beside != null && beside.has("dangling") && beside.get("dangling").getAsBoolean();
				if (dangling || dev.larattalabs.steward.gateway.DistrictLand.standingRemoved(v.ext()) != null) notes.put(d.id(), "its road to the town is gone");
			});
		}
		return dev.larattalabs.steward.view.SettlementView.of(s, SettlementRunner.busy(s.id()), sites, proposed, stock, builds, StewardStatus.attention(s.id(), ""), notes);
	}

	private static dev.larattalabs.steward.view.SettlementView.Site site(dev.larattalabs.architect.api.Sites sites, dev.larattalabs.architect.api.SiteView v) {
		var ext = v.ext();
		java.util.function.Function<String, String> str = k -> ext != null && ext.has(k) && ext.get(k).isJsonPrimitive() ? ext.get(k).getAsString() : null;
		var b = v.box();
		return new dev.larattalabs.steward.view.SettlementView.Site(v.id(), v.kind(), v.itemKey(), str.apply("steward_mc:role") != null ? str.apply("steward_mc:role") : Updates.recordedRole(v), Updates.lotIdOf(v), v.blueprintId(),
			v.version(), v.headVersion(), v.deviations(), v.state().name().toLowerCase(), v.updating(), b.minX(), b.minZ(), b.maxX(), b.maxZ(), Revert.previousVersion(sites, v.id()),
			b.minY(), b.maxY());
	}

	/** A card as the JSON the screens and the fixtures use. */
	public static String cardJson(dev.larattalabs.steward.model.ConceptCard c) {
		// nulls kept: the schema requires the nullable fields (a template of null) to be there
		return new com.google.gson.GsonBuilder().serializeNulls().create().toJson(c);
	}

	private static String clip(String t, int n) {
		return t == null ? "" : t.length() <= n ? t : t.substring(0, n - 1) + "…";
	}

	public static void sendCard(ServerPlayer p, Settlement s) {
		sendCard(p, s, false);
	}

	/** {@code refresh}: only updates the card screen if the player has it open on this settlement (the plan came in). */
	public static void sendCard(ServerPlayer p, Settlement s, boolean refresh) {
		// the whole card as JSON: the screen lays it out in sections (it is common code, the client parses it with ConceptCard.parse)
		// the plan composed from it, when there is one: its summary and what waits, in Claude's words (they replace the word rules' guesses)
		var plan = PlanStore.get(s.id());
		// the summary, then its bridges and tunnels as Architect resolved them
		String summary = plan.map(e -> clip(e.summary() + (e.crossings().isEmpty() ? "" : " Crossings: " + String.join("; ", e.crossings()) + "."), 4000)).orElse("");
		List<String> waits = plan.map(e -> e.pending().stream().map(x -> clip(x.words(), 1000)).limit(12).toList()).orElse(List.of());
		ServerPlayNetworking.send(p, new StewardNet.Card(s.id(), s.name(), CARD_GSON.toJson(s.card()), SettlementRunner.busy(s.id()), s.claim().radius(), summary,
			waits, refresh));
	}

	/** The start command to type for a freshly described card: its program's size and a budget at the high estimate, rounded up to $5. */
	public static String startHint(String id, ConceptCard c) {
		int n = Math.min(MAX_BUILDINGS, ProgramPlanner.total(c));
		int flagged = c.hasProgram() ? (int) c.program().stream().filter(ConceptCard.Building::landmark).count() : 0;
		int landmarks = BudgetPolicy.landmarksFor(c.site().size(), n, flagged);
		// copies of a design are free: only the designs are estimated
		var e = ProgramPlanner.estimate(c, n, landmarks);
		int budget = (int) (Math.ceil(e.usdHigh() / 5.0) * 5);
		return String.format("Saved. Start building: /steward start %s %d %d (%d buildings, about $%.0f-%.0f and %d-%d minutes)", id, n, budget, n, e.usdLow(), e.usdHigh(),
			e.minutesLow(), e.minutesHigh());
	}
}
