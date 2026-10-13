package dev.larattalabs.steward.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ApprovedRealise;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.Mode;
import dev.larattalabs.architect.api.PlanCheck;
import dev.larattalabs.architect.api.PlanRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Dev (the e2e check; docs/COMPOSITION.md): Architect's plan check (P0, API 1.23) on a plan/0 plan round where the player stands, and the realising of
 * its approved artifact with the e2e's test design on every lot. Plan v0's real path (describe, compose, check, repair, approve) builds on the same calls.
 * Server thread.
 */
public final class PlanChecks {
	/** The claim a dev check covers: this far each way round the player. */
	static final int RADIUS = 64;
	public static final String OWNER = "steward_mc:dev";
	private static final Map<String, Checked> LAST = new HashMap<>();
	private static final Map<String, String> REALISED = new HashMap<>();

	private record Checked(JsonObject plan, PlanCheck check) {}

	private PlanChecks() {
	}

	/** Checks {@code plan}; completes with a summary as JSON (overall, the classes counted, each part's answer, the artifact sha). */
	public static CompletableFuture<JsonObject> check(ServerPlayer p, String label, JsonObject plan) {
		var level = p.level();
		BlockPos at = p.blockPosition();
		var claim = new BoundingBox(at.getX() - RADIUS, level.getMinY(), at.getZ() - RADIUS, at.getX() + RADIUS, level.getMaxY(), at.getZ() + RADIUS);
		var req = new PlanRequest(plan, level, claim, Map.of("stone", at, "player", at), OWNER, null, null, null, null, new JsonObject());
		return ArchitectApi.get().regions().checkPlan(req).thenApply(c -> {
			LAST.put(label, new Checked(plan, c));
			var o = new JsonObject();
			o.addProperty("overall", c.overall().name());
			o.addProperty("artifact", c.artifactSha());
			Map<String, Integer> classes = new LinkedHashMap<>();
			var parts = new JsonArray();
			for (JsonObject part : c.parts()) {
				parts.add(part);
				String cls = part.has("answer") ? part.get("answer").getAsString() : part.has("class") ? part.get("class").getAsString() : "?";
				classes.merge(cls, 1, Integer::sum);
			}
			var counted = new JsonObject();
			classes.forEach(counted::addProperty);
			o.add("classes", counted);
			o.add("parts", parts);
			return o;
		});
	}

	/** Realises the approved artifact of the last check under {@code label}, every lot with library entry {@code entry}; completes with the region id. */
	public static CompletableFuture<String> realise(ServerPlayer p, String label, String entry) {
		var last = LAST.get(label);
		if (last == null || last.check().artifactSha() == null) return CompletableFuture.failedFuture(new IllegalStateException("no approved artifact under " + label));
		Map<String, String> lots = new LinkedHashMap<>();
		for (var z : last.plan().getAsJsonArray("zones")) {
			var prog = z.getAsJsonObject().getAsJsonObject("program");
			var list = prog.has("lots") ? prog.getAsJsonArray("lots") : prog.has("params") && prog.getAsJsonObject("params").has("lots") ? prog.getAsJsonObject("params").getAsJsonArray("lots") : new JsonArray();
			for (var l : list) lots.put(l.getAsJsonObject().get("id").getAsString(), entry);
		}
		var ext = new JsonObject();
		ext.addProperty("steward_mc:dev", label);
		return ArchitectApi.get().regions().realiseApproved(new ApprovedRealise(last.check().artifactSha(), Mode.INSTANT, p, lots, "steward.dev.plan." + label, ext))
			.thenApply(id -> {
				REALISED.put(label, id);
				return id;
			});
	}

	/** Takes up what {@link #realise} placed under {@code label}; completes with whether it was removed. */
	public static CompletableFuture<Boolean> unrealise(String label) {
		String id = REALISED.remove(label);
		if (id == null) return CompletableFuture.failedFuture(new IllegalStateException("nothing realised under " + label));
		return ArchitectApi.get().regions().remove(id, new dev.larattalabs.architect.api.RemoveOptions(false, OWNER)).thenApply(r -> r.removed());
	}
}
