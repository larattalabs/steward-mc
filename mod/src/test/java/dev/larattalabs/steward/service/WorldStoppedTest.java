package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.architect.api.ArchitectRefused;
import dev.larattalabs.architect.api.Reason;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class WorldStoppedTest {
	@Test
	void theWorldClosingIsNotARequestFailing() {
		assertTrue(SettlementRunner.worldStopped(new CompletionException(new ArchitectRefused(Reason.WORLD_STOPPED, "the world stopped while this was pending"))));
		assertFalse(SettlementRunner.worldStopped(new CompletionException(new ArchitectRefused(Reason.OTHER, "no"))));
		assertFalse(SettlementRunner.worldStopped(new IllegalStateException("link down")));
		assertFalse(SettlementRunner.worldStopped(null));
	}
}
