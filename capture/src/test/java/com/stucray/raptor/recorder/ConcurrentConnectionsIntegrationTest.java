package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two connections write at once, each into its own session (#65).
 *
 * <p>Through the real write path into a real PostgreSQL, with both sessions
 * replaying the same sample at the same time — the hardest case, since every
 * market id and every {@code seq} value then appears in both. The key is
 * {@code (session_id, seq, market_id)}, so one session per connection is what
 * keeps them apart; two connections sharing a session would collide on it, and a
 * collision is quarantined, which takes good data out of the system of record.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("Two connections write at once, each into its own session")
class ConcurrentConnectionsIntegrationTest {

	@Autowired RecorderPipeline pipeline;
	@Autowired Clock clock;
	@Autowired @Acquisition JdbcClient jdbc;

	@Test
	void eachConnectionLandsEveryMessageInItsOwnSessionAndNothingIsQuarantined()
			throws Exception {
		long slotZero;
		long slotOne;
		try (Recording zero = pipeline.start(ReplayStreamSource.of(CaptureSampleFiles.ROOT, clock),
						CaptureOrigin.RESIDENT, 0);
				Recording one = pipeline.start(ReplayStreamSource.of(CaptureSampleFiles.ROOT, clock),
						CaptureOrigin.RESIDENT, 1)) {
			zero.awaitSource();
			one.awaitSource();
			slotZero = zero.sessionId();
			slotOne = one.sessionId();
		}

		assertThat(slotZero).isNotEqualTo(slotOne);
		assertThat(rows(slotZero)).isEqualTo(CaptureSampleFiles.totalMessageLines());
		assertThat(rows(slotOne)).isEqualTo(CaptureSampleFiles.totalMessageLines());
		assertThat(jdbc.sql("select count(*) from raw.rejected_message")
				.query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select connection_slot from raw.capture_session where id = ?")
				.param(slotZero).query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("select connection_slot from raw.capture_session where id = ?")
				.param(slotOne).query(Integer.class).single()).isEqualTo(1);
	}

	private long rows(long sessionId) {
		return jdbc.sql("select count(*) from raw.stream_message where session_id = ?")
				.param(sessionId).query(Long.class).single();
	}
}
