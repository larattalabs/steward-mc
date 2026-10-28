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
		// the second review's: an outpost named as a place, and a negation of another one
		assertNull(Talk.quarterOf("a district east of the old outpost"));
		assertNull(Talk.quarterOf("an ordinary east district, not another outpost"));
		assertNull(Talk.quarterOf("no more outposts, a district to the north"));
		assertEquals("outpost", Talk.quarterOf("build another outpost to the west").kind());
		assertEquals("outpost", Talk.quarterOf("a new outpost past the old one").kind());
		assertEquals("outpost", Talk.quarterOf("not a district but an outpost").kind());
	}
}
