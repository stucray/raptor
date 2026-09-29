package com.stucray.raptor.rawstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V37 (#26): the empty future months of {@code raw.stream_message} are rebuilt on
 * midnight UTC, and nothing holding a row moves.
 *
 * <p><b>The migration is run again, against a state built to need it.</b> By the
 * time a test can look, Flyway has already applied it — and in CI, where V3 ran
 * in a UTC session, there was nothing for it to do. So each test builds, past
 * the current runway, the shape production has: a partition holding a row whose
 * upper bound sits off midnight, followed by empty months at the same offset.
 * The migration is written to be re-runnable for exactly this reason.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("V37: new months begin at midnight UTC, and nothing holding rows moves")
class UtcMonthBoundsMigrationTest {

	private static final String MIGRATION =
			"db/acquisition/V37__stream_message_utc_month_bounds.sql";

	/** Parsed as {@link PartitionMaintenanceIntegrationTest} parses them. */
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

	private static final DateTimeFormatter BOUND =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSXXX").withZone(ZoneOffset.UTC);

	private static final DateTimeFormatter MONTH =
			DateTimeFormatter.ofPattern("yyyy_MM").withZone(ZoneOffset.UTC);

	@Autowired @Acquisition JdbcClient jdbc;

	@Test
	@DisplayName("empty months after a kept one are replaced by a bridge and whole UTC months")
	void theEmptyFutureIsRealigned() throws IOException {
		// The shape production has, seven hours off midnight, past everything else.
		OffsetDateTime start = lastBound();
		OffsetDateTime keptUpper = start.plusMonths(1).minusHours(7);
		OffsetDateTime oldLast = start.plusMonths(3).minusHours(7);
		String kept = partition(start, keptUpper);
		partition(keptUpper, start.plusMonths(2).minusHours(7));
		partition(start.plusMonths(2).minusHours(7), oldLast);
		// Future, but holding a row: the migration must keep it, so the bridge
		// has to start from its odd upper bound.
		insertAt(start.plusDays(1));

		// The witness: the arrangement really is off midnight before the run, so a
		// pass below is the migration's doing and not the fixture's.
		assertThat(bounds()).filteredOn(b -> !b.lower().isBefore(keptUpper))
			.isNotEmpty()
			.anySatisfy(b -> assertThat(isUtcMonthStart(b.upper())).isFalse());

		runMigration();

		List<Bound> after = bounds();
		assertThat(after).filteredOn(b -> b.name().equals(kept))
			.singleElement()
			.satisfies(b -> {
				assertThat(b.lower()).isEqualTo(start);
				assertThat(b.upper()).isEqualTo(keptUpper);
			});
		assertThat(jdbc.sql("select tableoid::regclass::text from raw.stream_message")
				.query(String.class).list())
			.containsExactly("raw." + kept);

		assertContiguous(after);
		List<Bound> rebuilt = after.stream().filter(b -> !b.lower().isBefore(keptUpper)).toList();
		assertThat(rebuilt).first().satisfies(bridge -> {
			assertThat(bridge.lower()).isEqualTo(keptUpper);
			assertThat(bridge.upper()).isAfterOrEqualTo(keptUpper.plusMonths(1));
		});
		assertThat(rebuilt).allSatisfy(b -> assertThat(isUtcMonthStart(b.upper()))
			.as("%s ends at %s, which is not midnight UTC on the 1st", b.name(), b.upper())
			.isTrue());
		// The runway is no shorter than it was, and not a month longer.
		assertThat(after.getLast().upper()).isAfterOrEqualTo(oldLast)
			.isBefore(oldLast.plusMonths(1));
	}

	@Test
	@DisplayName("a second run changes nothing")
	void runningItAgainIsANoOp() throws IOException {
		OffsetDateTime start = lastBound();
		partition(start, start.plusMonths(1).minusHours(7));
		partition(start.plusMonths(1).minusHours(7), start.plusMonths(2).minusHours(7));
		runMigration();
		List<Bound> once = bounds();

		runMigration();

		assertThat(bounds()).isEqualTo(once);
	}

	@Test
	@DisplayName("a runway already on midnight UTC is left exactly as it is")
	void anAlignedRunwayIsUntouched() throws IOException {
		OffsetDateTime start = lastBound();
		// Aligned from here on, whatever state earlier tests left before it.
		if (!isUtcMonthStart(start)) {
			runMigration();
			start = lastBound();
		}
		partition(start, start.plusMonths(1));
		partition(start.plusMonths(1), start.plusMonths(2));
		List<Bound> before = bounds();

		runMigration();

		assertThat(bounds()).isEqualTo(before);
	}

	private void runMigration() throws IOException {
		jdbc.sql(new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8))
			.update();
	}

	/** One partition past the runway, named for its midpoint month. */
	private String partition(OffsetDateTime from, OffsetDateTime to) {
		String name = "stream_message_"
				+ MONTH.format(from.plus(Duration.between(from, to).dividedBy(2)));
		jdbc.sql("create table raw.%s partition of raw.stream_message for values from ('%s') to ('%s')"
				.formatted(name, BOUND.format(from), BOUND.format(to)))
			.update();
		return name;
	}

	private void insertAt(OffsetDateTime pt) {
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
	}

	private static boolean isUtcMonthStart(OffsetDateTime t) {
		OffsetDateTime utc = t.withOffsetSameInstant(ZoneOffset.UTC);
		return utc.getDayOfMonth() == 1 && utc.toLocalTime().toSecondOfDay() == 0
				&& utc.getNano() == 0;
	}

	private static void assertContiguous(List<Bound> bounds) {
		for (int i = 1; i < bounds.size(); i++) {
			assertThat(bounds.get(i).lower())
				.as("%s must begin exactly where %s ends",
						bounds.get(i).name(), bounds.get(i - 1).name())
				.isEqualTo(bounds.get(i - 1).upper());
		}
	}

	private List<Bound> bounds() {
		return jdbc.sql(BOUNDS_SQL)
			.query((rs, row) -> new Bound(rs.getString("name"),
					rs.getObject("lower_bound", OffsetDateTime.class)
						.withOffsetSameInstant(ZoneOffset.UTC),
					rs.getObject("upper_bound", OffsetDateTime.class)
						.withOffsetSameInstant(ZoneOffset.UTC)))
			.list();
	}

	private OffsetDateTime lastBound() {
		return bounds().getLast().upper();
	}

	private record Bound(String name, OffsetDateTime lower, OffsetDateTime upper) {}
}
