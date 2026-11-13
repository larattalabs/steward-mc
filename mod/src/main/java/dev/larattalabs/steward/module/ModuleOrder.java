package dev.larattalabs.steward.module;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * What the player accepted for one module lot (docs/MODULES.md 4.1, 4.4): the catalog version and program, the params, and in survival the bill's
 * upper bound they approved. Saved with the build (BuildStore format 6, {@code moduleOrders}: lotId -> order) before the growth is planned, so a
 * restart plans, binds and bills the same thing. Also the proposal's {@code module} member (the offer as the player saw it).
 *
 * @param moduleId the catalog id
 * @param version the catalog version offered
 * @param programSha the program's sha offered (a catalog whose newest version or program changed since the offer refuses the build: what was
 *     approved is not what would run)
 * @param params canonical params (sorted keys); a smaller size after {@code NO_ROOM} replaces them, said
 * @param bound in survival, the approved bound (item -> count); null when the town builds at once (nothing is billed)
 * @param rate the measured rate as the offer said it ("about 40 wheat a day once it's going"), or null when the measurement has none
 * @param lampFallback whether the offer said the style's lamp is too dim (the plain lantern is used)
 * @param autonomous the steward accepted it on its own (Autonomous, Full): in survival it asks first when the whole bill takes more than half of
 *     the stockpile's count of any item (Q4); false on an offer
 */
public record ModuleOrder(String moduleId, int version, String programSha, JsonObject params, @Nullable JsonObject bound, @Nullable String rate,
	boolean lampFallback, boolean autonomous) {
	public ModuleOrder {
		params = params == null ? null : Canon.sorted(params).getAsJsonObject();
		bound = ModuleBound.clean(bound);
	}

	/** The same order with other params (a smaller size) and the bound that goes with them. */
	public ModuleOrder resized(JsonObject p, @Nullable JsonObject b) {
		return new ModuleOrder(moduleId, version, programSha, p, b, rate, lampFallback, autonomous);
	}

	/** The same order with a new approved bound (the player approved a quote past the first one). */
	public ModuleOrder approved(JsonObject b) {
		return new ModuleOrder(moduleId, version, programSha, params, b, rate, lampFallback, autonomous);
	}

	/** The order as accepted: by the player, or by the steward on its own. */
	public ModuleOrder accepted(boolean byTheSteward) {
		return new ModuleOrder(moduleId, version, programSha, params, bound, rate, lampFallback, byTheSteward);
	}

	/** The same offer, with the lamp the bible gives (the plain lantern when its lamp is too dim). */
	public ModuleOrder withLamp(boolean fallback) {
		return new ModuleOrder(moduleId, version, programSha, params, bound, rate, fallback, autonomous);
	}

	/** The lot ext Architect keeps on the placed lot. */
	public ModuleRef ref() {
		return new ModuleRef(moduleId, version, programSha, params);
	}

	/** Whether the order is whole: an id, a version, a program sha and params (a malformed save refuses the build rather than guess). */
	public boolean valid() {
		return moduleId != null && moduleId.matches(ModuleCatalog.ID_RE) && version >= 1 && programSha != null && programSha.matches("[0-9a-f]{64}") && params != null;
	}
}
