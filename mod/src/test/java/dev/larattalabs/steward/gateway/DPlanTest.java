package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.BlockSize;
import dev.larattalabs.architect.api.LotSpec;
import dev.larattalabs.steward.layout.VillageLayout;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class DPlanTest {
	@Test
	void aDistrictsLotsKeepTheirFrontsAndTheirHeightCaps() {
		var specs = List.of(new VillageLayout.LotSpec("quay_1", "house", 9, 9), new VillageLayout.LotSpec("quay_2", "smithy", 11, 9),
			new VillageLayout.LotSpec("quay_3", "house", 9, 9));
		var placed = List.of(new LotSpec("quay_1", "lots", new BoundingBox(100, 65, 10, 108, 90, 18), 65, Direction.EAST, null, new BlockSize(9, 18, 9), new JsonObject()),
			new LotSpec("quay_2", "lots", new BoundingBox(120, 66, 10, 130, 90, 18), 66, Direction.NORTH, null, new BlockSize(11, 14, 9), new JsonObject()));
		var plan = DPlan.toPlan(placed, specs);
		assertEquals(VillageLayout.Front.EAST, plan.lots().get(0).front(), "an east front stays east (the flat village's adapter would make it south)");
		assertEquals(18, plan.lots().get(0).maxHeight());
		assertEquals(VillageLayout.Front.NORTH, plan.lots().get(1).front());
		assertEquals(14, plan.lots().get(1).maxHeight());
		assertEquals("smithy", plan.lots().get(1).type(), "type and role from the card");
		assertEquals(List.of("quay_3"), plan.unplaced().stream().map(VillageLayout.LotSpec::id).toList());
		assertEquals(100, plan.streetX0());
		assertEquals(130, plan.streetX1());
	}
}
