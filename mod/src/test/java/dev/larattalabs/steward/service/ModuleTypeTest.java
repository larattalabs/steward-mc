package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.DistrictSite;
import dev.larattalabs.steward.model.Proposal;
import dev.larattalabs.steward.model.ProposalRules;
import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.model.TalkAnswer;
import dev.larattalabs.steward.module.ModuleKind;
import dev.larattalabs.steward.module.ModuleTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * (slice MODULE-TYPE; Noah 2026-11-20, "Never as plain buildings") Additions, districts, quarters and talk never propose a catalog module's type as an
 * ordinary paid building: the module is offered through its own path when the town qualifies, else it is left out and said. An old save's pending one
 * loads, shows no price, is withdrawn (not declined) and is refused at Accept before anything is surveyed, accepted or spent.
 */
class ModuleTypeTest {
	static final Map<String, String> TYPES = ModuleTypes.KNOWN;
	static final Path PLAIN_SAVE = Path.of(System.getProperty("user.dir")).resolve("../fixtures/saves/v7/plain-module-proposals.json").normalize();

	@AfterEach
	void reset() {
		SettlementsTestAccess.reset();
	}

	static ConceptCard.Building b(String role, String type, boolean landmark) {
		return new ConceptCard.Building(role, type, 1, "M", landmark, null);
	}

	@Test
	void everyCatalogModuleTypeIsAModulesAndTheDecorativeFarmIsNot() {
		assertEquals("crop_farm", ModuleTypes.moduleOf("crop_farm", TYPES));
		assertEquals("storage_hall", ModuleTypes.moduleOf("storage_hall", TYPES));
		assertEquals("storage_hall", ModuleTypes.moduleOf("Storage Hall", TYPES), "read as a slug");
		assertNull(ModuleTypes.moduleOf("farm", TYPES), "the decorative farm is its own type (MODULES.md 4.1's priced substitute)");
		assertNull(ModuleTypes.moduleOf("barn", TYPES));
		// whatever the catalog lists is a module's type too, and the kinds Steward knows stay one with an empty catalog (failing closed)
		var live = ModuleTypes.of(List.of(), List.of("windmill"));
		assertEquals("windmill", ModuleTypes.moduleOf("windmill", live));
		assertEquals("crop_farm", ModuleTypes.moduleOf("crop_farm", live));
		// a module offer's own lot is of its type: never a plain proposal
		var offer = Proposal.module("module.crop_farm", "A field", "", b("crop farm", "crop_farm", false), 1, "seeds",
			new dev.larattalabs.steward.module.ModuleOrder("crop_farm", 1, "c".repeat(64), JsonParser.parseString("{\"crop\":\"wheat\",\"width\":9,\"depth\":9}").getAsJsonObject(), null, null, false, false));
		assertFalse(ModuleTypes.plain(offer, TYPES));
		assertTrue(ModuleTypes.plain(new Proposal("asked_x_crop_farm", "Crop farm", "", b("crop farm", "crop_farm", false), 1, "asked"), TYPES));
	}

	@Test
	void noProposalRuleIsAModulesType() {
		for (String key : List.of("farm", "orchard", "pens", "tower", "gatehouse", "vault", "smithy", "workshop")) {
			var p = ProposalRules.find(key);
			assertNotNull(p, key);
			assertFalse(ModuleTypes.plain(p, TYPES), key + " is an ordinary building's rule");
		}
	}

	@Test
	void talkAsksAModulesTypeOfTheModuleEvenWhenTheModelNamedNone() {
		var plain = TalkAnswer.parse(JsonParser.parseString("""
			{"reply": "A storage hall it is.", "intent": "building", "building": {"role": "storage hall", "type": "storage_hall", "footprint": "L", "landmark": false}}""").getAsJsonObject());
		assertNull(plain.module(), "the model named no module");
		var m = Talk.moduleAsked(plain, TYPES);
		assertNotNull(m, "converted after parsing: the module is asked for");
		assertEquals("storage_hall", m.id());
		assertEquals(0, m.params().size());
		var farm = TalkAnswer.parse(JsonParser.parseString("""
			{"reply": "A farm it is.", "intent": "building", "building": {"role": "farm", "type": "farm", "footprint": "L", "landmark": false},
			 "module": {"id": "crop_farm", "params": {"crop": "wheat"}}}""").getAsJsonObject());
		assertEquals("crop_farm", Talk.moduleAsked(farm, TYPES).id(), "the module the answer named");
		var house = TalkAnswer.parse(JsonParser.parseString("""
			{"reply": "A house.", "intent": "building", "building": {"role": "house", "type": "house", "footprint": "S", "landmark": false}}""").getAsJsonObject());
		assertNull(Talk.moduleAsked(house, TYPES));
	}

