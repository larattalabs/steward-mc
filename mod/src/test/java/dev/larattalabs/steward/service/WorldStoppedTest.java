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

	@Test
	void aLostAnswerIsLookedUpARefusalIsNot() {
		assertTrue(SettlementRunner.uncertain(new CompletionException(new java.util.concurrent.TimeoutException("no ack in 20 s"))));
		assertTrue(SettlementRunner.uncertain(new CompletionException(new ArchitectRefused(Reason.SIDECAR_UNAVAILABLE, "the helper is not connected"))));
		assertTrue(SettlementRunner.uncertain(new IllegalStateException("Sidecar link lost")));
		assertTrue(SettlementRunner.uncertain(new IllegalStateException("sidecar connection lost: closed by the sidecar (1000)")), "Architect 0.13's own words");
		assertFalse(SettlementRunner.uncertain(new IllegalStateException("no bible \"bib_blinkhaven\"")), "a refusal that happens to hold 'link'");
		assertFalse(SettlementRunner.uncertain(new CompletionException(new ArchitectRefused(Reason.OTHER, "the open type needs phase 4b"))));
		assertFalse(SettlementRunner.uncertain(null));
		assertTrue(SettlementRunner.keyConflict(new CompletionException(new ArchitectRefused(Reason.OP_KEY_CONFLICT, "another body"))));
		assertFalse(SettlementRunner.keyConflict(new CompletionException(new ArchitectRefused(Reason.OTHER, "no"))));
	}

	@Test
	void keysAreTrustedForLessThanArchitectKeepsThem() {
		// Architect keeps a key at least 30 days after its work ends: an empty lookup older than this proves nothing
		assertTrue(SettlementRunner.KEY_TRUST_MS < 30L * 24 * 3600_000);
	}
}
