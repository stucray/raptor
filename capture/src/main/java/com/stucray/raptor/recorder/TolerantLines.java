package com.stucray.raptor.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import org.jspecify.annotations.Nullable;

/**
 * Reads newline-delimited text from a stream that may stop mid-way, keeping
 * everything decoded before it stopped.
 *
 * <p>{@code BufferedReader} does not do that, and the difference is not
 * academic: a capture whose recorder was killed ends without a gzip
 * end-of-stream marker, and the decoder fills an 8 KB buffer before returning
 * anything, so the stream fails with the partial data still inside it. S4 found
 * a 37-message capture reading as <b>zero</b> messages that way. Replay is
 * pointed at exactly those files, so the same care is required here.
 *
 * <p><b>Deliberately a second copy</b> of {@code paddock-wire}'s
 * {@code TolerantLineReader}, which this package may not import: nothing that
 * writes to the system of record may depend on the parsers. Twenty lines
 * duplicated is the price of that boundary, and it is cheap — the two have no
 * shared behaviour to drift apart on beyond "split on 0x0A", and
 * {@code WritePathIsolationTest} fails the build if the import ever appears.
 */
final class TolerantLines implements AutoCloseable {

	private static final int CHUNK = 16 * 1024;

	private final InputStream source;
	private final Deque<String> ready = new ArrayDeque<>();
	private final byte[] chunk = new byte[CHUNK];
	private StringBuilder partial = new StringBuilder();
	private boolean exhausted;
	private boolean truncated;

	TolerantLines(InputStream source) {
		this.source = source;
	}

	/** The next line, or null at the end of the stream. */
	@Nullable String readLine() {
		while (ready.isEmpty() && !exhausted) {
			fill();
		}
		return ready.poll();
	}

	/** Whether the stream ended abruptly rather than cleanly. */
	boolean truncated() {
		return truncated;
	}

	private void fill() {
		int read;
		try {
			read = source.read(chunk, 0, CHUNK);
		} catch (IOException e) {
			truncated = true;
			exhausted = true;
			flushPartial();
			return;
		}
		if (read < 0) {
			exhausted = true;
			flushPartial();
			return;
		}

		// Split on the newline BYTE before decoding: 0x0A cannot appear inside a
		// multi-byte UTF-8 sequence, so this cannot tear a character in half the
		// way decoding each chunk independently would.
		int start = 0;
		for (int i = 0; i < read; i++) {
			if (chunk[i] == '\n') {
				partial.append(new String(chunk, start, i - start, StandardCharsets.UTF_8));
				ready.add(partial.toString());
				partial = new StringBuilder();
				start = i + 1;
			}
		}
		if (start < read) {
			partial.append(new String(chunk, start, read - start, StandardCharsets.UTF_8));
		}
	}

	private void flushPartial() {
		if (!partial.isEmpty()) {
			ready.add(partial.toString());
			partial = new StringBuilder();
		}
	}

	@Override
	public void close() throws IOException {
		source.close();
	}
}
