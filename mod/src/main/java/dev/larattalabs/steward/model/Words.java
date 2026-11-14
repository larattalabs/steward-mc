package dev.larattalabs.steward.model;

/** Counted nouns in what Steward says: "1 site", "3 sites", never "1 sites". Pure; the one place server text makes a plural. */
public final class Words {
	private Words() {
	}

	/** {@code n} and the noun for it: {@code one} for exactly 1 (and -1), {@code many} otherwise (0 sites, 2 sites). */
	public static String count(long n, String one, String many) {
		return n + " " + (Math.abs(n) == 1 ? one : many);
	}
}
