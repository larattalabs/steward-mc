package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.BesideNote;
import dev.larattalabs.architect.api.BuildStatus;
import dev.larattalabs.architect.api.ItemEvent;
import dev.larattalabs.architect.api.RegionLand;
import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.architect.api.RemovalStop;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.architect.api.State;
import dev.larattalabs.steward.model.Land;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

/** (slice 8bS) Architect 8b's typed homes win over the ext keys; the ext is read only when the typed field is empty. */
class TypedTest {
	private static final String OW = "minecraft:overworld";

	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static SiteView site(JsonObject ext, String part, String lot, int rid) {
		var box = new BoundingBox(0, 64, 0, 4, 70, 4);
		return new SiteView("s1", "bp", "steward_mc:settlement/set_1", ext, box, box, Rotation.NONE, null, State.BUILT, 0, 0, null, null, null, "building", null,
			List.of(), List.of(), 1, 1, 0, false, "rg1", null, null, part, lot, rid);
	}

	@Test
	void aSitesPartLotAndRevisionAreTyped() {
		var ext = json("{\"architect_mc:part\":\"old_part\",\"architect_mc:lot\":\"old_lot\",\"architect_mc:revision\":{\"rid\":5}}");
		var typed = site(ext, "market", "lot_3", 2);
		assertEquals("market", Typed.part(typed));
		assertEquals("lot_3", Typed.lot(typed));
		assertEquals(2, Typed.rid(typed));
		// written before Architect filled them: the ext keys
		var old = site(ext, null, null, 0);
		assertEquals("old_part", Typed.part(old));
		assertEquals("old_lot", Typed.lot(old));
		assertEquals(5, Typed.rid(old));
		var none = site(new JsonObject(), null, null, 0);
		assertNull(Typed.part(none));
		assertNull(Typed.lot(none));
		assertEquals(0, Typed.rid(none));
	}

	@Test
	void anItemEventsRegionPartRevisionAndStatusAreTyped() {
		var ext = json("{\"architect_mc:region\":\"rg_old\",\"architect_mc:part\":\"p_old\",\"architect_mc:revision\":{\"rid\":4}}");
		var st = new BuildStatus("waiting", "bill_cap", "c1", Map.of(), 0, 7L, "say", 3, "region");
		var typed = new ItemEvent("b1", "k1", ext, Optional.empty(), Optional.empty(), "", "rg1", "gate", "lot_1", 0, st);
		assertEquals("rg1", Typed.region(typed));
		assertEquals("gate", Typed.part(typed));
		assertEquals(3, Typed.rid(typed), "a bill_cap hold's revision is its status's");
		assertSame(st, Typed.status(typed));
		var old = new ItemEvent("b1", "k1", ext, Optional.empty(), Optional.empty(), "");
		assertEquals("rg_old", Typed.region(old));
		assertEquals("p_old", Typed.part(old));
		assertEquals(4, Typed.rid(old));
		assertNull(Typed.status(old));
	}

	private static dev.larattalabs.architect.api.RegionView region(JsonObject ext, BesideNote beside, RegionLand land) {
		return new dev.larattalabs.architect.api.RegionView("rg1", "plan", "sha", "o", ext, "grp", new BoundingBox(0, -64, 0, 30, 320, 30), RegionState.PLACED,
			List.of(), List.of(), 0L, Map.of(), null, null, List.of(), 0L, null, List.of(), null, null, 0, null, List.of(), RemovalStop.NONE, null, null, beside, land);
	}

	@Test
	void aRegionsBesideNoteAndLandAreTyped() {
		var ext = json("{\"architect_mc:beside\":{\"region\":\"rg0\",\"dangling\":false},\"architect_mc:land\":{\"outside\":{\"runs\":[[1,2,3,2]]}}}");
		var typed = region(ext, new BesideNote("rg0", true, "gate_rg"), new RegionLand(1, List.of(new RegionLand.Run(9, 4, 1)), List.of(), 1790000000000L));
		assertTrue(Typed.beside(typed).dangling());
		assertEquals("gate_rg", Typed.beside(typed).gateRemoved());
		assertEquals(new Land(OW, List.of(new Land.Run(9, 1, 4))), DistrictLand.outside(typed, OW).orElseThrow());
		assertTrue(DistrictLand.inside(typed, OW).isEmpty());
		assertEquals(new Land(OW, List.of(new Land.Run(9, 1, 4))), DistrictLand.outsideWhole(typed, OW).land());
		assertEquals(1790000000000L, DistrictLand.standingRemoved(typed));
		// no typed home: the ext, read as before
		var old = region(ext, null, null);
		assertFalse(Typed.beside(old).dangling());
		assertNull(Typed.beside(old).gateRemoved());
		assertEquals(new Land(OW, List.of(new Land.Run(2, 1, 3))), DistrictLand.outside(old, OW).orElseThrow());
		assertNull(DistrictLand.standingRemoved(old));
		var gone = region(json("{\"architect_mc:beside\":{\"dangling\":true,\"gateRemoved\":\"g\"}}"), null, null);
		assertTrue(Typed.beside(gone).dangling());
		assertEquals("g", Typed.beside(gone).gateRemoved());
		assertNull(Typed.beside(region(new JsonObject(), null, null)));
	}
}
