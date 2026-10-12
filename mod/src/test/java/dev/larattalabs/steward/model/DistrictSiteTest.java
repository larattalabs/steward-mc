package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.DistrictSite.Ground;
import dev.larattalabs.steward.model.DistrictSite.Pref;
import dev.larattalabs.steward.model.DistrictSite.Profile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DistrictSiteTest {
	private static Profile ground(double water, double trees, double height, double spread, Map<String, Double> biomes) {
		return new Profile(1, water, trees, height, spread, biomes);
	}

	@Test
	void wordsSayWhatGroundADistrictWants() {
		DistrictSite harbour = DistrictSite.fromWords("the harbour district");
		assertEquals(Pref.WANT, harbour.water());
		assertNull(harbour.way());
		assertEquals(Ground.HIGH, DistrictSite.fromWords("a mining quarter up in the hills").ground());
		assertEquals(Pref.WANT, DistrictSite.fromWords("a lumber camp").trees());
		DistrictSite farms = DistrictSite.fromWords("farms and orchards");
		assertEquals(Ground.FLAT, farms.ground());
		assertEquals(List.of("jungle"), DistrictSite.fromWords("spice growers on the jungle side").biomes());
		assertEquals(List.of("badlands"), DistrictSite.fromWords("a mesa outpost").biomes());
		assertEquals("E", DistrictSite.fromWords("a market to the east").way());
		assertEquals("W", DistrictSite.fromWords("the western quarter").way());
		assertFalse(DistrictSite.fromWords("more room").caresAboutGround());
		assertFalse(DistrictSite.fromWords(null).caresAboutGround());
	}

	@Test
	void aHarbourGoesToTheShore() {
		var c = DistrictSite.fromWords("the harbour").choose(List.of("N", "E", "S", "W"), Map.of(
			"N", ground(0, 0.2, 70, 2, Map.of()), "E", ground(0.4, 0, 64, 1, Map.of()), "S", ground(0.05, 0, 66, 2, Map.of()), "W", ground(0, 0.5, 72, 3, Map.of())), 66, null);
		assertEquals("E", c.way());
		assertTrue(c.why().startsWith("by the water (40%"), c.why());
	}

	@Test
	void aMineGoesUpAndTheJungleSideIsTheJungle() {
		var profiles = Map.of("N", ground(0, 0.1, 82, 6, Map.of("plains", 1.0)), "E", ground(0, 0.6, 66, 2, Map.of("jungle", 0.8, "sparse_jungle", 0.1)),
			"S", ground(0, 0, 64, 1, Map.of("desert", 1.0)));
		var mine = DistrictSite.fromWords("a mine in the hills").choose(List.of("N", "E", "S"), profiles, 66, null);
		assertEquals("N", mine.way());
		assertTrue(mine.why().contains("high ground (16 above"), mine.why());
		var jungle = DistrictSite.fromWords("spice growers on the jungle side").choose(List.of("N", "E", "S"), profiles, 66, null);
		assertEquals("E", jungle.way());
		assertTrue(jungle.why().contains("jungle (90%"), jungle.why());
		assertEquals("S", DistrictSite.fromWords("glassmakers in the desert").choose(List.of("N", "E", "S"), profiles, 66, null).way());
	}

	@Test
	void theSaidWayThenWhereYouStandThenTheGroundThenTodaysOrder() {
		var profiles = Map.of("N", ground(0, 0, 64, 1, Map.of()), "E", ground(0.5, 0, 64, 1, Map.of()));
		// said: the way wins, if it is free
		assertEquals("N", DistrictSite.fromWords("a harbour to the north").choose(List.of("N", "E"), profiles, 64, null).way());
		// a way that is taken: the ground decides
		assertEquals("E", DistrictSite.fromWords("a harbour to the south").choose(List.of("N", "E"), profiles, 64, null).way());
		// standing: the side nearest the player
		var stand = new DistrictSite.Standing(Map.of("N", 40.0, "E", 0.0));
		var c = DistrictSite.fromWords("the harbour").choose(List.of("N", "E"), profiles, 64, new DistrictSite.Standing(Map.of("N", 0.0, "E", 50.0)));
		assertEquals("N", c.way());
		assertEquals("where you stand", c.why());
		assertEquals("E", DistrictSite.ANYWHERE.choose(List.of("N", "E"), profiles, 64, stand).way());
		// no wish: today's order, no reason given
		var any = DistrictSite.ANYWHERE.choose(List.of("E", "S"), profiles, 64, null);
		assertEquals("E", any.way());
		assertEquals("", any.why());
		assertNull(DistrictSite.ANYWHERE.choose(List.of(), profiles, 64, null).way());
	}

	@Test
	void groundThatIsNotThereOrNotReadDoesNotDecide() {
		// a harbour with no water anywhere: today's order, not a made-up reason
		var dry = Map.of("N", ground(0, 0, 64, 1, Map.of()), "E", ground(0.01, 0, 64, 1, Map.of()));
		var c = DistrictSite.fromWords("the harbour").choose(List.of("N", "E"), dry, 64, null);
		assertEquals("N", c.way());
		assertEquals("", c.why());
		// a square mostly unread scores nothing
		assertEquals(0, DistrictSite.fromWords("the harbour").score(new Profile(0.1, 0.9, 0, 64, 1, Map.of()), 64).value());
	}

	@Test
	void columnsMakeAProfile() {
		var b = new DistrictSite.ProfileBuilder();
		b.add(true, true, false, 0, "minecraft:river");
		b.add(true, false, true, 66, "minecraft:jungle");
		b.add(true, false, false, 70, "minecraft:jungle");
		b.add(false, false, false, 0, null);
		Profile p = b.build();
		assertEquals(0.75, p.known(), 1e-9);
		assertEquals(1.0 / 3, p.water(), 1e-9);
		assertEquals(0.5, p.trees(), 1e-9);
		assertEquals(68, p.height(), 1e-9);
		assertEquals(2, p.spread(), 1e-9);
		assertEquals(2.0 / 3, p.biomes().get("jungle"), 1e-9);
	}
}
