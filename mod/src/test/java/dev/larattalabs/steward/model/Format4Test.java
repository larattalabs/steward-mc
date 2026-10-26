package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParseException;
import dev.larattalabs.steward.model.Settlement.District;
import dev.larattalabs.steward.service.BuildStore;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Settlements format 4 and builds format 2 (docs/DISTRICTS.md section 9): district kinds, states and land; older files migrate, newer ones are refused. */
class Format4Test {
	private static final String OW = "minecraft:overworld";
	private static final Claim TOWN = new Claim(OW, 0, 0, 64, -64, 320);

	private static String fixture(String name) throws Exception {
		try (InputStream in = Format4Test.class.getClassLoader().getResourceAsStream("saves/" + name)) {
			assertNotNull(in, name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void aV3DistrictMigratesToAStandingSquareWhoseLandIsItsClaim(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("steward-settlements.json");
		Files.writeString(f, fixture("v3/steward-settlements.json"));
		SettlementStore st = SettlementStore.load(f);
		assertEquals(3, st.readFormat());
		assertTrue(Files.exists(dir.resolve("steward-settlements.json.v3.bak")), "the v3 file is kept as it was");
		District d = st.get("set_44").orElseThrow().lands().district("dst_2").orElseThrow();
		assertEquals(District.SQUARE, d.kind());
		assertEquals(District.STANDING, d.state());
		assertNull(d.savedLand(), "a square's land is its claim: none written");
		assertEquals(Land.of(d.claim()), d.land());
		assertNull(d.parent());
		assertNull(d.region());
		st.save(f);
		String saved = Files.readString(f);
		assertTrue(saved.contains("\"format\": 4"));
		assertFalse(saved.contains("\"land\""), "squares write no land");
		assertTrue(saved.contains("\"kind\": \"square\""));
		assertEquals(4, SettlementStore.load(f).readFormat());
		assertEquals(d, SettlementStore.load(f).get("set_44").orElseThrow().lands().district("dst_2").orElseThrow());
	}

	@Test
	void anOlderStewardRefusesTheNewFormat() throws Exception {
		// what a v3 Steward does with this file (its own rule: format above its FORMAT is refused, the file untouched)
		String v4 = new SettlementStore().toJson();
		int format = com.google.gson.JsonParser.parseString(v4).getAsJsonObject().get("format").getAsInt();
		assertTrue(format > 3, "a v3 reader (FORMAT 3) refuses format " + format);
		String newer = v4.replaceFirst("\"format\": 4", "\"format\": 5");
		assertThrows(JsonParseException.class, () -> SettlementStore.fromJson(newer));
	}

	private static Settlement withDistricts() {
		Settlement s = Settlement.founded("set_1", "Here", TOWN, Permission.PROPOSALS, Difficulty.PATRON, 1L);
		// a beside district: its reservation east of the town plus the join's cells Architect reported outside the town
		Land reservation = Land.ofRect(OW, 65, -48, 161, 48);
		Land join = new Land(OW, List.of(new Land.Run(0, 62, 64)));
		Claim east = new Claim(OW, 113, 0, 48, -64, 320);
		District beside = new District("dst_1", "Harbour", "E", east, 2L, List.of(), false, District.BESIDE, District.STANDING, "rg3", "rg7",
			reservation.union(new Land(OW, List.of(new Land.Run(0, 65, 66)))), null, "steward.d.dst_1");
		// an along district: a band along a shore, and its join
		Land band = new Land(OW, List.of(new Land.Run(-80, -30, 30), new Land.Run(-79, -32, 28), new Land.Run(-78, -33, 27)));
		District along = new District("dst_2", "Quay", "N", new Claim(OW, 0, -79, 33, -64, 320), 3L, List.of(), false, District.ALONG, District.BUILDING, "rg3",
			null, band, new District.Follow("shore", List.of("water", "sand"), "land"), "steward.d.dst_2");
		return s.withLands(s.lands().next().withDistrict(beside).next().withDistrict(along));
	}

	@Test
	void besideAndAlongDistrictsRoundTrip() {
		Settlement s = withDistricts();
		SettlementStore st = new SettlementStore();
		st.put(s);
		Settlement back = SettlementStore.fromJson(st.toJson()).get("set_1").orElseThrow();
		assertEquals(s.lands(), back.lands());
		District along = back.lands().district("dst_2").orElseThrow();
		assertEquals("shore", along.follow().boundary());
		assertEquals(District.BUILDING, along.state());
		// the land decides, not the bounding square
		assertEquals("dst_2", back.districtAt(OW, 0, -80).orElseThrow().id());
		assertTrue(back.districtAt(OW, 33, -80).isEmpty(), "inside the bounding square, off the band");
		assertTrue(back.contains(OW, 30, 70, -80));
		assertFalse(back.contains(OW, 33, 70, -80));
	}

	@Test
	void anotherSettlementMayStandInADistrictsBoundingSquareButNotOnItsLand() {
		SettlementStore st = new SettlementStore();
		st.put(withDistricts());
		// north of the band (rows -108..-90), inside the along district's bounding square (rows -112..-46), off its land
		Settlement off = Settlement.founded("set_2", "Off", new Claim(OW, 0, -99, 9, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		st.put(off);
		Settlement on = Settlement.founded("set_3", "On", new Claim(OW, 0, -88, 8, -64, 320), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		assertThrows(IllegalArgumentException.class, () -> st.put(on), "its square reaches row -80 of the band");
	}

	@Test
	void aBoxIsADistrictsOnlyWhenItsLandHoldsEveryColumn() {
		Settlement s = withDistricts();
		assertEquals(s.lands().district("dst_2").orElseThrow().claim(), s.claimFor(-10, -80, 10, -78));
		assertEquals(TOWN, s.claimFor(-40, -80, 10, -78), "row -78 ends at x -33: not wholly the band's");
	}

	@Test
	void unknownKindsAndALandlessRegionDistrictAreRefused() {
		Claim c = new Claim(OW, 113, 0, 48, -64, 320);
		assertThrows(JsonParseException.class, () -> new District("d", "D", "E", c, 1L, List.of(), false, "tunnel", District.STANDING, null, null, Land.of(c), null, null));
		assertThrows(JsonParseException.class, () -> new District("d", "D", "E", c, 1L, List.of(), false, District.BESIDE, "flooded", null, null, Land.of(c), null, null));
		assertThrows(JsonParseException.class, () -> new District("d", "D", "E", c, 1L, List.of(), false, District.BESIDE, District.RESERVED, null, null, null, null, null));
		assertThrows(JsonParseException.class, () -> new District("d", "D", "E", c, 1L, List.of(), false, District.BESIDE, District.RESERVED, null, null,
			Land.ofRect("minecraft:the_nether", 0, 0, 1, 1), null, null));
	}

	@Test
	void theBuildsFileMovesToFormat2AndReadsFormat1(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("steward-builds.json");
		Files.writeString(f, fixture("v2/steward-builds.json"));
		BuildStore b = BuildStore.load(f);
		assertEquals(1, b.readFormat());
		assertTrue(Files.exists(dir.resolve("steward-builds.json.v1.bak")));
		b.save(f);
		assertTrue(Files.readString(f).contains("\"format\": 2"));
		BuildStore again = BuildStore.load(f);
		assertEquals(2, again.readFormat());
		assertEquals(b.all().get(0).vRegion(), again.all().get(0).vRegion());
		String newer = Files.readString(f).replaceFirst("\"format\": 2", "\"format\": 3");
		assertThrows(JsonParseException.class, () -> BuildStore.fromJson(newer));
	}
}
