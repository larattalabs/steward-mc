package dev.larattalabs.steward.module;

import dev.larattalabs.steward.module.ModuleCatalog.PaletteRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A bible's roles against a module's palette constraints (docs/MODULES.md 2.2), before Steward asks Architect for a re-skin. The program
 * checks the same rules and refuses a bible that breaks them; this is the same judgement made earlier, so a breaking bible is never sent.
 * <ul>
 * <li>The lamp role must give block light {@code minLight} or more. Otherwise the program uses its fallback lantern, which is part of the
 * measured envelope and said on the offer. This is the only substitution.</li>
 * <li>Roles that touch the core may not be gravity blocks ({@code noGravity}), and those in a circuit plane may not conduct redstone
 * ({@code nonConductive}).</li>
 * </ul>
 * Pure: the block facts come from a {@link BlockFacts} (Minecraft's registry in game, a table in tests).
 */
public final class PaletteCheck {
	private PaletteCheck() {}

	/** What Steward needs to know about a block id (e.g. {@code minecraft:lantern}) in its default state. */
	public interface BlockFacts {
		/** Whether this is a known block. */
		boolean known(String block);

		/** The block light it gives (0-15). */
		int light(String block);

		/** Whether it falls (sand, gravel, concrete powder, anvils...). */
		boolean gravity(String block);

		/** Whether it conducts redstone power. */
		boolean conducts(String block);
	}

	/**
	 * The verdict: {@code broken} lists every constraint a role breaks (refused when non-empty); otherwise {@code lamp} is the lamp the
	 * program will use and {@code lampFallback} says the bible's own was too dim.
	 */
	public record Result(List<String> broken, @Nullable String lamp, boolean lampFallback) {
		public Result {
			broken = List.copyOf(broken);
		}

		public boolean ok() {
			return broken.isEmpty();
		}
	}

	public static Result check(PaletteRules rules, Map<String, String> roles, BlockFacts facts) {
		List<String> broken = new ArrayList<>();
		for (String r : rules.noGravity()) {
			String b = roles.get(r);
			if (b == null) continue;
			if (!facts.known(b)) broken.add(r + " '" + b + "' is not a block");
			else if (facts.gravity(b)) broken.add(r + " '" + b + "' is a gravity block");
		}
		for (String r : rules.nonConductive()) {
			String b = roles.get(r);
			if (b == null) continue;
			if (!facts.known(b)) broken.add(r + " '" + b + "' is not a block");
			else if (facts.conducts(b)) broken.add(r + " '" + b + "' conducts redstone");
		}
		String lamp = null;
		boolean fallback = false;
		if (rules.lamp() != null) {
			String b = roles.get(rules.lamp().role());
			if (b != null && facts.known(b) && facts.light(b) >= rules.lamp().minLight()) lamp = b;
			else {
				lamp = rules.lamp().fallback();
				fallback = true;
			}
		}
		return new Result(broken, lamp, fallback);
	}
}
