package com.stucray.raptor.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stucray.raptor.recorder.RecorderProperties;
import com.stucray.raptor.projection.CloseOutHistory;
import com.stucray.raptor.projection.GapHistory;
import com.stucray.raptor.recorder.RecorderState;
import com.stucray.raptor.recorder.RecorderStatus;
import com.stucray.raptor.scope.DiscoveryReport;
import com.stucray.raptor.scope.ScopeCensus;
import com.stucray.raptor.scope.ScopeDiscovery;
import com.stucray.raptor.scope.ScopeProperties;
import com.stucray.raptor.scope.ScopeSummary;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.util.unit.DataSize;

/**
 * Both halves of the matrix, which is the bar #131 set for itself.
 *
 * <p>An indicator that goes red when a recorder is off is easy and useless — it
 * would fire every quiet Tuesday, and be ignored by the second week. The
 * property worth having is the pair: red when a switched-off recorder has
 * markets to record, and green when the same recorder has none.
 */
class CaptureCoverageHealthIndicatorTest {

	private final RecorderStatus recorder = mock(RecorderStatus.class);
	private final ScopeCensus census = mock(ScopeCensus.class);
	private final ScopeDiscovery discovery = mock(ScopeDiscovery.class);
	private static final Instant NOW = Instant.parse("2026-09-07T19:00:00Z");

	private final CloseOutHistory closeOuts = mock(CloseOutHistory.class);
	private final GapHistory gaps = mock(GapHistory.class);

	private final CaptureCoverageHealthIndicator indicator = new CaptureCoverageHealthIndicator(
			recorder, census, discovery, closeOuts, gaps, properties(), scopeProperties(),
			Clock.fixed(NOW, ZoneOffset.UTC));

	/** Discovery working: polls landing, every league resolved, scope seen recently. */
	@BeforeEach
	void discoveryIsHealthy() {
		when(discovery.discovery()).thenReturn(found(0, List.of()));
		// Likewise no finished run: the summary block is a detail, and a mock left
		// unstubbed would hand every case a null Optional rather than an empty one.
		when(closeOuts.latestFinished()).thenReturn(java.util.Optional.empty());
		// Tonight's run not yet due-and-missed: every other case is about the
		// verdict, and an unstubbed punctuality would be null.
		when(closeOuts.punctuality()).thenReturn(new CloseOutHistory.Punctuality(
				NOW.minusSeconds(19 * 3600 + 1800), null, false));
		// No gap lately, which is the ordinary answer: every case below is about
		// the verdict, and the wake report must not touch it.
		when(gaps.lastGapDuringPlay()).thenReturn(java.util.Optional.empty());
		// A state entered a moment ago: every pre-#184 case asserts on something
		// other than dwell, and an unstubbed mock would hand them Instant zero.
		when(recorder.stateSince()).thenReturn(NOW.minusSeconds(5));
	}

	/** 19:00 on a Saturday, and the switch is off. The incident #122 recorded. */
	@ParameterizedTest
	@EnumSource(value = RecorderState.class, names = {"DISABLED", "NO_SOURCE"})
	void notUpWhenNothingIsGoingToCaptureMarketsThatAreInScope(RecorderState state) {
		when(recorder.state()).thenReturn(state);
		when(census.summarise()).thenReturn(busy());

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
		assertThat(health.getDetails()).containsEntry("state", state.name())
				.containsEntry("marketsInScope", 40)
				.hasEntrySatisfying("reason",
						reason -> assertThat(reason).asString().contains("nothing is capturing"));
	}

	/** 04:00 on a Tuesday, and the switch is off. Correct, and boring. */
	@ParameterizedTest
	@EnumSource(value = RecorderState.class, names = {"DISABLED", "NO_SOURCE"})
	void upWhenThereIsNothingToCaptureInTheFirstPlace(RecorderState state) {
		when(recorder.state()).thenReturn(state);
		when(census.summarise()).thenReturn(quiet());

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("marketsInScope", 0)
				.doesNotContainKey("reason");
	}

