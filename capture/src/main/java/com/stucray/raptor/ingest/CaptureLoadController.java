package com.stucray.raptor.ingest;

import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Localhost-only ops surface for the capture-corpus load.
 *
 * <p>Launched deliberately, like every other job here: a resident service that
 * begins ingesting a corpus because someone restarted it is not a service anyone
 * can operate.
 */
@RestController
class CaptureLoadController {

	private static final Logger log = LoggerFactory.getLogger(CaptureLoadController.class);

	private final JobOperator jobOperator;
	private final Job loadCaptureCorpusJob;
	private final CaptureCorpusScanner scanner;
	private final Clock clock;

	CaptureLoadController(JobOperator jobOperator, Job loadCaptureCorpusJob,
			CaptureCorpusScanner scanner, Clock clock) {
		this.jobOperator = jobOperator;
		this.loadCaptureCorpusJob = loadCaptureCorpusJob;
		this.scanner = scanner;
		this.clock = clock;
	}

	/**
	 * Load every capture under the custody root. Idempotent by the natural key on
	 * {@code raw.capture_file.path}, so a re-run after a failure resumes rather
	 * than duplicates.
	 */
	@PostMapping("/ops/load-captures")
	Map<String, Object> load() throws Exception {
		log.info("loading the capture corpus from {}", scanner.root());
		JobExecution execution = jobOperator.start(loadCaptureCorpusJob,
				new JobParametersBuilder()
						.addLong(CaptureLoadJobConfig.RUN_PARAM, clock.millis())
						.toJobParameters());
		return Map.of(
				"status", execution.getStatus().toString(),
				"scanned", execution.getStepExecutions().stream()
						.mapToLong(StepExecution::getReadCount).sum(),
				"written", execution.getStepExecutions().stream()
						.mapToLong(StepExecution::getWriteCount).sum());
	}
}
