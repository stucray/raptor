package com.stucray.raptor.recorder;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Localhost-only ops surface for the spill.
 *
 * <p>The scheduled {@link SpillDrain} does the work; this exists for the two
 * cases a timer serves badly. One is a backlog somebody wants cleared <b>now</b>
 * and is prepared to wait for — the drain deliberately bounds each pass so it
 * cannot monopolise its executor, and unbounded is the right shape only when a
 * human is watching it. The other is a drain that has been failing: an operator
 * needs a way to retry that returns the error rather than putting it in a log
 * and moving on.
 *
 * <p>Every other finite job here is launched over {@code /ops/*} and this is no
 * different, but note the asymmetry with the rest: a spill replay is the one
 * job that is <b>also</b> on a timer, because unlike a load or a projection its
 * input is data that exists nowhere else yet.
 */
@RestController
class SpillReplayController {

	private final SpillDrain drain;
	private final SpillDirectory directory;

	SpillReplayController(SpillDrain drain, SpillDirectory directory) {
		this.drain = drain;
		this.directory = directory;
	}

	/**
	 * Replay pending spill files into the system of record.
	 *
	 * <p>Idempotent, and not by best effort: {@code SpillIngest} claims each file
	 * in {@code raw.spill_file} in the same transaction as its messages, so a
	 * second run over a file that already landed writes nothing and simply
	 * removes it.
	 *
	 * @param limit the most files to replay; unbounded by default
	 */
	@PostMapping("/ops/spill/replay")
	Map<String, Object> replay(
			@RequestParam(defaultValue = "" + Integer.MAX_VALUE) int limit) {
		int before = directory.pending().size();
		int replayed = drain.drainOnce(limit);
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("pendingBefore", before);
		report.put("replayed", replayed);
		report.put("pendingAfter", directory.pending().size());
		return report;
	}
}
