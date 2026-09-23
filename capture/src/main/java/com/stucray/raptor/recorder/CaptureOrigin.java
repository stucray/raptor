package com.stucray.raptor.recorder;

/**
 * Who started a capture session. Mirrors {@code raw.capture_session.origin}'s
 * check constraint, so an invalid value is a compile error rather than a
 * constraint violation at 20:00 on a Saturday.
 */
public enum CaptureOrigin {

	/** The resident recorder, started by the supervisor. */
	RESIDENT,

	/** A human: a replay, an ops call, a one-off. */
	MANUAL
}
