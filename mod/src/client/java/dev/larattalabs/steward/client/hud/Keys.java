package dev.larattalabs.steward.client.hud;

import com.mojang.blaze3d.platform.InputConstants;
import dev.larattalabs.steward.Steward;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;

/**
 * Steward's key mapping (Options > Controls > Key Binds > Steward, rebindable like any vanilla key): the inbox ({@code Y}; Architect has
 * {@code B}). Registered once. Ported from Architect's client kit.
 */
public final class Keys {
	public static KeyMapping inbox;
	/** Open the panel of the building the crosshair is on ({@code I}); survey mode, every building labelled and the claim's border ({@code U}). */
	public static KeyMapping inspect, survey;
	private static boolean registered;

	private Keys() {
	}

	public static synchronized void ensureRegistered() {
		if (registered) {
			return;
		}
		registered = true;
		KeyMapping.Category cat;
		try {
			cat = KeyMapping.Category.register(Steward.id("steward"));
		} catch (IllegalArgumentException alreadyThere) {
			cat = new KeyMapping.Category(Steward.id("steward"));
		}
		inbox = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.steward_mc.inbox", InputConstants.Type.KEYBOARD, InputConstants.KEY_Y, cat, 1));
		inspect = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.steward_mc.inspect", InputConstants.Type.KEYBOARD, InputConstants.KEY_I, cat, 2));
		survey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.steward_mc.survey", InputConstants.Type.KEYBOARD, InputConstants.KEY_U, cat, 3));
	}

	/**
	 * Short label of the key a mapping is bound to ("B", "Enter", "Backtick"). Punctuation keys whose glyph is only a pixel
	 * or two in the Minecraft font are spelled out, so a keycap never looks empty.
	 */
	public static String label(KeyMapping k) {
		if (k == null || k.isUnbound()) {
			return "?";
		}
		return readable(k.getTranslatedKeyMessage().getString());
	}

	/** Spell out tiny punctuation glyphs; cut long names to 9 characters. */
	public static String readable(String s) {
		String word = switch (s) {
			case "`" -> "Backtick";
			case "'" -> "Quote";
			case "´" -> "Accent";
			case "," -> "Comma";
			case "." -> "Period";
			case ";" -> "Semicolon";
			case ":" -> "Colon";
			case "|" -> "Bar";
			default -> s;
		};
		return word.length() > 9 ? word.substring(0, 9) : word;
	}

	public static boolean matches(KeyMapping k, KeyEvent e) {
		return k != null && k.matches(e);
	}
}
