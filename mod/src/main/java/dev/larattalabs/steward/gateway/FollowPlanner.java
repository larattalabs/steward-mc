package dev.larattalabs.steward.gateway;

import dev.larattalabs.architect.api.Designs;
import dev.larattalabs.architect.api.Library;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Copies follow their source (docs/FOLLOW.md, Architect FS): which sources' copies are re-made, under which key, and how each answer and each follow
 * update is put to the player. Pure apart from the API record types.
 */
public final class FollowPlanner {
	private FollowPlanner() {
	}

	/** A source whose copies are re-made from the version its lot stands at: {@code copies} the COPY entries made from an older one. */
	public record Source(String entryId, int target, List<String> copies, Map<String, Integer> heads) {
		public Source(String entryId, int target, List<String> copies) {
			this(entryId, target, copies, Map.of());
		}
	}

	/**
	 * The sources to follow: for each blueprint the settlement's buildings stand at ({@code standing}: entry id -> the highest version a lot of it stands
	 * at), its COPY followers made from an older version than that. The target is the standing version, never the head: a version the player skipped or
	 * reverted away from never reaches the copies. VARIANT, RESKIN and SITE followers never follow here.
	 */
	public static List<Source> toFollow(Map<String, Integer> standing, Function<String, List<Library.Follower>> followers) {
		List<Source> out = new ArrayList<>();
		for (var e : standing.entrySet()) {
			int target = e.getValue();
			if (target < 2) continue;
			List<Library.Follower> behind = followers.apply(e.getKey()).stream().filter(f -> f.kind() == Library.Derivation.Kind.COPY && f.sourceVersion() > 0
				&& f.sourceVersion() < target).toList();
			Map<String, Integer> heads = new java.util.TreeMap<>();
			behind.forEach(f -> heads.put(f.entryId(), f.headVersion()));
			if (!behind.isEmpty()) out.add(new Source(e.getKey(), target, behind.stream().map(Library.Follower::entryId).toList(), Map.copyOf(heads)));
		}
		out.sort(java.util.Comparator.comparing(Source::entryId));
		return out;
	}

