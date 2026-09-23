package com.stucray.raptor.betfair;

import java.io.Closeable;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * A line-oriented duplex connection: the socket, with the socket taken out.
 *
 * <p>The seam exists so that the protocol above it — authenticate, subscribe,
 * route what comes back, re-subscribe when scope moves — can be tested without
 * Betfair, without TLS and without a network. That protocol is the part with
 * decisions in it; the socket is the part that either connects or does not.
 *
 * <p>Reads and writes may be issued from different threads, and are: the read
 * loop reads while the subscription maintainer writes. Implementations must make
 * concurrent {@link #send} calls safe with each other, but need not make reads
 * safe with reads — exactly one thread ever reads.
 */
interface StreamConnection extends Closeable {

	/** What this connection is, for the session's config record and the logs. */
	String describe();

	/**
	 * Write one protocol message, terminated as Betfair expects.
	 *
	 * @throws IOException if the write fails; the caller treats that as a
	 *     disconnect rather than as a reason to stop recording
	 */
	void send(String json) throws IOException;

	/**
	 * The next line, or {@code null} once the peer has closed.
	 *
	 * @throws IOException on a failed or timed-out read. A read timeout is
	 *     deliberately not special-cased: a socket that has said nothing for six
	 *     heartbeats is dead whatever it claims, and the reconnect that follows
	 *     costs seconds while waiting on it costs a match.
	 */
	@Nullable String readLine() throws IOException;
}
