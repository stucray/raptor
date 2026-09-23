package com.stucray.raptor.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Projects the capture ledger: every recorder session, with what it received
 * and what it knows it lost.
 *
 * <p>The unit of work is every session that can still change, where the market
 * projection's unit is one market. That is not inconsistency — it follows from
 * what each is. A market is an expensive stateful parse of tens of thousands of
 * messages and is re-projected on its own when a parser changes; a session is
 * nine aggregates over rows that are already there.
 *
 * <p>This used to say the whole table was the unit, and that "no reason to
 * rebuild one session without rebuilding its neighbours has ever existed". The
 * reason turned out to be cost: re-aggregating all of them meant two passes over
 * every session-bearing row in {@code raw.stream_message} every five minutes,
 * which grew into the pool's 30s socket timeout (#261). A closed session's
 * neighbours are immutable, so rebuilding them bought nothing — see
 * {@link CaptureLedgerWriter} for what makes skipping them safe, and pass {@code
 * full=true} to get the old behaviour when the recipe itself changes.
 */
class ProjectCaptureLedgerTasklet implements Tasklet {

	private static final Logger log = LoggerFactory.getLogger(ProjectCaptureLedgerTasklet.class);

	private final CaptureLedgerWriter writer;
	private final ProjectionLock lock;

	ProjectCaptureLedgerTasklet(CaptureLedgerWriter writer, ProjectionLock lock) {
		this.writer = writer;
		this.lock = lock;
	}

	@Override
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		// One unit, so one lock: two launches would each be deleting and
		// re-inserting the same table.
		lock.await(ProjectionLock.CAPTURE_LEDGER, 0);

		// Read from the parameters rather than injected with @Value: a step-scoped
		// SpEL proxy for one boolean is more machinery than the lookup it replaces,
		// and this tasklet is already handed the context that holds it.
		boolean full = Boolean.parseBoolean(String.valueOf(
				chunkContext.getStepContext().getJobParameters()
						.getOrDefault(ProjectionRun.FULL_PARAM, "false")));

		long projectionId = writer.begin(ProjectCaptureLedgerJobConfig.JOB_NAME,
				full ? "all" : "live",
				chunkContext.getStepContext().getStepExecution().getJobExecutionId());
		CaptureLedgerWriter.Written written = writer.write(projectionId, full);
		writer.complete(projectionId);

		contribution.incrementWriteCount(
				written.sessions() + written.scopedMarkets() + written.gaps());
		// Says which kind of run it was, because the counts mean different things:
		// 83 sessions from a rebuild is the table, 1 from an incremental run is the
		// open session. A log line that read the same for both would make a ledger
		// that had stopped re-deriving look exactly like one that was keeping up.
		log.info("projected the capture ledger ({}): {} session(s), {} scoped market(s), "
						+ "{} gap(s)",
				full ? "full rebuild" : "live only",
				written.sessions(), written.scopedMarkets(), written.gaps());
		return RepeatStatus.FINISHED;
	}
}
