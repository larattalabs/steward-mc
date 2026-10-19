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
	/**
	 * The oldest minor version Steward runs on: groups, batches, critique and delta apply (1.8.0), and the flat village with tagged lots, keyed plan and
	 * realise and removal tombstones (1.14.0), the settlement stockpile with its waiting status (1.17.0), pausing a construction target (1.19.0), the plan check (1.23.0), the player's own builds (1.24.0), adopting one as a site (1.26.0), a village street's orientation with a design's block count (1.30.0), a plan check that loads a tunnel's corridor (1.33.0), and contained water with its report (API 1.34.0 = Architect 0.37.0; fabric.mod.json says the same).
	 */
	public static final int REQUIRED_MINOR = 34;
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
