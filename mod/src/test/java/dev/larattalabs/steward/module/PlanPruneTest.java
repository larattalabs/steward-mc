package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * (slice MODULE-TYPE-3) A plan whose left-out lots took a district or a zone with them keeps no reference to them, one test per kind of reference
 * Architect's checkPlan resolves (kit/lib/plan/schema.mjs validatePlan: refsOf, the ports, dependencies[], a district's near). {@link #unknownRefs}
 * mirrors those rules; each before/after pair is also written to {@code build/plan-prune/} for Architect's own validator (logs/vp.mjs in the slice).
 */
class PlanPruneTest {
	/** The town keeps its cottages (and loses its third farm); the fields hold one farm; the quarters' east district one farm, the west a house. */
	static final String ZONES = """
		"zones":[
		  {"id":"town","required":true,"program":{"lots":[{"id":"cottage_1","type":"cottage","size":[9,9]},{"id":"crop_farm_3","type":"crop_farm","size":[13,14]}]}},
		  {"id":"fields","required":true,"program":{"lots":[{"id":"crop_farm_1","type":"crop_farm","size":[13,14]}]}},
		  {"id":"quarters","program":{"districts":[{"id":"east","size":{"w":48,"d":48},"lots":[{"id":"farm_2","type":"crop_farm","size":[13,14]}]},
		    {"id":"west","size":{"w":48,"d":48},"lots":[{"id":"house_1","type":"house","size":[9,9]}]}]}}]""";

	static final Set<String> LEFT = Set.of("crop_farm_1", "farm_2", "crop_farm_3");

	static JsonObject plan(String rest) {
		return JsonParser.parseString("{\"schemaVersion\":\"plan/0\"," + ZONES + (rest.isEmpty() ? "" : "," + rest) + "}").getAsJsonObject();
	}

	/** The plan as built with {@link #LEFT} left out: checked clean, written for Architect's validator under {@code name}. */
	static PlanPrune.Result built(String name, JsonObject plan) {
		var before = ComposedModules.applied(plan, Map.of(), Set.of());
		assertEquals(plan, before.plan(), "nothing left out: the plan as written");
		var r = ComposedModules.applied(plan, Map.of(), LEFT);
		write(name + ".before", plan);
		write(name + ".after", r.plan());
		assertEquals(List.of(), unknownRefs(r.plan()), name + ": " + r.plan());
		assertEquals(r.plan(), ComposedModules.applied(plan, Map.of(), LEFT).plan(), "deterministic");
		return r;
	}

	static void write(String name, JsonObject plan) {
		try {
			Path dir = Path.of("build", "plan-prune");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(name + ".json"), plan.toString());
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	static List<String> ids(JsonObject plan, String family) {
		List<String> out = new ArrayList<>();
		if (plan.has(family)) for (JsonElement e : plan.getAsJsonArray(family)) out.add(e.getAsJsonObject().get("id").getAsString());
		return out;
	}

	static JsonObject part(JsonObject plan, String family, String id) {
		for (JsonElement e : plan.getAsJsonArray(family)) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
		return null;
	}

	@Test
	void theBaseIsCleanAndLosesTheFieldsAndTheEastDistrict() {
		var r = built("base", plan(""));
		assertEquals(List.of("town", "quarters"), ids(r.plan(), "zones"));
		assertEquals(List.of(), r.dropped());
	}

	// ------------------------------------------------------------------ dependencies[]

	@Test
	void aDependencyOfADroppedZoneGoesAndOneAfterItInheritsItsOrder() {
		var r = built("deps", plan("""
			"connectors":[{"id":"main_road","kind":"road","from":{"anchor":"claim_s"},"to":{"part":"town"}}],
			"forms":[{"id":"green","kind":"pond","where":{"inside":{"claim":true}}}],
			"dependencies":[{"part":"fields","after":["town"]},{"part":"main_road","after":["fields","green"]},{"part":"green","after":["quarters"]}]"""));
		JsonArray deps = r.plan().getAsJsonArray("dependencies");
		assertEquals(2, deps.size(), deps.toString());
		assertEquals("{\"part\":\"main_road\",\"after\":[\"green\"]}", deps.get(0).toString(), "the fields go; after the town it stays, by its own end");
		assertEquals("{\"part\":\"green\",\"after\":[\"quarters\"]}", deps.get(1).toString(), "untouched");
	}

	/** (fix round, GPT P1) An order the plan held through a dropped zone's own locator (R3: implicit) is written out. */
	@Test
	void anOrderThroughADroppedZonesLocatorIsKept() {
		JsonObject p = plan("""
			"forms":[{"id":"spire","kind":"rock_spire","where":{"near":{"anchor":"claim_center"}}}],
			"dependencies":[{"part":"town","after":["fields"]}]""");
		p.getAsJsonArray("zones").get(1).getAsJsonObject().add("where", JsonParser.parseString("{\"near\":{\"feature\":{\"part\":\"spire\"}}}"));
		var r = built("deps-implicit", p);
		assertEquals("[{\"part\":\"town\",\"after\":[\"spire\"]}]", r.plan().getAsJsonArray("dependencies").toString(), "the town stood after the fields, near the spire");
	}

	/** The order is kept through a chain of dropped parts: the quarters after the farm lane, the lane from the fields, the fields by the spire. */
	@Test
	void anOrderThroughTwoDroppedPartsIsKept() {
		JsonObject p = plan("""
			"forms":[{"id":"spire","kind":"rock_spire","where":{"near":{"anchor":"claim_center"}}}],
			"connectors":[{"id":"farm_lane","kind":"path","from":{"part":"fields"},"to":{"anchor":"claim_s"}}],
			"dependencies":[{"part":"quarters","after":["farm_lane"]}]""");
		p.getAsJsonArray("zones").get(1).getAsJsonObject().add("where", JsonParser.parseString("{\"near\":{\"feature\":{\"part\":\"spire\"}}}"));
		var r = built("deps-chain", p);
		assertEquals(List.of("farm_lane"), r.dropped());
		assertEquals("[{\"part\":\"quarters\",\"after\":[\"spire\"]}]", r.plan().getAsJsonArray("dependencies").toString());
	}

	/**
	 * (batch 091 review P2) Architect reads an avoided lot as an order after its zone: the left-out lot's avoid item goes, and while its zone (the town)
	 * is still built that order is written out, so the road still stands after the town (and fails with it).
	 */
	@Test
	void anAvoidedLotsOrderAfterItsSurvivingZoneIsWrittenOut() {
		var r = built("deps-avoid", plan("""
			"connectors":[{"id":"a_road","kind":"road","from":{"anchor":"claim_s"},"to":{"anchor":"claim_center"},"avoid":[{"part":"crop_farm_3"},{"water":{}}]}]"""));
		assertEquals(Set.of("town"), PlanPrune.order(plan("""
			"connectors":[{"id":"a_road","kind":"road","from":{"anchor":"claim_s"},"to":{"anchor":"claim_center"},"avoid":[{"part":"crop_farm_3"}]}]""")).get("a_road"),
			"as written: after the town, through the avoided lot");
		assertEquals("[{\"water\":{}}]", part(r.plan(), "connectors", "a_road").get("avoid").toString(), "the gone lot isn't avoided as its whole zone");
		assertEquals("[{\"part\":\"a_road\",\"after\":[\"town\"]}]", r.plan().getAsJsonArray("dependencies").toString());
		assertEquals(Set.of("town"), PlanPrune.order(r.plan()).get("a_road"), "the road still stands after the town");
	}

	@Test
	void aDependencyOnlyAfterADroppedZoneWithNoOrderOfItsOwnGoes() {
		var r = built("deps-empty", plan("""
			"connectors":[{"id":"main_road","kind":"road","from":{"anchor":"claim_s"},"to":{"part":"town"}}],
			"dependencies":[{"part":"main_road","after":["fields"]}]"""));
		assertEquals(0, r.plan().getAsJsonArray("dependencies").size());
	}

	// ------------------------------------------------------------------ connector endpoints and ports

	@Test
	void aRouteToADroppedZoneGoesAndOneThroughItKeepsItsEnds() {
		var r = built("endpoints", plan("""
			"connectors":[
			  {"id":"farm_lane","kind":"path","from":{"part":"town"},"to":{"part":"fields"}},
			  {"id":"main_road","kind":"road","from":{"anchor":"claim_s"},"via":[{"part":"fields"}],"to":{"part":"town"}},
			  {"id":"lot_lane","kind":"path","from":{"part":"house_1"},"to":{"part":"crop_farm_3"}}]"""));
		assertEquals(List.of("main_road", "lot_lane"), ids(r.plan(), "connectors"));
		assertEquals(List.of("farm_lane"), r.dropped());
		assertFalse(part(r.plan(), "connectors", "main_road").has("via"), "the waypoint goes");
		assertEquals("{\"part\":\"town\"}", part(r.plan(), "connectors", "lot_lane").get("to").toString(), "a left-out lot of a zone still built: the zone");
	}

	@Test
	void aPortNamingADroppedDistrictOrLotIsDeleted() {
		var r = built("ports", plan("""
			"connectors":[
			  {"id":"east_road","kind":"road","from":{"part":"town","port":"crop_farm_3"},"to":{"part":"quarters","port":"east"}},
			  {"id":"west_road","kind":"road","from":{"part":"town","port":"cottage_1"},"to":{"part":"quarters","port":"west"}}]"""));
		assertEquals("{\"part\":\"town\"}", part(r.plan(), "connectors", "east_road").get("from").toString());
		assertEquals("{\"part\":\"quarters\"}", part(r.plan(), "connectors", "east_road").get("to").toString());
		assertEquals("{\"part\":\"quarters\",\"port\":\"west\"}", part(r.plan(), "connectors", "west_road").get("to").toString(), "a port still built stays");
	}

	// ------------------------------------------------------------------ locators

	@Test
	void aLocatorNamingADroppedPlaceLosesTheZonesWhereOrItsFormOrConnector() {
		var r = built("locators", plan("""
			"forms":[
			  {"id":"farm_pond","kind":"pond","where":{"inside":{"part":"fields"}}},
			  {"id":"oak","kind":"giant_tree","where":{"near":{"feature":{"part":"crop_farm_3"}}}},
			  {"id":"terraces","kind":"terrace","where":{"adjacent":{"to":{"part":"fields"}}}}],
			"connectors":[{"id":"fields_wall","kind":"wall","where":{"inside":{"part":"fields"}}},
			  {"id":"town_road","kind":"road","from":{"anchor":"claim_s"},"to":{"part":"town"},"avoid":[{"part":"fields"},{"part":"crop_farm_3"},{"water":{}}]}]"""));
		JsonObject plan = r.plan();
		assertEquals(List.of("oak"), ids(plan, "forms"));
		assertEquals("{\"near\":{\"feature\":{\"part\":\"town\"}}}", part(plan, "forms", "oak").get("where").toString(), "near a lot of a zone still built: the zone");
		assertEquals(List.of("town_road"), ids(plan, "connectors"));
		assertEquals("[{\"water\":{}}]", part(plan, "connectors", "town_road").get("avoid").toString(), "a gone lot isn't avoided as its whole zone");
		assertEquals(List.of("farm_pond", "fields_wall", "terraces"), r.dropped().stream().sorted().toList());
	}

	/** (fix round, GPT P1) A zone placed by a dropped zone is never unplaced: the farm stays, as an ordinary lot, and the town keeps its place. */
	@Test
	void aZonePlacedByADroppedZoneKeepsItAsAnOrdinaryLot() {
		JsonObject p = plan("");
		p.getAsJsonArray("zones").get(0).getAsJsonObject().add("where", JsonParser.parseString("{\"adjacent\":{\"to\":{\"part\":\"fields\"}}}"));
		JsonObject farm = p.getAsJsonArray("zones").get(1).getAsJsonObject().getAsJsonObject("program").getAsJsonArray("lots").get(0).getAsJsonObject();
		farm.add("ext", JsonParser.parseString("{\"" + ComposedModules.EXT + "\":{\"id\":\"crop_farm\"},\"other\":1}"));
		var r = built("zone-where", p);
		assertEquals(List.of("crop_farm_1"), r.kept());
		assertEquals("{\"adjacent\":{\"to\":{\"part\":\"fields\"}}}", part(r.plan(), "zones", "town").get("where").toString());
		JsonObject kept = part(r.plan(), "zones", "fields").getAsJsonObject("program").getAsJsonArray("lots").get(0).getAsJsonObject();
		assertFalse(ComposedModules.marked(kept), "an ordinary lot now: " + kept);
		assertEquals("{\"other\":1}", kept.get("ext").toString());
		assertEquals(List.of("town", "quarters", "fields").stream().sorted().toList(), ids(r.plan(), "zones").stream().sorted().toList());
		assertEquals(1, part(r.plan(), "zones", "town").getAsJsonObject("program").getAsJsonArray("lots").size(), "the town's third farm still goes");
	}

	/** (fix round, GPT P1) A town hanging from a form placed by the dropped fields: the form isn't dropped from under it; the farm stays instead. */
	@Test
	void aFormAZoneIsPlacedByIsNeverDroppedFromUnderIt() {
		JsonObject p = plan("""
			"forms":[{"id":"isle","kind":"floating_island","where":{"near":{"feature":{"part":"fields"}}}}]""");
		p.getAsJsonArray("zones").get(0).getAsJsonObject().add("where", JsonParser.parseString("{\"inside\":{\"part\":\"isle\"}}"));
		var r = built("isle", p);
		assertEquals(List.of("isle"), ids(r.plan(), "forms"));
		assertEquals(List.of("crop_farm_1"), r.kept());
		assertEquals(List.of(), r.dropped());
		assertTrue(part(r.plan(), "zones", "town").has("where"));
	}

	/** (fix round, GPT P2) Orders that would need more than Architect's 128 dependencies: the farm stays instead. */
	@Test
	void anOrderPastTheDependencyLimitKeepsTheFarm() {
		StringBuilder c = new StringBuilder("\"connectors\":[");
		for (int i = 0; i < 60; i++) c.append(i == 0 ? "" : ",").append("{\"id\":\"road_").append(i).append("\",\"kind\":\"road\",\"from\":{\"anchor\":\"claim_s\"},\"via\":[{\"part\":\"fields\"}],\"to\":{\"part\":\"quarters\"}}");
		c.append("],\"rules\":[");
		for (int i = 0; i < 30; i++) c.append(i == 0 ? "" : ",").append("{\"id\":\"oak_").append(i).append("\",\"kind\":\"palette\",\"family\":\"oak\",\"scope\":[\"fields\",\"quarters\"]}");
		c.append("],\"forms\":[{\"id\":\"spire\",\"kind\":\"rock_spire\",\"where\":{\"near\":{\"anchor\":\"claim_center\"}}}");
		for (int i = 0; i < 30; i++) c.append(",{\"id\":\"blend_").append(i).append("\",\"kind\":\"relief\",\"around\":[\"fields\",\"quarters\"]}");
		c.append("],\"dependencies\":[{\"part\":\"quarters\",\"after\":[\"spire\"]}");
		// 124 parts (Architect's cap is 128): 120 would each be written after the spire, past 128 entries with these 10
		for (int i = 0; i < 9; i++) c.append(",{\"part\":\"oak_").append(i).append("\",\"after\":[\"town\"]}");
		c.append("]");
		JsonObject p = plan(c.toString());
		p.getAsJsonArray("zones").get(1).getAsJsonObject().add("where", JsonParser.parseString("{\"near\":{\"feature\":{\"part\":\"spire\"}}}"));
		var r = built("deps-limit", p);
		assertEquals(List.of("crop_farm_1"), r.kept());
		assertTrue(r.plan().getAsJsonArray("dependencies").size() <= 128);
	}

	// ------------------------------------------------------------------ a form's around, a rule's scope, a hidden room's host

	@Test
	void aFormAroundADroppedZoneLosesItOrGoes() {
		var r = built("around", plan("""
			"forms":[{"id":"blend","kind":"relief","around":["fields","town"]},{"id":"farm_blend","kind":"relief","around":["fields"]},
			  {"id":"cellar","kind":"hidden_room","host":"crop_farm_1"}]"""));
		assertEquals(List.of("blend"), ids(r.plan(), "forms"));
		assertEquals("[\"town\"]", part(r.plan(), "forms", "blend").get("around").toString());
		assertEquals(List.of("farm_blend", "cellar"), r.dropped());
	}

	@Test
	void aRuleScopedToADroppedZoneLosesItOrGoesNeverBindingTheWholePlan() {
		var r = built("scope", plan("""
			"rules":[{"id":"oak_town","kind":"palette","family":"oak","scope":["fields","town"]},{"id":"oak_fields","kind":"palette","family":"oak","scope":["fields"]},
			  {"id":"oak_all","kind":"palette","family":"oak"}]"""));
		assertEquals(List.of("oak_town", "oak_all"), ids(r.plan(), "rules"));
		assertEquals("[\"town\"]", part(r.plan(), "rules", "oak_town").get("scope").toString());
		assertFalse(part(r.plan(), "rules", "oak_all").has("scope"), "a rule over the whole plan stays one");
	}

	// ------------------------------------------------------------------ districts' near

	@Test
	void aDistrictNearADroppedDistrictLosesThatPreference() {
		JsonObject p = plan("");
		JsonObject west = p.getAsJsonArray("zones").get(2).getAsJsonObject().getAsJsonObject("program").getAsJsonArray("districts").get(1).getAsJsonObject();
		west.add("near", JsonParser.parseString("[\"east\"]"));
		var r = built("near", p);
		JsonObject kept = part(r.plan(), "zones", "quarters").getAsJsonObject("program").getAsJsonArray("districts").get(0).getAsJsonObject();
		assertEquals("west", kept.get("id").getAsString());
		assertFalse(kept.has("near"));
	}

	// ------------------------------------------------------------------ cascades

	@Test
	void aPartNamingADroppedPartGoesInTurn() {
		var r = built("cascade", plan("""
			"connectors":[{"id":"farm_lane","kind":"path","from":{"part":"town"},"to":{"part":"fields"}},
			  {"id":"farm_wall","kind":"wall","where":{"inside":{"part":"fields"}}},
			  {"id":"lane_gate","kind":"gate","crossing":"farm_lane"},{"id":"wall_gate","kind":"gate","at":{"part":"town"}}],
			"rules":[{"id":"lane_oak","kind":"palette","family":"oak","scope":["farm_lane"]}],
			"dependencies":[{"part":"quarters","after":["farm_lane"]}]"""));
		assertEquals(List.of(), ids(r.plan(), "connectors"), "the lane, its gate, the plan's one wall and the gate that stood in it");
		assertEquals(List.of(), ids(r.plan(), "rules"));
		assertEquals("[{\"part\":\"quarters\",\"after\":[\"town\"]}]", r.plan().getAsJsonArray("dependencies").toString(), "after the lane: after where the lane began");
	}

	/** A gate on a dropped crossing or in a dropped wall goes while another wall stands; a route to a form that went (written before it) goes too. */
	@Test
	void aGateOrRouteNamingAPartDroppedLaterInThePlanGoes() {
		var r = built("cascade-order", plan("""
			"connectors":[{"id":"pond_path","kind":"path","from":{"part":"town"},"to":{"part":"farm_pond"}},
			  {"id":"town_wall","kind":"wall","where":{"inside":{"claim":true}}},{"id":"farm_wall","kind":"wall","where":{"inside":{"part":"fields"}}},
			  {"id":"farm_lane","kind":"path","from":{"part":"town"},"to":{"part":"fields"}},
			  {"id":"lane_gate","kind":"gate","wall":"town_wall","crossing":"farm_lane"},{"id":"farm_gate","kind":"gate","wall":"farm_wall"},
			  {"id":"town_gate","kind":"gate","wall":"town_wall","at":{"part":"town"}}],
			"forms":[{"id":"farm_pond","kind":"pond","where":{"inside":{"part":"fields"}}}]"""));
		assertEquals(List.of("town_wall", "town_gate"), ids(r.plan(), "connectors"));
		assertEquals(List.of(), ids(r.plan(), "forms"));
	}

	@Test
	void aPlanNamingNothingLeftOutComesOutAsBuiltBefore() {
		JsonObject p = plan("""
			"connectors":[{"id":"main_road","kind":"road","from":{"anchor":"claim_s"},"to":{"part":"town","port":"cottage_1"}}],
			"rules":[{"id":"oak_town","kind":"palette","family":"oak","scope":["town"]}],
			"dependencies":[{"part":"main_road","after":["town"]}]""");
		var r = built("untouched", p);
		assertEquals(List.of(), r.dropped());
		JsonObject expected = p.deepCopy();
		expected.getAsJsonArray("zones").remove(1);
		expected.getAsJsonArray("zones").get(0).getAsJsonObject().getAsJsonObject("program").getAsJsonArray("lots").remove(1);
		expected.getAsJsonArray("zones").get(1).getAsJsonObject().getAsJsonObject("program").getAsJsonArray("districts").remove(0);
		assertEquals(expected, r.plan());
	}

	// ------------------------------------------------------------------ the references Architect's checkPlan resolves (a mirror of refsOf and its kin)

	/** Every reference of {@code plan} to an id it doesn't hold, and every empty lot list, as Architect's check would refuse them. */
	static List<String> unknownRefs(JsonObject plan) {
		List<String> bad = new ArrayList<>();
		Set<String> parts = new HashSet<>();
		Map<String, String> lotZone = new HashMap<>();
		Map<String, Set<String>> ports = new HashMap<>();
		Map<String, String> kind = new HashMap<>();
		for (String f : PlanPrune.FAMILIES) if (plan.has(f)) for (JsonElement e : plan.getAsJsonArray(f)) {
			JsonObject p = e.getAsJsonObject();
			String id = p.get("id").getAsString();
			parts.add(id);
			kind.put(id, p.has("kind") ? p.get("kind").getAsString() : "");
		}
		if (plan.has("zones")) for (JsonElement e : plan.getAsJsonArray("zones")) {
			JsonObject z = e.getAsJsonObject();
			String id = z.get("id").getAsString();
			Set<String> zp = new HashSet<>(Set.of("bell", "start", "end"));
			JsonObject program = z.getAsJsonObject("program");
			List<JsonArray> lots = new ArrayList<>();
			if (program.has("districts")) {
				Set<String> ds = new HashSet<>();
				for (JsonElement d : program.getAsJsonArray("districts")) ds.add(d.getAsJsonObject().get("id").getAsString());
				for (JsonElement d : program.getAsJsonArray("districts")) {
					lots.add(d.getAsJsonObject().getAsJsonArray("lots"));
					zp.add(d.getAsJsonObject().get("id").getAsString());
					if (d.getAsJsonObject().has("near")) for (JsonElement n : d.getAsJsonObject().getAsJsonArray("near"))
						if (!ds.contains(n.getAsString())) bad.add("near " + n.getAsString());
				}
			} else if (program.has("lots")) lots.add(program.getAsJsonArray("lots"));
			else if (program.has("params") && program.getAsJsonObject("params").has("lots")) lots.add(program.getAsJsonObject("params").getAsJsonArray("lots"));
			for (JsonArray ls : lots) {
				if (ls.isEmpty()) bad.add("lots [] in " + id);
				for (JsonElement l : ls) {
					lotZone.put(l.getAsJsonObject().get("id").getAsString(), id);
					zp.add(l.getAsJsonObject().get("id").getAsString());
				}
			}
			ports.put(id, zp);
		}
		java.util.function.BiConsumer<String, Boolean> place = (id, lotsOk) -> {
			if (id != null && !parts.contains(id) && !(lotsOk && lotZone.containsKey(id))) bad.add("unknown " + id);
		};
		java.util.function.Consumer<JsonObject> feature = f -> { if (f != null && f.has("part")) place.accept(f.get("part").getAsString(), true); };
		java.util.function.Consumer<JsonObject> endpoint = e -> {
			if (e == null) return;
			if (e.has("part")) {
				String id = e.get("part").getAsString();
				place.accept(id, true);
				if (e.has("port") && (!ports.containsKey(id) || !ports.get(id).contains(e.get("port").getAsString()))) bad.add("port " + id + "." + e.get("port").getAsString());
			}
			if (e.has("feature")) feature.accept(e.getAsJsonObject("feature"));
		};
		for (String f : PlanPrune.FAMILIES) if (plan.has(f)) for (JsonElement el : plan.getAsJsonArray(f)) {
			JsonObject p = el.getAsJsonObject();
			if (p.has("where")) {
				JsonObject w = p.getAsJsonObject("where");
				if (w.has("inside")) feature.accept(w.getAsJsonObject("inside"));
				if (w.has("adjacent")) feature.accept(w.getAsJsonObject("adjacent").getAsJsonObject("to"));
				for (String k : List.of("near", "along", "around")) if (w.has(k) && w.getAsJsonObject(k).has("feature")) feature.accept(w.getAsJsonObject(k).getAsJsonObject("feature"));
			}
			if (f.equals("connectors") || p.has("from") || p.has("to")) {
				for (String k : List.of("from", "to", "at")) if (p.has(k)) endpoint.accept(p.getAsJsonObject(k));
				if (p.has("via")) for (JsonElement v : p.getAsJsonArray("via")) endpoint.accept(v.getAsJsonObject());
				if (p.has("avoid")) for (JsonElement v : p.getAsJsonArray("avoid")) feature.accept(v.getAsJsonObject());
				if ("gate".equals(kind.get(p.get("id").getAsString()))) {
					for (String k : List.of("wall", "crossing")) if (p.has(k)) place.accept(p.get(k).getAsString(), false);
					// a gate with no wall stands in the plan's one wall (NO_FEATURE when there is none)
					if (!p.has("wall") && kind.values().stream().noneMatch("wall"::equals)) bad.add("gate with no wall " + p.get("id").getAsString());
				}
			}
			if (f.equals("forms") && p.has("host")) place.accept(p.get("host").getAsString(), true);
			if (f.equals("forms") && p.has("around") && p.get("around").isJsonArray()) for (JsonElement a : p.getAsJsonArray("around")) place.accept(a.getAsString(), false);
			if (f.equals("rules") && p.has("scope") && p.get("scope").isJsonArray()) for (JsonElement a : p.getAsJsonArray("scope")) place.accept(a.getAsString(), false);
		}
		if (plan.has("dependencies") && plan.getAsJsonArray("dependencies").size() > 128) bad.add("more than 128 dependencies");
		if (plan.has("dependencies")) for (JsonElement el : plan.getAsJsonArray("dependencies")) {
			if (el.getAsJsonObject().getAsJsonArray("after").size() > 16) bad.add("more than 16 afters");
			JsonObject d = el.getAsJsonObject();
			place.accept(d.get("part").getAsString(), false);
			if (d.getAsJsonArray("after").isEmpty()) bad.add("after [] of " + d.get("part").getAsString());
			for (JsonElement a : d.getAsJsonArray("after")) place.accept(a.getAsString(), false);
		}
		return bad;
	}

	/** The mirror catches what each test removes (it is the oracle the tests lean on). */
	@Test
	void theMirrorRefusesEachKindOfDanglingReference() {
		for (String rest : List.of(
			"\"dependencies\":[{\"part\":\"gone\",\"after\":[\"town\"]}]",
			"\"connectors\":[{\"id\":\"r\",\"kind\":\"road\",\"from\":{\"part\":\"town\",\"port\":\"gone\"},\"to\":{\"anchor\":\"claim_s\"}}]",
			"\"connectors\":[{\"id\":\"r\",\"kind\":\"road\",\"from\":{\"part\":\"gone\"},\"to\":{\"anchor\":\"claim_s\"}}]",
			"\"forms\":[{\"id\":\"p\",\"kind\":\"pond\",\"where\":{\"inside\":{\"part\":\"gone\"}}}]",
			"\"forms\":[{\"id\":\"p\",\"kind\":\"relief\",\"around\":[\"crop_farm_3\"]}]",
			"\"rules\":[{\"id\":\"p\",\"kind\":\"palette\",\"scope\":[\"gone\"]}]",
			"\"connectors\":[{\"id\":\"g\",\"kind\":\"gate\",\"at\":{\"part\":\"town\"}}]"))
			assertFalse(unknownRefs(plan(rest)).isEmpty(), rest);
		JsonObject p = plan("");
		p.getAsJsonArray("zones").get(2).getAsJsonObject().getAsJsonObject("program").getAsJsonArray("districts").get(1).getAsJsonObject()
			.add("near", JsonParser.parseString("[\"gone\"]"));
		assertFalse(unknownRefs(p).isEmpty());
		assertEquals(List.of(), unknownRefs(plan("")));
	}
}
