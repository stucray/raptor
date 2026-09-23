package com.stucray.raptor.recorder;

import com.stucray.raptor.datasource.Acquisition;
import com.stucray.raptor.rawstore.RawMessage;
import com.stucray.raptor.rawstore.RawWriter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds a running write path: source, read loop, queue, writer.
 *
 * <p>Package-private, like every other {@code @Component} here: the supervisor
 * that will drive it lives in this package, and nothing outside it has any
 * business starting a write path directly. When the ops API needs to, it gets a
 * narrow interface — the way {@code RawWriter} exposes exactly one method.
 *
 * <p>Assembling it here rather than as long-lived beans is what lets a session
 * be a real object with a start and an end — the threads, the queue and the
 * {@code raw.capture_session} row are created and destroyed together, so there
 * is no way to have one without the others.
 */
@Component
class RecorderPipeline {

	private static final Logger log = LoggerFactory.getLogger(RecorderPipeline.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final RawWriter writer;
	private final TransactionTemplate transactions;
	private final SpillSink spill;
	private final Quarantine quarantine;
	private final RecorderProperties properties;
	private final CaptureSessions sessions;
	private final Clock clock;

	/**
	 * The recording the meters read from.
	 *
	 * <p>One indirection rather than meters registered per session: a Micrometer
	 * meter id can only be bound once, so re-registering per session would silently
	 * keep reporting the <em>first</em> session's queue forever.
	 */
	private final AtomicReference<@Nullable Recording> active = new AtomicReference<>();

	RecorderPipeline(RawWriter writer,
			@Acquisition PlatformTransactionManager acquisitionTransactionManager,
			SpillSink spill, Quarantine quarantine, RecorderProperties properties,
			CaptureSessions sessions, Clock clock, MeterRegistry meters) {
		this.writer = writer;
		this.transactions = new TransactionTemplate(acquisitionTransactionManager);
		this.spill = spill;
		this.quarantine = quarantine;
		this.properties = properties;
		this.sessions = sessions;
		this.clock = clock;

		Gauge.builder("raptor.recorder.queue.depth", () -> read(Recording::queueDepth))
				.description("Messages accepted from the stream and not yet committed. "
						+ "Sustained growth is a stalled writer, which is what a wedged "
						+ "database looks like from the read loop.")
				.register(meters);
		Gauge.builder("raptor.recorder.messages.framed", () -> read(Recording::framed))
				.description("Messages framed off the stream in the current session")
				.register(meters);
		Gauge.builder("raptor.recorder.messages.written", () -> read(Recording::written))
				.description("Messages committed to the system of record in the current session")
				.register(meters);
	}

	/**
	 * Start recording from a source. The caller owns the returned handle and must
	 * close it; closing is what commits the tail and stamps the session's end.
	 */
	Recording start(StreamSource source, CaptureOrigin origin) {
		long sessionId = sessions.begin(origin, configJson(source), buildVersion());
		BlockingQueue<RawMessage> queue = new ArrayBlockingQueue<>(properties.queueCapacity());

		RawWriteLoop writeLoop = new RawWriteLoop(queue, writer, transactions, spill, quarantine,
				properties.batchSize(), properties.flushInterval().toNanos());
		StreamReadLoop readLoop =
				new StreamReadLoop(source, new StreamFramer(), queue, spill, sessionId, clock);

		// Platform, and named: the writer population is exactly one, permanently,
		// and a long CPU-bound run building a COPY buffer would otherwise occupy a
		// carrier shared with the read loop. Started before the reader so that no
		// message can sit in the queue with nothing draining it.
		Thread writeThread = Thread.ofPlatform().name("raw-writer-" + sessionId).start(writeLoop);
		Thread readThread =
				Thread.ofVirtual().name("stream-read-loop-" + sessionId).start(readLoop);

		Recording recording = new Recording(sessionId, source, readLoop, writeLoop,
				readThread, writeThread, queue, sessions);
		active.set(recording);
		log.info("capture session {} started: {}", sessionId, source.describe());
		return recording;
	}

	private String configJson(StreamSource source) {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("source", source.describe());
		config.put("queueCapacity", properties.queueCapacity());
		config.put("batchSize", properties.batchSize());
		config.put("flushInterval", properties.flushInterval().toString());
		return MAPPER.writeValueAsString(config);
	}

	/**
	 * The jar's version where there is one, {@code dev} otherwise — a session
	 * recorded by a developer's laptop should say so rather than claim a release.
	 */
	private static String buildVersion() {
		String version = RecorderPipeline.class.getPackage().getImplementationVersion();
		return version == null ? "dev" : version;
	}

	private double read(java.util.function.ToLongFunction<Recording> value) {
		Recording recording = active.get();
		return recording == null ? 0 : value.applyAsLong(recording);
	}
}
