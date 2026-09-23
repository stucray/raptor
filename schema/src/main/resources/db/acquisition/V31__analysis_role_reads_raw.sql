-- #312 (PRD #309): overround-analysis gains READ access to `raw`.
--
-- WHY THIS REVERSES V27'S "NO GRANT ON raw". V27 said the contract was `query`
-- and `team_alias`, and that was right while paddock owned the projection: the
-- analysis side consumed what paddock had already parsed. #309 moves the
-- projection the other way. overround-analysis builds `query` itself now, which
-- it cannot do without reading what `query` is projected FROM.
--
-- WHAT DOES NOT CHANGE, AND IT IS THE HALF THAT MATTERS: this is SELECT, and
-- only SELECT. `raw` is append-only, unreplayable and single-writer; that writer
-- is paddock today and raptor after the cutover. A reader cannot make the system
-- of record wrong. `AnalysisRoleBoundaryTest` asserts the writes stay refused.
--
-- WHY ALL OF `raw` RATHER THAN raw.football_file ALONE. Slice #312 needs only
-- the football archive, but #313 and #314 need the historic and live Betfair
-- tables immediately after, and each grant costs a migration and a deploy window
-- with nothing in scope. The end state is user story 26 — read access to `raw`
-- and the capture ledger — so this states it once.
--
-- THE DEFAULT PRIVILEGES ARE DELIBERATE and are the part to think about before
-- copying this pattern. They mean a table added to `raw` LATER is readable by
-- this role without anybody granting it, which is what the projection needs (a
-- new source adapter's table is projectable on the day it lands) and is also a
-- standing decision that `raw` holds nothing this role may not see. `raw` holds
-- captured upstream data and nothing else; if that ever stops being true, this
-- line is the one to revisit.
--
-- REVERSAL: scripts/rollback/V31__analysis_role_reads_raw.sql. The pre-state is
-- exactly nothing — no usage, no select, no default privileges — so the reversal
-- restores exactly, which is the property #256 found a reversal can quietly
-- fail. Probe it the same way: both sides counted, in a transaction you roll
-- back.

grant usage on schema raw to overround_analysis;

grant select on all tables in schema raw to overround_analysis;

-- `alter default privileges` applies to what the GRANTOR creates afterwards, so
-- this must name the role that creates `raw`'s tables — the migration identity,
-- which is the current user here and is `paddock` on every database that has
-- run V1. Spelled with `for role current_user` rather than left implicit, so the
-- statement says which grantor it depends on rather than leaving it to whoever
-- happens to run it.
alter default privileges for role current_user in schema raw
	grant select on tables to overround_analysis;
