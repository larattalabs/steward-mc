package dev.larattalabs.steward.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.larattalabs.steward.module.ModuleCatalog.Entry;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Whether a catalog module may be offered here, and with which params (docs/MODULES.md 2.1 and 2.2). Pure.
 *
 * <ul>
 * <li><b>No measurement, no offer.</b> Only on the Minecraft version it was measured on, and on an Architect version its measurement
 * names (measured.json {@code architect}: the versions whose RC ran its {@code module} flow).</li>
 * <li><b>Never a silent fallback.</b> {@link #offer(ModuleCatalog, String, Env)} judges the newest version only. If that one is refused,
 * nothing is offered: an older version is never put in its place.</li>
 * <li>Architect's helper must run given programs (feature {@code givenPrograms}).</li>
 * <li>A fixture module is test data: only a dev caller ({@link Env#fixtures()}) may install one.</li>
 * <li><b>Params within the envelope.</b> A branch param must be one of its measured values; any other param stays at its default.</li>
 * </ul>
 */
public final class ModuleGate {
	private ModuleGate() {}

	public static final String FEATURE = "givenPrograms";

	/** Why a module is not offered. */
	public enum Why {
		UNKNOWN, INVALID, NOT_MEASURED, MINECRAFT, ARCHITECT, FEATURE, FIXTURE, PARAMS
	}

	/** Where we are: the running Minecraft and Architect (mod) versions, Architect's features, whether fixtures are allowed (dev only). */
	public record Env(String minecraft, String architect, Set<String> features, boolean fixtures) {
		public Env {
			features = features == null ? Set.of() : Set.copyOf(features);
		}
	}

	/** A verdict: offered ({@code entry} set, {@code why} null), or refused with why and in words. */
	public record Verdict(@Nullable Entry entry, @Nullable Why why, String message) {
		public boolean ok() {
			return why == null;
		}

		static Verdict no(@Nullable Entry e, Why why, String message) {
			return new Verdict(e, why, message);
		}
	}

	/** The verdict on a module's newest version (never an older one in its place). */
	public static Verdict offer(ModuleCatalog catalog, String id, Env env) {
		Optional<Entry> e = catalog.newest(id);
		if (e.isEmpty()) return Verdict.no(null, Why.UNKNOWN, "No module " + id + " in the catalog.");
		return offer(e.get(), env);
	}

	/** The verdict on one catalog version. */
	public static Verdict offer(Entry e, Env env) {
		if (!e.valid()) return Verdict.no(e, Why.INVALID, e.key() + " is not a valid catalog entry: " + String.join("; ", e.problems()));
		if (e.fixture() && !env.fixtures()) return Verdict.no(e, Why.FIXTURE, e.key() + " is a test fixture, never offered.");
		var m = e.measured();
		if (m == null) return Verdict.no(e, Why.NOT_MEASURED, e.key() + " has no measurement.");
		if (!m.minecraft().equals(env.minecraft()))
			return Verdict.no(e, Why.MINECRAFT, e.key() + " was measured on Minecraft " + m.minecraft() + ", not " + env.minecraft() + ".");
		if (!m.architect().contains(env.architect()))
			return Verdict.no(e, Why.ARCHITECT, e.key() + " was measured with Architect " + String.join(", ", m.architect()) + ", not " + env.architect() + ".");
		if (!env.features().contains(FEATURE)) return Verdict.no(e, Why.FEATURE, "Architect's helper does not run given programs (" + FEATURE + ").");
		return new Verdict(e, null, e.key() + " may be offered.");
	}

	/** Resolved params: the canonical values (every param, sorted keys), or why they are refused. */
	public record Params(@Nullable JsonObject values, @Nullable String refused) {
		public boolean ok() {
			return refused == null;
		}
	}

	/**
	 * The params a caller asks for, checked against the entry's schema and envelope, defaults filled in, in canonical form. An unknown
	 * param, a value outside its domain, a branch value that was not measured, or a non-branch param away from its default is refused.
	 */
	public static Params params(Entry e, @Nullable JsonObject asked) {
		JsonObject a = asked == null ? new JsonObject() : asked;
		for (String k : a.keySet()) if (!e.params().containsKey(k)) return new Params(null, "unknown param " + k);
		JsonObject out = new JsonObject();
		for (Map.Entry<String, ModuleCatalog.Param> p : e.params().entrySet()) {
			String k = p.getKey();
			JsonElement v = a.has(k) ? a.get(k) : p.getValue().def();
			if (!p.getValue().admits(v)) return new Params(null, k + " " + v + " is outside its domain");
			var branch = e.envelope().branches().get(k);
			if (branch != null) {
				if (!branch.contains(v)) return new Params(null, k + " " + v + " is not a measured branch (" + branch + ")");
			} else if (!v.equals(p.getValue().def())) {
				return new Params(null, k + " is fixed at " + p.getValue().def() + " (not a measured branch)");
			}
			out.add(k, v.deepCopy());
		}
		JsonObject sorted = Canon.sorted(out).getAsJsonObject();
		if (Canon.text(sorted).length() > ModuleCatalog.PROGRAM_PARAMS_MAX) return new Params(null, "params are over " + ModuleCatalog.PROGRAM_PARAMS_MAX + " characters");
		return new Params(sorted, null);
	}
}
