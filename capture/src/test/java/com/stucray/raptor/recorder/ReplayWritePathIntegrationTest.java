package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S5's acceptance, in miniature: capture bytes through the real write path into
 * a real PostgreSQL.
 *
 * <p>Nothing here is mocked below the stream source. The frames are the
 * synthetic sample's, held to the real wire's shape (#303); the queue is the
 * queue, the writer is the writer, and the assertions are counts and values taken
 * from the files rather than from the code that read them. The same test over the
 * whole real corpus is the {@code replay} profile; this one is what CI runs, where
 * no corpus exists.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReplayWritePathIntegrationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired RecorderPipeline pipeline;
	@Autowired Clock clock;
	// The owner's client: these assertions read `raw`, which the read identity is
	// refused on outright. That refusal is the boundary, not a test problem.
	@Autowired @Acquisition JdbcClient jdbc;

	private long sessionId;

	@BeforeEach
	void replayTheSample() throws Exception {
		try (Recording recording = pipeline.start(
				ReplayStreamSource.of(CaptureSampleFiles.ROOT, clock), CaptureOrigin.MANUAL)) {
			recording.awaitSource();
			sessionId = recording.sessionId();
		}
	}

	/** Every message line in every file is a row, and nothing else is. */
	@Test
	void everyMessageInTheCaptureLands() throws Exception {
		Long rows = jdbc.sql("select count(*) from raw.stream_message where session_id = ?")
				.param(sessionId).query(Long.class).single();

		assertThat(rows).isEqualTo(CaptureSampleFiles.totalMessageLines());

		for (Path file : CaptureSampleFiles.all()) {
			Long forMarket = jdbc.sql(
							"select count(*) from raw.stream_message where session_id = ? and market_id = ?")
					.params(sessionId, CaptureSampleFiles.marketId(file))
					.query(Long.class).single();
			assertThat(forMarket)
					.as("rows for %s", file.getFileName())
					.isEqualTo(CaptureSampleFiles.messageLines(file).size());
		}
	}

	/** A live capture is session-provenanced. The check constraint permits one. */
	@Test
	void everyRowCarriesSessionProvenanceAndNoFileProvenance() {
		Long wrong = jdbc.sql(
						"select count(*) from raw.stream_message where session_id is null or file_id is not null")
				.query(Long.class).single();

		assertThat(wrong).isZero();
	}

	/**
	 * {@code seq} is dense and starts at zero.
	 *
	 * <p>It is the total order under equal {@code pt} — a single publish time can
	 * carry many blocks — so a hole in it is a message that never arrived, and the
	 * projection has no other way to notice.
	 */
	@Test
	void theSequenceIsDense() {
		Long rows = jdbc.sql("select count(*) from raw.stream_message where session_id = ?")
				.param(sessionId).query(Long.class).single();
		Long distinct = jdbc.sql("select count(distinct seq) from raw.stream_message where session_id = ?")
				.param(sessionId).query(Long.class).single();
		Long max = jdbc.sql("select max(seq) from raw.stream_message where session_id = ?")
				.param(sessionId).query(Long.class).single();
		Long min = jdbc.sql("select min(seq) from raw.stream_message where session_id = ?")
				.param(sessionId).query(Long.class).single();

		assertThat(distinct).isEqualTo(rows);
		assertThat(min).isZero();
		assertThat(max).isEqualTo(rows - 1);
	}

	/**
	 * The instants stored are the instants in the file — both of them.
	 *
	 * <p>COPY parses a bare timestamp in the session's {@code TimeZone}, which
	 * pgjdbc takes from the JVM default, so a value rendered in UTC without an
	 * offset lands shifted by the developer's own offset with nothing in a row
	 * count or a digest to show it. S2 shipped that bug and S3 found it. This
	 * asserts both clocks, and the receipt clock is the one a replay could most
	 * easily destroy by restamping it with today's time.
	 */
	@Test
	void storesBothClocksExactlyAsTheCaptureRecordedThem() throws Exception {
		Path file = CaptureSampleFiles.file("1.900000003");
		List<Instant> expectedPt = readLongs(file, "pt");
		List<Instant> expectedReceived = readLongs(file, "recv_ms");

		List<Instant> storedPt = column(file, "pt");
		List<Instant> storedReceived = column(file, "received_at");

		assertThat(storedPt).isEqualTo(expectedPt);
		assertThat(storedReceived).isEqualTo(expectedReceived);
	}

	/** The payload is the block, verbatim — nothing on this path interprets it. */
	@Test
	void storesEachMarketChangeBlockUntouched() throws Exception {
		Path file = CaptureSampleFiles.file("1.900000003");
		List<JsonNode> expected = CaptureSampleFiles.messageLines(file).stream()
				.map(line -> MAPPER.readTree(line).get("mc"))
				.toList();

		List<JsonNode> stored = jdbc.sql("""
						select payload from raw.stream_message
						where session_id = ? and market_id = ? order by seq""")
				.params(sessionId, CaptureSampleFiles.marketId(file))
				.query((rs, rowNum) -> MAPPER.readTree(rs.getString("payload")))
				.list();

		assertThat(stored).isEqualTo(expected);
	}

	/**
	 * The session row is the ledger, and it is closed by the same act that commits
	 * the tail — so a null {@code ended_at} means exactly one thing: the process
	 * did not get to finish, and there is a gap to account for.
	 */
	@Test
	void recordsTheSession() {
		record Session(OffsetDateTime startedAt, OffsetDateTime endedAt, String origin,
				String exitStatus, String config, String buildVersion) {}

		Session session = jdbc.sql("""
						select started_at, ended_at, origin, exit_status, config_json::text as config,
						       build_version
						from raw.capture_session where id = ?""")
				.param(sessionId)
				.query((rs, rowNum) -> new Session(
						rs.getObject("started_at", OffsetDateTime.class),
						rs.getObject("ended_at", OffsetDateTime.class),
						rs.getString("origin"),
						rs.getString("exit_status"),
						rs.getString("config"),
						rs.getString("build_version")))
				.single();

		assertThat(session.endedAt()).isNotNull();
		assertThat(session.startedAt()).isBefore(session.endedAt());
		assertThat(session.origin()).isEqualTo("MANUAL");
		assertThat(session.exitStatus()).isEqualTo("COMPLETED");
		assertThat(session.buildVersion()).isNotBlank();
		assertThat(session.config()).contains("queueCapacity", "flushInterval", "replay of");
	}

	/** What the read loop framed is what the writer committed. Nothing spilled. */
	@Test
	void framedEqualsWritten() throws Exception {
		try (Recording recording = pipeline.start(
				new ReplayStreamSource(List.of(CaptureSampleFiles.file("1.900000001")), clock),
				CaptureOrigin.MANUAL)) {
			recording.awaitSource();
			recording.close();

			assertThat(recording.framed()).isEqualTo(recording.written());
			assertThat(recording.written())
					.isEqualTo(CaptureSampleFiles.messageLines(CaptureSampleFiles.file("1.900000001")).size());
		}
	}

	private List<Instant> column(Path file, String column) {
		return jdbc.sql("select " + column + " as value from raw.stream_message "
						+ "where session_id = ? and market_id = ? order by seq")
				.params(sessionId, CaptureSampleFiles.marketId(file))
				.query((rs, rowNum) -> rs.getObject("value", OffsetDateTime.class).toInstant())
				.list();
	}

	private static List<Instant> readLongs(Path file, String field) throws Exception {
		return CaptureSampleFiles.messageLines(file).stream()
				.map(line -> Instant.ofEpochMilli(MAPPER.readTree(line).get(field).asLong()))
				.toList();
	}
}
