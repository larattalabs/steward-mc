package dev.larattalabs.steward.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.SiteEvents;
import dev.larattalabs.steward.Steward;
import dev.larattalabs.steward.gateway.ArchitectGateway;
import dev.larattalabs.steward.gateway.CardResult;
import dev.larattalabs.steward.gateway.ConceptCardJob;
import dev.larattalabs.steward.service.CardService;
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
				}
				return st.ok() ? 1 : 0;
			}))
			.then(Commands.literal("card").then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
				CommandSourceStack src = ctx.getSource();
				String text = StringArgumentType.getString(ctx, "text");
				src.sendSuccess(() -> Component.literal("Interpreting your description..."), false);
				// Settings: the world's survival toggle has no public Architect API yet, so the world is assumed creative for now.
				service().submit(text, Map.of(), new ConceptCardJob.Settings(false, "patron", 128), null).whenComplete((r, err) -> {
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
