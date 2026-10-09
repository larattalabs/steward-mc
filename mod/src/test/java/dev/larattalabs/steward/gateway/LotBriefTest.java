package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.DesignRequest;
import dev.larattalabs.steward.layout.VillageLayout.Front;
import dev.larattalabs.steward.layout.VillageLayout.Lot;
import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import java.io.InputStreamReader;
import org.junit.jupiter.api.Test;

class LotBriefTest {
	private static Settlement settlement(String fixture) throws Exception {
		var in = LotBriefTest.class.getClassLoader().getResourceAsStream(fixture + ".json");
		ConceptCard c = ConceptCard.parse(JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
		return Settlement.found("set_1", c, new Claim("minecraft:overworld", 0, 0, 128, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
	}

	@Test
	void carriesStylePurposeOwnerAndLotIdentity() throws Exception {
		Settlement s = settlement("crater_works");
		DesignRequest r = LotBrief.build(s, new Lot("lot_3", "smithy", 0, 0, 20, 14, Front.SOUTH, 70), 24, "claude-sonnet-5-5", 1.5);
		assertEquals("smithy", r.type());
		assertEquals("hellish evil lair", r.style());
		assertEquals("steward_mc:settlement/set_1", r.owner());
		assertEquals("lot_3", r.ext().get("steward_mc:lot").getAsString());
		assertEquals(20, r.maxSize().x());
		assertEquals(24, r.maxSize().y());
		assertEquals(14 - LotBrief.APPROACH_MARGIN, r.maxSize().z());
		assertEquals("claude-sonnet-5-5", r.model());
		assertEquals(1.5, r.budgetUsd());
		assertTrue(r.notes().contains("mining facility"));
		assertTrue(r.notes().contains("repurposed after the impact"));
	}

	@Test
	void unknownTypesBecomeCustomAndSizesAreClampedToArchitectsCap() throws Exception {
		Settlement s = settlement("stilt_village");
		DesignRequest r = LotBrief.build(s, new Lot("lot_9", "ore_hall", 0, 0, 120, 5, Front.NORTH, 70), 200, null, null);
		assertEquals("custom", r.type());
		assertEquals(96, r.maxSize().x());
		assertEquals(7, r.maxSize().z());
		assertEquals(64, r.maxSize().y());
		assertNull(r.model());
		assertTrue(r.notes().contains("custom building"));
	}

	@Test
	void avoidListReachesTheNotes() throws Exception {
		Settlement s = settlement("sky_temple");
		DesignRequest r = LotBrief.build(s, new Lot("lot_1", "chapel", 0, 0, 12, 16, Front.SOUTH, 70), 30, null, null);
		assertTrue(r.notes().contains("Avoid: dark stone"));
	}
}
