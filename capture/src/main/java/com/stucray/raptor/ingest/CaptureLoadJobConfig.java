package com.stucray.raptor.ingest;

import com.stucray.raptor.datasource.Acquisition;
import com.stucray.raptor.rawstore.RawWriter;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.IteratorItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code loadCaptureCorpusJob} — the Python era's captures, into {@code raw}.
 *
 * <p>A one-off migration, and the last one this system needs: after it, every
 * live capture paddock has ever taken is in the system of record, and the read
 * side can be served from a projection instead of from the normaliser's spool.
 *
 * <p><b>Chunk-oriented, one file per chunk</b>, where the historic load is a
 * tasklet per month. The unit is the same in both — a whole file, because its
 * provenance row and its messages must be written together — but the corpus is
 * not: a month of vendor files is around 243,000 rows, while the capture corpus
 * is several million in total and has no natural partition to break it on. One
 * transaction per file gives the same atomicity without a single transaction
 * holding the whole load.
 *
 * <p>The {@code run} stamp is identifying, for the reason #113 established:
 * without a parameter that varies, Batch's permanent {@code (JOB_NAME, JOB_KEY)}
 * uniqueness would let this job run once per database and refuse ever after —
 * including after a failure that left half the corpus loaded.
 */
@Configuration
class CaptureLoadJobConfig {

	static final String JOB_NAME = "loadCaptureCorpusJob";
	static final String RUN_PARAM = "run";

	@Bean
	Job loadCaptureCorpusJob(JobRepository jobRepository, Step loadCaptureFileStep) {
		return new JobBuilder(JOB_NAME, jobRepository).start(loadCaptureFileStep).build();
	}

	/**
	 * The whole corpus in path order, read once when the step starts.
	 *
	 * <p>{@code @StepScope} because the list is the step's own state; without a
	 * step-scoped proxy the directory would be walked while the context is being
	 * built, on every application start, whether or not the job is ever launched.
	 */
	@Bean
	@StepScope
	ItemReader<Path> captureFileReader(CaptureCorpusScanner scanner) throws IOException {
		return new IteratorItemReader<>(scanner.files());
	}

	@Bean
	@StepScope
	ItemProcessor<Path, CaptureLoad> captureFileProcessor(CaptureCorpusScanner scanner,
			CaptureMessageExtractor extractor, @Acquisition JdbcClient jdbc) {
		return new CaptureFileProcessor(scanner, extractor, jdbc);
	}

	@Bean
	ItemWriter<CaptureLoad> captureFileWriter(RawWriter rawWriter,
			@Acquisition JdbcClient jdbc) {
		return new CaptureFileWriter(rawWriter, jdbc);
	}

	/**
	 * The transaction manager is the <em>acquisition</em> one, explicitly. The
	 * {@code @Primary} manager belongs to the read identity, whose pool has no
	 * grant on {@code raw} at all — so a bare declaration here would give the step
	 * a transaction on a connection none of its writes go out through, which is
	 * not a permission error but no transaction at all (#110, #112).
	 */
	@Bean
	Step loadCaptureFileStep(JobRepository jobRepository,
			@Acquisition PlatformTransactionManager transactionManager,
			ItemReader<Path> captureFileReader,
			ItemProcessor<Path, CaptureLoad> captureFileProcessor,
			ItemWriter<CaptureLoad> captureFileWriter) {
		return new StepBuilder("loadCaptureFileStep", jobRepository)
				.<Path, CaptureLoad>chunk(1, transactionManager)
				.reader(captureFileReader)
				.processor(captureFileProcessor)
				.writer(captureFileWriter)
				.build();
	}
}
