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
 * {@code /steward}: temporary developer commands until the Founding Stone and the card screen exist.
 * <pre>
 * /steward status          Architect API version, features, whether its Claude link is up
 * /steward card &lt;text&gt;     run the concept-card parse on text and print the card (about 2 cents, Sonnet)
 * </pre>
 */
public final class StewardCommands {
	private static CardService cards;

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
			.then(Commands.literal("survey").executes(ctx -> {
				CommandSourceStack src = ctx.getSource();
				var p = src.getPlayerOrException();
				int cx = p.blockPosition().getX(), cz = p.blockPosition().getZ();
				var area = new net.minecraft.world.level.levelgen.structure.BoundingBox(cx - 64, src.getLevel().getMinY(), cz - 64, cx + 64, src.getLevel().getMaxY(), cz + 64);
				ArchitectApi.get().survey().sample(src.getLevel(), area, 1, dev.larattalabs.architect.api.LoadPolicy.LOADED_ONLY).whenComplete((sm, err) -> {
					if (err != null) { src.sendFailure(Component.literal("survey failed: " + err.getMessage())); return; }
					var g = dev.larattalabs.steward.layout.TerrainGrid.fromSample(sm);
					var specs = java.util.List.of(new dev.larattalabs.steward.layout.VillageLayout.LotSpec("a", "house", 14, 13), new dev.larattalabs.steward.layout.VillageLayout.LotSpec("b", "house", 14, 13),
						new dev.larattalabs.steward.layout.VillageLayout.LotSpec("c", "house", 14, 13), new dev.larattalabs.steward.layout.VillageLayout.LotSpec("d", "house", 14, 13));
					var claim = new dev.larattalabs.steward.model.Claim("d", cx, cz, 64, 0, 400);
					int fit = dev.larattalabs.steward.layout.VillageLayout.plan(claim, g, specs, dev.larattalabs.steward.layout.VillageLayout.Rules.defaults()).lots().size();
					Steward.LOGGER.info("survey at {},{}: trees {}, missing {}, 4 test lots fit: {}", cx, cz, sm.tree().cardinality(), sm.missing().cardinality(), fit);
					src.sendSuccess(() -> Component.literal("survey at " + cx + "," + cz + ": trees " + sm.tree().cardinality() + ", missing " + sm.missing().cardinality() + ", 4 test lots fit: " + fit), false);
				});
				return 1;
			}))
			.then(Commands.literal("resume").then(Commands.argument("group", StringArgumentType.word()).then(Commands.argument("card", StringArgumentType.word())
				.then(Commands.argument("buildings", IntegerArgumentType.integer(1, 12)).then(Commands.argument("budget", DoubleArgumentType.doubleArg(1, 200)).executes(ctx -> {
					CommandSourceStack src = ctx.getSource();
					SettlementRunner runner = new SettlementRunner(src.getServer(), src.getLevel(), src.getPlayerOrException(), Permission.FULL, service(), 0);
					runner.resume(StringArgumentType.getString(ctx, "group"), StringArgumentType.getString(ctx, "card"), IntegerArgumentType.getInteger(ctx, "buildings"),
						DoubleArgumentType.getDouble(ctx, "budget"));
					return 1;
				}))))))
			.then(Commands.literal("build").then(Commands.argument("buildings", IntegerArgumentType.integer(1, 12)).then(Commands.argument("budget", DoubleArgumentType.doubleArg(1, 200))
				.then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
					CommandSourceStack src = ctx.getSource();
					var player = src.getPlayerOrException();
					int n = IntegerArgumentType.getInteger(ctx, "buildings");
					double budget = DoubleArgumentType.getDouble(ctx, "budget");
					SettlementRunner runner = new SettlementRunner(src.getServer(), src.getLevel(), player, Permission.FULL, service(), n >= 8 ? 2 : 0);
					runner.start(StringArgumentType.getString(ctx, "text"), n, budget);
					return 1;
				})))))
			.then(Commands.literal("card").then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
				CommandSourceStack src = ctx.getSource();
				String text = StringArgumentType.getString(ctx, "text");
				src.sendSuccess(() -> Component.literal("Interpreting your description..."), false);
				// the world's survival toggle, read (Architect API 1.2.0 Sites.survival())
				boolean survival = WorldMode.survival(src.getServer()).orElse(false);
				service().submit(text, Map.of(), new ConceptCardJob.Settings(survival, survival ? "supplied" : "patron", 128), null).whenComplete((r, err) -> {
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

	private static synchronized CardService service() {
		if (cards == null) {
			cards = new CardService(ArchitectApi.get().jobs());
			SiteEvents.JOB_DONE.register(cards::onDone);
			Steward.LOGGER.info("card service ready");
		}
		return cards;
	}
}
