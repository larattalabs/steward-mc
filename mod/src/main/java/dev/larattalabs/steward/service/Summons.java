package dev.larattalabs.steward.service;

import dev.larattalabs.steward.pipeline.Pipeline;
import java.util.Optional;
import java.util.UUID;

/**
 * Whom a settlement's steward should go and find: the player whose build waits for a decision (the style bible, the massings, the budget, the placement).
 * The steward walks over and waits near them while they are in the settlement (docs/PLAN.md phase 3b, "come find you"). Server thread.
 */
public final class Summons {
	private Summons() {
	}

	public static Optional<UUID> waitingFor(String settlementId) {
		SettlementRunner r = SettlementRunner.active(settlementId);
		if (r == null || r.state() == null || r.state().phase().terminal()) return Optional.empty();
		return Pipeline.awaiting(r.state()) == Pipeline.Decision.NONE ? Optional.empty() : Optional.of(r.playerId());
	}
}
