package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.OwedPart;
import dev.larattalabs.architect.api.RemoveResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * (T4RD-S) The one rule for what of a removal's owed parts ({@code RemoveResult.owedParts()}, Architect T4R-D: immutable, snapshotted before the removal
 * finished) the steward says, sums or calls "owed to you": a part whose recipient is the settlement's own owner id <b>and</b> whose reason is a refund of
 * what the settlement drew ({@link #REFUND_REASONS}). An allowlist, so the player's own blocks ({@code player_blocks}: owed to whoever placed them, told
 * by Architect's own line), a container's contents ({@code cargo}), {@code architect:unclaimed} and any reason added later stay out. Used by every reader:
 * {@code Undo.growth}, a growth's empty take-up, {@link RemovalEnds} and the sweep's recovered result. It changes no state: Architect's owed store is the
 * record. Pure.
 */
public final class OwedParts {
	private OwedParts() {
	}

	/** Architect's refund kinds of owed (T2b's {@code Owed.reason}): what a removal owes back of what the settlement drew. */
	static final Set<String> REFUND_REASONS = Set.of("crate_settle", "crate_putback", "group_refund");

	/** One owed part as the rule reads it: its recipient, its reason and its items (any item key: tests use names). */
	record Part<T>(@Nullable String recipient, @Nullable String reason, Map<T, Integer> items) {
	}

	/** The items owed back to {@code owner} of what it drew, summed over {@code parts} by the rule above; empty when none. Pure. */
	static <T> Map<T, Integer> sum(@Nullable String owner, @Nullable List<Part<T>> parts) {
		Map<T, Integer> out = new LinkedHashMap<>();
		if (owner == null || parts == null) return out;
		for (Part<T> p : parts) {
			if (p == null || !owner.equals(p.recipient()) || !REFUND_REASONS.contains(p.reason())) continue;
			p.items().forEach((item, n) -> {
				if (n != null && n > 0) out.merge(item, n, Integer::sum);
			});
		}
		return out;
	}

	/** {@link #sum} over Architect's owed parts. Pure. */
	public static Map<Item, Integer> owedTo(@Nullable String owner, @Nullable List<OwedPart> parts) {
		if (parts == null) return Map.of();
		return sum(owner, parts.stream().filter(java.util.Objects::nonNull).map(p -> new Part<>(p.recipient(), p.reason(), p.items())).toList());
	}

	/** {@link #owedTo} of a removal's answer; empty for none, or an Architect before T4R-D (no {@code owedParts}). */
	public static Map<Item, Integer> owedTo(@Nullable String owner, @Nullable RemoveResult r) {
		if (r == null) return Map.of();
		try {
			return owedTo(owner, r.owedParts());
		} catch (LinkageError e) {
			return Map.of();
		}
	}
}
