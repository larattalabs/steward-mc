package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TalkQuarterTest {
	@Test
	void aQuarterAskedForInWordsIsTold() {
		assertEquals("shore", Talk.quarterOf("a fishing quarter along the river").boundary());
		assertEquals("shore", Talk.quarterOf("put some huts by the lake").boundary());
		assertEquals("ridge", Talk.quarterOf("a watch post on the ridge").boundary());
		var edge = Talk.quarterOf("a market where the desert meets the jungle");
		assertEquals("biome_edge", edge.boundary());
		assertEquals(List.of("desert", "jungle"), edge.between());
		assertEquals("outpost", Talk.quarterOf("an outpost out by the mine").kind());
		assertNull(Talk.quarterOf("a district to the east for the smiths"), "an ordinary district");
		// the review's false positives: a gate, a negation, a camp that is a building
		assertNull(Talk.quarterOf("a district out by the east gate"));
		assertNull(Talk.quarterOf("an east district, not an outpost"));
		assertNull(Talk.quarterOf("a district with a hunting camp"));
	}
}
