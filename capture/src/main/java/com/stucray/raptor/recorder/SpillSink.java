package com.stucray.raptor.recorder;

import java.util.List;
import com.stucray.raptor.rawstore.RawMessage;

/**
 * Where messages go when the database will not take them.
 *
 * <p>Exceptional by construction: in normal operation nothing is ever handed
 * here. It exists because without it a ten-minute Postgres restart on a Saturday
 * evening permanently loses a match — Betfair's {@code clk} replay covers
 * minutes, not tens of them — and with it that failure costs nothing.
 *
 * <p>An implementation must not block on the database, must not throw, and must
 * be fast enough to be called from the socket-draining thread: it is the fallback
 * for precisely the situations where waiting is what kills the capture.
 */
public interface SpillSink {

	void spill(List<RawMessage> messages, SpillCause cause);
}
