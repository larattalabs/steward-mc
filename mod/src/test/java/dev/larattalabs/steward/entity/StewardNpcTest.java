package dev.larattalabs.steward.entity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StewardNpcTest {
	@Test
	void talksOncePerRightClick() {
		// the server sees a main-hand and an off-hand interact for one click: talk on the first, swallow the second
		assertEquals(StewardNpc.Use.TALK, StewardNpc.use(true, true));
		assertEquals(StewardNpc.Use.SWALLOW, StewardNpc.use(true, false));
		assertEquals(StewardNpc.Use.PASS, StewardNpc.use(false, true));
		assertEquals(StewardNpc.Use.PASS, StewardNpc.use(false, false));
	}
}
