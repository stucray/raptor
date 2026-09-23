package com.stucray.raptor.rawstore;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One Betfair MCM market-change block, verbatim, on its way into
 * {@code raw.stream_message}.
 *
 * <p>{@code payload} is the raw JSON text of the {@code mc} entry — never a
 * parsed structure. Nothing on the write path may interpret it: that is the
 * whole reason the system of record stores messages rather than shredded ticks,
 * and it is why a parser bug cannot cost data.
 *
 * @param sessionId provenance for a live capture; null for a vendor file
 * @param fileId provenance for a vendor file; null for a live capture
 * @param seq monotonic within the session or file — a total order under equal pt
 */
public record RawMessage(
		@Nullable Long sessionId,
		@Nullable Long fileId,
		String marketId,
		Instant pt,
		@Nullable Instant receivedAt,
		long seq,
		String payload) {

	public RawMessage {
		if ((sessionId == null) == (fileId == null)) {
			throw new IllegalArgumentException(
					"exactly one of sessionId/fileId must be set (market " + marketId + ")");
		}
	}

	public static RawMessage fromFile(long fileId, String marketId, Instant pt, long seq, String payload) {
		return new RawMessage(null, fileId, marketId, pt, null, seq, payload);
	}
}
