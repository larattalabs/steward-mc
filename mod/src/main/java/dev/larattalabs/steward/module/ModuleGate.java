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
 * <li><b>The running kit.</b> (slice BCU) module.json's pins must be the kit this Architect bundles ({@code ArchitectApi.kitVersion()}) and the
 * checker its helper runs now ({@code checkerVersion()}): measured.json's Architect list alone does not say which kit runs. An unknown kit or
 * checker (an Architect without feature {@code kitVersion}, or no helper connected yet) is a mismatch, said in words.</li>
 * <li>A fixture module is test data: only a dev caller ({@link Env#fixtures()}) may install one.</li>
 * <li>A measurement certifies the exact program, schema, envelope, palette rules and pins ({@code result: pass}, {@code moduleSha}); a missing,
 * failed or stale one offers nothing. Only a dev measurement run ({@link Env#measuring()}) installs an uncertified entry, to measure it.</li>
 * <li>A smoke certificate ({@code basis: "smoke"}, speed rule 4) offers like a full one, on the Architect versions it names, only when
 * {@link ModuleCatalog} finds its carried full measurement binds to this program and to this module.json but for its pins; else it is
 * uncertified like any stale one.</li>
 * <li><b>Params within the envelope.</b> A branch param must be one of its measured values; any other param stays at its default.</li>
 * </ul>
 */
public final class ModuleGate {
	private ModuleGate() {}

	public static final String FEATURE = "givenPrograms";

	/** Why a module is not offered. */
	public enum Why {
		UNKNOWN, INVALID, NOT_MEASURED, MINECRAFT, ARCHITECT, KIT, FEATURE, FIXTURE, PARAMS
	}

	/**
	 * Where we are: the running Minecraft and Architect (mod) versions, Architect's features, whether fixtures are allowed, whether this is
	 * a measurement run (both dev only: {@code measuring} installs an entry whose measurement is missing or stale, to measure it), and the kit
	 * Architect bundles and the checker its helper runs ({@link #UNKNOWN_KIT} when Architect has not said). A null {@code kit} or {@code checker}
	 * is never passed by the game: it is a unit test's "not under test", and that pin is not compared.
	 */
	public record Env(String minecraft, String architect, Set<String> features, boolean fixtures, boolean measuring, @Nullable String kit,
		@Nullable String checker) {
		public Env {
			features = features == null ? Set.of() : Set.copyOf(features);
		}

		/** Tests: the kit and checker not compared. */
		public Env(String minecraft, String architect, Set<String> features, boolean fixtures, boolean measuring) {
			this(minecraft, architect, features, fixtures, measuring, null, null);
		}

		/** A player's game: no fixtures, no measuring (tests: the kit and checker not compared). */
		public Env(String minecraft, String architect, Set<String> features) {
			this(minecraft, architect, features, false, false);
		}
	}

	/** The kit or checker version of an Architect that has not said it (no feature {@code kitVersion}, or no helper connected). */
	public static final String UNKNOWN_KIT = "none";

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
		if (catalog.blocked(id)) return Verdict.no(e.orElse(null), Why.INVALID, "The catalog has a row of " + id + " it could not load: " + String.join("; ", catalog.problems()));
		if (e.isEmpty()) return Verdict.no(null, Why.UNKNOWN, "No module " + id + " in the catalog.");
		return offer(e.get(), env);
	}

	/** The verdict on one catalog version. */
	public static Verdict offer(Entry e, Env env) {
		if (!e.valid()) return Verdict.no(e, Why.INVALID, e.key() + " is not a valid catalog entry: " + String.join("; ", e.problems()));
		if (e.fixture() && !env.fixtures()) return Verdict.no(e, Why.FIXTURE, e.key() + " is a test fixture, never offered.");
		if (!env.features().contains(FEATURE)) return Verdict.no(e, Why.FEATURE, "Architect's helper does not run given programs (" + FEATURE + ").");
		if (env.measuring()) return new Verdict(e, null, e.key() + " may be installed to measure it.");
		var m = e.measured();
		if (m == null || !e.uncertified().isEmpty()) return Verdict.no(e, Why.NOT_MEASURED, e.key() + " is not certified: " + String.join("; ", e.uncertified()));
		if (!m.minecraft().equals(env.minecraft()))
			return Verdict.no(e, Why.MINECRAFT, e.key() + " was measured on Minecraft " + m.minecraft() + ", not " + env.minecraft() + ".");
		if (!m.architect().contains(env.architect()))
			return Verdict.no(e, Why.ARCHITECT, e.key() + " was measured with Architect " + String.join(", ", m.architect()) + ", not " + env.architect() + ".");
		var kit = kitMismatch(e, env);
		if (kit != null) return Verdict.no(e, Why.KIT, kit);
		return new Verdict(e, null, e.key() + " may be offered" + (m.smoke() ? " (its measurement on Architect " + m.from() + ", carried on a smoke check)." : "."));
	}

	/**
	 * (slice BCU) Why the running kit or checker is not the one module.json pins, or null when both match (a null in {@code env} is not compared).
	 * Measurement certifies the program run by that kit and checked by that checker, so any other kit is unmeasured.
	 */
	static @Nullable String kitMismatch(Entry e, Env env) {
		boolean kitOff = env.kit() != null && !env.kit().equals(e.kitPin());
		boolean checkerOff = env.checker() != null && !env.checker().equals(e.checkerPin());
		if (!kitOff && !checkerOff) return null;
		String running = "Architect runs kit " + said(env.kit()) + " and its helper checks with " + said(env.checker());
		String hint = !kitOff && UNKNOWN_KIT.equals(env.checker()) ? " (no helper connected yet?)"
			: !kitOff ? " (the helper is stale: it restarts to the bundled kit)" : "";
		return e.key() + " is pinned to kit " + e.kitPin() + " and checker " + e.checkerPin() + "; " + running + hint + ".";
	}

	private static String said(@Nullable String v) {
		return v == null ? "(not checked)" : UNKNOWN_KIT.equals(v) ? "(not said)" : v;
	}

	/** The dependent rule of a module's domain params, or why they break it; a module with domain params but no rule here is refused (fail closed). */
	static @Nullable String domainRule(String id, JsonObject values) {
		if (ModuleKind.StorageHall.ID.equals(id)) {
			var it = StorageHallLot.items(values);
			return it.ok() ? null : it.refused();
		}
		return "no rule judges " + id + "'s domain params";
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
			} else if (e.envelope().domains().containsKey(k)) {
				// (M4b) a domain param: any of its options (admitted above); the module's dependent rule is judged below
			} else if (!v.equals(p.getValue().def())) {
				return new Params(null, k + " is fixed at " + p.getValue().def() + " (not a measured branch)");
			}
			out.add(k, v.deepCopy());
		}
		// (M4b) a module with domain params judges them together (storage_hall: distinct filter items for its slots, none after); none known: refused
		if (!e.envelope().domains().isEmpty()) {
			String why = domainRule(e.id(), out);
			if (why != null) return new Params(null, why);
		}
		JsonObject sorted = Canon.sorted(out).getAsJsonObject();
		if (Canon.text(sorted).length() > ModuleCatalog.PROGRAM_PARAMS_MAX) return new Params(null, "params are over " + ModuleCatalog.PROGRAM_PARAMS_MAX + " characters");
		return new Params(sorted, null);
	}
}
