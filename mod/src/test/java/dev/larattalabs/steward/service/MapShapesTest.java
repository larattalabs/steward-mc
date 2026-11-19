package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.BuildStatus;
import dev.larattalabs.architect.api.LotState;
import dev.larattalabs.architect.api.PartBuild;
import dev.larattalabs.architect.api.PartShape;
import dev.larattalabs.architect.api.RegionBuild;
import dev.larattalabs.architect.api.RegionLayout;
import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.architect.api.RegionView;
import dev.larattalabs.architect.api.RemovalStop;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

/** (slice 8bS) The map's pads, routes and connectors from Architect 8b's RegionLayout, filled by BuildStatus; a hidden part never sent. */
class MapShapesTest {
	private static final BoundingBox BOX = new BoundingBox(0, 64, 0, 9, 70, 9);

	private static PartShape shape(String part, String kind, String lot, List<BlockPos> ends) {
		return new PartShape(part, kind, null, BOX, ends, 0, lot, List.of());
	}

	private static RegionView region(RegionState state, List<LotState> lots, RegionBuild build) {
		return new RegionView("rg1", "plan", "sha", "o", new JsonObject(), "grp", new BoundingBox(0, -64, 0, 99, 320, 99), state, List.of(), lots, 0L, Map.of(), null,
			null, List.of(), 0L, null, List.of(), null, build, 0, null, List.of(), RemovalStop.NONE, null, null, null, null);
	}

	private static final Secrets HIDES_VAULT = Secrets.of(JsonParser.parseString("{\"forms\":[{\"id\":\"vault\",\"kind\":\"hidden_room\",\"host\":\"hall_1\"}]}")
		.getAsJsonObject(), null);

	private static RegionLayout layout() {
		return new RegionLayout("rg1", "sha", 0, List.of(shape("hall_1", "lot", "hall_1", List.of()), shape("pad_2", "lot", "pad_2", List.of()),
			shape("east_street", "route", null, List.of(new BlockPos(0, 64, 1), new BlockPos(9, 64, 1))), shape("bridge", "connector.rope_bridge", null,
				List.of(new BlockPos(2, 70, 2), new BlockPos(8, 70, 8))), shape("f_vault", "form.hidden_room", "hall_1", List.of()), shape("f_vault_door", "lot",
					null, List.of()), shape("t1", "tile", null, List.of())));
	}

	@Test
	void padsRoutesAndConnectorsTakeTheirStatusHiddenPartsDropped() {
		var lots = List.of(new LotState("hall_1", "s7", "placed", 0, BOX, new BuildStatus("waiting", "occupied", "s7", Map.of(), 0, 5L, "x", 0, null)),
			new LotState("pad_2", null, "pad", 0, BOX, null));
		var build = new RegionBuild(false, false, 40, Map.of(), List.of(new PartBuild("east_street", "building", List.of("c1"), 10, 4, 0, 0, 0,
			BuildStatus.of(BuildStatus.BUILDING, 0), null)));
		var out = MapShapes.shapes(layout(), region(RegionState.PLACING, lots, build), HIDES_VAULT, true);
		assertEquals(List.of("hall_1", "pad_2", "east_street", "bridge"), out.stream().map(m -> m.part()).toList(), "tiles left out, the hidden room and its door dropped");
		assertEquals("waiting", out.get(0).status());
		assertEquals("occupied", out.get(0).reason());
		assertEquals("s7", out.get(0).site());
		assertEquals("planned", out.get(1).status(), "a pad is planned");
		assertEquals("building", out.get(2).status());
		assertEquals(List.of(0, 1, 9, 1), out.get(2).ends());
		assertEquals("connector", out.get(3).kind());
		assertEquals(List.of(2, 2, 8, 8), out.get(3).ends());
	}

	@Test
	void aCreativeRegionStandingIsBuiltAndWithoutTheFeatureNoStatusIsRead() {
		var lots = List.of(new LotState("hall_1", "s7", "placed", 0, BOX, new BuildStatus("waiting", "occupied", "s7", Map.of(), 0, 5L, "x", 0, null)));
		var creative = MapShapes.shapes(layout(), region(RegionState.PLACED, lots, null), Secrets.NONE, false);
		assertTrue(creative.stream().allMatch(m -> "built".equals(m.status())), creative.toString());
		assertTrue(creative.stream().anyMatch(m -> m.part().equals("f_vault_door")), "no secrets: every lot drawn");
		assertEquals("planned", MapShapes.shapes(layout(), region(RegionState.PLACING, lots, null), Secrets.NONE, false).get(0).status());
	}

