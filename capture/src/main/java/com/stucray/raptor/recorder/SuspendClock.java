package com.stucray.raptor.recorder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Whether this machine was suspended, asked by both things that need to know.
 *
 * <p>The measurement is one subtraction: {@code Clock} advances while a machine
 * is asleep and {@code nanoTime} does not, so their disagreement over an
 * interval <em>is</em> the suspend. Scheduling jitter and NTP slew land in the
 * same difference, which is what the tolerance is for.
 *
 * <p><b>It lives here, and not in {@link StreamWatchdog}, because the cause of a
 * gap must not depend on which thread noticed it first (#264.)</b> A suspend is
 * torn down by whichever detector gets there — the watchdog's clock check, or
 * {@code StreamReadLoop}'s socket finally timing out — and on a long suspend the
 * socket usually wins, because the read timeout expires while the machine is
 * away and fires the instant it resumes. The supervisor then wrote
 * {@code DISCONNECT} and a later watchdog tick found no session to attach its
 * measurement to and discarded it. That put "Stuart shut the lid" into the same
 * population as "Betfair dropped the stream", with no way to separate them
 * afterwards — and biased, because the longer the suspend the likelier the
 * socket is first, so the worst holes were the likeliest to be mislabelled.
 *
 * <p>The reason this is a shared measurement rather than a correction applied
 * afterwards: {@code raw} is append-only. A gap row cannot be relabelled once
 * written, so the cause has to be decided before the insert.
 *
 * <p><b>Two answers, and both are needed.</b> {@link #sample()} is the
 * watchdog's periodic tick, which moves the baseline on. {@link #straddling}
 * measures against the baseline <em>without</em> moving it, and falls back to
 * the last suspend this process observed. The first covers a suspend the
 * watchdog has not ticked over yet, which is the ordinary socket-first case; the
 * second covers the few milliseconds in which a tick can land between the read
 * loop ending and the gap being written, where the live measurement has already
 * been consumed.
 */
@Component
class SuspendClock {

	private final Clock clock;
	private final LongSupplier nanoTime;
	private final Duration tolerance;

	private volatile Instant baselineWall;
	private volatile long baselineNanos;

	/**
	 * The last suspend observed, kept indefinitely.
	 *
	 * <p>Not expired on a timer: {@link #straddling} asks whether it overlaps the
	 * gap being written, which is a far tighter test than any age limit, and an
	 * old suspend cannot overlap a recent gap. Keeping it means a suspend
	 * measured by a tick that had nothing to attach it to is still available to
	 * the row that gets written a moment later.
	 */
	private volatile @Nullable Suspend last;

	/** Two constructors, so the annotation says which one Spring builds. */
	@Autowired
	SuspendClock(RecorderProperties properties, Clock clock) {
		this(properties, clock, System::nanoTime);
	}

	/** Both clocks injected, so a test can suspend a machine without one. */
	SuspendClock(RecorderProperties properties, Clock clock, LongSupplier nanoTime) {
		this.clock = clock;
		this.nanoTime = nanoTime;
		this.tolerance = properties.watchdog().clockJumpTolerance();
		this.baselineWall = clock.instant();
		this.baselineNanos = nanoTime.getAsLong();
	}

	/**
	 * Measure since the last sample, and move the baseline on.
	 *
	 * <p>Called by the watchdog on every tick, whether or not there is a session
	 * to tear down — the baseline has to advance regardless, or the next
	 * measurement reports this interval again.
	 */
	@Nullable Suspend sample() {
		Instant wall = clock.instant();
		long nanos = nanoTime.getAsLong();
		Suspend suspend = measure(wall, nanos);
		this.baselineWall = wall;
		this.baselineNanos = nanos;
		if (suspend != null) {
			this.last = suspend;
		}
		return suspend;
	}

	/**
	 * A suspend overlapping {@code [from, to]}, or null if the machine was awake
	 * for all of it.
	 *
	 * <p>Overlap and not recency, which is the whole precision of this: on
	 * 2026-09-12 the suspend of 08:17:37–08:28:00 was followed at 08:28:20 by the
	 * stale pre-suspend socket finally timing out. That second gap is a genuine
	 * {@code DISCONNECT} and must stay one; it does not overlap the suspend, and
	 * nothing weaker than an overlap test tells the two apart.
	 */
	@Nullable Suspend straddling(Instant from, Instant to) {
		Suspend unsampled = measure(clock.instant(), nanoTime.getAsLong());
		if (unsampled != null && unsampled.overlaps(from, to)) {
			return unsampled;
		}
		Suspend retained = this.last;
		return retained != null && retained.overlaps(from, to) ? retained : null;
	}

	private @Nullable Suspend measure(Instant wall, long nanos) {
		Duration byWallClock = Duration.between(this.baselineWall, wall);
		Duration byMonotonic = Duration.ofNanos(nanos - this.baselineNanos);
		Duration difference = byWallClock.minus(byMonotonic);
		return difference.compareTo(tolerance) > 0
				? new Suspend(wall.minus(difference), wall)
				: null;
	}

	/**
	 * When the machine went away and when it came back.
	 *
	 * @param from the wall instant the suspend began, derived by subtracting the
	 *     measured difference from the instant it was noticed — the machine
	 *     cannot report this itself, since nothing was running to record it
	 * @param to when the difference was observed, which is at most one watchdog
	 *     interval after the machine resumed
	 */
	record Suspend(Instant from, Instant to) {

		Duration duration() {
			return Duration.between(from, to);
		}

		/** Half-open on both sides: touching at an instant is not overlapping. */
		boolean overlaps(Instant start, Instant end) {
			return from.isBefore(end) && to.isAfter(start);
		}
	}
}
