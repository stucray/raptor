package com.stucray.raptor.recorder;

import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * Where frames come from.
 *
 * <p>The interface is the whole reason the durability design is testable. The
 * write path behind it — batching, the bounded queue, the spill, the watchdog —
 * is the only part of this system where a bug destroys data permanently, and
 * with a source that replays real captures off disk every one of those
 * behaviours can be exercised deterministically in a test, with no Betfair
 * session, no credentials and no waiting for a Saturday.
 *
 * <p>Implementations are not thread-safe: exactly one read loop owns a source
 * for its lifetime.
 */
public interface StreamSource extends AutoCloseable {

	/** What this source is, for the session's config record and the logs. */
	String describe();

	/**
	 * Whether this source can be made to wait.
	 *
	 * <p>False for anything live, and that is the important direction. Frames off
	 * a socket arrive whether or not we are ready: pausing means not draining the
	 * receive buffer, which closes the TCP window and gets us dropped as a slow
	 * consumer. There is no backpressure to apply to Betfair, so the read loop
	 * offers and spills what the queue refuses.
	 *
	 * <p>True for a replay, because a file is not a socket. Left false, replay
	 * would deliver five and a half million messages as fast as the disk can
	 * decompress them — some two hundred times the live peak — and the queue would
	 * overflow purely because of the speed of the test. That measures the queue,
	 * not the write path, and it makes the one bar this slice has to clear
	 * ("every message in the capture lands") unmeetable for reasons that have
	 * nothing to do with durability. A paused replay is the faithful stand-in for
	 * a socket delivering at the rate a match actually produces.
	 *
	 * @return whether the read loop may block waiting for queue capacity
	 */
	default boolean canPause() {
		return false;
	}

	/**
	 * The next frame, or {@code null} when the source is exhausted.
	 *
	 * @throws IOException if the underlying stream fails; the read loop treats
	 *     that as a disconnect, not as a reason to stop the recorder
	 */
	@Nullable StreamFrame next() throws IOException;

	@Override
	void close() throws IOException;
}
