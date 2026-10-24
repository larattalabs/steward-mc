package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParseException;
import dev.larattalabs.steward.model.Land.Run;
import java.util.List;
import org.junit.jupiter.api.Test;

class LandTest {
	private static final String OW = "minecraft:overworld";

	@Test
	void runsAreSortedAndTouchingRunsInARowJoin() {
		Land l = new Land(OW, List.of(new Run(2, 5, 9), new Run(1, 0, 3), new Run(1, 4, 6), new Run(1, 10, 12)));
		assertEquals(List.of(new Run(1, 0, 6), new Run(1, 10, 12), new Run(2, 5, 9)), l.runs(), "0..3 and 4..6 touch: one run; 10..12 stays apart");
		assertEquals(new Land.Rect(0, 1, 12, 2), l.rect());
		assertEquals(7 + 3 + 5, l.cells());
	}

	@Test
	void containsAndContainsBoxReadTheRuns() {
		Land l = new Land(OW, List.of(new Run(0, 0, 4), new Run(0, 8, 9), new Run(1, 0, 9)));
		assertTrue(l.contains(OW, 4, 0));
		assertFalse(l.contains(OW, 6, 0), "the gap in row 0");
		assertFalse(l.contains("minecraft:the_nether", 4, 0));
		assertTrue(l.containsBox(OW, 0, 0, 4, 1));
		assertFalse(l.containsBox(OW, 3, 0, 8, 1), "row 0 has a gap under the box");
		assertFalse(l.containsBox(OW, 0, 1, 0, 2), "row 2 isn't land");
	}

	@Test
	void overlapsIsColumnExact() {
		Land a = new Land(OW, List.of(new Run(0, 0, 4), new Run(1, 0, 0)));
		assertTrue(a.overlaps(new Land(OW, List.of(new Run(0, 4, 6)))));
		assertFalse(a.overlaps(new Land(OW, List.of(new Run(0, 5, 6), new Run(1, 1, 9)))), "inside the rect, but no shared column");
		assertFalse(a.overlaps(new Land("minecraft:the_nether", List.of(new Run(0, 0, 4)))));
		Claim c = new Claim(OW, 20, 20, 8, -64, 320);
		assertTrue(Land.of(c).overlaps(c));
		assertFalse(a.overlaps(c));
	}

	@Test
	void aClaimsLandIsItsSquare() {
		Claim c = new Claim(OW, 100, -20, 8, -64, 320);
		Land l = Land.of(c);
		assertEquals(17 * 17, l.cells());
		assertEquals(new Land.Rect(92, -28, 108, -12), l.rect());
		assertEquals(l, Land.ofRect(OW, 108, -12, 92, -28));
	}

	@Test
	void unionKeepsBoth() {
		Land a = Land.ofRect(OW, 0, 0, 2, 0), b = Land.ofRect(OW, 3, 0, 5, 1);
		assertEquals(List.of(new Run(0, 0, 5), new Run(1, 3, 5)), a.union(b).runs());
	}

	@Test
	void minusLeavesTheColumnsTheOtherDoesNotHold() {
		Land a = new Land(OW, List.of(new Run(0, 0, 9), new Run(1, 0, 9)));
		Land b = new Land(OW, List.of(new Run(0, 3, 4), new Run(0, 7, 20)));
		assertEquals(List.of(new Run(0, 0, 2), new Run(0, 5, 6), new Run(1, 0, 9)), a.minus(b).runs());
		assertSame(a, a.minus(new Land(OW, List.of(new Run(5, 0, 9)))), "nothing shared: unchanged");
		assertNull(new Land(OW, List.of(new Run(0, 3, 4))).minus(b), "all of it taken");
	}

	@Test
	void landTooWideOrPastTheWorldIsRefused() {
		assertThrows(JsonParseException.class, () -> new Run(0, 0, Integer.MAX_VALUE), "a run to the end of int would loop forever when walked");
		assertThrows(JsonParseException.class, () -> new Run(0, -40_000_000, -39_999_990));
		assertThrows(JsonParseException.class, () -> new Run(0, 0, Land.MAX_SPAN));
		assertDoesNotThrow(() -> Land.of(new Claim(OW, 0, 0, 2048, -64, 320)), "the largest claim is land");
	}

	@Test
	void badLandIsRefused() {
		assertThrows(JsonParseException.class, () -> new Land(OW, List.of()));
		assertThrows(JsonParseException.class, () -> new Land(null, List.of(new Run(0, 0, 0))));
		assertThrows(JsonParseException.class, () -> new Run(0, 5, 4));
	}
}
