package dev.larattalabs.steward.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.SiteEvents;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.ArchitectGateway;
import dev.larattalabs.steward.gateway.CardResult;
import dev.larattalabs.steward.gateway.ConceptCardJob;
import dev.larattalabs.steward.gateway.WorldMode;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.service.Actions;
import dev.larattalabs.steward.service.CardService;
import dev.larattalabs.steward.service.SettlementRunner;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.Map;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /steward}: the settlement commands until the card screen and inbox exist, plus developer commands. Player commands are open to everyone (they are the
 * game's flow and cost only what the player asks for); developer commands, and {@code claim} (a free Founding Stone), need cheats (permission level 2), like
 * Architect's mutating commands.
 * <pre>
 * /steward status                                  Architect API version, features, Claude link, world mode
 * /steward settlements                             the settlements in this world
 * /steward describe &lt;id&gt; &lt;words&gt;                 turn a description into the settlement's concept card (about 2 cents)
 * /steward start &lt;id&gt; &lt;buildings&gt; &lt;budget&gt;        design and build a described settlement
 * /steward approve &lt;id&gt;                           approve what the build waits for: the style bible, the massings, or placing it
 * /steward redirect &lt;id&gt; &lt;lot&gt; &lt;notes&gt;           send one massing back with notes
 * /steward raise &lt;id&gt; &lt;budget&gt;                    raise the budget of a build paused at its soft budget
 * /steward cancel &lt;id&gt;                            stop the build (what is placed stays)
 * /steward show|hide &lt;id&gt;                         show or hide the build's massings as ghosts on their lots
 * /steward expand &lt;id&gt;                            grow the claim one size step (S 97, M 129, L 193, XL 257 across)
 * /steward updates                                look again for newer versions of placed buildings (the inbox offers them)
 * /steward undo &lt;id&gt;                              remove the newest placed project and restore the land exactly
 * dev (cheats):
 * /steward claim                                   claim the land under you, as the Founding Stone does
 * /steward survey                                  survey around you and try four test lots
 * /steward card &lt;words&gt;                            parse a card and print it, saving nothing
 * /steward build &lt;buildings&gt; &lt;budget&gt; &lt;words&gt;     card, layout and build around you at Full permission (not saved)
 * /steward resume &lt;group&gt; &lt;cardJob&gt; &lt;n&gt; &lt;budget&gt;  continue a paused group from an earlier dev build
 * </pre>
 */
public final class StewardCommands {

	private StewardCommands() {
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(Commands.literal("steward")
			.then(Commands.literal("status").executes(ctx -> {
				ArchitectGateway.Status st = ArchitectGateway.check();
				ctx.getSource().sendSuccess(() -> Component.literal("Steward: " + st.summary()), false);
				if (st.ok()) {
					boolean up = ArchitectApi.get().jobs().available();
					ctx.getSource().sendSuccess(() -> Component.literal("Claude link: " + (up ? "up" : "not available")), false);
					String mode = WorldMode.survival(ctx.getSource().getServer()).map(b -> b ? "survival" : "creative").orElse("unknown");
					ctx.getSource().sendSuccess(() -> Component.literal("World mode (Architect toggle): " + mode), false);
				}
				return st.ok() ? 1 : 0;
			}))
			.then(Commands.literal("survey").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
				CommandSourceStack src = ctx.getSource();
				var p = src.getPlayerOrException();
				int cx = p.blockPosition().getX(), cz = p.blockPosition().getZ();
				int rad = dev.larattalabs.steward.service.Settlements.DEFAULT_RADIUS;
				var area = new net.minecraft.world.level.levelgen.structure.BoundingBox(cx - rad, src.getLevel().getMinY(), cz - rad, cx + rad, src.getLevel().getMaxY(), cz + rad);
				ArchitectApi.get().survey().sample(src.getLevel(), area, 1, dev.larattalabs.architect.api.LoadPolicy.LOADED_ONLY).whenComplete((sm, err) -> {
					if (err != null) { src.sendFailure(Component.literal("survey failed: " + err.getMessage())); return; }
					var g = dev.larattalabs.steward.layout.TerrainGrid.fromSample(sm);
					var specs = java.util.List.of(new dev.larattalabs.steward.layout.VillageLayout.LotSpec("a", "house", 14, 13), new dev.larattalabs.steward.layout.VillageLayout.LotSpec("b", "house", 14, 13),
						new dev.larattalabs.steward.layout.VillageLayout.LotSpec("c", "house", 14, 13), new dev.larattalabs.steward.layout.VillageLayout.LotSpec("d", "house", 14, 13));
					var claim = new dev.larattalabs.steward.model.Claim("d", cx, cz, rad, src.getLevel().getMinY(), src.getLevel().getMaxY());
					int fit = dev.larattalabs.steward.layout.VillageLayout.plan(claim, g, specs, dev.larattalabs.steward.layout.VillageLayout.Rules.defaults()).lots().size();
					Steward.LOGGER.info("survey at {},{}: trees {}, missing {}, 4 test lots fit: {}", cx, cz, sm.tree().cardinality(), sm.missing().cardinality(), fit);
					src.sendSuccess(() -> Component.literal("survey at " + cx + "," + cz + ": trees " + sm.tree().cardinality() + ", missing " + sm.missing().cardinality() + ", 4 test lots fit: " + fit), false);
				});
				return 1;
			}))
			.then(Commands.literal("claim").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
				var p = ctx.getSource().getPlayerOrException();
				return dev.larattalabs.steward.item.FoundingStone.claim(p, ctx.getSource().getLevel(), p.blockPosition().below()) ? 1 : 0;
			}))
			.then(Commands.literal("settlements").executes(ctx -> {
				var all = dev.larattalabs.steward.service.Settlements.store().all();
				if (all.isEmpty()) ctx.getSource().sendSuccess(() -> Component.literal("No settlements yet. Right-click a block with a Founding Stone (creative: Tools tab)."), false);
				for (var s : all) ctx.getSource().sendSuccess(() -> Component.literal(s.id() + "  " + s.name() + "  at " + s.claim().centerX() + "," + s.claim().centerZ() + "  " + (s.described() ? s.card().site().text() + " / " + s.card().style().text() : "(not described)")), false);
				return all.size();
			}))
			.then(Commands.literal("describe").then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx ->
				answer(ctx.getSource(), Actions.describe(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "text")))))))
			.then(Commands.literal("start").then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("buildings", IntegerArgumentType.integer(Actions.MIN_BUILDINGS,
				Actions.MAX_BUILDINGS)).then(Commands.argument("budget", DoubleArgumentType.doubleArg(Actions.MIN_BUDGET, Actions.MAX_BUDGET)).executes(ctx ->
					answer(ctx.getSource(), Actions.start(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "buildings"),
						DoubleArgumentType.getDouble(ctx, "budget"))))))))
			.then(Commands.literal("approve").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> decide(ctx, "approve", "", "", 0))))
			.then(Commands.literal("redirect").then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("lot", StringArgumentType.word())
				.then(Commands.argument("notes", StringArgumentType.greedyString()).executes(ctx -> decide(ctx, "redirect", StringArgumentType.getString(ctx, "lot"),
					StringArgumentType.getString(ctx, "notes"), 0))))))
			.then(Commands.literal("raise").then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("budget", DoubleArgumentType.doubleArg(Actions.MIN_BUDGET,
				Actions.MAX_RAISE)).executes(ctx -> decide(ctx, "raise", "", "", DoubleArgumentType.getDouble(ctx, "budget"))))))
			.then(Commands.literal("cancel").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> decide(ctx, "cancel", "", "", 0))))
			.then(Commands.literal("updates").executes(ctx -> {
				// look again for newer versions of placed buildings (they are found at world load and when Architect announces a version)
				dev.larattalabs.steward.service.Updates.refreshAll(ctx.getSource().getServer());
				ctx.getSource().sendSuccess(() -> Component.literal("Checked for building updates: see the inbox (Y)."), false);
				return 1;
			}))
			.then(Commands.literal("expand").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> decide(ctx, "expand", "", "", 0))))
			.then(Commands.literal("show").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> decide(ctx, "show", "", "", 0))))
			.then(Commands.literal("hide").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> decide(ctx, "hide", "", "", 0))))
			.then(Commands.literal("dev").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.literal("place").then(Commands.argument("id",
				StringArgumentType.word()).then(Commands.argument("entry", StringArgumentType.word()).executes(ctx -> {
					// dev (the e2e check): place a library entry for a settlement 6 blocks in front of the player, as the settlement's owner, logged like a build
					CommandSourceStack src = ctx.getSource();
					var p = src.getPlayerOrException();
					String id = StringArgumentType.getString(ctx, "id");
					var s = dev.larattalabs.steward.service.Settlements.store().get(id);
					if (s.isEmpty()) { src.sendFailure(Component.literal("No such settlement: " + id)); return 0; }
					com.google.gson.JsonObject ext = new com.google.gson.JsonObject();
					ext.addProperty("steward_mc:settlement", id);
					ext.addProperty("steward_mc:lot", "dev_1");
					ext.addProperty("steward_mc:role", "dev building");
					var at = p.blockPosition().relative(net.minecraft.core.Direction.SOUTH, 6);
					ArchitectApi.get().sites(src.getServer()).place(new dev.larattalabs.architect.api.PlaceRequest(StringArgumentType.getString(ctx, "entry"), src.getLevel(), at,
						net.minecraft.world.level.block.Rotation.NONE, dev.larattalabs.architect.api.Mode.INSTANT, s.get().owner(), ext, false, null)).whenComplete((r, err) -> {
							if (err != null || !r.placed()) {
								p.sendSystemMessage(Component.literal("dev place refused: " + (err != null ? err.getMessage() : r.refusals())));
								return;
							}
							String site = r.siteId().orElse("?");
							dev.larattalabs.steward.service.Settlements.log(id, new dev.larattalabs.steward.model.Settlement.LogEntry(System.currentTimeMillis(),
								dev.larattalabs.steward.model.Settlement.Kind.PROJECT_PLACED, "Placed 1 buildings (dev)", java.util.List.of(site)));
							p.sendSystemMessage(Component.literal("dev placed " + site));
						});
					return 1;
				})))))
			.then(Commands.literal("ui").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("screen", StringArgumentType.word())
				.then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
					// dev: open a screen without the right-click (DevBridge cannot use entities)
					var p = ctx.getSource().getPlayerOrException();
					String id = StringArgumentType.getString(ctx, "id");
					var s = dev.larattalabs.steward.service.Settlements.store().get(id);
					switch (StringArgumentType.getString(ctx, "screen")) {
						case "inbox" -> {
							SettlementRunner.sendInbox(ctx.getSource().getServer(), p.getUUID());
							net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.larattalabs.steward.net.StewardNet.OpenInbox(id));
						}
						case "describe" -> net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.larattalabs.steward.net.StewardNet.OpenDescribe(id,
							s.map(x -> x.name()).orElse(id), ""));
						case "card" -> { if (s.isPresent() && s.get().described()) Actions.sendCard(p, s.get()); }
						case "open" -> s.ifPresent(x -> Actions.openFor(p, x));
						case "sample" -> {
							// a made-up inbox for screenshots: no build is touched, decisions from it come back as "nothing is being built"
							var lots = java.util.List.of(
								new dev.larattalabs.steward.inbox.InboxModel.Lot("tavern_1", "harbourmaster's hall", "approval", true, true, "14x13, 18 tall (stilts, common_room, lodging, roof, porch)"),
								new dev.larattalabs.steward.inbox.InboxModel.Lot("cabin_1", "fisher's stilt house", "approval", false, true, "10x9, 13 tall (stilts, hut, roof, chimney, porch)"),
								new dev.larattalabs.steward.inbox.InboxModel.Lot("tower_1", "lookout tower", "approval", false, true, "8x8, 21 tall (stilts, shaft, crown, roof)"),
								new dev.larattalabs.steward.inbox.InboxModel.Lot("shed_1", "drying rack shed", "massing", false, false, "11x9 lot"));
							var lots2 = java.util.List.of(
								new dev.larattalabs.steward.inbox.InboxModel.Lot("glassworks_1", "glassblowers' hall", "done", true, false, "18x15 lot"),
								new dev.larattalabs.steward.inbox.InboxModel.Lot("workshop_1", "glassblower's workshop", "done", false, false, "14x12 lot"),
								new dev.larattalabs.steward.inbox.InboxModel.Lot("workshop_2", "lantern maker's workshop", "detail", false, false, "14x12 lot"),
								new dev.larattalabs.steward.inbox.InboxModel.Lot("cottage_1", "artisan cottage", "detail", false, false, "11x9 lot"));
							var a1 = new dev.larattalabs.steward.inbox.InboxModel.Entry(id, "Stilt Swamp Fishing Village", "MASSINGS", "3 massings wait for you: approve them, or send one back with notes.",
								java.util.List.of("Buildings: 3 approval", "Spent $3.12 of $35"), lots, 35, 3.12, 10);
							var a2 = new dev.larattalabs.steward.inbox.InboxModel.Entry(id + "_b", "Lantern Shore", "BUDGET", "Paused at 80% of the $30 budget. Raise it to at least $40 to go on.",
								java.util.List.of("Buildings: 2 done, 2 detail", "Spent $26.38 of $30"), lots2, 30, 26.38, 40);
							net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.larattalabs.steward.net.StewardNet.Inbox(java.util.List.of(a1, a2)));
							net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.larattalabs.steward.net.StewardNet.OpenInbox(id));
						}
						default -> { ctx.getSource().sendFailure(Component.literal("inbox, describe, card, open or sample")); return 0; }
					}
					return 1;
				}))))
			.then(Commands.literal("undo").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
				CommandSourceStack src = ctx.getSource();
				var s = dev.larattalabs.steward.service.Settlements.store().get(StringArgumentType.getString(ctx, "id"));
				if (s.isEmpty()) { src.sendFailure(Component.literal("No such settlement (see /steward settlements).")); return 0; }
				if (SettlementRunner.busy(s.get().id())) { src.sendFailure(Component.literal(s.get().id() + " is being built; cancel it first (/steward cancel " + s.get().id() + ").")); return 0; }
				return dev.larattalabs.steward.service.Undo.lastProject(src.getServer(), s.get(), line -> src.sendSuccess(() -> Component.literal(line), false)) ? 1 : 0;
			})))
			.then(Commands.literal("resume").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("group", StringArgumentType.word()).then(Commands.argument("card", StringArgumentType.word())
				.then(Commands.argument("buildings", IntegerArgumentType.integer(1, 12)).then(Commands.argument("budget", DoubleArgumentType.doubleArg(1, 200)).executes(ctx -> {
					CommandSourceStack src = ctx.getSource();
					SettlementRunner runner = new SettlementRunner(src.getServer(), src.getLevel(), src.getPlayerOrException(), Permission.FULL, service(), 0);
					runner.resume(StringArgumentType.getString(ctx, "group"), StringArgumentType.getString(ctx, "card"), IntegerArgumentType.getInteger(ctx, "buildings"),
						DoubleArgumentType.getDouble(ctx, "budget"));
					return 1;
				}))))))
			.then(Commands.literal("build").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("buildings", IntegerArgumentType.integer(1, 12)).then(Commands.argument("budget", DoubleArgumentType.doubleArg(1, 200))
				.then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
					CommandSourceStack src = ctx.getSource();
					var player = src.getPlayerOrException();
					int n = IntegerArgumentType.getInteger(ctx, "buildings");
					double budget = DoubleArgumentType.getDouble(ctx, "budget");
					SettlementRunner runner = new SettlementRunner(src.getServer(), src.getLevel(), player, Permission.FULL, service(), n >= 8 ? 2 : 0);
					runner.start(StringArgumentType.getString(ctx, "text"), n, budget);
					return 1;
				})))))
			.then(Commands.literal("card").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
				CommandSourceStack src = ctx.getSource();
				String text = StringArgumentType.getString(ctx, "text");
				src.sendSuccess(() -> Component.literal("Interpreting your description..."), false);
				// the world's survival toggle, read (Architect API 1.2.0 Sites.survival())
				boolean survival = WorldMode.survival(src.getServer()).orElse(false);
				service().submit(text, Map.of(), new ConceptCardJob.Settings(survival, survival ? "supplied" : "patron", dev.larattalabs.steward.service.Settlements.DEFAULT_RADIUS), null).whenComplete((r, err) -> {
					// completes on the server thread (JOB_DONE), or immediately when refused
					Steward.LOGGER.info("concept card: {}", err != null ? "refused: " + err.getMessage() : r.ok() ? String.format("ok, $%.4f, %s", r.cost().usd(), r.card().name()) : "failed: " + r.error());
					if (err != null) {
						src.sendFailure(Component.literal(String.valueOf(err.getMessage() != null ? err.getMessage() : err)));
					} else if (!r.ok()) {
						src.sendFailure(Component.literal(r.error()));
					} else {
						for (String line : CardResult.lines(r.card(), r.cost())) src.sendSuccess(() -> Component.literal(line), false);
					}
				});
				return 1;
			})))));
	}

	private static int answer(CommandSourceStack src, Actions.Result r) {
		if (r.message().isEmpty()) return r.ok() ? 1 : 0;
		if (r.ok()) src.sendSuccess(() -> Component.literal(r.message()), false);
		else src.sendFailure(Component.literal(r.message()));
		return r.ok() ? 1 : 0;
	}

	private static int decide(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String action, String lot, String text, double amount)
		throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		return answer(ctx.getSource(), Actions.decide(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id"), action, lot, text, amount));
	}

	private static CardService service() {
		return Actions.cards();
	}
}
