package com.stucray.raptor.recorder;

/**
 * Why the stream stopped arriving.
 *
 * <p>Three causes, not a taxonomy: each one is a thing the recorder <em>knows</em>
 * rather than something a later script has to infer from timestamp arithmetic
 * over the messages that did arrive.
 */
public enum GapCause {

	/**
	 * The machine suspended: wall clock advanced materially more than the
	 * monotonic clock.
	 *
	 * <p>Sleep is the largest single cause of holes in the existing corpus, and
	 * the one a bounded launchd run at least made legible. A resident service does
	 * not stop a laptop sleeping, so the least it can do is say so exactly.
	 */
	SLEEP,

	/**
	 * The socket claimed to be alive and sent nothing.
	 *
	 * <p>Betfair sends a heartbeat every 5 seconds, so silence past the timeout is
	 * a dead connection whatever the socket believes. macOS sleep in particular
	 * leaves sockets that look fine until a write finally fails, which can take
	 * minutes — minutes of a match, during which nothing would have been recorded
	 * and nothing would have said so.
	 */
	SILENCE,

	/** The source ended or failed, and the supervisor reconnected. */
	DISCONNECT
}
