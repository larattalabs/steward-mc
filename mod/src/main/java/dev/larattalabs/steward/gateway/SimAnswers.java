package dev.larattalabs.steward.gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Scripted answers for Architect's sim helper (v0.12.2, {@code ARCHITECT_SIDECAR_BACKEND=sim}), set by the $0 checks with {@code /steward dev sim_answer}:
 * a job of that kind carries its answer in {@code ext["architect:simAnswer"]}, which the sim returns (validated against the job's schema). The real helper
 * never sees one unless a host set it. One world's.
 */
public final class SimAnswers {
	public static final String CARD = "card", TALK = "talk", PROPOSAL = "proposal", PLAN = "plan";
	private static final Map<String, JsonElement> ANSWERS = new HashMap<>();

	private SimAnswers() {
	}

	public static synchronized void set(String kind, @Nullable JsonElement answer) {
		if (answer == null) ANSWERS.remove(kind);
		else ANSWERS.put(kind, answer.deepCopy());
	}

	public static synchronized void clear() {
		ANSWERS.clear();
	}

	/** A job's ext: the scripted answer of {@code kind}, when one is set. */
	public static synchronized JsonObject ext(String kind) {
		JsonObject ext = new JsonObject();
		JsonElement a = ANSWERS.get(kind);
		if (a != null) ext.add("architect:simAnswer", a.deepCopy());
		return ext;
	}
}
