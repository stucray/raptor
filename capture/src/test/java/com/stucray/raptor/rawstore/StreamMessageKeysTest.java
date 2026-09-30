package com.stucray.raptor.rawstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The two keys that say what a stored message is (#33), through the real COPY
 * writer: the same thing cannot be stored twice, and nothing else is refused.
 *
 * <p>SQLSTATE 23505 is what {@code RawWriteLoop} classifies as a refusal, so a
 * duplicate on the live path is quarantined rather than spilled for ever.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("raw.stream_message refuses a message it already holds, and only that")
class StreamMessageKeysTest {

	private static final Instant PT = Instant.parse("2026-09-30T19:00:00Z");

	@Autowired RawWriter writer;
	@Autowired @Acquisition JdbcClient jdbc;

	private long session;
	private long file;

	@BeforeEach
	void provenance() {
		session = jdbc.sql("""
						insert into raw.capture_session (started_at, origin, config_json, build_version)
						values (now(), 'RESIDENT', '{}', 'test') returning id""")
				.query(Long.class).single();
		file = jdbc.sql("""
						insert into raw.historic_file (path, sha256, bytes, messages)
						values ('keys/1.1.bz2', '\\x00', 1, 1) returning id""")
				.query(Long.class).single();
	}

	@Test
	@DisplayName("a market's change in a session's message is stored once")
	void aDuplicateSessionKeyIsRefused() throws Exception {
		writer.write(List.of(live(1, "1.10")));

		assertThatThrownBy(() -> writer.write(List.of(live(1, "1.10"))))
				.isInstanceOfSatisfying(SQLException.class,
						e -> assertThat(e.getSQLState()).isEqualTo("23505"));
		assertThat(rows()).isOne();
	}

	@Test
	@DisplayName("a vendor file's message is stored once")
	void aDuplicateFileKeyIsRefused() throws Exception {
		writer.write(List.of(RawMessage.fromFile(file, "1.1", PT, 1, "{}")));

		assertThatThrownBy(() -> writer.write(List.of(RawMessage.fromFile(file, "1.1", PT, 1, "{}"))))
				.isInstanceOfSatisfying(SQLException.class,
						e -> assertThat(e.getSQLState()).isEqualTo("23505"));
		assertThat(rows()).isOne();
	}

	/**
	 * Imported sessions numbered {@code seq} per stream message, so one message
	 * that carried two markets is two rows with one {@code (session_id, seq)}.
	 * 209,009 such pairs are on live (#31); the key must admit them.
	 */
	@Test
	@DisplayName("one message carrying two markets is two rows")
	void twoMarketsInOneMessageAreBothStored() throws Exception {
		writer.write(List.of(live(1, "1.10"), live(1, "1.11")));

		assertThat(rows()).isEqualTo(2);
	}

	/**
	 * The keys bind only their own provenance: NULLs are distinct, so a session's
	 * seq 1 and a file's seq 1 never meet.
	 */
	@Test
	@DisplayName("a session and a vendor file may both have a message 1")
	void theKeysDoNotCrossProvenance() throws Exception {
		writer.write(List.of(live(1, "1.1"), RawMessage.fromFile(file, "1.1", PT, 1, "{}")));

		assertThat(rows()).isEqualTo(2);
	}

	private RawMessage live(long seq, String market) {
		return new RawMessage(session, null, market, PT, PT, seq, "{}");
	}

	private long rows() {
		return jdbc.sql("select count(*) from raw.stream_message").query(Long.class).single();
	}
}
