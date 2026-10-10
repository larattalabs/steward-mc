package dev.larattalabs.steward.model;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the steward answers by itself, for $0 (docs/PLAN.md phase 3b "conversation"): greetings, thanks, "what are you doing", "help". Anything else goes
 * to the talk job. Pure.
 */
public final class TalkRules {
	/** The longest thing the player may say to the steward at once. */
	public static final int MAX_SAID = 300;

	/** Whole utterances only (an optional "steward" and punctuation): "thanks, now make the tavern creepier" is a request, not thanks. */
	private static final String END = "(,? steward)?[\\s!.?]*$";
	private static final Pattern GREETING = Pattern.compile("^(hi|hello|hey|hiya|good (morning|afternoon|evening)|greetings)" + END);
	private static final Pattern THANKS = Pattern.compile("^(thanks|thank you|thanks a lot|cheers|ty|great|nice|perfect|good job|well done)" + END);
	private static final Pattern STATUS = Pattern.compile("^(status|what('s| is) (going on|happening)|how('s| is) it going|what are you (doing|up to|working on))" + END);
	private static final Pattern HELP = Pattern.compile("^(help|\\?|what can (you|i) (do|ask|say)|how does this work|commands)" + END);

	private TalkRules() {
	}

	/** What the steward can say from what it knows: {@code doing} the line for what it does now ("building 3 houses", "nothing"). */
	public record Facts(String name, String doing, int buildings, int districts, int proposals) {}

	/** The steward's own answer, or empty when the talk job should read it. */
	public static Optional<String> local(String said, Facts f) {
		String s = said == null ? "" : said.strip().toLowerCase(Locale.ROOT);
		if (s.isEmpty()) return Optional.of("Yes?");
		if (GREETING.matcher(s).matches()) return Optional.of("Hello! Tell me what " + f.name() + " needs, or ask what I am doing.");
		if (THANKS.matcher(s).matches()) return Optional.of("Glad to. Tell me if anything else wants building.");
		if (STATUS.matcher(s).matches()) {
			String props = f.proposals() == 0 ? "" : " " + (f.proposals() == 1 ? "One idea waits" : f.proposals() + " ideas wait") + " in the inbox.";
			return Optional.of("I am " + f.doing() + ". " + f.name() + " has " + f.buildings() + (f.buildings() == 1 ? " building" : " buildings")
				+ (f.districts() > 0 ? " and " + f.districts() + (f.districts() == 1 ? " district" : " districts") : "") + "." + props);
		}
		if (HELP.matcher(s).matches()) return Optional.of("Ask me for a building (\"add a library\"), a district (\"a harbour to the east\"), or a change to what stands."
			+ " I draw it up and it waits in the inbox for you.");
		return Optional.empty();
	}
}
