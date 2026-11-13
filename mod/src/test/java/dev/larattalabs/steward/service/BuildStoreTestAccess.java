package dev.larattalabs.steward.service;

/** BuildStoreTest's sample build, for the other build-store tests. */
final class BuildStoreTestAccess {
	private BuildStoreTestAccess() {}

	static BuildStore.Saved saved() {
		try {
			var m = BuildStoreTest.class.getDeclaredMethod("saved");
			m.setAccessible(true);
			return (BuildStore.Saved) m.invoke(null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
