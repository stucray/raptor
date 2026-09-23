package com.stucray.raptor.recorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The spill directory, and the three questions anyone asks of it: what is
 * waiting, where is a given file, and how much room is left.
 *
 * <p>One place, because the answers have to agree. A health check reporting an
 * empty directory while the replayer sees files in it — or either of them
 * disagreeing with the writer about which files are complete — would be a
 * monitoring failure at exactly the moment monitoring matters.
 */
@Component
class SpillDirectory {

	private static final Logger log = LoggerFactory.getLogger(SpillDirectory.class);

	/**
	 * Where a file that cannot be replayed is moved to (#271).
	 *
	 * <p>A subdirectory rather than a suffix, so that {@link #pending()} keeps
	 * excluding it by construction — the same reason {@code .partial} works —
	 * and so that "is anything stuck?" is one {@code ls}.
	 */
	static final String SET_ASIDE = "unreplayable";

	private final Path path;

	SpillDirectory(RecorderProperties properties) {
		this.path = properties.spill().directory();
	}

	Path path() {
		return path;
	}

	Path resolve(String name) {
		return path.resolve(name);
	}

	/**
	 * Files waiting to be replayed, oldest first.
	 *
	 * <p>{@code .partial} files are excluded by construction rather than by
	 * filtering: a batch is written under that suffix and renamed into place only
	 * once complete, so anything ending {@code .ndjson} is whole.
	 *
	 * <p>Oldest first is by the timestamp the writer puts in the name. Nothing
	 * downstream needs the order — rows carry {@code pt} and {@code seq} — but a
	 * replay that fails partway should have made the oldest data safe first,
	 * because it is the data closest to being lost to whatever goes wrong next.
	 */
	List<Path> pending() {
		if (!Files.isDirectory(path)) {
			return List.of();
		}
		try (var stream = Files.list(path)) {
			return stream.filter(file -> file.getFileName().toString().endsWith(".ndjson"))
					.sorted(Comparator.comparing(file -> file.getFileName().toString()))
					.toList();
		} catch (IOException e) {
			log.error("could not list the spill directory {}", path, e);
			return List.of();
		}
	}

	/**
	 * Move a file that cannot be replayed out of the drain's way, keeping it.
	 *
	 * <p><b>Moved, never deleted.</b> The messages in it are not in the system of
	 * record — that is the whole finding — so the file is the only copy there is.
	 * What this buys is that the drain stops spending its bounded pass on a file
	 * that can never succeed, and stops the files behind it accumulating at
	 * capture rate.
	 *
	 * @return whether it was moved; false means it is still in {@link #pending()}
	 */
	boolean setAside(Path file) {
		try {
			Path aside = path.resolve(SET_ASIDE);
			Files.createDirectories(aside);
			Files.move(file, aside.resolve(file.getFileName().toString()));
			return true;
		}
		catch (IOException e) {
			// Not fatal: the file stays pending and the drain goes on retrying it,
			// which is the behaviour this exists to end but is not worse than it.
			log.error("could not set aside unreplayable spill file {}", file, e);
			return false;
		}
	}

	/** Files moved out of the way because they could not be replayed (#271). */
	List<Path> setAside() {
		Path aside = path.resolve(SET_ASIDE);
		if (!Files.isDirectory(aside)) {
			return List.of();
		}
		try (var stream = Files.list(aside)) {
			return stream.filter(file -> file.getFileName().toString().endsWith(".ndjson"))
					.sorted(Comparator.comparing(file -> file.getFileName().toString()))
					.toList();
		}
		catch (IOException e) {
			log.error("could not list {}", aside, e);
			return List.of();
		}
	}

	/**
	 * When the oldest file still waiting was last written, or empty if nothing is
	 * waiting.
	 *
	 * <p>Modification time rather than the timestamp in the name: the name's
	 * timestamp is when the batch was <em>spilled</em>, and what health needs to
	 * know is how long it has been sitting here undrained. The two are the same
	 * number today, and they would stop being so the moment anything ever
	 * rewrote a file — which is exactly when a stale-file alarm would matter
	 * most.
	 */
	Optional<Instant> oldestPendingAt() {
		List<Path> pending = pending();
		if (pending.isEmpty()) {
			return Optional.empty();
		}
		Instant oldest = null;
		for (Path file : pending) {
			try {
				Instant at = Files.getLastModifiedTime(file).toInstant();
				if (oldest == null || at.isBefore(oldest)) {
					oldest = at;
				}
			} catch (IOException e) {
				// The file was drained out from under us between listing and
				// stat-ing, which is the drain working. Not a reason to report
				// anything.
				log.debug("could not stat spill file {}: {}", file, e.toString());
			}
		}
		return Optional.ofNullable(oldest);
	}

	/**
	 * Free space on the volume, measured on the nearest existing ancestor.
	 *
	 * <p>The directory itself does not exist until the first spill, and reporting
	 * zero free bytes for a system that has simply never failed would be exactly
	 * the wrong alarm.
	 */
	long freeBytes() {
		Path existing = path;
		while (existing != null && !Files.exists(existing)) {
			existing = existing.getParent();
		}
		if (existing == null) {
			return 0;
		}
		try {
			return Files.getFileStore(existing).getUsableSpace();
		} catch (IOException e) {
			log.error("could not measure free space at {}", existing, e);
			return 0;
		}
	}
}
