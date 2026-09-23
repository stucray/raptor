-- How often Betfair conflated a stream we asked it not to conflate.
--
-- `StreamProperties.conflate` is zero, deliberately: the corpus exists to
-- answer whether a bet could have been filled, and conflation deletes exactly
-- the short suspensions that question turns on. `TlsStreamSource` duly sends
-- `conflateMs: 0`. Betfair conflates anyway, and until now nothing counted it
-- (#132).
--
-- It is not loss, which is why nothing else could see it. A conflated envelope
-- merges several changes and keeps the final state, so the intermediate ones go
-- with no gap row, no error and no silence — `GapCause` is connection-level and
-- correct about loss, the parity gates compare parsers, and the replay bar
-- feeds the same bytes to both derivations so whatever was conflated away
-- cancels.
--
-- The rate is a property of what arrived, so it is counted from the messages
-- rather than reported by the recorder — every conflated envelope carries
-- `"con": true`. Measured across sessions to date it spans forty-fold, 0.0096%
-- to 0.3964%, which is the reason to trend it before anybody proposes a
-- threshold for it.
alter table query.capture_session
	add column conflated bigint not null default 0;

comment on column query.capture_session.conflated is
	'Messages Betfair marked conflated, though conflateMs was 0. Not loss: '
	'summarisation, invisible to every gap and parity check (#132).';
