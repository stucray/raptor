package com.stucray.raptor.recorder;

import com.stucray.raptor.rawstore.RawMessage;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drains the stream source, frames what it reads, and hands it to the queue.
 *
 * <p>This is the thread the whole design is arranged around. It may not block on
 * anything whose progress depends on the database being healthy — hence
 * {@code offer()} and never {@code put()}. With {@code put()} on a bounded queue
 * and Postgres wedged, this loop blocks indefinitely, the OS receive buffer
 * fills, the TCP window closes and Betfair drops us as a slow consumer: the exact
 * loss the spill exists to prevent, reintroduced one layer up. Blocking briefly
 * on a queue that has capacity is not the same thing and is fine.
 *
 * <p>A virtual thread, correctly: it is overwhelmingly parked on a socket read,
 * and the framing it does between reads is small.
 */
final class StreamReadLoop implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(StreamReadLoop.class);

	/** How long a paused source waits for queue capacity before re-checking stop. */
	private static final long PAUSE_POLL_MILLIS = 100;

	private final StreamSource source;
	private final StreamFramer framer;
	private final BlockingQueue<RawMessage> queue;
	private final SpillSink spill;
	private final long sessionId;
	private final boolean paced;
	private final Clock clock;

	/**
	 * When a frame last arrived — the watchdog's only evidence that the stream is
	 * alive.
	 *
	 * <p>Set on every frame including the heartbeats, which is the point: Betfair
	 * sends one every five seconds precisely so that a quiet book and a dead
	 * socket can be told apart. Written here and read from another thread, so it
	 * is volatile; a stale read costs one watchdog interval and nothing else.
	 */
	private volatile Instant lastFrameAt;

	private final AtomicLong framed = new AtomicLong();
	private final AtomicLong seq = new AtomicLong();
	private volatile boolean running = true;
	private volatile boolean exhausted;

	StreamReadLoop(StreamSource source, StreamFramer framer, BlockingQueue<RawMessage> queue,
			SpillSink spill, long sessionId, Clock clock) {
		this.clock = clock;
		this.lastFrameAt = clock.instant();
		this.source = source;
		this.framer = framer;
		this.queue = queue;
		this.spill = spill;
		this.sessionId = sessionId;
		this.paced = source.canPause();
	}

	@Override
	public void run() {
		try {
			while (running) {
				StreamFrame frame = source.next();
				if (frame != null) {
					lastFrameAt = clock.instant();
				}
				if (frame == null) {
					exhausted = true;
					reportSkipped();
					return;
				}
				List<RawMessage> messages = framer.frame(frame, sessionId, seq.get());
				if (messages.isEmpty()) {
					continue;
				}
				seq.addAndGet(messages.size());
				framed.addAndGet(messages.size());
				enqueue(messages);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			exhausted = true;
		} catch (IOException e) {
			// A dead source is a disconnect, not a crash. Reconnection is the
			// supervisor's business; this loop's business is to stop cleanly and let
			// the writer drain what it already framed.
			log.warn("stream source failed ({}); read loop stopping", source.describe(), e);
			exhausted = true;
		}
	}

	/**
	 * Offer each message, and spill the ones the queue refuses.
	 *
	 * <p>Per message rather than per frame: a frame that half fits should have its
	 * first blocks stored normally, and only the remainder spilled. The spill is
	 * expensive to replay and this keeps it as small as the failure actually was.
	 *
	 * <p>A source that {@link StreamSource#canPause() can pause} waits instead —
	 * only ever a replay, never a socket. The invariant is unchanged: what must
	 * never happen is the <em>socket</em>-draining thread blocking on the
	 * database's health, and a source that can pause is by definition not one
	 * where waiting loses anything.
	 */
	private void enqueue(List<RawMessage> messages) throws InterruptedException {
		if (paced) {
			waitForCapacity(messages);
		} else {
			offerOrSpill(messages);
		}
	}

	/**
	 * A replay: wait for room, and spill only what is left when asked to stop.
	 *
	 * <p>Bounded waits in a loop rather than {@code put()}. An unbounded put with a
	 * writer that has stopped for good would park here where nothing — not
	 * {@code stop()}, not {@code close()} — could reach it, and a test that hangs
	 * teaches nothing. Waiting while there is a point in waiting, and giving up
	 * once the loop has been asked to stop, keeps shutdown terminating.
	 */
	private void waitForCapacity(List<RawMessage> messages) throws InterruptedException {
		int index = 0;
		while (index < messages.size()) {
			if (queue.offer(messages.get(index), PAUSE_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
				index++;
			} else if (!running) {
				spill.spill(messages.subList(index, messages.size()), SpillCause.QUEUE_FULL);
				return;
			}
		}
	}

	/** A socket: offer, and hand on whatever the queue refuses. */
	private void offerOrSpill(List<RawMessage> messages) {
		List<RawMessage> refused = null;
		for (RawMessage message : messages) {
			if (!queue.offer(message)) {
				if (refused == null) {
					refused = new ArrayList<>();
				}
				refused.add(message);
			}
		}
		if (refused != null) {
			spill.spill(refused, SpillCause.QUEUE_FULL);
		}
	}

	/**
	 * Frames the framer could not use, said once at the end rather than per frame.
	 *
	 * <p>Worth saying at all: on a socket these should be zero, and a non-zero
	 * count is evidence about the stream itself — a torn frame, a shape we do not
	 * yet handle — that would otherwise leave no trace anywhere, because the write
	 * path deliberately stores only what it could address.
	 */
	private void reportSkipped() {
		if (framer.unparseable() > 0 || framer.withoutAddress() > 0) {
			log.warn("session {}: {} unparseable frame(s), {} block(s) with no pt or id",
					sessionId, framer.unparseable(), framer.withoutAddress());
		}
	}

	/** Ask the loop to stop after the frame in flight. */
	void stop() {
		running = false;
	}

	/** Whether the source ran out, as opposed to the loop being stopped. */
	boolean exhausted() {
		return exhausted;
	}

	/**
	 * When a frame last arrived.
	 *
	 * <p>Starts at the loop's construction rather than at zero: a stream that has
	 * connected and not yet delivered has been silent for as long as it has
	 * existed, which is exactly what the watchdog should measure.
	 */
	Instant lastFrameAt() {
		return lastFrameAt;
	}

	/** Messages framed out of the stream, whether or not they reached the queue. */
	long framed() {
		return framed.get();
	}
}
