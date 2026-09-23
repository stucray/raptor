package com.stucray.raptor.archive;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Localhost-only ops surface for the archive sweep.
 *
 * <p>Launched deliberately, like every other job here. The scheduled sweep is
 * the current season only; anything wider — a full 748-pair revalidation, one
 * division, one historical season — is asked for.
 */
@RestController
class FetchArchiveController {

	private static final Logger log = LoggerFactory.getLogger(FetchArchiveController.class);

	private final JobOperator jobOperator;
	private final Job fetchArchiveJob;
	private final ArchiveTargets targets;
	private final Clock clock;

	FetchArchiveController(JobOperator jobOperator, Job fetchArchiveJob, ArchiveTargets targets,
			Clock clock) {
		this.jobOperator = jobOperator;
		this.fetchArchiveJob = fetchArchiveJob;
		this.targets = targets;
		this.clock = clock;
	}

	/**
	 * Sweep the archive. Idempotent by construction: a file whose bytes have not
	 * changed costs one conditional request and writes nothing but a timestamp.
	 *
	 * @param division limit to one division code
	 * @param season limit to one season code
	 * @param current only the season in progress — 22 requests rather than 748
	 */
	@PostMapping("/ops/fetch-archive")
	Map<String, Object> fetch(@RequestParam(required = false) @Nullable String division,
			@RequestParam(required = false) @Nullable String season,
			@RequestParam(defaultValue = "false") boolean current) throws Exception {
		log.info("sweeping the football-data archive into {}", targets.custodyRoot());
		JobParametersBuilder parameters = new JobParametersBuilder()
				.addLong(FetchArchiveJobConfig.RUN_PARAM, clock.millis())
				.addString(FetchArchiveJobConfig.CURRENT_PARAM, String.valueOf(current));
		if (division != null) {
			parameters.addString(FetchArchiveJobConfig.DIVISION_PARAM, division);
		}
		if (season != null) {
			parameters.addString(FetchArchiveJobConfig.SEASON_PARAM, season);
		}
		return report(jobOperator.start(fetchArchiveJob, parameters.toJobParameters()));
	}

	/** The verdict tally, which is what a sweep actually has to say for itself. */
	static Map<String, Object> report(JobExecution execution) {
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("status", execution.getStatus().toString());
		for (ArchiveVerdict verdict : ArchiveVerdict.values()) {
			report.put(verdict.name().toLowerCase(Locale.ROOT), execution.getExecutionContext()
					.getLong(ArchiveFetchWriter.TALLY_PREFIX + verdict.name(), 0));
		}
		return report;
	}
}
