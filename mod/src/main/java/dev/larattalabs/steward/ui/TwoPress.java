package dev.larattalabs.steward.ui;

/**
 * A key that acts only when pressed twice (pure, unit-tested in {@code TwoPressTest}): the first press arms it (the screen says to press again), a second
 * within {@link #WINDOW_MS} acts. Any other key, or the window passing, disarms it. For a key that spends, so a stray Enter starts nothing.
 */
public final class TwoPress {
	public static final long WINDOW_MS = 4000;
	private long armedAt = -1;

	/** A press at {@code now}: true when it is the second (act now), false when it only armed. */
	public boolean press(long now) {
		if (armed(now)) {
			armedAt = -1;
			return true;
		}
		armedAt = now;
		return false;
	}

	/** Whether a first press waits for its second. */
	public boolean armed(long now) {
		return armedAt >= 0 && now - armedAt <= WINDOW_MS;
	}

	public void reset() {
		armedAt = -1;
	}

	private boolean held;

	/**
	 * The key going down at {@code now}: as {@link #press}, but a key held down (the game repeats a held key's presses) counts once until it is
	 * released ({@link #up}), so holding Enter never arms and then acts.
	 */
	public boolean down(long now) {
		if (held) return false;
		held = true;
		return press(now);
	}

	/** The key released: its next going down is a new press. */
	public void up() {
		held = false;
	}
}
