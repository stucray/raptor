package com.stucray.raptor.ingest;

import com.stucray.raptor.rawstore.RawMessage;
import com.stucray.raptor.rawstore.RawWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Loads one month of the vendor corpus into {@code raw}.
 *
 * <p>A {@link Tasklet} per month rather than a chunk-oriented step, because the
 * unit that must succeed or fail together is a whole file: its
 * {@code raw.historic_file} row is the provenance record for its messages, and
 * splitting them across chunk commits would allow a file to be marked loaded
 * while some of its messages are missing.
 *
 * <p>Idempotent by natural key. {@code raw.historic_file.path} is unique, and an
 * {@code on conflict do nothing} that returns no id means the file is already
 * loaded and is skipped. So a re-run is a no-op rather than a duplicate, and a
 * partially-failed month can simply be run again.
 */
class HistoricMonthTasklet implements Tasklet {

	private static final Logger log = LoggerFactory.getLogger(HistoricMonthTasklet.class);

	private final String month;
	private final CorpusScanner scanner;
	private final RawMessageExtractor extractor;
	private final RawWriter writer;
	private final JdbcClient jdbc;

	public HistoricMonthTasklet(String month, CorpusScanner scanner,
			RawMessageExtractor extractor, RawWriter writer, JdbcClient jdbc) {
		this.month = month;
		this.scanner = scanner;
		this.extractor = extractor;
		this.writer = writer;
		this.jdbc = jdbc;
	}

	@Override
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext)
			throws Exception {
		List<Path> files = scanner.files().stream()
				.filter(f -> scanner.monthOf(f).equals(month))
				.toList();

		long loadedFiles = 0;
		long loadedMessages = 0;
		long skipped = 0;

		for (Path file : files) {
			HistoricFile described = scanner.describe(file);
			Optional<Long> fileId = claim(described);
			if (fileId.isEmpty()) {
				skipped++;
				continue;
			}

			List<RawMessage> messages = extractor.extract(file, fileId.get());
			writer.write(messages);
			jdbc.sql("update raw.historic_file set messages = ? where id = ?")
					.params(messages.size(), fileId.get())
					.update();

			loadedFiles++;
			loadedMessages += messages.size();
			contribution.incrementWriteCount(messages.size());
		}

		log.info("[{}] loaded {} files / {} messages ({} already present)",
				month, loadedFiles, loadedMessages, skipped);
		return RepeatStatus.FINISHED;
	}

	/**
	 * Reserve the provenance row for a file, or report that it already exists.
	 *
	 * <p>{@code messages} is written as 0 here and corrected once the extraction
	 * has actually happened — the row exists to hold the natural-key lock inside
	 * the transaction, and the transaction is what makes the pair atomic.
	 */
	private Optional<Long> claim(HistoricFile file) {
		return jdbc.sql("""
						insert into raw.historic_file (path, sha256, bytes, messages)
						values (?, ?, ?, 0)
						on conflict (path) do nothing
						returning id""")
				.params(file.relativePath(), file.sha256(), file.bytes())
				.query(Long.class)
				.optional();
	}
}
