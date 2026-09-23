/**
 * The live write path: socket to {@code raw.stream_message}, and nothing else.
 *
 * <p>Two threads and a bounded queue. The socket-draining loop does framing and
 * addressing only — where a message ends, which market it belongs to, when it
 * was published and when we received it — and hands the block through untouched.
 * The writer drains the queue and COPYs.
 *
 * <p>The invariant this package exists to hold: <b>the socket-draining thread
 * must never block on anything whose progress depends on the database being
 * healthy.</b> That is why the queue is offered to rather than put to, and why a
 * refused offer spills instead of waiting. Blocking briefly on a queue with
 * capacity is fine; blocking until Postgres comes back is not, because the
 * receive buffer then fills, the TCP window closes, and Betfair drops us as a
 * slow consumer — losing the very book the wait was meant to protect.
 *
 * <p>Like {@code ingest}, this package may not reach {@code paddock-wire}. A
 * parse bug must be structurally incapable of costing data; it may only ever
 * cost a rebuild of {@code query}. {@code WritePathIsolationTest} holds it.
 */
package com.stucray.raptor.recorder;
