package com.stucray.raptor.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * When the close-out was due and whether it is late — the half of #334 that
 * replaces "the timer fired, so it ran" once the timer is on the host.
 *
 * <p>Since #79 it is due on chosen weekdays rather than every night, because
 * football-data.co.uk asks for automated downloads at most twice a week. The
 * dates below are real weekdays: 2026-10-12 is a Monday, 2026-10-15 a Thursday.
 */
@DisplayName("The close-out schedule says when a run was due, and when it is overdue or stale")
class CloseOutScheduleTest {

	private static final LocalTime DUE = LocalTime.of(23, 30);
	private static final Duration OVERDUE_AFTER = Duration.ofHours(1);
	private static final Duration STALE_AFTER = Duration.ofHours(2);

	private final CloseOutSchedule schedule =
			new CloseOutSchedule("MONDAY,THURSDAY", DUE, OVERDUE_AFTER, STALE_AFTER);

	@Nested
	@DisplayName("on a Monday and Thursday schedule")
	class TwiceAWeek {

		@Test
		@DisplayName("midweek, the run that was due is Monday's")
		void midweekTheLastDueIsMonday() {
			assertThat(schedule.lastDue(Instant.parse("2026-10-14T10:00:00Z")))
					.isEqualTo(Instant.parse("2026-10-12T23:30:00Z"));
		}

		@Test
		@DisplayName("on Monday before 23:30Z it is still the previous Thursday's")
		void beforeMondaysDueTimeItIsThursdays() {
			assertThat(schedule.lastDue(Instant.parse("2026-10-12T23:29:59Z")))
					.isEqualTo(Instant.parse("2026-10-08T23:30:00Z"));
		}

		@Test
		@DisplayName("from Monday 23:30Z it is Monday's")
		void fromMondaysDueTimeItIsMondays() {
			assertThat(schedule.lastDue(Instant.parse("2026-10-12T23:30:00Z")))
					.isEqualTo(Instant.parse("2026-10-12T23:30:00Z"));
		}

		@Test
		@DisplayName("over the weekend it is Thursday's")
		void overTheWeekendItIsThursdays() {
			assertThat(schedule.lastDue(Instant.parse("2026-10-18T12:00:00Z")))
					.isEqualTo(Instant.parse("2026-10-15T23:30:00Z"));
		}

		@Test
		@DisplayName("an off day after a run that came is neither overdue nor stale")
		void anOffDayIsQuiet() {
			Instant mondaysRun = Instant.parse("2026-10-12T23:30:20Z");

			CloseOutHistory.Punctuality wednesday = schedule.punctuality(
					Instant.parse("2026-10-14T23:45:00Z"), mondaysRun, mondaysRun);

			assertThat(wednesday.dueAt()).isEqualTo(Instant.parse("2026-10-12T23:30:00Z"));
			assertThat(wednesday.overdue()).isFalse();
			assertThat(wednesday.stale())
					.as("a nightly 26h rule would call Wednesday stale; the schedule must not")
					.isFalse();
		}

		@Test
		@DisplayName("a scheduled day with no run goes stale once the margin passes")
		void aMissedScheduledRunGoesStale() {
			CloseOutHistory.Punctuality friday = schedule.punctuality(
					Instant.parse("2026-10-16T01:31:00Z"), null, null);

			assertThat(friday.dueAt()).isEqualTo(Instant.parse("2026-10-15T23:30:00Z"));
			assertThat(friday.stale()).isTrue();
		}
	}

	@Nested
	@DisplayName("overdue: no run has started")
	class Overdue {

		@Test
		@DisplayName("no run within the margin is not yet overdue")
		void withinTheMarginIsNotOverdue() {
			assertThat(schedule.punctuality(Instant.parse("2026-10-13T00:29:00Z"), null, null)
					.overdue()).isFalse();
		}

