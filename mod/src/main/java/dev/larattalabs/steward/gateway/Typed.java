package dev.larattalabs.steward.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.larattalabs.architect.api.BesideNote;
import dev.larattalabs.architect.api.BuildStatus;
import dev.larattalabs.architect.api.ItemEvent;
import dev.larattalabs.architect.api.RegionView;
import dev.larattalabs.architect.api.SiteView;
import org.jspecify.annotations.Nullable;

/**
 * (slice 8bS, Architect 8b's S-8b-8) The typed homes Architect 0.81.0 (API 1.69.0) gives the ext keys Steward read: a site's {@code part}, {@code lot} and
 * {@code revision}, an item event's {@code region}, {@code part}, {@code lot}, {@code revision} and {@code status} (the home of {@code waitsFor}), a
 * region's {@code besideNote} and {@code land}. The typed field wins; the ext key is read only when the typed field is empty (a record written before
 * Architect filled it). Pure.
 */
public final class Typed {
	public static final String PART = "architect_mc:part";
	public static final String LOT = "architect_mc:lot";
	public static final String REGION = "architect_mc:region";
	public static final String BESIDE = "architect_mc:beside";

	private Typed() {
	}

	/** The plan part a region's target builds. */
	public static @Nullable String part(SiteView v) {
		return v.part() != null ? v.part() : str(v.ext(), PART);
	}

	/** The lot a site stands on (Architect's; Steward's own {@code steward_mc:lot} tag is read by {@code Updates.lotIdOf} first). */
	public static @Nullable String lot(SiteView v) {
		return v.lot() != null ? v.lot() : str(v.ext(), LOT);
	}

	/** The rid of the revision that put the site down (0: the realised plan's). */
	public static int rid(SiteView v) {
		return v.revision() > 0 ? v.revision() : rid(v.ext());
	}

	/** The region a batch item belongs to. */
	public static @Nullable String region(ItemEvent e) {
		return e.region() != null ? e.region() : str(e.ext(), REGION);
	}

	/** The plan part a batch item builds (or its wait's part). */
	public static @Nullable String part(ItemEvent e) {
		return e.part() != null ? e.part() : str(e.ext(), PART);
	}

	/** The rid of the revision a batch item belongs to (0: the realised plan's). */
	public static int rid(ItemEvent e) {
		if (e.revision() > 0) return e.revision();
		if (e.status() != null && e.status().revision() > 0) return e.status().revision();
		return rid(e.ext());
	}

	/** A batch item's typed status, or null (an event from the old constructor). */
	public static @Nullable BuildStatus status(ItemEvent e) {
		return e.status();
	}

	/** {@code architect_mc:revision.rid} on an ext, else 0. */
	public static int rid(@Nullable JsonObject ext) {
		if (ext == null || !(ext.get("architect_mc:revision") instanceof JsonObject rv)) return 0;
		return rv.get("rid") instanceof JsonPrimitive r && r.isNumber() ? r.getAsInt() : 0;
	}

	/**
	 * A region's beside note: {@link RegionView#besideNote}, else ext {@code architect_mc:beside} read into the same record; null for a region not planned
	 * beside one.
	 */
	public static @Nullable BesideNote beside(RegionView v) {
		if (v.besideNote() != null) return v.besideNote();
		if (!(v.ext().get(BESIDE) instanceof JsonObject b)) return null;
		boolean dangling = b.get("dangling") instanceof JsonPrimitive d && d.isBoolean() && d.getAsBoolean();
		String gate = b.get("gateRemoved") instanceof JsonPrimitive g ? g.getAsString() : null;
		String region = b.get("region") instanceof JsonPrimitive r ? r.getAsString() : v.beside() != null ? v.beside() : "";
		return new BesideNote(region, dangling, gate);
	}

	private static @Nullable String str(@Nullable JsonObject ext, String key) {
		return ext != null && ext.get(key) instanceof JsonPrimitive p ? p.getAsString() : null;
	}
}
