package dev.larattalabs.steward.service;

import dev.larattalabs.steward.module.ComposedModules;
import dev.larattalabs.steward.module.ModuleOrder;
import java.util.Map;
import java.util.function.Consumer;

/**
 * (LIFT-S) The composed realise's order-sensitive steps, as {@link SettlementRunner} takes them: the build's state is saved before anything is asked of
 * Architect or the player (a hold, a realise under its caps, a re-ask after a put-down refusal), and nothing goes on when the save didn't land. Kept
 * apart so a test drives them over a real build file whose save fails or is cut short by a crash (ComposedRunnerCrashTest): reversing an order here
 * fails it.
 */
final class ComposedSteps {
	private ComposedSteps() {}

	/** The build's save: true when it landed on disk. */
	@FunctionalInterface
	interface Save {
		boolean save();
	}

	/**
	 * Saves, then goes on ({@code then}: the hold put to the player, or the realise sent under its caps): never the other way round, and nothing goes on
	 * when the save didn't land. Whether it went on.
	 */
	static boolean saveThen(Save save, Runnable then) {
		if (!save.save()) return false;
		then.run();
		return true;
	}

	/** What a composed realise refused at put-down ({@code BILL_CAP}) leads to. */
	enum AfterRefusal {
		/** The refusal saved on every order: the next quote is put to the player, also after a restart. */
		REASK,
		/** The refusal couldn't be saved: a restart would find the build placing as approved, so it stops here (nothing was written or taken). */
		STOP_UNSAVED,
		/** A cap named a lot the realise doesn't bind: Steward's error; the build stops, never asked again. */
		STOP_UNKNOWN_LOT
	}

	/**
	 * (QD-2, M5b, LIFT-S) A composed realise refused at put-down: an unknown lot stops; else each order is marked refused ({@code keep}: the runner's
	 * orders now) and saved before anything is asked again; a save that fails puts the orders back as they were.
	 */
	static AfterRefusal refusedAtPutDown(ComposedModules.CapRefusal r, Map<String, ModuleOrder> orders, Consumer<Map<String, ModuleOrder>> keep, Save save) {
		if (r.scope() == ComposedModules.CapRefusal.Scope.UNKNOWN_LOT) return AfterRefusal.STOP_UNKNOWN_LOT;
		keep.accept(SettlementRunner.refusedAtPutDown(orders));
		if (save.save()) return AfterRefusal.REASK;
		// unsaved: the runner's orders as they were (the build stops as saved)
		keep.accept(orders);
		return AfterRefusal.STOP_UNSAVED;
	}
}
