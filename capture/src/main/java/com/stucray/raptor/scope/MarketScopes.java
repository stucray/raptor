package com.stucray.raptor.scope;

import com.stucray.raptor.datasource.Acquisition;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@code raw.market_scope}, read and written. */
@Component
class MarketScopes {

	private final JdbcClient jdbc;
	private final Clock clock;

	MarketScopes(@Acquisition JdbcClient acquisitionJdbcClient, Clock clock) {
		this.jdbc = acquisitionJdbcClient;
		this.clock = clock;
	}

	/**
	 * Record what the catalogue said about a market, entering it into scope if it
	 * is new.
	 *
	 * <p>The conflict clause is deliberately narrow: descriptive fields are
	 * refreshed every poll — a kickoff really does move — but <b>state is left
	 * alone</b>. A poll that saw a market it already knows about must not walk a
	 * LIVE market back to PENDING, and it is the one place where an upsert would
	 * quietly do exactly that.
	 *
	 * <p>The exception is a market that had left scope and has come back: a
	 * rescheduled fixture reappearing in the catalogue is a market to capture,
	 * not a closed row to preserve, so DONE is resurrected to PENDING.
	 */
	void seen(CatalogueMarket market, boolean requested) {
		jdbc.sql("""
						insert into raw.market_scope (
								market_id, event_id, event_name, competition_id, competition_name,
								market_type, country_code, kickoff, requested, state, state_changed_at)
						values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
						on conflict (market_id) do update set
								event_id = excluded.event_id,
								event_name = excluded.event_name,
								competition_id = excluded.competition_id,
								competition_name = excluded.competition_name,
								country_code = excluded.country_code,
								kickoff = excluded.kickoff,
								requested = excluded.requested,
								state = case when raw.market_scope.state = 'DONE' then 'PENDING'
										else raw.market_scope.state end,
								exit_reason = case when raw.market_scope.state = 'DONE' then null
										else raw.market_scope.exit_reason end,
								state_changed_at = case when raw.market_scope.state = 'DONE'
										then excluded.state_changed_at
										else raw.market_scope.state_changed_at end""")
				.params(Arrays.asList(market.marketId(), market.eventId(), market.eventName(),
						market.competitionId(), market.competitionName(), market.marketType(),
						market.countryCode(), utc(market.kickoff()), requested, now()))
				.update();
	}

	/** Everything still in scope, for the planner and the guards. */
	List<ScopedMarket> open() {
		return jdbc.sql("""
						select market_id, event_id, kickoff, state, in_play_since, requested
						from raw.market_scope
						where state <> 'DONE'
						order by kickoff nulls last, market_id""")
				.query((rs, row) -> new ScopedMarket(
						rs.getString("market_id"),
						rs.getString("event_id"),
						instant(rs.getObject("kickoff", OffsetDateTime.class)),
						ScopeState.valueOf(rs.getString("state")),
						instant(rs.getObject("in_play_since", OffsetDateTime.class)),
						rs.getBoolean("requested")))
				.list();
	}

	/**
	 * Set the wire membership to exactly these markets.
	 *
	 * <p><b>Both directions, in one write, because the subscription is a
	 * replacement and not an addition.</b> {@code marketSubscription} carries the
	 * whole market list every time, so a market that was in the last plan and is
	 * not in this one has left the wire — and recording only the entries is what
	 * made the ledger overstate capture for a whole card (#219). On 2026-09-08
	 * the planner trimmed to 200, said so, and the table held 205 rows in
	 * {@code SUBSCRIBED} at the same instant; by kickoff the displaced rows had
	 * gone on to {@code LIVE}, and 31 markets the ledger called live had received
	 * no message in five minutes of in-play. An absence is invisible: a market
	 * with no messages and a LIVE record looks exactly like one that was captured
	 * and quiet, so the error ran in the direction that hides a gap.
	 *
	 * <p>A displaced market goes back to {@code PENDING} and not to {@code DONE}:
	 * it has not left scope, it has lost a slot, and the next plan may well take
	 * it back. That is also why nothing is written to {@code exit_reason}, which
	 * answers "why did this leave scope" and is constrained to {@code DONE} rows;
	 * {@code state_changed_at} is when it left the wire.
	 *
	 * <p><b>An empty list is not a removal.</b> The recorder keeps its current
	 * subscription when scope empties rather than sending an empty one, so the
	 * markets really are still on the wire and the rows must say so.
	 */
	void subscribed(Collection<String> marketIds) {
		if (marketIds.isEmpty()) {
			return;
		}
		String[] ids = marketIds.toArray(String[]::new);
		jdbc.sql("""
						update raw.market_scope
						set state = case when market_id = any (?) then 'SUBSCRIBED'
								else 'PENDING' end,
								state_changed_at = ?
						where state <> 'DONE'
							and case when market_id = any (?) then state = 'PENDING'
									else state in ('SUBSCRIBED', 'LIVE') end""")
				.params(List.of(ids, now(), ids))
				.update();
	}

	/**
	 * First sighting in-play. Idempotent: the clock is set once and kept.
	 *
	 * <p><b>The clock is a fact about the fixture; LIVE is a fact about the
	 * recorder.</b> So {@code in_play_since} is dated for any market in scope —
	 * it is what the {@code IN_PLAY_ELAPSED} guard measures from, and a market
	 * that never fitted under the cap still has to leave scope when its match is
	 * over — while {@code LIVE} is reached only from {@code SUBSCRIBED}, because
	 * it is the state the planner reads as "being recorded". Promoting a market
	 * that was never subscribed is half of #219: it told the planner a market
	 * held a slot it did not hold, and told the ledger a fixture was captured
	 * in-play when nothing was listening to it.
	 *
	 * <p>Which leaves {@code PENDING} carrying two cases — before kickoff, and in
	 * play but not on the wire. They are separated by {@code in_play_since}, and
	 * a reader asking whether a fixture really started should ask that column
	 * rather than the state.
	 */
	void live(String marketId) {
		jdbc.sql("""
						update raw.market_scope
						set in_play_since = coalesce(in_play_since, ?),
								state = case when state = 'SUBSCRIBED' then 'LIVE' else state end,
								state_changed_at = case when state = 'SUBSCRIBED' then ?
										else state_changed_at end
						where market_id = ? and state <> 'DONE'""")
				.params(List.of(now(), now(), marketId))
				.update();
	}

	/** Out of scope, with the reason kept. */
	void done(String marketId, ScopeExit reason) {
		jdbc.sql("""
						update raw.market_scope
						set state = 'DONE', exit_reason = ?, state_changed_at = ?
						where market_id = ? and state <> 'DONE'""")
				.params(List.of(reason.name(), now(), marketId))
				.update();
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
	}

	/** pgjdbc cannot infer a SQL type for an {@code Instant} parameter. */
	private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

	private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
