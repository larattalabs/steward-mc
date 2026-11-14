package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WordsTest {
	@Test
	void oneIsSingularAndEveryOtherCountPlural() {
		assertEquals("1 site", Words.count(1, "site", "sites"));
		assertEquals("0 sites", Words.count(0, "site", "sites"));
		assertEquals("2 sites", Words.count(2, "site", "sites"));
		assertEquals("1 block", Words.count(1L, "block", "blocks"));
		assertEquals("297 blocks", Words.count(297L, "block", "blocks"));
		assertEquals("-1 block", Words.count(-1, "block", "blocks"));
		assertEquals("1 building has", Words.count(1, "building has", "buildings have"), "a noun can carry its verb");
		assertEquals("4 buildings have", Words.count(4, "building has", "buildings have"));
	}
}
