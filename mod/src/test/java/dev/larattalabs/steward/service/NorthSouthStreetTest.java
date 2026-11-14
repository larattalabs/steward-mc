package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.Claim;
import dev.larattalabs.steward.model.Difficulty;
import dev.larattalabs.steward.model.DistrictRules;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.model.Settlement;
import dev.larattalabs.steward.model.Settlement.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * (slice NS) The street reader and Growth B's inventory over a north-south street: the same rules as east-west, by construction. Every east-west case
 * here is read again with x and z swapped, and must give the same street, roads and lots, on the other axis.
 */
class NorthSouthStreetTest {
	private static final Claim TOWN = new Claim("minecraft:overworld", 0, 0, 64, -64, 320);

	private static Settlement town(String districtDir, Claim districtClaim) {
		Settlement s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);
		s = s.withLands(s.lands().next().withDistrict(new Settlement.District("dst_1", "Side", districtDir, districtClaim, 1L, List.of())));
		return s.withLog(new Settlement.LogEntry(4L, Kind.BUILD_ADOPTED, "Took on noah's house", List.of("s9"), null));
	}

	private static com.google.gson.JsonObject ext(String... kv) {
		var o = new com.google.gson.JsonObject();
		for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
		return o;
	}

	private static Adoption.Raw lot(String id, String settlement, String lot, int x, int z) {
		return new Adoption.Raw(id, "building", "lot", "minecraft:overworld", null, x - 4, x + 4, z - 4, z + 4, ext("steward_mc:settlement", settlement,
			"steward_mc:lot", lot), 1);
	}

	/** An east-west street piece as BatchPlanner records it ({@code from}/{@code to} in the order it was laid). */
	private static Adoption.Raw ew(String id, String key, int from, int to, int z) {
		var e = ext("steward_mc:settlement", "set_1");
		e.addProperty("steward_mc:street_z", z);
		e.addProperty("steward_mc:street_x0", from);
		e.addProperty("steward_mc:street_x1", to);
		return new Adoption.Raw(id, "road", key, "minecraft:overworld", null, Math.min(from, to), Math.max(from, to), z - 1, z + 1, e, 1);
	}

	/** The site with x and z swapped: an east-west street piece becomes a north-south one, recorded under the north-south keys. */
	private static Adoption.Raw swap(Adoption.Raw v) {
		com.google.gson.JsonObject e = v.ext() == null ? null : v.ext().deepCopy();
		if (e != null && e.has("steward_mc:street_z")) {
			e.add("steward_mc:street_x", e.remove("steward_mc:street_z"));
			e.add("steward_mc:street_z0", e.remove("steward_mc:street_x0"));
			e.add("steward_mc:street_z1", e.remove("steward_mc:street_x1"));
		}
		return new Adoption.Raw(v.id(), v.kind(), v.itemKey(), v.dimension(), v.region(), v.minZ(), v.maxZ(), v.minX(), v.maxX(), e, v.version());
	}

	/** The east-west town of AdoptionTest's inventory case: its street pieces, a district's, another line's, and lots of every kind. */
	private static List<Adoption.Raw> eastWestSites() {
		List<Adoption.Raw> sites = new ArrayList<>(List.of(
			ew("r1", "street", -30, 20, 0),
			// laid the other way (west from 30)
			ew("r2", "street#2", 30, 21, 0),
			ew("r3", "street", 45, 60, 0),
			ew("r4", "street", -30, 20, 30),
			lot("s1", "set_1", "house_1", -20, 8),
			lot("s2", "set_1", "house_1", 0, -8),
			lot("s3", "set_1", "library_1", 25, 8),
			lot("s4", "set_1", "house_1", 52, 8),
			lot("s5", "set_2", "house_1", 10, 8),
			lot("s9", "set_1", "house_1", 15, -8),
			lot("s6", "set_1", "far_1", 200, 0)));
		sites.add(new Adoption.Raw("s7", "building", "lot", "minecraft:overworld", "rg1", -5, 3, 10, 18, ext("steward_mc:settlement", "set_1", "steward_mc:lot",
			"tower_1"), 1));
		return sites;
	}

	@Test
	void aNorthSouthTownIsTakenOnAsAnEastWestOneIs() {
		var ewTown = town("E", new Claim("minecraft:overworld", 50, 0, 12, -64, 320));
		var nsTown = town("S", new Claim("minecraft:overworld", 0, 50, 12, -64, 320));
		var a = Adoption.readOf(eastWestSites(), ewTown).inventory().orElseThrow();
		var b = Adoption.readOf(eastWestSites().stream().map(NorthSouthStreetTest::swap).toList(), nsTown).inventory().orElseThrow();
		assertFalse(a.northSouth());
		assertTrue(b.northSouth());
		assertEquals(List.of(0, -30, 30), List.of(a.streetZ(), a.xLo(), a.xHi()));
		assertEquals(List.of(a.streetZ(), a.xLo(), a.xHi()), List.of(b.streetZ(), b.xLo(), b.xHi()), "the same line and ends, on the other axis");
		assertEquals(List.of("r1", "r2"), a.roads(), "the town's pieces, either way they were laid; not the district's (r3) nor another line's (r4)");
		assertEquals(a.roads(), b.roads());
		assertEquals(Map.of("house_1", "s1", "house_1_2", "s2", "library_1", "s3"), a.lots());
		assertEquals(a.lots(), b.lots(), "the same lots: not the district's, another settlement's, a player's build, one outside the claim, a region's");
		assertEquals(a.versions(), b.versions());
	}

	@Test
	void theGeometryNamesTheStreetsAxis() {
		var ew = new Adoption.Inventory(10, -20, 30, List.of("r1"), Map.of("house_1", "s1"));
		assertEquals("{\"streetZ\":10,\"xLo\":-20,\"xHi\":30}", ew.geometry().toString());
		var ns = new Adoption.Inventory(10, -20, 30, List.of("r1"), Map.of("house_1", "s1"), Map.of(), true);
		assertEquals("{\"streetX\":10,\"zLo\":-20,\"zHi\":30}", ns.geometry().toString(), "Architect AD's north-south form");
	}

	@Test
	void aBentStreetIsNotTakenOnAndSaysWhy() {
		var s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);
		// an L: east-west from -30 to 0 on z 0, then north-south from 0 to 30 on x 0
		var leg = swap(ew("r2", "street#2", 0, 30, 0));
		var read = Adoption.readOf(List.of(ew("r1", "street", -30, 0, 0), leg, lot("s1", "set_1", "house_1", -20, 8)), s);
		assertTrue(read.inventory().isEmpty(), "a bent street keeps Steward's street pieces");
		assertNotNull(read.why());
		assertTrue(read.why().contains("turns"), read.why());
		// no street at all: nothing to say
		var none = Adoption.readOf(List.of(lot("s1", "set_1", "house_1", 0, 8)), s);
		assertTrue(none.inventory().isEmpty());
		assertNull(none.why());
	}

	// ------------------------------------------------------------------ the reader

	private static final Settlement S = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);

	private static Districts.Piece box(String key, int minX, int maxX, int minZ, int maxZ) {
		return new Districts.Piece("road", key, false, minX, maxX, minZ, maxZ, null, null, null);
	}

	@Test
	void theReaderTakesAStreetOnEitherAxis() {
		assertEquals(new DistrictRules.Street(1, -28, 28), Districts.readStreet(List.of(new Districts.Piece("road", "street", false, -30, 30, -3, 3, 1, -28, 28)), S,
			null).street());
		var ns = Districts.piece("road", "street", ext(), -3, 3, -30, 30);
		assertEquals(new DistrictRules.Street(0, -30, 30, true), Districts.readStreet(List.of(ns), S, null).street(), "an unrecorded tall box: north-south");
		var rec = new Districts.Piece("road", "street", false, -3, 3, -30, 30, null, null, null, 1, 28, -28);
		assertEquals(new DistrictRules.Street(1, -28, 28, true), Districts.readStreet(List.of(rec), S, null).street(),
			"its recorded line and ends, laid south to north");
		// two pieces of one north-south street, and one on another line (a second whole build): the first line is the street
		var a = new Districts.Piece("road", "street", false, -3, 3, -30, 0, null, null, null, 0, -30, 0);
		var b = new Districts.Piece("road", "street#2", false, -3, 3, 1, 20, null, null, null, 0, 20, 1);
		var c = new Districts.Piece("road", "street", false, 37, 43, -30, 30, null, null, null, 40, -30, 30);
		assertEquals(new DistrictRules.Street(0, -30, 20, true), Districts.readStreet(List.of(a, b, c), S, null).street());
		assertNull(Districts.readStreet(List.of(box("road:lane_3", -1, 1, 0, 30)), S, null).street(), "a lane is not a street");
	}

	@Test
	void eastWestReadersStillPassANorthSouthStreetBy() {
		// the callers that know an east-west line (additions, stockpiles, links) read a north-south town as having none, as before
		var rec = new Districts.Piece("road", "street", false, -3, 3, -30, 30, null, null, null, 0, -30, 30);
		assertNull(Districts.streetOfPieces(List.of(rec), S, null));
		// even a short one whose box is wider than it is long (its lanterns): the recorded axis decides
		var stub = new Districts.Piece("road", "street", false, -4, 4, 0, 2, null, null, null, 0, 0, 2);
		assertNull(Districts.streetOfPieces(List.of(stub), S, null));
		assertTrue(Districts.readStreet(List.of(stub), S, null).street().northSouth());
	}

	@Test
	void aDistrictsStreetIsReadOnEitherAxis() {
		var d = new Settlement.District("dst_1", "South", "S", new Claim("minecraft:overworld", 0, 50, 12, -64, 320), 1L, List.of());
		var s = S.withLands(S.lands().next().withDistrict(d));
		var inD = new Districts.Piece("road", "street", false, -3, 3, 40, 60, null, null, null, 0, 40, 60);
		var town = new Districts.Piece("road", "street", false, -3, 3, -30, 30, null, null, null, 0, -30, 30);
		assertEquals(new DistrictRules.Street(0, 40, 60, true), Districts.readStreet(List.of(inD, town), s, d).street());
		assertEquals(new DistrictRules.Street(0, -30, 30, true), Districts.readStreet(List.of(inD, town), s, null).street());
	}

	@Test
	void anOldUnrecordedTallPieceBesideAnEastWestStreetIsPassedByNotBent() {
		// a street laid before lines were recorded: a short extension whose box (with its lanterns) is taller than wide stays out, as it always did
		var main = new Districts.Piece("road", "street", false, -30, 20, -3, 3, 0, -30, 20);
		var stub = box("street#2", 21, 23, -4, 4);
		var read = Districts.readStreet(List.of(main, stub), S, null);
		assertNull(read.why());
		assertEquals(new DistrictRules.Street(0, -30, 20), read.street());
		assertEquals(Districts.streetOfPieces(List.of(main, stub), S, null), read.street(), "as the east-west reader has it");
		// the same town's adoption: taken on, on its east-west street
		var town = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);
		var r = Adoption.readOf(List.of(ew("r1", "street", -30, 20, 0), new Adoption.Raw("r2", "road", "street#2", "minecraft:overworld", null, 21, 23, -4, 4, ext(), 1),
			lot("s1", "set_1", "house_1", -20, 8)), town);
		assertEquals(List.of("r1"), r.inventory().orElseThrow().roads());
		assertFalse(r.inventory().get().northSouth());
	}
}
