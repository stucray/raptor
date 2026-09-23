package com.stucray.raptor.datasource;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The two database identities.
 *
 * @param url        JDBC url, shared by both identities — same database, two logins
 * @param owner      the identity that owns the acquisition schemas and writes them
 * @param read       the restricted identity the read side uses
 * @param socketTimeout how long a statement may wait on a socket that has gone
 *     quiet before the driver gives up
 * @param connectionTimeout how long a caller waits for a connection from the
 *     pool before being refused
 * @param maxPoolSize how many connections each of the two pools may hold
 */
@ConfigurationProperties("raptor.datasource")
record DataSourceProperties(String url, Credentials owner, Credentials read,
		@DefaultValue("30s") Duration socketTimeout,
		@DefaultValue("10s") Duration connectionTimeout,
		@DefaultValue("10") int maxPoolSize) {

	record Credentials(String username, String password) {}

	/**
	 * Hikari's own default, made addressable so the tests can shrink it (#118).
	 *
	 * <p>Every Spring test context builds both pools and none of them is ever
	 * closed — the test-context cache holds them for the life of the JVM — so at
	 * ten connections apiece a module's suite asks one PostgreSQL for more
	 * backends than its {@code max_connections} allows, and the context that
	 * happens to be next fails to start with "sorry, too many clients already".
	 * That only became visible once all the contexts shared one database instead
	 * of starting one container each, which is the whole point of the change.
	 */

	/**
	 * pgjdbc takes {@code socketTimeout} in whole seconds, and it is load-bearing
	 * on the write path.
	 *
	 * <p>Without it a COPY against a wedged connection — a paused server, a
	 * partitioned network — neither returns nor throws, so the writer stops
	 * draining and the recorder's spill is never reached: the failure it exists to
	 * absorb becomes the one it sleeps through. Postgres restarting cleanly gives
	 * an immediate {@code SQLException}; a hang gives nothing at all, and this is
	 * what turns the second case into the first.
	 */
	int socketTimeoutSeconds() {
		return Math.max(1, (int) socketTimeout.toSeconds());
	}

	/**
	 * Hikari's own default is 30 seconds, which is a long time to hold a batch of
	 * live book while the database is down.
	 *
	 * <p>Together with {@link #socketTimeout} this bounds how long the writer can
	 * be stuck before a refused batch reaches the spill — and the spill is only
	 * worth having if it is reached promptly. The other direction costs nothing
	 * here: this pool serves one writer thread and a handful of jobs, not a burst
	 * of web requests that a short timeout would start refusing.
	 */
	long connectionTimeoutMillis() {
		return Math.max(250, connectionTimeout.toMillis());
	}
}
