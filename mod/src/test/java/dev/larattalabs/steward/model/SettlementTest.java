package dev.larattalabs.steward.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettlementTest {
	private static ConceptCard card() throws Exception {
		var in = SettlementTest.class.getClassLoader().getResourceAsStream("crater_works.json");
		return ConceptCard.parse(JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
	}

	private static Claim claim(int cx, int cz) {
		return new Claim("minecraft:overworld", cx, cz, 128, -64, 320);
	}

	@Test
	void claimContainsAndOverlaps() {
		Claim c = claim(0, 0);
		assertTrue(c.contains("minecraft:overworld", 128, 0, -128));
		assertFalse(c.contains("minecraft:overworld", 129, 0, 0));
		assertFalse(c.contains("minecraft:the_nether", 0, 0, 0));
		assertTrue(c.containsBox("minecraft:overworld", -10, 0, -10, 10, 50, 10));
		assertFalse(c.containsBox("minecraft:overworld", -10, 0, -10, 200, 50, 10));
		assertTrue(c.overlaps(claim(200, 0)));
		assertFalse(c.overlaps(claim(300, 0)));
		assertThrows(IllegalArgumentException.class, () -> new Claim("d", 0, 0, 4, 0, 10));
	}

	@Test
	void foundingNamesAndLogs() throws Exception {
		Settlement s = Settlement.found("set_1", card(), claim(0, 0), Permission.PROPOSALS, Difficulty.PATRON, 1000L);
		assertEquals("Ashfall Works", s.name());
		assertEquals("steward_mc:settlement/set_1", s.owner());
		assertEquals(1, s.log().size());
		assertEquals(Settlement.Kind.FOUNDED, s.log().get(0).kind());
	}

	@Test
	void reskinBumpsOnlyTheStyleVersion() throws Exception {
		Settlement s = Settlement.found("set_1", card(), claim(0, 0), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		Settlement r = s.reskin(new ConceptCard.Field("frozen ice fortress", null, null, null), 2L);
		assertEquals(2, r.styleVersion());
		assertEquals(1, r.siteVersion());
		assertEquals(1, r.purposeVersion());
		assertEquals("frozen ice fortress", r.card().style().text());
		assertEquals(s.card().site(), r.card().site());
		assertEquals(2, r.log().size());
		assertEquals(1, s.log().size(), "the original is unchanged");
	}

	@Test
	void undoListsComeNewestFirst() throws Exception {
		Settlement s = Settlement.found("set_1", card(), claim(0, 0), Permission.FULL, Difficulty.PATRON, 1L)
			.withLog(new Settlement.LogEntry(2L, Settlement.Kind.PROJECT_PLACED, "a", List.of("s1", "s2")))
			.withLog(new Settlement.LogEntry(3L, Settlement.Kind.PROJECT_PLACED, "b", List.of("s3")));
		assertEquals(List.of("s3", "s1", "s2"), s.siteIdsOf(Settlement.Kind.PROJECT_PLACED));
	}

	@Test
	void storeRoundTripsAndRefusesOverlap(@TempDir Path dir) throws Exception {
		SettlementStore st = new SettlementStore();
		Settlement a = Settlement.found(st.nextId(), card(), claim(0, 0), Permission.PROPOSALS, Difficulty.SUPPLIED, 5L);
		st.put(a);
		assertThrows(IllegalArgumentException.class, () -> st.put(Settlement.found("set_9", card(), claim(100, 0), Permission.FULL, Difficulty.PATRON, 6L)));
		st.put(Settlement.found("set_9", card(), claim(1000, 0), Permission.FULL, Difficulty.PATRON, 6L));

		Path file = dir.resolve("sub/steward-settlements.json");
		st.save(file);
		SettlementStore back = SettlementStore.load(file);
		assertEquals(2, back.all().size());
		Settlement b = back.get("set_1").orElseThrow();
		assertEquals(a, b);
		assertEquals(Difficulty.SUPPLIED, b.difficulty());
		assertTrue(back.at("minecraft:overworld", 10, 70, 10).isPresent());
		assertTrue(back.at("minecraft:overworld", 5000, 70, 5000).isEmpty());
		assertEquals("set_3", back.nextId());
	}

	@Test
	void missingFileIsEmptyButCorruptFileIsAnError(@TempDir Path dir) throws Exception {
		assertTrue(SettlementStore.load(dir.resolve("none.json")).all().isEmpty());
		Path bad = dir.resolve("bad.json");
		Files.writeString(bad, "{ not json");
		assertThrows(JsonParseException.class, () -> SettlementStore.load(bad));
		Files.writeString(bad, "{\"format\":99,\"settlements\":[]}");
		assertThrows(JsonParseException.class, () -> SettlementStore.load(bad));
	}

	@Test
	void aClaimedSettlementHasNoCardUntilDescribedAndSurvivesTheStore(@TempDir Path dir) throws Exception {
		Settlement s = Settlement.founded("set_1", "Settlement", claim(0, 0), Permission.PROPOSALS, Difficulty.PATRON, 1L);
		assertFalse(s.described());
		assertThrows(IllegalStateException.class, () -> s.reskin(new ConceptCard.Field("x", null, null, null), 2L));
		SettlementStore st = new SettlementStore();
		st.put(s);
		Path f = dir.resolve("s.json");
		st.save(f);
		Settlement back = SettlementStore.load(f).get("set_1").orElseThrow();
		assertFalse(back.described());
		Settlement d = back.withCard(card(), 3L);
		assertTrue(d.described());
		assertEquals("Ashfall Works", d.name());
		assertEquals(2, d.log().size());
		st.put(d);
		st.save(f);
		assertEquals("giant meteor crater", SettlementStore.load(f).get("set_1").orElseThrow().card().site().text());
	}
}
