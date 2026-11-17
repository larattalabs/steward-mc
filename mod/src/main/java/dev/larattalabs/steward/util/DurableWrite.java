package dev.larattalabs.steward.util;

import dev.larattalabs.steward.Steward;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (slice STORE-FSYNC) One durable write for Steward's saves, as Architect's (RegionStore.write, DurableFile: PSV's queue writer):
 * <ol>
 * <li>the folder made when it is missing (and its own entry forced in its parent);</li>
 * <li>{@code <file>.tmp} written whole (a leftover from an earlier crash is truncated, never read) and forced ({@code FileChannel.force(true)});</li>
 * <li>renamed over the file atomically;</li>
 * <li>the folder forced, so the rename itself survives a power loss (a file's fsync does not persist its directory entry, Linux fsync(2)).</li>
 * </ol>
 * On macOS the JDK's {@code force} is {@code fcntl(F_FULLFSYNC)} (falling back to {@code fsync} on a non-local volume), so the data reaches the
 * disk, not only its cache.
 * <p>
 * Every failure reaches the caller as an {@link IOException}, so a failed save still stops what relied on it (persist-before-mutate). Before the
 * rename, the file on disk is the previous one and the temp file is removed. After it (the folder's force failed) the new file is in place but may
 * not survive a power loss: the caller treats the save as failed, and its next save writes the whole store again. A folder that can't be opened
 * for reading is best effort only on Windows (logged once); elsewhere that open's failure, an unsupported one included, is the force's.
 */
public final class DurableWrite {
	private DurableWrite() {
	}

	/** (TEST seam) The forces: a file's channel, a directory. */
	interface Forcer {
		void file(FileChannel ch) throws IOException;

		void dir(Path dir) throws IOException;
	}

	static final Forcer REAL = new Forcer() {
		@Override
		public void file(FileChannel ch) throws IOException {
			ch.force(true);
		}

		@Override
		public void dir(Path dir) throws IOException {
			forceDir(dir);
		}
	};

	/** (TEST) The forces every write uses; the units swap it to fail or record one. */
	static volatile Forcer forcer = REAL;
	/** (TEST, the store units elsewhere) The next writes whose folder force after the rename fails (an EIO): the new file in place, in doubt. */
	public static final java.util.concurrent.atomic.AtomicInteger FAIL_DIR_FORCE_AFTER_RENAME = new java.util.concurrent.atomic.AtomicInteger();

	/** (TEST) How one write fails: before its rename (the old file stays), or at the folder's force after it (the new file in place, in doubt). */
	public enum TestFail { NONE, BEFORE_RENAME, AFTER_RENAME }

	/**
	 * (TEST, the store units elsewhere) The next writes' failures in order, one taken per write ({@link TestFail#NONE}: that write as usual); checked
	 * before {@link #FAIL_DIR_FORCE_AFTER_RENAME}.
	 */
	public static final java.util.Queue<TestFail> FAIL_NEXT = new java.util.concurrent.ConcurrentLinkedQueue<>();
	/** (TEST) Whether a folder that can't be opened is best effort (Windows); the units set it. */
	static volatile boolean dirOpenBestEffort = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	private static volatile boolean dirUnsupportedLogged;
	/** Folders made by a write whose entry in their parent is not forced yet (a force failed): retried before every write (review P2). */
	private static final Set<Path> unforcedDirs = ConcurrentHashMap.newKeySet();
	/**
	 * (batch-076 review P2-3) Folders whose entries up to their store's root were forced by this process. An owed force ({@link #unforcedDirs}) is
	 * lost when the process ends, and the folder then exists, so it is never made again: the first write to a folder in each process forces its
	 * chain once (a few folder syncs), then this cache holds it.
	 */
	private static final Set<Path> settledDirs = ConcurrentHashMap.newKeySet();

	/** {@code text} (UTF-8) as {@code file}, durably; its store's root is its own folder (every store sits in the world's folder). */
	public static void writeString(Path file, String text) throws IOException {
		write(file, text.getBytes(StandardCharsets.UTF_8), null);
	}

	/** {@code data} as {@code file}, durably; its store's root is its own folder. */
	public static void write(Path file, byte[] data) throws IOException {
		write(file, data, null);
	}

	/**
	 * {@code data} as {@code file}, durably (the steps in the class comment). {@code root}: the store's root (the world's save folder), at or above the
	 * file's folder; every folder entry from the file's folder up to it, its own included, is forced once a process (null: the file's folder).
	 */
	public static void write(Path file, byte[] data, @org.jspecify.annotations.Nullable Path root) throws IOException {
		// (batch-076 fix-check P2) normalized: the world folder comes as world/., whose parent is the world itself
		Path abs = file.toAbsolutePath().normalize();
		Path dir = abs.getParent();
		ensureDir(dir);
		settle(dir, root);
		TestFail fail = FAIL_NEXT.poll();
		Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
		Forcer f = forcer;
		try {
			try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				ByteBuffer b = ByteBuffer.wrap(data);
				while (b.hasRemaining()) ch.write(b);
				f.file(ch);
			}
			if (fail == TestFail.BEFORE_RENAME) throw new IOException("the write failed before its rename (test)");
			Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			// before the rename: the file is the previous one; the temp file goes (it is never read as the store either way)
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException d) {
				e.addSuppressed(d);
			}
			throw e;
		}
		if (fail == TestFail.AFTER_RENAME) throw new IOException("the folder's force failed (test)");
		if (fail == null && FAIL_DIR_FORCE_AFTER_RENAME.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new IOException("the folder's force failed (test)");
		f.dir(dir);
	}

	/**
	 * {@code file} as it stands made durable: the file and its folder forced (a file a write renamed into place whose folder's force failed, or
	 * one an earlier process may have left only in the page cache). Before a value read back is trusted for something a power loss must not
	 * change (review P1: the world id).
	 */
	public static void confirm(Path file) throws IOException {
		// (batch-076 fix-check P2) normalized: the world folder comes as world/., whose parent is the world itself
		Path abs = file.toAbsolutePath().normalize();
		retryUnforcedDirs();
		settle(abs.getParent(), null);
		Forcer f = forcer;
		// opened for writing (never truncated): Windows' FlushFileBuffers needs write access, and the JDK drops its access-denied (fix-check P2)
		try (FileChannel ch = FileChannel.open(abs, StandardOpenOption.WRITE)) {
			f.file(ch);
		}
		f.dir(abs.getParent());
	}

	/** {@code from}'s bytes copied to {@code to}, durably (a migration's backup, taken before the old file is written over). */
	public static void copy(Path from, Path to) throws IOException {
		write(to, Files.readAllBytes(from));
	}

	/** The folder made when it is missing: each one made has its entry forced in its parent before anything is renamed into it. */
	private static void ensureDir(Path dir) throws IOException {
		retryUnforcedDirs();
		if (Files.isDirectory(dir)) return;
		Path top = dir;
		while (top.getParent() != null && !Files.isDirectory(top.getParent())) top = top.getParent();
		// each folder about to be made is owed its entry's force until that force is done (a failed one is retried by the next write)
		for (Path d = dir; d != null && !d.equals(top.getParent()); d = d.getParent()) {
			if (d.getParent() != null) unforcedDirs.add(d);
		}
		Files.createDirectories(dir);
		retryUnforcedDirs();
	}

	/**
	 * (batch-076 review P2-3) The first time this process writes into {@code dir}: each folder's entry in its parent forced, from {@code dir} up to
	 * {@code root} (its own entry included), so a folder an earlier process made and could not force is durable before anything is renamed into
	 * it. A failure throws (before the rename) and leaves it to the next write.
	 */
	private static void settle(Path dir, @org.jspecify.annotations.Nullable Path root) throws IOException {
		if (settledDirs.contains(dir)) return;
		Path stop = root == null ? dir : root.toAbsolutePath().normalize();
		if (!dir.startsWith(stop)) stop = dir;
		for (Path d = dir; d != null; d = d.getParent()) {
			if (d.getParent() != null) forcer.dir(d.getParent());
			if (d.equals(stop)) break;
		}
		settledDirs.add(dir);
	}

	/** (TEST) A new process: what this one owed and settled forgotten, as a restart forgets it (the folders on disk stay as they are). */
	static void restartForTest() {
		unforcedDirs.clear();
		settledDirs.clear();
	}

	/** Every owed folder entry forced, deepest first; a failure throws and keeps the rest owed. */
	private static void retryUnforcedDirs() throws IOException {
		if (unforcedDirs.isEmpty()) return;
		var owed = new java.util.ArrayList<>(unforcedDirs);
		owed.sort((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()));
		for (Path d : owed) {
			if (!Files.isDirectory(d)) {
				unforcedDirs.remove(d); // gone since: nothing to make durable
				continue;
			}
			forcer.dir(d.getParent());
			unforcedDirs.remove(d);
		}
	}

	/** (TEST) The owed folder entries. */
	static Set<Path> unforcedDirs() {
		return Set.copyOf(unforcedDirs);
	}

	/**
	 * A directory forced (its entries durable). On Windows a folder can't be opened for reading: best effort, logged once. Elsewhere an open that
	 * fails throws as a failed force does.
	 */
	static void forceDir(Path dir) throws IOException {
		FileChannel ch;
		try {
			ch = FileChannel.open(dir, StandardOpenOption.READ);
		} catch (IOException | UnsupportedOperationException e) {
			// (batch-076 review P1-1, a known platform limit, not a guarantee) Windows can't open a folder for reading, so a rename's folder entry is
			// not forced there and a host crash may undo the last save. Kept best effort on purpose: failing closed would fail every save on Windows,
			// and Architect's DurableFile has the same limit. Elsewhere a failed open is a failed force
			if (dirOpenBestEffort) {
				if (!dirUnsupportedLogged) {
					dirUnsupportedLogged = true;
					Steward.LOGGER.warn("Durable writes: the folder {} can't be synced on this platform ({}); renames are best effort", dir, e.toString());
				}
				return;
			}
			throw e instanceof IOException io ? io : new IOException(e);
		}
		try (ch) {
			ch.force(true);
		}
	}
}
