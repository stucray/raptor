package com.stucray.raptor.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When the close-out was due and whether it is late — the half of #334 that
 * replaces "the timer fired, so it ran" once the timer is on the host.
 */
@DisplayName("The close-out schedule says when a run was due and when it is overdue")
class CloseOutScheduleTest {

	private final CloseOutSchedule schedule =
			new CloseOutSchedule(LocalTime.of(23, 30), Duration.ofHours(1));

	@Test
	@DisplayName("before 23:30Z the run that was due is yesterday's")
	void beforeTheDueTimeTheLastDueIsYesterday() {
		assertThat(schedule.lastDue(Instant.parse("2026-09-23T04:31:00Z")))
				.isEqualTo(Instant.parse("2026-09-22T23:30:00Z"));
	}

	@Test
	@DisplayName("at and after 23:30Z it is today's")
	void fromTheDueTimeTheLastDueIsToday() {
		assertThat(schedule.lastDue(Instant.parse("2026-09-23T23:30:00Z")))
				.isEqualTo(Instant.parse("2026-09-23T23:30:00Z"));
		assertThat(schedule.lastDue(Instant.parse("2026-09-23T23:59:00Z")))
				.isEqualTo(Instant.parse("2026-09-23T23:30:00Z"));
	}

	@Test
	@DisplayName("no run within the margin is not yet overdue")
	void withinTheMarginIsNotOverdue() {
		assertThat(schedule.punctuality(Instant.parse("2026-09-23T00:29:00Z"), null).overdue())
				.isFalse();
	}

	@Test
	@DisplayName("no run past the margin is overdue — the case a 26h absence took a day to show")
	void pastTheMarginWithNoRunIsOverdue() {
		CloseOutHistory.Punctuality late =
				schedule.punctuality(Instant.parse("2026-09-23T00:31:00Z"), null);

		assertThat(late.overdue()).isTrue();
		assertThat(late.dueAt()).isEqualTo(Instant.parse("2026-09-22T23:30:00Z"));
		assertThat(late.startedAt()).isNull();
	}

	@Test
	@DisplayName("a run that came, however late, is not overdue and carries its start")
	void aRunThatCameIsNotOverdue() {
		Instant started = Instant.parse("2026-09-23T00:33:00Z");

		CloseOutHistory.Punctuality came =
				schedule.punctuality(Instant.parse("2026-09-23T04:00:00Z"), started);

		assertThat(came.overdue()).isFalse();
		assertThat(came.startedAt()).isEqualTo(started);
	}
}
