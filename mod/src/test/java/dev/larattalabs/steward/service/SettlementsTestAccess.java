package dev.larattalabs.steward.service;

import dev.larattalabs.steward.model.SettlementStore;

/** (CRASH-T) The settlements store a test outside this package reads through (the inbox, the proposals), and back to none. */
public final class SettlementsTestAccess {
	private SettlementsTestAccess() {}

	/** {@code store} as this session's settlements, with no file (nothing is saved). */
	public static void use(SettlementStore store) {
		Settlements.useForTest(store, null);
	}

	public static void reset() {
		Settlements.resetForTest();
	}
}
