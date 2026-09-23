package com.stucray.raptor.recorder;

import com.stucray.raptor.rawstore.RawMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Splits one stream frame into {@code raw.stream_message} rows.
 *
 * <p><b>Framing and addressing only.</b> Four fields are read — {@code pt},
 * {@code recv_ms}, and each market-change block's {@code id} — and the block is
 * carried through untouched. This class does not know what a runner is, what a
 * suspension is or what a price ladder is, and it must never learn: everything
 * downstream of the system of record is rebuildable, and nothing that writes to
 * the system of record may depend on a parse being right. The capture path's
 * sibling on the vendor side is {@code RawMessageExtractor}, held to the same
 * rule.
 *
 * <p><b>{@code mc} is an array on the wire and an object in a capture file.</b>
 * Betfair's published schema (<i>ESASwaggerSchema.json</i>,
 * {@code MarketChangeMessage.mc}) declares it {@code "type": "array"} of
 * {@code MarketChange}, "will be null on a heartbeat"; the Python recorder
 * iterates that array and writes one line per market, so all 5,485,830 message
 * lines in the 541 captures on disk carry a single object instead. Both are
 * accepted, because replaying a capture must produce exactly the rows the live
 * path would have produced from the frame it came from — that equivalence is
 * what makes an offline test evidence about the online path. The array branch is
 * the only thing here derived from the spec rather than from a captured frame,
 * and S6 must confirm it against a real {@code mcm} the first time one is
 * recorded.
 *
 * <p><b>A frame that cannot be parsed is skipped, not fatal.</b> The vendor
 * loader stops at the first unreadable line because a BASIC file's tail is
 * truncated and everything after it is gone; a live socket is the opposite case
 * — one malformed frame says nothing about the next one, and stopping the
 * recorder over it would turn a corrupt byte into a lost match.
 */
final class StreamFramer {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private long unparseable;
	private long withoutAddress;

	/**
	 * @param sessionId the capture session these rows belong to
	 * @param firstSeq sequence number for the first row produced; the caller
	 *     advances its counter by the size of the result
	 * @return one row per market-change block, in frame order
	 */
	List<RawMessage> frame(StreamFrame frame, long sessionId, long firstSeq) {
		JsonNode message;
		try {
			message = MAPPER.readTree(frame.json());
		} catch (JacksonException e) {
			unparseable++;
			return List.of();
		}

		// A capture opens with a `_meta` header naming the fixture. It is not a
		// message: counting it as one would make every count assertion in this
		// slice disagree with the file it was derived from.
		if (message.has("_meta")) {
			return List.of();
		}

		JsonNode pt = message.get("pt");
		if (pt == null || pt.isNull()) {
			// pt is the partition key and is declared not null. Skip rather than
			// invent a timestamp, and never substitute now(): a message stored under
			// the wrong publish time is worse than a message not stored, because
			// nothing downstream can tell.
			withoutAddress++;
			return List.of();
		}
		Instant publishTime = Instant.ofEpochMilli(pt.asLong());

		// The frame's own receipt clock wins where it has one. A replayed capture
		// then keeps the receipt time it really had, so `pt - received_at` — the
		// only measure of our own latency that exists — survives the round trip.
		JsonNode recv = message.get("recv_ms");
		Instant receivedAt = recv == null || recv.isNull()
				? frame.receivedAt()
				: Instant.ofEpochMilli(recv.asLong());

		JsonNode mc = message.get("mc");
		if (mc == null || mc.isNull()) {
			return List.of();
		}

		List<RawMessage> rows = new ArrayList<>();
		long seq = firstSeq;
		for (JsonNode marketChange : mc.isArray() ? mc : List.of(mc)) {
			JsonNode id = marketChange.get("id");
			if (id == null || id.isNull()) {
				withoutAddress++;
				continue;
			}
			rows.add(new RawMessage(
					sessionId,
					null,
					id.asString(),
					publishTime,
					receivedAt,
					seq++,
					// Re-serialised rather than substring-sliced. The column is jsonb,
					// which normalises key order, whitespace and duplicate keys anyway,
					// so a byte-exact slice would buy nothing that survives the write.
					MAPPER.writeValueAsString(marketChange)));
		}
		return rows;
	}

	/** Frames that were not valid JSON. */
	long unparseable() {
		return unparseable;
	}

	/** Blocks with no {@code pt}, or no {@code id} to address them by. */
	long withoutAddress() {
		return withoutAddress;
	}
}
