package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * The deviation shapes are Architect's own (0.72.0, read from ../architect-mc): plan.json's {@code parts[].deviations} enum, the S3 golden
 * ({@code scenarios/goldens/s3/meta.json}: count, move), the kit's tests ({@code kit/test/gm-b.test.mjs}: mechanism, width and height) and the
 * kit's emitters ({@code kit/lib/plan/compile.mjs}: move, size, rotate, count, sketch, detour; {@code kit/lib/plan/palette.mjs}: material;
 * {@code kit/lib/connectors/wall.mjs}: join; {@code bridge.mjs}: crossing; {@code climb.mjs}: climb; {@code fluids/quay.mjs}: length, reach;
 * {@code plan/free.mjs}: outline, layout).
 */
class ChangeWordsTest {
	private static String of(String json) {
		JsonElement e = JsonParser.parseString(json);
		return ChangeWords.of(e);
	}

	@Test
	void theS3GoldensReadPlainly() {
		assertEquals("2 of its lots left out", of("[{\"what\":\"count\",\"value\":2,\"max\":4}]"));
		assertEquals("moved 15 blocks", of("[{\"what\":\"move\",\"value\":15,\"max\":16}]"));
	}

	@Test
	void aZoneMovedResizedAndTurnedSaysAllThreeAndSkipsTheZeros() {
		// compile.mjs pushes move, size and count even at 0
		assertEquals("moved 4 blocks, resized by about 20% and turned a quarter turn", of("[{\"what\":\"move\",\"value\":4,\"max\":16,\"hard\":false,\"ceiling\":64},"
			+ "{\"what\":\"size\",\"value\":20,\"max\":25,\"hard\":false,\"ceiling\":50},{\"what\":\"rotate\",\"value\":90,\"max\":90,\"hard\":false,\"ceiling\":90},"
			+ "{\"what\":\"count\",\"value\":0,\"max\":0,\"hard\":false}]"));
		assertEquals("moved 1 block", of("[{\"what\":\"move\",\"value\":1},{\"what\":\"move\",\"value\":9}]"));
	}

	@Test
	void theGateKindsFromGmB() {
		assertEquals("a wooden door instead of the iron gate", of("[{\"what\":\"mechanism\",\"kind\":\"swing\",\"gate\":\"east_gate\",\"why\":\"thickness\",\"need\":3,\"thickness\":2}]"));
		assertEquals("made 3 wide and made 3 high", of("[{\"what\":\"width\",\"value\":3,\"from\":2},{\"what\":\"height\",\"value\":3,\"from\":2}]"));
	}

	@Test
	void everyOtherKindArchitectSends() {
		assertEquals("40 cobblestone in place of oak planks", of("[{\"what\":\"material\",\"value\":40,\"from\":\"minecraft:oak_planks\",\"to\":\"minecraft:cobblestone\"}]"));
		assertEquals("2 lots set on the wall's line", of("[{\"what\":\"join\",\"kind\":\"join\",\"value\":1,\"lot\":\"tower_ne\",\"key\":\"corner\",\"box\":[0,64,0,6,80,6]},"
			+ "{\"what\":\"join\",\"kind\":\"join\",\"value\":2,\"lot\":\"gate_lot\",\"key\":\"gate\",\"box\":[10,64,0,16,72,6]}]"));
		assertEquals("1 bridge where it crosses", of("[{\"what\":\"crossing\",\"kind\":\"crossing\",\"value\":1,\"bridges\":[{\"form\":\"beam\",\"span\":5,\"over\":\"water\"}]}]"));
		assertEquals("a walkable slope instead of stairs", of("[{\"what\":\"climb\",\"value\":3,\"kind\":\"walk\",\"rise\":3}]"));
		assertEquals("a longer way round (about 2.5 times the straight line)", of("[{\"what\":\"detour\",\"value\":2.5,\"max\":2,\"exceeds\":true}]"));
		assertEquals("set 6 blocks off from where it was drawn", of("[{\"what\":\"sketch\",\"value\":6,\"max\":8,\"hard\":false,\"ceiling\":32}]"));
		assertEquals("a steeper slope", of("[{\"what\":\"grade\",\"value\":0.3,\"max\":0.25}]"));
		assertEquals("12 long instead of 16 and reaching 3 out instead of 4", of("[{\"what\":\"length\",\"value\":12,\"asked\":16},{\"what\":\"reach\",\"value\":3,\"asked\":4}]"));
		assertEquals("laid out as a ring", of("[{\"what\":\"layout\",\"asked\":\"spiral\",\"got\":\"ring\"}]"));
		assertEquals("a different outline", of("[{\"what\":\"outline\",\"value\":5}]"));
	}

	@Test
	void moreThanThreeIsCut() {
		assertEquals("moved 2 blocks, resized by about 10%, 1 of its lots left out and more", of("[{\"what\":\"move\",\"value\":2},{\"what\":\"size\",\"value\":10},"
			+ "{\"what\":\"count\",\"value\":1},{\"what\":\"sketch\",\"value\":3}]"));
	}

	@Test
	void anythingElseIsItsSizeOrPlaceNeverJson() {
		for (String json : new String[] {"[{\"what\":\"wobble\",\"value\":3}]", "[]", "[\"gate moved\"]", "{\"what\":\"move\",\"value\":3}", "\"moved\"",
			"[{\"value\":3}]", "[{\"what\":\"move\",\"value\":0}]", "[{\"what\":\"move\",\"value\":\"far\"}]"}) {
			assertEquals(ChangeWords.FALLBACK, of(json), json);
		}
		assertEquals(ChangeWords.FALLBACK, ChangeWords.of(null));
		// a known kind beside an unknown one says the known one
		assertEquals("moved 3 blocks", of("[{\"what\":\"wobble\",\"value\":3},{\"what\":\"move\",\"value\":3}]"));
	}
}
