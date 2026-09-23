package com.stucray.raptor.projection;

import com.stucray.raptor.datasource.Acquisition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The last gap that overlapped play, read from {@code query}.
 *
 * <p>Read from the projection rather than from {@code raw}, which is where the
 * recorder writes gaps, because the question needs both halves: the intervals
 * and what was on at the time. {@code ledger.market_scope} carries
 * {@code in_play_since}, and {@code CaptureLedgerRefresh} keeps both tables
 * within five minutes of the write side — so a suspend is reportable within a
 * refresh of the wake rather than waiting for the nightly close-out.
 *
 * <p><b>The interval predicate is the screen's, verbatim.</b>
 * {@code CollectorHealthController.gapsDuringPlay()} already decides what "a gap
 * during play" means, and two spellings of one rule drift. What is added here is
 * the counts and the identity, which a count of all history cannot carry.
 */
@Component
class CaptureGapLedger implements GapHistory {

	/**
	 * How far back a gap is still worth reporting.
	 *
	 * <p>A day, and generously so: the gap this exists for ends at the wake, so
	 * it is minutes old when it is first read. The bound is not about missing a
	 * suspend, it is about what the <em>first</em> read after a fresh de-dupe
	 * key announces — without it, deploying this would push a finding about
	 * whatever the ledger's oldest in-play gap happened to be.
	 */
	private static final Duration WINDOW = Duration.ofHours(24);

	/**
	 * Shorter than this and the gap is an ordinary reconnect, not a finding.
	 *
	 * <p><b>Taken from the mechanism, not fitted to the rows.</b> The recorder's
	 * own reconnect backoff caps at a minute (#176), so anything inside that is
	 * within what the reconnect machinery is built to absorb, and reporting it
	 * would be reporting the machinery working.
	 *
	 * <p>The ledger agrees without being the argument, and it is worth writing
	 * the numbers down because they are what showed this was needed (#298).
	 * Every one of the 24 gaps that had ever overlapped play was a
	 * {@code DISCONNECT} of 30 seconds or less, eighteen of them sub-second —
	 * and sixteen of those were one card, 2026-09-05. Without a floor that night
	 * would have sent sixteen notifications about reconnects that lost under a
	 * second each, which is how a channel stops being read. The two {@code SLEEP}
	 * rows in the same ledger are 67s and 255s.
	 *
	 * <p>It is a duration and not a cause list on purpose: a five-minute Betfair
	 * outage in the second half is worth knowing about whatever the ledger calls
	 * it. What is excluded is brevity, not kind.
	 */
	private static final Duration FLOOR = Duration.ofSeconds(60);

	/**
	 * A market counts as having been on if the gap overlapped its time in
	 * scope; in play, if the gap overlapped its time in play.
	 *
	 * <p>{@code state_changed_at} is when a DONE market stopped being watched,
	 * and for anything not DONE the market is still on, so the interval has no
	 * upper bound to test — which is why {@code :now} appears in the same place
	 * the screen's query puts {@code now()}.
	 */
	private static final String LAST_GAP_SQL = """
			select g.ended_at, g.cause,
				round(extract(epoch from (g.ended_at - g.started_at)))::bigint as seconds,
				(select count(*) from ledger.market_scope s
					where s.first_seen_at < g.ended_at
						and g.started_at < case when s.state = 'DONE'
							then s.state_changed_at else :now end) as markets,
				(select count(*) from ledger.market_scope s
					where s.in_play_since is not null
						and g.ended_at > s.in_play_since
						and g.started_at < case when s.state = 'DONE'
							then s.state_changed_at else :now end) as live
			from ledger.capture_gap g
			where g.started_at > :since
				and g.ended_at - g.started_at >= cast(:floor as interval)
				and exists (
					select 1 from ledger.market_scope s
					where s.in_play_since is not null
						and g.started_at < case when s.state = 'DONE'
							then s.state_changed_at else :now end
						and g.ended_at > s.in_play_since)
			order by g.ended_at desc, g.id desc
			limit 1""";

	private final JdbcClient jdbc;
	private final Clock clock;

	CaptureGapLedger(@Acquisition JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	public Optional<GapInPlay> lastGapDuringPlay() {
		Instant now = clock.instant();
		return jdbc.sql(LAST_GAP_SQL)
				.param("now", at(now))
				.param("since", at(now.minus(WINDOW)))
				.param("floor", FLOOR.toSeconds() + " seconds")
				.query((rs, i) -> new GapInPlay(
						Objects.requireNonNull(rs.getObject("ended_at", OffsetDateTime.class))
								.toInstant(),
						rs.getString("cause"),
						rs.getLong("seconds"),
						rs.getInt("markets"),
						rs.getInt("live")))
				.optional();
	}

	/** pgjdbc cannot infer a SQL type for an {@code Instant} parameter. */
	private static OffsetDateTime at(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
