package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RevertTest {
	@Test
	void backIsTheNewestOlderVersionItStoodAt() {
		assertEquals(1, Revert.previousVersion(List.of(1, 2)));
		assertEquals(1, Revert.previousVersion(List.of(1, 2, 2)), "an instant 3 -> 2 can leave [1, 2, 2]");
		assertEquals(1, Revert.previousVersion(List.of(1, 2, 3, 2)), "a forward revert 3 -> 2 leaves [1, 2, 3, 2]: back is 1, not 3");
		assertEquals(2, Revert.previousVersion(List.of(2, 3)), "placed at 2");
		assertEquals(0, Revert.previousVersion(List.of(1)));
		assertEquals(0, Revert.previousVersion(List.of()));
	}
}
