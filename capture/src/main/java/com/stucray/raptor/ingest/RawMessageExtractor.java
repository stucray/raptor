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
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Splits a vendor BASIC file into {@code raw.stream_message} rows.
 *
 * <p><b>Framing and addressing only.</b> This reads exactly three things — the
 * message {@code pt}, and each market-change block's {@code id} — and carries
 * the block through untouched. It does not know what a runner is, what a
 * suspension is, or what a price ladder is, and it must never learn: everything
 * downstream of the system of record can be rebuilt, and nothing that writes to
 * the system of record may depend on a parse being right.
 *
 * <p>Deliberately does not use {@code paddock-wire}. The parsers live there; the
 * write path cannot reach them.
 */
@Component
class RawMessageExtractor {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * @param fileId provenance row this file's messages belong to
	 * @return one row per market-change block, in file order
	 */
	public List<RawMessage> extract(Path file, long fileId) throws IOException {
		List<RawMessage> messages = new ArrayList<>();
		long seq = 0;

		try (InputStream raw = Files.newInputStream(file);
				BZip2CompressorInputStream bz = new BZip2CompressorInputStream(raw, true);
				BufferedReader reader =
						new BufferedReader(new InputStreamReader(bz, StandardCharsets.UTF_8))) {

			String line;
			while ((line = reader.readLine()) != null) {
				JsonNode message;
				try {
					message = MAPPER.readTree(line);
				} catch (JacksonException e) {
					// A truncated tail is expected in this corpus and is not an error:
					// keep everything read so far, exactly as the Python does. The
					// file's message count records what was actually recoverable.
					break;
				}

				JsonNode pt = message.get("pt");
				if (pt == null || pt.isNull()) {
					// pt is the partition key and is declared not null. A message
					// without one cannot be stored or ordered, so skip it rather than
					// invent a timestamp — and never silently substitute now().
					continue;
				}
				Instant publishTime = Instant.ofEpochMilli(pt.asLong());

				for (JsonNode marketChange : message.path("mc")) {
					JsonNode id = marketChange.get("id");
					if (id == null || id.isNull()) {
						continue;
					}
					messages.add(RawMessage.fromFile(
							fileId,
							id.asString(),
							publishTime,
							seq++,
							// Re-serialised rather than substring-sliced. The target
							// column is jsonb, which normalises key order, whitespace and
							// duplicate keys regardless, so a byte-exact slice would buy
							// nothing here — the files on disk remain the byte-level
							// evidence.
							MAPPER.writeValueAsString(marketChange)));
				}
			}
		}
		return messages;
	}
}
