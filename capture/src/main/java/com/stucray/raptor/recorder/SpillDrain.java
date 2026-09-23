package com.stucray.raptor.recorder;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Puts spilled messages back into the system of record, without being asked.
 *
 * <p><b>This is the half of the spill that was missing.</b> The write side was
 * complete from S5 — a batch the database will not take is written to disk,
 * whole, and {@code SpillIngest} puts one file back exactly once — but nothing
 * in the application ever called {@link SpillReplayer}. Only the two tests did.
 * So the spill absorbed an outage exactly as designed and then held the
 * messages forever: on 2026-09-05 a file from session 45 was still sitting
 * there four sessions later, its one message absent from {@code raw}, with
 * {@code raw.spill_file} empty and every health signal green (#167).
 *
 * <p>An unreplayed spill file is the worst state this system has. The message
 * is not in the system of record, so nothing downstream can see it; it is on a
 * single local disk, so it has none of the durability the database gives it;
 * and its absence is invisible, because a row that never arrived looks exactly
 * like a row that was never sent. The window between the outage ending and the
 * rows landing is pure exposure, and only this closes it.
 *
 * <p><b>Not on the shared scheduler thread.</b> {@code spring.task.scheduling}
 * is left at Boot's default of one thread, and the scope poll, the stream
 * watchdog, the keep-alive and a ledger refresh that takes ten seconds already
 * share it. A drain of a long outage's backlog would block all of them — the
 * watchdog included, which is the component that notices the recorder has gone
 * deaf. So the scheduled method only hands work to a dedicated executor and
 * returns; the {@code draining} flag makes a pass that overruns its interval
 * skip rather than pile up.
 */
@Component
class SpillDrain {

	private static final Logger log = LoggerFactory.getLogger(SpillDrain.class);

	private final SpillReplayer replayer;
	private final SpillDirectory directory;
	private final boolean enabled;
	private final int batch;
	private final int attemptsBeforeSettingAside;
	/**
	 * Consecutive failures per file name, and in memory deliberately.
	 *
	 * <p>A restart clears it, which is the right way round: after a restart the
	 * cause may well be different, and the cost of finding out is one more
	 * attempt. Persisting it would mean a schema for bookkeeping about files that
	 * should not exist.
	 */
	private final Map<String, Integer> failures = new ConcurrentHashMap<>();
	private final AtomicBoolean draining = new AtomicBoolean();
	private final ExecutorService executor =
			Executors.newSingleThreadExecutor(Thread.ofVirtual().name("spill-drain").factory());

	SpillDrain(SpillReplayer replayer, SpillDirectory directory, RecorderProperties properties) {
		this.replayer = replayer;
		this.directory = directory;
		this.enabled = properties.spill().enabled();
		this.batch = properties.spill().drainBatch();
		this.attemptsBeforeSettingAside = properties.spill().drainAttemptsBeforeSettingAside();
	}

	@Scheduled(initialDelayString = "${raptor.recorder.spill.drain-interval:1m}",
			fixedDelayString = "${raptor.recorder.spill.drain-interval:1m}")
	void drain() {
		if (!enabled || directory.pending().isEmpty()) {
			return;
		}
		if (!draining.compareAndSet(false, true)) {
			// The previous pass is still going. Saying so at DEBUG rather than
			// WARN: it is the bound doing its job on a large backlog, not a fault.
			log.debug("a spill drain is already running; skipping this pass");
			return;
		}
		executor.execute(() -> {
			try {
				drainOnce(batch);
			} finally {
				draining.set(false);
			}
		});
	}

	/**
	 * One pass, on the calling thread.
	 *
	 * @param limit the most files to replay
	 * @return how many were replayed
	 */
	int drainOnce(int limit) {
		int pending = directory.pending().size();
		try {
			List<JobExecution> executions = replayer.replayPending(limit);
			setAsideWhatCannotSucceed(executions);
			int remaining = directory.pending().size();
			// Always the tally, never a bare "drained": the number that matters to
			// anyone reading this later is what is STILL waiting, and a log line
			// that omits it cannot distinguish a drain that finished from one that
			// is quietly falling behind.
			log.info("drained {} spill file(s) of {} pending; {} still waiting",
					executions.size(), pending, remaining);
			return executions.size();
		} catch (Exception e) {
			// Expected whenever the database is still the thing that is down —
			// which is, by construction, why these files exist. WARN and not ERROR
			// for that reason, and the next pass simply tries again.
			log.warn("spill drain failed with {} file(s) pending; retrying on the next pass ({})",
					pending, e.toString());
			return 0;
		}
	}

	/**
	 * Stop retrying a file that can never succeed (#271).
	 *
	 * <p>{@code SpillIngest} unlinks a file only after its transaction commits,
	 * which is exactly right for its own guarantee and means a file the database
	 * <em>refuses</em> is retried every drain interval, forever. Before #271 that
	 * could only happen through a bug; it is now also the last line of defence
	 * for anything {@code RawWriteLoop}'s SQLSTATE classification does not catch
	 * — a refusal wrapped so the state is invisible, or a rejection that is
	 * genuinely new.
	 *
	 * <p>The file is MOVED, never deleted. Its messages are not in the system of
	 * record, so it is the only copy of them there is.
	 */
	private void setAsideWhatCannotSucceed(List<JobExecution> executions) {
		for (JobExecution execution : executions) {
			String name = execution.getJobParameters().getString(SpillReplayJobConfig.PARAM);
			if (name == null) {
				continue;
			}
			if (execution.getStatus() == BatchStatus.COMPLETED) {
				failures.remove(name);
				continue;
			}
			int consecutive = failures.merge(name, 1, Integer::sum);
			if (consecutive < attemptsBeforeSettingAside) {
				log.warn("spill file {} failed to replay ({} of {} attempts before it is set "
						+ "aside)", name, consecutive, attemptsBeforeSettingAside);
				continue;
			}
			if (directory.setAside(directory.resolve(name))) {
				failures.remove(name);
				// ERROR, and it should read as one: these messages are not in the
				// system of record and nothing downstream can tell them from messages
				// that were never sent.
				log.error("spill file {} failed to replay {} times and cannot succeed; moved to "
						+ "{}/ and NOT deleted. Its messages are not captured — read the file, "
						+ "fix the cause, replay it by hand", name, consecutive,
						SpillDirectory.SET_ASIDE);
			}
		}
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}
}
