-- A recorder that is alive says so, so that one that died can be told apart.
--
-- `ended_at is null` used to mean two things at once: a capture in progress,
-- and a capture whose JVM was killed before anything could stamp an ending
-- (#129). Rows 7 and 8 said "in progress" for two days. The supervisor's
-- shutdown path deliberately survives an interrupt so the final commit and the
-- ending both complete, but nothing survives SIGKILL, an OOM kill or a
-- container torn down underneath the process.
--
-- The heartbeat is what makes the difference computable rather than assumed:
-- the watchdog advances it while a stream is in flight, so a row left open by a
-- kill carries the last moment the recorder was demonstrably alive. That is
-- also the most honest ending available for it — the recorder cannot report the
-- instant it was killed, and inventing a clean one would be worse than
-- recording an unclean one.
alter table raw.capture_session add column last_seen_at timestamptz;

comment on column raw.capture_session.last_seen_at is
    'Advanced by the watchdog while this session is recording. Null for a '
    'session that never got that far, and for every session written before '
    'V21. Used as the ending when a session is reconciled as ABANDONED.';
