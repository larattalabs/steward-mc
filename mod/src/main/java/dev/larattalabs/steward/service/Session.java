package dev.larattalabs.steward.service;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Which world session is running. Architect's futures and events can complete after the world that asked for them closed (a singleplayer client opens
 * another world in the same JVM), so every callback that writes Steward state captures {@link #current()} when it is made and does nothing once it differs.
 */
public final class Session {
	private static volatile int generation;

	private Session() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(s -> generation++);
		// at STOPPING, before Architect clears its caches and fails pending futures: a callback that runs during the shutdown must not read those failures
		// as "nothing runs any more" and drop a build's checkpoint
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> generation++);
	}

	public static int current() {
		return generation;
	}

	public static boolean is(int session) {
		return session == generation;
	}
}
