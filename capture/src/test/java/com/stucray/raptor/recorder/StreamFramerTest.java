package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.rawstore.RawMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class StreamFramerTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Instant READ_CLOCK = Instant.parse("2026-09-01T18:00:00Z");

	private final StreamFramer framer = new StreamFramer();

	@Test
	void framesARealCaptureLineIntoOneAddressedRow() throws IOException {
		Path file = CaptureSampleFiles.file("1.900000004");
		String line = CaptureSampleFiles.messageLines(file).getFirst();
		JsonNode expected = MAPPER.readTree(line);

		List<RawMessage> rows = framer.frame(new StreamFrame(line, READ_CLOCK), 7L, 100L);

		assertThat(rows).hasSize(1);
		RawMessage row = rows.getFirst();
		assertThat(row.sessionId()).isEqualTo(7L);
		assertThat(row.fileId()).isNull();
		assertThat(row.marketId()).isEqualTo(CaptureSampleFiles.marketId(file));
		assertThat(row.pt()).isEqualTo(Instant.ofEpochMilli(expected.get("pt").asLong()));
		assertThat(row.seq()).isEqualTo(100L);
		assertThat(MAPPER.readTree(row.payload())).isEqualTo(expected.get("mc"));
	}

	/**
	 * The receipt clock is the recorder's, not the replayer's.
	 *
	 * <p>{@code pt - received_at} is the only measure of our own latency that will
	 * ever exist, and a replay that restamped it with today's clock would destroy
	 * that for every message it re-wrote while leaving every count and digest in
	 * this slice green.
	 */
	@Test
	void prefersTheFramesOwnReceiptClock() throws IOException {
		String line = CaptureSampleFiles.messageLines(CaptureSampleFiles.file("1.900000004")).getFirst();
		long recorded = MAPPER.readTree(line).get("recv_ms").asLong();

		RawMessage row = framer.frame(new StreamFrame(line, READ_CLOCK), 1L, 0L).getFirst();

		assertThat(row.receivedAt()).isEqualTo(Instant.ofEpochMilli(recorded));
		assertThat(row.receivedAt()).isNotEqualTo(READ_CLOCK);
	}

	/** A live frame carries no recv_ms, so the loop's clock stands in. */
	@Test
	void fallsBackToTheReadLoopsClock() {
		String frame = """
				{"pt":1787490204789,"mc":[{"id":"1.234","rc":[]}]}""";

		RawMessage row = framer.frame(new StreamFrame(frame, READ_CLOCK), 1L, 0L).getFirst();

		assertThat(row.receivedAt()).isEqualTo(READ_CLOCK);
	}

	/**
	 * The live wire shape: {@code mc} is an array.
	 *
	 * <p>Derived from Betfair's published schema — {@code ESASwaggerSchema.json}
	 * declares {@code MarketChangeMessage.mc} an array of {@code MarketChange} —
	 * and <b>not</b> from a captured frame, because the recorder splits the array
	 * before writing and no file on disk contains one. It is flagged as such: S6
	 * must confirm it against a real {@code mcm} the first time one is recorded.
	 */
	@Test
	void framesTheLiveArrayShapeIntoOneRowPerMarket() {
		String frame = """
				{"pt":1787490204789,"clk":"AAAAAA==","mc":[\
				{"id":"1.111","rc":[{"ltp":2.5,"id":47999}]},\
				{"id":"1.222","rc":[{"ltp":1.5,"id":1141}]}]}""";

		List<RawMessage> rows = framer.frame(new StreamFrame(frame, READ_CLOCK), 3L, 40L);

		assertThat(rows).extracting(RawMessage::marketId).containsExactly("1.111", "1.222");
		assertThat(rows).extracting(RawMessage::seq).containsExactly(40L, 41L);
		assertThat(rows).allSatisfy(row -> assertThat(row.pt())
				.isEqualTo(Instant.ofEpochMilli(1787490204789L)));
	}

	/** A capture's `_meta` header names the fixture. It is not a message. */
	@Test
	void ignoresTheCaptureHeader() throws IOException {
		Path file = CaptureSampleFiles.file("1.900000004");
		String header = CaptureSampleFiles.lines(file).getFirst();
		assertThat(header).contains("_meta");

		assertThat(framer.frame(new StreamFrame(header, READ_CLOCK), 1L, 0L)).isEmpty();
		assertThat(framer.unparseable()).isZero();
	}

	/** A heartbeat has no market changes at all — the schema says mc is null there. */
	@Test
	void ignoresAHeartbeat() {
		String heartbeat = """
				{"op":"mcm","pt":1787490204789,"clk":"AAAAAA==","ct":"HEARTBEAT"}""";

		assertThat(framer.frame(new StreamFrame(heartbeat, READ_CLOCK), 1L, 0L)).isEmpty();
	}

	/**
	 * No publish time, no row — and never {@code now()} in its place.
	 *
	 * <p>{@code pt} is the partition key. A message stored under an invented
	 * publish time is worse than a message not stored, because nothing downstream
	 * can tell which it was.
	 */
	@Test
	void refusesAMessageWithNoPublishTime() {
		String frame = """
				{"mc":[{"id":"1.234","rc":[]}]}""";

		assertThat(framer.frame(new StreamFrame(frame, READ_CLOCK), 1L, 0L)).isEmpty();
		assertThat(framer.withoutAddress()).isEqualTo(1);
	}

	@Test
	void refusesABlockWithNoMarketId() {
		String frame = """
				{"pt":1787490204789,"mc":[{"rc":[]},{"id":"1.234","rc":[]}]}""";

		List<RawMessage> rows = framer.frame(new StreamFrame(frame, READ_CLOCK), 1L, 0L);

		assertThat(rows).extracting(RawMessage::marketId).containsExactly("1.234");
		assertThat(framer.withoutAddress()).isEqualTo(1);
	}

	/**
	 * One corrupt frame costs one frame.
	 *
	 * <p>The vendor loader stops at the first unreadable line, because a BASIC
	 * file's tail is truncated and everything past it is gone. A socket is the
	 * opposite: the next frame is fine, and stopping the recorder over one bad one
	 * would turn a corrupt byte into a lost match.
	 */
	@Test
	void skipsAnUnparseableFrameAndKeepsGoing() {
		assertThat(framer.frame(new StreamFrame("{\"pt\":178749020", READ_CLOCK), 1L, 0L)).isEmpty();
		assertThat(framer.unparseable()).isEqualTo(1);

		List<RawMessage> next = framer.frame(
				new StreamFrame("{\"pt\":1787490204789,\"mc\":[{\"id\":\"1.234\"}]}", READ_CLOCK), 1L, 0L);
		assertThat(next).hasSize(1);
	}
}
