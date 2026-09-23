package com.stucray.raptor.recorder;

import com.stucray.raptor.rawstore.RawMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The spill file's format, in one place: how a batch is written and how it is
 * read back.
 *
 * <p>Newline-delimited JSON, a header line and then one line per message,
 * deliberately the same shape as a capture file. The header carries what the
 * ledger row will need — which session, why it spilled, when — so a file found
 * on disk after a crash is self-describing rather than dependent on a naming
 * convention somebody has to reverse-engineer at the worst possible moment.
 *
 * <p>The payload is embedded as JSON rather than as an escaped string. It came
 * off the wire as JSON and is going into a {@code jsonb} column; keeping it
 * readable means a spill file can be inspected with {@code jq} during the
 * incident that produced it.
 */
final class SpillFile {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SpillFile() {}

	/** What the header line says, and what the ledger row is built from. */
	record Header(long sessionId, SpillCause cause, Instant spilledAt, int messages) {}

	/** A file read back off disk. */
	record Contents(Header header, List<RawMessage> messages) {}

	/**
	 * Render a batch. Short keys because this is written while the database is
	 * refusing writes: every byte is a byte closer to the disk-full case, which is
	 * the one failure the spill cannot survive.
	 */
	static String render(List<RawMessage> messages, SpillCause cause, Instant spilledAt) {
		RawMessage first = messages.getFirst();
		StringBuilder out = new StringBuilder(messages.size() * 512);
		out.append("{\"_spill\":{\"session_id\":").append(first.sessionId())
				.append(",\"cause\":\"").append(cause.name())
				.append("\",\"spilled_at\":").append(spilledAt.toEpochMilli())
				.append(",\"messages\":").append(messages.size())
				.append("}}\n");
		for (RawMessage message : messages) {
			out.append("{\"s\":").append(message.sessionId())
					.append(",\"m\":").append(quote(message.marketId()))
					.append(",\"p\":").append(message.pt().toEpochMilli())
					.append(",\"r\":")
					.append(message.receivedAt() == null ? "null" : message.receivedAt().toEpochMilli())
					.append(",\"q\":").append(message.seq())
					.append(",\"d\":").append(message.payload())
					.append("}\n");
		}
		return out.toString();
	}

	/**
	 * Read a file back.
	 *
	 * <p>Strict, unlike the stream framer: a spill file is ours, was written in
	 * one act, and was renamed into place only once it was complete. A line that
	 * does not parse here is not a torn frame off a socket — it is a file that
	 * cannot be trusted, and quietly ingesting the half of it that happens to
	 * parse would put messages in {@code raw} while the ledger claims the file is
	 * fully accounted for.
	 */
	static Contents read(Path file) throws IOException {
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		if (lines.isEmpty()) {
			throw new IOException("spill file " + file.getFileName() + " is empty");
		}
		JsonNode headerLine = MAPPER.readTree(lines.getFirst()).get("_spill");
		if (headerLine == null) {
			throw new IOException("spill file " + file.getFileName() + " has no header");
		}
		Header header = new Header(
				headerLine.get("session_id").asLong(),
				SpillCause.valueOf(headerLine.get("cause").asString()),
				Instant.ofEpochMilli(headerLine.get("spilled_at").asLong()),
				headerLine.get("messages").asInt());

		List<RawMessage> messages = new ArrayList<>(header.messages());
		for (String line : lines.subList(1, lines.size())) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode row = MAPPER.readTree(line);
			JsonNode received = row.get("r");
			messages.add(new RawMessage(
					row.get("s").asLong(),
					null,
					row.get("m").asString(),
					Instant.ofEpochMilli(row.get("p").asLong()),
					received == null || received.isNull()
							? null : Instant.ofEpochMilli(received.asLong()),
					row.get("q").asLong(),
					MAPPER.writeValueAsString(row.get("d"))));
		}
		if (messages.size() != header.messages()) {
			// The header is written before the rows and counts them, so a mismatch
			// means a torn file — which should be impossible, because a file is
			// renamed into place only once fully written and forced. Refuse it
			// rather than ingest a partial batch under a ledger row that says
			// otherwise.
			throw new IOException("spill file " + file.getFileName() + " holds " + messages.size()
					+ " message(s), header says " + header.messages());
		}
		return new Contents(header, messages);
	}

	private static String quote(String value) {
		return MAPPER.writeValueAsString(value);
	}
}