	/**
	 * The operation's key: the source, the target version and the copies with their heads ({@code copy@head}), so a restart resumes or replays
	 * Architect's saved operation ($0). A copy whose head moved, or a copy made later, is another member set and so another operation; a refused copy
	 * keeps its head, so its key replays the refusal (it is not re-made). Not per settlement: settlements at one source version share it.
	 */
	public static String opKey(String sourceEntryId, int target, Map<String, Integer> copyHeads) {
		String members = copyHeads.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + "@" + e.getValue())
			.collect(java.util.stream.Collectors.joining(","));
		return "steward_mc:follow:" + sourceEntryId + ":v" + target + ":" + hash(members);
	}

	/** What a refused copy is remembered by: the copy, its head and the target ({@code c1@1>v2}); the same three are not re-made. */
	public static String refusedKey(String copy, int head, int target) {
		return copy + "@" + head + ">v" + target;
	}

	/**
	 * The source without the copies already refused at their head for this target (a mixed answer: one followed, one refused, then the next scan's
	 * member set is the refused one alone, which would re-make it). Null when none is left.
	 */
	public static @Nullable Source without(Source src, java.util.Set<String> refused) {
		List<String> left = src.copies().stream().filter(c -> !refused.contains(refusedKey(c, src.heads().getOrDefault(c, 0), src.target()))).toList();
		if (left.isEmpty()) return null;
		Map<String, Integer> heads = new java.util.TreeMap<>();
		left.forEach(c -> heads.put(c, src.heads().getOrDefault(c, 0)));
		return new Source(src.entryId(), src.target(), left, Map.copyOf(heads));
	}

	/** The first 10 hex digits of the SHA-256 of {@code s}. */
	static String hash(String s) {
		try {
			byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(d).substring(0, 10);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Whether an answer is told to the player: a refusal (the copy stays); not a follow, an already-followed copy or a head another follow moved. */
	public static boolean refused(Designs.FollowResult r) {
		return !r.followed() && !r.alreadyFollowed() && r.refused() != null && !"head_moved".equals(r.refused());
	}

	/** A refused copy, in words: "The fisher's hut (h2) stays as it is: the fisher's hut's new design (v2) would no longer fit its lot." */
	public static String refusalText(String copyLot, String sourceLot, int target, Designs.FollowResult r) {
		String why = switch (r.refused() == null ? "" : r.refused()) {
			case "lot_too_small" -> "would no longer fit its lot";
			case "frame" -> "would move its entrance";
			case "bar" -> "would make it the same building as the " + sourceLot;
			case "fit" -> "would be larger than a copy of it may be";
			case "check" -> "did not pass Architect's check as a copy";
			case "bible_gone" -> "needs a style bible that is gone";
			case "source_gone" -> "is gone";
			default -> "could not be followed (" + r.refused() + ")";
		};
		return "The " + copyLot + " stays as it is: the " + sourceLot + "'s new design (v" + target + ") " + why + ".";
	}

	/** The copies that followed, in one line: "The fisher's hut's copies (h2, h3) follow its new design (v2): their updates wait in the inbox." */
	public static String followedText(String sourceLot, List<String> copyLots, int target) {
		String which = String.join(", ", copyLots);
		return (copyLots.size() == 1 ? "The " + sourceLot + "'s copy (" + which + ") follows" : "The " + sourceLot + "'s copies (" + which + ") follow")
			+ " its new design (v" + target + "): " + (copyLots.size() == 1 ? "its update waits" : "their updates wait") + " in the inbox.";
	}

	/** A whole call refused by Architect (typed): said once, not asked again for this version. */
	public static String callRefusedText(String sourceLot, int target, String why) {
		return "The " + sourceLot + "'s copies can't follow its new design (v" + target + "): " + why + ".";
	}

	/**
	 * Whether a copy's follow update is offered now: while its source stands in the settlement at the version it follows or later. {@code sourceStanding}
	 * null: the source stands nowhere in the settlement, which holds nothing back.
	 */
	public static boolean offered(@Nullable Integer sourceStanding, int followsSourceVersion) {
		return sourceStanding == null || sourceStanding >= followsSourceVersion;
	}

	/** A follow update always waits for the player (the player changed one building, not its copies), and says what it follows. */
	public static UpdatePlanner.Plan asFollow(UpdatePlanner.Plan p, String sourceLot) {
		if (p.action() == UpdatePlanner.Action.NOTHING) return p;
		UpdatePlanner.Action a = p.action() == UpdatePlanner.Action.APPLY ? UpdatePlanner.Action.ASK : p.action();
		// "hut: follows the cottage's new design; 2 parts changed ...": the inbox line keeps what follows the first ": "
		String text = p.text().replaceFirst(" has an update: ", java.util.regex.Matcher.quoteReplacement(": follows the " + sourceLot + "'s new design; "));
		return new UpdatePlanner.Plan(a, text, p.playerEdits());
	}

	/** One building a name matched: its site, its entry, and the entry its design is a COPY of (null when it is not a copy). */
	public record Match(String siteId, String entryId, @Nullable String copyOf) {}

	/**
	 * Several buildings matched a name ("the fisher's hut"): when they are one family, a source and its copies, the source is the one meant. Empty when
	 * they are not one family, or the source does not stand on exactly one lot among them.
	 */
	public static java.util.Optional<String> familySource(List<Match> matches) {
		if (matches.size() < 2) return java.util.Optional.empty();
		String source = null;
		for (Match m : matches) {
			String root = m.copyOf() != null ? m.copyOf() : m.entryId();
			if (source == null) source = root;
			else if (!source.equals(root)) return java.util.Optional.empty();
		}
		final String src = source;
		var at = matches.stream().filter(m -> m.copyOf() == null && m.entryId().equals(src)).toList();
		return at.size() == 1 ? java.util.Optional.of(at.get(0).siteId()) : java.util.Optional.empty();
	}

	/** A change asked for a copy: Architect makes a copy's versions only by following, so the change is asked of its source. */
	public static String copyChangeText(String copyLot, String sourceLot) {
		return "The " + copyLot + " is a copy of the " + sourceLot + "'s design: ask for the change on the " + sourceLot + ", and its copies follow.";
	}
}
