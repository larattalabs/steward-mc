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
}
