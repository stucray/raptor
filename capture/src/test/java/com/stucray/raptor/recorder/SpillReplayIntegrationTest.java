package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import com.stucray.raptor.rawstore.RawMessage;
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

	private static void clearSpillDirectory() throws IOException {
		try (var stream = Files.list(SPILL_DIRECTORY)) {
			for (Path path : stream.toList()) {
				Files.deleteIfExists(path);
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
