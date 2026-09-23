package com.stucray.raptor.ingest;

import com.stucray.raptor.rawstore.RawWriter;
import java.util.Arrays;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes one capture file's provenance row and its messages, together.
 *
 * <p>The chunk size is one, so this whole method is a file's transaction. That
 * is the unit that must succeed or fail together: {@code raw.capture_file} is
 * the record that a file's messages are present, and a row saying so while some
 * of them are missing is the one outcome the load must not be able to produce.
 * The message count is known before the insert, so the row is written correct
 * the first time rather than corrected afterwards.
 */
class CaptureFileWriter implements ItemWriter<CaptureLoad> {

	private final RawWriter rawWriter;
	private final JdbcClient jdbc;

	CaptureFileWriter(RawWriter rawWriter, JdbcClient jdbc) {
		this.rawWriter = rawWriter;
		this.jdbc = jdbc;
	}

	@Override
	public void write(Chunk<? extends CaptureLoad> chunk) throws Exception {
		for (CaptureLoad load : chunk) {
			jdbc.sql("""
							insert into raw.capture_file
								(path, sha256, bytes, messages, status, meta_json)
							values (?, ?, ?, ?, ?, cast(? as jsonb))""")
					.params(Arrays.asList(load.file().relativePath(), load.file().sha256(),
							load.file().bytes(), load.messages().size(), load.status(),
							load.metaJson()))
					.update();
			rawWriter.write(load.messages());
		}
	}
}