	/**
	 * Every other state stays UP with a full horizon, as long as sessions are
	 * productive. Standby is single-writer working, idle is the planner's
	 * business, stopped is a shutdown — and a single reconnect is ordinary,
	 * which is why the churn rule counts sessions rather than reading the state
	 * word.
	 */
	@ParameterizedTest
	@EnumSource(value = RecorderState.class,
			names = {"RECORDING", "RECONNECTING", "IDLE", "STANDBY", "STOPPED"})
	void everyOtherStateStaysUpEvenWithAFullHorizon(RecorderState state) {
		when(recorder.state()).thenReturn(state);
		when(census.summarise()).thenReturn(busy());

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}

	/** The numbers are on the payload whatever the verdict, so it can be judged. */
	@Test
	void reportsWhatItJudgedOn() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(census.summarise()).thenReturn(busy());

		assertThat(indicator.health().getDetails()).containsEntry("state", "RECORDING")
				.containsEntry("marketsInScope", 40)
				.containsEntry("live", 6);
	}

	/**
	 * The recorder cannot hold a connection, and something needs recording.
	 *
	 * <p>This is #171. On 2026-09-05 a refused subscription produced sixteen
	 * sessions in four minutes, every one ending {@code framed=0 written=0},
	 * while this indicator read UP throughout — the loop passed through
	 * RECORDING on every cycle, so no state word and no {@code secondsInState}
	 * could see it. The sessions could.
	 */
	@ParameterizedTest
	@EnumSource(value = RecorderState.class, names = {"RECORDING", "RECONNECTING"})
	void outOfServiceWhenSessionsKeepEndingWithNothingWritten(RecorderState state) {
		when(recorder.state()).thenReturn(state);
		when(recorder.consecutiveFailedAttempts()).thenReturn(3);
		when(census.summarise()).thenReturn(busy());

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
		assertThat(health.getDetails()).containsEntry("consecutiveFailedAttempts", 3)
				.hasEntrySatisfying("reason", reason -> assertThat(reason).asString()
						.contains("cannot hold a connection"));
	}

	/**
	 * One or two empty sessions are not a crashloop.
	 *
	 * <p>The exclusion that keeps this worth reading: a failed connect happens,
	 * and a verdict that flipped on the first one would be ignored by the second
	 * week — which is the reason RECONNECTING was excluded outright before, and
	 * the reason the replacement is a count rather than a state.
	 */
	@ParameterizedTest
	@ValueSource(ints = {1, 2})
	void upWhileEmptySessionsAreStillBelowTheThreshold(int empty) {
		when(recorder.state()).thenReturn(RecorderState.RECONNECTING);
		when(recorder.consecutiveFailedAttempts()).thenReturn(empty);
		when(census.summarise()).thenReturn(busy());

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("consecutiveFailedAttempts", empty);
	}

	/**
	 * Churn with an empty horizon is still not an incident.
	 *
	 * <p>The rule the whole class is built on, applied to the new red: nothing
	 * turns this indicator over when there is nothing to capture.
	 */
	@Test
	void upWhenChurningWithNothingInScope() {
		when(recorder.state()).thenReturn(RecorderState.RECONNECTING);
		when(recorder.consecutiveFailedAttempts()).thenReturn(50);
		when(census.summarise()).thenReturn(quiet());

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}

	/**
	 * The catalogue cannot be reached, and <b>nothing is in scope</b>.
	 *
	 * <p>This is #180 in one test. Every other red in this class is gated on
	 * {@code marketsInScope > 0}, and this one must not be: the empty scope is the
	 * one the failing poll could not refresh. On 2026-09-06 an app key revoked
	 * overnight would have produced a reading byte-identical to a quiet morning —
	 * UP here, OK at the heartbeat, UP at the root.
	 */
	@Test
	void outOfServiceWhenTheCatalogueCannotBeReached() {
		when(recorder.state()).thenReturn(RecorderState.IDLE);
		when(census.summarise()).thenReturn(quiet());
		when(discovery.discovery()).thenReturn(found(3, List.of()));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
		assertThat(health.getDetails()).containsEntry("marketsInScope", 0)
				.containsEntry("consecutiveScopePollFailures", 3)
				.hasEntrySatisfying("reason", reason -> assertThat(reason).asString()
						.contains("not evidence that there is nothing on"));
	}

	/** One or two failed polls are a Betfair blip, not an outage. */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void upWhileFailedPollsAreStillBelowTheThreshold(int failures) {
		when(recorder.state()).thenReturn(RecorderState.IDLE);
		when(census.summarise()).thenReturn(quiet());
		when(discovery.discovery()).thenReturn(found(failures, List.of()));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("consecutiveScopePollFailures", failures);
	}

	/**
	 * A league that does not resolve is reported and <b>not</b> judged.
	 *
	 * <p>The exclusion that keeps this indicator worth reading.
	 * {@code listCompetitions} returns only competitions that currently have
	 * markets, so a league between rounds is legitimately absent — and an
	 * indicator that went red on that would go red every international break,
	 * which is the exact failure this class was written to avoid. The names are on
	 * the payload because the fix is editing one of them.
	 */
	@Test
	void namesThatDidNotResolveAreReportedWithoutTurningItRed() {
		when(recorder.state()).thenReturn(RecorderState.IDLE);
		when(census.summarise()).thenReturn(quiet());
		when(discovery.discovery()).thenReturn(found(0, List.of("Scottish Premiership")));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("unresolvedLeagues", 1)
				.containsEntry("configuredLeagues", 8)
				.containsEntry("unresolvedLeagueNames", List.of("Scottish Premiership"))
				.doesNotContainKey("reason");
	}

	/**
	 * The empty-scope clock is informational, whatever it reads.
	 *
	 * <p>A genuine international break is days of legitimately empty scope, so the
	 * threshold that would separate it from broken discovery has to be calibrated
	 * against real numbers. Publishing the number is what makes that possible;
	 * guessing a constant here is what this test forbids.
	 */
	@Test
	void aLongEmptyScopeIsPublishedAndNotJudged() {
		when(recorder.state()).thenReturn(RecorderState.IDLE);
		when(census.summarise()).thenReturn(quiet());
		when(discovery.discovery()).thenReturn(new DiscoveryReport(0, 8, List.of(),
				Duration.ofDays(9), Duration.ZERO));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails())
				.containsEntry("secondsSinceScopeNonEmpty", Duration.ofDays(9).toSeconds());
	}

	/**
	 * Two faults at once say both.
	 *
	 * <p>The reasons are joined rather than chosen between: a recorder that cannot
	 * hold a connection <em>and</em> a catalogue nobody can reach is a worse night
	 * than either, and reporting only the first would hide the second from the one
	 * reader — the heartbeat — that forwards this sentence to a phone (#179).
	 */
	@Test
	void bothReasonsAreCarriedWhenBothApply() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(recorder.consecutiveFailedAttempts()).thenReturn(5);
		when(census.summarise()).thenReturn(busy());
		when(discovery.discovery()).thenReturn(found(4, List.of()));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
		assertThat(health.getDetails()).hasEntrySatisfying("reason",
				reason -> assertThat(reason).asString().contains("cannot hold a connection")
						.contains("catalogue poll(s) in a row have failed"));
	}

	/**
	 * Stuck, rather than failing — which is the whole of #184.
	 *
	 * <p>A supervisor wedged inside a connect completes no attempt, so
	 * {@code consecutiveFailedAttempts} stays frozen at whatever it was, and the
	 * container Docker is watching is perfectly healthy because
	 * {@code restart: unless-stopped} fires on process exit. Nothing but the
	 * clock can see it. RECONNECTING here is the shape that was silent on
	 * 2026-09-07: 45 minutes of it, 42 markets in scope, six matches in play, and
	 * every signal reading OK.
	 */
	@ParameterizedTest
	@EnumSource(value = RecorderState.class, names = {"RECONNECTING", "IDLE", "STOPPED"})
	void outOfServiceWhenTheRecorderHasBeenStuckWithMarketsInScope(RecorderState state) {
		when(recorder.state()).thenReturn(state);
		when(recorder.stateSince()).thenReturn(NOW.minus(Duration.ofMinutes(45)));
		when(census.summarise()).thenReturn(busy());

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
		assertThat(health.getDetails())
				.containsEntry("secondsInState", Duration.ofMinutes(45).toSeconds())
				.hasEntrySatisfying("reason", reason -> assertThat(reason).asString()
						.contains("it is not failing, it is stuck"));
	}

	/**
	 * A reconnect is not a wedge, and the clock is the only thing that knows.
	 *
	 * <p>The distinction #144 exists for. The heartbeat probe this red replaces
	 * had no clock at all and fired on both, which is why it could not be left
	 * switched on and could not simply be reinstated.
	 */
	@ParameterizedTest
	@ValueSource(ints = {5, 60, 599})
	void upWhileTheRecorderHasNotBeenStuckLongEnough(int seconds) {
		when(recorder.state()).thenReturn(RecorderState.RECONNECTING);
		when(recorder.stateSince()).thenReturn(NOW.minusSeconds(seconds));
		when(census.summarise()).thenReturn(busy());

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}

	/**
	 * STANDBY is not stuck however long it lasts.
	 *
	 * <p>Another instance holds the lease and is recording. Dwelling here all
	 * night is exactly what a correct second instance does, and the retired
	 * heartbeat probe alerted on it — a false positive that would have made a
	 * stray development JVM an outage, and now a restart of the wrong container.
	 */
	@Test
	void upWhenStandingByAllEveningWithAFullHorizon() {
		when(recorder.state()).thenReturn(RecorderState.STANDBY);
		when(recorder.stateSince()).thenReturn(NOW.minus(Duration.ofHours(6)));
		when(census.summarise()).thenReturn(busy());

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}

	/** And the rule the whole class is built on: nothing is stuck at 04:00. */
	@Test
	void upWhenIdleForHoursWithNothingInScope() {
		when(recorder.state()).thenReturn(RecorderState.IDLE);
		when(recorder.stateSince()).thenReturn(NOW.minus(Duration.ofHours(9)));
		when(census.summarise()).thenReturn(quiet());

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}

	/**
	 * A switched-off recorder says so, and does not also say it is stuck.
	 *
	 * <p>DISABLED for an hour satisfies the dwell test too; the two reasons are
	 * the same news and the switched-off one is the one that names the fix.
	 */
	@Test
	void aSwitchedOffRecorderGivesOneReasonAndNotTwo() {
		when(recorder.state()).thenReturn(RecorderState.DISABLED);
		when(recorder.stateSince()).thenReturn(NOW.minus(Duration.ofHours(1)));
		when(census.summarise()).thenReturn(busy());

		assertThat(indicator.health().getDetails()).hasEntrySatisfying("reason",
				reason -> assertThat(reason).asString().contains("nothing is capturing them")
						.doesNotContain("it is stuck"));
	}

	/**
	 * The first minute of a card is not a wedge, and #278 is what it cost.
	 *
	 * <p>{@code stateSince()} does not reset when scope fills. Between cards the
	 * recorder sits IDLE for hours — correctly, there is nothing to capture — so
	 * by the time a fixture entered the horizon the dwell test was ALREADY
	 * satisfied, and this went OUT_OF_SERVICE on the first evaluation after scope
	 * flipped non-empty, staying there until the stream authenticated. Both
	 * clauses were true and the conjunction was not: the recorder had not been
	 * idle WHILE there was anything to capture.
	 *
	 * <p>Observed 2026-09-13: scope filled at 07:08:40Z, OUT_OF_SERVICE at
	 * 07:08:50Z with {@code secondsInState} 928, RECORDING at 07:09:13Z. Too
	 * short to reach the heartbeat's three-check restart gate, long enough to
	 * send a NOT-CAPTURING push — at the start of a card, which is the worst
	 * moment to teach an operator that this alert cries wolf.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, 30, 599})
	void upWhileScopeHasOnlyJustFilledHoweverLongTheRecorderHasBeenIdle(int secondsOfCard) {
		when(recorder.state()).thenReturn(RecorderState.IDLE);
		// The whole overnight gap, which is the ordinary case and not the extreme
		// one: the 09-13 sighting read only 15 minutes because a deploy had
		// restarted the JVM.
		when(recorder.stateSince()).thenReturn(NOW.minus(Duration.ofHours(9)));
		when(census.summarise()).thenReturn(busy());
		when(discovery.discovery())
				.thenReturn(found(0, List.of(), Duration.ofSeconds(secondsOfCard)));

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}

	/**
	 * And the finding the reason exists for is untouched: still not RECORDING ten
	 * minutes into a card.
	 *
	 * <p>The number in the sentence is the scope-relative one. On an ordinary
	 * morning {@code inState} is the whole overnight gap, and "IDLE for 540
	 * minute(s)" describes time the recorder was right to be idle — it sends its
	 * reader looking for a fault hours older than the one in front of them.
	 */
	@Test
	void outOfServiceWhenStillIdleTenMinutesIntoACardAndSaysTheScopeRelativeMinutes() {
		when(recorder.state()).thenReturn(RecorderState.IDLE);
		when(recorder.stateSince()).thenReturn(NOW.minus(Duration.ofHours(9)));
		when(census.summarise()).thenReturn(busy());
		when(discovery.discovery())
				.thenReturn(found(0, List.of(), Duration.ofMinutes(11)));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
		assertThat(health.getDetails())
				.containsEntry("secondsScopeNonEmpty", Duration.ofMinutes(11).toSeconds())
				.containsEntry("secondsInState", Duration.ofHours(9).toSeconds())
				.hasEntrySatisfying("reason", reason -> assertThat(reason).asString()
						.contains("IDLE for 11 minute(s)")
						.doesNotContain("540 minute(s)"));
	}

	/**
	 * The other direction, and the reason the dwell is a minimum rather than a
	 * swap: a card four hours old does not make a recorder that reconnected a
	 * minute ago stuck.
	 */
	@Test
	void upWhenTheCardIsOldButTheRecorderJustChangedState() {
		when(recorder.state()).thenReturn(RecorderState.RECONNECTING);
		when(recorder.stateSince()).thenReturn(NOW.minusSeconds(60));
		when(census.summarise()).thenReturn(busy());
		when(discovery.discovery()).thenReturn(found(0, List.of(), Duration.ofHours(4)));

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}

	private static DiscoveryReport found(int pollFailures, List<String> unresolved) {
		// A card that has been on for hours, which is what every case written
		// before #278 assumed without being able to say so: they vary the
		// recorder's clock and mean "stuck DURING a card".
		return found(pollFailures, unresolved, Duration.ofHours(6));
	}

	private static DiscoveryReport found(int pollFailures, List<String> unresolved,
			Duration scopeNonEmptyFor) {
		return new DiscoveryReport(pollFailures, 8, unresolved, Duration.ofMinutes(20),
				scopeNonEmptyFor);
	}

	private static ScopeProperties scopeProperties() {
		return new ScopeProperties(Duration.ofHours(4), Duration.ofMinutes(15),
				Duration.ofSeconds(10), Duration.ofMinutes(130), Duration.ofHours(6),
				Duration.ofHours(48), Duration.ofMinutes(30), true, 3);
	}

	private static RecorderProperties properties() {
		return new RecorderProperties(true, 50_000, 1024, Duration.ofMillis(200),
				Duration.ofSeconds(5), Duration.ofSeconds(60), 1L, 3, Duration.ofMinutes(10),
				new RecorderProperties.Spill(false, Path.of("target/unused"),
						DataSize.ofGigabytes(1), Duration.ofMinutes(1), 200,
						Duration.ofMinutes(15), 3),
				new RecorderProperties.Watchdog(Duration.ofSeconds(10), Duration.ofSeconds(30),
						Duration.ofSeconds(5)));
	}

	private static ScopeSummary busy() {
		return new ScopeSummary(40, 20, 14, 6, Instant.parse("2026-09-05T18:30:00Z"));
	}

	private static ScopeSummary quiet() {
		return new ScopeSummary(0, 0, 0, 0, null);
	}

	/**
	 * No projection facts since #316: whether captured markets reached
	 * {@code query} is overround-analysis's to report, on its own health, and the
	 * capture channel carries nothing a re-projection could fix.
	 */
	@Test
	void reportsNoProjectionFacts() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(census.summarise()).thenReturn(busy());

		assertThat(indicator.health().getDetails())
				.doesNotContainKeys("projectionBacklog", "archiveBacklog", "backlogMeasuredSecondsAgo");
	}

	/**
	 * When the last run FINISHED, whatever its verdict, and it does not move the
	 * verdict. The heartbeat's staleness probe reads this: it asks whether the
	 * timer is still firing, and a football-data outage is a night it fired.
	 */
	@Test
	void theLastRunsAgeIsReportedWhateverItsVerdict() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(census.summarise()).thenReturn(busy());
		when(closeOuts.latestFinished()).thenReturn(java.util.Optional.of(
				new CloseOutHistory.CloseOut(NOW.minusSeconds(3600), false, 0, "archive sweep failed (503)")));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("lastCloseOutSecondsAgo", 3600L);
	}

	/** Never run reads as "never", not as an age of zero. */
	@Test
	void aCloseOutThatHasNeverRunSaysSo() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(census.summarise()).thenReturn(busy());

		assertThat(indicator.health().getDetails())
				.containsEntry("lastCloseOut", "never")
				.doesNotContainKey("lastCloseOutSecondsAgo");
	}

	/**
	 * The last run's counts are published for the morning summary (#201).
	 *
	 * <p>Asserted key by key because the host reads them with jq: a renamed field
	 * is a summary that silently reports zeros, and nothing on this side would
	 * notice — the indicator would still be green and the notification would
	 * still arrive.
	 */
	@Test
	void theLastFinishedRunIsPublishedForTheSummary() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(census.summarise()).thenReturn(busy());
		when(closeOuts.latestFinished()).thenReturn(java.util.Optional.of(
				new CloseOutHistory.CloseOut(NOW.minusSeconds(3600), false, 0,
						"archive sweep failed (503)")));

		@SuppressWarnings("unchecked")
		java.util.Map<String, Object> closeOut =
				(java.util.Map<String, Object>) indicator.health().getDetails().get("closeOut");

		assertThat(closeOut)
				// The summary's identity: the host de-dupes on it, so a changed
				// spelling would push one notification every five minutes.
				.containsEntry("finishedAt", NOW.minusSeconds(3600).toString())
				.containsEntry("archive", "FAILED")
				.containsEntry("archiveFiles", 0)
				.containsEntry("detail", "archive sweep failed (503)")
				// The capture half left in #316; its keys must not linger as zeros.
				.doesNotContainKeys("capture", "marketsProjected", "marketsFailed");
	}

	/**
	 * No finished run publishes no summary block at all.
	 *
	 * <p>Rather than one full of zeroes, which the host would announce as a night
	 * that did nothing — a claim about a chain that has simply not run yet.
	 */
	@Test
	void aChainThatHasNotFinishedARunPublishesNoSummary() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(census.summarise()).thenReturn(busy());
		when(closeOuts.latestFinished()).thenReturn(java.util.Optional.empty());

		assertThat(indicator.health().getDetails()).doesNotContainKey("closeOut");
	}

	/**
	 * An overdue close-out is published, and changes nothing about the verdict
	 * (#334): the trigger is a launchd agent, and the restart this verdict drives
	 * cannot make it fire.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void anOverdueCloseOutIsPublishedButIsNotAVerdict() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(census.summarise()).thenReturn(busy());
		Instant due = Instant.parse("2026-09-06T23:30:00Z");
		when(closeOuts.punctuality()).thenReturn(new CloseOutHistory.Punctuality(due, null, true));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat((java.util.Map<String, Object>) health.getDetails().get("closeOutDue"))
				.containsEntry("dueAt", "2026-09-06T23:30:00Z")
				.containsEntry("overdue", true)
				.doesNotContainKey("startedAt");
	}

	/** A run that came late says by how much — the case #333 left silent. */
	@Test
	@SuppressWarnings("unchecked")
	void aLateRunSaysHowLate() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(census.summarise()).thenReturn(busy());
		Instant due = Instant.parse("2026-09-06T23:30:00Z");
		when(closeOuts.punctuality()).thenReturn(
				new CloseOutHistory.Punctuality(due, due.plusSeconds(63 * 60), false));

		assertThat((java.util.Map<String, Object>) indicator.health().getDetails().get("closeOutDue"))
				.containsEntry("overdue", false)
				.containsEntry("startedAt", "2026-09-07T00:33:00Z")
				.containsEntry("startedLateBySeconds", 3780L);
	}

	/**
	 * A suspend that overlapped play is published, with what it cost.
	 *
	 * <p>The host cannot see this any other way: during the gap the machine was
	 * asleep, launchd did not fire and nothing inside paddock was running. The
	 * report is therefore always retrospective, and this payload is where the
	 * first heartbeat pass after the wake reads it (#291).
	 */
	@Test
	void publishesTheLastGapThatOverlappedPlay() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(census.summarise()).thenReturn(busy());
		when(gaps.lastGapDuringPlay()).thenReturn(java.util.Optional.of(new GapHistory.GapInPlay(
				Instant.parse("2026-09-17T19:42:11Z"), "SLEEP", 322, 12, 3)));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).extracting("gapInPlay")
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				// endedAt is the report's identity, and it is the GAP's timestamp
				// rather than the time it was read: the host de-dupes on it, so a
				// five-minute poll announces one suspend once rather than
				// repeatedly for as long as it stays the most recent one.
				.containsEntry("endedAt", "2026-09-17T19:42:11Z")
				.containsEntry("cause", "SLEEP")
				.containsEntry("seconds", 322L)
				.containsEntry("markets", 12)
				.containsEntry("live", 3);
	}

	/**
	 * A gap that overlapped play NEVER turns the verdict over.
	 *
	 * <p>The gate that must not rot, and it is sharper here than for the backlog
	 * beside it: the gap is over. The recorder is back, and this indicator's red
	 * drives the heartbeat's automatic restart — so a finding about an interval
	 * that has already closed would answer a suspend by killing the recorder
	 * that survived it.
	 */
	@Test
	void aGapThatOverlappedPlayIsAFactAndNotAVerdict() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(census.summarise()).thenReturn(busy());
		when(gaps.lastGapDuringPlay()).thenReturn(java.util.Optional.of(new GapHistory.GapInPlay(
				Instant.parse("2026-09-17T19:42:11Z"), "SLEEP", 7200, 40, 18)));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).doesNotContainKey("reason");
	}

	/**
	 * No recent gap publishes no block at all.
	 *
	 * <p>Absent rather than zeroed, for the same reason the summary block is:
	 * the host announces what it finds, and a block of zeroes is a claim that a
	 * gap happened and cost nothing.
	 */
	@Test
	void noRecentGapPublishesNothing() {
		when(recorder.state()).thenReturn(RecorderState.RECORDING);
		when(census.summarise()).thenReturn(busy());

		assertThat(indicator.health().getDetails()).doesNotContainKey("gapInPlay");
	}
}
