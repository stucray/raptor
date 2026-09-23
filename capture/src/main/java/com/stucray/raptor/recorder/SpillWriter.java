package com.stucray.raptor.recorder;

import com.stucray.raptor.rawstore.RawMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a refused batch to disk so a database outage costs nothing.
 *
 * <p><b>One file per batch, written whole and renamed into place.</b> The
 * obvious alternative — one long file, appended to and rotated — needs a state
 * machine that has to be exactly right during an outage, which is the worst
 * moment to depend on state. Writing each batch to {@code .partial}, forcing it,
 * and then {@code ATOMIC_MOVE}-ing it to its final name means the replay job can
 * never see a file that is not complete, and it needs no way to ask whether one
 * is. A ten-minute outage at the live peak leaves a few thousand small files;
 * that is a cheap price for having no partial-file case at all.
 *
 * <p>Never throws. This is the fallback for exactly the situations where the
 * normal path has already failed, and an exception here would propagate into the
 * writer loop that called it — or worse, into the read loop draining the socket.
 * A failure to spill is a real loss and is logged as one.
 */
class SpillWriter implements SpillSink {

	private static final Logger log = LoggerFactory.getLogger(SpillWriter.class);

	private final Path directory;
	private final Clock clock;
	private final AtomicLong counter = new AtomicLong();
	private final MeterRegistry meters;

	SpillWriter(RecorderProperties properties, Clock clock, MeterRegistry meters) {
		this.directory = properties.spill().directory();
		this.clock = clock;
		this.meters = meters;
	}

	@Override
	public void spill(List<RawMessage> messages, SpillCause cause) {
		if (messages.isEmpty()) {
			return;
		}
		Instant spilledAt = clock.instant();
		String name = "spill-" + messages.getFirst().sessionId() + "-" + spilledAt.toEpochMilli()
				+ "-" + counter.incrementAndGet() + ".ndjson";
		try {
			write(name, SpillFile.render(messages, cause, spilledAt));
			count("raptor.recorder.messages.spilled",
					"Messages written to a spill file because the database would not take them",
					cause, messages.size());
			log.warn("spilled {} message(s) to {} ({})", messages.size(), name, cause);
		} catch (IOException | RuntimeException e) {
			count("raptor.recorder.messages.lost",
					"Messages that reached neither the database nor a spill file",
					cause, messages.size());
			log.error("LOST {} message(s) ({}): the spill file could not be written either. "
							+ "First market {} at pt {}",
					messages.size(), cause, messages.getFirst().marketId(), messages.getFirst().pt(), e);
		}
	}

	/** Meter ids are cached by the registry, so building one per batch is cheap. */
	private void count(String name, String description, SpillCause cause, int messages) {
		Counter.builder(name).description(description).tag("cause", cause.name())
				.register(meters).increment(messages);
	}

	private void write(String name, String contents) throws IOException {
		Files.createDirectories(directory);
		Path partial = directory.resolve(name + ".partial");
		try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE)) {
			channel.write(StandardCharsets.UTF_8.encode(contents));
			// force(true), not force(false): the rename that follows makes this file
			// visible to the replay job, and the point of the whole exercise is that
			// what it then reads survived the failure that caused the spill.
			channel.force(true);
		}
		Files.move(partial, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
	}
}
