package com.stucray.raptor.rawstore;

import com.stucray.raptor.datasource.Acquisition;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code raw.stream_message} supplied with partitions to write into (#267).
 *
 * <p><b>This class was documented before it was written.</b>
 * {@code V3__raw_stream_message.sql} says "beyond that, PartitionMaintenance
 * creates the next month a week ahead", and nothing of the sort existed: the
 * migration's {@code do} block computed twelve months from {@code now()} at the
 * moment it ran, and a Flyway migration never runs again. So the runway was
 * frozen at a date in 2027 and shrinking by a day a day, with the failure at the
 * end of it being that the recorder cannot write the system of record at all.
 *
 * <p><b>The new bound comes from the old one, never from a fresh calculation of
 * "this month".</b> The existing partitions do not sit on midnight UTC — the
 * generating block used {@code date} boundaries cast in the session's timezone,
 * so every bound is 17:00Z, which is midnight at UTC+7 where the migration was
 * run. Computing the next month from a clock would therefore leave a seven-hour
 * hole, or an overlap that PostgreSQL refuses outright. Extending from the last
 * existing upper bound is exact whatever that offset happens to be, and it is
 * the same answer on a machine in any timezone. The month arithmetic is done
 * explicitly {@code at time zone 'UTC'} for the reason the rest of this codebase
 * does: the same expression evaluated in the session's zone is a different
 * value on a laptop than in CI.
 *
 * <p><b>It creates whole months until the runway is long enough</b>, rather than
 * one per run. A machine that was off for a quarter comes back needing three,
 * and a job that adds one a day would take three days to catch up while the
 * health indicator reported a shrinking runway that nothing appeared to be
 * fixing.
 *
 * <p><b>The lock is bounded, and failure is not.</b> {@code CREATE TABLE …
 * PARTITION OF} takes an {@code ACCESS EXCLUSIVE} lock on the parent, which for
 * the duration blocks the recorder's own inserts. Creating an empty table is
 * fast, but "fast" is not a guarantee under a COPY-heavy write path, so each
 * statement runs under a short {@code lock_timeout} and simply gives up until
 * the next tick. Losing a race at 20:00 on a Saturday costs nothing when the
 * runway is measured in months; blocking the write path to win it would cost the
 * card.
 */
@Component
class PartitionMaintenance {

	private static final Logger log = LoggerFactory.getLogger(PartitionMaintenance.class);

	/**
	 * How far ahead the last bound already reaches, and where the next begins.
	 *
	 * <p>{@code DEFAULT} is excluded explicitly. Its {@code relpartbound} renders
	 * as the literal {@code DEFAULT} with no bound to split out, and the cast of
	 * the empty string that produces is an error rather than a null — so a query
	 * that merely aggregates over every partition starts throwing the moment a
	 * default exists.
	 */
	private static final String LAST_BOUND_SQL = """
			select max(case when pg_get_expr(c.relpartbound, c.oid) <> 'DEFAULT'
					then (split_part(
						split_part(pg_get_expr(c.relpartbound, c.oid), 'TO (''', 2),
						'''', 1))::timestamptz end) as last_covered
			from pg_inherits i
			join pg_class c on c.oid = i.inhrelid
			where i.inhparent = 'raw.stream_message'::regclass""";

	/**
	 * One partition, with its bounds and name written into the statement.
	 *
	 * <p><b>Interpolated rather than bound, because none of it can be a
	 * parameter.</b> A partition bound must be a constant — {@code FOR VALUES
	 * FROM (?)} is a syntax error — and an identifier never could be. The values
	 * are not input either: both timestamps come out of {@code pg_inherits} and
	 * through {@code java.time}, and the name is checked against
	 * {@link #PARTITION_NAME} before it reaches here.
	 *
	 * <p>The {@code do} block exists for {@code set local}, which bounds the
	 * {@code ACCESS EXCLUSIVE} lock this takes on the parent to two seconds and
	 * reverts with the block's own transaction — where a bare {@code set} on a
	 * pooled connection would follow it to whatever ran next.
	 */
	private static final String CREATE_SQL = """
			do $$
			begin
				set local lock_timeout = '2s';
				create table if not exists raw.%s
					partition of raw.stream_message
					for values from ('%s') to ('%s')
					with (fillfactor = 100,
						autovacuum_vacuum_insert_scale_factor = 0,
						autovacuum_vacuum_insert_threshold = 1000000);
			end
			$$""";

	/**
	 * The only shape a generated partition name may have.
	 *
	 * <p>Checked rather than trusted. Nothing user-supplied reaches the name, but
	 * it is interpolated into DDL, and an assertion at the boundary costs nothing
	 * and says out loud that the property is required rather than incidental.
	 */
	private static final Pattern PARTITION_NAME =
			Pattern.compile("stream_message_\\d{4}_\\d{2}");

	/**
	 * An explicit offset, always.
	 *
	 * <p>A bare timestamp in the statement text would be read in the session's
	 * {@code TimeZone}, which pgjdbc takes from the JVM default — the trap
	 * {@code CopyBuffer} exists to hold shut, and one that is invisible in CI
	 * because CI runs UTC. A partition bound off by the developer's own offset
	 * would leave a hole exactly that wide.
	 */
	private static final DateTimeFormatter BOUND =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSXXX").withZone(ZoneOffset.UTC);

	/** Names the month the partition mostly covers; neither bound is in it. */
	private static final DateTimeFormatter MONTH =
			DateTimeFormatter.ofPattern("yyyy_MM").withZone(ZoneOffset.UTC);

	/**
	 * A run creates at most this many, so a misread bound cannot fill the
	 * catalogue with partitions in one pass. Two years of monthly catch-up is
	 * more than any real outage and far less than a runaway.
	 */
	private static final int MAX_PER_RUN = 24;

	private final JdbcClient jdbc;
	private final RawStoreProperties properties;
	private final Clock clock;

	PartitionMaintenance(@Acquisition JdbcClient jdbc, RawStoreProperties properties,
			Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Daily, and once shortly after startup.
	 *
	 * <p>Daily because the unit of work is a month; the initial delay is what
	 * covers a machine that has been off long enough to matter, without doing
	 * DDL in the middle of context refresh.
	 */
	@Scheduled(fixedDelayString = "${raptor.raw-store.partition-check-interval:24h}",
			initialDelayString = "${raptor.raw-store.partition-check-initial-delay:2m}")
	void extend() {
		try {
			int created = ensureRunway();
			if (created > 0) {
				log.info("created {} partition(s) for raw.stream_message; the runway now reaches "
						+ "{}", created, lastCovered());
			}
		}
		catch (RuntimeException e) {
			// Never fatal, and said once a day rather than swallowed: the runway is
			// months long, so a failed attempt is not urgent — but a silent one
			// would leave `partitionRunway` counting down with nothing explaining
			// why the number never moves.
			log.error("could not extend raw.stream_message's partitions ({}); "
					+ "partitionRunway reports the remaining runway", e.toString(), e);
		}
	}

	/**
	 * Create months until the last bound is beyond the target, and say how many.
	 *
	 * <p>Package-private so a test can drive it and read the count. The schedule
	 * is asserted separately, where {@code @EnableScheduling} lives — a test that
	 * calls this method could never catch the timer being absent, which is the
	 * failure this whole class exists to prevent one layer down.
	 */
	int ensureRunway() {
		OffsetDateTime last = lastCovered();
		if (last == null) {
			// No bound to extend from. Either the table is not partitioned any more
			// or every partition is the default; both are schema states this must
			// not paper over by inventing a start date.
			log.warn("raw.stream_message has no bounded partition to extend from; "
					+ "creating none");
			return 0;
		}
		OffsetDateTime target = OffsetDateTime.now(clock).plus(properties.partitionRunwayTarget());
		int created = 0;
		while (last.isBefore(target) && created < MAX_PER_RUN) {
			create(last, nextBound(last));
			OffsetDateTime next = lastCovered();
			if (next == null || !next.isAfter(last)) {
				// The bound did not move: the partition already existed under a name
				// this did not predict, or the read is not seeing what was written.
				// Looping again would spin, so stop and let the runway indicator say
				// the number is not moving.
				log.warn("creating a partition from {} did not extend the runway; stopping",
						last);
				break;
			}
			last = next;
			created++;
		}
		return created;
	}

	/**
	 * One month on, in UTC.
	 *
	 * <p>Month arithmetic needs a calendar and therefore a zone, and the zone is
	 * named rather than inherited: {@code plusMonths} on a zone with daylight
	 * saving would move the wall-clock bound by an hour twice a year and put a
	 * gap or an overlap in a range that must have neither. UTC has no such
	 * transition and is what the database is asked to agree with.
	 */
	private static OffsetDateTime nextBound(OffsetDateTime from) {
		return from.atZoneSameInstant(ZoneOffset.UTC).plusMonths(1).toOffsetDateTime();
	}

	private void create(OffsetDateTime from, OffsetDateTime to) {
		// The midpoint names it. A range of 2027-07-31T17:00Z to 2027-08-31T17:00Z
		// is August and neither bound says so, because the existing partitions sit
		// on 17:00Z rather than midnight.
		String name = "stream_message_"
				+ MONTH.format(from.plus(Duration.between(from, to).dividedBy(2)));
		if (!PARTITION_NAME.matcher(name).matches()) {
			throw new IllegalStateException("refusing to create a partition named " + name);
		}
		jdbc.sql(CREATE_SQL.formatted(name, BOUND.format(from), BOUND.format(to))).update();
	}

	private @Nullable OffsetDateTime lastCovered() {
		return jdbc.sql(LAST_BOUND_SQL)
				.query((rs, row) -> rs.getObject("last_covered", OffsetDateTime.class))
				.optional()
				.orElse(null);
	}
}
