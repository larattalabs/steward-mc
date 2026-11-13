package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.module.ModuleCatalog.Lamp;
import dev.larattalabs.steward.module.ModuleCatalog.PaletteRules;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Palette constraints for the roles that touch a module's core (docs/MODULES.md 2.2). */
class PaletteCheckTest {
	/** A few vanilla facts (light, gravity, redstone) as Minecraft 26.3 has them. */
	static final PaletteCheck.BlockFacts FACTS = new PaletteCheck.BlockFacts() {
		final Map<String, int[]> t = Map.of(
			"minecraft:lantern", new int[] {15, 0, 0},
			"minecraft:sea_lantern", new int[] {15, 0, 1},
			"minecraft:soul_lantern", new int[] {10, 0, 0},
			"minecraft:torch", new int[] {14, 0, 0},
			"minecraft:dirt_path", new int[] {0, 0, 0},
			"minecraft:gravel", new int[] {0, 1, 1},
			"minecraft:red_concrete_powder", new int[] {0, 1, 1},
			"minecraft:cobblestone", new int[] {0, 0, 1},
			"minecraft:spruce_log", new int[] {0, 0, 1});

		public boolean known(String b) {
			return t.containsKey(b);
		}

		public int light(String b) {
			return t.get(b)[0];
		}

		public boolean gravity(String b) {
			return t.get(b)[1] == 1;
		}

		public boolean conducts(String b) {
			return t.get(b)[2] == 1;
		}
	};
	static final PaletteRules RULES = new PaletteRules(new Lamp("light", 14, "minecraft:lantern"), List.of("path", "frame"), List.of("path"));

	static Map<String, String> roles(String light, String path, String frame) {
		Map<String, String> m = new HashMap<>();
		m.put("light", light);
		m.put("path", path);
		m.put("frame", frame);
		m.put("wall", "minecraft:gravel"); // a role that does not touch the core may be anything
		return m;
	}

	@Test
	void aBibleThatKeepsTheRulesIsTakenWithItsOwnLamp() {
		var r = PaletteCheck.check(RULES, roles("minecraft:sea_lantern", "minecraft:dirt_path", "minecraft:spruce_log"), FACTS);
		assertTrue(r.ok());
		assertEquals("minecraft:sea_lantern", r.lamp());
		assertFalse(r.lampFallback());
		assertEquals("minecraft:torch", PaletteCheck.check(RULES, roles("minecraft:torch", "minecraft:dirt_path", "minecraft:spruce_log"), FACTS).lamp(), "14 is enough");
	}

	@Test
	void aDimLampIsTheOneSubstitutionAndIsSaid() {
		var r = PaletteCheck.check(RULES, roles("minecraft:soul_lantern", "minecraft:dirt_path", "minecraft:spruce_log"), FACTS);
		assertTrue(r.ok(), "a dim lamp is not a refusal");
		assertEquals("minecraft:lantern", r.lamp());
		assertTrue(r.lampFallback());
		assertTrue(PaletteCheck.check(RULES, roles("minecraft:no_such_lamp", "minecraft:dirt_path", "minecraft:spruce_log"), FACTS).lampFallback());
	}

	@Test
	void gravityOrConductiveCoreRolesAreRefusedNeverSubstituted() {
		var g = PaletteCheck.check(RULES, roles("minecraft:lantern", "minecraft:gravel", "minecraft:spruce_log"), FACTS);
		assertFalse(g.ok());
		assertTrue(g.broken().contains("path 'minecraft:gravel' is a gravity block"));
		assertTrue(g.broken().contains("path 'minecraft:gravel' conducts redstone"));
		var c = PaletteCheck.check(RULES, roles("minecraft:lantern", "minecraft:cobblestone", "minecraft:red_concrete_powder"), FACTS);
		assertEquals(List.of("frame 'minecraft:red_concrete_powder' is a gravity block", "path 'minecraft:cobblestone' conducts redstone"), c.broken().stream().sorted().toList());
		assertFalse(PaletteCheck.check(RULES, roles("minecraft:lantern", "minecraft:not_a_block", "minecraft:spruce_log"), FACTS).ok(), "an unknown block on a core role");
	}
}
