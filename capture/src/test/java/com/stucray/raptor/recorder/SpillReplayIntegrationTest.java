package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import com.stucray.raptor.rawstore.RawMessage;
import com.stucray.raptor.rawstore.RawWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * The spill's guarantee, end to end: a batch the database refused comes back
 * exactly once.
 *
 * <p>Exactly-once here is not a claim about the write — it is a claim about an
 * order of operations. The ledger row and the messages commit together; the file
 * is unlinked only afterwards. The interesting test is therefore not "does
 * replay work" but "what happens when a file that was already ingested is
 * replayed again", which is precisely the state a crash between those two acts
 * leaves behind.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SpillReplayIntegrationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Path SPILL_DIRECTORY = createSpillDirectory();

	@Autowired RecorderPipeline pipeline;
	@Autowired SpillSink spill;
	@Autowired SpillReplayer replayer;
	@Autowired SpillDrain drain;
	@Autowired Clock clock;
	@Autowired @Acquisition JdbcClient jdbc;
	@Autowired RawWriter writer;
	@Autowired SpillDirectory directory;

	@DynamicPropertySource
	static void spillDirectory(DynamicPropertyRegistry registry) {
		registry.add("raptor.recorder.spill.directory", () -> SPILL_DIRECTORY.toString());
	}

	private long sessionId;
	private List<RawMessage> spilled = List.of();

	@BeforeEach
	void spillABatch() throws Exception {
		clearSpillDirectory();

		// A real session row, because the ledger's session_id is a foreign key to
		// it: a spill file outlives the outage, and the session it belonged to has
		// to still be there when it lands.
		try (Recording recording = pipeline.start(
				new ReplayStreamSource(List.of(CaptureSampleFiles.file("1.900000004")), clock),
				CaptureOrigin.MANUAL)) {
			recording.awaitSource();
			sessionId = recording.sessionId();
		}
		// Not cleanup: the recording above exists only to make the session row, and
		// what it wrote has to go so the replay is what puts the spilled batch in
		// raw. `rows()` asserting zero before the replay is the point of the test.
		jdbc.sql("delete from raw.stream_message").update();

		spilled = framed(CaptureSampleFiles.file("1.900000003"), sessionId);
		spill.spill(spilled, SpillCause.DB_UNAVAILABLE);
	}

	@Test
	void replaysASpilledBatchIntoTheSystemOfRecord() throws Exception {
		assertThat(rows()).as("nothing is in raw until the replay runs").isZero();
		assertThat(replayer.pending()).hasSize(1);

		List<JobExecution> executions = replayer.replayPending();

		assertThat(executions).singleElement()
				.extracting(JobExecution::getStatus).isEqualTo(BatchStatus.COMPLETED);
		assertThat(rows()).isEqualTo(spilled.size());
		assertThat(replayer.pending()).as("an ingested file is unlinked").isEmpty();
	}

	/** The messages come back as themselves, not as an approximation of themselves. */
	@Test
	void thePayloadsAreTheOnesThatWereSpilled() throws Exception {
		replayer.replayPending();

		List<String> stored = jdbc.sql(
						"select payload from raw.stream_message where session_id = ? order by seq")
				.param(sessionId).query(String.class).list();

		assertThat(stored).hasSameSizeAs(spilled);
		for (int i = 0; i < stored.size(); i++) {
			assertThat(MAPPER.readTree(stored.get(i)))
					.isEqualTo(MAPPER.readTree(spilled.get(i).payload()));
		}
	}

	@Test
	void recordsTheFileInTheLedger() throws Exception {
		replayer.replayPending();

		record Ledger(String name, long sessionId, String cause, int messages, long bytes,
				OffsetDateTime spilledAt, OffsetDateTime ingestedAt) {}

		Ledger ledger = jdbc.sql("select * from raw.spill_file")
				.query((rs, rowNum) -> new Ledger(rs.getString("name"), rs.getLong("session_id"),
						rs.getString("cause"), rs.getInt("messages"), rs.getLong("bytes"),
						rs.getObject("spilled_at", OffsetDateTime.class),
						rs.getObject("ingested_at", OffsetDateTime.class)))
				.single();

		assertThat(ledger.name()).startsWith("spill-" + sessionId + "-").endsWith(".ndjson");
		assertThat(ledger.sessionId()).isEqualTo(sessionId);
		assertThat(ledger.cause()).isEqualTo("DB_UNAVAILABLE");
		assertThat(ledger.messages()).isEqualTo(spilled.size());
		assertThat(ledger.bytes()).isPositive();
		// The distance between these two is the length of the outage — the only
		// record that the system of record briefly was not one.
		assertThat(ledger.spilledAt()).isBeforeOrEqualTo(ledger.ingestedAt());
		// Measured, not defaulted: an ordinary replay found none already stored.
		assertThat(ledger()).containsExactly(spilled.size() + "/0");
	}

	/**
	 * Replaying an already-ingested file writes nothing and removes the file.
	 *
	 * <p>This is the exact state left by a crash between the commit and the
	 * unlink, and the only failure mode the design has to survive. It is caught by
	 * the ledger's unique key — not by deleting from {@code raw}, which is
	 * append-only and would have been the wrong way to buy idempotency.
	 */
	@Test
	void replayingAnAlreadyIngestedFileIsANoOp() throws Exception {
		Path file = replayer.pending().getFirst();
		byte[] contents = Files.readAllBytes(file);
		String name = file.getFileName().toString();

		replayer.replayPending();
		long afterFirst = rows();

		// The file survives the crash that the unlink never got to.
		Files.write(SPILL_DIRECTORY.resolve(name), contents);
		List<JobExecution> second = replayer.replayPending();

		assertThat(second).singleElement()
				.extracting(JobExecution::getStatus).isEqualTo(BatchStatus.COMPLETED);
		assertThat(rows()).as("a re-replayed file must not duplicate the book").isEqualTo(afterFirst);
		assertThat(jdbc.sql("select count(*) from raw.spill_file").query(Long.class).single())
				.isEqualTo(1);
		assertThat(replayer.pending()).isEmpty();
	}

	/**
	 * A spilled batch that had in fact been stored is ingested, not refused, and
	 * the ledger says so (#40).
	 *
	 * <p>The recorder spills when a write's outcome is unknown, and "unknown"
	 * includes a COMMIT that landed on the server while the reply was lost. Until
	 * #40 the session key refused the replay, and three drains later the file was
	 * set aside with an ERROR saying its messages were not captured. They were.
	 * Now nothing is written twice, the ledger counts what was already there, the
	 * file is removed, and nothing is set aside. Driven through the drain, because
	 * the false alarm was the drain's.
	 */
	@Test
	void aFileWhoseMessagesAreAlreadyStoredIsIngestedAndCounted() throws Exception {
		// The COPY that committed although the recorder never heard back.
		writer.write(spilled);
		assertThat(rows()).isEqualTo(spilled.size());

		for (int pass = 0; pass < 3; pass++) {
			drain.drainOnce(Integer.MAX_VALUE);
		}

		assertThat(rows()).as("nothing is written twice").isEqualTo(spilled.size());
		assertThat(ledger()).containsExactly(spilled.size() + "/" + spilled.size());
		assertThat(replayer.pending()).as("the file is removed").isEmpty();
		assertThat(directory.setAside()).as("and never set aside").isEmpty();
	}

	/**
	 * Part of a file stored, part not: only the absent messages are appended, and
	 * the ledger counts the rest (#40). A spill file can hold more than one batch,
	 * so the in-doubt batch may sit beside one that really did fail.
	 */
	@Test
	void onlyTheMessagesNotStoredAreAppended() throws Exception {
		int half = spilled.size() / 2;
		assertThat(half).as("the fixture has messages on both sides").isPositive();
		writer.write(spilled.subList(0, half));

		List<JobExecution> executions = replayer.replayPending();

		assertThat(executions).singleElement()
				.extracting(JobExecution::getStatus).isEqualTo(BatchStatus.COMPLETED);
		assertThat(rows()).isEqualTo(spilled.size());
		assertThat(ledger()).containsExactly(spilled.size() + "/" + half);
	}

	/**
	 * A stored message with the same session key and DIFFERENT content is not the
	 * same message, and the replay is refused whole: nothing appended, nothing
	 * ledgered, and the file kept. Skipping it would hide exactly what the key is
	 * there to catch.
	 */
	@Test
	void aStoredMessageWithTheSameKeyAndOtherContentRefusesTheReplay() throws Exception {
		RawMessage first = spilled.getFirst();
		writer.write(List.of(new RawMessage(first.sessionId(), first.fileId(), first.marketId(),
				first.pt(), first.receivedAt(), first.seq(), "{\"op\":\"mcm\",\"other\":true}",
				first.segmentType(), first.changeType())));

		List<JobExecution> executions = replayer.replayPending();

		assertThat(executions).singleElement()
				.extracting(JobExecution::getStatus).isEqualTo(BatchStatus.FAILED);
		assertThat(rows()).as("nothing appended").isOne();
		assertThat(ledger()).as("nothing ledgered").isEmpty();
		assertThat(replayer.pending()).as("never deleted without a commit").hasSize(1);
	}

	/** The comparison and the append must see one table, so there must be one transaction. */
	@Test
	void aReplayOutsideATransactionIsRefused() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.replay(spilled))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("transaction");
		assertThat(rows()).isZero();
	}

	/**
	 * The drain puts a spilled batch back without anybody asking.
	 *
	 * <p><b>The test that did not exist, which is why #167 did not.</b> Every
	 * assertion in this class used to drive {@link SpillReplayer} directly, so
	 * the suite proved the replay worked while nothing in the application ever
	 * invoked it — a spill file from session 45 sat unreplayed through four
	 * sessions with CI green throughout. A test that calls the collaborator
	 * itself can never catch a missing caller; this one goes through the
	 * component that production actually schedules.
	 */
	@Test
	void theDrainPutsASpilledBatchBackWithoutBeingAsked() throws Exception {
		assertThat(rows()).as("nothing is in raw until the drain runs").isZero();

		int drained = drain.drainOnce(Integer.MAX_VALUE);

		assertThat(drained).isEqualTo(1);
		assertThat(rows()).isEqualTo(spilled.size());
		assertThat(replayer.pending()).isEmpty();
	}

	/**
	 * A pass replays at most its limit, and leaves the rest for the next one.
	 *
	 * <p>The bound is what keeps a drain after a long outage from occupying its
	 * executor for minutes. Oldest first, so the data closest to being lost to
	 * whatever goes wrong next is the data made safe first.
	 */
	@Test
	void aBoundedPassLeavesTheRestForTheNextOne() throws Exception {
		spill.spill(framed(CaptureSampleFiles.file("1.900000004"), sessionId),
				SpillCause.DB_UNAVAILABLE);
		assertThat(replayer.pending()).hasSize(2);

		int drained = drain.drainOnce(1);

		assertThat(drained).isEqualTo(1);
		assertThat(replayer.pending()).as("the second file waits for the next pass").hasSize(1);
	}

	/** Each ledger row as "messages/already_present". */
	private List<String> ledger() {
		return jdbc.sql("select messages || '/' || coalesce(already_present::text, 'null') "
				+ "from raw.spill_file").query(String.class).list();
	}

	private long rows() {
		return jdbc.sql("select count(*) from raw.stream_message where session_id = ?")
				.param(sessionId).query(Long.class).single();
	}

	private static List<RawMessage> framed(Path capture, long sessionId) throws IOException {
		StreamFramer framer = new StreamFramer();
		List<RawMessage> messages = new ArrayList<>();
		for (String line : CaptureSampleFiles.messageLines(capture)) {
			messages.addAll(framer.frame(
					new StreamFrame(line, java.time.Instant.now()), sessionId, messages.size()));
		}
		return messages;
	}

	/**
	 * Everything under the directory, the set-aside subdirectory included. A test
	 * that fails by setting a file aside must fail alone, not take every later
	 * test's setup with it, which is what a flat delete did.
	 */
	private static void clearSpillDirectory() throws IOException {
		try (var stream = Files.walk(SPILL_DIRECTORY)) {
			for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
				if (!path.equals(SPILL_DIRECTORY)) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	private static Path createSpillDirectory() {
		try {
			return Files.createTempDirectory("paddock-spill-replay");
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
