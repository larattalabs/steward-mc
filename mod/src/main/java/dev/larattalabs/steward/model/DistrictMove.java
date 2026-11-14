package dev.larattalabs.steward.model;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Placing a waiting district again from what the player says (docs/PLAN.md "Zones": "no, by the lake" and Steward places it again). One district waits
 * at a time; a new ask for one while it waits moves it rather than adding a second. "No", "not there", "somewhere else" rule out the side it waits on
 * (unless they name it). When the words ask for ground no side has, it stays where it was and the steward says so. Pure.
 */
public final class DistrictMove {
	private DistrictMove() {
	}

	private static final Pattern AWAY = Pattern.compile("^\\s*(no|nope|nah)\\b|\\b(not (there|that (side|way|one))|somewhere else|elsewhere|another (side|place|spot)|"
		+ "the other side|move it|instead)\\b");

	/** Whether the words turn away from where the district waits ("no, by the lake"; "somewhere else"; "move it by the river"). */
	public static boolean awayFrom(@Nullable String said) {
		return said != null && AWAY.matcher(said.toLowerCase(Locale.ROOT)).find();
	}

	/**
	 * The sides not to consider: those declined, and the side the district waits on ({@code waiting}, null when none waits) when the player turned away
	 * from it, unless they named that side ({@code named}).
	 */
	public static Set<String> skip(Set<String> declined, @Nullable String waiting, String said, @Nullable String named) {
		Set<String> out = new HashSet<>(declined);
		if (waiting != null && awayFrom(said) && !waiting.equals(named)) out.add(waiting);
		return out;
	}

	/** What happens to the ask: a district offered on a side, or kept where it waits (with why), or nowhere to go. */
	public record Outcome(@Nullable String way, boolean stays, String reply) {}

	/**
	 * What to do with the side {@code choice} gave, for words that ask for {@code site}, while {@code waiting} (null: none) waits under {@code name}.
	 * Ground asked for that no side has: a waiting district stays put (and the player is told what was not found); with none waiting, the side chosen is
	 * offered with no reason. {@code reply} is only set when nothing is offered.
	 */
	public static Outcome decide(DistrictSite site, DistrictSite.Choice choice, @Nullable String waiting, @Nullable String name, String settlementName) {
		String wish = site.wish();
		boolean unmet = site.caresAboutGround() && wish != null && choice.why().isEmpty() && site.way() == null;
		if (waiting != null && (choice.way() == null || unmet)) {
			String what = name == null || name.isBlank() ? "the district" : name;
			String why = choice.way() == null ? "there is no other side with room for it" : "I see no " + wish + " beside " + settlementName + " on a side with room";
			return new Outcome(null, true, capital(why) + ", so " + what + " stays to the " + DistrictRules.directionName(waiting) + ". Approve it there, or tell me another way.");
		}
		if (choice.way() == null) return new Outcome(null, false, "There is no way left to found a district that fits.");
		return new Outcome(choice.way(), false, "");
	}

	/** The reply when a waiting district moved from {@code from} to {@code to}, and the line survey mode shows for it. */
	public static String movedReply(@Nullable String reply, String to, @Nullable String from, String why) {
		String side = DistrictRules.directionName(to);
		String moved = from != null && !from.equals(to) ? " (instead of to the " + DistrictRules.directionName(from) + ")" : "";
		String head = reply == null || reply.isBlank() ? "" : reply.strip() + " ";
		return why.isEmpty() ? (head + "It goes to the " + side + moved + ".").strip() : head + "I would put it to the " + side + moved + ", " + why + ".";
	}

	private static String capital(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
