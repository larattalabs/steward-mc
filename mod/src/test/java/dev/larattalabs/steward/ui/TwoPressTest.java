package dev.larattalabs.steward.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TwoPressTest {
	@Test
	void theSecondPressInTimeActsAndTheFirstOnlyArms() {
		TwoPress p = new TwoPress();
		assertFalse(p.armed(0));
		assertFalse(p.press(1000), "the first press arms");
		assertTrue(p.armed(1500));
		assertTrue(p.press(2000), "the second acts");
		assertFalse(p.armed(2000), "and disarms");
		assertFalse(p.press(2100), "a third arms again");
	}

	@Test
	void anotherKeyOrTheWindowPassingDisarms() {
		TwoPress p = new TwoPress();
		p.press(0);
		p.reset();
		assertFalse(p.press(100), "after another key the next press arms again");
		assertFalse(p.armed(100 + TwoPress.WINDOW_MS + 1), "the window passed");
		assertFalse(p.press(100 + TwoPress.WINDOW_MS + 1), "so a late press arms again");
		assertTrue(p.press(200 + TwoPress.WINDOW_MS));
	}

	@Test
	void aHeldKeyCountsOnceUntilReleased() {
		TwoPress p = new TwoPress();
		assertFalse(p.down(0), "the key goes down: armed");
		assertFalse(p.down(50), "repeats while held are not presses");
		assertFalse(p.down(100));
		assertTrue(p.armed(100));
		p.up();
		assertTrue(p.down(200), "released and pressed again: acts");
	}
}
