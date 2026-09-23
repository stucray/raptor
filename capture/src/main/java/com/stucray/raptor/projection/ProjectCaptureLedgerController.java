package com.stucray.raptor.projection;

import java.time.Clock;
import java.util.Map;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Localhost-only ops surface for the capture ledger, and the one place that
 * knows how to launch it.
 *
 * <p>{@link CaptureLedgerRefresh} calls the same method rather than duplicating
 * the launch, so a ledger projected on a timer and one projected by hand are the
 * same act with the same run stamp scheme.
 */
@RestController
class ProjectCaptureLedgerController {

	private final JobOperator jobOperator;
	private final Job projectCaptureLedgerJob;
	private final Clock clock;

	ProjectCaptureLedgerController(JobOperator jobOperator, Job projectCaptureLedgerJob,
			Clock clock) {
		this.jobOperator = jobOperator;
		this.projectCaptureLedgerJob = projectCaptureLedgerJob;
		this.clock = clock;
	}

	/**
	 * @param full re-derive every row rather than only the sessions and markets
	 *     that can still change. The timer never asks for this; a human does,
	 *     after a change to how the ledger is derived (#261).
	 */
	@PostMapping("/ops/project-capture-ledger")
	Map<String, Object> project(@RequestParam(defaultValue = "false") boolean full)
			throws Exception {
		JobExecution execution = project(clock.millis(), full);
		// "rows", not "sessions": the job projects sessions, scoped markets and
		// gaps, and the step's write count is all three. A key that named only the
		// first would be quietly wrong the moment anybody read it.
		return Map.of(
				"status", execution.getStatus().toString(),
				"full", full,
				"rows", execution.getStepExecutions().stream()
						.mapToLong(step -> step.getWriteCount()).sum());
	}

	/**
	 * Each launch carries its own run stamp, for the reason #113 established: a
	 * projection whose only identifying parameters never vary can be run once per
	 * database and is refused ever after. This one has no unit to key on at all,
	 * so without the stamp it would be a one-shot.
	 */
	JobExecution project(long run, boolean full) throws Exception {
		return jobOperator.start(projectCaptureLedgerJob, new JobParametersBuilder()
				.addLong(ProjectionRun.PARAM, run)
				.addString(ProjectionRun.FULL_PARAM, Boolean.toString(full))
				.toJobParameters());
	}
}
