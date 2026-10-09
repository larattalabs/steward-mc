package dev.larattalabs.steward;

import dev.larattalabs.steward.command.StewardCommands;
import dev.larattalabs.steward.gateway.ArchitectGateway;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common entrypoint. Phase 1 wiring (Founding Stone, claim, steward entity, concept card) lands here. */
public class Steward implements ModInitializer {
	public static final String MOD_ID = "steward_mc";
	public static final Logger LOGGER = LoggerFactory.getLogger("steward");

	@Override
	public void onInitialize() {
		ArchitectGateway.Status status = ArchitectGateway.check();
		LOGGER.info("Steward common init: {}", status.summary());
		dev.larattalabs.steward.item.FoundingStone.init();
		dev.larattalabs.steward.service.Settlements.init();
		StewardCommands.init();
	}
}
