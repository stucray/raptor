package com.stucray.raptor.schema;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The acquisition schemas, migrated on their own version line.
 *
 * <p>A second Flyway instance, deliberately. paddock's read-side migrations and
 * these have separate version numbers, separate history tables and separate
 * schemas; folding them into one line would mean renumbering sixteen existing
 * migrations today and negotiating version numbers across two halves of the
 * system forever after. It also keeps {@code raw} / {@code query} / {@code batch}
 * independently rebuildable, which is the property the whole architecture rests
 * on.
 *
 * <p>Not a {@code Flyway} or {@code FlywayMigrationInitializer} bean, because
 * Boot's {@code FlywayAutoConfiguration} declares both
 * {@code @ConditionalOnMissingBean} — publishing either type would back the
 * auto-configuration off and paddock's own migrations would stop running. So the
 * instance is built and driven here instead, behind a type Boot is not looking
 * for.
 *
 * <p>Runs as the owner identity. The primary datasource is the restricted one,
 * which is precisely the identity that must not be able to create these objects.
 *
 * <p><b>On its own connection, not the owner's pool (#33).</b> That pool carries
 * the write path's {@code socketTimeout} (30s), which is right for a COPY
 * against a wedged database and wrong for a migration: V38 copies the whole
 * system of record in one statement, and the driver would abandon it thirty
 * seconds in. The backend would then fail to start, and the server would go on
 * copying for a client that had left. A migration runs once, at startup, before
 * the recorder, so waiting is the right failure: a backend that never comes up
 * is already what the heartbeat reports.
 *
 * <p><b>Ordering against Boot's initialiser used to be unconstrained</b>, on the
 * grounds that the two instances shared no schema and no history table and that
 * nothing read an acquisition table during context refresh. S8 ended that: the
 * read side is served from {@code query} through compatibility views, and a view
 * cannot be created over a table that does not exist yet. {@link
 * MigrationOrdering} now makes Boot's initialiser depend on this bean, so the
 * acquisition schemas are always in place first.
 */
@Component
class AcquisitionMigrations {

	private static final Logger log = LoggerFactory.getLogger(AcquisitionMigrations.class);

	private final DataSource dataSource;
	private final String readerPassword;
	private final String analysisPassword;

	AcquisitionMigrations(@Value("${raptor.datasource.url}") String url,
			@Value("${raptor.datasource.owner.username}") String username,
			@Value("${raptor.datasource.owner.password}") String password,
			@Value("${raptor.datasource.read.password}") String readerPassword,
			@Value("${raptor.analysis-role.password}") String analysisPassword) {
		this.dataSource = migrationDataSource(url, username, password);
		this.readerPassword = readerPassword;
		this.analysisPassword = analysisPassword;
	}

	/**
	 * The owner, unpooled, with no socket timeout. The URL is applied first so
	 * that the timeout set after it wins over any the URL carries.
	 */
	private static DataSource migrationDataSource(String url, String username, String password) {
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setURL(url);
		dataSource.setUser(username);
		dataSource.setPassword(password);
		dataSource.setSocketTimeout(0);
		// So a long migration is recognisable in pg_stat_activity.
		dataSource.setApplicationName("raptor-migrations");
		return dataSource;
	}

	/** What the migrations connect with; package-private for the test that times it. */
	DataSource dataSource() {
		return dataSource;
	}

	@PostConstruct
	void migrate() {
		Flyway flyway = Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/acquisition")
				.schemas("raw", "query", "batch")
				.defaultSchema("query")
				// Its own history table, so the read side's V1-V16 line is
				// untouched and unrenumbered.
				.table("flyway_schema_history_acquisition")
				.createSchemas(true)
				// V6 creates the read side's login role. Its password comes from
				// the same property the read datasource is built from, so the
				// credential has one definition and none of it is in the
				// repository. V27 does the same for overround-analysis's role,
				// which paddock never connects as but whose login it creates.
				.placeholders(Map.of(
						"readerPassword", readerPassword,
						"analysisPassword", analysisPassword))
				.load();

		var result = flyway.migrate();
		log.info("acquisition schema at {} ({} migration(s) applied)",
				result.targetSchemaVersion, result.migrationsExecuted);
	}
}
