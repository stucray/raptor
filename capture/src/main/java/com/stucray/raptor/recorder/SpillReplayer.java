package com.stucray.raptor.recorder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Component;

/**
 * Drains the spill directory back into the system of record.
 *
 * <p>One job launch per file, each with its own transaction. A single
 * transaction over the whole directory would be faster and would be the wrong
 * shape: after a long outage the directory holds thousands of files, and an
 * all-or-nothing replay of a match's worth of book is precisely the risk
 * profile the spill exists to remove.
 */
@Component
class SpillReplayer {

	private static final Logger log = LoggerFactory.getLogger(SpillReplayer.class);

	private final JobOperator jobOperator;
	private final Job spillReplayJob;
	private final SpillDirectory directory;

	SpillReplayer(JobOperator jobOperator, Job spillReplayJob, SpillDirectory directory) {
		this.jobOperator = jobOperator;
		this.spillReplayJob = spillReplayJob;
		this.directory = directory;
	}

	/** Files waiting to be replayed. */
	List<Path> pending() {
		return directory.pending();
	}

	/**
	 * Replay everything pending.
	 *
	 * @return the executions launched, in order
	 */
	List<JobExecution> replayPending() throws Exception {
		return replayPending(Integer.MAX_VALUE);
	}

	/**
	 * Replay at most {@code limit} files, oldest first.
	 *
	 * <p>The bound is for the scheduled drain. A job launch here is synchronous,
	 * so an unbounded pass after a long outage would occupy its caller for as
	 * long as the backlog takes; whatever is left is simply picked up by the next
	 * pass, and the ordering guarantees the oldest data — the data closest to
	 * being lost to whatever goes wrong next — is made safe first.
	 *
	 * @param limit the most files to replay in this pass
	 * @return the executions launched, in order
	 */
	List<JobExecution> replayPending(int limit) throws Exception {
		List<JobExecution> executions = new ArrayList<>();
		for (Path file : directory.pending()) {
			if (executions.size() >= limit) {
				break;
			}
			executions.add(replay(file.getFileName().toString()));
		}
		return executions;
	}

	/**
	 * {@code attempt} is identifying, deliberately.
	 *
	 * <p>Without it, a second launch for the same file hits Batch's
	 * {@code (JOB_NAME, JOB_KEY)} uniqueness and is refused as already complete —
	 * and the file, which is still on disk precisely because the previous run died
	 * before unlinking it, could then never be cleaned up. Batch's duplicate
	 * protection is the wrong guard here; the ledger's unique key on the file name
	 * is the right one, and it is the one that decides whether messages are
	 * written.
	 */
	private JobExecution replay(String name) throws Exception {
		log.info("replaying spill file {}", name);
		return jobOperator.start(spillReplayJob, new JobParametersBuilder()
				.addString(SpillReplayJobConfig.PARAM, name)
				.addLong("attempt", System.nanoTime())
				.toJobParameters());
	}
}
