package com.stucray.raptor;

import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Empty the database before every test method.
 *
 * <p>Every application context now shares one PostgreSQL (#118), which makes a
 * test's starting state whatever the test before it left behind — and surefire's
 * run order is filesystem order, which is not the same on macOS as on the Linux
 * runner. Rather than have each class delete the rows it happens to know about,
 * every test starts from an empty database and seeds what it needs. A test that
 * cannot state its own preconditions is then a test that fails, not one that
 * passes because of where it sits in the alphabet.
 *
 * <p>Registered as a default listener in {@code META-INF/spring.factories}, so it
 * applies to every Spring test in the module without any of them opting in.
 *
 * <p>One thing is deliberately left alone:
 *
 * <ul>
 * <li><b>{@code flyway_schema_history*}</b> — the migrations ran once, when the
 * first context started this container. Truncating their history would make the
 * next context re-run them against tables that already exist.
 * </ul>
 *
 * <p>{@code team_alias} used to be the second exception: paddock seeded it at
 * startup and nothing else wrote it. #315 moved it to overround-analysis, and
 * #317 took the last screens that joined on it, so paddock's tests no longer
 * hold even a stand-in.
 *
 * <p>{@code analysis} was in this list, and is deliberately NOT any more (#256).
 * It was here because paddock's own analysis code wrote those tables; that code
 * is deleted, and paddock now writes nothing in that schema at all. Leaving the
 * truncate in would be worse than useless: it would quietly clean up after a
 * regression that started writing there again, which is the one symptom that
 * would otherwise be visible. The tables still exist in a test container —
 * paddock's applied migration history created them and #250 moved them — but
 * they are another application's now, and nothing here touches them.
 *
 * <p>The table list is read from the catalogue on each call rather than written
 * down, so a table added by a future migration is covered without anyone
 * remembering to add it here. Partitions are excluded because truncating the
 * partitioned parent takes them with it.
 */
public class DatabaseReset implements TestExecutionListener {

	/**
	 * The owner's pool by bean name. The restricted identity has no grant on
	 * {@code raw} at all, which is the boundary working as designed.
	 */
	private static final String OWNER_DATA_SOURCE = "acquisitionDataSource";

	private static final String TABLES = """
			select string_agg(format('%I.%I', n.nspname, c.relname), ', ')
			from pg_class c
			join pg_namespace n on n.oid = c.relnamespace
			where c.relkind in ('r', 'p')
			  and not c.relispartition
			  and n.nspname in ('raw', 'query', 'ledger', 'batch', 'public')
			  and c.relname not like 'flyway_schema_history%'""";

	@Override
	public void beforeTestMethod(TestContext testContext) {
		ApplicationContext context = testContext.getApplicationContext();
		if (!context.containsBean(OWNER_DATA_SOURCE)) {
			return;
		}
		JdbcTemplate jdbc = new JdbcTemplate(context.getBean(OWNER_DATA_SOURCE, DataSource.class));
		String tables = jdbc.queryForObject(TABLES, String.class);
		if (tables == null || tables.isEmpty()) {
			return;
		}
		// RESTART IDENTITY so a test that asserts on an id gets the same one every
		// run; CASCADE because the foreign keys between these tables are the whole
		// reason hand-written cleanups kept getting the order wrong.
		jdbc.execute("truncate " + tables + " restart identity cascade");
	}
}
