package com.stucray.raptor.projection;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * When the close-out is due, and when it counts as late (#334) or stale (#79).
 *
 * <p><b>The app no longer fires the close-out, but it still has to know when it
 * should have run</b>, because the trigger is now outside it — launchd, on the
 * host — and an absence is the only thing a trigger that stopped leaves behind.
 *
 * <p><b>Due on chosen UTC weekdays, not every night (#79).</b> football-data.co.uk
 * asks for automated downloads "at most … twice per week", and its own
 * {@code Last-Modified} headers show when the files change: weekend results on
 * Sunday night or Monday evening, midweek ones Tuesday to Thursday evening. So
 * Monday and Thursday at 23:30Z collect each batch soon after it lands. launchd
 * still fires every night; {@link NightlyCloseOut} asks {@link #lastDue} whether
 * that firing has work to do, so a run missed to sleep or a football-data outage
 * is retried the next night rather than at the next scheduled day.
 *
 * <p><b>Two verdicts, about two different absences.</b> {@code overdue} is about a
 * START: the trigger is late or on the wrong clock. launchd's
 * {@code StartCalendarInterval} is LOCAL time, so moving the machine to another
 * zone shifts the UTC firing without anything failing, and this is what says so.
 * {@code stale} is about a FINISH, and replaces the heartbeat's fixed 26 hours,
 * which a twice-weekly schedule would trip on every day off.
 *
 * <p>Pure arithmetic over a UTC due time, so the tests need no clock and no
 * database.
 */
@Component
class CloseOutSchedule {

	private final Set<DayOfWeek> days;
	private final LocalTime due;
	private final Duration overdueAfter;
	private final Duration staleAfter;

	CloseOutSchedule(@Value("${raptor.close-out.days:MONDAY,THURSDAY}") String days,
			@Value("${raptor.close-out.due:23:30}") LocalTime due,
			@Value("${raptor.close-out.overdue-after:PT1H}") Duration overdueAfter,
			@Value("${raptor.close-out.stale-after:PT2H}") Duration staleAfter) {
		this.days = parse(days);
		this.due = due;
		this.overdueAfter = overdueAfter;
		this.staleAfter = staleAfter;
	}

	/**
	 * Refuse an empty or misspelt setting at startup. A schedule with no days would
	 * otherwise never be due, which reads exactly like a run that is never needed.
	 */
	private static Set<DayOfWeek> parse(String days) {
		Set<DayOfWeek> parsed = EnumSet.noneOf(DayOfWeek.class);
		for (String name : days.split(",")) {
			String trimmed = name.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			try {
				parsed.add(DayOfWeek.valueOf(trimmed.toUpperCase(Locale.ROOT)));
			}
			catch (IllegalArgumentException e) {
				throw new IllegalArgumentException(
						"raptor.close-out.days names a day that is not one: " + trimmed, e);
			}
		}
		if (parsed.isEmpty()) {
			throw new IllegalArgumentException(
					"raptor.close-out.days names no days; the close-out would never be due");
		}
		return parsed;
	}

	/** The most recent due time at or before {@code now}, on a scheduled UTC weekday. */
	Instant lastDue(Instant now) {
		LocalDate date = now.atOffset(ZoneOffset.UTC).toLocalDate();
		// Eight steps reach back over a whole week whatever today is, and parse()
		// guarantees at least one day, so the loop always returns.
		for (int back = 0; back <= 7; back++) {
			LocalDate candidate = date.minusDays(back);
			Instant at = candidate.atTime(due).toInstant(ZoneOffset.UTC);
			if (days.contains(candidate.getDayOfWeek()) && !at.isAfter(now)) {
				return at;
			}
		}
		throw new IllegalStateException("no scheduled day in the last week: " + days);
	}

	/**
	 * @param firstStartSinceDue the earliest run that started at or after
	 *     {@link #lastDue}, if any has
	 * @param firstFinishSinceDue the earliest run that finished at or after it,
	 *     whatever its verdict, if any has
	 */
	CloseOutHistory.Punctuality punctuality(Instant now, @Nullable Instant firstStartSinceDue,
			@Nullable Instant firstFinishSinceDue) {
		Instant dueAt = lastDue(now);
		boolean overdue = firstStartSinceDue == null
				&& now.isAfter(dueAt.plus(overdueAfter));
		boolean stale = firstFinishSinceDue == null
				&& now.isAfter(dueAt.plus(staleAfter));
		return new CloseOutHistory.Punctuality(dueAt, firstStartSinceDue, firstFinishSinceDue,
				overdue, stale);
	}
}
