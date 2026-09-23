package com.stucray.raptor.archive;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Component;

/**
 * {@code fetchArchiveJob} for the season in progress — what {@code ArchiveSchedule}
 * used to run at 08:30Z, minus the schedule (#200).
 *
 * <p>The class it replaces carried a {@code @Scheduled} of its own and was, for
 * a while, "the one thing here that runs unbidden". It was also half a chain:
 * it wrote {@code raw.football_file} on a timer and nothing projected the
 * result, so the archive was automatically acquired into a system of record
 * nobody automatically read. One schedule now owns both halves, which also
 * means there is one place a missed run shows up rather than two.
 *
 * <p><b>Disabled is a state this class reports, not a bean that is absent.</b>
 * {@code ArchiveSchedule} was {@code @ConditionalOnProperty} because switching it
 * off meant having no timer; switching this off must not remove a dependency the
 * close-out declares, or every test that boots the application would have to
 * choose between reaching the public internet and not wiring the chain at all.
 */
@Component
class CurrentSeasonSweep implements ArchiveRefresh {

	private static final Logger log = LoggerFactory.getLogger(CurrentSeasonSweep.class);

	/** Files this sweep put into the system of record, as opposed to revalidated. */
	private static final ArchiveVerdict[] CHANGED =
			{ArchiveVerdict.NEW, ArchiveVerdict.UPDATED, ArchiveVerdict.ADOPTED};

	private final JobOperator jobOperator;
	private final Job fetchArchiveJob;
	private final Clock clock;
	private final boolean enabled;

	CurrentSeasonSweep(JobOperator jobOperator, Job fetchArchiveJob, Clock clock,
			ArchiveProperties properties) {
		this.jobOperator = jobOperator;
		this.fetchArchiveJob = fetchArchiveJob;
		this.clock = clock;
		this.enabled = properties.sweep().enabled();
	}

	@Override
	public Outcome refreshCurrentSeason() {
		if (!enabled) {
			// Off is a deliberate setting — it is how a test context boots the whole
			// chain without reaching football-data.co.uk — so it is a successful run
			// that fetched nothing, and it says so rather than reading as an outage.
			log.info("archive sweep is disabled; skipping the fetch half of the close-out");
			return new Outcome(true, 0, "sweep disabled");
		}
		try {
			JobExecution execution = jobOperator.start(fetchArchiveJob, new JobParametersBuilder()
					.addLong(FetchArchiveJobConfig.RUN_PARAM, clock.millis())
					.addString(FetchArchiveJobConfig.CURRENT_PARAM, "true")
					.toJobParameters());
			Map<String, Object> report = FetchArchiveController.report(execution);
			boolean successful = !execution.getStatus().isUnsuccessful()
					&& tally(execution, ArchiveVerdict.FAILED) == 0;
			// AT ERROR WHEN IT FAILED, because the only other trace of a sweep that
			// did not run is a row that never arrived — and an absence is invisible
			// until somebody goes looking. #141 was a FAILED sweep logging at INFO
			// beside the successful ones, every morning, for weeks.
			if (successful) {
				log.info("archive sweep: {}", report);
			}
			else {
				log.error("archive sweep FAILED: {} {}", report, execution.getAllFailureExceptions());
			}
			return new Outcome(successful, changed(execution), describe(report));
		}
		catch (Exception e) {
			// Deliberately swallowed: see ArchiveRefresh. A free service on the
			// public internet being unreachable must cost results, not the live
			// capture projection that runs in the same chain.
			log.error("archive sweep could not be launched; the close-out continues without it", e);
			return new Outcome(false, 0, e.toString());
		}
	}

	private static int changed(JobExecution execution) {
		long total = 0;
		for (ArchiveVerdict verdict : CHANGED) {
			total += tally(execution, verdict);
		}
		return Math.toIntExact(total);
	}

	private static long tally(JobExecution execution, ArchiveVerdict verdict) {
		return execution.getExecutionContext()
				.getLong(ArchiveFetchWriter.TALLY_PREFIX + verdict.name(), 0);
	}

	/** {@code new=2 updated=1 unchanged=19}: the zeroes are noise in a ledger row. */
	private static String describe(Map<String, Object> report) {
		return report.entrySet().stream()
				.filter(entry -> !"0".equals(String.valueOf(entry.getValue())))
				.map(entry -> entry.getKey() + "=" + entry.getValue())
				.collect(Collectors.joining(" ", "archive: ", ""))
				.toLowerCase(Locale.ROOT);
	}
}
