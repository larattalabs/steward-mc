package dev.larattalabs.steward.view;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.labui.ui.Cvd;
import dev.larattalabs.labui.ui.GhostPalette;
import java.util.List;
import org.junit.jupiter.api.Test;

/** (UI-PR-S) The map's state colours in lab-ui's two ghost palettes. */
class MapColorsTest {
	/** The kit's clay, brass and sage as the client passes them (their values don't matter to the rules here). */
	private static final MapColors.Standard STD = new MapColors.Standard(0xFF8FA98B, 0xFFB8862B, 0xFF9A3F27);

	@Test
	void theStandardPaletteKeepsTheKitsColours() {
		assertEquals(STD.done(), MapColors.fill("done", GhostPalette.Palette.STANDARD, STD));
		assertEquals(STD.working(), MapColors.fill("working", GhostPalette.Palette.STANDARD, STD));
		assertEquals(STD.waiting(), MapColors.fill("error", GhostPalette.Palette.STANDARD, STD), "an error is drawn as waiting");
		assertEquals(MapColors.IDLE, MapColors.fill("idle", GhostPalette.Palette.STANDARD, STD));
		assertEquals(MapColors.IDLE, MapColors.fill(null, GhostPalette.Palette.STANDARD, STD));
	}

	@Test
	void okabeItoTakesTheGhostsColoursForTheSameMeanings() {
		assertEquals(0xFF0072B2, MapColors.fill("done", GhostPalette.Palette.OKABE_ITO, STD), "blue, as the OK outline");
		assertEquals(0xFFE69F00, MapColors.fill("working", GhostPalette.Palette.OKABE_ITO, STD), "orange");
		assertEquals(0xFFD55E00, MapColors.fill("waiting", GhostPalette.Palette.OKABE_ITO, STD), "vermillion, as refused");
		assertEquals(MapColors.IDLE, MapColors.fill("idle", GhostPalette.Palette.OKABE_ITO, STD), "planned stays grey");
	}

	@Test
	void everyOkabeItoMarkPairStaysApartForEveryVision() {
		// lab-ui's GhostPaletteTest bar (min ΔE76 16.7 in its groups; 15 here, as the contract's A1 gate), after Machado 2009 at full severity
		List<Integer> marks = MapColors.marks(GhostPalette.Palette.OKABE_ITO, STD);
		double min = Double.MAX_VALUE;
		String at = "";
		for (int i = 0; i < marks.size(); i++) {
			for (int j = i + 1; j < marks.size(); j++) {
				double d = Cvd.minDeltaE(marks.get(i) & 0xFFFFFF, marks.get(j) & 0xFFFFFF);
				if (d < min) {
					min = d;
					at = Integer.toHexString(marks.get(i)) + " vs " + Integer.toHexString(marks.get(j));
				}
			}
		}
		assertTrue(min >= 15, "min ΔE " + min + " at " + at);
	}
}
