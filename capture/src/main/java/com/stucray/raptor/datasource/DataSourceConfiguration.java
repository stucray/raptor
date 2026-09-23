package com.stucray.raptor.datasource;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Two identities against one database, so the system-of-record boundary stays a
 * database guarantee after the two processes became one.
 *
 * <p>The two-process design got this for free: paddock's connection had no grant
 * on {@code raw}, so the read side <em>could not</em> read the system of record.
 * One application means one process that must both write {@code raw} and serve
 * {@code query}, and that enforcement evaporates unless it is rebuilt.
 *
 * <p>The restricted identity is {@link Primary} deliberately. The read side goes
 * on injecting the auto-configured {@code JdbcClient} and is unchanged; ingest
 * and projection ask for {@link Acquisition} explicitly. The default therefore
 * fails closed — code that forgets the qualifier is refused by PostgreSQL rather
 * than silently granted the ability to write the system of record.
 *
 * <p>"Read-only" is scoped to the acquisition schemas, not to the database. The
 * read side owns {@code public} outright and writes it constantly — the spool
 * importers, the bulk corpus import, the simulation lab's saved runs. What it
 * cannot do is touch {@code raw} at all, or write {@code query}.
 *
 * <p>Both Flyway instances and the Batch job repository run as the owner:
 * migrations create the objects, and the run ledger lives in {@code batch}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataSourceProperties.class)
class DataSourceConfiguration {

	/**
	 * The read side's identity: select on {@code query}, everything on
	 * {@code public}, nothing at all on {@code raw}.
	 *
	 * <p>The pool connects lazily, which matters on a fresh database: this role
	 * is created by the acquisition migrations, so it does not exist until they
	 * have run. Hikari builds its pool on first {@code getConnection()}, and by
	 * then it does.
	 */
	@Bean
	@Primary
	DataSource dataSource(DataSourceProperties properties) {
		return pool(properties, properties.read(), "paddock-read");
	}

	/**
	 * The owner: {@code raw}, {@code query} and {@code batch} belong to it.
	 *
	 * <p>{@code @FlywayDataSource} points Boot's auto-configured Flyway — the
	 * read side's own V1-V16 line against {@code public} — at this identity too.
	 * Migrations create tables, and the restricted identity is precisely the one
	 * that must not.
	 */
	@Bean
	@Acquisition
	@FlywayDataSource
	DataSource acquisitionDataSource(DataSourceProperties properties) {
		return pool(properties, properties.owner(), "raptor-capture");
	}

	/**
	 * The read side's {@code JdbcTemplate} and {@code JdbcClient}, declared rather
	 * than left to auto-configuration.
	 *
	 * <p>Both must be declared {@link Primary} explicitly, and the
	 * {@code JdbcClient} is the one that bites. Boot's
	 * {@code JdbcClientAutoConfiguration} is
	 * {@code @ConditionalOnSingleCandidate(JdbcTemplate.class)}: the acquisition
	 * template below makes two candidates, so with neither marked primary the
	 * condition fails, Boot contributes no {@code JdbcClient} at all, and every
	 * read-side injection silently resolves to the only one left — the owner's.
	 * The boundary is then gone with nothing to show for it: no ambiguity error,
	 * no failure, just the read side quietly running as the identity that can
	 * write {@code raw}. Caught by {@code SystemOfRecordBoundaryTest} refusing to
	 * see a refusal.
	 */
	@Bean
	@Primary
	JdbcTemplate jdbcTemplate(DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	@Bean
	@Primary
	JdbcClient jdbcClient(JdbcTemplate jdbcTemplate) {
		return JdbcClient.create(jdbcTemplate);
	}

	/**
	 * The read side's transaction manager, declared for the same reason as the
	 * client above and with a nastier failure if it isn't.
	 *
	 * <p>Boot's {@code DataSourceTransactionManagerAutoConfiguration} is
	 * {@code @ConditionalOnMissingBean(TransactionManager.class)}, so the
	 * acquisition manager below backs it off completely. The read side's
	 * {@code @Transactional} boundaries would then be managed on the <em>owner's</em>
	 * connection while its statements ran on the read pool — two connections, one
	 * of them holding a transaction the other never joins. Rollback silently stops
	 * rolling anything back: a failed import stays {@code RUNNING}, and a run row
	 * that should have been undone survives to collide with its retry. Three
	 * read-side tests caught exactly that.
	 *
	 * <p>{@code JdbcTransactionManager} rather than
	 * {@code DataSourceTransactionManager}, matching what Boot would have built
	 * with {@code spring.dao.exceptiontranslation.enabled} at its default.
	 */
	@Bean
	@Primary
	PlatformTransactionManager transactionManager(DataSource dataSource) {
		return new JdbcTransactionManager(dataSource);
	}

	/** Transactions on the owner. Named for {@code @EnableJdbcJobRepository}. */
	@Bean
	@Acquisition
	PlatformTransactionManager acquisitionTransactionManager(
			@Acquisition DataSource acquisitionDataSource) {
		return new JdbcTransactionManager(acquisitionDataSource);
	}

	/**
	 * The job repository's own {@code JdbcOperations}.
	 *
	 * <p>Needed explicitly: {@code @EnableJdbcJobRepository} defaults
	 * {@code jdbcOperationsRef} to the bean named {@code jdbcTemplate}, which is
	 * Boot's auto-configured one on the <em>primary</em> — the identity with no
	 * rights in {@code batch}.
	 */
	@Bean
	@Acquisition
	JdbcTemplate acquisitionJdbcTemplate(@Acquisition DataSource acquisitionDataSource) {
		return new JdbcTemplate(acquisitionDataSource);
	}

	/** What ingest and projection inject. */
	@Bean
	@Acquisition
	JdbcClient acquisitionJdbcClient(@Acquisition JdbcTemplate acquisitionJdbcTemplate) {
		return JdbcClient.create(acquisitionJdbcTemplate);
	}

	private static HikariDataSource pool(DataSourceProperties properties,
			DataSourceProperties.Credentials credentials, String poolName) {
		HikariDataSource pool = new HikariDataSource();
		pool.setJdbcUrl(properties.url());
		// A statement that will never answer must eventually say so. On the write
		// path this is what makes a wedged database reach the recorder's spill
		// instead of stalling the writer indefinitely; the driver's own default is
		// to wait forever.
		//
		// The value MUST be a String. Driver properties travel in a `Properties`,
		// and pgjdbc reads them with `getProperty`, which returns null for any
		// value that is not a String — so an int here is not a type mismatch that
		// fails, it is a setting that silently does not exist. Measured: with an
		// int, a COPY against a paused server hung for four minutes instead of
		// two seconds, and the spill was never reached.
		pool.addDataSourceProperty("socketTimeout",
				String.valueOf(properties.socketTimeoutSeconds()));
		pool.setConnectionTimeout(properties.connectionTimeoutMillis());
		pool.setMaximumPoolSize(properties.maxPoolSize());
		pool.setUsername(credentials.username());
		pool.setPassword(credentials.password());
		// Twice, because the two names go to different places and only one of them
		// is the server's. Hikari's pool name labels its own threads and metrics;
		// what `pg_stat_activity.application_name` reports is pgjdbc's
		// `ApplicationName` connection property, whose default is the literal
		// "PostgreSQL JDBC Driver". Setting only the first left both identities
		// indistinguishable server-side (#156) — so the promise that a refused
		// statement says who was not being kept, at the one moment it is wanted.
		pool.setPoolName(poolName);
		pool.addDataSourceProperty("ApplicationName", poolName);
		return pool;
	}
}
