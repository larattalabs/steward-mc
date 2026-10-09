package dev.larattalabs.steward.gateway;

import dev.larattalabs.architect.api.ArchitectApi;
import java.util.Set;

/**
 * Steward's only door to Architect. Everything Steward needs from Architect goes through here, so a change in the API (this is
 * written against a draft contract) is fixed in one place. Pure checks live in {@link Status} so they are unit-testable.
 */
public final class ArchitectGateway {
	/** The Architect API major version Steward is written against. */
	public static final int REQUIRED_MAJOR = 1;
	/** The oldest minor version Steward runs on: it calls groups, batches, critique and delta apply (API 1.8.0 = Architect 0.11.0; fabric.mod.json says the same). */
	public static final int REQUIRED_MINOR = 8;
	/** Features Steward cannot work without (names from ArchitectApi.features()). */
	public static final Set<String> REQUIRED_FEATURES = Set.of("designs", "sites", "events");

	private ArchitectGateway() {
	}

	/** Reads the real API. Never throws: a missing or older Architect is reported in the status. */
	public static Status check() {
		try {
			ArchitectApi api = ArchitectApi.get();
			return Status.evaluate(ArchitectApi.VERSION, api.features());
		} catch (Throwable t) {
			return new Status(false, "unavailable", Set.of(), "Architect API not available: " + t);
		}
	}

	public record Status(boolean ok, String version, Set<String> features, String problem) {
		public static Status evaluate(String version, Set<String> features) {
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
