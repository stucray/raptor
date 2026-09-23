/**
 * What the recorder is trying to capture, and why it stopped.
 *
 * <p>This module replaces the capture window. A night used to be a twelve-hour
 * launchd run, which cannot cover a 20:00Z midweek tie and a 12:30Z Saturday
 * kickoff without subscribing to the hours in between, and misses a
 * short-notice reschedule entirely. Scope is per market: a fixture enters when
 * its kickoff comes inside the horizon and leaves when it is over, so the
 * recorder has no schedule at all — it records what is in scope.
 *
 * <p>Nothing here writes to {@code raw.stream_message} and nothing here is on
 * the socket-draining path. Scope reads Betfair's REST catalogue on a slow
 * timer and keeps a table; the recorder reads that table to decide what to
 * subscribe to. Keeping the two apart is what lets the write path stay unable
 * to parse anything (see {@code WritePathIsolationTest}) while scope makes
 * decisions that are entirely about the parsed content of the catalogue.
 */
package com.stucray.raptor.scope;
