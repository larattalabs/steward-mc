package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BesideRefusalTest {
	@Test
	void architectsNamedRefusalsReadInStewardsWords() {
		assertEquals("it would not touch the town.", Districts.besideRefusal("NO_JOIN", "NO_JOIN: [apart] the district's claim neither overlaps nor touches"));
		assertEquals("the town's wall closes every way in (a gate would let a road through).", Districts.besideRefusal("NO_JOIN", "NO_JOIN: [wall] enclosed"));
		assertEquals("no road could be found from there to the town's street.", Districts.besideRefusal("NO_JOIN", "NO_JOIN: [budget] 48 routes"));
		assertEquals("it would cross a protected area.", Districts.besideRefusal("PROTECTED", "area pa_1"));
		// (raw-sweep) anything else: the generic line, never Architect's message
		assertEquals(RefusalWords.GENERIC + ".", Districts.besideRefusal(null, "beside: no standing region"));
	}
}
