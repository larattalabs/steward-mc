package dev.larattalabs.steward.service;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectRefused;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.architect.api.Refusal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * (place-words) The steward's words for why a placement, a fit, a quote, a module's variant or a compose was refused, from Architect's typed
 * answer only ({@link Reason}, {@link ArchitectRefused#reason()}, a module outcome's {@code detail} code, a quote note's class, a compose's
 * {@code refused} code). Architect's own message, say or exception text is never read for a player line here: it names chunk files, entry ids
 * and the player's things, and is logged by the caller instead. Anything untyped is said as {@link #GENERIC}. The removal paths' words are
 * {@link RemovalStops}'. Pure.
 */
public final class RefusalWords {
	/** What an untyped or unknown refusal is said as: never Architect's text. */
	static final String GENERIC = "Architect couldn't do it just now";
	/** A refusal that waits on the helper or ran out of time. */
	static final String NO_ANSWER = "Architect didn't answer in time";

	private RefusalWords() {
	}

	/**
	 * A failure Steward raises itself, in the steward's own words (a save that failed, a lot it won't place): {@link #of} says its message as it is.
	 * Every other exception's message is Architect's (or the JVM's), never shown.
	 */
	public static final class Own extends IllegalStateException {
		public Own(String stewardWords) {
			super(stewardWords);
		}
	}

	/** What a typed placement refusal is, in the steward's words (a clause, no full stop); {@link #GENERIC} for one with no words of its own. Pure. */
	static String reason(@Nullable Reason r) {
		if (r == null) return GENERIC;
		return switch (r) {
			case PLAYER_IN_BOX -> "someone is standing where it goes";
			case OCCUPIED -> "an animal or a villager is where it goes";
			case OVERLAP, OVERLAP_OWNED -> "another build stands where it goes";
			case OVERLAP_BUSY, SITE_BUSY -> "the build under it is still being worked on";
			case LAVA -> "there is lava by it";
			case BLOCK_ENTITIES -> "chests or other containers stand where it goes";
			case BUILD_HEIGHT -> "it would go past the world's height limit";
			case DOOR_CUT -> "its edge would cut a door in half";
			case CREATIVE_ONLY_BLOCK -> "it has blocks that can't be built in survival";
			case NOT_ALLOWED, PERMISSION -> "that isn't allowed in this world";
			case NOT_LOADED, NOT_GENERATED, CHUNK_BOUND -> "part of the land there isn't loaded";
			case LOT_TOO_SMALL -> "it doesn't fit its lot";
			case TIMED_OUT, TILE_SLOW, SIDECAR_UNAVAILABLE -> NO_ANSWER;
			case CANCELLED -> "it was cancelled";
			case COVERED -> "another build stands over it";
			case TOO_STEEP -> "the ground is too steep";
			case DEEP_WATER -> "the water there is too deep";
			case TOO_LARGE, REGION_LIMIT -> "it is too big for the claim";
			case PROTECTED -> "it is in a protected area";
			case PLAYER_BUILD, PLAYER_BLOCKS, PLAYER_EDITS -> "your own build stands there";
			case DRIFTED, SITE_DRIFTED, ARTIFACT_STALE, REPAIR_STALE -> "the land changed since it was planned";
			case WORLD_STOPPED -> "the world stopped";
			case BILL_CAP -> "its bill went past what you approved";
			case NO_ROOM -> "there is no room left for it";
			case PROGRAM_REFUSED -> "it didn't pass Architect's checks";
			case PLAN_STALE -> "this Architect is too old for its plan";
			case PALETTE -> "the style doesn't fit the town's rules";
			default -> GENERIC;
		};
	}

	/**
	 * What a failed future's refusal is, in the steward's words: the typed reason of the {@link ArchitectRefused} anywhere in its cause chain (a
	 * {@code CompletionException} wraps it), else {@link #GENERIC}; no answer at all is {@link #NO_ANSWER}. The exception's message is never read. Pure.
	 */
	static String of(@Nullable Throwable err) {
		if (err == null) return NO_ANSWER;
		for (Throwable t = err; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof Own own && own.getMessage() != null) return own.getMessage();
			if (t instanceof ArchitectRefused r) return reason(r.reason());
			if (t instanceof java.util.concurrent.TimeoutException) return NO_ANSWER;
		}
		return GENERIC;
	}

	/** A fit's refusals in the steward's words, each typed reason once, in order. Pure. */
	static String fit(Collection<Refusal> refusals) {
		Set<String> said = new LinkedHashSet<>();
		for (Refusal r : refusals) said.add(reason(r == null ? null : r.reason()));
		return said.isEmpty() ? GENERIC : String.join("; ", said);
	}

	/**
	 * Why a module's variant wasn't made, from its outcome's typed {@code detail} ({@code ModuleInstalls.Outcome}): Steward's own refusals ({@code
	 * gate:*}, {@code params}, {@code palette}, {@code key}) keep their own words ({@code stewardMessage}); Architect's ({@code <reason>[:<detail>]})
	 * are said by their reason; a program the checker refused (a rule id) as {@link Reason#PROGRAM_REFUSED}; anything else ({@code error}, a lost
	 * answer) as {@link #GENERIC}. Pure.
	 */
	static String module(@Nullable String detail, @Nullable String stewardMessage) {
		if (detail == null) return GENERIC;
		String d = detail.strip();
		if ((d.startsWith("gate:") || d.equals("params") || d.equals("palette") || d.equals("key")) && stewardMessage != null && !stewardMessage.isBlank()) {
			return stewardMessage.strip().replaceAll("[.]+$", "");
		}
		if (d.equals("error") || d.isEmpty()) return GENERIC;
		String head = d.contains(":") ? d.substring(0, d.indexOf(':')) : d;
		Reason r = reasonNamed(head);
		if (r != null) return reason(r);
		// ProgramRefused's detail is the first finding's rule id, no reason in front
		return reason(Reason.PROGRAM_REFUSED);
	}

	/** The reason named {@code name} (any case), or null. Pure. */
	static @Nullable Reason reasonNamed(@Nullable String name) {
		if (name == null || name.isBlank()) return null;
		try {
			return Reason.valueOf(name.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * What a quote left unbilled ({@code ModulePlacement.QuoteCheck.refused}: Architect's notes, classed there), in the steward's words: a part with no
	 * bill, a part that couldn't be read, or both. Pure.
	 */
	static String unbilled(@Nullable String refusedNotes) {
		String n = refusedNotes == null ? "" : refusedNotes;
		List<String> said = new ArrayList<>();
		if (n.contains("not billed") || n.contains("no construction plan")) said.add("part of it has no bill");
		if (n.contains("could not be read")) said.add("part of it couldn't be read");
		return said.isEmpty() ? "part of it has no bill" : String.join(" and ", said);
	}

	/**
	 * Why a quote may differ ({@code ModulePlacement.QuoteCheck.ask}: Architect's notes, or Steward's "its quote is not exact"), in the steward's
	 * words: land not loaded, land that may change, else the quote is not exact. Pure.
	 */
	static String inexact(@Nullable String askNotes) {
		String n = askNotes == null ? "" : askNotes.toLowerCase(Locale.ROOT);
		List<String> said = new ArrayList<>();
		if (n.contains("unloaded") || n.contains("not loaded")) said.add("part of the land isn't loaded");
		if (n.contains("may differ") || n.contains("drift")) said.add("the land may change before it is built");
		return said.isEmpty() ? "its quote is not exact" : String.join(" and ", said);
	}

	/**
	 * (raw-sweep) What a plan check's part reason code is ({@code plan.json $defs/reason.code}, not a {@link Reason}), in the steward's words (a clause,
	 * no full stop); {@link #GENERIC} for a code with no words of its own. The reason's {@code message} is never read. Pure.
	 */
	static String planCode(@Nullable String code) {
		if (code == null) return GENERIC;
		return switch (code.strip().toUpperCase(Locale.ROOT)) {
			case "SCHEMA", "UNKNOWN_REF", "CYCLE" -> "its plan doesn't hold together";
			case "EXTENSION_BOUND", "UNSUPPORTED", "NO_PROGRAM" -> "this Architect can't build that kind of part";
			case "NO_FEATURE" -> "the land has nothing it needs there (a slope, a river or a hill)";
			case "NO_ROOM" -> "there is no room left for it";
			case "OUT_OF_CLAIM" -> "it would go outside the claim";
			case "OVERLAP" -> "another build stands where it goes";
			case "PROTECTED" -> "it is in a protected area";
			case "KEEP_OUT" -> "it would go where the plan keeps clear";
			case "TOO_STEEP" -> "the ground is too steep";
			case "WATER" -> "there is water where it goes";
			case "UNREACHABLE" -> "it couldn't be reached from the street";
			case "TOLERANCE" -> "the land is too far from what it needs";
			case "DEPENDENCY" -> "a part it needs can't be built";
			case "BUDGET", "LIMIT" -> "it is past what Architect may work out for one plan";
			case "CANCELLED" -> "it was cancelled";
			case "NO_GAP" -> "there is no gap for it to cross";
			case "SPAN" -> "the gap is too wide or too narrow for it";
			case "ANCHOR" -> "there is no firm ground to hold it";
			case "HEADROOM" -> "there isn't room above it";
			case "MOVER" -> "villagers couldn't get across it";
			case "NO_COVER" -> "there isn't enough rock over it";
			case "VOLUME" -> "Architect couldn't read all the rock it goes through";
			case "LEAK" -> "its water would spill where it shouldn't";
			case "FIRE" -> "it would put something that burns too near fire";
			case "VOCAB", "PALETTE" -> "the style doesn't fit the town's rules";
			case "SURVIVAL" -> "it has blocks that can't be built in survival";
			case "NO_CLIMB" -> "the ground doesn't rise enough for it";
			case "AUTHORED_STALE" -> "the land changed since it was planned";
			default -> GENERIC;
		};
	}

	/** (raw-sweep) What a plan check's part class ({@code partAnswer.class}) that isn't {@code fits} or {@code adjusted} means, in the steward's words. Pure. */
	static String planClass(@Nullable String cls) {
		return switch (cls == null ? "" : cls.strip().toLowerCase(Locale.ROOT)) {
			case "unsupported" -> "this Architect can't build it";
			case "blocked" -> "something stands in its way";
			case "malformed" -> "its plan doesn't hold together";
			case "incomplete" -> "Architect ran out of time working it out";
			case "refused" -> "it doesn't fit there";
			default -> GENERIC;
		};
	}

	/** (raw-sweep) A plan check's overall answer with no artifact, in the steward's words (never the enum's name). Pure. */
	static String planOverall(dev.larattalabs.architect.api.PlanCheck.@Nullable Overall o) {
		if (o == null) return "Architect's check gave no answer";
		return switch (o) {
			case MALFORMED -> "the plan doesn't hold together";
			case INCOMPLETE -> "Architect ran out of time checking the plan";
			case REQUIRED_BLOCKED -> "a part the plan needs can't be built here";
			default -> "Architect's check came back with nothing to build from";
		};
	}

	/**
	 * Why a compose ended with no plan, from its result's typed {@code refused} code ({@code "NOT_ALLOWED: ..."}: the reason before the colon), or
	 * null when it has none (the caller says its own generic line). A compose refused for another level than the Overworld is said so. Pure.
	 */
	static @Nullable String composeRefused(@Nullable JsonObject result) {
		if (result == null || !result.has("refused") || !result.get("refused").isJsonPrimitive()) return null;
		String code = result.get("refused").getAsString();
		Reason r = reasonNamed(code.contains(":") ? code.substring(0, code.indexOf(':')) : code);
		if (r == null) return null;
		if (r == Reason.NOT_ALLOWED) return "towns can only be built in the Overworld for now";
		return reason(r);
	}
}
