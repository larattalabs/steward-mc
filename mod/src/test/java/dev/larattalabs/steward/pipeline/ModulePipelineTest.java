package dev.larattalabs.steward.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Permission;
import dev.larattalabs.steward.pipeline.Pipeline.*;
import java.io.InputStreamReader;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** docs/MODULES.md 4.2 steps 5-6 (slice M3): a module build placed on the player's Accept, held only when its bill is past the bound. */
class ModulePipelineTest {
	static State ready() throws Exception {
		var in = ModulePipelineTest.class.getClassLoader().getResourceAsStream("real/crater_works_real.json");
		var card = ConceptCard.parse(JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card"));
		return new State(Phase.READY_TO_PLACE, "set_1", card, "bib", 1, null, Map.of(), 0, 0, 0, false, null, null);
	}

	@Test
	void aBoundModuleIsPlacedWithoutAskingAgainWhateverThePermission() throws Exception {
		for (Permission p : Permission.values()) {
			var st = Pipeline.step(ready(), new ModulePlace(), p);
			assertEquals(Phase.PLACING, st.next().phase(), p.name());
			assertTrue(st.commands().isEmpty());
		}
	}

	@Test
	void aBillPastTheBoundHoldsAndAsks() throws Exception {
		var held = Pipeline.step(ready(), new HoldBill("The bill came to more than you approved."), Permission.AUTONOMOUS);
		assertEquals(Phase.AWAITING_PLACEMENT_APPROVAL, held.next().phase());
		var n = (Notify) held.commands().get(0);
		assertTrue(n.needsDecision());
		assertEquals(Pipeline.Decision.PLACEMENT, Pipeline.awaiting(held.next()));
		// asked again while waiting: still waiting
		var again = Pipeline.step(held.next(), new HoldBill("again"), Permission.AUTONOMOUS);
		assertEquals(Phase.AWAITING_PLACEMENT_APPROVAL, again.next().phase());
		// approved: placed (the runner quotes again against what was approved)
		var go = Pipeline.step(held.next(), new PlacementApproved(), Permission.AUTONOMOUS);
		assertEquals(Phase.PLACING, go.next().phase());
		assertTrue(go.commands().stream().anyMatch(c -> c instanceof ApproveStages));
		// held from PLACING (the quote after an approval past it again)
		var placing = Pipeline.step(ready(), new ModulePlace(), Permission.PROPOSALS).next();
		assertEquals(Phase.AWAITING_PLACEMENT_APPROVAL, Pipeline.step(placing, new HoldBill("x"), Permission.PROPOSALS).next().phase());
	}

	@Test
	void aCancelWhileBindingEndsWithNothingRunning() throws Exception {
		var st = Pipeline.step(ready(), new Cancel(), Permission.PROPOSALS);
		assertEquals(Phase.CANCELLING, st.next().phase());
		assertTrue(st.commands().stream().anyMatch(c -> c instanceof CancelBatch));
		assertEquals(Phase.CANCELLED, Pipeline.step(st.next(), new CancelConfirmed(), Permission.PROPOSALS).next().phase());
	}
}
