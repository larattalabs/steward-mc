package dev.larattalabs.steward.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.CritiqueMode;
import dev.larattalabs.architect.api.CritiqueSpec;
import dev.larattalabs.architect.api.DesignRequest;
import dev.larattalabs.architect.api.GroupRequest;
import dev.larattalabs.steward.layout.VillageLayout;
import dev.larattalabs.steward.layout.VillageLayout.Lot;
import dev.larattalabs.steward.model.BudgetPolicy;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Turns an approved concept card, a village layout and a bible into Architect's design group: one item per lot, massing first with the approval
 * owned by Steward (its inbox decides), landmarks on Opus and designed first, the rest on Sonnet in waves. Pure (no Minecraft calls).
 */
public final class GroupPlanner {
	public static final int LANDMARK_HEIGHT = 40;
	public static final int ORDINARY_HEIGHT = 24;

	private GroupPlanner() {
	}

	public record Options(String bibleId, @Nullable Integer bibleVersion, double budgetUsd, int concurrency, int maxRedirects, int landmarks, boolean critiqueReport) {
		public Options(String bibleId, @Nullable Integer bibleVersion, double budgetUsd, int concurrency, int maxRedirects, int landmarks) {
			this(bibleId, bibleVersion, budgetUsd, concurrency, maxRedirects, landmarks, true);
		}

		public Options withCritiqueReport(boolean on) {
			return new Options(bibleId, bibleVersion, budgetUsd, concurrency, maxRedirects, landmarks, on);
		}

		/** Defaults for a card: its budget if the player set one, else the size-scaled suggestion; landmarks per the typical count for the size. */
		public static Options forCard(Settlement s, String bibleId, @Nullable Integer bibleVersion) {
			String size = s.card().site().size();
			Double b = s.card().constraints().budgetUsd();
			double budget = b != null && b > 0 ? b : BudgetPolicy.suggestedBudgetUsd(size);
			return new Options(bibleId, bibleVersion, budget, 3, 3, BudgetPolicy.typicalLandmarks(size));
		}
	}

	/** The request, and the ids of lots that did not fit in one group (Architect allows 24 items). */
	public record Built(GroupRequest request, List<String> omittedLotIds) {}

	public static Built build(Settlement s, VillageLayout.Plan plan, Options o) {
		List<Lot> lots = new ArrayList<>(plan.lots());
		// landmarks: the ones the card's program marks, else the largest footprints; the largest landmark is the anchor (designed first, the rest match it)
		List<Lot> byArea = new ArrayList<>(lots);
		byArea.sort(Comparator.<Lot>comparingInt(l -> l.sizeX() * l.sizeZ()).reversed().thenComparing(Lot::id));
		Set<String> landmarkIds = new java.util.LinkedHashSet<>();
		boolean flagged = lots.stream().anyMatch(Lot::landmark);
		for (Lot l : byArea) {
			if (landmarkIds.size() >= o.landmarks() && !flagged) break;
			if (!flagged || l.landmark()) landmarkIds.add(l.id());
		}
		String anchorId = landmarkIds.isEmpty() ? null : landmarkIds.iterator().next();

		List<GroupRequest.Item> items = new ArrayList<>();
		List<String> omitted = new ArrayList<>();
		// landmarks first so the 24-item cap never drops one
		List<Lot> ordered = new ArrayList<>();
		for (Lot l : lots) if (landmarkIds.contains(l.id())) ordered.add(l);
		for (Lot l : lots) if (!landmarkIds.contains(l.id())) ordered.add(l);
		for (Lot l : ordered) {
			if (items.size() >= GroupRequest.MAX_ITEMS) {
				omitted.add(l.id());
				continue;
			}
			boolean landmark = landmarkIds.contains(l.id());
			DesignRequest r = LotBrief.build(s, l, landmark ? LANDMARK_HEIGHT : ORDINARY_HEIGHT, null, null);
			boolean anchor = l.id().equals(anchorId);
			GroupRequest.Item item = new GroupRequest.Item(l.id(), r, landmark ? GroupRequest.Role.LANDMARK : GroupRequest.Role.ORDINARY, landmark && !anchor ? 1 : 2, anchor);
			items.add(o.critiqueReport() ? item.critique(reportSpec(s, l)) : item);
		}
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:styleVersion", s.styleVersion());
		GroupRequest req = new GroupRequest(LotBrief.clip(s.name(), LotBrief.MAX_GROUP_NAME), o.bibleId(), o.bibleVersion(), s.owner(), ext, o.concurrency(), o.budgetUsd(), items)
			.withMassingFirst(GroupRequest.ApprovalUi.OWNER, o.maxRedirects())
			.withContext(context(s, plan));
		return new Built(req, List.copyOf(omitted));
	}

	/**
	 * Report-only critique for an item (Architect 0.9.0: the revision LOOP failed its quality gates and ships experimental, so Steward uses REPORT, about $0.05-0.15
	 * a design): scores and issues for the inbox, no revision. The extra criteria carry facts about this lot that the critic should check.
	 */
	static CritiqueSpec reportSpec(Settlement s, Lot l) {
		List<String> extra = new ArrayList<>();
		extra.add("The entrance is on the front (south) face and is easy to read and reach");
		if (!s.card().avoid().isEmpty()) extra.add(clip("Avoids: " + String.join(", ", s.card().avoid())));
		extra.add(clip("Reads as a " + l.role() + " in " + s.card().purpose().text()));
		return new CritiqueSpec(CritiqueMode.REPORT, null, null, null, null, null, null, List.of(), null, extra);
	}

	private static String clip(String t) {
		return t.length() <= CritiqueSpec.MAX_CRITERION ? t : t.substring(0, CritiqueSpec.MAX_CRITERION);
	}

	/** Shared context for every item's brief: what the settlement is, and where the neighbours and the street are. */
	static com.google.gson.JsonElement context(Settlement s, VillageLayout.Plan plan) {
		ConceptCard c = s.card();
		JsonObject o = new JsonObject();
		o.addProperty("settlement", s.name());
		o.addProperty("site", c.site().text());
		o.addProperty("purpose", c.purpose().text());
		o.addProperty("style", c.style().text());
		if (c.story() != null && c.story().text() != null && !c.story().text().isBlank()) o.addProperty("story", c.story().text());
		JsonArray avoid = new JsonArray();
		c.avoid().forEach(avoid::add);
		o.add("avoid", avoid);
		JsonObject street = new JsonObject();
		street.addProperty("axis", "east-west");
		street.addProperty("z", plan.streetZ());
		street.addProperty("x0", plan.streetX0());
		street.addProperty("x1", plan.streetX1());
		o.add("street", street);
		JsonArray lots = new JsonArray();
		for (Lot l : plan.lots()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", l.id());
			j.addProperty("type", l.type());
			j.addProperty("role", l.role());
			j.addProperty("x", l.x());
			j.addProperty("z", l.z());
			j.addProperty("sizeX", l.sizeX());
			j.addProperty("sizeZ", l.sizeZ());
			j.addProperty("frontsStreetToThe", l.front().name().toLowerCase());
			lots.add(j);
		}
		o.add("lots", lots);
		return o;
	}
}
