package com.stucray.raptor.capture;

import com.stucray.raptor.projection.CloseOutHistory;
import com.stucray.raptor.projection.GapHistory;
import com.stucray.raptor.recorder.RecorderProperties;
import com.stucray.raptor.recorder.RecorderState;
import com.stucray.raptor.recorder.RecorderStatus;
import com.stucray.raptor.scope.DiscoveryReport;
import com.stucray.raptor.scope.ScopeCensus;
import com.stucray.raptor.scope.ScopeDiscovery;
import com.stucray.raptor.scope.ScopeProperties;
import com.stucray.raptor.scope.ScopeSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The one verdict that needed both halves: is anything capturing what is in
 * scope?
 *
 * <p>Twice — #122 and #127 — a plain {@code bin/up} produced a container that
 * was not capturing, and every signal stayed green: {@code /actuator/health} UP,
 * the capture group UP with the state buried in its details, {@code keep-awake}
 * quiet because it watches for RECORDING, and no gap row because a recorder
 * that never connected has nothing to write a gap about. The only trace was a
 * {@code raw.capture_session} that never appeared — an absence, invisible until
 * somebody went looking for a night's data that was not there.
 *
 * <p><b>Only two states can turn this red, and only with markets in scope.</b>
 * {@link RecorderState#DISABLED} and {@link RecorderState#NO_SOURCE} both mean
 * "the only thing that captures on this machine is not going to": the switch is
 * off, or the credentials did not arrive. Everything else stays UP, and the
 * exclusions are the point:
 *
 * <ul>
 * <li>{@code STANDBY} — single-writer working. A second instance reporting
 * itself unhealthy would make a stray development JVM an outage.
 * <li>{@code RECONNECTING} — a single reconnect is ordinary and flapping the
 * verdict on one would train everyone to ignore it. <b>But nothing owned the
 * case where it never stops</b>: this list used to say the watchdog did, and
 * the watchdog looks for a stream that has gone quiet, which a stream torn down
 * 400 ms after every connect never gets to be. See the churn rule below.
 * <li>{@code IDLE} — the factory found nothing to subscribe to. That it can
 * disagree with a non-empty ledger is worth knowing, but it is a planner
 * question and not a switched-off one; both numbers are in this payload.
 * <li>{@code STOPPED} — shutting down, which is not a state anything can be
 * alerted about usefully.
 * </ul>
 *
 * <p><b>The second red, added by #171: a recorder that cannot hold a
 * connection.</b> On 2026-09-05 a refused subscription put this into a
 * seven-second loop — connect, start a session, be refused, tear down — for
 * four minutes, writing nothing and reporting UP throughout. No state word
 * could have caught it, because the loop passes through RECORDING on every
 * cycle and {@code secondsInState} is reset by each transition; the clock #144
 * added measures dwell in the current state, and a fast enough loop never
 * accumulates any.
 *
 * <p>What is unambiguous is the attempts. Sixteen sessions were started in four
 * minutes and every one ended {@code framed=0 written=0}. An attempt that records
 * nothing is not a reconnect, it is a failure, and
 * {@code consecutiveFailedAttempts} counts them — resetting the instant anything
 * is written, so an ordinary disconnect-and-resume never trips it. It also
 * generalises past the cause that prompted it: any reason the recorder cannot
 * keep a stream reads the same way, including one that never gets as far as a
 * session, which a count of sessions alone could not see (#176).
 *
 * <p>And <b>nothing</b> turns it red with an empty horizon. A recorder switched
 * off at 04:00 on a Tuesday is correct and boring; the same recorder at 19:00
 * on a Saturday with forty markets in scope is an incident. An indicator that
 * went red every quiet night would be worse than the silence it replaces.
 *
 * <p><b>The third red, added by #184: stuck, rather than failing.</b> Every
 * signal above needs something to <em>happen</em> — a state to be DISABLED, an
 * attempt to complete and record nothing. A supervisor wedged inside a connect
 * does neither: {@code consecutiveFailedAttempts} only moves when an attempt
 * finishes, so a thread that has died without taking the process with it leaves
 * the count frozen wherever it was, and the container Docker is watching is
 * perfectly healthy because {@code restart: unless-stopped} fires on process
 * exit.
 *
 * <p>What is left is the clock. {@link RecorderStatus#stateSince()} has existed
 * since #144 and this verdict had never read it: <b>not RECORDING, with markets
 * in scope, for longer than {@code stuckStateTimeout}</b>. Ten minutes is past
 * everything benign — the #176 backoff caps at a minute, the factory re-checks
 * scope every thirty seconds while idle, the scope poll runs every fifteen.
 *
 * <p>Two exclusions, and both are the difference between this and the crude
 * copy of it the heartbeat used to carry (#179, and the coverage this restores):
 *
 * <ul>
 * <li>{@code STANDBY} is not stuck. Another instance holds the lease and is
 * recording; single-writer is working, and alerting on it would make a stray
 * development JVM an outage. The heartbeat's version fired on it.
 * <li><b>The clock is what makes it readable at all.</b> RECONNECTING for four
 * seconds is a reconnect and for forty minutes is an outage — the distinction
 * #144 exists for, and one a bare state word cannot make. The heartbeat's
 * version had no clock and fired on both.
 * </ul>
 *
 * <p><b>And the clock is read against scope, not only against the recorder
 * (#278).</b> {@code stateSince()} does not reset when scope fills, so between
 * cards — hours of correct IDLE — this test was already satisfied before the
 * first fixture entered the horizon, and the verdict went OUT_OF_SERVICE for
 * the ~22 seconds it took the stream to authenticate. The dwell is therefore
 * the shorter of two clocks: how long the recorder has been in this state, and
 * how long there has been anything to capture at all.
 *
 * <p>{@code IDLE} is included, and that is not a contradiction of the exclusion
 * above. The factory reports IDLE only while it is actively waiting for scope
 * and re-checks every thirty seconds, so IDLE persisting for ten minutes
 * <em>while the ledger holds markets</em> is not a planner question, it is the
 * two halves disagreeing about whether there is anything to record.
 *
 * <p><b>The fourth red, added by #180: nobody can tell what is on.</b> Every
 * verdict above is gated on scope being non-empty, and that gate is right —
 * it is what keeps a quiet Tuesday quiet. Its consequence is that <b>a
 * discovery failure and an international break are the same observation</b>: a
 * revoked app key, an expired cert, Betfair in maintenance and a typo in
 * {@code capture.properties} all produce {@code marketsInScope: 0}, which reads
 * UP here, OK at the heartbeat, and UP at the root.
 *
 * <p>So the one signal that is <b>not</b> gated on scope is the catalogue poll
 * failing, because a poll that cannot run is exactly the case where the empty
 * scope it leaves behind is not evidence of anything.
 * {@code MarketScopeService.poll()} swallows the exception deliberately — a REST
 * failure must not retire a match in progress — and
 * {@link DiscoveryReport#consecutivePollFailures()} is what stops the swallow
 * being silent.
 *
 * <p><b>What is reported and deliberately not judged.</b> Unresolved league
 * names and a long-empty scope are on the payload and turn nothing red.
 * {@code listCompetitions} lists only competitions that currently have markets,
 * so a league between rounds legitimately does not resolve, and an indicator
 * that fired on that would go red every international break — the exact failure
 * this class exists to avoid. Same for the empty-scope clock: a real break is
 * days long, and the threshold that separates it from broken discovery has to
 * be calibrated against a fortnight of real numbers rather than guessed here.
 * Publishing them first is what makes that calibration possible.
 *
 * <p>{@code OUT_OF_SERVICE} rather than DOWN or a custom status: it is the
 * built-in whose meaning is exactly this — the instance is not doing the job —
 * it already sorts above UP in the default aggregator, and it already maps to
 * 503 with no configuration to get wrong. DOWN would claim the application is
 * broken, and it is not: the read side is serving perfectly well.
 */
@Component
class CaptureCoverageHealthIndicator implements HealthIndicator {

	/** The states that mean nothing on this machine is going to capture. */
	private static final Set<RecorderState> NOT_GOING_TO_CAPTURE =
			EnumSet.of(RecorderState.DISABLED, RecorderState.NO_SOURCE);

	/**
	 * The states a clock says nothing about.
	 *
	 * <p>RECORDING is the point of the exercise. STANDBY is single-writer
	 * working — the recording is another instance's, and dwelling in it is what
	 * a correct second instance does all night.
	 */
	private static final Set<RecorderState> NOT_STUCK =
			EnumSet.of(RecorderState.RECORDING, RecorderState.STANDBY);

	private final RecorderStatus recorder;
	private final ScopeCensus census;
	private final ScopeDiscovery discovery;
	private final CloseOutHistory closeOuts;
	private final GapHistory gaps;
	private final Clock clock;
	private final int failedAttemptsBeforeRed;
	private final Duration stuckStateTimeout;
	private final int pollFailuresBeforeRed;

	CaptureCoverageHealthIndicator(RecorderStatus recorder, ScopeCensus census,
			ScopeDiscovery discovery, CloseOutHistory closeOuts,
			GapHistory gaps, RecorderProperties recorderProperties,
			ScopeProperties scopeProperties, Clock clock) {
		this.recorder = recorder;
		this.census = census;
		this.discovery = discovery;
		this.closeOuts = closeOuts;
		this.gaps = gaps;
		this.clock = clock;
		this.failedAttemptsBeforeRed = recorderProperties.failedAttemptsBeforeRed();
		this.stuckStateTimeout = recorderProperties.stuckStateTimeout();
		this.pollFailuresBeforeRed = scopeProperties.pollFailuresBeforeRed();
	}

	@Override
	public Health health() {
		RecorderState state = recorder.state();
		ScopeSummary scope = census.summarise();
		DiscoveryReport found = discovery.discovery();
		int failed = recorder.consecutiveFailedAttempts();
		Duration inState = Duration.between(recorder.stateSince(), clock.instant());

		List<String> reasons = reasons(state, scope, found, failed, inState);


		Health.Builder health = reasons.isEmpty() ? Health.up() : Health.outOfService();
		health.withDetail("state", state.name())
				.withDetail("marketsInScope", scope.marketsInScope())
				.withDetail("live", scope.live())
				// Always, not only when red: a count climbing towards the threshold
				// is the earliest sign there is, and a reader should not have to
				// wait for the verdict to flip before they can see it.
				.withDetail("consecutiveFailedAttempts", failed)
				.withDetail("secondsInState", inState.toSeconds())
				.withDetail("consecutiveScopePollFailures", found.consecutivePollFailures())
				.withDetail("configuredLeagues", found.configuredLeagues())
				.withDetail("unresolvedLeagues", found.unresolvedLeagues().size())
				.withDetail("secondsSinceScopeNonEmpty", found.sinceScopeNonEmpty().toSeconds())
				// How long the current card has been on, which is the denominator
				// of the stuck test below and is worth reading on its own: it is
				// the only published answer to "when did scope fill?" (#278).
				.withDetail("secondsScopeNonEmpty", found.scopeNonEmptyFor().toSeconds());

		// NO PROJECTION FACTS HERE SINCE #316. This indicator used to report the
		// projection backlog and when the close-out's capture half last
		// succeeded — whether captured markets had reached `query`. That work is
		// overround-analysis's now, and it reports it on its own derivation
		// health: a projection that is behind is rebuildable and never urgent, so
		// it does not belong on the one channel whose every message is (PRD #245).
		//
		// WHAT STAYS is the close-out's own record, because it still fetches the
		// archive into raw every night and is still a `@Scheduled` timer that can
		// stop without a sound (#141, #201). So: when the last run FINISHED,
		// whatever its verdict — a football-data outage is an ordinary night, a
		// run that never came back is not — and what that run did, for the
		// morning summary. A fact and not a verdict: this indicator's verdict
		// drives an automatic restart, which fetches nothing.
		//
		// `finishedAt` is the run's own timestamp and is the summary's IDENTITY:
		// the heartbeat keys its once-per-run de-dupe on it, so a five-minute poll
		// sends one notification per close-out rather than 288 per day.
		closeOuts.latestFinished().ifPresentOrElse(run -> health
				.withDetail("lastCloseOutSecondsAgo",
						Duration.between(run.finishedAt(), clock.instant()).toSeconds())
				.withDetail("closeOut", Map.of(
						"finishedAt", run.finishedAt().toString(),
						"archive", run.archiveSuccessful() ? "COMPLETED" : "FAILED",
						"archiveFiles", run.archiveFiles(),
						// Map.of refuses a null value, and a clean night has no detail.
						"detail", run.detail() == null ? "" : run.detail())),
				// "never", not an age of zero: a chain that has not run and one that
				// ran a moment ago must not read alike.
				() -> health.withDetail("lastCloseOut", "never"));
		// WHETHER TONIGHT'S RUN CAME WHEN IT WAS DUE (#334). The trigger is a launchd
		// agent on the host since then, so this application can no longer vouch
		// for a firing by being the thing that fires. `overdue` is the one bit a
		// reader needs; `startedAt` beside `dueAt` is how late a run that DID come
		// was, which is the case #333 made silent everywhere else. A fact and not
		// a verdict, for the reason given above: a restart fires nothing on the
		// host.
		CloseOutHistory.Punctuality punctuality = closeOuts.punctuality();
		Map<String, Object> due = new LinkedHashMap<>();
		due.put("dueAt", punctuality.dueAt().toString());
		due.put("overdue", punctuality.overdue());
		Instant startedAt = punctuality.startedAt();
		if (startedAt != null) {
			due.put("startedAt", startedAt.toString());
			due.put("startedLateBySeconds",
					Duration.between(punctuality.dueAt(), startedAt).toSeconds());
		}
		health.withDetail("closeOutDue", due);
		// WHAT A SUSPEND COST, for the report the host sends on wake (#291).
		// A FACT AND NOT A VERDICT, like the close-out above and for a sharper
		// reason: the gap is over. The recorder reconnected, the machine is
		// awake, and there is nothing a restart could do about an interval that
		// has already closed — so this must never reach `reasons`, whose list
		// drives the heartbeat's automatic restart.
		//
		// It is here rather than in the app's own alerting because nothing
		// inside paddock can report that paddock is absent: during a suspend the
		// host is asleep, launchd does not fire, and the process is not running.
		// The finding is therefore always retrospective, and its reader is the
		// heartbeat's first pass after the machine comes back.
		//
		// `endedAt` is the gap's OWN timestamp and is the report's IDENTITY: the
		// heartbeat keys its once-per-gap de-dupe on it, exactly as it does on
		// the close-out's `finishedAt`.
		gaps.lastGapDuringPlay().ifPresent(gap -> health.withDetail("gapInPlay", Map.of(
				"endedAt", gap.endedAt().toString(),
				"cause", gap.cause(),
				"seconds", gap.seconds(),
				"markets", gap.markets(),
				// The number that decides whether this is worth waking anybody
				// for. Absent entirely when it would be zero: an alert that fires
				// on every end-of-evening lid close is an alert that gets muted,
				// and the silence is then load-bearing on the night it matters.
				"live", gap.live())));
		if (!found.unresolvedLeagues().isEmpty()) {
			// The names, not just the count: the fix is editing one of them, and a
			// number sends the reader to the container log to find out which.
			health.withDetail("unresolvedLeagueNames", found.unresolvedLeagues());
		}
		if (!reasons.isEmpty()) {
			health.withDetail("reason", String.join("; and ", reasons));
		}
		return health.build();
	}

	/**
	 * Every way this instance is currently not doing its job, in words.
	 *
	 * <p>Words as well as fields because the reader of a 503 at 19:00 on a
	 * Saturday needs the sentence, not four numbers to combine — and because the
	 * heartbeat forwards exactly this to a phone (#179).
	 *
	 * <p><b>A list rather than the first match.</b> Two of these at once is a
	 * worse night than either, and reporting only the first would hide the
	 * second from the only reader that acts on it. Empty means UP: the verdict
	 * is "is there anything to say", so a red with no reason cannot be built.
	 */
	private List<String> reasons(RecorderState state, ScopeSummary scope, DiscoveryReport found,
			int failed, Duration inState) {
		boolean switchedOff = NOT_GOING_TO_CAPTURE.contains(state);
		List<String> reasons = new ArrayList<>();
		if (scope.anythingToCapture()) {
			if (switchedOff) {
				reasons.add("recorder is " + state.name() + " while " + scope.marketsInScope()
						+ " markets are in scope; nothing is capturing them");
			}
			if (failed >= failedAttemptsBeforeRed) {
				reasons.add(failed + " attempt(s) in a row recorded nothing while "
						+ scope.marketsInScope() + " markets are in scope; the recorder "
						+ "cannot hold a connection");
			}
			// Not also when switchedOff: DISABLED for an hour satisfies the dwell
			// test too, and the switched-off sentence is the one that names the fix.
			//
			// MEASURED AGAINST THE SHORTER OF THE TWO CLOCKS (#278). The recorder's
			// own dwell is not the whole answer, because it does not reset when
			// scope fills: between cards the recorder sits IDLE for hours —
			// correctly, there is nothing to capture — so by the time a fixture
			// enters the horizon this test was ALREADY satisfied, and the verdict
			// went OUT_OF_SERVICE on the first evaluation after scope flipped,
			// staying there until the stream authenticated. Both clauses were true
			// and the conjunction was not: it had not been idle WHILE there was
			// something to capture, which is what the sentence claims and what
			// stuck is supposed to mean. Observed 2026-09-13, a 22-second window
			// at 07:08:50Z, which cannot reach the heartbeat's three-check restart
			// gate but can and does send a spurious NOT-CAPTURING push — at the
			// start of a card, which is the worst moment to teach an operator that
			// this alert cries wolf.
			//
			// So the recorder gets the same ten minutes to connect, authenticate
			// and subscribe that it would get for any other transition, and the
			// finding the reason exists for — still not RECORDING ten minutes into
			// a card — is untouched.
			Duration stuck = min(inState, found.scopeNonEmptyFor());
			if (!switchedOff && !NOT_STUCK.contains(state)
					&& stuck.compareTo(stuckStateTimeout) > 0) {
				// The scope-relative figure, not the absolute one: on an ordinary
				// morning `inState` is the whole overnight gap, and a sentence
				// reading "IDLE for 480 minute(s)" describes time the recorder was
				// right to be idle and sends its reader looking for a fault hours
				// older than the one in front of them.
				reasons.add("recorder has been " + state.name() + " for " + stuck.toMinutes()
						+ " minute(s) while " + scope.marketsInScope() + " markets are in "
						+ "scope; it is not failing, it is stuck");
			}
		}
		// OUTSIDE the scope gate, and that is the whole of #180: the scope this
		// would be gated on is the one the failing poll could not refresh, so
		// gating on it is asking the broken instrument whether it is broken.
		if (found.consecutivePollFailures() >= pollFailuresBeforeRed) {
			reasons.add(found.consecutivePollFailures() + " catalogue poll(s) in a row have "
					+ "failed; what is in scope is unknown, and an empty scope right now is "
					+ "not evidence that there is nothing on");
		}
		return reasons;
	}

	private static Duration min(Duration a, Duration b) {
		return a.compareTo(b) <= 0 ? a : b;
	}
}
