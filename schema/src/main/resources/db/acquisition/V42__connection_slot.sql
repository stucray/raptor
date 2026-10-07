-- raptor#64: record which stream connection carries each market, and which
-- connection each capture session was.
--
-- WHY. Betfair caps a stream connection at 200 markets and allows several
-- connections per app key (#56, #63), so capture is to be partitioned across a
-- fixed set of connections, each in a numbered CONNECTION SLOT (0, 1, ...). The
-- slot is raptor's own number for a connection: it outlives reconnects, and it is
-- not Betfair's per-socket connectionId, which changes with every socket.
--
-- Which slot carries a market is a capture fact. The subscription of a slot is a
-- replacement, not an addition, so recording it is what lets one slot's
-- resubscribe leave every other slot's markets as they were: until now the wire
-- membership was replaced across the whole table, which is right for one
-- connection and wrong for two.
--
-- NULLABLE, NO DEFAULT. NULL means "recorded before connection slots existed",
-- which is every row this migration finds: a default of 0 would claim a fact
-- nobody recorded. On market_scope a NULL also means "not on the wire", since a
-- PENDING market holds no slot. capture_session.connection_slot is only added
-- here; the recorder starts writing it in #65.
--
-- A row that has left scope keeps the slot that last carried it.

alter table raw.market_scope add column connection_slot smallint;

alter table raw.market_scope add constraint market_scope_connection_slot_non_negative
	check (connection_slot >= 0);

-- PENDING is "not in any subscription", so it holds no slot.
alter table raw.market_scope add constraint market_scope_pending_holds_no_slot
	check (state <> 'PENDING' or connection_slot is null);

comment on column raw.market_scope.connection_slot is
	'The connection slot (0, 1, ...) whose subscription carries this market, or '
	'last carried it once DONE: raptor''s own number for a stream connection, '
	'stable across reconnects and unrelated to Betfair''s connectionId (#64). NULL '
	'while PENDING, and on every row written before connection slots existed.';

alter table raw.capture_session add column connection_slot smallint;

alter table raw.capture_session add constraint capture_session_connection_slot_non_negative
	check (connection_slot >= 0);

comment on column raw.capture_session.connection_slot is
	'The connection slot this session''s stream connection occupied (#64, written '
	'from #65). NULL: a session recorded before connection slots existed, or one '
	'loaded from a file.';
