package com.stucray.raptor.ingest;

import com.stucray.raptor.corpus.CorpusMonth;
import com.stucray.raptor.datasource.Acquisition;
import com.stucray.raptor.rawstore.RawWriter;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code loadHistoricCorpusJob} — one {@link org.springframework.batch.core.job.JobInstance}
 * per month of the corpus.
 *
 * <p>The month is an <em>identifying</em> job parameter, which is what makes
 * Spring Batch's protection real here: the unique {@code (JOB_NAME, JOB_KEY)} on
 * {@code BATCH_JOB_INSTANCE}, created under SERIALIZABLE isolation, means the
 * same month cannot be loaded concurrently by two launches. That is the
 * guarantee launchd used to provide, now enforced by the database rather than by
 * a process supervisor.
 *
 * <p>That guarantee rests on the job repository being a real one. Boot 4's
 * auto-configuration supplies a resourceless repository that persists nothing,
 * so {@code BatchJobRepositoryConfiguration} declares
 * {@code @EnableBatchProcessing} and a JDBC repository on the acquisition
 * identity (#84). An earlier note here said the opposite — that the annotation
 * was banned module-wide — which was true until it was found to be the reason
 * {@code batch.BATCH_*} stayed empty through a full load.
 */
@Configuration
class HistoricLoadJobConfig {

	public static final String JOB_NAME = "loadHistoricCorpusJob";

	@Bean
	public Job loadHistoricCorpusJob(JobRepository jobRepository, Step loadHistoricMonthStep) {
		return new JobBuilder(JOB_NAME, jobRepository).start(loadHistoricMonthStep).build();
	}

	/**
	 * {@code @StepScope} is load-bearing, not decoration. {@code jobParameters} is
	 * late-bound: without a step-scoped proxy Spring evaluates the SpEL while
	 * building the context, when no job is running, and the whole application
	 * fails to start — not just this job.
	 */
	@Bean
	@StepScope
	public Tasklet historicMonthTasklet(
			@Value("#{jobParameters['" + CorpusMonth.PARAM + "']}") String month,
			CorpusScanner scanner, RawMessageExtractor extractor,
			RawWriter writer, @Acquisition JdbcClient jdbc) {
		return new HistoricMonthTasklet(month, scanner, extractor, writer, jdbc);
	}

	/**
	 * The owner's transaction manager, asked for by name.
	 *
	 * <p>A bare {@code PlatformTransactionManager} gets the {@code @Primary} one,
	 * which belongs to the <b>read</b> identity — while everything this tasklet
	 * writes goes out through {@code @Acquisition} beans on the owner's pool. The
	 * consequence is not a permission error but no transaction at all: the step
	 * begins one on a connection nothing in the load ever uses, and each
	 * statement autocommits (#112, the sibling of #110 and #92).
	 *
	 * <p>What that costs is atomicity between the three things a file's load
	 * does — claim the provenance row, COPY its messages, write the count back to
	 * {@code raw.historic_file}. A crash between the second and third leaves a
	 * file whose recorded count disagrees with the rows present, which is exactly
	 * what {@code tools/verify/load-integrity.sql} exists to report.
	 *
	 * <p><b>The step transaction is now a whole month</b> — around 243,000 rows
	 * on average, more at the peak — because the tasklet is one invocation
	 * returning {@code FINISHED}. Comfortable for PostgreSQL, and a genuinely
	 * different shape from statement-at-a-time autocommit, which is why this
	 * change was re-verified against a full 84-month reload rather than assumed.
	 */
	@Bean
	public Step loadHistoricMonthStep(JobRepository jobRepository,
			@Acquisition PlatformTransactionManager transactionManager,
			Tasklet historicMonthTasklet) {
		return new StepBuilder("loadHistoricMonthStep", jobRepository)
				.tasklet(historicMonthTasklet, transactionManager)
				.build();
	}
}