		@Test
		@DisplayName("no run past the margin is overdue — the case a 26h absence took a day to show")
		void pastTheMarginWithNoRunIsOverdue() {
			CloseOutHistory.Punctuality late =
					schedule.punctuality(Instant.parse("2026-10-13T00:31:00Z"), null, null);

			assertThat(late.overdue()).isTrue();
			assertThat(late.dueAt()).isEqualTo(Instant.parse("2026-10-12T23:30:00Z"));
			assertThat(late.startedAt()).isNull();
		}

		@Test
		@DisplayName("a run that came, however late, is not overdue and carries its start")
		void aRunThatCameIsNotOverdue() {
			Instant started = Instant.parse("2026-10-13T00:33:00Z");

			CloseOutHistory.Punctuality came =
					schedule.punctuality(Instant.parse("2026-10-13T04:00:00Z"), started, null);

			assertThat(came.overdue()).isFalse();
			assertThat(came.startedAt()).isEqualTo(started);
		}
	}

	@Nested
	@DisplayName("stale: no run has finished — what the heartbeat alerts on")
	class Stale {

		@Test
		@DisplayName("no finish within the margin is not yet stale")
		void withinTheMarginIsNotStale() {
			assertThat(schedule.punctuality(Instant.parse("2026-10-13T01:29:00Z"), null, null)
					.stale()).isFalse();
		}

		@Test
		@DisplayName("a run still in flight past the margin is stale: it has not finished")
		void aRunThatNeverFinishesIsStale() {
			Instant started = Instant.parse("2026-10-12T23:30:05Z");

			CloseOutHistory.Punctuality wedged =
					schedule.punctuality(Instant.parse("2026-10-13T01:31:00Z"), started, null);

			assertThat(wedged.overdue()).isFalse();
			assertThat(wedged.stale()).isTrue();
		}

		@Test
		@DisplayName("a finish, whatever its verdict, is not stale and carries its time")
		void aFinishIsNotStale() {
			Instant finished = Instant.parse("2026-10-12T23:30:20Z");

			CloseOutHistory.Punctuality done = schedule.punctuality(
					Instant.parse("2026-10-14T12:00:00Z"), finished, finished);

			assertThat(done.stale()).isFalse();
			assertThat(done.finishedAt()).isEqualTo(finished);
		}
	}

	@Nested
	@DisplayName("the days setting")
	class Days {

		@Test
		@DisplayName("every day reproduces the nightly schedule it replaces")
		void everyDayIsNightly() {
			CloseOutSchedule nightly = new CloseOutSchedule(
					"MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY",
					DUE, OVERDUE_AFTER, STALE_AFTER);

			assertThat(nightly.lastDue(Instant.parse("2026-10-14T04:31:00Z")))
					.isEqualTo(Instant.parse("2026-10-13T23:30:00Z"));
			assertThat(nightly.lastDue(Instant.parse("2026-10-14T23:59:00Z")))
					.isEqualTo(Instant.parse("2026-10-14T23:30:00Z"));
		}

		@Test
		@DisplayName("names are read case-insensitively and with spaces")
		void namesAreForgiving() {
			CloseOutSchedule loose =
					new CloseOutSchedule(" monday , Thursday", DUE, OVERDUE_AFTER, STALE_AFTER);

			assertThat(loose.lastDue(Instant.parse("2026-10-14T10:00:00Z")))
					.isEqualTo(Instant.parse("2026-10-12T23:30:00Z"));
		}

		@Test
		@DisplayName("no days, or a day that is not one, fails at startup rather than never being due")
		void anUnusableSettingFailsFast() {
			assertThatThrownBy(() -> new CloseOutSchedule("", DUE, OVERDUE_AFTER, STALE_AFTER))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("raptor.close-out.days");
			assertThatThrownBy(() -> new CloseOutSchedule("MONDAY,FUNDAY", DUE, OVERDUE_AFTER,
					STALE_AFTER))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("FUNDAY");
		}
	}
}
