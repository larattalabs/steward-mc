package dev.larattalabs.steward.util;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.model.SettlementStore;
import dev.larattalabs.steward.service.BuildStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** (slice STORE-FSYNC) The durable write: forced, renamed, the folder forced; a failure leaves the old file; a crash's leftover .tmp is harmless. */
class DurableWriteTest {
	@AfterEach
	void reset() {
		DurableWrite.restartForTest();
		DurableWrite.FAIL_NEXT.clear();
		DurableWrite.forcer = DurableWrite.REAL;
		DurableWrite.FAIL_DIR_FORCE_AFTER_RENAME.set(0);
		DurableWrite.dirOpenBestEffort = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
	}

	/** Records every force (and what the file held at each), optionally failing one. */
	static class Recorder implements DurableWrite.Forcer {
		final List<String> calls = new ArrayList<>();
		final Path target;
		boolean failFile;
		boolean failDir;

		Recorder(Path target) {
			this.target = target;
		}

		@Override
		public void file(FileChannel ch) throws IOException {
			calls.add("file:" + (Files.exists(target) ? read(target) : "-"));
			if (failFile) throw new IOException("disk full (test)");
			ch.force(true);
		}

		@Override
		public void dir(Path dir) throws IOException {
			calls.add("dir:" + dir.getFileName() + ":" + (Files.exists(target) ? read(target) : "-"));
			if (failDir) throw new IOException("EIO (test)");
			DurableWrite.forceDir(dir);
		}
	}

	/** {@code dir} written to once already in this process (its chain settled, review P2-3): what follows shows only a write's own forces. */
	static void settled(Path dir) throws IOException {
		DurableWrite.writeString(dir.resolve("settled"), "");
	}

