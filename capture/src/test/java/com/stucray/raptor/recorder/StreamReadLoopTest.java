package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.rawstore.RawMessage;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jspecify.annotations.Nullable;

class StreamReadLoopTest {

	private final CollectingSpillSink spill = new CollectingSpillSink();

	@Test
	void framesEveryMessageInOrderWithADenseSequence() throws Exception {
		BlockingQueue<RawMessage> queue = new ArrayBlockingQueue<>(100);
		StreamReadLoop loop = loop(source(frames(5)), queue);

		loop.run();

		assertThat(loop.exhausted()).isTrue();
		assertThat(loop.framed()).isEqualTo(5);
		assertThat(queue).extracting(RawMessage::seq).containsExactly(0L, 1L, 2L, 3L, 4L);
		assertThat(spill.messages()).isEmpty();
	}

	/**
	 * A full queue is refused, never waited on.
	 *
	 * <p>The invariant of the whole slice: the socket-draining thread must never
	 * block on anything whose progress depends on the database being healthy. With
	 * {@code put()} here and a wedged writer, this loop stops draining the socket,
	 * the receive buffer fills, the TCP window closes and Betfair drops us as a
	 * slow consumer — losing far more than the spill ever would. The timeout is the
	 * assertion: a blocking loop never finishes.
	 */
	@Test
	@Timeout(10)
	void spillsRatherThanBlockingWhenTheQueueIsFull() throws Exception {
		BlockingQueue<RawMessage> queue = new ArrayBlockingQueue<>(2);
		StreamReadLoop loop = loop(source(frames(10)), queue);

		loop.run();

		assertThat(queue).hasSize(2);
		assertThat(spill.messages()).hasSize(8);
		assertThat(spill.causes()).allMatch(cause -> cause == SpillCause.QUEUE_FULL);
		// Nothing is lost from the framing's point of view: everything read off the
		// stream is either queued or handed to the sink, and the two account for it.
		assertThat(queue.size() + spill.messages().size()).isEqualTo((int) loop.framed());
	}

	/**
	 * A replay waits for capacity instead of spilling.
	 *
	 * <p>A file is not a socket: it delivers as fast as the disk allows — some two
	 * hundred times the live peak — so without this the queue overflows because of
	 * the speed of the test rather than anything about durability, and the slice's
	 * one bar ("every message in the capture lands") cannot be met. The waiting is
	 * scoped to sources that declare they can pause; nothing live ever can.
	 */
	@Test
	@Timeout(10)
	void waitsForCapacityWhenTheSourceCanPause() throws Exception {
		BlockingQueue<RawMessage> queue = new ArrayBlockingQueue<>(2);
		StreamReadLoop loop = loop(new ListSource(frames(10), -1, true), queue);
		Thread thread = Thread.ofVirtual().start(loop);

		List<RawMessage> drained = new ArrayList<>();
		while (drained.size() < 10) {
			drained.add(queue.take());
		}
		thread.join();

		assertThat(spill.messages()).isEmpty();
		assertThat(drained).extracting(RawMessage::seq).containsExactly(
				0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
	}

	/** A dead source is a disconnect: stop cleanly, keep what was framed. */
	@Test
	@Timeout(10)
	void stopsCleanlyWhenTheSourceFails() throws Exception {
		BlockingQueue<RawMessage> queue = new ArrayBlockingQueue<>(100);
		StreamReadLoop loop = loop(failingAfter(3), queue);

		loop.run();

		assertThat(queue).hasSize(3);
		assertThat(loop.exhausted()).isTrue();
	}

	@Test
	@Timeout(10)
	void stopsWhenAsked() throws Exception {
		BlockingQueue<RawMessage> queue = new ArrayBlockingQueue<>(100);
		StreamReadLoop loop = loop(source(frames(1000)), queue);
		Thread thread = Thread.ofVirtual().start(loop);

		Thread.ofVirtual().start(loop::stop).join();
		thread.join(Duration.ofSeconds(5));

		assertThat(thread.isAlive()).isFalse();
	}

	private StreamReadLoop loop(StreamSource source, BlockingQueue<RawMessage> queue) {
		return new StreamReadLoop(source, new StreamFramer(), queue, spill, 42L, Clock.systemUTC());
	}

	private static List<String> frames(int count) {
		List<String> frames = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			frames.add("{\"pt\":" + (1787490204789L + i) + ",\"recv_ms\":" + (1787490204700L + i)
					+ ",\"mc\":{\"id\":\"1.234\",\"rc\":[]}}");
		}
		return frames;
	}

	private static StreamSource source(List<String> frames) {
		return new ListSource(frames, -1, false);
	}

	private static StreamSource failingAfter(int frames) {
		return new ListSource(frames(100), frames, false);
	}

	/** Frames from a list, optionally failing partway through like a dropped socket. */
	private static final class ListSource implements StreamSource {

		private final List<String> frames;
		private final int failAfter;
		private final boolean paced;
		private int index;

		ListSource(List<String> frames, int failAfter, boolean paced) {
			this.frames = frames;
			this.failAfter = failAfter;
			this.paced = paced;
		}

		@Override
		public boolean canPause() {
			return paced;
		}

		@Override
		public String describe() {
			return "list of " + frames.size() + " frame(s)";
		}

		@Override
		public @Nullable StreamFrame next() throws IOException {
			if (failAfter >= 0 && index == failAfter) {
				throw new IOException("connection reset (simulated)");
			}
			if (index >= frames.size()) {
				return null;
			}
			return new StreamFrame(frames.get(index++), Instant.ofEpochMilli(1787490204700L));
		}

		@Override
		public void close() {}
	}
}
