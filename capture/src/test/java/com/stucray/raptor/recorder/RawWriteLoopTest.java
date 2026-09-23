package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.rawstore.RawMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class RawWriteLoopTest {

	private final BlockingQueue<RawMessage> queue = new ArrayBlockingQueue<>(1000);
	private final RecordingWriter writer = new RecordingWriter();
	private final CollectingSpillSink spill = new CollectingSpillSink();
	private final CollectingQuarantine quarantine = new CollectingQuarantine();

	/**
	 * The batch ends on the timer, not on a full buffer.
	 *
	 * <p>This is the durability window and the whole reason the design is
	 * time-bounded first: three messages on a quiet book must not wait for a
	 * thousand more before they are safe.
	 */
	@Test
	void commitsAPartialBatchWhenTheWindowExpires() throws Exception {
		RawWriteLoop loop = loop(1024, Duration.ofMillis(50));
		Thread thread = Thread.ofPlatform().start(loop);

		for (int i = 0; i < 3; i++) {
			queue.put(message(i));
		}

		Awaitility.await().atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertThat(writer.written()).hasSize(3));
		loop.stop();
		thread.join();

		assertThat(writer.batches()).hasSize(1);
		assertThat(spill.messages()).isEmpty();
	}

	/** The batch size is a ceiling on memory, and it holds. */
	@Test
	void neverExceedsTheBatchSize() throws Exception {
		RawWriteLoop loop = loop(4, Duration.ofMillis(50));
		for (int i = 0; i < 10; i++) {
			queue.put(message(i));
		}
		loop.stop();
		loop.run();

		assertThat(writer.batches()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(4));
		assertThat(writer.written()).hasSize(10);
	}

	/**
	 * A stop drains what was already taken off the socket.
	 *
	 * <p>Messages in the queue exist nowhere else — they have left the stream and
	 * have not reached the database. Exiting on {@code stop()} with the queue
	 * non-empty would make an orderly shutdown lose the tail of a match, which is
	 * the loss this whole design is built to prevent.
	 */
	@Test
	void drainsTheQueueBeforeExiting() throws Exception {
		RawWriteLoop loop = loop(1024, Duration.ofMillis(20));
		for (int i = 0; i < 250; i++) {
			queue.put(message(i));
		}

		loop.stop();
		loop.run();

		assertThat(writer.written()).hasSize(250);
		assertThat(queue).isEmpty();
	}

	/**
	 * A refused write spills the batch intact, and does not retry in place.
	 *
	 * <p>Retrying would hold the queue open behind a database that may be down for
	 * ten minutes, which is exactly how a recoverable outage becomes a lost match.
	 */
	@Test
	void spillsABatchTheDatabaseRefuses() throws Exception {
		RawWriteLoop loop = loop(1024, Duration.ofMillis(20));
		writer.failNext(1);
		for (int i = 0; i < 5; i++) {
			queue.put(message(i));
		}

		loop.stop();
		loop.run();

		assertThat(writer.written()).isEmpty();
		assertThat(spill.causes()).containsExactly(SpillCause.DB_UNAVAILABLE);
		assertThat(spill.messages()).hasSize(5);
		assertThat(loop.written()).isZero();
	}

	/** And recovery is simply the next batch succeeding. */
	@Test
	void recoversOnTheNextBatch() throws Exception {
		RawWriteLoop loop = loop(2, Duration.ofMillis(20));
		writer.failNext(1);
		for (int i = 0; i < 6; i++) {
			queue.put(message(i));
		}

		loop.stop();
		loop.run();

		assertThat(spill.messages()).hasSize(2);
		assertThat(writer.written()).hasSize(4);
	}

	private RawWriteLoop loop(int batchSize, Duration flush) {
		return new RawWriteLoop(queue, writer, NoOpTransactions.template(), spill, quarantine,
				batchSize, flush.toNanos());
	}

	/**
	 * A row the server refuses is quarantined ALONE — its batch-mates are written.
	 *
	 * <p>The point of #271, and the reason the loop bisects. COPY is
	 * all-or-nothing, so one unacceptable row takes a batch of up to 1,024 down
	 * with it. Quarantining the batch would lose nothing (the rows are
	 * held whole) and would still be wrong: a thousand perfectly good messages
	 * would sit outside the system of record, invisible to every projection,
	 * until somebody replayed them by hand.
	 */
	@Test
	void quarantinesOnlyTheMessageTheDatabaseRefuses() throws Exception {
		RawWriteLoop loop = loop(1024, Duration.ofMillis(20));
		writer.refuse(m -> m.seq() == 3, "23503");
		for (int i = 0; i < 8; i++) {
			queue.put(message(i));
		}

		loop.stop();
		loop.run();

		assertThat(quarantine.messages()).extracting(RawMessage::seq).containsExactly(3L);
		assertThat(quarantine.states()).containsExactly("23503");
		assertThat(writer.written()).extracting(RawMessage::seq)
				.as("every message but the refused one reaches the system of record")
				.containsExactlyInAnyOrder(0L, 1L, 2L, 4L, 5L, 6L, 7L);
		assertThat(spill.messages())
				.as("a refusal is not an outage; nothing belongs on disk")
				.isEmpty();
	}

	/**
	 * And isolating it costs a handful of attempts, not one per message.
	 *
	 * <p>512 rather than the production batch size of 1,024 because this queue
	 * holds 1,000 and {@code put} blocks when it is full — with the loop not yet
	 * running, filling past the capacity deadlocks the test rather than failing
	 * it. The bound being asserted is logarithmic either way.
	 */
	@Test
	void findsTheRefusedMessageByHalving() throws Exception {
		RawWriteLoop loop = loop(512, Duration.ofMillis(20));
		writer.refuse(m -> m.seq() == 500, "23514");
		for (int i = 0; i < 512; i++) {
			queue.put(message(i));
		}

		loop.stop();
		loop.run();

		assertThat(quarantine.messages()).extracting(RawMessage::seq).containsExactly(500L);
		assertThat(writer.written()).hasSize(511);
		// Halving 512 down to the one bad row is 10 refused attempts: the batch
		// itself and one per split. The bound is what makes bisection worth having
		// over a row-by-row retry, which would be 512 round trips on the write
		// path — and the assertion is what would notice it degrading into one.
		assertThat(writer.refusals())
				.as("isolating one bad row in 512 must not cost 512 attempts")
				.isLessThanOrEqualTo(12);
	}

	/**
	 * An unavailable database still spills, whole. This is the guard on the
	 * classification's default: only SQLSTATE classes 22 and 23 are refusals, and
	 * everything else must keep the behaviour that absorbs a ten-minute outage.
	 */
	@Test
	void stillSpillsWhenTheDatabaseIsMerelyUnavailable() throws Exception {
		RawWriteLoop loop = loop(1024, Duration.ofMillis(20));
		writer.refuse(m -> true, "08006");
		for (int i = 0; i < 5; i++) {
			queue.put(message(i));
		}

		loop.stop();
		loop.run();

		assertThat(spill.messages()).hasSize(5);
		assertThat(quarantine.messages()).isEmpty();
		assertThat(writer.refusals())
				.as("an outage must not be bisected — that would be five attempts, not one")
				.isOne();
	}

	/** A resource failure is a retry that can work, so it spills too. */
	@Test
	void spillsOnDiskFullRatherThanQuarantining() throws Exception {
		RawWriteLoop loop = loop(1024, Duration.ofMillis(20));
		writer.refuse(m -> true, "53100");
		queue.put(message(0));

		loop.stop();
		loop.run();

		assertThat(spill.messages()).hasSize(1);
		assertThat(quarantine.messages()).isEmpty();
	}

	/**
	 * If the quarantine cannot take it either, it goes to the spill.
	 *
	 * <p>The old behaviour, deliberately: the drain will churn on it and #271's
	 * set-aside will eventually move it, which is worse than quarantining and far
	 * better than dropping a message from the system of record.
	 */
	@Test
	void fallsBackToTheSpillWhenTheQuarantineRefusesItToo() throws Exception {
		RawWriteLoop loop = loop(1024, Duration.ofMillis(20));
		writer.refuse(m -> m.seq() == 1, "23502");
		quarantine.refuseEverything();
		for (int i = 0; i < 4; i++) {
			queue.put(message(i));
		}

		loop.stop();
		loop.run();

		assertThat(quarantine.messages()).isEmpty();
		assertThat(spill.messages()).extracting(RawMessage::seq).containsExactly(1L);
		assertThat(writer.written()).extracting(RawMessage::seq).containsExactlyInAnyOrder(0L, 2L, 3L);
	}

	private static RawMessage message(int seq) {
		return new RawMessage(1L, null, "1.234", Instant.ofEpochMilli(1787490204789L + seq),
				Instant.ofEpochMilli(1787490204700L + seq), seq, "{\"id\":\"1.234\"}");
	}
}
