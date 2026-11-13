package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** crop_farm@1's lot spec and geometry (docs/MODULES.md 3.1 and 7: W = F + 2, D = F + 3). */
class CropFarmLotTest {
	@Test
	void sizesAreFieldPlusBorderAndFrontRow() {
		for (int w : CropFarmLot.SIDES) {
			for (int d : CropFarmLot.SIDES) {
				var l = CropFarmLot.layout(w, d);
				assertEquals(w + 2, l.w());
				assertEquals(d + 3, l.d());
				int sources = (w / 9) * (d / 9);
				assertEquals(sources, l.sources().size(), "one IRR source per 9x9");
				assertEquals(w * d - 5 * sources - 2, l.field().size(), "the field less each source and its 4 sides, and the port's 2 cells");
				JsonObject lot = CropFarmLot.growLot("farm_1", w, d);
				assertEquals(w + 2, lot.getAsJsonArray("size").get(0).getAsInt());
				assertEquals(d + 3, lot.getAsJsonArray("size").get(1).getAsInt());
				assertTrue(lot.get("irrigate").getAsBoolean());
				assertEquals(l.field().size(), lot.getAsJsonObject("field").getAsJsonArray("cells").size());
				assertEquals(4, lot.getAsJsonObject("field").getAsJsonArray("crops").size());
			}
		}
	}

	@Test
	void sourcesSitAtSegmentCentresLikeIrr() {
		assertEquals(List.of(5), CropFarmLot.axisSources(1, 9));
		assertEquals(List.of(5, 14), CropFarmLot.axisSources(1, 18));
		assertEquals(List.of(5, 14, 23), CropFarmLot.axisSources(1, 27));
		var l = CropFarmLot.layout(18, 9);
		assertArrayEquals(new int[] {5, 5}, l.sources().get(0));
		assertArrayEquals(new int[] {14, 5}, l.sources().get(1));
	}

	@Test
	void fieldCellsAvoidIrrigationCellsAndStayInTheField() {
		var l = CropFarmLot.layout(27, 18);
		Set<String> irr = new HashSet<>();
		for (int[] s : l.sources()) {
			irr.add(s[0] + "," + s[1]);
			for (int[] o : new int[][] {{0, -1}, {-1, 0}, {1, 0}, {0, 1}}) irr.add((s[0] + o[0]) + "," + (s[1] + o[1]));
		}
		for (int[] c : l.field()) {
			assertFalse(irr.contains(c[0] + "," + c[1]));
			assertTrue(c[0] >= 1 && c[0] <= l.w() - 2 && c[1] >= 1 && c[1] <= l.d() - 3);
		}
		// every field cell within IRR's reach (4, Chebyshev) of a source
		for (int[] c : l.field()) assertTrue(l.sources().stream().anyMatch(s -> Math.max(Math.abs(s[0] - c[0]), Math.abs(s[1] - c[1])) <= 4));
	}

	@Test
	void refusesSidesOutsideTheEnvelope() {
		assertThrows(IllegalArgumentException.class, () -> CropFarmLot.layout(10, 9));
		assertThrows(IllegalArgumentException.class, () -> CropFarmLot.growLot("x", 9, 36));
	}
}
