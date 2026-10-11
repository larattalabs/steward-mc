package dev.larattalabs.steward.service;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.LoadPolicy;
import dev.larattalabs.architect.api.Mode;
import dev.larattalabs.architect.api.RealiseRequest;
import dev.larattalabs.architect.api.RegionPlanRequest;
import dev.larattalabs.architect.api.RemoveOptions;
import dev.larattalabs.steward.gateway.ProgramPlanner;
import dev.larattalabs.steward.model.ClaimRules;
import dev.larattalabs.steward.model.Settlement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Dev only (a spike, 2026-10-11): what Architect's flat village (slice V, {@code Regions.plan("flat_village")}) makes of a settlement's card, and what a
 * realised one leaves in the world, so Steward's adoption is designed on facts. Plans, realises every lot with a test entry, and dumps the plan, the region
 * and the owner's sites to {@code <gameDir>/steward-v-spike.json}. $0: no design job. Server thread.
 */
public final class VSpike {
	private static String lastPlan;
	private static String lastRegion;
	private static final JsonObject DUMP = new JsonObject();

	private VSpike() {
	}

	public static void plan(ServerPlayer p, Settlement s, int buildings, Consumer<String> say) {
		var c = s.card();
		JsonArray lots = new JsonArray();
		for (var l : ProgramPlanner.lots(c, buildings, 1).specs()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", l.id());
			o.addProperty("type", l.type());
			o.addProperty("role", l.role());
			JsonArray size = new JsonArray();
			size.add(l.sizeX());
			size.add(l.sizeZ());
			o.add("size", size);
			o.addProperty("landmark", l.landmark());
			if (l.placement() != null) o.addProperty("placement", l.placement());
			if (l.notes() != null) o.addProperty("brief", l.notes());
			lots.add(o);
		}
		JsonObject params = new JsonObject();
		params.add("lots", lots);
		int r = Math.min(ClaimRules.SURVEY_RADIUS, s.claim().radius());
		var level = p.level();
		BoundingBox claim = new BoundingBox(s.claim().centerX() - r, level.getMinY(), s.claim().centerZ() - r, s.claim().centerX() + r, level.getMaxY(), s.claim().centerZ() + r);
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		DUMP.add("params", params);
		say.accept("v_spike: planning " + lots.size() + " lots over " + claim);
		ArchitectApi.get().regions().plan(new RegionPlanRequest("flat_village", params, level, claim, null, null, null, LoadPolicy.LOADED_ONLY, s.owner(), ext))
			.whenComplete((plan, err) -> p.level().getServer().execute(() -> {
				if (err != null) { say.accept("v_spike plan failed: " + err); return; }
				lastPlan = plan.planId();
				JsonObject o = new JsonObject();
				o.addProperty("planId", plan.planId());
				o.add("stages", new GsonBuilder().create().toJsonTree(plan.stages()));
				o.add("notes", new GsonBuilder().create().toJsonTree(plan.notes()));
				JsonArray pl = new JsonArray();
				for (var l : plan.lots()) {
					JsonObject x = new JsonObject();
					x.addProperty("id", l.id());
					x.addProperty("stage", l.stage());
					x.addProperty("box", String.valueOf(l.lot()));
					x.addProperty("floorY", l.floorY());
					x.addProperty("front", String.valueOf(l.front()));
					x.add("ext", l.ext());
					pl.add(x);
				}
				o.add("lots", pl);
				o.add("anchors", new GsonBuilder().create().toJsonTree(plan.anchors().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toShortString()))));
				DUMP.add("plan", o);
				write(p);
				say.accept("v_spike: plan " + plan.planId() + ", " + plan.lots().size() + " lots placed, stages " + plan.stages());
			}));
	}

	public static void realise(ServerPlayer p, Settlement s, String entry, Consumer<String> say) {
		if (lastPlan == null) { say.accept("v_spike: plan first"); return; }
		var plan = DUMP.getAsJsonObject("plan");
		Map<String, String> lots = new HashMap<>();
		for (var l : plan.getAsJsonArray("lots")) lots.put(l.getAsJsonObject().get("id").getAsString(), entry);
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:build", "spike");
		ArchitectApi.get().regions().realise(new RealiseRequest(lastPlan, Mode.INSTANT, p, lots, LoadPolicy.LOADED_ONLY, true, null, false, ext))
			.whenComplete((id, err) -> p.level().getServer().execute(() -> {
				if (err != null) { say.accept("v_spike realise failed: " + err); return; }
				lastRegion = id;
				say.accept("v_spike: realising region " + id);
			}));
	}

	/** Takes up every region a spike realised, in any settlement (the e2e world must not keep them: their lots show as updates in its inbox). */
	public static void removeAll(ServerPlayer p, Consumer<String> say) {
		var server = p.level().getServer();
		int n = 0;
		for (Settlement s : Settlements.store().all()) {
			for (var v : ArchitectApi.get().regions().list(s.owner())) {
				if (v.ext() == null || !v.ext().has("steward_mc:build") || !"spike".equals(v.ext().get("steward_mc:build").getAsString())) continue;
				n++;
				String id = v.id();
				ArchitectApi.get().regions().remove(id, new RemoveOptions(false, s.owner())).whenComplete((r, err) -> server.execute(() ->
					say.accept("v_spike: " + id + (err == null ? " removed" : " not removed: " + err))));
			}
		}
		say.accept("v_spike: removing " + n + " spike region(s)");
	}

	public static void dump(ServerPlayer p, Settlement s, Consumer<String> say) {
		var server = p.level().getServer();
		var gson = new GsonBuilder().create();
		if (lastRegion != null) {
			ArchitectApi.get().regions().get(lastRegion).ifPresent(v -> {
				JsonObject o = new JsonObject();
				o.addProperty("id", v.id());
				o.addProperty("groupId", v.groupId());
				o.addProperty("state", String.valueOf(v.state()));
				o.add("ext", v.ext());
				o.addProperty("lots", String.valueOf(v.lots()));
				o.addProperty("stages", String.valueOf(v.stages()));
				DUMP.add("region", o);
			});
		}
		JsonArray sites = new JsonArray();
		for (var v : ArchitectApi.get().sites(server).list(s.owner())) {
			JsonObject x = new JsonObject();
			x.addProperty("id", v.id());
			x.addProperty("kind", v.kind());
			x.addProperty("itemKey", v.itemKey());
			x.addProperty("blueprint", v.blueprintId());
			x.addProperty("group", v.group());
			x.addProperty("region", v.region());
			x.addProperty("box", String.valueOf(v.box()));
			x.add("ext", v.ext());
			sites.add(x);
		}
		DUMP.add("sites", sites);
		DUMP.add("regions", gson.toJsonTree(ArchitectApi.get().regions().list(s.owner()).stream().map(v -> v.id() + " " + v.state() + " group " + v.groupId()).toList()));
		write(p);
		say.accept("v_spike: dumped " + sites.size() + " sites");
	}

	private static void write(ServerPlayer p) {
		try {
			Path f = p.level().getServer().getServerDirectory().resolve("steward-v-spike.json");
			Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(DUMP), StandardCharsets.UTF_8);
		} catch (Exception e) {
			dev.larattalabs.steward.Steward.LOGGER.warn("v_spike dump failed", e);
		}
	}
}