	static String read(Path p) {
		try {
			return Files.readString(p, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	void theTargetHoldsTheNewContentForcedBeforeTheRenameAndTheFolderAfter(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("store.json");
		Files.writeString(f, "old");
		var r = new Recorder(f);
		settled(dir);
		DurableWrite.forcer = r;
		DurableWrite.writeString(f, "new ✓");
		assertEquals("new ✓", read(f));
		assertFalse(Files.exists(dir.resolve("store.json.tmp")));
		// the file forced while the old one still stood; the folder forced once the new one was in place
		assertEquals(List.of("file:old", "dir:" + dir.getFileName() + ":new ✓"), r.calls);
	}

	@Test
	void theRealForcesRunOnThisPlatform(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("a.json");
		DurableWrite.writeString(f, "{}");
		DurableWrite.writeString(f, "{\"x\":1}");
		assertEquals("{\"x\":1}", read(f));
		DurableWrite.forceDir(dir);
	}

	@Test
	void aFailureBeforeTheRenameLeavesTheOldFileAndNoTmp(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("store.json");
		Files.writeString(f, "old");
		var r = new Recorder(f);
		r.failFile = true;
		settled(dir);
		DurableWrite.forcer = r;
		IOException e = assertThrows(IOException.class, () -> DurableWrite.writeString(f, "new"));
		assertEquals("disk full (test)", e.getMessage());
		assertEquals("old", read(f));
		assertFalse(Files.exists(dir.resolve("store.json.tmp")));
		// nothing renamed, so no folder force
		assertEquals(List.of("file:old"), r.calls);
	}

	@Test
	void aFolderForceFailureReachesTheCaller(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("store.json");
		Files.writeString(f, "old");
		var r = new Recorder(f);
		r.failDir = true;
		settled(dir);
		DurableWrite.forcer = r;
		// the rename happened (in doubt across a power loss): the caller is told the save failed
		assertThrows(IOException.class, () -> DurableWrite.writeString(f, "new"));
		assertEquals("new", read(f));
		assertFalse(Files.exists(dir.resolve("store.json.tmp")));
	}

	/**
	 * (batch-076 review P1-1) A known platform limit, not a guarantee: on Windows a folder can't be opened to force, so the rename is best effort
	 * there (a host crash may undo the last save), as in Architect's DurableFile. Failing closed would fail every save on Windows.
	 */
	@Test
	void aFolderThatCantBeOpenedThrowsElsewhereAndIsAKnownBestEffortLimitOnWindows(@TempDir Path dir) throws Exception {
		Path missing = dir.resolve("gone");
		DurableWrite.dirOpenBestEffort = false;
		assertThrows(IOException.class, () -> DurableWrite.forceDir(missing));
		DurableWrite.dirOpenBestEffort = true;
		DurableWrite.forceDir(missing); // logged once, carried on
	}

	@Test
	void aLeftoverTmpFromAnEarlierCrashIsWrittenOver(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("store.json");
		Files.writeString(f, "old");
		// longer than the new content: a truncate, not an overwrite in place
		Files.writeString(dir.resolve("store.json.tmp"), "{ half a store from a crash ...................................");
		DurableWrite.writeString(f, "new");
		assertEquals("new", read(f));
		assertFalse(Files.exists(dir.resolve("store.json.tmp")));
	}

	@Test
	void missingFoldersAreMadeAndEachNewEntryForced(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("a").resolve("b").resolve("store.json");
		var r = new Recorder(f);
		DurableWrite.forcer = r;
		DurableWrite.writeString(f, "x");
		assertEquals("x", read(f));
		// b's entry in a, a's in the temp dir, before the write (then b's again: the first write to b settles its chain, review P2-3); then b once the
		// file is renamed in
		assertEquals(List.of("dir:a:-", "dir:" + dir.getFileName() + ":-", "dir:a:-", "file:-", "dir:b:x"), r.calls);
	}

	@Test
	void aCopyIsByteForByteAndDurable(@TempDir Path dir) throws Exception {
		Path from = dir.resolve("s.json");
		byte[] bytes = {0x7b, 0x0a, (byte) 0xe2, (byte) 0x9c, (byte) 0x93, 0x7d, 0x0d, 0x0a};
		Files.write(from, bytes);
		var r = new Recorder(dir.resolve("s.json.v3.bak"));
		settled(dir);
		DurableWrite.forcer = r;
		DurableWrite.copy(from, dir.resolve("s.json.v3.bak"));
		assertArrayEquals(bytes, Files.readAllBytes(dir.resolve("s.json.v3.bak")));
		assertEquals(2, r.calls.size());
	}

	// the stores themselves

	@Test
	void theSettlementStoreLoadsAndSavesPastALeftoverTmp(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("steward-settlements.json");
		new SettlementStore().save(f);
		String saved = read(f);
		Files.writeString(dir.resolve("steward-settlements.json.tmp"), "{\"format\": 99, torn");
		SettlementStore s = SettlementStore.load(f);
		assertEquals(0, s.all().size());
		s.save(f);
		assertEquals(saved, read(f));
		assertFalse(Files.exists(dir.resolve("steward-settlements.json.tmp")));
	}

	@Test
	void theBuildStoreLoadsAndSavesPastALeftoverTmp(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("steward-builds.json");
		new BuildStore().save(f);
		String saved = read(f);
		Files.writeString(dir.resolve("steward-builds.json.tmp"), "{\"format\": 99, torn");
		BuildStore s = BuildStore.load(f);
		s.save(f);
		assertEquals(saved, read(f));
		assertFalse(Files.exists(dir.resolve("steward-builds.json.tmp")));
	}

	@Test
	void aStoreSaveThatFailsBeforeTheRenameThrowsAndKeepsTheOldFile(@TempDir Path dir) throws Exception {
		Path sf = dir.resolve("steward-settlements.json");
		Path bf = dir.resolve("steward-builds.json");
		Files.writeString(sf, "settlements before");
		Files.writeString(bf, "builds before");
		var r = new Recorder(sf);
		r.failFile = true;
		DurableWrite.forcer = r;
		assertThrows(IOException.class, () -> new SettlementStore().save(sf));
		assertThrows(IOException.class, () -> new BuildStore().save(bf));
		assertEquals("settlements before", read(sf));
		assertEquals("builds before", read(bf));
		try (var ls = Files.list(dir)) {
			assertEquals(List.of("steward-builds.json", "steward-settlements.json"), ls.map(p -> p.getFileName().toString()).sorted().toList());
		}
	}

	// review fixes

	@Test
	void anUnsupportedFolderOpenThrowsOffWindows() {
		DurableWrite.dirOpenBestEffort = false;
		// a missing folder stands in: what matters is that only the Windows flag makes a failed open best effort
		assertThrows(IOException.class, () -> DurableWrite.forceDir(Path.of("/nonexistent-steward-durable-test")));
	}

	@Test
	void aFailedNewFolderForceIsOwedAndRetriedByTheNextWrite(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("a").resolve("b").resolve("store.json");
		var r = new Recorder(f) {
			int left = 1;

			@Override
			public void dir(Path d) throws IOException {
				calls.add("dir:" + d.getFileName());
				if (d.equals(dir) && left-- > 0) throw new IOException("EIO (test)");
				DurableWrite.forceDir(d);
			}
		};
		DurableWrite.forcer = r;
		assertThrows(IOException.class, () -> DurableWrite.writeString(f, "x"));
		// b's entry in a forced; a's entry in the temp dir owed
		assertEquals(java.util.Set.of(dir.resolve("a")), DurableWrite.unforcedDirs());
		assertFalse(Files.exists(f));
		r.calls.clear();
		DurableWrite.writeString(f, "x");
		assertEquals("x", read(f));
		assertTrue(DurableWrite.unforcedDirs().isEmpty());
		assertEquals(List.of("dir:" + dir.getFileName(), "dir:a", "file:-", "dir:b"), r.calls);
	}

	@Test
	void confirmForcesTheFileAndItsFolder(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("steward-world-id");
		Files.writeString(f, "abcdef012345\n");
		var r = new Recorder(f);
		settled(dir);
		DurableWrite.forcer = r;
		DurableWrite.confirm(f);
		assertEquals(List.of("file:abcdef012345\n", "dir:" + dir.getFileName() + ":abcdef012345\n"), r.calls);
		r.failDir = true;
		assertThrows(IOException.class, () -> DurableWrite.confirm(f));
		assertThrows(IOException.class, () -> DurableWrite.confirm(dir.resolve("missing")));
	}

	@Test
	void theTestHookFailsTheFolderForceAfterTheRename(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("s.json");
		Files.writeString(f, "old");
		DurableWrite.FAIL_DIR_FORCE_AFTER_RENAME.set(1);
		assertThrows(IOException.class, () -> DurableWrite.writeString(f, "new"));
		assertEquals("new", read(f));
		DurableWrite.writeString(f, "newer");
		assertEquals("newer", read(f));
	}

	@Test
	void aFolderAnEarlierProcessMadeButCouldNotForceIsForcedByTheNextProcessFirstWrite(@TempDir Path dir) throws Exception {
		// (batch-076 review P2-3) the store's root is dir; a/b is made by the first write, whose force of a's entry (in dir) fails
		Path f = dir.resolve("a").resolve("b").resolve("store.json");
		var r = new Recorder(f) {
			boolean failRoot = true;

			@Override
			public void dir(Path d) throws IOException {
				calls.add("dir:" + d.getFileName());
				if (d.equals(dir) && failRoot) throw new IOException("EIO (test)");
				DurableWrite.forceDir(d);
			}
		};
		DurableWrite.forcer = r;
		assertThrows(IOException.class, () -> DurableWrite.writeString(f, "x"));
		assertEquals(java.util.Set.of(dir.resolve("a")), DurableWrite.unforcedDirs());
		// the process ends with a's entry owed; the folders stay on disk
		DurableWrite.restartForTest();
		assertTrue(DurableWrite.unforcedDirs().isEmpty());
		assertTrue(Files.isDirectory(f.getParent()));
		r.failRoot = false;
		r.calls.clear();
		DurableWrite.write(f, "y".getBytes(StandardCharsets.UTF_8), dir);
		assertEquals("y", read(f));
		// the new process's first write forces b's entry (in a), a's (in dir) and dir's own, before anything is renamed in; then b
		assertEquals(List.of("dir:a", "dir:" + dir.getFileName(), "dir:" + dir.getParent().getFileName(), "file:-", "dir:b"), r.calls);
		// cached: the next write in this process forces only the folder
		r.calls.clear();
		DurableWrite.write(f, "z".getBytes(StandardCharsets.UTF_8), dir);
		assertEquals(List.of("file:y", "dir:b"), r.calls);
		// a force that fails while settling is retried by the next write, before its rename
		DurableWrite.restartForTest();
		r.failRoot = true;
		r.calls.clear();
		assertThrows(IOException.class, () -> DurableWrite.write(f, "w".getBytes(StandardCharsets.UTF_8), dir));
		assertEquals("z", read(f), "nothing renamed in");
		r.failRoot = false;
		DurableWrite.write(f, "w".getBytes(StandardCharsets.UTF_8), dir);
		assertEquals("w", read(f));
	}
}
