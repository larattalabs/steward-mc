package dev.larattalabs.steward.view;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** (UI-PR-S) The text size in the nameplate, the bubble and the board. */
class WorldTextTest {
	@Test
	void theFactorIsTheSettingOnlyWhenOptedIn() {
		assertEquals(1f, WorldText.factor(false, 150));
		assertEquals(1.5f, WorldText.factor(true, 150));
		assertEquals(1.25f, WorldText.factor(true, 130), "snapped to a step");
		assertEquals(1f, WorldText.factor(true, 100));
	}

	@Test
	void theSlateKeepsItsWorldSizeAndItsTextGrows() {
		WorldText.Slate at100 = WorldText.slate(1f);
		assertEquals(144, at100.w());
		assertEquals(96, at100.h());
		assertEquals(85, at100.foot());
		WorldText.Slate at150 = WorldText.slate(1.5f);
		assertEquals(at100.w() / at100.ppb(), at150.w() / at150.ppb(), 0.05f, "3 blocks wide either way");
		assertEquals(96, at150.w());
		assertEquals(64, at150.h());
		// under a one-line status (y 34): 4 rows at 100 %, and at 150 % one slot, which counts the rest
		assertEquals(4, at100.slots(34));
		assertEquals(1, at150.slots(34));
		assertEquals(1, at150.slots(60), "never fewer than one");
	}
}
