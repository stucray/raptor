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
	 * not a closed row to preserve, so DONE is resurrected to PENDING — on no
	 * connection, since PENDING holds no slot.
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
								connection_slot = case when raw.market_scope.state = 'DONE' then null
										else raw.market_scope.connection_slot end,
								state_changed_at = case when raw.market_scope.state = 'DONE'
										then excluded.state_changed_at
										else raw.market_scope.state_changed_at end""")
				.params(Arrays.asList(market.marketId(), market.eventId(), market.eventName(),
						market.competitionId(), market.competitionName(), market.marketType(),
						market.countryCode(), utc(market.kickoff()), requested, now()))
				.update();
	}

	/**
	 * Enter a market that had already kicked off when discovery first saw it —
	 * and only if raptor has never seen it at all (#57).
	 *
	 * <p><b>Insert or nothing, unlike {@link #seen}.</b> A market already known,
	 * in any state, is left exactly as it is. One being recorded needs nothing
	 * from here. One that has left scope must stay out: its kickoff is behind it,
	 * so it is not the rescheduled fixture {@code seen} revives. Reviving a
	 * market the in-play guard retired while Betfair still lists it would not
	 * hold a slot, because the guard retires it again in the same pass, but it
	 * would rewrite the row on every poll: {@code state_changed_at} would move to
	 * each one, and the ledger would stop saying when the market left scope.
	 *
	 * <p>Lateness needs no column of its own: {@code first_seen_at} after
	 * {@code kickoff} is what a late entry looks like.
	 *
	 * @return true when the market was new and is now in scope
	 */
	boolean admitStarted(CatalogueMarket market, boolean requested) {
		return jdbc.sql("""
						insert into raw.market_scope (
								market_id, event_id, event_name, competition_id, competition_name,
								market_type, country_code, kickoff, requested, state, state_changed_at)
						values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
						on conflict (market_id) do nothing""")
				.params(Arrays.asList(market.marketId(), market.eventId(), market.eventName(),
						market.competitionId(), market.competitionName(), market.marketType(),
						market.countryCode(), utc(market.kickoff()), requested, now()))
				.update() == 1;
	}

	/** Everything still in scope, for the planner and the guards. */
	List<ScopedMarket> open() {
		return jdbc.sql("""
						select market_id, event_id, kickoff, state, in_play_since, requested,
								connection_slot
						from raw.market_scope
						where state <> 'DONE'
						order by kickoff nulls last, market_id""")
				.query((rs, row) -> new ScopedMarket(
						rs.getString("market_id"),
						rs.getString("event_id"),
						instant(rs.getObject("kickoff", OffsetDateTime.class)),
						ScopeState.valueOf(rs.getString("state")),
						instant(rs.getObject("in_play_since", OffsetDateTime.class)),
						rs.getBoolean("requested"),
						rs.getObject("connection_slot", Integer.class)))
				.list();
	}

	/**
	 * Set one connection's wire membership to exactly these markets.
	 *
	 * <p><b>Both directions, in one write, because the subscription is a
	 * replacement and not an addition.</b> {@code marketSubscription} carries the
	 * whole market list every time, so a market that was in the connection's last
	 * plan and is not in this one has left the wire — and recording only the
	 * entries is what made the ledger overstate capture for a whole card (#219).
	 * On 2026-09-08 the planner trimmed to 200, said so, and the table held 205
	 * rows in {@code SUBSCRIBED} at the same instant; by kickoff the displaced rows
	 * had gone on to {@code LIVE}, and 31 markets the ledger called live had
	 * received no message in five minutes of in-play. An absence is invisible: a
	 * market with no messages and a LIVE record looks exactly like one that was
	 * captured and quiet, so the error ran in the direction that hides a gap.
	 *
	 * <p><b>The replacement is scoped to this connection slot (#64).</b> Each
	 * connection's subscription replaces only its own, so only rows on this slot
	 * are taken off the wire. A replacement across the whole table, which is what
	 * this was while there was one connection, would have every connection's
	 * resubscribe demote every other connection's markets. A row on the wire with
	 * no recorded slot was subscribed before slots existed, when there was only
	 * the one connection, so it is slot 0's.
	 *
	 * <p>A market listed here that is on another slot moves to this one, keeping
	 * its state: it is on the wire either way.
	 *
	 * <p>A displaced market goes back to {@code PENDING} and not to {@code DONE}:
	 * it has not left scope, it has lost its place, and the next plan may well
	 * take it back. That is also why nothing is written to {@code exit_reason},
	 * which answers "why did this leave scope" and is constrained to {@code DONE}
	 * rows; {@code state_changed_at} is when it left the wire, and it moves only
	 * when the state does.
	 *
	 * <p><b>An empty list is not a removal.</b> The recorder keeps its current
	 * subscription when scope empties rather than sending an empty one, so the
	 * markets really are still on the wire and the rows must say so.
	 */
	void subscribed(int connectionSlot, Collection<String> marketIds) {
		if (marketIds.isEmpty()) {
			return;
		}
		String[] ids = marketIds.toArray(String[]::new);
		OffsetDateTime now = now();
		jdbc.sql("""
						update raw.market_scope
						set connection_slot = case when market_id = any (?) then ? end,
								state = case when market_id <> all (?) then 'PENDING'
										when state = 'PENDING' then 'SUBSCRIBED'
										else state end,
								state_changed_at = case when market_id <> all (?)
										or state = 'PENDING' then ? else state_changed_at end
						where state <> 'DONE'
							and case when market_id = any (?)
									then state = 'PENDING' or connection_slot is distinct from ?
									else state in ('SUBSCRIBED', 'LIVE')
										and coalesce(connection_slot, 0) = ? end""")
				.params(List.of(ids, connectionSlot, ids, ids, now, ids, connectionSlot,
						connectionSlot))
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
