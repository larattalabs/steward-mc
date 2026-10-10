package dev.larattalabs.steward;

import dev.larattalabs.steward.command.StewardCommands;
import dev.larattalabs.steward.gateway.ArchitectGateway;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common entrypoint: the Founding Stone, the per-world settlement store, the steward NPC, the settlement runners and the {@code /steward} commands. */
public class Steward implements ModInitializer {
	public static final String MOD_ID = "steward_mc";
	public static final Logger LOGGER = LoggerFactory.getLogger("steward");

	public static net.minecraft.resources.Identifier id(String path) {
		return net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ArchitectGateway.Status status = ArchitectGateway.check();
		LOGGER.info("Steward common init: {}", status.summary());
		// first: the session changes before any other listener of the same lifecycle event runs
		dev.larattalabs.steward.service.Session.init();
		dev.larattalabs.steward.net.StewardNet.init();
		dev.larattalabs.steward.item.FoundingStone.init();
		dev.larattalabs.steward.item.StewardLedger.init();
		dev.larattalabs.steward.service.Settlements.init();
		dev.larattalabs.steward.entity.StewardNpc.init();
		dev.larattalabs.steward.service.SettlementRunner.init();
		dev.larattalabs.steward.service.Actions.init();
		dev.larattalabs.steward.service.Updates.init();
		dev.larattalabs.steward.service.SettlementSync.init();
		StewardCommands.init();
	}
}
