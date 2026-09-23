package com.stucray.raptor.recorder;

import java.time.Instant;

/**
 * What the recorder is doing, for anything outside this package that needs to
 * judge it.
 *
 * <p>Three methods wide on purpose. The supervisor is the write path's engine
 * and stays package-private; what is published is the facts a verdict needs and
 * nothing that would let a caller start or stop a capture. The judgement that
 * uses them lives in {@code com.stucray.raptor.capture}, because it takes
 * scope to make and neither module may see the other (#131).
 */
public interface RecorderStatus {

	/** The state to report, derived where the stored one cannot tell the truth. */
	RecorderState state();

	/** Since when it has been in that state. */
	Instant stateSince();

	/**
	 * How many capture sessions in a row have ended having written nothing.
	 *
	 * <p><b>The fact the state word cannot carry.</b> A recorder that connects,
	 * is refused, and rebuilds every seven seconds passes through RECORDING on
	 * every cycle, so {@code state()} is whatever the sample caught and
	 * {@code stateSince()} never grows past single digits — the clock added by
	 * #144 measures dwell in the current state, which a fast loop resets. On
	 * 2026-09-05 that combination read UP through a four-minute total outage
	 * (#171).
	 *
	 * <p>Attempts are the durable evidence: sixteen sessions were started in four
	 * minutes and every one ended {@code framed=0 written=0}. An attempt that
	 * records nothing is not a reconnect, it is a failure, and consecutive ones
	 * are a recorder that cannot hold a connection whatever the reason. Zero
	 * whenever the last one wrote anything at all.
	 *
	 * <p><b>An attempt is not always a session.</b> A stream that could not be
	 * opened at all counts too — that is the shape a revoked app key, an invalid
	 * session token or upstream maintenance takes, and none of them ever reaches
	 * a session row.
	 */
	int consecutiveFailedAttempts();
}
