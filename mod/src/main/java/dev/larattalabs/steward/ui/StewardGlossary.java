package dev.larattalabs.steward.ui;

import dev.larattalabs.labui.ui.Glossary;
import dev.larattalabs.labui.ui.Glossary.Term;
import java.util.List;

/**
 * (UI-PR-S) Steward's section of lab-ui's shared glossary (the slot lab-ui reserves for it, {@link Glossary#STEWARD}, with the words it must
 * define). Shown on the "?" overlay's Terms of Steward's screens and on Architect's Status > Help page. Pure; registered at client init.
 * The shared words keep Architect's meanings: a lot is the same in both mods, a Steward lot is not Architect's plot, and "stockpile" is the
 * materials a survival build holds ("design", never "blueprint").
 */
public final class StewardGlossary {
	public static final String OWNER = Glossary.STEWARD;
	public static final List<Term> TERMS = List.of(
		new Term("settlement", "A place you found with the Founding Stone and describe in your own words. Its steward plans, builds and grows it."),
		new Term("steward", "The settlement's keeper: it stands by the stone, plans what you asked for and builds it. Right-click it to talk."),
		new Term("Founding Stone", "The block that claims a settlement. Place it where the town should stand, then describe what it should be."),
		new Term("lot", "One building's place in a settlement or a region (Architect uses the same word). Not the same as an Architect plot."),
		new Term("district", "A part of the settlement with its own street and buildings, joined to the town by a road."),
		new Term("quarter", "A small district built at once along a shore, a river, a ridge or a biome's edge: two to four buildings."),
		new Term("module", "A working part the steward can build, such as a crop farm or a storage hall, measured before it is offered."),
		new Term("stockpile", "In survival, the materials a settlement's builds take from: what you put in its crates."),
		new Term("bill limit", "The most a survival build may take from the stockpile: its quote, as you approved it. It never takes more."),
		new Term("proposal", "An idea the steward offers for your settlement. Build it, or decline it: nothing is built until you say so."));

	private StewardGlossary() {
	}

	public static Glossary.Section section() {
		return new Glossary.Section(OWNER, "Steward", TERMS);
	}

	public static void register() {
		Glossary.register(section());
	}
}
