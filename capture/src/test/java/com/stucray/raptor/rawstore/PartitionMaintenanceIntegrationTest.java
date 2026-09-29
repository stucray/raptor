package com.stucray.raptor.rawstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The partition extender, against the real schema (#267).
 *
 * <p><b>Every assertion here is about the boundary, not the arithmetic.</b> A
 * test that checked the migration's SQL text, or that a month is thirty-ish
 * days, would have passed throughout the year in which the runway was quietly
 * frozen. What can only be answered against a real PostgreSQL is where a row
 * actually lands, and that is what is asked.
 *
 * <p>The schedule is asserted elsewhere, in the module that owns
 * {@code @EnableScheduling}: a test that calls {@link
 * PartitionMaintenance#ensureRunway()} itself can never catch the timer being
 * absent, which is the failure this class exists to prevent one layer down.
 */
// A target far past what V3 seeded, and pinned rather than inherited. The
// migration creates twelve months from the moment it runs and the shipped target
// is 365 days, so the two land within a day of each other: on most dates the
// extender has exactly one month to add, and on the first of a month it can have
// none at all. A test whose subject is "did anything get created" must not be
// one day of the year away from having nothing to do. This also puts the
// multi-month catch-up under test, which the shipped setting never exercises.
@SpringBootTest(properties = "raptor.raw-store.partition-runway-target=900d")
@Import(TestcontainersConfiguration.class)
@DisplayName("Partition maintenance: the runway keeps moving, and the bounds still meet")
class PartitionMaintenanceIntegrationTest {

	/**
	 * Bounds and names of every non-default partition, in order.
	 *
	 * <p>Parsed the way {@link PartitionRunwayHealthIndicator} parses them,
	 * because a test that read them another way could agree with the schema while
	 * disagreeing with the code under test about what the schema says.
	 */
	private static final String BOUNDS_SQL = """
			select c.relname                                                as name,
				(split_part(split_part(pg_get_expr(c.relpartbound, c.oid),
					'FROM (''', 2), '''', 1))::timestamptz                  as lower_bound,
				(split_part(split_part(pg_get_expr(c.relpartbound, c.oid),
					'TO (''', 2), '''', 1))::timestamptz                    as upper_bound
			from pg_inherits i
			join pg_class c on c.oid = i.inhrelid
			where i.inhparent = 'raw.stream_message'::regclass
			  and pg_get_expr(c.relpartbound, c.oid) <> 'DEFAULT'
			order by lower_bound""";

	@Autowired PartitionMaintenance maintenance;

	@Autowired @Acquisition JdbcClient jdbc;

	@Test
	@DisplayName("a row past the old last bound lands in a real partition, not the default")
	void theRunwayIsExtendedPastTheTarget() {
		OffsetDateTime before = lastBound();

		// More than one, so the loop is what is under test and not a single call
		// that happens to be enough: a machine off for a quarter comes back needing
		// three, and one-per-run would take three days to catch up.
		assertThat(maintenance.ensureRunway()).isGreaterThan(1);
		assertThat(lastBound()).isAfter(before);

		// THE ASSERTION THE ISSUE ASKED FOR — and note what it is not. "The insert
		// succeeded" would now pass with no partition at all, because V28's DEFAULT
		// catches exactly this row. What proves the extender ran is WHERE it went.
		assertThat(insertAt(before.plusSeconds(1)))
			.as("a row one second past the old runway must land in a month partition")
			.isNotEqualTo("raw.stream_message_default")
			.startsWith("raw.stream_message_");
	}

	@Test
	@DisplayName("the new bounds meet the old ones exactly, at whatever offset they sit")
	void thereIsNoGapAndNoOverlap() {
		maintenance.ensureRunway();

		List<Bound> bounds = jdbc.sql(BOUNDS_SQL)
			.query((rs, row) -> new Bound(rs.getString("name"),
					rs.getObject("lower_bound", OffsetDateTime.class),
					rs.getObject("upper_bound", OffsetDateTime.class)))
			.list();

		// The existing partitions sit on 17:00Z, not midnight: the migration's block
		// used date bounds cast in the session's timezone, on a laptop at UTC+7.
		// Anything computing "the next month" from a clock leaves a seven-hour hole
		// here — or an overlap, which PostgreSQL refuses outright.
		assertThat(bounds).hasSizeGreaterThan(1);
		for (int i = 1; i < bounds.size(); i++) {
			assertThat(bounds.get(i).lower())
				.as("%s must begin exactly where %s ends",
						bounds.get(i).name(), bounds.get(i - 1).name())
				.isEqualTo(bounds.get(i - 1).upper());
		}
	}

	@Test
	@DisplayName("a second run creates nothing, because the first one was enough")
	void extendingIsIdempotent() {
		maintenance.ensureRunway();
		OffsetDateTime afterFirst = lastBound();

		assertThat(maintenance.ensureRunway()).isZero();
		assertThat(lastBound()).isEqualTo(afterFirst);
	}

	@Test
	@DisplayName("a pt nothing anticipated lands in the default rather than being rejected")
	void theDefaultPartitionCatchesWhatTheMonthsDoNot() {
		// Before 2019, which no monthly partition covers and none ever will: the
		// corpus starts in 2019-10 and the extender only ever adds to the end. This
		// is what V28 is for — a misparsed or corrupt pt that would otherwise take
		// the whole write path down with a data-shaped rejection.
		assertThat(insertAt(OffsetDateTime.parse("1900-01-01T00:00:00Z")))
			.isEqualTo("raw.stream_message_default");
	}

	/** The partition a row with this {@code pt} actually landed in. */
	private String insertAt(OffsetDateTime pt) {
		Long session = jdbc.sql("""
				insert into raw.capture_session (started_at, origin, config_json, build_version)
				values (?, 'RESIDENT', '{}'::jsonb, 'test') returning id""")
			.param(OffsetDateTime.now(ZoneOffset.UTC))
			.query(Long.class).single();
		jdbc.sql("""
				insert into raw.stream_message (session_id, market_id, pt, received_at, seq,
					payload)
				values (?, '1.1', ?, ?, 1, '{}'::jsonb)""")
			.params(session, pt, pt)
			.update();
		return jdbc.sql("select tableoid::regclass::text from raw.stream_message "
				+ "where session_id = ?")
			.param(session)
			.query(String.class).single();
	}

	private OffsetDateTime lastBound() {
		return jdbc.sql("select max(upper_bound) as last_bound from (" + BOUNDS_SQL + ") b")
			.query((rs, row) -> rs.getObject("last_bound", OffsetDateTime.class))
			.single();
	}

	private record Bound(String name, OffsetDateTime lower, OffsetDateTime upper) {}
}
