package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.Land;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TownRegionTest {
	private static final String OW = "minecraft:overworld";
	private static final Claim TOWN = new Claim(OW, 0, 0, 64, -64, 320);

	private static Settlement placed(String... regions) {
		Settlement s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);
		long at = 2;
		for (String r : regions) s = s.withLog(new Settlement.LogEntry(at++, Settlement.Kind.PROJECT_PLACED, "placed", List.of(), "region:" + r));
		return s;
	}

	private static final TownRegion.Seen TOWN_SEEN = new TownRegion.Seen(true, null, "b1");

	@Test
	void theTownIsTheNewestStandingRegionThatIsNeitherADistrictNorAPart() {
		Map<String, TownRegion.Seen> seen = new HashMap<>();
		seen.put("rg1", TOWN_SEEN);
		seen.put("rg2", new TownRegion.Seen(true, null, PendingOffers.TAG + "set_1:wall"));
		seen.put("rg3", new TownRegion.Seen(true, "rg1", "b2"));
		assertEquals(Optional.of("rg1"), TownRegion.pick(placed("rg1", "rg2", "rg3"), id -> Optional.ofNullable(seen.get(id))),
			"a part's region and a district's (beside the town) are newer, never the town");
	}

	@Test
	void aDistrictNamedInTheLandsIsNeverTheTownEvenWhenItsTownIsGone() {
		Map<String, TownRegion.Seen> seen = new HashMap<>();
		seen.put("rg1", new TownRegion.Seen(false, null, "b1"));
		// a district region standing alone after the town was undone (Architect marks it dangling; its beside still names the town)
		seen.put("rg3", new TownRegion.Seen(true, "rg1", "b2"));
		Claim east = new Claim(OW, 113, 0, 48, -64, 320);
		Settlement s = placed("rg1", "rg3");
		s = s.withLands(s.lands().next().withDistrict(new Settlement.District("dst_1", "Harbour", "E", east, 2L, List.of(), false, Settlement.District.BESIDE,
			Settlement.District.STANDING, "rg1", "rg3", Land.of(east), null, "steward.d.dst_1")));
		assertEquals(Optional.empty(), TownRegion.pick(s, id -> Optional.ofNullable(seen.get(id))), "no town region: a district founded now is refused");
	}

	@Test
	void aTownPlacedAgainAfterAnUndoIsTheNewOne() {
		Map<String, TownRegion.Seen> seen = new HashMap<>();
		seen.put("rg1", new TownRegion.Seen(false, null, "b1"));
		seen.put("rg5", TOWN_SEEN);
		assertEquals(Optional.of("rg5"), TownRegion.pick(placed("rg1", "rg5"), id -> Optional.ofNullable(seen.get(id))));
		assertEquals(Optional.empty(), TownRegion.pick(placed(), id -> Optional.empty()), "never a region town");
	}
}
