package com.stucray.raptor.recorder;

import com.stucray.raptor.datasource.Acquisition;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code spillReplayJob} — one {@code JobInstance} per spill file.
 *
 * <p>A job rather than a loop in the recorder, for the reason every finite piece
 * of work here is one: the run ledger. When a match is missing from
 * {@code query} three weeks later, {@code BATCH_JOB_EXECUTION} plus
 * {@code raw.spill_file} answer what was replayed and when, and a loop inside a
 * resident service answers nothing.
 *
 * <p><b>The step runs on the acquisition transaction manager, explicitly.</b>
 * Left to bean-name resolution it would get the {@code @Primary} one — the read
 * identity, which has no grant on {@code raw} at all — and the ledger row would
 * not be in the same transaction as the messages it vouches for. That
 * transaction <em>is</em> the exactly-once guarantee, so this is not a tidiness
 * point.
 */
@Configuration
class SpillReplayJobConfig {

	static final String JOB_NAME = "spillReplayJob";

	/** The spill file's name, relative to the spill directory. */
	static final String PARAM = "spillFile";

	@Bean
	public Job spillReplayJob(JobRepository jobRepository, Step spillReplayStep) {
		return new JobBuilder(JOB_NAME, jobRepository).start(spillReplayStep).build();
	}

	/**
	 * {@code @StepScope} is load-bearing: {@code jobParameters} is late-bound, and
	 * without the proxy Spring evaluates the SpEL while building the context —
	 * when no job is running — and the whole application fails to start.
	 */
	@Bean
	@StepScope
	public Tasklet spillReplayTasklet(@Value("#{jobParameters['" + PARAM + "']}") String spillFile,
			SpillIngest ingest) {
		return (contribution, chunkContext) -> {
			SpillIngest.Result result = ingest.ingest(spillFile);
			contribution.incrementWriteCount(result.messages());
			return RepeatStatus.FINISHED;
		};
	}

	@Bean
	public Step spillReplayStep(JobRepository jobRepository,
			@Acquisition PlatformTransactionManager acquisitionTransactionManager,
			Tasklet spillReplayTasklet) {
		return new StepBuilder("spillReplayStep", jobRepository)
				.tasklet(spillReplayTasklet, acquisitionTransactionManager)
				.build();
	}
}
