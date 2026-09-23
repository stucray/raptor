package com.stucray.raptor.recorder;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The write path's dials, and what they cost.
 *
 * @param enabled whether this instance may record at all. Off is a way to run
 *     the read side against the production database without any chance of
 *     touching capture; on is not a promise to record, only to try — an
 *     instance that cannot take the lease stands by.
 * @param queueCapacity slots between the read loop and the writer. 50,000 rows
 *     is roughly 10 MB and, at a ~200 msg/s peak, about 250 seconds of book —
 *     sized so that only a stalled writer can fill it, never a busy one.
 * @param batchSize rows per COPY. A ceiling, not a target: the timer below is
 *     what normally ends a batch.
 * @param spill where messages go when the database will not take them
 * @param watchdog how the recorder notices it has stopped receiving
 * @param reconnectDelay how long to wait before rebuilding a stream that ended.
 *     Short, because the thing being waited on is a match in progress; not zero,
 *     because a source failing instantly in a loop should not become a busy wait
 *     against Betfair. This is the delay after an attempt that <b>recorded
 *     something</b>; see {@code reconnectDelayMax} for the other case.
 * @param reconnectDelayMax the ceiling the delay backs off to when consecutive
 *     attempts record nothing. A minute, which is the point of the dial: a
 *     deterministic refusal — a subscription limit, a revoked app key, an
 *     invalid session token, upstream maintenance — cannot succeed on a retry,
 *     because the input that caused it has not changed, and retrying it every
 *     six seconds is the behaviour rate limits exist to punish. Sixteen attempts
 *     in four minutes (#171) becomes five. <b>It is bounded rather than
 *     unlimited because the classification can be wrong:</b> a transient failure
 *     that happened to record nothing costs this much lost book, so the ceiling
 *     is a minute of one fixture and not an hour of a card.
 * @param failedAttemptsBeforeRed how many attempts in a row may record nothing
 *     before {@code captureCoverage} reports OUT_OF_SERVICE. An attempt records
 *     nothing if it could not open a stream at all, or if the session it opened
 *     ended having written no message. Three, because one is an ordinary failed
 *     connect and two is a bad minute, while three in a row has never happened
 *     for a benign reason — and because the failure this exists for produced
 *     sixteen in four minutes (#171), so the threshold is reached in about
 *     twenty seconds without being reachable by noise.
 * @param stuckStateTimeout how long the recorder may sit in a state that is not
 *     RECORDING, with markets in scope, before {@code captureCoverage} reports
 *     OUT_OF_SERVICE. The fact {@code failedAttemptsBeforeRed} cannot see: that
 *     count only moves when an attempt <em>completes</em> having written
 *     nothing, so a supervisor wedged inside a connect never increments it and a
 *     thread that died without taking the process with it never will again.
 *     Ten minutes because everything benign has had its turn by then — the #176
 *     backoff caps at a minute, the factory re-checks scope every thirty seconds
 *     while idle, and the scope poll runs every fifteen. STANDBY is excluded
 *     from it: another instance holding the lease is single-writer working.
 * @param leaseKey the advisory-lock key single-writer is held on. Its default is
 *     the specified {@code String.hashCode()} of {@code raptor.recorder.capture}
 *     — stable across JVMs by the language spec, so two paddocks against one
 *     database collide by construction while nothing else in this database is
 *     likely to. Overridable for the one case that needs it: two independent
 *     deployments deliberately sharing a cluster.
 * @param flushInterval <b>the durability window.</b> A SIGKILL loses at most
 *     this much book. The baseline it replaces is {@code record_suspensions.py}'s
 *     {@code FLUSH_EVERY_S = 30}, so 200 ms is a 150x improvement on the most
 *     common failure mode — which inverts the intuition that putting a database
 *     on the capture path is riskier than appending to a file.
 */
@ConfigurationProperties("raptor.recorder")
public record RecorderProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("50000") int queueCapacity,
		@DefaultValue("1024") int batchSize,
		@DefaultValue("200ms") Duration flushInterval,
		@DefaultValue("5s") Duration reconnectDelay,
		@DefaultValue("60s") Duration reconnectDelayMax,
		@DefaultValue("-395679298") long leaseKey,
		@DefaultValue("3") int failedAttemptsBeforeRed,
		@DefaultValue("10m") Duration stuckStateTimeout,
		Spill spill,
		// @DefaultValue, or the whole group binds to NULL wherever no
		// `raptor.recorder.watchdog.*` property is set — which is every context
		// outside `backend`, since that is the only module carrying an
		// application.yml. StreamWatchdog survived it by storing the null and
		// dereferencing it later in check(), which nothing schedules in those
		// contexts; SuspendClock reads the tolerance when it is built and does
		// not. A latent NPE either way, and the defaults on Watchdog's own
		// components are the values the code means.
		@DefaultValue Watchdog watchdog) {

	/**
	 * The spill: exceptional by construction, and a flag either way.
	 *
	 * <p>It exists for one failure — the database not accepting writes — and
	 * without it a ten-minute Postgres restart on a Saturday evening loses a match
	 * permanently, because Betfair's {@code clk} replay covers minutes rather than
	 * tens of them. In normal operation no file is ever created.
	 *
	 * @param enabled off puts the recorder back on "lose it and say so loudly"
	 * @param directory where batches are written. Its free space is a health
	 *     metric, not a footnote: disk full <em>and</em> database down at once is
	 *     the one failure nothing here can absorb, so the point of measuring is to
	 *     never arrive at it with no warning.
	 * @param minFree below this, health reports DOWN — the spill can no longer do
	 *     the job it exists for
	 * @param drainInterval how often to put pending files back into the system of
	 *     record. Short, because a spilled message is the only copy that exists
	 *     and it is on one disk: the window between the outage ending and the
	 *     rows landing is pure exposure, and nothing else closes it.
	 * @param drainBatch files per pass. Bounded so a drain after a long outage
	 *     cannot run for minutes; whatever is left goes on the next pass, and
	 *     {@code POST /ops/spill/replay} drains without a limit when somebody is
	 *     watching.
	 * @param staleAfter how long a file may sit pending before health stops
	 *     reading UP. Long enough that an ordinary outage-then-drain never trips
	 *     it, short enough that a drain which is failing gets noticed the same
	 *     day. This is the dial that makes #167 impossible to repeat: a spill
	 *     file sat unreplayed for four sessions and every signal stayed green.
	 * @param drainAttemptsBeforeSettingAside how many times one file may fail to
	 *     replay before it is moved out of the way (#271). A file the database
	 *     refuses on its own merits can never succeed, and because
	 *     {@code SpillIngest} unlinks only after its transaction commits, it
	 *     would otherwise be retried every drain interval forever — holding the
	 *     drain's bounded pass open and letting files accumulate behind it at
	 *     capture rate. Three, because two is indistinguishable from an outage
	 *     that spans one interval and this must not fire on the case the spill
	 *     exists for. Setting aside never deletes anything.
	 */
	public record Spill(
			@DefaultValue("true") boolean enabled,
			Path directory,
			@DefaultValue("1GB") DataSize minFree,
			@DefaultValue("1m") Duration drainInterval,
			@DefaultValue("200") int drainBatch,
			@DefaultValue("15m") Duration staleAfter,
			@DefaultValue("3") int drainAttemptsBeforeSettingAside) {}

	/**
	 * How the recorder notices that it has stopped receiving.
	 *
	 * <p>Both checks exist because <b>a socket that is dead does not say so</b>.
	 * macOS sleep in particular leaves connections that look alive until a write
	 * finally fails, which can take minutes — minutes of a match during which
	 * nothing is recorded and nothing complains.
	 *
	 * @param interval how often to look. Ten seconds against a five-second
	 *     heartbeat: often enough to catch a dead stream inside one reconnect,
	 *     rare enough to be free.
	 * @param silenceTimeout silence longer than this means the stream is gone
	 *     whatever the socket claims. Betfair sends a heartbeat every 5 s, so 30 s
	 *     is six missed ones — comfortably past jitter, well short of a match.
	 * @param clockJumpTolerance how far the wall clock may run ahead of the
	 *     monotonic clock before the machine is judged to have slept. Scheduling
	 *     jitter and NTP slew are milliseconds; a suspend is seconds to hours.
	 */
	public record Watchdog(
			@DefaultValue("10s") Duration interval,
			@DefaultValue("30s") Duration silenceTimeout,
			@DefaultValue("5s") Duration clockJumpTolerance) {}

	public RecorderProperties {
		if (queueCapacity < 1 || batchSize < 1 || flushInterval.isNegative() || flushInterval.isZero()) {
			throw new IllegalArgumentException(
					"queue capacity, batch size and flush interval must all be positive");
		}
		if (reconnectDelay.isNegative()) {
			throw new IllegalArgumentException("reconnect delay must not be negative");
		}
		if (reconnectDelayMax.compareTo(reconnectDelay) < 0) {
			// A ceiling below the floor would make backing off speed the retries
			// up, which is the opposite of what the dial is for and would be
			// invisible in every log.
			throw new IllegalArgumentException(
					"reconnect delay max must not be shorter than the reconnect delay");
		}
	}
}
