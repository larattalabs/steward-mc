package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParseException;
import dev.larattalabs.steward.model.Settlement.District;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Quarters (Noah's Q2): an along district or an outpost, founded and built at once beside a region town. */
class QuarterTest {
	private static final String OW = "minecraft:overworld";
	private static final Claim TOWN = new Claim(OW, 0, 0, 64, -64, 320);
	private static final int[] REGION = {-40, -40, 40, 40};

	@Test
	void aQuarterHoldsTheStripBeyondTheClaimAndPlansBackIntoTheRegion() {
		var e = DistrictRules.quarter(TOWN, REGION, List.of(), List.of(), List.of(), "E", DistrictRules.ALONG_DEPTH, "the quay");
		assertTrue(e.ok(), e.note());
		assertEquals(Land.ofRect(OW, 65, -64, 160, 64), e.land(), "the strip, none of the claim");
		assertArrayEquals(new int[] {25, -64, 160, 64}, e.request());
		var n = DistrictRules.quarter(TOWN, REGION, List.of(), List.of(), List.of(), "N", DistrictRules.OUTPOST_DEPTH, "the camp");
		assertEquals(Land.ofRect(OW, -64, -176, 64, -65), n.land(), "north is a quarter's side too (its join may meet the street's side)");
		assertArrayEquals(new int[] {-64, -176, 64, -25}, n.request());
	}

	@Test
	void aQuartersRequestComesFromItsHeldStripNotTheClaimAsItIsNow() {
		var e = DistrictRules.quarter(TOWN, REGION, List.of(), List.of(), List.of(), "E", DistrictRules.OUTPOST_DEPTH, "camp");
		// the claim expanded since: the strip it holds, and so the ground it may be placed on, don't move
		assertArrayEquals(e.request(), DistrictRules.quarterRequest(REGION, e.land(), "E"));
		assertArrayEquals(new int[] {25, -64, 176, 64}, DistrictRules.quarterRequest(REGION, e.land(), "E"));
	}

	@Test
	void aQuarterIsRefusedWhereADistrictOrAnotherSettlementIs() {
		var east = new District("dst_1", "Old", "E", new Claim(OW, 113, 0, 48, -64, 320), 1L, List.of());
		assertFalse(DistrictRules.quarter(TOWN, REGION, List.of(east), List.of(), List.of(), "E", 96, "q").ok(), "one district a side");
		var north = new District("dst_2", "Hill", "N", new Claim(OW, 100, -60, 20, -64, 320), 1L, List.of());
		assertFalse(DistrictRules.quarter(TOWN, REGION, List.of(north), List.of(), List.of(), "E", 96, "q").ok(), "its strip reaches Hill's land");
		assertFalse(DistrictRules.quarter(TOWN, REGION, List.of(), List.of(new Claim(OW, 150, 0, 20, -64, 320)), List.of(), "E", 96, "q").ok());
	}

	@Test
	void theQuarterPayloadIsCheckedAndSaved() {
		assertThrows(JsonParseException.class, () -> new Proposal.Quarter("castle", null, List.of(), null));
		assertThrows(JsonParseException.class, () -> new Proposal.Quarter("along", "river", List.of(), null), "a river's bank is its shore");
		var q = new Proposal.Quarter("along", "biome_edge", List.of("desert", "jungle"), "b");
		var b = new ConceptCard.Building("fisher's hut", "hut", 3, "S", false, null, null);
		Proposal p = Proposal.quarter("E", q, b, "A quarter along the biome edge", "why", 5L, "asked");
		assertTrue(p.foundsQuarter());
		assertFalse(p.foundsDistrict(), "not a square district's founding");
		Settlement s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L)
			.withProposals(new Settlement.Proposals(List.of(p), List.of(), List.of(), 5L));
		SettlementStore st = new SettlementStore();
		st.put(s);
		var back = SettlementStore.fromJson(st.toJson()).get("set_1").orElseThrow().proposals().open().get(0);
		assertEquals(p, back);
		assertEquals(List.of("desert", "jungle"), back.quarter().between());
	}

	@Test
	void aQuarterIsPricedLikeABuildOfItsBuildings() {
		var b3 = new ConceptCard.Building("fisher's hut", "hut", 3, "S", false, null, null);
		var b1 = new ConceptCard.Building("fisher's hut", "hut", 1, "S", false, null, null);
		var q = new Proposal.Quarter("outpost", null, List.of(), null);
		int three = dev.larattalabs.steward.service.Proposals.estimate(Proposal.quarter("E", q, b3, "t", "w", 1L, "asked"));
		int one = dev.larattalabs.steward.service.Proposals.estimate(new Proposal("k", "t", "w", b1, 1L, "asked"));
		assertTrue(three > one, three + " vs " + one);
	}
}
