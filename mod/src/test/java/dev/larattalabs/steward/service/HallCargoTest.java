package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The steward's words for a storage hall's refused undo read only the halls whose containers Architect's cargo refusal names (M4, the diff review's findings 1 and 4). */
class HallCargoTest {
	@Test
	void architectsRealRefusalNamesEveryChest() throws Exception {
		// Architect 0.67.0's refusal of a hall growth's undo, as the measurement saw it (artifacts/m4/hall.json)
		String m = new String(HallCargoTest.class.getResourceAsStream("/hall-refusal-0.67.0.txt").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(HallCargo.cargoRefusal(m));
		var cells = HallCargo.namedCells(m);
		assertTrue(cells.size() >= 3, cells.toString());
		assertEquals(new net.minecraft.core.BlockPos(125, 66, 93), cells.get(0));
	}

	@Test
	void theSitesToEmptyComeFromArchitectsRefusal() {
		String m = "NOT_ALLOWED: undo of region rg3 to position 1 refused, nothing written: Move these out of s17 first (removing it puts the old terrain back"
			+ " over them): chest at 105, 66, 578 (595 items). Or confirm again with force: they are lost (blockers); Move these out of s12 first (...): chest at 1, 2, 3 (4 items)";
		assertEquals(List.of(new net.minecraft.core.BlockPos(105, 66, 578), new net.minecraft.core.BlockPos(1, 2, 3)), HallCargo.namedCells(m));
		assertTrue(HallCargo.cargoRefusal(m));
		assertFalse(HallCargo.cargoRefusal("OCCUPIED: undo of region rg1 to position 0 refused, nothing written: noahlaratta is in site c12 of revision 1"),
			"a player standing in a site is not cargo");
		assertFalse(HallCargo.cargoRefusal(null));
		// negative coordinates; an entity whose custom name looks like a container entry, overflowing: skipped, never thrown
		String odd = "NOT_ALLOWED: ...: Move these out of s3 first (...): chest at -120, -40, -3000 (2 items), a wolf named \"chest at 999999999999, 1, 2\" at 5, 6, 7";
		assertEquals(List.of(new net.minecraft.core.BlockPos(-120, -40, -3000)), HallCargo.namedCells(odd));
		assertTrue(HallCargo.namedCells("Move these out of s3 first: a cow at 1, 2, 3").isEmpty(), "an entity is not a container");
	}

	@Test
	void theWordsAreUnchangedForTheHallsOwnChests() {
		assertEquals("the storage hall still holds 74 items in 1 chest; empty it first", HallCargo.say(74, 1, true));
		assertEquals("the storage hall still holds at least 1,240 items in 9 chests; empty it first", HallCargo.say(1240, 9, 0, 0, false));
	}

	@Test
	void aPlayersBarrelIsSaidApartNeverAsTheHallsChests() {
		// (M4-L) the hall's chests and a barrel a player added in its lot; the barrel alone still refuses (Architect's removal waits on it)
		assertEquals("the storage hall still holds 74 items in 1 chest, and 12 items in 1 other container that is not the hall's; empty them first",
			HallCargo.say(74, 1, 12, 1, true));
		assertEquals("the storage hall's lot still holds 12 items in 1 other container that is not the hall's; empty it first", HallCargo.say(0, 0, 12, 1, true));
		assertEquals("the storage hall's lot still holds 30 items in 2 other containers that are not the hall's; empty them first", HallCargo.say(0, 0, 30, 2, true));
	}

	private static com.google.gson.JsonObject params(int slots, int bulk) {
		var o = new com.google.gson.JsonObject();
		o.addProperty("slots", slots);
		o.addProperty("bulk", bulk);
		return o;
	}

	@Test
	void theTurnIsVanillaTemplateRotation() {
		// StructureTemplate.transform(pos, Mirror.NONE, rotation, BlockPos.ZERO), checked for each rotation
		for (var r : net.minecraft.world.level.block.Rotation.values()) {
			for (int[] c : new int[][] {{0, 0}, {3, 1}, {10, 6}, {6, 4}}) {
				var v = net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.transform(new net.minecraft.core.BlockPos(c[0], 5, c[1]),
					net.minecraft.world.level.block.Mirror.NONE, r, net.minecraft.core.BlockPos.ZERO);
				assertArrayEquals(new int[] {v.getX(), v.getZ()}, HallCargo.turn(c[0], c[1], r), r + " " + java.util.Arrays.toString(c));
			}
		}
	}

	@Test
	void theDeclaredCellsAreTheLayoutsTurnedIntoTheBox() {
		var R = net.minecraft.world.level.block.Rotation.class;
		// unturned: the template's cells moved to the box's corner (11 x 7 x 7)
		var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(100, 60, 200, 110, 66, 206);
		var none = HallCargo.declared(params(0, 2), box, net.minecraft.world.level.block.Rotation.NONE);
		var l = dev.larattalabs.steward.module.StorageHallLot.layout(0, 2);
		assertEquals(5, none.size());
		int[] in = l.input();
		assertEquals(new net.minecraft.core.BlockPos(100 + in[0], 60 + in[1], 200 + in[2]), none.iterator().next(), "the input chest first");
		for (int[] c : l.containers()) assertTrue(none.contains(new net.minecraft.core.BlockPos(100 + c[0], 60 + c[1], 200 + c[2])));
		// turned a half: (x, z) -> (W-1-x, D-1-z)
		var half = HallCargo.declared(params(0, 2), box, net.minecraft.world.level.block.Rotation.CLOCKWISE_180);
		for (int[] c : l.containers()) assertTrue(half.contains(new net.minecraft.core.BlockPos(100 + 10 - c[0], 60 + c[1], 200 + 6 - c[2])));
		// turned a quarter: the box is 7 across and 11 deep; clockwise (x, z) -> (D-1-z, x), counterclockwise (x, z) -> (z, W-1-x)
		var deep = new net.minecraft.world.level.levelgen.structure.BoundingBox(100, 60, 200, 106, 66, 210);
		var cw = HallCargo.declared(params(0, 2), deep, net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
		var ccw = HallCargo.declared(params(0, 2), deep, net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90);
		for (int[] c : l.containers()) {
			assertTrue(cw.contains(new net.minecraft.core.BlockPos(100 + 6 - c[2], 60 + c[1], 200 + c[0])), "cw " + java.util.Arrays.toString(c));
			assertTrue(ccw.contains(new net.minecraft.core.BlockPos(100 + c[2], 60 + c[1], 200 + 10 - c[0])), "ccw " + java.util.Arrays.toString(c));
		}
		// every cell inside its box
		for (var s : java.util.List.of(none, half)) for (var p : s) assertTrue(box.isInside(p));
		for (var s : java.util.List.of(cw, ccw)) for (var p : s) assertTrue(deep.isInside(p));
	}

	@Test
	void unknownParamsOrABoxNotTheTemplatesFallBackToEveryContainer() {
		var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(0, 0, 0, 10, 6, 6);
		assertNull(HallCargo.declared(null, box, null));
		assertNull(HallCargo.declared(new com.google.gson.JsonObject(), box, null));
		assertNull(HallCargo.declared(params(4, 2), box, null), "a sorting branch this version doesn't build: never thrown");
		assertNull(HallCargo.declared(params(0, 9), box, null));
		assertNull(HallCargo.declared(params(0, 2), box, net.minecraft.world.level.block.Rotation.CLOCKWISE_90), "a quarter turn needs a 7 x 11 box");
		assertNotNull(HallCargo.declared(params(0, 0), box, null), "no bulk: the input chest alone");
		assertEquals(1, HallCargo.declared(params(0, 0), box, null).size());
	}
}
