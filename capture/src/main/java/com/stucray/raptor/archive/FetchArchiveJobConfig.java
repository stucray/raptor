package com.stucray.raptor.archive;

import com.stucray.raptor.datasource.Acquisition;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.IteratorItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code fetchArchiveJob} — football-data.co.uk into {@code raw}, incrementally.
 *
 * <p>Chunk-oriented, <b>one target per chunk</b>, like the capture-corpus load
 * and for a related reason: the unit that must land or not land together is one
 * file — its row in the system of record and its bytes in custody. A sweep is up
 * to 748 of those and takes several minutes at the polite request rate, so a
 * single transaction across the lot would hold one open for the whole run to no
 * benefit.
 *
 * <p>The {@code run} stamp is identifying, for the reason #113 established: with
 * no parameter that varies, Batch's permanent {@code (JOB_NAME, JOB_KEY)}
 * uniqueness would let a sweep run once per database and refuse ever after.
 */
@Configuration
@EnableConfigurationProperties(ArchiveProperties.class)
class FetchArchiveJobConfig {

	static final String JOB_NAME = "fetchArchiveJob";
	static final String RUN_PARAM = "run";
	static final String DIVISION_PARAM = "division";
	static final String SEASON_PARAM = "season";
	static final String CURRENT_PARAM = "current";

	/**
	 * Adopt custody first, then sweep — and never the other way round.
	 *
	 * <p>The files on disk are the corpus this project's findings were derived
	 * from. Upstream republishes a season's CSV whenever a result is corrected, so
	 * a job that fetched first would store the correction, overwrite the file, and
	 * leave the version those findings came from nowhere at all. Adopting first
	 * costs one read per file on the first run and nothing afterwards.
	 */
	@Bean
	Job fetchArchiveJob(JobRepository jobRepository, Step adoptCustodyStep, Step fetchArchiveStep) {
		return new JobBuilder(JOB_NAME, jobRepository)
				.start(adoptCustodyStep)
				.next(fetchArchiveStep)
				.build();
	}

	@Bean
	@StepScope
	ItemReader<Path> custodyFileReader(ArchiveCustody custody) throws IOException {
		return new IteratorItemReader<>(custody.files());
	}

	@Bean
	@StepScope
	ItemProcessor<Path, ArchiveOutcome> custodyAdoptionProcessor(ArchiveCustody custody,
			FootballFiles files) {
		return new CustodyAdoptionProcessor(custody, files);
	}

	@Bean
	Step adoptCustodyStep(JobRepository jobRepository,
			@Acquisition PlatformTransactionManager transactionManager,
			ItemReader<Path> custodyFileReader,
			ItemProcessor<Path, ArchiveOutcome> custodyAdoptionProcessor,
			ItemWriter<ArchiveOutcome> archiveFetchWriter) {
		return new StepBuilder("adoptCustodyStep", jobRepository)
				.<Path, ArchiveOutcome>chunk(1, transactionManager)
				.reader(custodyFileReader)
				.processor(custodyAdoptionProcessor)
				.writer(archiveFetchWriter)
				.build();
	}

	/**
	 * The grid the sweep will ask about, resolved when the step starts.
	 *
	 * <p>{@code @StepScope} both because the job parameters are what select it and
	 * because the list is the step's own state — without the proxy the custody
	 * root would be walked while the context is being built, on every application
	 * start, whether or not a sweep is ever launched.
	 */
	@Bean
	@StepScope
	ItemReader<ArchiveTarget> archiveTargetReader(ArchiveTargets targets, Clock clock,
			@Value("#{jobParameters['" + DIVISION_PARAM + "']}") @Nullable String division,
			@Value("#{jobParameters['" + SEASON_PARAM + "']}") @Nullable String season,
			@Value("#{jobParameters['" + CURRENT_PARAM + "']}") @Nullable String current) {
		return new IteratorItemReader<>(
				targets.forSweep(division, season, Boolean.parseBoolean(current), clock));
	}

	@Bean
	@StepScope
	ItemProcessor<ArchiveTarget, ArchiveOutcome> archiveFetchProcessor(ArchiveFetchClient client,
			FootballFiles files, ArchiveProperties properties) {
		return new ArchiveFetchProcessor(client, files, properties.requestDelay());
	}

	@Bean
	@StepScope
	ItemWriter<ArchiveOutcome> archiveFetchWriter(FootballFiles files, ArchiveCustody custody,
			@Value("#{stepExecution}") StepExecution stepExecution) {
		return new ArchiveFetchWriter(files, custody,
				stepExecution.getJobExecution().getExecutionContext());
	}

	/**
	 * The transaction manager is the <em>acquisition</em> one, explicitly: the
	 * {@code @Primary} manager belongs to the read identity, whose pool has no
	 * grant on {@code raw} at all, so a bare declaration here would give the step
	 * a transaction on a connection none of its writes go out through — which is
	 * not a permission error but no transaction at all (#110, #112).
	 */
	@Bean
	Step fetchArchiveStep(JobRepository jobRepository,
			@Acquisition PlatformTransactionManager transactionManager,
			ItemReader<ArchiveTarget> archiveTargetReader,
			ItemProcessor<ArchiveTarget, ArchiveOutcome> archiveFetchProcessor,
			ItemWriter<ArchiveOutcome> archiveFetchWriter) {
		return new StepBuilder("fetchArchiveStep", jobRepository)
				.<ArchiveTarget, ArchiveOutcome>chunk(1, transactionManager)
				.reader(archiveTargetReader)
				.processor(archiveFetchProcessor)
				.writer(archiveFetchWriter)
				.build();
	}
}
