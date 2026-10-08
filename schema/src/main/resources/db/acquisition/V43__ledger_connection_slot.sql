-- raptor#66: the capture ledger carries the connection slot, so its readers can
-- stop assuming sessions run one after another.
--
-- WHY. Since V42 a market's scope row and a session's row each record which
-- connection slot they belong to, and the recorder can hold one connection per
-- slot (#65). The ledger is the read side's copy of both, and three of its
-- questions are about ONE connection: which markets a gap cost, which interval
-- between two sessions was a restart, and whether an open session was abandoned.
-- Asked across every connection at once, each gives a wrong answer as soon as
-- there are two.
--
-- NULLABLE, NO DEFAULT, copied verbatim from raw. NULL means what it means there:
-- recorded before connection slots existed, when there was one connection — so
-- the readers treat it as slot 0, and every row from before slots gives exactly
-- the answer it gave before. The ledger is rebuilt from raw, so no row here needs
-- a value written by this migration.
--
-- Grants: the ledger tables are granted at table level (V34), so the new
-- columns are readable by exactly the roles that read the tables.

alter table ledger.market_scope add column connection_slot smallint;

comment on column ledger.market_scope.connection_slot is
	'raw.market_scope.connection_slot, copied: the connection slot that carries, or '
	'last carried, this market (#64). NULL as there; readers treat it as slot 0.';

alter table ledger.capture_session add column connection_slot smallint;

comment on column ledger.capture_session.connection_slot is
	'raw.capture_session.connection_slot, copied: the connection slot this session '
	'occupied (#65). NULL as there; readers treat it as slot 0.';
