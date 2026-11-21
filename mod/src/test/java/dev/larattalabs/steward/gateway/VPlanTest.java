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
		assertTrue(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "find", false, false, 64, 70));
		assertTrue(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "flat", false, false, 64, 64));
		assertFalse(VPlan.applies("set_1", Difficulty.SUPPLIED, false, true, false, null, "flat", false, false, 64, 64), "Supplied keeps today's path");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, true, true, false, null, "flat", false, false, 64, 64), "a survival world");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, true, null, "flat", false, false, 64, 64), "an addition");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, "dst_1", "flat", false, false, 64, 64), "a district");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "sculpt", false, false, 64, 64), "a sculpted form");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "flat", false, true, 64, 64), "something stands");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, false, false, null, "flat", false, false, 64, 64), "not the Overworld");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "find", false, false, 60, 80), "not flat");
		VPlan.legacy("set_1", true);
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "flat", false, false, 64, 64), "set to Steward's own layout");
		VPlan.clear();
	}

	/**
	 * (slice M5-SCULPT-2) A sculpted card takes a region plan only when the kept composed plan carries the shaping ({@code planShapes}), in both modes; the
	 * other gates hold either way (first whole build, nothing standing, flat range, the Overworld, the mode, a host's own layout).
	 */
	@Test
	void aSculptedCardPassesOnlyWhenThePlanCarriesTheShapingAndEveryOtherGateHolds() {
		assertTrue(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "sculpt", true, false, 64, 64), "creative: the plan's river");
		assertTrue(VPlan.appliesSupplied("set_1", Difficulty.SUPPLIED, true, true, true, false, null, "sculpt", true, false, 64, 64), "survival: the plan's river");
		assertFalse(VPlan.appliesSupplied("set_1", Difficulty.SUPPLIED, true, true, true, false, null, "sculpt", false, false, 64, 64), "survival: no shaping in the plan");
		assertTrue(VPlan.appliesSupplied("set_1", Difficulty.SUPPLIED, true, true, true, false, null, "flat", false, false, 64, 64), "a flat card needs no shaping");
		// each other gate still refuses a sculpted card whose plan carries the shaping
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "sculpt", true, true, 64, 64), "something stands");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "sculpt", true, false, 60, 80), "not flat");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, false, false, null, "sculpt", true, false, 64, 64), "not the Overworld");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, true, null, "sculpt", true, false, 64, 64), "an addition");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, "dst_1", "sculpt", true, false, 64, 64), "a district");
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, true, true, false, null, "sculpt", true, false, 64, 64), "a survival world");
		assertFalse(VPlan.appliesSupplied("set_1", Difficulty.SUPPLIED, true, true, true, false, null, "sculpt", true, true, 64, 64), "survival: something stands");
		assertFalse(VPlan.appliesSupplied("set_1", Difficulty.SUPPLIED, true, true, true, false, null, "sculpt", true, false, 60, 80), "survival: not flat");
		assertFalse(VPlan.appliesSupplied("set_1", Difficulty.SUPPLIED, true, true, false, false, null, "sculpt", true, false, 64, 64), "survival: not the Overworld");
		assertFalse(VPlan.appliesSupplied("set_1", Difficulty.SUPPLIED, true, true, true, true, null, "sculpt", true, false, 64, 64), "survival: an addition");
		assertFalse(VPlan.appliesSupplied("set_1", Difficulty.SUPPLIED, true, true, true, false, "dst_1", "sculpt", true, false, 64, 64), "survival: a district");
		assertFalse(VPlan.appliesSupplied("set_1", Difficulty.SUPPLIED, false, true, true, false, null, "sculpt", true, false, 64, 64), "survival: not a survival region");
		VPlan.legacy("set_1", true);
		assertFalse(VPlan.applies("set_1", Difficulty.PATRON, false, true, false, null, "sculpt", true, false, 64, 64), "set to Steward's own layout");
		assertFalse(VPlan.appliesSupplied("set_1", Difficulty.SUPPLIED, true, true, true, false, null, "sculpt", true, false, 64, 64), "survival: Steward's own layout");
		VPlan.clear();
	}

	/** (slice M5-SCULPT-2) What carries the shaping: a channel connector or a ground-moving form, not one that waits, not a form that only stands on the land. */
	@Test
	void thePlanCarriesTheShapingWithAChannelOrAGroundMovingFormThatDoesntWait() {
		var json = (java.util.function.Function<String, JsonObject>) t -> com.google.gson.JsonParser.parseString(t).getAsJsonObject();
		java.util.Set<String> none = java.util.Set.of();
		assertTrue(VPlan.carriesShaping(json.apply("{\"connectors\": [{\"id\": \"river\", \"kind\": \"channel\"}]}"), none));
		assertFalse(VPlan.carriesShaping(json.apply("{\"connectors\": [{\"id\": \"river\", \"kind\": \"channel\"}]}"), java.util.Set.of("river")), "it waits");
		assertTrue(VPlan.carriesShaping(json.apply("{\"connectors\": [{\"id\": \"river\", \"kind\": \"channel\"}, {\"id\": \"r2\", \"kind\": \"channel\"}]}"),
			java.util.Set.of("river")), "another channel doesn't wait");
		assertFalse(VPlan.carriesShaping(json.apply("{\"connectors\": [{\"id\": \"main\", \"kind\": \"street\"}, {\"id\": \"w\", \"kind\": \"wall\"}]}"), none), "streets and walls");
		for (String k : VPlan.SHAPING_FORMS) assertTrue(VPlan.carriesShaping(json.apply("{\"forms\": [{\"id\": \"f\", \"kind\": \"" + k + "\"}]}"), none), k);
		for (String k : List.of("giant_tree", "brush", "scatter", "fountain", "hidden_room", "coral"))
			assertFalse(VPlan.carriesShaping(json.apply("{\"forms\": [{\"id\": \"f\", \"kind\": \"" + k + "\"}]}"), none), k);
		assertFalse(VPlan.carriesShaping(json.apply("{\"forms\": [{\"id\": \"f\", \"kind\": \"crater\"}]}"), java.util.Set.of("f")), "a crater that waits");
		// a channel named in forms or a crater in connectors is not what the plan's sections mean
		assertFalse(VPlan.carriesShaping(json.apply("{\"forms\": [{\"id\": \"f\", \"kind\": \"channel\"}], \"connectors\": [{\"id\": \"c\", \"kind\": \"crater\"}]}"), none));
		assertFalse(VPlan.carriesShaping(null, none));
		assertFalse(VPlan.carriesShaping(json.apply("{\"connectors\": {}, \"forms\": [1, \"x\"]}"), none), "unreadable sections");
		assertFalse(VPlan.carriesShaping(json.apply("{}"), none));
	}

	/** (slice M5-SCULPT-2, GPT review P1) The shaping parts by id, whether the check builds one, and their words. */
	@Test
	void theShapingIsBuiltOnlyWhenTheCheckFitsOneOfItsParts() {
		var json = (java.util.function.Function<String, JsonObject>) t -> com.google.gson.JsonParser.parseString(t).getAsJsonObject();
		JsonObject plan = json.apply("""
			{"connectors": [{"id": "main", "kind": "street"}, {"id": "river", "kind": "channel", "label": "River"}],
			 "forms": [{"id": "crater_lake", "kind": "crater"}, {"id": "oak", "kind": "giant_tree"}, {"kind": "relief"}]}""");
		assertEquals(List.of("river", "crater_lake"), VPlan.shapingParts(plan, java.util.Set.of()), "an id-less form can't be matched to its check");
		assertEquals(List.of("crater_lake"), VPlan.shapingParts(plan, java.util.Set.of("river")));
		assertEquals("river and crater lake", VPlan.shapingWords(plan, List.of("river", "crater_lake")));
		assertEquals("river", VPlan.shapingWords(plan, List.of("river")));
		assertEquals("shaping of the land", VPlan.shapingWords(plan, List.of()));
		var shaping = List.of("river", "crater_lake");
		var part = (java.util.function.BiFunction<String, String, JsonObject>) (id, cls) -> json.apply("{\"part\": \"" + id + "\", \"class\": \"" + cls + "\"}");
		assertTrue(VPlan.shapingBuilt(shaping, List.of(part.apply("town", "adjusted"), part.apply("river", "fits"))));
		assertTrue(VPlan.shapingBuilt(shaping, List.of(part.apply("river", "refused"), part.apply("crater_lake", "adjusted"))));
		assertFalse(VPlan.shapingBuilt(shaping, List.of(part.apply("town", "fits"), part.apply("river", "refused"))), "the optional river refused, the rest fits");
		assertFalse(VPlan.shapingBuilt(shaping, List.of(part.apply("river", "incomplete"), part.apply("crater_lake", "malformed"))));
		assertFalse(VPlan.shapingBuilt(shaping, List.of(part.apply("town", "fits"))), "not answered at all");
		assertFalse(VPlan.shapingBuilt(List.of(), List.of(part.apply("river", "fits"))));
	}

	/**
	 * (B092-FIX, batch 092 GPT review P1) A stale artifact's re-check: the frozen plan still holds the optional pond the approved check refused, so a
	 * re-check where the ponds swap (west refused, east now fits) builds a shaping nobody approved. Only the approved shaping, as approved, passes.
	 */
	@Test
	void aRecheckKeepsOnlyTheApprovedShaping() {
		var json = (java.util.function.Function<String, JsonObject>) t -> com.google.gson.JsonParser.parseString(t).getAsJsonObject();
		JsonObject frozen = json.apply("""
			{"connectors": [{"id": "main", "kind": "street"}],
			 "forms": [{"id": "west_pond", "kind": "pond"}, {"id": "east_pond", "kind": "pond"}]}""");
		var shaping = VPlan.shapingParts(frozen, java.util.Set.of());
		assertEquals(List.of("west_pond", "east_pond"), shaping);
		var part = (java.util.function.BiFunction<String, String, JsonObject>) (id, cls) -> json.apply("{\"part\": \"" + id + "\", \"class\": \"" + cls
			+ "\", \"geometry\": {\"rect\": [0, 0, 9, 9]}}");
		var approved = List.of(part.apply("town", "fits"), part.apply("west_pond", "fits"), part.apply("east_pond", "refused"));
		// the transition the review found: the approved pond refused now, the refused one fits
		assertFalse(VPlan.sameShaping(shaping, approved, List.of(part.apply("town", "fits"), part.apply("west_pond", "refused"), part.apply("east_pond", "fits"))),
			"the ponds swapped: east was never approved");
		// both fit now: more shaping than approved
		assertFalse(VPlan.sameShaping(shaping, approved, List.of(part.apply("west_pond", "fits"), part.apply("east_pond", "adjusted"))), "east added");
		// the same pond, built elsewhere (its geometry changed)
		JsonObject moved = json.apply("{\"part\": \"west_pond\", \"class\": \"fits\", \"geometry\": {\"rect\": [4, 0, 13, 9]}}");
		assertFalse(VPlan.sameShaping(shaping, approved, List.of(part.apply("town", "fits"), moved, part.apply("east_pond", "refused"))), "west moved");
		// the approved artifact unreadable: nothing to compare with, so no
		assertFalse(VPlan.sameShaping(shaping, null, List.of(part.apply("west_pond", "fits"), part.apply("east_pond", "refused"))), "unreadable original");
		assertFalse(VPlan.sameShaping(shaping, List.of(part.apply("west_pond", "refused"), part.apply("east_pond", "refused")),
			List.of(part.apply("west_pond", "refused"), part.apply("east_pond", "refused"))), "no shaping built at all");
		// the same shaping, the same geometry: yes (fits and adjusted both build it; the rest of the plan may change)
		assertTrue(VPlan.sameShaping(shaping, approved, List.of(part.apply("town", "adjusted"), part.apply("west_pond", "adjusted"), part.apply("east_pond", "incomplete"))));
		assertTrue(VPlan.sameShaping(shaping, approved, approved));
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
