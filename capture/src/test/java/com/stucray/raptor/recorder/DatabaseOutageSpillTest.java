package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;

/**
 * The failure the spill exists for, injected: the database stops answering
 * mid-capture, and not one message is lost.
 *
 * <p><b>Paused, not stopped.</b> A stopped Postgres refuses connections
 * immediately and the writer gets a prompt {@code SQLException} — the easy half,
 * already covered by {@code RawWriteLoopTest}. A <em>paused</em> one is the case
 * that actually costs data: the socket stays open, the COPY neither returns nor
 * throws, and a driver left on its defaults waits forever. The writer then stops
 * draining, the queue fills, and the spill is never reached at all — a
 * recoverable outage turned into a lost match <em>with</em> the spill correctly
 * configured. That is why {@code raptor.datasource.socket-timeout} and
 * {@code connection-timeout} exist, and this is the test that would fail if
 * either were removed.
 *
 * <p>Capture liveness is decoupled from database liveness, and this is what that
 * claim means in practice: the read loop keeps framing throughout, the messages
 * land on disk, and they go into {@code raw} when the database comes back.
 */
@SpringBootTest(properties = {
		// Whole seconds: pgjdbc's socketTimeout has no finer resolution.
		"raptor.datasource.socket-timeout=2s",
		"raptor.datasource.connection-timeout=2s",
		// A whole capture in a batch or two, so the test spends seconds discovering
		// the outage rather than one socket timeout per thousand messages. The
		// production value is 1024.
		"raptor.recorder.batch-size=20000"})
@Import(TestcontainersConfiguration.class)
// The pause, the short timeouts and the evicted pool all stay in this context.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseOutageSpillTest {

	private static final Path SPILL_DIRECTORY = createSpillDirectory();

	@Autowired RecorderPipeline pipeline;
	@Autowired SpillReplayer replayer;
	@Autowired Clock clock;
	@Autowired @Acquisition JdbcClient jdbc;

	@DynamicPropertySource
	static void spillDirectory(DynamicPropertyRegistry registry) {
		registry.add("raptor.recorder.spill.directory", () -> SPILL_DIRECTORY.toString());
	}

	@Test
	@Timeout(300)
	void aWedgedDatabaseCostsNothing() throws Exception {
		Path capture = CaptureSampleFiles.file("1.900000001");
		int expected = CaptureSampleFiles.messageLines(capture).size();
		// Capture rows only: one database is shared by every context now (#118),
		// and the historic rows belong to another test class.

		long framed;
		long sessionId;
		// Start first: the session row is the recording's own provenance, and it
		// has to exist before the database it lives in stops answering.
		try (Recording recording = pipeline.start(
				new ReplayStreamSource(List.of(capture), clock), CaptureOrigin.MANUAL)) {
			sessionId = recording.sessionId();
			pause();
			try {
				recording.awaitSource();
				recording.close();
			} finally {
				unpause();
			}
			framed = recording.framed();
			assertThat(recording.written())
					.as("a wedged database must not be credited with writes")
					.isZero();
		}

		assertThat(framed)
				.as("the read loop keeps framing while the database is down")
				.isEqualTo(expected);
		assertThat(replayer.pending()).as("the book is on disk, not in memory").isNotEmpty();
		Awaitility.await("the pool discards the connections broken by the pause")
				.atMost(Duration.ofSeconds(60))
				.ignoreExceptions()
				.until(() -> jdbc.sql("select 1").query(Long.class).single() == 1);
		assertThat(rows(sessionId)).isZero();

		List<JobExecution> executions = replayer.replayPending();

		assertThat(executions).isNotEmpty();
		assertThat(executions).allSatisfy(execution ->
				assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED));
		assertThat(rows(sessionId))
				.as("every message framed during the outage must reach the system of record")
				.isEqualTo(framed);
		assertThat(replayer.pending()).isEmpty();

		Long spilledMessages = jdbc.sql("select coalesce(sum(messages), 0) from raw.spill_file")
				.query(Long.class).single();
		assertThat(spilledMessages).isEqualTo(framed);
	}

	private long rows(long sessionId) {
		return jdbc.sql("select count(*) from raw.stream_message where session_id = ?")
				.param(sessionId).query(Long.class).single();
	}

	private void pause() {
		DockerClientFactory.instance().client()
				.pauseContainerCmd(TestcontainersConfiguration.POSTGRES.getContainerId()).exec();
	}

	private void unpause() {
		if (Boolean.TRUE.equals(DockerClientFactory.instance().client()
				.inspectContainerCmd(TestcontainersConfiguration.POSTGRES.getContainerId()).exec().getState().getPaused())) {
			DockerClientFactory.instance().client()
					.unpauseContainerCmd(TestcontainersConfiguration.POSTGRES.getContainerId()).exec();
		}
	}

	private static Path createSpillDirectory() {
		try {
			return Files.createTempDirectory("paddock-spill-outage");
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
