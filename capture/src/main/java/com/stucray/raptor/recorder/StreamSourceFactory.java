package com.stucray.raptor.recorder;

import java.io.IOException;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Builds a fresh {@link StreamSource} for each connection attempt.
 *
 * <p>Separate from the source itself because reconnecting is the normal case,
 * not the exception: a stream that ends is a disconnect to be rebuilt, and a
 * source is single-use by construction so that no state can survive one.
 *
 * <p><b>There is deliberately no production implementation in this slice.</b>
 * The live {@code TlsStreamSource} belongs to S6 with the token provider and
 * subscription planner it needs; landing it here would put the one path where a
 * bug destroys data on the socket, unproven and untestable offline. With no
 * factory present the supervisor takes no lease and records nothing, and says
 * so — which is exactly what this application should do today.
 */
public interface StreamSourceFactory {

	/** What this factory connects to, for the log. */
	String describe();

	/**
	 * Open a stream.
	 *
	 * @throws IOException if the stream could not be opened; the supervisor waits
	 *     and tries again rather than giving up on the night
	 */
	StreamSource open() throws IOException;

	/**
	 * When {@code open()} started waiting for something to connect to, or
	 * {@code null} when it is not waiting.
	 *
	 * <p>The supervisor cannot see this for itself: it calls {@code open()} and
	 * blocks, so a factory that is patiently waiting for a fixture to enter the
	 * horizon and one that is failing to reach the exchange are the same call
	 * from where it stands. Only the factory knows which, and only it knows when
	 * the wait began — so it answers both here, in one method, rather than
	 * leaving the supervisor to stamp a transition it never observes.
	 *
	 * <p>The default is "never waiting", which is the truth for a factory that
	 * always has something to connect to, such as a replay of files already on
	 * disk.
	 */
	default @Nullable Instant awaitingSince() {
		return null;
	}
}