	@Test
	void theMapKeepsLotsRoutesAndConnectors() {
		assertEquals("lot", MapShapes.kindOf("lot"));
		assertEquals("route", MapShapes.kindOf("route"));
		assertEquals("connector", MapShapes.kindOf("connector.tunnel"));
		assertNull(MapShapes.kindOf("tile"));
		assertNull(MapShapes.kindOf("form.hollow_mountain"));
		assertNull(MapShapes.kindOf("__lamps__"));
	}

	@Test
	void theLoggedSummaryNamesEachPartsKindBoxAndEnds() {
		String line = MapShapes.summary(layout());
		assertTrue(line.startsWith("7 part(s); lot hall_1 [0,0..9,9] ends=0"), line);
		assertTrue(line.contains("; route east_street [0,0..9,9] ends=2"), line);
		assertTrue(line.contains("; connector.rope_bridge bridge [0,0..9,9] ends=2"), line);
	}

	// ------------------------------------------------------------------ (slice LYW) walls, gates, towers, widths (feature layoutWalls)

	private static PartShape walled(String part, String kind, BoundingBox box, List<BlockPos> ends, String lot, int width, String wall, String wallAt) {
		return new PartShape(part, kind, null, box, ends, 0, lot, List.of(), width, 3, wall, wallAt);
	}

	private static final BoundingBox WALL_BOX = new BoundingBox(0, 64, 0, 40, 70, 40);
	private static final BoundingBox STREET = new BoundingBox(10, 64, 18, 30, 64, 22);

