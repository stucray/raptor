-- SUBSCRIBED and LIVE mean "on the wire", and only the wire (#219).
--
-- No DDL: the states and the constraints are unchanged, and every row already
-- in the table keeps the value it has. What changes is the transitions that
-- produce them, and therefore what V10's comments claim — which is the whole
-- reason this migration exists, because a false comment on a table is what the
-- next reader gets from `\d+` and nothing else contradicts it.
--
-- V10 said LIVE is "the catalogue reported it in-play". That was true of the
-- write path and false of what the column was used for: the planner reads
-- SUBSCRIBED and LIVE together as "being recorded" and gives them precedence
-- over a fixture waiting to start. So a market the 200-market cap had never
-- admitted went PENDING -> LIVE at kickoff, told the planner it held a slot it
-- did not hold, and told the capture ledger it was captured in-play.
--
-- Measured on the card of 2026-09-08: 205 rows SUBSCRIBED against a plan of
-- 200, and by 18:58Z 256 rows SUBSCRIBED or LIVE against a 200-market wire
-- subscription, 31 of them with no message at all in the preceding five
-- minutes of in-play. The error ran in the direction that hides a gap — a
-- market with no messages and a LIVE record is indistinguishable from one
-- that was captured and quiet.
--
-- ROWS WRITTEN BEFORE THIS ARE NOT CORRECTED, and cannot be: the table holds
-- one current state per market, not a history, so which of the 2026-09-08 rows
-- were really on the wire is no longer derivable from it. `query.market_scope`
-- counts messages per market, which is the measurement that survives — a DONE
-- market with zero messages is the lost fixture, whatever its state says.

comment on column raw.market_scope.state is
	'PENDING -> SUBSCRIBED -> LIVE -> DONE. SUBSCRIBED and LIVE both mean the '
	'market is in the current marketSubscription, and LIVE that the catalogue '
	'has also reported it in-play; a market displaced from the plan returns to '
	'PENDING, because it has lost a slot rather than left scope. LIVE is '
	'skippable — a market can close without a poll ever catching it in-play — '
	'and a fixture that never kicks off goes PENDING -> DONE on a guard.';

comment on column raw.market_scope.in_play_since is
	'When the catalogue first reported this market in-play, whether or not it '
	'was ever subscribed, and the clock the IN_PLAY_ELAPSED guard measures '
	'from. This and not the state is what says a fixture really started: a '
	'market that never fitted under the cap is PENDING throughout its match.';
