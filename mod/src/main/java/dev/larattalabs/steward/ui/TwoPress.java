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

	/** A held key's latch lapses after this long with no event from it (a press the game never released, a synthetic one; a held key repeats far faster). */
	public static final long HELD_LAPSE_MS = 3000;
	/** The key held down (its code) and when it last went down or repeated; -1: none held. */
	private int heldKey = -1;
	private long heldAt;
	private boolean repeat;

	/**
	 * The key {@code key} going down at {@code now}: as {@link #press}, but a key held down (the game repeats a held key's presses) counts once until it is
	 * released ({@link #up}), so holding Enter never arms and then acts. Each key is held on its own: another key's release frees nothing.
	 */
	public boolean down(int key, long now) {
		repeat = heldKey == key && now - heldAt < HELD_LAPSE_MS;
		heldKey = key;
		heldAt = now;
		if (repeat) return false;
		return press(now);
	}

	/** Whether the last {@link #down} was a held key's repeat (ignored). */
	public boolean repeated() {
		return repeat;
	}

	/** The key {@code key} released: its next going down is a new press. */
	public void up(int key) {
		if (key == heldKey) heldKey = -1;
	}
}
