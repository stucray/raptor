package com.stucray.raptor.recorder;

import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.BlockingQueue;
import com.stucray.raptor.rawstore.RawMessage;

/**
 * One capture session in progress: two threads, a queue, and the row in
 * {@code raw.capture_session} that will outlive them.
 *
 * <p>Closing is the durable act. It stops the read loop, lets the writer commit
 * everything already accepted from the socket, and only then stamps
 * {@code ended_at} — so a session row with a null {@code ended_at} means exactly
 * what it should: the process did not get to finish, and there is a gap to
 * account for.
 */
public final class Recording implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(Recording.class);

	private final long sessionId;
	private final StreamSource source;
	private final StreamReadLoop readLoop;
	private final RawWriteLoop writeLoop;
	private final Thread readThread;
	private final Thread writeThread;
	private final BlockingQueue<RawMessage> queue;
	private final CaptureSessions sessions;

	private boolean closed;

	Recording(long sessionId, StreamSource source, StreamReadLoop readLoop, RawWriteLoop writeLoop,
			Thread readThread, Thread writeThread, BlockingQueue<RawMessage> queue,
			CaptureSessions sessions) {
		this.sessionId = sessionId;
		this.source = source;
		this.readLoop = readLoop;
		this.writeLoop = writeLoop;
		this.readThread = readThread;
		this.writeThread = writeThread;
		this.queue = queue;
		this.sessions = sessions;
	}

	public long sessionId() {
		return sessionId;
	}

	/** Wait for the source to run out. A live socket never does; a replay does. */
	public void awaitSource() throws InterruptedException {
		readThread.join();
	}

	/**
	 * Stop reading, and return without waiting.
	 *
	 * <p>What the watchdog calls when a stream has gone quiet. It must not block:
	 * the scheduled thread that noticed the silence is also the one that will
	 * notice if this teardown itself gets stuck, and a socket close can take as
	 * long as it likes. The supervisor's thread does the waiting, in
	 * {@link #close()}, where the order that protects already-framed messages is
	 * written down.
	 */
	public void requestStop() {
		readLoop.stop();
	}

	/** When a frame last arrived — the watchdog's evidence that the stream lives. */
	public Instant lastFrameAt() {
		return readLoop.lastFrameAt();
	}

	/** Messages framed off the stream, whether or not they reached the database. */
	public long framed() {
		return readLoop.framed();
	}

	/** Messages committed to {@code raw.stream_message}. */
	public long written() {
		return writeLoop.written();
	}

	/** COPY batches committed. */
	public long batches() {
		return writeLoop.batches();
	}

	int queueDepth() {
		return queue.size();
	}

	/**
	 * Stop reading, drain what was read, and close the session.
	 *
	 * <p>Order matters and is the whole method: read loop first, then the writer,
	 * which exits only once the queue is empty. Stopping the writer first would
	 * discard messages already taken off the socket — data that exists nowhere
	 * else.
	 */
	@Override
	public synchronized void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		readLoop.stop();
		join(readThread);
		writeLoop.stop();
		join(writeThread);
		source.close();
		endSession();
	}

	/**
	 * Closing the session row is best-effort, and it has to be.
	 *
	 * <p>The database being down is precisely a situation in which a recording
	 * stops, so the act of recording that it stopped is one of the things that
	 * cannot be assumed to work. Throwing here would break shutdown after the
	 * messages were already safe on disk — failing the one part of the outage the
	 * spill had just handled correctly.
	 *
	 * <p>What survives is an honest ledger: a session with a null {@code ended_at}
	 * means the recorder did not get to say how it ended, whether because it was
	 * killed or because the database would not take the update. Either way there
	 * is a gap to account for, which is what a reader of that row needs to know.
	 */
	private void endSession() {
		try {
			sessions.end(sessionId, "COMPLETED",
					"framed=" + framed() + " written=" + written() + " batches=" + batches());
		} catch (RuntimeException e) {
			log.error("could not close capture session {}: it stays open, and its null ended_at "
					+ "is the record that this session did not finish cleanly", sessionId, e);
		}
	}

	private static void join(Thread thread) {
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
