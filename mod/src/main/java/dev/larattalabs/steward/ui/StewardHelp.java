package dev.larattalabs.steward.ui;

import dev.larattalabs.labui.ui.HelpKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * (UI-PR-S) The help on Steward's screens, lab-ui L-0.6's player-ready pass: each screen's "what's this" line (one line of at most
 * {@link HelpKeys#MAX_TIP} characters, hidden with its × and kept hidden in lab-ui's {@code config/lab_ui.json}), and its "?" overlay
 * (Shift+/ or F1): the screen's keys and the glossary words it uses. Pure; the client builds lab-ui's {@code WhatsThis} and {@code KeysOverlay}
 * from these. Steward's own world keys (the inbox, inspect, survey: Y, I, U by default) are read live, so a rebound key shows as bound.
 */
public final class StewardHelp {
	/** The tips' keys are namespaced, as Architect's are ({@code architect_mc:library}). */
	public static final String NS = "steward_mc:";

	/** Steward's screens with help, by id. */
	public enum Screen {
		SETTLEMENT("settlement", "Settlement"),
		BUILDING("building", "Building"),
		INBOX("inbox", "Inbox"),
		CARD("card", "Concept card"),
		TALK("talk", "Talk"),
		DESCRIBE("describe", "Describe");

		public final String id;
		public final String title;

		Screen(String id, String title) {
			this.id = id;
			this.title = title;
		}

		public String key() {
			return NS + id;
		}
	}

	/** Steward's world keys, by the id its key mapping has ({@code key.steward_mc.<id>}). */
	public static final List<String> WORLD_KEYS = List.of("inbox", "inspect", "survey");

	public static final Map<Screen, String> TIPS = new LinkedHashMap<>();
	public static final Map<Screen, List<String>> TERMS = new LinkedHashMap<>();

	static {
		TIPS.put(Screen.SETTLEMENT, "Your settlement: its buildings on a map, what changed, its claim and its settings.");
		TIPS.put(Screen.BUILDING, "One building: the design it stands at, your edits, and the versions it can go back to.");
		TIPS.put(Screen.INBOX, "What your stewards wait on: a choice to make, a build to start, or an idea to take up.");
		TIPS.put(Screen.CARD, "The steward's reading of your words: the buildings it will plan. Start it, or change it.");
		TIPS.put(Screen.TALK, "Ask the steward for a building, a district or a change, or ask what it is doing.");
		TIPS.put(Screen.DESCRIBE, "Say what this place should be in your own words; the steward turns them into a card.");
		TERMS.put(Screen.SETTLEMENT, List.of("settlement", "district", "quarter", "lot", "stockpile", "Founding Stone"));
		TERMS.put(Screen.BUILDING, List.of("lot", "design"));
		TERMS.put(Screen.INBOX, List.of("proposal", "bill limit", "stockpile", "module"));
		TERMS.put(Screen.CARD, List.of("settlement", "district", "module", "bill limit"));
		TERMS.put(Screen.TALK, List.of("steward", "district", "proposal"));
		TERMS.put(Screen.DESCRIBE, List.of("settlement", "steward"));
	}

	private StewardHelp() {
	}

	public static String tip(Screen s) {
		return TIPS.get(s);
	}

	public static List<String> terms(Screen s) {
		return TERMS.getOrDefault(s, List.of());
	}

	/**
	 * The overlay's rows for {@code s} (lab-ui adds its own "close" row last). {@code keyLabel} reads a world key's label by its id in
	 * {@link #WORLD_KEYS}, live (the client: the bound key's name).
	 */
	public static List<HelpKeys.Row> rows(Screen s, Function<String, String> keyLabel) {
		List<HelpKeys.Row> out = new ArrayList<>();
		switch (s) {
			case SETTLEMENT -> {
				out.add(new HelpKeys.Row("Tab", "the next tab"));
				out.add(new HelpKeys.Row("↑↓←→", "choose a building in the list or on the map"));
				out.add(new HelpKeys.Row("Enter", "open the chosen building"));
				out.add(new HelpKeys.Row("1-9", "press the button with that number"));
			}
			case BUILDING -> {
				out.add(new HelpKeys.Row("←→", "choose the version to go back to"));
				out.add(new HelpKeys.Row("↑↓", "scroll its history"));
				out.add(new HelpKeys.Row("1-9", "press the button with that number"));
			}
			case INBOX -> {
				out.add(new HelpKeys.Row("↑↓", "choose a settlement"));
				out.add(new HelpKeys.Row("1-9", "press the button with that number"));
			}
			case CARD -> {
				out.add(new HelpKeys.Row("←→", "fewer or more buildings"));
				out.add(new HelpKeys.Row("↑↓", "scroll the list"));
				out.add(new HelpKeys.Row("Tab", "the buildings or the steward's reading"));
				out.add(new HelpKeys.Row("Enter ×2", "start the build (the second press spends)"));
			}
			case TALK -> {
				out.add(new HelpKeys.Row("Enter", "say it"));
				out.add(new HelpKeys.Row("Tab", "leave the box, or go back to it"));
				out.add(new HelpKeys.Row("1-4", "press the button with that number"));
			}
			case DESCRIBE -> {
				out.add(new HelpKeys.Row("Enter", "make the card"));
				out.add(new HelpKeys.Row("Shift+Enter", "a new line"));
				out.add(new HelpKeys.Row("Tab", "leave the box, or go back to it"));
			}
		}
		out.add(new HelpKeys.Row("Esc", "close"));
		// outside any screen: Steward's own keys, as bound now
		out.add(new HelpKeys.Row(keyLabel.apply("inbox"), "the inbox (no screen open)"));
		if (s == Screen.SETTLEMENT || s == Screen.BUILDING) {
			out.add(new HelpKeys.Row(keyLabel.apply("inspect"), "the building you look at"));
			out.add(new HelpKeys.Row(keyLabel.apply("survey"), "survey: every building labelled"));
		}
		return out;
	}
}
