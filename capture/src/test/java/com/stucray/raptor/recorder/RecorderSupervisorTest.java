package com.stucray.raptor.recorder;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.unit.DataSize;

/**
 * What the supervisor has to earn back from the LaunchAgent it replaces.
 *
 * <p>The agent gave three things for free: only one recorder ever ran, a run had
 * a definite beginning and end, and a night that died was visible as a job that
 * exited. Each is a property asserted here — the lease, the session per stream,
 * and the gap row — because none of them survives the move to a resident service
 * on its own.
 */
class RecorderSupervisorTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T18:00:00Z"),
			ZoneOffset.UTC);

	private final CaptureGaps gaps = mock(CaptureGaps.class);
	private final CaptureLease lease = mock(CaptureLease.class);
	/**
	 * A machine that can be suspended without one.
	 *
	 * <p>Its own clocks, deliberately separate from {@link #CLOCK}: what decides a
	 * suspend is the DISAGREEMENT between a wall clock and a monotonic one, and a
	 * fixed clock cannot express that. Left alone by every test but the suspend
	 * ones, where it reads as a machine that was awake throughout.
	 */
	private final MutableClock suspendWall = new MutableClock(Instant.parse("2026-09-02T17:50:00Z"));
	private final AtomicLong suspendNanos = new AtomicLong();
	private final SuspendClock suspends = new SuspendClock(
			properties(true, Duration.ofMillis(10), Duration.ofMillis(40)),
			suspendWall, suspendNanos::get);
	private final CaptureSessions sessions = mock(CaptureSessions.class);
	private final RecordingWriter writer = new RecordingWriter();
	private final AtomicLong nextSessionId = new AtomicLong(1);

	private @Nullable RecorderSupervisor supervisor;

	@AfterEach
	void stopSupervisor() {
		if (supervisor != null) {
			supervisor.stop();
		}
	}

	/**
	 * The honest state of this application today.
	 *
	 * <p>The live source belongs to S6. Until then the supervisor must not take
	 * the lease: an instance holding single-writer while capturing nothing would
	 * lock out the one that could.
	 */
	@Test
	void takesNoLeaseWhenThereIsNoSourceToRecordFrom() {
		supervisor = supervisor(null, true);

		supervisor.start();

		assertThat(supervisor.state()).isEqualTo(RecorderState.NO_SOURCE);
		verifyNoInteractions(lease);
	}

	@Test
	void doesNothingAtAllWhenDisabled() {
		supervisor = supervisor(factory(() -> endless()), false);

		supervisor.start();

		assertThat(supervisor.state()).isEqualTo(RecorderState.DISABLED);
		verifyNoInteractions(lease);
		verifyNoInteractions(sessions);
	}

	/**
	 * A killed recorder cannot write its own ending, so the next one writes it.
	 *
	 * <p>Only ever with the lease in hand: it is a session-level advisory lock,
	 * so PostgreSQL released the dead holder's within seconds, and holding it is
	 * the proof that every still-open row belongs to a process that is gone
	 * (#129). Two rows said "in progress" for two days before this existed.
	 */
	@Test
	void closesSessionsTheLastRecorderLeftOpenBeforeStartingItsOwn() {
		when(lease.acquire()).thenReturn(true);
		when(sessions.abandonOpenSessions(any())).thenReturn(List.of(7L, 8L));
		supervisor = supervisor(factory(() -> endless()), true);

		supervisor.start();

		InOrder order = Mockito.inOrder(lease, sessions);
		order.verify(lease).acquire();
		// After the lease and before anything of this instance's own: a
		// reconciliation that ran first would be closing rows it cannot prove are
		// dead, and one that ran later would close its own.
		order.verify(sessions).abandonOpenSessions(any());
	}

	/** A standing-by instance proves nothing about the writer, so it closes nothing. */
	@Test
	void aStandbyInstanceReconcilesNothing() {
		when(lease.acquire()).thenReturn(false);
		supervisor = supervisor(factory(() -> endless()), true);

		supervisor.start();

		verify(sessions, never()).abandonOpenSessions(any());
	}

	/**
	 * A second instance is inert, not fatal.
	 *
	 * <p>Refusing to boot would make a stray development JVM an outage, and
	 * recording anyway would be the corruption single-writer exists to prevent.
	 */
	@Test
	void standsByWhenAnotherInstanceHoldsTheLease() {
		when(lease.acquire()).thenReturn(false);
		supervisor = supervisor(factory(() -> endless()), true);

		supervisor.start();

		assertThat(supervisor.state()).isEqualTo(RecorderState.STANDBY);
		verifyNoInteractions(sessions);
	}

	/**
	 * A stream that ends is rebuilt, and the hole it left is written down.
	 *
	 * <p>Betfair closes long-lived connections on its own schedule, so a
	 * disconnect is routine rather than exceptional. What must never be routine is
	 * losing the record of one: a gap nobody wrote down is a gap somebody has to
	 * infer later from timestamp arithmetic over the messages that did arrive,
	 * which is precisely the apparatus this design replaces.
	 */
	@Test
	@Timeout(30)
	void reconnectsWhenAStreamEndsAndRecordsTheGap() {
		when(lease.acquire()).thenReturn(true);
		AtomicInteger opened = new AtomicInteger();
		supervisor = supervisor(factory(() -> {
			opened.incrementAndGet();
			return finite(3);
		}), true);

		supervisor.start();

		Awaitility.await().atMost(Duration.ofSeconds(10))
				.untilAsserted(() -> assertThat(opened.get()).isGreaterThanOrEqualTo(2));
		verify(gaps, Mockito.atLeastOnce()).record(anyLong(), any(), any(),
				eq(GapCause.DISCONNECT), any());
		// Every stream is its own session: two connections are two rows, which is
		// what makes the space between them answerable.
		verify(sessions, Mockito.atLeast(2)).begin(eq(CaptureOrigin.RESIDENT), any(), any());
	}

	/**
	 * A suspend is named SLEEP even when the socket, not the watchdog, ends the
	 * stream — #264.
	 *
	 * <p>This is the case that had never been asserted, and the reason it had not
	 * is instructive: {@code StreamWatchdogTest} drives {@code check()} directly,
	 * which proves the clock check names a suspend correctly and proves nothing
	 * about the ordering that actually decides the outcome. On a long suspend the
	 * read timeout expires while the machine is away and fires the instant it
	 * resumes, so the socket usually gets there first. The supervisor then wrote
	 * {@code DISCONNECT} and a later watchdog tick found no session to attach its
	 * measurement to.
	 *
	 * <p>So the stream here ends on its own, with no {@code reconnect()} call
	 * anywhere — exactly the path the read loop takes — and the machine was away
	 * across the interval. The cause must come from the clock.
	 */
	@Test
	@Timeout(30)
	void namesASuspendSleepEvenWhenTheSocketNoticedFirst() {
		when(lease.acquire()).thenReturn(true);
		// Fifteen minutes of wall clock, ten seconds of it real: the machine was
		// away for the rest, across the instant the gap is written at.
		suspendWall.advance(Duration.ofMinutes(15));
		suspendNanos.addAndGet(Duration.ofSeconds(10).toNanos());
		supervisor = supervisor(factory(() -> finite(3)), true);

		supervisor.start();

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				verify(gaps, Mockito.atLeastOnce())
						.record(anyLong(), any(), any(), eq(GapCause.SLEEP), any()));
		// And never as the upstream fault it is not. This is the whole value: a
		// DISCONNECT population mixed with lid closures cannot be used to measure
		// anything about Betfair.
		verify(gaps, never()).record(anyLong(), any(), any(), eq(GapCause.DISCONNECT), any());
	}

	/**
	 * The clamshell close of 2026-09-17, which #290 recorded as SILENCE — #293.
	 *
	 * <p>The exact ordering from the log, and it is the ordering that made the
	 * previous fix insufficient: the watchdog names the suspend correctly, and the
	 * tick ten seconds later overwrites it with SILENCE because the stream has of
	 * course delivered nothing while the machine was off. Both requests land on
	 * one closing recording, so only the second reached the ledger.
	 *
	 * <p>Not a race between equals: any suspend longer than the silence timeout
	 * leaves the stream silent for its whole duration, so the following tick
	 * always relabels. The 2026-09-12 close came out right only because its
	 * teardown finished inside one watchdog interval.
	 */
	@Test
	@Timeout(30)
	void aLaterSilenceDoesNotOverwriteASuspendAlreadyNamed() {
		when(lease.acquire()).thenReturn(true);
		supervisor = supervisor(factory(() -> endless()), true);
		supervisor.start();
		awaitRecording();
		// The machine was away across the whole window the gap will cover.
		suspendWall.advance(Duration.ofMinutes(6));
		suspendNanos.addAndGet(Duration.ofSeconds(10).toNanos());
		Instant lastFrame = Instant.parse("2026-09-02T17:54:00Z");

		requireNonNull(supervisor).reconnect(GapCause.SLEEP, lastFrame.plusSeconds(17));
		requireNonNull(supervisor).reconnect(GapCause.SILENCE, lastFrame);

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				verify(gaps).record(anyLong(), eq(lastFrame), any(), eq(GapCause.SLEEP), any()));
		verify(gaps, never()).record(anyLong(), any(), any(), eq(GapCause.SILENCE), any());
	}

	/**
	 * And the same verdict when only the silence detector ever fired.
	 *
	 * <p>The watchdog can miss the jump entirely — a tick that lands while the
	 * supervisor is between sessions returns without recording anything — and the
	 * gap must still be named from the clock rather than from the one detector
	 * that spoke.
	 */
	@Test
	@Timeout(30)
	void aSilenceThatStraddlesASuspendIsNamedSleep() {
		when(lease.acquire()).thenReturn(true);
		supervisor = supervisor(factory(() -> endless()), true);
		supervisor.start();
		awaitRecording();
		suspendWall.advance(Duration.ofMinutes(6));
		suspendNanos.addAndGet(Duration.ofSeconds(10).toNanos());

		requireNonNull(supervisor).reconnect(GapCause.SILENCE,
				Instant.parse("2026-09-02T17:54:00Z"));

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				verify(gaps).record(anyLong(), any(), any(), eq(GapCause.SLEEP), any()));
	}

	/**
	 * But a quiet book on a machine that never slept is still SILENCE.
	 *
	 * <p>The guard on the guard: a fix that named everything SLEEP would pass the
	 * two cases above and destroy the distinction they exist to protect.
	 */
	@Test
	@Timeout(30)
	void aSilenceWithNoSuspendBehindItStaysSilence() {
		when(lease.acquire()).thenReturn(true);
		supervisor = supervisor(factory(() -> endless()), true);
		supervisor.start();
		awaitRecording();

		requireNonNull(supervisor).reconnect(GapCause.SILENCE,
				Instant.parse("2026-09-02T17:54:00Z"));

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				verify(gaps).record(anyLong(), any(), any(), eq(GapCause.SILENCE), any()));
		verify(gaps, never()).record(anyLong(), any(), any(), eq(GapCause.SLEEP), any());
	}

	/**
	 * A suspend that is over before the gap starts is somebody else's fault.
	 *
	 * <p>The 2026-09-12 evidence has both in four minutes: a suspend of
	 * 08:17:37–08:28:00, then at 08:28:20 the stale pre-suspend socket finally
	 * timing out. The second is a genuine {@code DISCONNECT} and stays one — which
	 * is why the question asked of {@link SuspendClock} is whether a suspend
	 * OVERLAPS the gap, not whether one happened recently.
	 */
	@Test
	@Timeout(30)
	void doesNotBlameASuspendThatHadAlreadyEnded() {
		when(lease.acquire()).thenReturn(true);
		// Measured and retained, then the baseline moved past it, so the machine
		// reads as awake by the time the stream ends.
		suspendWall.advance(Duration.ofMinutes(5));
		suspends.sample();
		suspendWall.advance(Duration.ofMinutes(20));
		suspendNanos.addAndGet(Duration.ofMinutes(20).toNanos());
		suspends.sample();
		supervisor = supervisor(factory(() -> finite(3)), true);

		supervisor.start();

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				verify(gaps, Mockito.atLeastOnce())
						.record(anyLong(), any(), any(), eq(GapCause.DISCONNECT), any()));
		verify(gaps, never()).record(anyLong(), any(), any(), eq(GapCause.SLEEP), any());
	}

	/**
	 * An orderly stop is not a gap.
	 *
	 * <p>The session's {@code ended_at} already says exactly when recording
	 * stopped. A row here would be noise in the one ledger whose value is that
	 * every row in it means something happened that nobody chose.
	 */
	@Test
	@Timeout(30)
	void anOrderlyStopInventsNoGap() {
		when(lease.acquire()).thenReturn(true);
		supervisor = supervisor(factory(() -> endless()), true);
		supervisor.start();
		awaitRecording();

		supervisor.stop();
		supervisor = null;

		verify(gaps, never()).record(anyLong(), any(), any(), any(), any());
		verify(lease).close();
	}

	/** What the watchdog decided is what the ledger says. */
	@Test
	@Timeout(30)
	void recordsTheCauseAndInstantTheWatchdogSupplies() {
		when(lease.acquire()).thenReturn(true);
		supervisor = supervisor(factory(() -> endless()), true);
		supervisor.start();
		awaitRecording();
		Instant sleptFrom = Instant.parse("2026-09-02T17:30:00Z");

		supervisor.reconnect(GapCause.SLEEP, sleptFrom);

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				verify(gaps).record(anyLong(), eq(sleptFrom), any(), eq(GapCause.SLEEP), any()));
	}

	/**
	 * Waiting for a fixture is not reconnecting, and says so.
	 *
	 * <p>The supervisor is blocked inside {@code open()} for both, so the word it
	 * set on the way in — RECONNECTING — covered a quiet Tuesday and a Betfair
	 * outage alike (#144). The factory is the only thing that can tell them
	 * apart; this asserts that the supervisor asks, and that it stops saying IDLE
	 * the moment a stream is actually running.
	 */
	@Test
	@Timeout(30)
	void saysItIsIdleWhileTheFactoryWaitsForAFixtureToEnterScope() {
		when(lease.acquire()).thenReturn(true);
		AtomicReference<@Nullable Instant> awaiting =
				new AtomicReference<>(Instant.parse("2026-09-02T03:00:00Z"));
		CountDownLatch release = new CountDownLatch(1);
		supervisor = supervisor(waitingFactory(awaiting, release), true);

		supervisor.start();

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				assertThat(requireNonNull(supervisor).state()).isEqualTo(RecorderState.IDLE));
		// And the clock against it is the moment the wait began, not the moment the
		// supervisor last changed a field — the whole point is to date the state.
		assertThat(requireNonNull(supervisor).stateSince())
				.isEqualTo(Instant.parse("2026-09-02T03:00:00Z"));

		awaiting.set(null);
		release.countDown();

		awaitRecording();
	}

	/**
	 * Sessions that end having written nothing are counted, in a row.
	 *
	 * <p>The wiring half of #171. The verdict lives in
	 * {@code CaptureCoverageHealthIndicator} and its tests drive a mocked
	 * {@code RecorderStatus} — which proves the judgement and nothing about
	 * whether anything ever supplies the number. That gap is exactly how the
	 * original bug survived: every existing signal was correct in isolation.
	 *
	 * <p>A source that ends immediately having emitted nothing is the shape a
	 * refused subscription produced sixteen times in four minutes.
	 */
	@Test
	@Timeout(30)
	void countsCaptureSessionsThatEndWithoutWritingAnything() {
		when(lease.acquire()).thenReturn(true);
		supervisor = supervisor(factory(() -> finite(0)), true);

		supervisor.start();

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			RecorderSupervisor current = supervisor;
			assertThat(current).isNotNull();
			assertThat(current.consecutiveFailedAttempts()).isGreaterThanOrEqualTo(3);
		});
	}

	/**
	 * Anything written at all clears the count.
	 *
	 * <p>Reset rather than decay, because the question the verdict asks is "is
	 * it failing now". Without this a recorder that had a bad minute in the
	 * morning would still be carrying it at kickoff.
	 */
	@Test
	@Timeout(30)
	void aSessionThatWritesSomethingClearsTheCount() {
		when(lease.acquire()).thenReturn(true);
		AtomicInteger opened = new AtomicInteger();
		// Two dead streams, then a working one — the shape of an outage ending.
		supervisor = supervisor(factory(() ->
				opened.getAndIncrement() < 2 ? finite(0) : finite(3)), true);

		supervisor.start();

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			RecorderSupervisor current = supervisor;
			assertThat(current).isNotNull();
			assertThat(current.consecutiveFailedAttempts()).isZero();
		});
	}

	/**
	 * ...and clears it while the writing session is still OPEN (#13).
	 *
	 * <p>The test above ends its good session, which is the one moment the old
	 * reset ran, so it could not see this. On 2026-09-25 four dead attempts were
	 * followed by a healthy session that recorded for hours, and capture health
	 * read OUT_OF_SERVICE for all of it — with the heartbeat's restart of a
	 * healthy recorder one cooldown away. A session that never ends is the shape
	 * of a match in progress.
	 */
	@Test
	@Timeout(30)
	void aSessionStillRecordingClearsTheCountBeforeItEnds() {
		when(lease.acquire()).thenReturn(true);
		AtomicInteger opened = new AtomicInteger();
		supervisor = supervisor(factory(() ->
				opened.getAndIncrement() < 2 ? finite(0) : endless()), true);

		supervisor.start();

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			RecorderSupervisor current = supervisor;
			assertThat(current).isNotNull();
			Recording recording = current.current();
			assertThat(recording).isNotNull();
			assertThat(recording.written()).isPositive();
			assertThat(current.consecutiveFailedAttempts()).isZero();
		});
	}


	/**
	 * The arithmetic, at its exact values.
	 *
	 * <p>Asserted directly rather than inferred from how long a test took: a
	 * backoff measured by a stopwatch is a backoff whose numbers nobody can read
	 * off the failure. The behaviour that this is *wired in* is the test below.
	 */
	@Test
	void backsOffGeometricallyAndStopsAtTheCeiling() {
		Duration base = Duration.ofSeconds(5);
		Duration max = Duration.ofSeconds(60);

		// Nothing yet, and one failure, are both the full-speed case: the delay
		// exists for a match in progress and must not grow before it has to.
		assertThat(RecorderSupervisor.backoff(base, max, 0)).isEqualTo(base);
		assertThat(RecorderSupervisor.backoff(base, max, 1)).isEqualTo(base);
		assertThat(RecorderSupervisor.backoff(base, max, 2)).isEqualTo(Duration.ofSeconds(10));
		assertThat(RecorderSupervisor.backoff(base, max, 3)).isEqualTo(Duration.ofSeconds(20));
		assertThat(RecorderSupervisor.backoff(base, max, 4)).isEqualTo(Duration.ofSeconds(40));
		assertThat(RecorderSupervisor.backoff(base, max, 5)).isEqualTo(max);
		// A recorder left failing over a long weekend: the shift is bounded, so it
		// cannot overflow its way back round to a busy wait.
		assertThat(RecorderSupervisor.backoff(base, max, 4000)).isEqualTo(max);
	}

	/**
	 * A stream that cannot be opened at all counts, and slows down.
	 *
	 * <p>#171's counter only ever saw sessions, so it was blind to every failure
	 * that never reached one — a revoked app key, an invalid session token,
	 * upstream maintenance. Those are precisely the deterministic refusals the
	 * backoff exists for, so a count that cannot see them cannot pace them.
	 */
	@Test
	@Timeout(30)
	void countsAConnectThatNeverBecameASession() {
		when(lease.acquire()).thenReturn(true);
		supervisor = supervisor(factory(() -> {
			throw new IOException("refused");
		}), true);

		supervisor.start();

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			RecorderSupervisor current = supervisor;
			assertThat(current).isNotNull();
			assertThat(current.consecutiveFailedAttempts()).isGreaterThanOrEqualTo(3);
		});
		verifyNoInteractions(gaps);
	}

	/**
	 * The wiring: a hopeless source really is retried more slowly over time.
	 *
	 * <p>Asserted as a ceiling on attempts rather than as elapsed time, so a slow
	 * machine can only make this pass more easily — the failure direction is a
	 * recorder retrying too fast, which is the thing being prevented. At 20 ms
	 * base and a 100 ms cap the schedule is 20, 40, 80, 100, 100… so a second
	 * admits roughly a dozen attempts, where the flat delay admits fifty.
	 */
	@Test
	@Timeout(30)
	void aHopelessSourceIsRetriedMoreSlowlyTheLongerItStaysHopeless() {
		when(lease.acquire()).thenReturn(true);
		AtomicInteger opened = new AtomicInteger();
		supervisor = supervisor(factory(() -> {
			opened.incrementAndGet();
			throw new IOException("refused identically, every time");
		}), true, Duration.ofMillis(20), Duration.ofMillis(100));

		supervisor.start();

		Awaitility.await().atMost(Duration.ofSeconds(10))
				.until(() -> opened.get() >= 6);
		int afterBackoff = opened.get();
		Awaitility.await().pollDelay(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5))
				.until(() -> true);

		// Six hundred milliseconds at the 100 ms ceiling is at most six or seven
		// more attempts; at the flat 20 ms it would be thirty.
		assertThat(opened.get() - afterBackoff).isLessThan(15);
	}

	private void awaitRecording() {
		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				assertThat(supervisor).isNotNull()
						.extracting(RecorderSupervisor::state)
						.isEqualTo(RecorderState.RECORDING));
	}

	/** A clock a test can suspend a machine with. */
	private static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	private RecorderSupervisor supervisor(@Nullable StreamSourceFactory factory, boolean enabled) {
		return supervisor(factory, enabled, Duration.ofMillis(10), Duration.ofMillis(40));
	}

	private RecorderSupervisor supervisor(@Nullable StreamSourceFactory factory, boolean enabled,
			Duration reconnectDelay, Duration reconnectDelayMax) {
		when(sessions.begin(any(), any(), any())).thenAnswer(call -> nextSessionId.getAndIncrement());
		RecorderProperties properties = properties(enabled, reconnectDelay, reconnectDelayMax);
		RecorderPipeline pipeline = new RecorderPipeline(writer, new NoOpTransactions(),
				new CollectingSpillSink(), new CollectingQuarantine(), properties, sessions, CLOCK,
				new SimpleMeterRegistry());
		when(sessions.abandonOpenSessions(any())).thenReturn(List.of());
		return new RecorderSupervisor(provider(factory), pipeline, sessions, lease, gaps, suspends,
				properties, CLOCK);
	}

	// Milliseconds, not seconds: the delay exists so a source that fails instantly
	// cannot become a busy wait, and a test should not pay five seconds to
	// observe that it is honoured.
	private static RecorderProperties properties(boolean enabled, Duration reconnectDelay,
			Duration reconnectDelayMax) {
		return new RecorderProperties(enabled, 10_000, 64, Duration.ofMillis(20),
				reconnectDelay, reconnectDelayMax, 1L, 3, Duration.ofMinutes(10),
				new RecorderProperties.Spill(false, java.nio.file.Path.of("target/unused"),
						DataSize.ofGigabytes(1), Duration.ofMinutes(1), 200,
						Duration.ofMinutes(15), 3),
				new RecorderProperties.Watchdog(Duration.ofSeconds(10), Duration.ofSeconds(30),
						Duration.ofSeconds(5)));
	}

	private static StreamSourceFactory factory(Opener opener) {
		return new StreamSourceFactory() {
			@Override
			public String describe() {
				return "test";
			}

			@Override
			public StreamSource open() throws IOException {
				return opener.open();
			}
		};
	}

	/**
	 * A factory that waits, the way the live one does with an empty horizon: it
	 * reports when the wait began, and hands back a stream only once released.
	 */
	private static StreamSourceFactory waitingFactory(
			AtomicReference<@Nullable Instant> awaiting, CountDownLatch release) {
		return new StreamSourceFactory() {
			@Override
			public String describe() {
				return "waiting";
			}

			@Override
			public @Nullable Instant awaitingSince() {
				return awaiting.get();
			}

			@Override
			public StreamSource open() throws IOException {
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("interrupted while waiting for a fixture", e);
				}
				return endless();
			}
		};
	}

	@FunctionalInterface
	private interface Opener {
		StreamSource open() throws IOException;
	}

	/** A source that ends, the way a dropped connection does. */
	private static StreamSource finite(int frames) {
		return new FakeSource(frames);
	}

	/** A source that never ends, the way a healthy one does not. */
	private static StreamSource endless() {
		return new FakeSource(Integer.MAX_VALUE);
	}

	private static final class FakeSource implements StreamSource {

		private final int frames;
		private int emitted;

		FakeSource(int frames) {
			this.frames = frames;
		}

		@Override
		public String describe() {
			return "fake(" + frames + ")";
		}

		@Override
		public @Nullable StreamFrame next() {
			if (emitted >= frames) {
				return null;
			}
			emitted++;
			// Paced, so an endless source does not spin a core while a test watches
			// the state machine around it.
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
			return new StreamFrame("{\"pt\":1787490204789,\"recv_ms\":1787490204700,"
					+ "\"mc\":{\"id\":\"1.234\",\"rc\":[]}}", CLOCK.instant());
		}

		@Override
		public void close() {}
	}

	private static ObjectProvider<StreamSourceFactory> provider(
			@Nullable StreamSourceFactory factory) {
		return new ObjectProvider<>() {
			@Override
			public StreamSourceFactory getObject() {
				if (factory == null) {
					throw new IllegalStateException("no factory");
				}
				return factory;
			}

			@Override
			public StreamSourceFactory getObject(Object... args) {
				return getObject();
			}

			@Override
			public @Nullable StreamSourceFactory getIfAvailable() {
				return factory;
			}

			@Override
			public @Nullable StreamSourceFactory getIfUnique() {
				return factory;
			}

			@Override
			public Iterator<StreamSourceFactory> iterator() {
				return (factory == null ? List.<StreamSourceFactory>of() : List.of(factory))
						.iterator();
			}
		};
	}
}
