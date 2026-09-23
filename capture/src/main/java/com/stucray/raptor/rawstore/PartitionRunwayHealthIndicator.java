package com.stucray.raptor.rawstore;

import com.stucray.raptor.datasource.Acquisition;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How much longer {@code raw.stream_message} has partitions to be written into
 * (#267).
 *
 * <p>The table is {@code range (pt)} by month and its partitions were created
 * once, by a {@code do} block in {@code V3__raw_stream_message.sql} that computed
 * twelve months from {@code now()} — evaluated when the migration ran, and a
 * Flyway migration never runs again. So the runway does not move on its own, and
 * when it ends every insert fails with <i>no partition of relation found for
 * row</i>: the recorder cannot write the system of record at all.
 *
 * <p><b>Why a number rather than a reminder.</b> The date is known and the query
 * is trivial, so the deadline can announce itself instead of depending on someone
 * reading an issue. That matters more after the extender is built than before:
 * a {@code @Scheduled} job that silently stops creating partitions is
 * indistinguishable from one that is keeping up, right until the writes fail —
 * the shape of #122, #141 and #144. This check is what tells them apart, and it
 * is the reason to keep it once the runway stops shrinking.
 *
 * <p><b>Always UP, and that is the design rather than an omission.</b> This joins
 * the {@code capture} health group, whose verdict drives an automatic backend
 * restart after three unhealthy checks (#184). Restarting a JVM does not create a
 * partition, so a contributor that could turn the group over would answer a
 * schema problem with a SIGTERM against a live recorder — and would do it for
 * something known months ahead, competing with the capture failures the group
 * exists to catch. The analysis close-out's staleness indicator made the same
 * argument one layer down until #256 moved it to overround-analysis, which is
 * where that reasoning now lives; the principle it shares with this class is
 * that a contributor whose problem a restart cannot fix must never be able to
 * cause one. The {@code low} flag is the signal; the heartbeat decides what to
 * do with it.
 *
 * <p>A {@code DEFAULT} partition is reported because it changes what running out
 * means: with one, writes past the last bound land somewhere findable instead of
 * failing, which turns an outage into a mess. There is one since #267, so what
 * matters is no longer whether it exists but whether anything has landed in it.
 */
@Component
class PartitionRunwayHealthIndicator implements HealthIndicator {

	/**
	 * The last instant a row can be written, across every non-default partition.
	 *
	 * <p>Read from {@code pg_get_expr} with {@code split_part} rather than a
	 * regex: the bound text is fixed (<i>FOR VALUES FROM ('…') TO ('…')</i>) and
	 * splitting it needs no escaping, where a regex would need doubled backslashes
	 * inside a Java text block and is one transcription error from silently
	 * matching nothing.
	 *
	 * <p><b>The {@code DEFAULT} partition is excluded inside a {@code case}, and
	 * that is load-bearing rather than tidy.</b> Its bound renders as the literal
	 * {@code DEFAULT}, which the splits above reduce to the empty string, and
	 * {@code ''::timestamptz} is an <i>error</i> and not a null. Written as a
	 * plain aggregate over every partition — which is how it was until #267
	 * created a default — the query does not return a wrong runway, it throws,
	 * and this contributor takes the capture health group down with it. A
	 * {@code filter} clause would not obviously be enough either: it selects
	 * rows, and the expression still has to be safe on the ones it sees.
	 */
	private static final String RUNWAY_SQL = """
			select max(case when pg_get_expr(c.relpartbound, c.oid) <> 'DEFAULT'
					then (split_part(
						split_part(pg_get_expr(c.relpartbound, c.oid), 'TO (''', 2),
						'''', 1))::timestamptz end)                          as last_covered,
				count(*) filter (
					where pg_get_expr(c.relpartbound, c.oid) = 'DEFAULT')    as defaults,
				count(*)                                                     as partitions
			from pg_inherits i
			join pg_class c on c.oid = i.inhrelid
			where i.inhparent = 'raw.stream_message'::regclass""";

	/**
	 * Whether anything has actually landed in the default partition.
	 *
	 * <p>The existence of the net is not the interesting fact; a row caught by it
	 * is. One means the extender stopped or a {@code pt} arrived that nothing
	 * anticipated, and it also blocks creating the partition that range belongs
	 * to — so it needs saying out loud rather than waiting to be discovered
	 * during the recovery it causes.
	 *
	 * <p>{@code exists} rather than a count: the answer is a yes/no and the
	 * partition is expected to be empty forever, so this stops at the first row
	 * instead of scanning however many arrived.
	 */
	private static final String DEFAULT_USED_SQL =
			"select exists (select 1 from raw.stream_message_default)";

	private final JdbcClient jdbc;
	private final RawStoreProperties properties;
	private final Clock clock;

	PartitionRunwayHealthIndicator(@Acquisition JdbcClient jdbc, RawStoreProperties properties,
			Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public Health health() {
		Health.Builder health = Health.up();
		Runway runway = jdbc.sql(RUNWAY_SQL)
				.query((rs, row) -> new Runway(
						rs.getObject("last_covered", OffsetDateTime.class),
						rs.getInt("defaults"),
						rs.getInt("partitions")))
				.single();

		health.withDetail("partitions", runway.partitions());
		health.withDetail("hasDefaultPartition", runway.defaults() > 0);
		if (runway.defaults() > 0) {
			health.withDetail("defaultPartitionUsed",
					jdbc.sql(DEFAULT_USED_SQL).query(Boolean.class).single());
		}

		if (runway.lastCovered() == null) {
			// Not "fine": it means the parse found no bound at all, which is either
			// a table that is no longer partitioned or a bound format that moved
			// under us. Either way the number below would be a lie, so say so
			// rather than reporting a runway nobody computed.
			health.withDetail("runway", "unknown - no partition bound could be read");
			return health.build();
		}

		long days = Duration.between(clock.instant(), runway.lastCovered().toInstant()).toDays();
		health.withDetail("lastCovered", runway.lastCovered().toInstant().toString());
		health.withDetail("daysRemaining", days);
		// The flag, not a status. A boolean the heartbeat can read without
		// re-deriving the threshold, and without this contributor being able to
		// restart the JVM over it.
		health.withDetail("low", days < properties.partitionRunwayWarning().toDays());
		return health.build();
	}

	private record Runway(@Nullable OffsetDateTime lastCovered, int defaults, int partitions) {}
}
