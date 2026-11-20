package dev.larattalabs.steward.ui;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.labui.ui.Glossary;
import dev.larattalabs.labui.ui.HelpKeys;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** (UI-PR-S) Steward's glossary section, its screens' "what's this" lines and "?" rows. */
class StewardHelpTest {
	@AfterEach
	void clear() {
		Glossary.clearForTest();
	}

	@Test
	void theSectionDefinesEveryWordLabUiReservedForSteward() {
		Glossary.Section s = StewardGlossary.section();
		assertEquals(Glossary.STEWARD, s.owner());
		assertEquals(List.of(), Glossary.missing(s), "lab-ui's reserved words (S-PR-3)");
		for (Glossary.Term t : s.terms()) {
			assertTrue(t.meaning().length() <= Glossary.MAX_MEANING, t.word() + " is too long to register");
			assertFalse(t.meaning().toLowerCase(Locale.ROOT).contains("blueprint"), "design, never blueprint: " + t.word());
		}
		StewardGlossary.register();
		assertEquals(s.terms(), Glossary.section(Glossary.STEWARD).terms(), "registered whole");
		assertEquals("Steward", Glossary.sections(Glossary.STEWARD).get(0).title(), "first on Steward's own pages");
	}

	@Test
	void sharedWordsKeepArchitectsMeaning() {
		// a Steward lot is a building's place, never Architect's plot (the contract's §3)
		String lot = StewardGlossary.section().find("lot").meaning();
		assertTrue(lot.contains("plot") && lot.contains("Architect"), lot);
	}

	@Test
	void everyScreenHasATipThatFitsOneLineAndTermsTheGlossaryKnows() {
		StewardGlossary.register();
		for (StewardHelp.Screen s : StewardHelp.Screen.values()) {
			String tip = StewardHelp.tip(s);
			assertTrue(HelpKeys.tipFits(tip), s + ": a tip is one line of at most " + HelpKeys.MAX_TIP + " characters (" + (tip == null ? 0 : tip.length()) + ")");
			assertTrue(s.key().startsWith("steward_mc:"), "namespaced: " + s.key());
			for (String w : StewardHelp.terms(s)) {
				// "design" is Architect's word: the overlay finds it once both mods register
				if (!w.equals("design")) assertFalse(Glossary.lookup(w).isEmpty(), s + " names " + w + ", which no section defines");
			}
		}
	}

	@Test
	void theOverlayReadsSteward_sKeysLiveAndEndsWithEsc() {
		Map<String, String> bound = new java.util.HashMap<>(Map.of("inbox", "Y", "inspect", "I", "survey", "U"));
		List<HelpKeys.Row> rows = StewardHelp.rows(StewardHelp.Screen.SETTLEMENT, bound::get);
		assertTrue(rows.stream().anyMatch(r -> r.key().equals("Y") && r.verb().contains("inbox")));
		assertTrue(rows.stream().anyMatch(r -> r.key().equals("Esc")));
		bound.put("inbox", "Mouse Button 4");
		assertTrue(StewardHelp.rows(StewardHelp.Screen.TALK, bound::get).stream().anyMatch(r -> r.key().equals("Mouse Button 4")), "a rebound key shows as bound");
		assertFalse(StewardHelp.rows(StewardHelp.Screen.TALK, bound::get).stream().anyMatch(r -> r.verb().contains("survey")), "only where it helps");
		for (StewardHelp.Screen s : StewardHelp.Screen.values()) assertFalse(StewardHelp.rows(s, bound::get).isEmpty());
	}

	@Test
	void theOverlaySendsThePlayerToArchitectsHelpForEveryTerm() {
		// (LUI3-S) set on lab-ui 0.6.2's KeysOverlay.footer / moreTerms; no longer than lab-ui's default footer, which fits at 150 % x GUI 4
		assertEquals("Esc or ? closes · all terms: Architect's Help", StewardHelp.FOOTER);
		assertEquals("+3 more in Architect's Help", StewardHelp.moreTerms(3));
		assertTrue(StewardHelp.FOOTER.length() <= HelpKeys.DEFAULT_FOOTER.length(), StewardHelp.FOOTER);
	}
}
