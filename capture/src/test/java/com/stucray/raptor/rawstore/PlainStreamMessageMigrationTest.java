package com.stucray.raptor.rawstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stucray.raptor.TestcontainersConfiguration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V38 (#33): a partitioned {@code raw.stream_message} holding rows of every
 * provenance becomes one plain table, with every row in it exactly once. Then
 * V39 (#35) drops the partitioned table V38 set aside.
 *
 * <p><b>Each test gets its own database</b> in the shared container, migrated
 * by Flyway exactly as {@code AcquisitionMigrations} does it. The test context's
 * database cannot serve: V38 has run there long before a test can look, on a
 * table that was empty when it did. Here Flyway stops at V37, the fixture is
 * written into the partitioned table, and only then does V38 run.
 *
 * <p>The fixture spans the shapes live holds: a resident session across two
 * months, an imported session that numbered {@code seq} per stream message (so
 * one {@code (session_id, seq)} carries two markets), two vendor files from
 * 2019 and 2020, a spill-replayed message, a row in the DEFAULT partition, and
 * the V36 envelope fields.
 */
@DisplayName("V38/V39: raw.stream_message becomes a plain table and every row survives")
class PlainStreamMessageMigrationTest {

	private static final PostgreSQLContainer POSTGRES = TestcontainersConfiguration.POSTGRES;

	/** Every column V38 carries over, rendered as one string per row. */
	private static final String ROWS_SQL = """
			select concat_ws('|', session_id, file_id, market_id, pt, received_at, seq,
				payload::text, segment_type, change_type)
			from %s""";

	/** Rows per provenance, the unit the migration itself verifies. */
	private static final String COUNTS_SQL = """
			select concat_ws('|', session_id, file_id, count(*))
			from %s group by session_id, file_id""";

	/**
	 * Everything about the table's definition that must match live, as text:
	 * columns, constraints, indexes, grants, storage options and comments. Names
	 * carry the relation, so two databases compare line for line.
	 */
	private static final String SHAPE_SQL = """
			select 'kind ' || c.relkind::text from pg_class c where c.oid = 'raw.stream_message'::regclass
			union all
			select 'column ' || a.attnum || ' ' || a.attname || ' '
				|| format_type(a.atttypid, a.atttypmod) || ' notnull=' || a.attnotnull
				|| ' identity=' || a.attidentity::text
			from pg_attribute a
			where a.attrelid = 'raw.stream_message'::regclass and a.attnum > 0
				and not a.attisdropped
			union all
			select 'constraint ' || conname || ' ' || pg_get_constraintdef(oid)
			from pg_constraint where conrelid = 'raw.stream_message'::regclass
			union all
			select 'index ' || pg_get_indexdef(indexrelid)
			from pg_index where indrelid = 'raw.stream_message'::regclass
			union all
			select 'acl ' || x::text
			from pg_class c, unnest(c.relacl) x where c.oid = 'raw.stream_message'::regclass
			union all
			select 'option ' || o
			from pg_class c, unnest(c.reloptions) o where c.oid = 'raw.stream_message'::regclass
			union all
			select 'comment ' || coalesce(a.attname, '(table)') || ' '
				|| col_description('raw.stream_message'::regclass, coalesce(a.attnum, 0))
			from (select null::name as attname, null::smallint as attnum
				union all select attname, attnum from pg_attribute
				where attrelid = 'raw.stream_message'::regclass and attnum > 0) a
			where col_description('raw.stream_message'::regclass, coalesce(a.attnum, 0)) is not null
			union all
			select 'sequence ' || pg_get_serial_sequence('raw.stream_message', 'id')
			union all
			select 'partitions ' || count(*) from pg_inherits
			where inhparent = 'raw.stream_message'::regclass
			order by 1""";

	private final String migrated = "v38_" + UUID.randomUUID().toString().replace("-", "");
	private JdbcClient owner;
	private JdbcClient probe;

	@BeforeEach
	void createDatabase() {
		owner = JdbcClient.create(dataSource(POSTGRES.getDatabaseName()));
		owner.sql("create database " + migrated).update();
		probe = JdbcClient.create(dataSource(migrated));
	}

	@AfterEach
	void dropDatabase() {
		owner.sql("drop database if exists " + migrated + " with (force)").update();
	}

	@Test
	@DisplayName("every row is copied exactly once, counted per session and per file")
	void everyRowSurvivesExactlyOnce() {
		flyway(migrated, "37").migrate();
		seed();
		List<String> rows = rows("raw.stream_message");
		List<String> counts = counts("raw.stream_message");
		long partitions = partitions("raw.stream_message");
		// The witness: before V38 the table is partitioned, and the fixture really
		// reached several partitions including the default one. A pass below is
		// then the migration's doing.
		assertThat(relkind("raw.stream_message")).isEqualTo("p");
		assertThat(probe.sql("select count(distinct tableoid) from raw.stream_message")
				.query(Long.class).single()).isGreaterThanOrEqualTo(4);
		assertThat(probe.sql("select count(*) from raw.stream_message_default")
				.query(Long.class).single()).isOne();

		flyway(migrated, "38").migrate();

		assertThat(relkind("raw.stream_message")).isEqualTo("r");
		assertThat(partitions("raw.stream_message")).isZero();
		assertThat(rows("raw.stream_message")).containsExactlyInAnyOrderElementsOf(rows);
		assertThat(counts("raw.stream_message")).containsExactlyInAnyOrderElementsOf(counts);
		// Numbered 1..N by the copy, which the written reversal relies on.
		assertThat(probe.sql("select min(id) || '..' || max(id) from raw.stream_message")
				.query(String.class).single()).isEqualTo("1.." + rows.size());

		// Set aside intact, default partition and all.
		assertThat(relkind("raw.stream_message_partitioned")).isEqualTo("p");
		assertThat(partitions("raw.stream_message_partitioned")).isEqualTo(partitions);
		assertThat(rows("raw.stream_message_partitioned")).containsExactlyInAnyOrderElementsOf(rows);
		assertThat(probe.sql("select count(*) from raw.stream_message_default")
				.query(Long.class).single()).isOne();
	}

	@Test
	@DisplayName("keys, grants, comments and options carry over, and nothing else is indexed")
	void theTableIsKeyedOnlyForIntegrity() {
		flyway(migrated, "37").migrate();
		seed();
		String grants = acl("raw.stream_message");
		// The witness: live's table carries a grant beyond its owner's, so equal
		// grants below mean the copy moved it rather than that there was none.
		assertThat(grants).contains("overround_analysis=r/");

		flyway(migrated, "38").migrate();

		assertThat(probe.sql("""
						select conname || ' ' || contype::text from pg_constraint
						where conrelid = 'raw.stream_message'::regclass""")
				.query(String.class).list())
			.containsExactlyInAnyOrder(
					"stream_message_pkey p",
					"stream_message_session_seq_market_key u",
					"stream_message_file_seq_key u",
					"stream_message_one_provenance c",
					"stream_message_session_id_fkey f",
					"stream_message_file_id_fkey f");
		assertThat(probe.sql("""
						select indexrelid::regclass::text from pg_index
						where indrelid = 'raw.stream_message'::regclass""")
				.query(String.class).list())
			.containsExactlyInAnyOrder("raw.stream_message_pkey",
					"raw.stream_message_session_seq_market_key", "raw.stream_message_file_seq_key");
		assertThat(acl("raw.stream_message")).isEqualTo(grants);
		assertThat(probe.sql("select obj_description('raw.stream_message'::regclass, 'pg_class')")
				.query(String.class).single()).startsWith("Betfair MCM stream messages, verbatim.");
		assertThat(probe.sql("""
						select col_description('raw.stream_message'::regclass, attnum)
						from pg_attribute
						where attrelid = 'raw.stream_message'::regclass and attname = 'segment_type'""")
				.query(String.class).single()).startsWith("The stream envelope's segmentType");
		assertThat(probe.sql("select pg_get_serial_sequence('raw.stream_message', 'id')")
				.query(String.class).single()).isEqualTo("raw.stream_message_id_seq");
	}

	/**
	 * A database built from every migration with an empty table ends in the same
	 * shape as one migrated with rows in it, which is what live is.
	 */
	@Test
	@DisplayName("a fresh database ends in the same shape as a migrated one")
	void aFreshDatabaseMatchesAMigratedOne() {
		String fresh = "v38_fresh_" + UUID.randomUUID().toString().replace("-", "");
		owner.sql("create database " + fresh).update();
		try {
			flyway(fresh, null).migrate();
			flyway(migrated, "37").migrate();
			seed();
			flyway(migrated, null).migrate();

			List<String> freshShape = JdbcClient.create(dataSource(fresh)).sql(SHAPE_SQL)
					.query(String.class).list();
			assertThat(freshShape).contains("kind r", "partitions 0");
			assertThat(probe.sql(SHAPE_SQL).query(String.class).list())
				.containsExactlyElementsOf(freshShape);
		}
		finally {
			owner.sql("drop database if exists " + fresh + " with (force)").update();
		}
	}

	@Test
	@DisplayName("a publish time no partition covered is written like any other")
	void noCalendarBoundaryStopsAWrite() {
		flyway(migrated, null).migrate();
		long session = session(null, "RESIDENT");

		probe.sql("""
				insert into raw.stream_message (session_id, market_id, pt, received_at, seq, payload)
				values (?, '1.99', '2200-01-01 00:00:00+00', now(), 1, '{}')""")
			.param(session).update();

		assertThat(probe.sql("select tableoid::regclass::text from raw.stream_message")
				.query(String.class).single()).isEqualTo("raw.stream_message");
	}

	/**
	 * V39 (#35) drops the set-aside table with every partition, and only that: a
	 * row captured after V38 has an id above the copy's and must not trip the
	 * comparison, and the plain table keeps every row.
	 */
	@Test
	@DisplayName("V39 drops the set-aside table and every partition, and nothing else")
	void v39DropsTheSetAsideTable() {
		flyway(migrated, "37").migrate();
		seed();
		flyway(migrated, "38").migrate();
		message(session(null, "RESIDENT"), null, "1.50", "2026-09-30 19:00:00+00", 1, null, null);
		List<String> rows = rows("raw.stream_message");
		List<String> shape = probe.sql(SHAPE_SQL).query(String.class).list();
		// The witness: the set-aside table and its DEFAULT partition are there to drop.
		assertThat(partitions("raw.stream_message_partitioned")).isGreaterThanOrEqualTo(4);
		assertThat(setAsideRelations()).contains("stream_message_partitioned", "stream_message_default");

		flyway(migrated, "39").migrate();

		assertThat(setAsideRelations()).isEmpty();
		assertThat(rows("raw.stream_message")).containsExactlyInAnyOrderElementsOf(rows);
		assertThat(probe.sql(SHAPE_SQL).query(String.class).list()).containsExactlyElementsOf(shape);
	}

	/**
	 * The drop is the last moment the two tables can be compared, so V39 refuses
	 * to drop a row the plain table does not also hold, and drops nothing.
	 *
	 * <p>The row removed is the LAST one V38 copied (the imported session sorts
	 * after the resident one), so no cut-off by id can step around it.
	 */
	@Test
	@DisplayName("V39 refuses to drop a row the plain table does not hold")
	void v39RefusesWhenARowWouldBeLost() {
		flyway(migrated, "37").migrate();
		seed();
		flyway(migrated, "38").migrate();
		long partitions = partitions("raw.stream_message_partitioned");
		assertThat(probe.sql("select max(id) from raw.stream_message where market_id = '1.21'")
				.query(Long.class).single()).isEqualTo(probe.sql("select max(id) from raw.stream_message")
						.query(Long.class).single());
		assertThat(probe.sql("delete from raw.stream_message where market_id = '1.21'").update()).isOne();

		assertThatThrownBy(() -> flyway(migrated, "39").migrate())
			.hasMessageContaining("V39: 1 row(s) of the set-aside table are not in raw.stream_message")
			.hasMessageContaining("(1 from capture sessions, 0 from vendor files)");

		assertThat(partitions("raw.stream_message_partitioned")).isEqualTo(partitions);
		assertThat(probe.sql("select max(version::int) from query.flyway_schema_history_acquisition where success")
				.query(Integer.class).single()).isEqualTo(38);
	}

	/** Every relation in {@code raw} left from the partitioned table: itself and its partitions. */
	private List<String> setAsideRelations() {
		return probe.sql("""
						select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
						where n.nspname = 'raw' and c.relkind in ('r', 'p')
							and c.relname like 'stream\\_message\\_%'""")
			.query(String.class).list();
	}

	/** The fixture, written into whichever table is called raw.stream_message. */
	private void seed() {
		long resident = session(null, "RESIDENT");
		long imported = session("probe/imported", "MANUAL");
		long early = file("probe/2019/Nov/1/1.1.bz2");
		long late = file("probe/2020/Mar/1/1.2.bz2");

		// A resident session across a month boundary, with the V36 envelope.
		message(resident, null, "1.10", "2026-08-31 22:00:00+00", 1, "SEG_START", "SUB_IMAGE");
		message(resident, null, "1.10", "2026-08-31 22:00:00+00", 2, "SEG_END", "SUB_IMAGE");
		message(resident, null, "1.10", "2026-09-01 01:00:00+00", 3, null, null);
		message(resident, null, "1.11", "2026-09-01 01:00:01+00", 4, null, "RESUB_DELTA");
		// Replayed from a spill file after the session had moved on.
		probe.sql("""
				insert into raw.spill_file (name, session_id, cause, messages, bytes, spilled_at)
				values ('spill-probe.ndjson', ?, 'DB_UNAVAILABLE', 1, 10, '2026-09-01 01:05:00+00')""")
			.param(resident).update();
		message(resident, null, "1.11", "2026-09-01 01:04:59+00", 5, null, null);
		// An imported session: one stream message, two markets, one seq.
		message(imported, null, "1.20", "2025-12-06 15:00:00+00", 1, null, null);
		message(imported, null, "1.21", "2025-12-06 15:00:00+00", 1, null, null);
		// Vendor files, one from each end of the historic span.
		message(null, early, "1.1", "2019-11-02 12:00:00+00", 1, null, null);
		message(null, early, "1.1", "2019-11-02 12:00:01+00", 2, null, null);
		message(null, late, "1.2", "2020-03-02 12:00:00+00", 1, null, null);
		// Before every monthly partition: the DEFAULT one holds it.
		message(null, late, "1.2", "2018-06-01 00:00:00+00", 2, null, null);
	}

	private long session(@Nullable String sourceKey, String origin) {
		return probe.sql("""
						insert into raw.capture_session (started_at, origin, config_json, build_version,
							source_key)
						values ('2026-08-31 21:59:00+00', ?, '{}', 'probe', ?) returning id""")
			.params(Arrays.asList(origin, sourceKey))
			.query(Long.class).single();
	}

	private long file(String path) {
		return probe.sql("""
						insert into raw.historic_file (path, sha256, bytes, messages)
						values (?, '\\x00', 1, 2) returning id""")
			.param(path)
			.query(Long.class).single();
	}

	private void message(@Nullable Long session, @Nullable Long file, String market, String pt,
			long seq, @Nullable String segmentType, @Nullable String changeType) {
		probe.sql("""
				insert into raw.stream_message (session_id, file_id, market_id, pt, received_at, seq,
					payload, segment_type, change_type)
				values (?, ?, ?, cast(? as timestamptz),
					case when ? then cast(? as timestamptz) + interval '150 milliseconds' end,
					?, cast(? as jsonb), ?, ?)""")
			.params(Arrays.asList(session, file, market, pt, session != null, pt, seq,
					"{\"id\":\"" + market + "\",\"seq\":" + seq + "}", segmentType, changeType))
			.update();
	}

	private List<String> rows(String table) {
		return probe.sql(ROWS_SQL.formatted(table)).query(String.class).list();
	}

	private List<String> counts(String table) {
		return probe.sql(COUNTS_SQL.formatted(table)).query(String.class).list();
	}

	private long partitions(String table) {
		return probe.sql("select count(*) from pg_inherits where inhparent = cast(? as regclass)")
			.param(table).query(Long.class).single();
	}

	private String relkind(String table) {
		return probe.sql("select relkind::text from pg_class where oid = cast(? as regclass)")
			.param(table).query(String.class).single();
	}

	private String acl(String table) {
		return probe.sql("""
						select string_agg(x::text, ',' order by x::text)
						from pg_class c, unnest(c.relacl) x where c.oid = cast(? as regclass)""")
			.param(table).query(String.class).single();
	}

	/** Configured as {@code AcquisitionMigrations} configures it. */
	private static Flyway flyway(String database, @Nullable String target) {
		var configuration = Flyway.configure()
				.dataSource(dataSource(database))
				.locations("classpath:db/acquisition")
				.schemas("raw", "query", "batch")
				.defaultSchema("query")
				.table("flyway_schema_history_acquisition")
				.createSchemas(true)
				.placeholders(Map.of(
						"readerPassword", "reader-test-password",
						"analysisPassword", TestcontainersConfiguration.ANALYSIS_PASSWORD));
		if (target != null) {
			configuration.target(target);
		}
		return configuration.load();
	}

	private static DataSource dataSource(String database) {
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setServerNames(new String[] {POSTGRES.getHost()});
		dataSource.setPortNumbers(new int[] {POSTGRES.getMappedPort(5432)});
		dataSource.setDatabaseName(database);
		dataSource.setUser(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		dataSource.setLoginTimeout(15);
		return dataSource;
	}
}
