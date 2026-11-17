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
 * @param proposal the key of the proposal accepted (null on an offer): a build that ends with nothing placed gives it back, also after a restart
 * @param held the bill the player is asked about now (a quote past the bound, or one asked about for another reason), saved with the build so
 *     the approval binds to what was shown, also across a restart; null when nothing waits. (QD, since 2026-11-14) It is the quote with each
 *     terrain item up to its allowance ({@link ModuleBound#allowed}), as shown ("up to N"); an older save's is the exact quote, approved exactly.
 *     Approved, it becomes the bound, and the bound is the realise's {@code maxBill}
 * @param inexactApproved the player approved a held bill that Architect could not quote exactly: a later quote with the same uncertainty, within
 *     that bill, is not asked about again
 * @param refused (QD-2, since 2026-11-16) Architect refused the realise at put-down ({@code BILL_CAP}: its bill then was past this bound): the
 *     next quote is put to the player whatever it says, never realised on its own, also after a restart. Saved with the build before the quote is
 *     asked for, kept through the hold it leads to and cleared by the player's approval ({@link #approvedHeld}). TRUE or null (absent in the file: Gson writes no null), so an
 *     order saved before it, or an offer, reads as not refused. No format bump: an older Steward that drops it loses only the re-ask, and the bound
 *     still holds at the realise (Architect's maxBill), so nothing is paid past what was approved
 */
public record ModuleOrder(String moduleId, int version, String programSha, JsonObject params, @Nullable JsonObject bound, @Nullable String rate,
	boolean lampFallback, boolean autonomous, @Nullable String proposal, @Nullable JsonObject held, boolean inexactApproved, @Nullable Boolean refused) {
	public ModuleOrder {
		params = params == null ? null : Canon.sorted(params).getAsJsonObject();
		bound = ModuleBound.clean(bound);
		held = ModuleBound.clean(held);
		refused = Boolean.TRUE.equals(refused) ? Boolean.TRUE : null;
	}

	/** As saved before QD-2: not refused at put-down. */
	public ModuleOrder(String moduleId, int version, String programSha, JsonObject params, @Nullable JsonObject bound, @Nullable String rate,
		boolean lampFallback, boolean autonomous, @Nullable String proposal, @Nullable JsonObject held, boolean inexactApproved) {
		this(moduleId, version, programSha, params, bound, rate, lampFallback, autonomous, proposal, held, inexactApproved, null);
	}

	/** An offer (not accepted, nothing held). */
	public ModuleOrder(String moduleId, int version, String programSha, JsonObject params, @Nullable JsonObject bound, @Nullable String rate,
		boolean lampFallback, boolean autonomous) {
		this(moduleId, version, programSha, params, bound, rate, lampFallback, autonomous, null, null, false);
	}

	/** (QD-2) Whether the realise under this bound was refused at put-down and the player has not been asked again yet. */
	public boolean refusedAtPutDown() {
		return Boolean.TRUE.equals(refused);
	}

	/** (QD-2) The same order, its realise refused at put-down ({@code BILL_CAP}): the next quote is put to the player. */
	public ModuleOrder refusedAtPutDown(boolean r) {
		return new ModuleOrder(moduleId, version, programSha, params, bound, rate, lampFallback, autonomous, proposal, held, inexactApproved, r ? Boolean.TRUE : null);
	}

	/** The same order with other params (a smaller size) and the bound that goes with them. */
	public ModuleOrder resized(JsonObject p, @Nullable JsonObject b) {
		return new ModuleOrder(moduleId, version, programSha, p, b, rate, lampFallback, autonomous, proposal, held, inexactApproved, refused);
	}

	/**
	 * The held bill, approved by the player: it becomes the bound, nothing waits any more, and it is no longer the steward's own choice (Q4 is the
	 * player's word now).
	 */
	public ModuleOrder approvedHeld() {
		return new ModuleOrder(moduleId, version, programSha, params, held, rate, lampFallback, false, proposal, null, true);
	}

	/**
	 * The same order holding this quote for the player's word. A put-down refusal stays until the player approves ({@link #approvedHeld}): the
	 * hold's order is saved before its phase is (a crash between them restores PLACING), so a restart there asks again, never realises on its own.
	 */
	public ModuleOrder holding(JsonObject bill) {
		return new ModuleOrder(moduleId, version, programSha, params, bound, rate, lampFallback, autonomous, proposal, bill, inexactApproved, refused);
	}

	/** The order as accepted: by the player, or by the steward on its own, from the proposal {@code key}. */
	public ModuleOrder accepted(boolean byTheSteward, String key) {
		return new ModuleOrder(moduleId, version, programSha, params, bound, rate, lampFallback, byTheSteward, key, null, false);
	}

	/** The same offer, with the lamp the bible gives (the plain lantern when its lamp is too dim). */
	public ModuleOrder withLamp(boolean fallback) {
		return new ModuleOrder(moduleId, version, programSha, params, bound, rate, fallback, autonomous, proposal, held, inexactApproved, refused);
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
