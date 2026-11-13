package dev.larattalabs.steward.module;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.ArchitectRefused;
import dev.larattalabs.architect.api.Bible;
import dev.larattalabs.architect.api.BiblePin;
import dev.larattalabs.architect.api.CheckReport;
import dev.larattalabs.architect.api.DesignRequest;
import dev.larattalabs.architect.api.ProgramRefused;
import dev.larattalabs.steward.module.ModuleCatalog.Entry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;

/**
 * Installs catalog modules through Architect's given-program API (S-M5-1, Architect 0.62.0 / API 1.55.0), at $0 and by key:
 * <ul>
 * <li>{@link #install}: the catalog entry itself, {@code Designs.fromProgram(source, params, null, key, owner)} under
 * {@link #catalogKey} (the same key and body answer the same entry, also after a restart);</li>
 * <li>{@link #variant}: a keyed re-run with params, a bible and the lot's reserved cells, {@code Designs.variant} under the caller's key
 * (a lot's: {@link #lotKey});</li>
 * <li>{@link #byKey}: the variant a key made, {@code Designs.variantByKey(owner, key)}.</li>
 * </ul>
 * Every call is gated first ({@link ModuleGate}) and its params checked against the envelope; a variant's bible is checked against the
 * palette constraints ({@link PaletteCheck}) before anything is sent. Nothing falls back: a refusal is an {@link Outcome} with why.
 */
public final class ModuleInstalls {
	private ModuleInstalls() {}

	/** The owner scope of catalog entries' keys (every catalog install is under it, so its keys are found again under it). */
	public static final String CATALOG_OWNER = "steward_mc:catalog";

	/** Architect's opKey alphabet and length (Designs: {@code [A-Za-z0-9_.:-]{1,128}}). */
	public static final String KEY_RE = "[A-Za-z0-9_.:-]{1,128}";

	/**
	 * The catalog entry's key, {@code steward.catalog.<id>.v<version>.<sha12>}: its identity and the program's short sha (a changed program never
	 * collides with an old key). Architect's keys take no {@code @}.
	 */
	public static String catalogKey(Entry e) {
		return "steward.catalog." + e.id() + ".v" + e.version() + "." + ModuleCatalog.shortSha(e.programSha());
	}

	/** A module lot's variant key (docs/MODULES.md 4.2 step 3): {@code steward.<build>.module.<lotId>}, {@code .<n>} for the n-th (n > 1) after a re-plan. */
	public static String lotKey(String buildId, String lotId, int n) {
		return "steward." + buildId + ".module." + lotId + (n > 1 ? "." + n : "");
	}

	/**
	 * How an install ended: {@code entryId} set, or refused with a {@code detail} (Architect's, e.g. {@code reserved}, {@code params},
	 * {@code palette}, or Steward's own: {@code gate:<why>}, {@code params}, {@code palette}), a message and the checker's findings.
	 */
	public record Outcome(@Nullable String entryId, @Nullable String detail, String message, List<String> findings, @Nullable String lamp, boolean lampFallback) {
		public Outcome {
			findings = findings == null ? List.of() : List.copyOf(findings);
		}

		public boolean ok() {
			return entryId != null;
		}

		static Outcome refused(String detail, String message) {
			return new Outcome(null, detail, message, List.of(), null, false);
		}
	}

	/** Installs the catalog entry at its default params and the program's own palette. */
	public static CompletableFuture<Outcome> install(Entry e, ModuleGate.Env env) {
		var v = ModuleGate.offer(e, env);
		if (!v.ok()) return CompletableFuture.completedFuture(Outcome.refused("gate:" + v.why().name().toLowerCase(), v.message()));
		var p = ModuleGate.params(e, null);
		if (!p.ok()) return CompletableFuture.completedFuture(Outcome.refused("params", p.refused()));
		return answer(ArchitectApi.get().designs().fromProgram(e.source(), p.values(), null, catalogKey(e), CATALOG_OWNER), null, false);
	}

	/**
	 * A keyed variant of an installed catalog entry ({@code baseEntryId}, from {@link #install}): params within the envelope, a bible
	 * ({@code null}: the program's palette; version 0: its latest) whose roles keep the palette constraints, the lot's reserved cells.
	 */
	public static CompletableFuture<Outcome> variant(Entry e, ModuleGate.Env env, String baseEntryId, @Nullable JsonObject params, @Nullable BiblePin bible,
		DesignRequest.@Nullable Reserved reserved, String key, @Nullable String owner, PaletteCheck.BlockFacts facts) {
		var v = ModuleGate.offer(e, env);
		if (!v.ok()) return CompletableFuture.completedFuture(Outcome.refused("gate:" + v.why().name().toLowerCase(), v.message()));
		var p = ModuleGate.params(e, params);
		if (!p.ok()) return CompletableFuture.completedFuture(Outcome.refused("params", p.refused()));
		String lamp = null;
		boolean fallback = false;
		if (bible != null) {
			Optional<Bible> b = bible.version() > 0 ? ArchitectApi.get().bibles().get(bible.id(), bible.version()) : ArchitectApi.get().bibles().get(bible.id());
			if (b.isEmpty()) return CompletableFuture.completedFuture(Outcome.refused("palette", "No bible " + bible.id() + (bible.version() > 0 ? " v" + bible.version() : "") + "."));
			var pc = PaletteCheck.check(e.palette(), b.get().roles(), facts);
			if (!pc.ok()) return CompletableFuture.completedFuture(new Outcome(null, "palette", e.key() + " can't take the style " + bible.id() + ": "
				+ String.join("; ", pc.broken()), pc.broken(), null, false));
			lamp = pc.lamp();
			fallback = pc.lampFallback();
		}
		return answer(ArchitectApi.get().designs().variant(baseEntryId, p.values(), bible, reserved, key, owner), lamp, fallback);
	}

	/** The variant a key made: empty when the key never made one. */
	public static CompletableFuture<Optional<String>> byKey(String key, @Nullable String owner) {
		return ArchitectApi.get().designs().variantByKey(owner, key);
	}

	private static CompletableFuture<Outcome> answer(CompletableFuture<String> f, @Nullable String lamp, boolean fallback) {
		return f.handle((id, err) -> {
			if (err == null) return new Outcome(id, null, "installed " + id, List.of(), lamp, fallback);
			Throwable t = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
			if (t instanceof ProgramRefused pr) {
				return new Outcome(null, pr.detail(), pr.getMessage(), pr.report().findings().stream().map(ModuleInstalls::finding).toList(), null, false);
			}
			if (t instanceof ArchitectRefused ar) return Outcome.refused(ar.reason().name().toLowerCase() + (ar.detail() == null ? "" : ":" + ar.detail()), ar.getMessage());
			return Outcome.refused("error", String.valueOf(t));
		});
	}

	private static String finding(CheckReport.Finding f) {
		return f.rule() + ": " + f.message();
	}

	/** The roles a bible gives (for tests and the dev hook). */
	static Map<String, String> roles(Bible b) {
		return b.roles();
	}
}