	@Test
	void talkLeavesAModuleTypeOutAndSaysWhyRatherThanPricingIt() {
		String said = Talk.plainLeftOut(b("storage hall", "storage_hall", false), "one stands already", TYPES);
		assertEquals("A working storage hall can't go here: one stands already. I don't put a storage hall in the inbox as an ordinary building: a working storage hall"
			+ " comes from the catalog as a module, offered on its own when the town qualifies; I never design one as an ordinary building.", said);
		assertFalse(said.contains("$"), "nothing is priced");
		assertNotNull(Talk.plainLeftOut(b("crop farm", "crop_farm", false), null, TYPES));
		assertNull(Talk.plainLeftOut(b("farm", "farm", false), "one stands already", TYPES), "the decorative farm stays its own priced proposal");
	}

	@Test
	void aQuarterIsNeverMadeOfAModulesType() throws Exception {
		var card = StreetModulesTest.cardWith("""
			[{"role": "storage hall", "type": "storage_hall", "count": 1, "footprint": "XL", "landmark": false},
			 {"role": "crop farm", "type": "crop_farm", "count": 1, "footprint": "L", "landmark": false},
			 {"role": "chapel", "type": "chapel", "count": 1, "footprint": "M", "landmark": true},
			 {"role": "townhouses", "type": "house", "count": 4, "footprint": "S", "landmark": false}]""");
		assertEquals("house", Proposals.quarterBuilding(card, TYPES).type());
		var landmarkOnly = StreetModulesTest.cardWith("""
			[{"role": "storage hall", "type": "storage_hall", "count": 1, "footprint": "XL", "landmark": false},
			 {"role": "chapel", "type": "chapel", "count": 1, "footprint": "M", "landmark": true}]""");
		assertEquals("chapel", Proposals.quarterBuilding(landmarkOnly, TYPES).type(), "an ordinary landmark before a module");
		var modulesOnly = StreetModulesTest.cardWith("""
			[{"role": "storage hall", "type": "storage_hall", "count": 1, "footprint": "XL", "landmark": false},
			 {"role": "crop farm", "type": "crop_farm", "count": 1, "footprint": "L", "landmark": false}]""");
		assertNull(Proposals.quarterBuilding(modulesOnly, TYPES), "no quarter of modules");
	}

	@Test
	void aLeftOutDistrictNeverNamesAModulesTypeAndSaysWhy() {
		var c = new DistrictSite.Choice("E", "");
		var left = List.of(b("storage hall", "storage_hall", false), b("smithy", "smithy", false));
		var split = ModuleTypes.split(left, TYPES);
		assertEquals(List.of("smithy"), split.ordinary().stream().map(ConceptCard.Building::role).toList());
		var d = Proposals.leftOutDistrict(c, split.ordinary(), split.modules(), TYPES, 1);
		assertEquals("A district for the smithy (east)", d.title());
		assertTrue(d.why().endsWith("It leaves out the storage hall: a working storage hall comes from the catalog as a module, offered on its own when the town"
			+ " qualifies; I never design one as an ordinary building."), d.why());
		// a caller that didn't split: split here all the same
		var unsplit = Proposals.leftOutDistrict(c, left, List.of(), TYPES, 1);
		assertEquals("A district for the smithy (east)", unsplit.title());
		assertTrue(unsplit.why().contains("It leaves out the storage hall"), unsplit.why());
		assertNull(Proposals.leftOutDistrict(c, List.of(b("crop farm", "crop_farm", false)), List.of(), TYPES, 1), "no district for modules alone");
	}

	/** The old save: a talk ask of a storage hall and a quarter of crop farms waiting as ordinary buildings, a decorative farm, an accepted plain key. */
	static SettlementStore oldSave() throws Exception {
		var file = JsonParser.parseString(Files.readString(PLAIN_SAVE)).getAsJsonObject();
		assertEquals(7, file.get("format").getAsInt(), "a format-7 file, as saved before this slice");
		return SettlementStore.fromJson(file.toString());
	}

