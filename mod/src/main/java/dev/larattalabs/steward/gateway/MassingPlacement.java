package dev.larattalabs.steward.gateway;

import dev.larattalabs.steward.layout.VillageLayout.Front;
import dev.larattalabs.steward.layout.VillageLayout.Lot;

/**
 * Where a massing shows on its lot, for the approval ghost: the design faces south as designed, so a lot whose front is north (street to the north) turns
 * it 180 degrees; it is centred across the lot and set back from the street by the approach margin, as {@code fitToLot} would place the detailed building.
 * An approximation for previewing only (the real placement comes from {@code Sites.fitToLot} once the design is done). Pure.
 *
 * @param rotation a Minecraft {@code Rotation} name
 */
public record MassingPlacement(int x, int y, int z, String rotation) {
	public static MassingPlacement on(Lot lot, int sizeX, int sizeZ) {
		int x = lot.x() + Math.max(0, (lot.sizeX() - sizeX) / 2);
		int depth = Math.min(sizeZ, Math.max(1, lot.sizeZ() - LotBrief.APPROACH_MARGIN));
		if (lot.front() == Front.SOUTH) {
			// street to the south: the front (south face) sits the margin north of the lot's south edge
			return new MassingPlacement(x, lot.groundY(), lot.maxZ() - LotBrief.APPROACH_MARGIN - depth + 1, "NONE");
		}
		// street to the north: turned 180 degrees so its front faces north, the margin south of the lot's north edge
		return new MassingPlacement(x, lot.groundY(), lot.z() + LotBrief.APPROACH_MARGIN, "CLOCKWISE_180");
	}
}
