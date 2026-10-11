package dev.larattalabs.steward.layout;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CopyPlanTest {
	@Test
	void everyThirdLikeLotDesignsTheOthersCopyIt() {
		List<CopyPlan.Spec> lots = List.of(
			new CopyPlan.Spec("keep_1", "keep", "keep", 18, 15, true),
			new CopyPlan.Spec("hut_1", "fisher's hut", "hut", 11, 9, false),
			new CopyPlan.Spec("hut_2", "fisher's hut", "hut", 11, 9, false),
			new CopyPlan.Spec("hut_3", "fisher's hut", "hut", 11, 9, false),
			new CopyPlan.Spec("hut_4", "fisher's hut", "hut", 11, 9, false),
			new CopyPlan.Spec("shop_1", "bait shop", "shop", 11, 9, false),
			new CopyPlan.Spec("keep_2", "keep", "keep", 18, 15, true),
			new CopyPlan.Spec("hut_5", "fisher's hut", "hut", 11, 9, false));
		Map<String, String> c = CopyPlan.copies(lots);
		assertEquals(Map.of("hut_2", "hut_1", "hut_3", "hut_1", "hut_5", "hut_4"), c, "runs of three; another role or a landmark is never a copy");
	}

	@Test
	void aDifferentSizeIsAnotherDesign() {
		var c = CopyPlan.copies(List.of(new CopyPlan.Spec("a", "house", "house", 11, 9, false), new CopyPlan.Spec("b", "house", "house", 14, 12, false)));
		assertTrue(c.isEmpty());
	}
}
