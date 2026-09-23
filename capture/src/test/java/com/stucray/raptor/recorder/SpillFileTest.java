package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stucray.raptor.rawstore.RawMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class SpillFileTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Instant SPILLED_AT = Instant.parse("2026-09-01T18:00:00Z");

	/**
	 * A capture message survives the round trip unchanged.
	 *
	 * <p>The spill's whole promise is that the messages come back exactly as they
	 * would have been written, so the fidelity check uses whole capture frames in
	 * the wire's shape (the synthetic sample, #303) rather than a fragment written
	 * to match the format.
	 */
	@Test
	void roundTripsRealCaptureMessages(@TempDir Path directory) throws IOException {
		List<RawMessage> original = framedSample();

		Path file = directory.resolve("spill.ndjson");
		Files.writeString(file, SpillFile.render(original, SpillCause.DB_UNAVAILABLE, SPILLED_AT),
				StandardCharsets.UTF_8);
		SpillFile.Contents read = SpillFile.read(file);

		assertThat(read.header().cause()).isEqualTo(SpillCause.DB_UNAVAILABLE);
		assertThat(read.header().spilledAt()).isEqualTo(SPILLED_AT);
		assertThat(read.header().sessionId()).isEqualTo(original.getFirst().sessionId());
		assertThat(read.messages()).hasSameSizeAs(original);

		for (int i = 0; i < original.size(); i++) {
			RawMessage before = original.get(i);
			RawMessage after = read.messages().get(i);
			assertThat(after.marketId()).isEqualTo(before.marketId());
			assertThat(after.pt()).isEqualTo(before.pt());
			assertThat(after.receivedAt()).isEqualTo(before.receivedAt());
			assertThat(after.seq()).isEqualTo(before.seq());
			assertThat(MAPPER.readTree(after.payload())).isEqualTo(MAPPER.readTree(before.payload()));
		}
	}

	/** A live frame has no receipt clock of its own; null must survive as null. */
	@Test
	void keepsAnAbsentReceiptClockAbsent(@TempDir Path directory) throws IOException {
		RawMessage message = new RawMessage(3L, null, "1.234",
				Instant.ofEpochMilli(1787490204789L), null, 7L, "{\"id\":\"1.234\"}");

		Path file = directory.resolve("spill.ndjson");
		Files.writeString(file, SpillFile.render(List.of(message), SpillCause.QUEUE_FULL, SPILLED_AT));

		assertThat(SpillFile.read(file).messages().getFirst().receivedAt()).isNull();
	}

	/**
	 * A file whose row count disagrees with its header is refused outright.
	 *
	 * <p>It should be impossible — a file is renamed into place only once written
	 * and forced — but the consequence of ingesting one anyway is a ledger row
	 * claiming a file is fully accounted for while some of its messages are
	 * missing, which is a lie the system has no way to detect later.
	 */
	@Test
	void refusesATornFile(@TempDir Path directory) throws IOException {
		List<RawMessage> messages = framedSample();
		String rendered = SpillFile.render(messages, SpillCause.DB_UNAVAILABLE, SPILLED_AT);
		String torn = rendered.substring(0, rendered.length() / 2);
		Path file = directory.resolve("torn.ndjson");
		Files.writeString(file, torn.substring(0, torn.lastIndexOf('\n') + 1));

		assertThatThrownBy(() -> SpillFile.read(file))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("header says");
	}

	@Test
	void refusesAFileWithNoHeader(@TempDir Path directory) throws IOException {
		Path file = directory.resolve("headerless.ndjson");
		Files.writeString(file, "{\"s\":1,\"m\":\"1.234\",\"p\":1,\"r\":1,\"q\":0,\"d\":{}}\n");

		assertThatThrownBy(() -> SpillFile.read(file))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("no header");
	}

	private static List<RawMessage> framedSample() throws IOException {
		StreamFramer framer = new StreamFramer();
		List<RawMessage> messages = new java.util.ArrayList<>();
		List<String> lines =
				CaptureSampleFiles.messageLines(CaptureSampleFiles.file("1.900000003"));
		for (String line : lines) {
			messages.addAll(framer.frame(new StreamFrame(line, SPILLED_AT), 11L, messages.size()));
		}
		return messages;
	}
}
