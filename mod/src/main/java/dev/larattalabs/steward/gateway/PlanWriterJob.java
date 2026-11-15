package dev.larattalabs.steward.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What is left of Steward's plan writer: the portcullis default Steward adds to a composed plan before its own check. Since slice CA the whole plan,
 * and since CA2 (Architect CP) a waiting part's plan too, is written by Architect's composer ({@code Regions.compose}, {@link ComposeJob}); the plan
 * writer's job, its prompt and its repair-round catalogue are gone. Pure.
 */
public final class PlanWriterJob {
	private PlanWriterJob() {
	}

	/** (CA) The portcullis default on a composed plan (Architect's compose answers it unchanged; Steward adds the mechanism before its own check). */
	public static void gateMechanisms(JsonObject plan) {
		withGateMechanisms(plan);
	}

	/** Architect GM: a gatehouse's mechanism fits a lot at least this wide along its wall and this deep through it (CONTRACT GM §14, 13 x 7 x 10). */
	static final int MECH_ALONG = 13, MECH_THROUGH = 7;

	/**
	 * Every gatehouse a wall names gets a portcullis (Noah, 2026-11-03, once Architect's GM-a2 bills it in survival). On a wall with a {@code lots} member,
	 * its {@code at: gate} entries get {@code mech}, but only when the lot's planned size fits the mechanism: an explicit {@code mech} on a required wall
	 * that can't hold it is refused NO_ROOM. On a wall without one, the lenient {@code params.mechanism}, which a too-small gatehouse answers with a plain
	 * passage. A mechanism the plan already names, or declines on the wall ({@code params.mechanism: "none"}), is kept. Applied to the writer's answer, never to a kept plan, so a saved plan stays as approved.
	 */
	static void withGateMechanisms(JsonObject plan) {
		if (!plan.has("connectors") || !plan.get("connectors").isJsonArray()) return;
		Map<String, JsonObject> lots = new java.util.HashMap<>();
		if (plan.has("zones") && plan.get("zones").isJsonArray()) for (JsonElement z : plan.getAsJsonArray("zones")) {
			JsonObject prog = z.isJsonObject() && z.getAsJsonObject().has("program") && z.getAsJsonObject().get("program").isJsonObject() ? z.getAsJsonObject().getAsJsonObject("program") : null;
			if (prog == null || !prog.has("lots") || !prog.get("lots").isJsonArray()) continue;
			for (JsonElement l : prog.getAsJsonArray("lots"))
				if (l.isJsonObject() && l.getAsJsonObject().has("id") && l.getAsJsonObject().get("id").isJsonPrimitive()) lots.put(l.getAsJsonObject().get("id").getAsString(), l.getAsJsonObject());
		}
		for (JsonElement e : plan.getAsJsonArray("connectors")) {
			if (!e.isJsonObject()) continue;
			JsonObject w = e.getAsJsonObject();
			if (!w.has("kind") || !w.get("kind").isJsonPrimitive() || !"wall".equals(w.get("kind").getAsString())) continue;
			JsonObject params = w.has("params") && w.get("params").isJsonObject() ? w.getAsJsonObject("params") : null;
			if (w.has("lots") && w.get("lots").isJsonArray()) {
				// the card asked for open gates: the writer says so on the wall (lots entries have no "none")
				if (params != null && declines(params.get("mechanism"))) continue;
				for (JsonElement q : w.getAsJsonArray("lots")) {
					if (!q.isJsonObject()) continue;
					JsonObject entry = q.getAsJsonObject();
					String lot = entry.has("lot") && entry.get("lot").isJsonPrimitive() ? entry.get("lot").getAsString() : null;
					JsonObject spec = lot == null ? null : lots.get(lot);
					boolean gate = entry.has("at") && entry.get("at").isJsonPrimitive() ? "gate".equals(entry.get("at").getAsString())
						: spec != null && spec.has("type") && "gatehouse".equals(spec.get("type").getAsString());
					if (!gate || entry.has("mech") || !fitsMechanism(spec)) continue;
					entry.addProperty("mech", "portcullis");
				}
				continue;
			}
			boolean namesGate = params != null && (params.has("gate_lot") || (params.has("gate") && params.get("gate").isJsonPrimitive()) || (params.has("gates") && params.get("gates").isJsonArray()));
			if (!namesGate || params.has("mechanism") || params.has("gate_mech")) continue;
			params.addProperty("mechanism", "portcullis");
		}
	}

	private static boolean declines(@Nullable JsonElement v) {
		if (v == null || !v.isJsonPrimitive()) return false;
		return v.getAsJsonPrimitive().isBoolean() ? !v.getAsBoolean() : java.util.Set.of("none", "no", "false", "open").contains(v.getAsString().toLowerCase(java.util.Locale.ROOT));
	}

	private static boolean fitsMechanism(@Nullable JsonObject spec) {
		if (spec == null || !spec.has("size") || !spec.get("size").isJsonArray() || spec.getAsJsonArray("size").size() < 2) return false;
		JsonArray size = spec.getAsJsonArray("size");
		return size.get(0).isJsonPrimitive() && size.get(1).isJsonPrimitive() && size.get(0).getAsInt() >= MECH_ALONG && size.get(1).getAsInt() >= MECH_THROUGH;
	}
}
