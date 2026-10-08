package dev.larattalabs.steward.gateway;

import dev.larattalabs.architect.api.DeltaRequest;
import dev.larattalabs.architect.api.DeltaVerdict;
import dev.larattalabs.architect.api.PartDelta;
import dev.larattalabs.architect.api.PartStatus;
import dev.larattalabs.architect.api.PlayerEdits;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.architect.api.Refusal;
import dev.larattalabs.steward.model.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.world.item.Item;

/**
 * "Update available" for a placed building (Architect 1.7.0 delta apply): decides from a {@code checkDelta} preview what Steward does at the
 * settlement's permission level, and words the inbox line. Pure apart from the API record types. Polish is not used (it failed Architect's gate);
 * new versions come from re-designs, and this applies them.
 */
public final class UpdatePlanner {
	private UpdatePlanner() {
	}

	public enum Action { APPLY, ASK, BLOCKED, NOTHING }

	public record Plan(Action action, String text, PlayerEdits playerEdits) {}

	/** Refusals that no retry fixes (the building must be re-placed or the situation changed by the player). */
	private static final List<Reason> PERMANENT = List.of(Reason.FRAME_CHANGED, Reason.VERSION_GONE, Reason.COVERED, Reason.PLAYER_EDITS, Reason.BLOCK_ENTITIES);

	public static DeltaRequest request(String siteId, int toVersion, PlayerEdits edits) {
		return new DeltaRequest(siteId, toVersion, edits, null, null, false, new com.google.gson.JsonObject());
	}

	/** KEEP never destroys player work; only an Observer-level settlement asks to refuse instead (so the player decides first). */
	public static PlayerEdits editsFor(Permission p) {
		return p == Permission.OBSERVER ? PlayerEdits.REFUSE : PlayerEdits.KEEP;
	}

	public static Plan plan(String building, DeltaVerdict v, Permission p) {
		PlayerEdits edits = editsFor(p);
		if (!v.ok()) {
			boolean permanent = v.refusals().stream().anyMatch(r -> PERMANENT.contains(r.reason()));
			String why = v.refusals().stream().map(Refusal::message).collect(Collectors.joining("; "));
			return new Plan(permanent ? Action.BLOCKED : Action.ASK, building + " cannot be updated" + (permanent ? "" : " right now") + ": " + why, edits);
		}
		if (v.added() + v.removed() + v.changed() == 0) return new Plan(Action.NOTHING, building + ": nothing to write.", edits);
		String text = building + " has an update: " + summary(v);
		// survival costs materials, so the player always decides; creative upgrades follow the permission level
		boolean costs = !v.bom().isEmpty();
		boolean ask = costs || p.needsApproval(Permission.Action.UPGRADE);
		return new Plan(ask ? Action.ASK : Action.APPLY, text, edits);
	}

	/** "3 parts changed (roof, porch, +wing), 2 cells kept, needs 140 dirt, refunds 80 planks". */
	public static String summary(DeltaVerdict v) {
		List<String> parts = new ArrayList<>();
		for (PartDelta d : v.parts().values()) {
			if (d.status() == PartStatus.UNCHANGED) continue;
			parts.add((d.status() == PartStatus.ADDED ? "+" : d.status() == PartStatus.REMOVED ? "-" : "") + d.name());
		}
		parts.sort(String::compareTo);
		StringBuilder b = new StringBuilder(parts.size() + (parts.size() == 1 ? " part" : " parts") + " changed (" + String.join(", ", parts) + ")");
		b.append(", ").append(v.added() + v.removed() + v.changed()).append(" blocks");
		if (!v.kept().isEmpty()) b.append(", ").append(v.kept().size()).append(v.kept().size() == 1 ? " edited block kept" : " edited blocks kept");
		if (!v.bom().isEmpty()) b.append(", needs ").append(items(v.bom()));
		if (!v.refund().isEmpty()) b.append(", refunds ").append(items(v.refund()));
		return b.toString();
	}

	private static String items(java.util.Map<Item, Integer> m) {
		return m.entrySet().stream().map(e -> e.getValue() + " " + e.getKey().getDescriptionId().replaceAll(".*\\.", "")).sorted().collect(Collectors.joining(", "));
	}
}
