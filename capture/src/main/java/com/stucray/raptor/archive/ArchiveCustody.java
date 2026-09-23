package com.stucray.raptor.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The custody root, for the one source paddock now writes into.
 *
 * <p>Every other corpus under the custody root is written by something else and
 * read here; this one paddock fetches itself, which is what S9 changes. The
 * files still matter even though {@code raw.football_file} holds the same bytes:
 * the data repo's analysis scripts, its notebooks and the symlinks under
 * {@code football/data/} all resolve the archive from disk, and the parity gate
 * reads the CSVs directly.
 *
 * <p>Writes are atomic — a temporary file in the same directory, then a move.
 * A half-written CSV in custody is worse than no CSV: it parses, and what it
 * yields is a season with its last matches missing.
 */
@Component
class ArchiveCustody {

	/** {@code E0-2526.csv}, {@code SC0-9899.csv} — division and season, as the archive names them. */
	private static final Pattern FILE_NAME = Pattern.compile("([A-Z]+[0-9]?)-([0-9]{4})\\.csv");

	private final ArchiveProperties properties;

	ArchiveCustody(ArchiveProperties properties) {
		this.properties = properties;
	}

	Path root() {
		return properties.custodyRoot();
	}

	void write(ArchiveTarget target, byte[] content) throws IOException {
		Path destination = properties.fileIn(target);
		Files.createDirectories(destination.getParent());
		Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".part");
		try {
			Files.write(temporary, content);
			Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		}
		finally {
			Files.deleteIfExists(temporary);
		}
	}

	/** Every archive CSV already in custody, in stable path order. */
	List<Path> files() throws IOException {
		Path raw = properties.custodyRoot().resolve("raw");
		if (!Files.isDirectory(raw)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(raw)) {
			return walk.filter(Files::isRegularFile)
					.filter(p -> targetOf(p) != null)
					.sorted()
					.toList();
		}
	}

	/**
	 * Which division and season a file in custody is, or null if its name is not
	 * one the archive uses — a stray {@code .part} from an interrupted write, an
	 * editor's backup, anything a person left there.
	 */
	@Nullable ArchiveTarget targetOf(Path file) {
		Matcher matcher = FILE_NAME.matcher(file.getFileName().toString());
		return matcher.matches() ? new ArchiveTarget(matcher.group(1), matcher.group(2)) : null;
	}

	static byte[] sha256(byte[] content) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(content);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
