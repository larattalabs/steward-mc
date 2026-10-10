package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimRulesTest {
	private static Claim at(int x, int z, int r) {
		return new Claim("minecraft:overworld", x, z, r, -64, 320);
	}

	@Test
	void theCardsSizeSetsTheClaim() {
		assertEquals(48, ClaimRules.radiusFor("S"));
		assertEquals(64, ClaimRules.radiusFor(null));
		assertEquals(128, ClaimRules.radiusFor("XL"));
		assertEquals(193, ClaimRules.side(ClaimRules.radiusFor("L")));
		var o = ClaimRules.growTo(at(0, 0, 64), ClaimRules.radiusFor("XL"), List.of());
		assertEquals(128, o.radius());
		assertEquals("", o.note());
	}

	@Test
	void aClaimNeverShrinks() {
		var o = ClaimRules.growTo(at(0, 0, 96), ClaimRules.radiusFor("S"), List.of());
		assertEquals(96, o.radius(), "an S card keeps a claim that was grown before");
	}

	@Test
	void aNeighbourStopsTheClaimAtTheLargestSizeThatFits() {
		// a neighbour 180 east with radius 64: XL overlaps (128 + 64 >= 180), L fits (96 + 64 < 180)
		var o = ClaimRules.growTo(at(0, 0, 64), 128, List.of(at(180, 0, 64)));
		assertEquals(96, o.radius());
		assertTrue(o.note().contains("180,0"), o.note());
		// a neighbour right next to it: nothing larger fits
		var stuck = ClaimRules.growTo(at(0, 0, 64), 128, List.of(at(140, 0, 64)));
		assertEquals(64, stuck.radius());
		assertTrue(stuck.note().startsWith("The claim stays"), stuck.note());
	}

	@Test
	void expandGrowsOneStepAndStopsAtXl() {
		assertEquals(96, ClaimRules.expand(at(0, 0, 64), List.of()).radius());
		var max = ClaimRules.expand(at(0, 0, 128), List.of());
		assertEquals(128, max.radius());
		assertTrue(max.note().contains("districts"), max.note());
		assertEquals(64, ClaimRules.expand(at(0, 0, 64), List.of(at(140, 0, 64))).radius(), "refused onto a neighbour");
	}
}
