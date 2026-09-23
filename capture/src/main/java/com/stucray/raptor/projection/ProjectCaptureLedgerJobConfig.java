package com.stucray.raptor.projection;

import com.stucray.raptor.datasource.Acquisition;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code projectCaptureLedgerJob} — {@code query.capture_session} from the rows
 * the recorder itself wrote.
 *
 * <p>#95 called this {@code projectCaptureRunJob}. The name moved with the unit:
 * a row is a session rather than a run, because S6 abolished the run. A capture
 * was a 12-hour launchd window fired at a fixed hour; the recorder is resident
 * and has no schedule, and {@code raw.market_scope} is what replaced the window.
 * Keeping the old name would have promised a grain the data no longer has.
 *
 * <p>The step asks for the {@code @Acquisition} transaction manager explicitly.
 * The {@code @Primary} one belongs to the read identity, whose pool has no grant
 * on {@code raw} at all — a bare declaration is not a permission error but no
 * transaction at all, which is what #110 and #112 were.
 */
@Configuration
class ProjectCaptureLedgerJobConfig {

	public static final String JOB_NAME = "projectCaptureLedgerJob";

	@Bean
	public Job projectCaptureLedgerJob(JobRepository jobRepository,
			Step projectCaptureLedgerStep) {
		return new JobBuilder(JOB_NAME, jobRepository).start(projectCaptureLedgerStep).build();
	}

	@Bean
	public Tasklet projectCaptureLedgerTasklet(CaptureLedgerWriter writer, ProjectionLock lock) {
		return new ProjectCaptureLedgerTasklet(writer, lock);
	}

	@Bean
	public Step projectCaptureLedgerStep(JobRepository jobRepository,
			@Acquisition PlatformTransactionManager acquisitionTransactionManager,
			Tasklet projectCaptureLedgerTasklet) {
		return new StepBuilder("projectCaptureLedgerStep", jobRepository)
				.tasklet(projectCaptureLedgerTasklet, acquisitionTransactionManager)
				.build();
	}
}