	@Test
	void anOldSavesPlainModuleProposalsLoadShowNoPriceAndAreWithdrawn() throws Exception {
		var store = oldSave();
		var ps = store.get("set_2").orElseThrow().proposals();
		assertEquals(List.of("asked_mhx0a1_storage_hall", "quarter_along_E", "farm"), ps.open().stream().map(Proposal::key).toList(), "all three load");
		assertEquals(List.of("module.crop_farm", "asked_mhwz11_storage_hall"), ps.accepted(), "an accepted plain key loads as it was");
		assertTrue(ps.open().get(1).foundsQuarter());
		// the inbox: no price on the two plain ones (they aren't built), the farm priced as ever
		SettlementsTestAccess.use(store);
		var rows = Proposals.inboxEntries().stream().filter(e -> e.settlementId().equals("set_2")).findFirst().orElseThrow().lots();
		var hall = rows.stream().filter(l -> l.id().equals("asked_mhx0a1_storage_hall")).findFirst().orElseThrow();
		assertEquals("Storage hall isn't built: a working storage hall comes from the catalog as a module, offered on its own when the town qualifies; I never"
			+ " design one as an ordinary building. It leaves the inbox (not declined).", hall.detail());
		var quarter = rows.stream().filter(l -> l.id().equals("quarter_along_E")).findFirst().orElseThrow();
		assertFalse(quarter.detail().contains("$"), quarter.detail());
		assertTrue(rows.stream().filter(l -> l.id().equals("farm")).findFirst().orElseThrow().detail().startsWith("about $"));
		// the steward's pass: both withdrawn, not declined; the farm and the accepted keys stay
		var swept = Proposals.withoutPlainModules(ps, TYPES);
		assertEquals(List.of("asked_mhx0a1_storage_hall", "quarter_along_E"), swept.removed().stream().map(Proposal::key).toList());
		assertEquals(List.of("farm"), swept.proposals().open().stream().map(Proposal::key).toList());
		assertEquals(ps.declined(), swept.proposals().declined(), "withdrawn, not declined: the module itself may be offered");
		assertEquals(ps.accepted(), swept.proposals().accepted());
		assertSame(swept.proposals(), Proposals.withoutPlainModules(swept.proposals(), TYPES).proposals(), "nothing more to sweep");
		// saved by this Steward and read again: the same
		var again = SettlementStore.fromJson(store.toJson()).get("set_2").orElseThrow().proposals();
		assertEquals(ps.open().stream().map(Proposal::key).toList(), again.open().stream().map(Proposal::key).toList());
	}

	@Test
	void acceptRefusesAPlainModuleProposalBeforeAnythingIsSurveyedAcceptedOrSpent(@TempDir Path dir) throws Exception {
		var store = oldSave();
		Path file = dir.resolve("steward-settlements.json");
		Settlements.useForTest(store, file);
		for (String key : List.of("asked_mhx0a1_storage_hall", "quarter_along_E")) {
			var s = Settlements.store().get("set_2").orElseThrow();
			var title = s.proposals().open().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow().title();
			var spendsBefore = s.autonomy().spends();
			// no player: the guard runs before anything needs one (a missing guard reaches the quarter's or the addition's start and fails otherwise)
			var r = Actions.acceptProposal(null, s, key, false);
			assertFalse(r.ok());
			assertTrue(r.message().startsWith(title + " isn't built: a working "), r.message());
			var after = Settlements.store().get("set_2").orElseThrow();
			assertTrue(after.proposals().open().stream().noneMatch(p -> p.key().equals(key)), "withdrawn");
			assertFalse(after.proposals().accepted().contains(key), "never recorded as accepted");
			assertFalse(after.proposals().declined().contains(key), "not declined");
			assertEquals(spendsBefore, after.autonomy().spends(), "nothing spent");
			// the withdrawal is the saved file's, confirmed on disk
			var onDisk = SettlementStore.fromJson(Files.readString(file)).get("set_2").orElseThrow().proposals();
			assertTrue(onDisk.open().stream().noneMatch(p -> p.key().equals(key)));
		}
		// the autonomous accept takes the same guard
		var s = Settlements.store().get("set_2").orElseThrow();
		assertEquals(List.of("farm"), s.proposals().open().stream().map(Proposal::key).toList());
		assertNotNull(ModuleKind.of("crop_farm").orElse(null));
	}

	@Test
	void anAutonomousAcceptOfAPlainModuleIsRefusedToo(@TempDir Path dir) throws Exception {
		Settlements.useForTest(oldSave(), dir.resolve("steward-settlements.json"));
		var s = Settlements.store().get("set_2").orElseThrow();
		var r = Actions.acceptProposal(null, s, "asked_mhx0a1_storage_hall", true);
		assertFalse(r.ok());
		assertTrue(r.message().contains("isn't built"), r.message());
		assertTrue(Settlements.store().get("set_2").orElseThrow().autonomy().spends().isEmpty());
	}
}
