package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.unit.DataSize;

/**
 * Single-writer, against a real PostgreSQL — because the guarantee is
 * PostgreSQL's, not this code's.
 *
 * <p>Two recorders writing one capture is the failure the lease prevents, and it
 * used to be an OS guarantee: launchd will not start a second instance of a job.
 * Nothing in a resident service replaces that, so the property is rebuilt on a
 * session-level advisory lock and asserted here the only way it can be — with
 * two lease holders and one database.
 *
 * <p>The test that matters most is the last one. The reason for a
 * <em>session</em> lock rather than a table row is that PostgreSQL releases it
 * when the connection ends, so a {@code kill -9} frees it in seconds with
 * nothing left to clean up. That is a claim about the server's behaviour, and a
 * mock cannot make it true.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CaptureLeaseIntegrationTest {

	private static final long KEY = 8_090_902L;

	@Autowired @Acquisition DataSource dataSource;
	@Autowired @Acquisition JdbcClient jdbc;

	private final List<CaptureLease> leases = new java.util.ArrayList<>();

	@AfterEach
	void releaseEverything() {
		leases.forEach(CaptureLease::close);
	}

	@Test
	void onlyOneInstanceCanHoldTheLease() {
		CaptureLease first = lease();
		CaptureLease second = lease();

		assertThat(first.acquire()).isTrue();
		assertThat(second.acquire()).isFalse();

		assertThat(first.held()).isTrue();
		assertThat(second.held()).isFalse();
	}

	/** A standby instance takes over the moment the holder lets go. */
	@Test
	void theNextInstanceTakesOverWhenTheHolderReleases() {
		CaptureLease first = lease();
		CaptureLease second = lease();
		assertThat(first.acquire()).isTrue();
		assertThat(second.acquire()).isFalse();

		first.close();

		assertThat(second.acquire()).isTrue();
	}

	/** Asking twice is not a second lock, and must not leak a second connection. */
	@Test
	void acquiringTwiceIsTheSameLease() {
		CaptureLease lease = lease();

		assertThat(lease.acquire()).isTrue();
		assertThat(lease.acquire()).isTrue();

		lease.close();
		assertThat(lease.held()).isFalse();
	}

	/**
	 * The property the whole design rests on: a dead process frees the lease.
	 *
	 * <p>Terminating the backend from another connection is what a {@code kill -9}
	 * looks like from PostgreSQL's side — the session ends without anything having
	 * run any cleanup. If a lease survived that, single-writer would need a lease
	 * timeout, a heartbeat, and a decision about what to do when the holder is
	 * merely slow; it does not, and this is why.
	 */
	@Test
	void aLeaseDiesWithItsConnection() throws SQLException {
		CaptureLease crashed = lease();
		assertThat(crashed.acquire()).isTrue();

		terminateLeaseBackends();

		CaptureLease successor = lease();
		Awaitility.await().atMost(Duration.ofSeconds(10))
				.untilAsserted(() -> assertThat(successor.acquire()).isTrue());
		// And the crashed instance knows it is no longer the writer, rather than
		// going on believing it holds a lock the server has already given away.
		assertThat(crashed.held()).isFalse();
	}

	/**
	 * End every session holding this key, the way a killed process would.
	 *
	 * <p>Scoped to the lock rather than to the pool: terminating every backend of
	 * the acquisition identity would take the connection running this statement
	 * with it.
	 */
	private void terminateLeaseBackends() {
		jdbc.sql("""
						select pg_terminate_backend(l.pid)
						from pg_locks l
						where l.locktype = 'advisory' and l.objid = ? and l.pid <> pg_backend_pid()""")
				.param(((Long) KEY).intValue())
				.query(Boolean.class)
				.list();
	}

	private CaptureLease lease() {
		CaptureLease lease = new CaptureLease(dataSource, properties());
		leases.add(lease);
		return lease;
	}

	private static RecorderProperties properties() {
		return new RecorderProperties(true, 50_000, 1024, Duration.ofMillis(200),
				Duration.ofSeconds(5), Duration.ofSeconds(60), KEY, 3, Duration.ofMinutes(10),
				new RecorderProperties.Spill(false, java.nio.file.Path.of("target/unused"),
						DataSize.ofGigabytes(1), Duration.ofMinutes(1), 200,
						Duration.ofMinutes(15), 3),
				new RecorderProperties.Watchdog(Duration.ofSeconds(10), Duration.ofSeconds(30),
						Duration.ofSeconds(5)));
	}
}
