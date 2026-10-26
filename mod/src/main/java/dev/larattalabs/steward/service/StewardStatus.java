package dev.larattalabs.steward.service;

import dev.larattalabs.steward.inbox.Attention;

/**
 * What a settlement's steward shows on its nameplate: one short activity line and whether it needs the player (the pulsing "!"), both its
 * {@link Attention} (the one the inbox entry and the board show). From its build, else its updates, else what it is doing ({@code idle}: resting, asleep,
 * at work). A steward's idea never lights the "!". Server thread.
 */
public final class StewardStatus {
	public record Status(String activity, boolean needsYou) {}

	private StewardStatus() {
	}

	/** {@code doing}: the steward's own state when no build or update speaks for it ("resting", "asleep", "looking it over", or ""). */
	public static Status of(String settlementId, String doing) {
		Attention a = attention(settlementId, doing);
		return new Status(a.line(), a.needsYou());
	}

	/** The settlement's {@link Attention}: the nameplate, the board and the inbox entry all read this. */
	public static Attention attention(String settlementId, String doing) {
		boolean update = Updates.pending(settlementId).stream().anyMatch(p -> p.action() == dev.larattalabs.steward.gateway.UpdatePlanner.Action.ASK);
		SettlementRunner r = SettlementRunner.active(settlementId);
		if (r != null && r.state() != null && !r.state().phase().terminal()) return r.attention(update, doing);
		return Attention.of(null, 0, null, update, doing, System.currentTimeMillis());
	}
}
