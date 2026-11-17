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
 * for reading is best effort only on Windows (logged once), as Architect's; elsewhere that open's failure is the force's.
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
	/** (TEST) Whether a folder that can't be opened is best effort (Windows); the units set it. */
	static volatile boolean dirOpenBestEffort = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	private static volatile boolean dirUnsupportedLogged;

	/** {@code text} (UTF-8) as {@code file}, durably. */
	public static void writeString(Path file, String text) throws IOException {
		write(file, text.getBytes(StandardCharsets.UTF_8));
	}

	/** {@code data} as {@code file}, durably (the steps in the class comment). */
	public static void write(Path file, byte[] data) throws IOException {
		Path abs = file.toAbsolutePath();
		Path dir = abs.getParent();
		ensureDir(dir);
		Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
		Forcer f = forcer;
		try {
			try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				ByteBuffer b = ByteBuffer.wrap(data);
				while (b.hasRemaining()) ch.write(b);
				f.file(ch);
			}
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
		f.dir(dir);
	}

	/** {@code from}'s bytes copied to {@code to}, durably (a migration's backup, taken before the old file is written over). */
	public static void copy(Path from, Path to) throws IOException {
		write(to, Files.readAllBytes(from));
	}

	/** The folder made when it is missing: each one made has its entry forced in its parent before anything is renamed into it. */
	private static void ensureDir(Path dir) throws IOException {
		if (Files.isDirectory(dir)) return;
		Path top = dir;
		while (top.getParent() != null && !Files.isDirectory(top.getParent())) top = top.getParent();
		Files.createDirectories(dir);
		// from the deepest new folder up to the one that already stood: each new entry forced in its parent
		for (Path d = dir; d != null && !d.equals(top.getParent()); d = d.getParent()) {
			if (d.getParent() != null) forcer.dir(d.getParent());
		}
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
			if (dirOpenBestEffort || e instanceof UnsupportedOperationException) {
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