	private static RegionLayout walledLayout() {
		return new RegionLayout("rg1", "sha", 0, List.of(
			walled("town_wall", "connector.wall", WALL_BOX, List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0), new BlockPos(40, 64, 40), new BlockPos(0, 64, 40),
				new BlockPos(0, 64, 0)), null, 2, null, null),
			walled("gatehouse_passage", "connector.gate", new BoundingBox(18, 64, 38, 22, 68, 42), List.of(new BlockPos(20, 64, 42), new BlockPos(20, 64, 38)),
				"gatehouse", 4, "town_wall", "gate"),
			walled("town_wall_tower_1", "tower", new BoundingBox(-2, 64, -2, 2, 74, 2), List.of(), null, 0, "town_wall", "corner"),
			walled("gatehouse", "lot", new BoundingBox(16, 64, 36, 24, 72, 44), List.of(), "gatehouse", 0, "town_wall", "gate"),
			walled("east_street", "route", STREET, List.of(new BlockPos(10, 64, 20), new BlockPos(30, 64, 20)), null, 3, null, null),
			walled("east_street_road_1", "route", STREET, List.of(new BlockPos(10, 64, 20), new BlockPos(30, 64, 20)), null, 5, null, null),
			walled("north_stairs", "route", new BoundingBox(20, 64, -20, 22, 80, 0), List.of(new BlockPos(21, 64, 0), new BlockPos(21, 80, -20)), null, 2, null,
				null),
			walled("t1", "tile", WALL_BOX, List.of(), null, 0, null, null)));
	}

	@Test
	void wallsGatesAndTowersAreDrawnWithTheirWidthsAndWalls() {
		var out = MapShapes.shapes(walledLayout(), region(RegionState.PLACED, List.of(), null), Secrets.NONE, false, true);
		assertEquals(List.of("town_wall", "gatehouse_passage", "town_wall_tower_1", "gatehouse", "east_street", "north_stairs"), out.stream().map(m -> m.part())
			.toList(), "the tile left out; the street's second copy (same box) dropped");
		var wall = out.get(0);
		assertEquals("wall", wall.kind());
		assertEquals(2, wall.width(), "its thickness");
		assertEquals(List.of(0, 0, 40, 0, 40, 40, 0, 40, 0, 0), wall.ends(), "a closed wall returns to its first point");
		assertNull(wall.wall());
		var gate = out.get(1);
		assertEquals("gate", gate.kind());
		assertEquals(4, gate.width());
		assertEquals(List.of(20, 42, 20, 38), gate.ends(), "the passage's front and back");
		assertEquals("town_wall", gate.wall());
		assertEquals("gatehouse", gate.lot());
		var tower = out.get(2);
		assertEquals("tower", tower.kind());
		assertEquals("corner", tower.wallAt());
		assertEquals(List.of(-2, -2, 2, 2), List.of(tower.minX(), tower.minZ(), tower.maxX(), tower.maxZ()));
		var lot = out.get(3);
		assertEquals("lot", lot.kind(), "a gatehouse lot stays a lot");
		assertEquals("town_wall", lot.wall(), "a wall lot: tinted on the map");
		assertEquals("gate", lot.wallAt());
		assertEquals("route", out.get(4).kind());
		assertEquals(5, out.get(4).width(), "a route given twice: kept once, at the wider width");
		assertEquals(2, out.get(5).width(), "a district's stairs: one route at its width");
		assertTrue(out.stream().allMatch(m -> "built".equals(m.status())), "a creative region standing: all built");
	}

	@Test
	void routesAreDedupedByTheirWholeBoxOnly() {
		var near = new BoundingBox(10, 64, 18, 30, 65, 22);
		var layout = new RegionLayout("rg1", "sha", 0, List.of(walled("a", "route", STREET, List.of(), null, 3, null, null), walled("b", "route", near, List.of(), null,
			3, null, null), walled("c", "route", STREET, List.of(), null, 1, null, null)));
		var out = MapShapes.shapes(layout, region(RegionState.PLACED, List.of(), null), Secrets.NONE, false, true);
		assertEquals(List.of("a", "b"), out.stream().map(m -> m.part()).toList(), "a different box (its height too) is another route");
		assertEquals(3, out.get(0).width(), "a narrower copy leaves the first as it was");
	}

	@Test
	void withoutLayoutWallsTheMapIsAsBefore() {
		var out = MapShapes.shapes(walledLayout(), region(RegionState.PLACED, List.of(), null), Secrets.NONE, false, false);
		assertEquals(List.of("town_wall", "gatehouse_passage", "gatehouse", "east_street", "east_street_road_1", "north_stairs"), out.stream().map(m -> m.part())
			.toList(), "no towers, no dedupe");
		assertEquals(List.of("connector", "connector", "lot", "route", "route", "route"), out.stream().map(m -> m.kind()).toList());
		assertTrue(out.stream().allMatch(m -> m.width() == 0 && m.wall() == null && m.wallAt() == null), out.toString());
		assertEquals(MapShapes.shapes(walledLayout(), region(RegionState.PLACED, List.of(), null), Secrets.NONE, false), out, "the 1.69.0 call: no walls");
	}

	@Test
	void theMapsKindsWithWalls() {
		assertEquals("wall", MapShapes.kindOf("connector.wall", true));
		assertEquals("gate", MapShapes.kindOf("connector.gate", true));
		assertEquals("tower", MapShapes.kindOf("tower", true));
		assertEquals("connector", MapShapes.kindOf("connector.rope_bridge", true));
		assertEquals("connector", MapShapes.kindOf("connector.wall", false));
		assertNull(MapShapes.kindOf("tower", false));
	}

	@Test
	void aHiddenGateOrTowerIsNeverSent() {
		var hides = Secrets.of(JsonParser.parseString("{\"forms\":[{\"id\":\"vault\",\"kind\":\"hidden_room\",\"host\":\"gatehouse\"}]}").getAsJsonObject(), null);
		var layout = new RegionLayout("rg1", "sha", 0, List.of(walled("f_vault", "connector.gate", BOX, List.of(), "gatehouse", 2, "town_wall", "gate"),
			walled("town_wall", "connector.wall", WALL_BOX, List.of(), null, 2, null, null)));
		var out = MapShapes.shapes(layout, region(RegionState.PLACED, List.of(), null), hides, false, true);
		assertEquals(List.of("town_wall"), out.stream().map(m -> m.part()).toList());
	}

	@Test
	void theSummaryNamesWidthsAndWalls() {
		String line = MapShapes.summary(walledLayout());
		assertTrue(line.contains("; connector.wall town_wall [0,0..40,40] ends=5 w=2"), line);
		assertTrue(line.contains("; tower town_wall_tower_1 [-2,-2..2,2] ends=0 wall=town_wall@corner"), line);
	}
}
