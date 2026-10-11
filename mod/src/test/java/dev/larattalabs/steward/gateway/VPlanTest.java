package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.BlockSize;
import dev.larattalabs.architect.api.LotSpec;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.model.Difficulty;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class VPlanTest {
	private static final List<VillageLayout.LotSpec> SPECS = List.of(
		new VillageLayout.LotSpec("hall_1", "tavern", 18, 15, "harvest hall", "the heart of it", true, "central"),
		new VillageLayout.LotSpec("cottage_1", "cottage", 11, 9, "farmhouse", null, false, null),
		new VillageLayout.LotSpec("cottage_2", "cottage", 11, 9, "farmhouse", null, false, null));

	@Test
	void onlyAPatronSettlementsFirstWholeBuildOnFlatGround() {
		assertTrue(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "find", false, 64, 70));
		assertTrue(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "flat", false, 64, 64));
		assertFalse(VPlan.applies("set_1", Difficulty.SUPPLIED, false, true, false, null, "flat", false, 64, 64), "Supplied keeps today's path");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, true, true, false, null, "flat", false, 64, 64), "a survival world");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, true, null, "flat", false, 64, 64), "an addition");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, "dst_1", "flat", false, 64, 64), "a district");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "sculpt", false, 64, 64), "a sculpted form");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "flat", true, 64, 64), "something stands");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, false, false, null, "flat", false, 64, 64), "not the Overworld");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "find", false, 60, 80), "not flat");
		VPlan.legacy("set_1", true);
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "flat", false, 64, 64), "set to Steward's own layout");
		VPlan.clear();
	}

	@Test
	void theLotsGoAsTheContractSays() {
		JsonObject p = VPlan.params(SPECS);
		JsonArray lots = p.getAsJsonArray("lots");
		assertEquals(3, lots.size());
		JsonObject hall = lots.get(0).getAsJsonObject();
		assertEquals("hall_1", hall.get("id").getAsString());
		assertEquals(18, hall.getAsJsonArray("size").get(0).getAsInt());
		assertTrue(hall.get("landmark").getAsBoolean());
		assertEquals("central", hall.get("placement").getAsString());
		assertEquals("the heart of it", hall.get("brief").getAsString());
		assertFalse(hall.has("villager"), "Architect infers it from the type");
		assertFalse(hall.has("ext"), "no tags without a settlement");
		JsonObject tagged = VPlan.params(SPECS, "set_9", "b42").getAsJsonArray("lots").get(1).getAsJsonObject().getAsJsonObject("ext");
		assertEquals("set_9", tagged.get("steward_mc:settlement").getAsString());
		assertEquals("cottage_1", tagged.get("steward_mc:lot").getAsString());
		assertEquals("farmhouse", tagged.get("steward_mc:role").getAsString());
		assertEquals("b42", tagged.get(BatchPlanner.BUILD_EXT).getAsString());
		assertTrue(tagged.toString().length() < 4096, "within Architect's 4 KB a lot");
	}

	@Test
	void plannedLotsBecomeStewardsPlanWithTheUnplacedListed() {
		JsonObject ext = new JsonObject();
		JsonObject v = new JsonObject();
		JsonArray entrance = new JsonArray();
		entrance.add(105);
		entrance.add(65);
		entrance.add(209);
		v.add("entrance", entrance);
		ext.add("architect_mc:village", v);
		var placed = List.of(new LotSpec("cottage_1", "lots", new BoundingBox(100, 65, 195, 110, 96, 208), 65, Direction.SOUTH, null, new BlockSize(11, 24, 9), ext),
			new LotSpec("stranger", "lots", new BoundingBox(0, 65, 0, 5, 96, 5), 65, Direction.NORTH, null, new BlockSize(5, 5, 5), new JsonObject()));
		var plan = VPlan.toPlan(placed, SPECS);
		assertEquals(1, plan.lots().size(), "a lot Steward did not ask for is not its");
		var lot = plan.lots().get(0);
		assertEquals("farmhouse", lot.role());
		assertEquals(100, lot.x());
		assertEquals(11, lot.sizeX());
		assertEquals(14, lot.sizeZ());
		assertEquals(VillageLayout.Front.SOUTH, lot.front());
		assertEquals(209, plan.streetZ(), "the street runs past the entrances");
		assertEquals(List.of("hall_1", "cottage_2"), plan.unplaced().stream().map(VillageLayout.LotSpec::id).toList());
		assertEquals("rg7", VPlan.regionOf("region:rg7"));
		assertNull(VPlan.regionOf("g12"));
	}

	@Test
	void aNorthSouthStreetIsSetAside() {
		var south = new LotSpec("cottage_1", "lots", new BoundingBox(100, 65, 195, 110, 96, 208), 65, Direction.SOUTH, null, new BlockSize(11, 24, 9), new JsonObject());
		var east = new LotSpec("cottage_2", "lots", new BoundingBox(120, 65, 195, 133, 96, 205), 65, Direction.EAST, null, new BlockSize(14, 24, 11), new JsonObject());
		assertFalse(VPlan.northSouth(List.of(south)), "lots facing north or south line an east-west street");
		assertTrue(VPlan.northSouth(List.of(south, east)), "a lot facing east lines a north-south street");
		assertFalse(VPlan.northSouth(List.of()));
	}
}
