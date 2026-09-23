package com.stucray.raptor.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Replays capture files off disk as if they were arriving on the socket.
 *
 * <p>This is what makes S5 provable without Betfair. The 541 captures under the
 * custody root are real frames recorded from the real stream, so replaying them
 * through the real read loop, the real queue and the real writer exercises every
 * durability behaviour on genuine wire bytes — and the acceptance test can then
 * be a count against the files rather than against fixtures somebody wrote to
 * match what the code already did.
 *
 * <p>Frames are delivered as fast as the consumer takes them, deliberately. Real
 * time would make the fault-injection tests take hours and would prove less: a
 * writer that keeps up at replay speed keeps up at ~200 msg/s with three orders
 * of magnitude to spare.
 */
public final class ReplayStreamSource implements StreamSource {

	private final List<Path> files;
	private final Clock clock;
	private final Iterator<Path> remaining;

	private @Nullable InputStream open;
	private @Nullable TolerantLines lines;
	private @Nullable Path current;

	public ReplayStreamSource(List<Path> files, Clock clock) {
		this.files = List.copyOf(files);
		this.clock = clock;
		this.remaining = this.files.iterator();
	}

	/** Every {@code *.ndjson.gz} under a directory, in file-name order. */
	public static ReplayStreamSource of(Path directory, Clock clock) throws IOException {
		List<Path> found = new ArrayList<>();
		try (var stream = Files.list(directory)) {
			stream.filter(p -> p.getFileName().toString().endsWith(".ndjson.gz"))
					.sorted(Comparator.comparing(p -> p.getFileName().toString()))
					.forEach(found::add);
		}
		return new ReplayStreamSource(found, clock);
	}

	public List<Path> files() {
		return files;
	}

	/**
	 * True: this is a file, and the read loop may wait for queue capacity rather
	 * than spill. See {@link StreamSource#canPause()} for why that distinction is
	 * the difference between a meaningful replay and a stress test of the queue.
	 */
	@Override
	public boolean canPause() {
		return true;
	}

	@Override
	public String describe() {
		return "replay of " + files.size() + " capture file(s)";
	}

	@Override
	public @Nullable StreamFrame next() throws IOException {
		while (true) {
			if (lines == null && !advance()) {
				return null;
			}
			String line = requireLines().readLine();
			if (line == null) {
				closeCurrent();
				continue;
			}
			if (line.isBlank()) {
				continue;
			}
			// Our clock now, not the file's: `recv_ms` inside the frame is the
			// original recorder's receipt clock and the framer prefers it, so a
			// replayed message keeps the receipt time it really had. This stamp
			// only ever applies to a frame that carries none — which is what a
			// live frame looks like.
			return new StreamFrame(line, clock.instant());
		}
	}

	private boolean advance() throws IOException {
		if (!remaining.hasNext()) {
			return false;
		}
		current = remaining.next();
		open = Files.newInputStream(current);
		lines = new TolerantLines(new GZIPInputStream(open));
		return true;
	}

	private TolerantLines requireLines() {
		TolerantLines active = lines;
		if (active == null) {
			throw new IllegalStateException("no file open");
		}
		return active;
	}

	private void closeCurrent() throws IOException {
		if (lines != null) {
			lines.close();
			lines = null;
		}
		open = null;
		current = null;
	}

	@Override
	public void close() throws IOException {
		closeCurrent();
	}
}
