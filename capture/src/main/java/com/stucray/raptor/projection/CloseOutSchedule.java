package com.stucray.raptor.projection;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * When the close-out is due, and when it counts as late (#334).
 *
 * <p><b>The app no longer fires the close-out, but it still has to know when it
 * should have run</b>, because the trigger is now outside it — launchd, on the
 * host — and an absence is the only thing a trigger that stopped leaves behind.
 * The 26-hour STALE-CLOSE-OUT on the heartbeat catches a trigger that has died;
 * this catches one that fires late, or on the wrong clock.
 *
 * <p><b>The wrong clock is a real case.</b> launchd's {@code StartCalendarInterval}
 * is LOCAL time, and the plist says 06:30 because the host runs at UTC+7. Move the
 * machine to another zone and the agent fires at a different UTC hour without
 * anything failing — this is what says so.
 *
 * <p>Pure arithmetic over a UTC due time, so the tests need no clock and no
 * database.
 */
@Component
class CloseOutSchedule {

	private final LocalTime due;
	private final Duration overdueAfter;

	CloseOutSchedule(@Value("${raptor.close-out.due:23:30}") LocalTime due,
			@Value("${raptor.close-out.overdue-after:PT1H}") Duration overdueAfter) {
		this.due = due;
		this.overdueAfter = overdueAfter;
	}

	/** The most recent due time at or before {@code now}. */
	Instant lastDue(Instant now) {
		Instant today = now.atOffset(ZoneOffset.UTC).toLocalDate().atTime(due)
				.toInstant(ZoneOffset.UTC);
		return today.isAfter(now) ? today.minus(Duration.ofDays(1)) : today;
	}

	/**
	 * @param firstStartSinceDue the earliest run that started at or after
	 *     {@link #lastDue}, if any has
	 */
	CloseOutHistory.Punctuality punctuality(Instant now, @Nullable Instant firstStartSinceDue) {
		Instant dueAt = lastDue(now);
		boolean overdue = firstStartSinceDue == null
				&& now.isAfter(dueAt.plus(overdueAfter));
		return new CloseOutHistory.Punctuality(dueAt, firstStartSinceDue, overdue);
	}
}
