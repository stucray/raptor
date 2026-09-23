package com.stucray.raptor.ingest;

import com.stucray.raptor.rawstore.RawMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Splits a Python-era capture file into {@code raw.stream_message} rows.
 *
 * <p><b>Framing and addressing only</b>, exactly as {@link RawMessageExtractor}
 * is for the vendor corpus. This reads the message {@code pt}, the receipt clock
 * and each market-change block's {@code id}, and carries the block through
 * untouched. It does not know what a runner is, what a suspension is, or what a
 * price ladder is, and it must never learn.
 *
 * <p>Deliberately does not use {@code paddock-wire}. The parsers live there; the
 * write path cannot reach them.
 */
@Component
class CaptureMessageExtractor {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * @param sessions resolves the session a message was received during
	 * @return the file's messages in file order, plus its header if it has one
	 */
	CaptureContents extract(Path file, ImportedSessions sessions) throws IOException {
		List<RawMessage> messages = new ArrayList<>();
		String metaJson = null;
		long seq = 0;

		try (InputStream raw = Files.newInputStream(file);
				GZIPInputStream gz = new GZIPInputStream(raw);
				BufferedReader reader =
						new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8))) {

			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				JsonNode message;
				try {
					message = MAPPER.readTree(line);
				} catch (JacksonException e) {
					// A truncated tail is expected in this corpus and is not an error:
					// the recorder was killed mid-write on more than one night. Keep
					// everything read so far, exactly as the Python's own parser does.
					break;
				}

				JsonNode meta = message.get("_meta");
				if (meta != null && !meta.isNull()) {
					metaJson = MAPPER.writeValueAsString(meta);
					continue;
				}

				JsonNode pt = message.get("pt");
				if (pt == null || pt.isNull()) {
					// pt is the partition key and is declared not null. A message
					// without one cannot be stored or ordered, so skip it rather than
					// invent a timestamp — and never silently substitute now().
					continue;
				}
				Instant publishTime = Instant.ofEpochMilli(pt.asLong());
				Instant receivedAt = receivedAt(message);
				long sessionId = sessions.at(receivedAt != null ? receivedAt : publishTime);

				for (JsonNode marketChange : marketChanges(message)) {
					JsonNode id = marketChange.get("id");
					if (id == null || id.isNull()) {
						continue;
					}
					messages.add(new RawMessage(sessionId, null, id.asString(), publishTime,
							receivedAt, seq++,
							// Re-serialised rather than substring-sliced, for the reason
							// RawMessageExtractor gives: the target column is jsonb, which
							// normalises key order and whitespace regardless. The files on
							// disk remain the byte-level evidence, and capture_file.sha256
							// is what ties them to this load.
							MAPPER.writeValueAsString(marketChange)));
				}
			}
		}
		return new CaptureContents(metaJson, messages);
	}

	/**
	 * The {@code _meta} header alone, without reading the rest of the file.
	 *
	 * <p>For a superseded capture, whose messages are not loaded: the header is
	 * the one thing in the file that paddock's own recording does not contain,
	 * and it costs one line to keep.
	 */
	@Nullable String header(Path file) throws IOException {
		try (InputStream raw = Files.newInputStream(file);
				GZIPInputStream gz = new GZIPInputStream(raw);
				BufferedReader reader =
						new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8))) {
			String line = reader.readLine();
			if (line == null || line.isBlank()) {
				return null;
			}
			JsonNode meta;
			try {
				meta = MAPPER.readTree(line).get("_meta");
			} catch (JacksonException e) {
				return null;
			}
			return meta == null || meta.isNull() ? null : MAPPER.writeValueAsString(meta);
		}
	}

	/**
	 * The Python wrote one market change per line, as an object; the Betfair
	 * envelope the recorder frames carries an array of them. Both are read, so
	 * that this class describes the shape rather than one producer's habit.
	 */
	private static List<JsonNode> marketChanges(JsonNode message) {
		JsonNode mc = message.get("mc");
		if (mc == null || mc.isNull()) {
			return List.of();
		}
		if (mc.isArray()) {
			List<JsonNode> changes = new ArrayList<>();
			mc.forEach(changes::add);
			return changes;
		}
		return List.of(mc);
	}

	/**
	 * Our clock at receipt. Betfair's {@code pt} and the recorder's own clock
	 * disagree by tens of milliseconds and both matter — and it is the receipt
	 * clock, not the publish clock, that says which session was running.
	 */
	private static @Nullable Instant receivedAt(JsonNode message) {
		JsonNode recv = message.get("recv_ms");
		return recv == null || recv.isNull() ? null : Instant.ofEpochMilli(recv.asLong());
	}
}
