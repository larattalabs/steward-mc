package dev.larattalabs.steward.gateway;

import dev.larattalabs.architect.api.ArchitectApi;
import java.util.Set;

/**
 * Steward's only door to Architect. Everything Steward needs from Architect goes through here, so a change in the API (this is
 * written against a draft contract) is fixed in one place. Pure checks live in {@link Status} so they are unit-testable.
 */
public final class ArchitectGateway {
	/** The Architect API major version Steward is written against. A loaded API of another major is reported as not ok (Noah, 2026-11-13: an upper bound at the next major API). */
	public static final int REQUIRED_MAJOR = 1;
	/**
	 * The pinned API: the {@code ArchitectApi.VERSION} Steward was compiled against. javac inlines that constant into Steward's bytecode, so it names
	 * the pin ({@code architect_version} in gradle.properties), never the Architect that is loaded.
	 */
	public static final String PINNED_VERSION = ArchitectApi.VERSION;
	/**
	 * The oldest minor version Steward runs on: the pinned API's. A pin bump moves it with no edit here, as it moves fabric.mod.json's
	 * {@code architect_mc} range to the pinned release.
	 */
	public static final int REQUIRED_MINOR = minorOf(PINNED_VERSION);
	/** Features Steward cannot work without (names from ArchitectApi.features()). */
	public static final Set<String> REQUIRED_FEATURES = Set.of("designs", "sites", "events");

	private ArchitectGateway() {
	}

	/** Reads the real API. Never throws: a missing, older or other-major Architect is reported in the status (the startup log, /steward status); nothing else is gated on it. */
	public static Status check() {
		try {
			ArchitectApi api = ArchitectApi.get();
			// apiVersion() (TJ, API 1.58.0) is the loaded Architect's version; VERSION would be the inlined pin. fabric.mod.json's range
			// (>=0.65.0) keeps older Architects out, and one that slips in fails as unavailable (NoSuchMethodError).
			return Status.evaluate(api.apiVersion(), api.features());
		} catch (Throwable t) {
			return new Status(false, "unavailable", Set.of(), "Architect API not available: " + t);
		}
	}

	static int minorOf(String version) {
		return Integer.parseInt(version.split("\\.")[1]);
	}

	public record Status(boolean ok, String version, Set<String> features, String problem) {
		public static Status evaluate(String version, Set<String> features) {
			if (version == null) return new Status(false, "unknown", features, "the loaded Architect's API version can't be read");
			int major, minor;
			try {
				String[] parts = version.split("\\.");
				major = Integer.parseInt(parts[0]);
				minor = Integer.parseInt(parts[1]);
			} catch (RuntimeException e) {
				return new Status(false, version, features, "unreadable Architect API version: " + version);
			}
			if (major != REQUIRED_MAJOR) {
				return new Status(false, version, features, "Architect API " + version + " is not major version " + REQUIRED_MAJOR);
			}
			if (minor < REQUIRED_MINOR) {
				return new Status(false, version, features, "Architect API " + version + " is too old: Steward needs " + REQUIRED_MAJOR + "." + REQUIRED_MINOR + " or later (update Architect)");
			}
			for (String f : REQUIRED_FEATURES) {
				if (!features.contains(f)) return new Status(false, version, features, "Architect lacks the feature \"" + f + "\"");
			}
			return new Status(true, version, features, "");
		}

		public String summary() {
			return ok ? "Architect API " + version + " ok (" + features.size() + " features)" : problem;
		}
	}
}
