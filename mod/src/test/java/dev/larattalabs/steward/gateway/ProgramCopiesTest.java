package dev.larattalabs.steward.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.BudgetPolicy;
import dev.larattalabs.steward.model.ConceptCard;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProgramCopiesTest {
	@Test
	void aVillageOfHutsCopiesItsHutDesignsAndTheEstimateCountsOnlyDesigns() throws Exception {
		ConceptCard c;
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("stilt_village.json")) {
			c = ConceptCard.parse(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("card"));
		}
		// the whole program: 10 buildings, six of them fisher's huts (two designs, four copies)
		int copies = ProgramPlanner.copies(c, 10, 1);
		assertEquals(4, copies, "six huts: two designs and four copies");
		assertTrue(ProgramPlanner.estimate(c, 10, 1).usdHigh() < BudgetPolicy.estimate(10, 1).usdHigh(), "copies are free");
		for (int n = 3; n <= 10; n++) System.out.println("stilt village at " + n + ": " + ProgramPlanner.copies(c, n, 1) + " copies");
	}
}
