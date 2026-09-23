package com.stucray.raptor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Every {@code @Scheduled} method this application depends on is actually
 * scheduled.
 *
 * <p><b>Nothing else can check this, and the gap has cost data twice.</b>
 * {@code @EnableScheduling} lives in this module, on
 * {@link SchedulingConfiguration}; the beans that carry {@code @Scheduled} live
 * in {@code acquisition}. So acquisition's own tests boot a context with no
 * scheduler in it at all and cannot tell a wired timer from an unwired one —
 * and a {@code @Scheduled} method that is never invoked logs nothing, fails
 * nothing, and leaves only an absence.
 *
 * <p>That is the shape of #167, where {@code SpillReplayer} had no production
 * caller and a spill file sat unreplayed through four capture sessions while
 * two unit tests drove the replay directly and passed. It is also the risk
 * {@code SchedulingConfiguration}'s own javadoc describes: deleting or moving
 * that one class would silently switch off the scope poll, the stream watchdog,
 * the keep-alive, the daily archive sweep and the ledger refresh, and no test
 * would notice.
 *
 * <p>A test that calls a collaborator itself can never catch a missing caller.
 * This one asks the container what it will actually run.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("Every scheduled task the application depends on is registered")
class ScheduledTasksTest {

	/**
	 * The methods whose absence is invisible at runtime.
	 *
	 * <p>Named by {@code Class.method} because that is what
	 * {@code ScheduledMethodRunnable} renders, and because a name is what a
	 * reader of a failure needs — "no scheduled task for SpillDrain.drain" says
	 * what broke, where a count does not.
	 */
	private static final List<String> REQUIRED = List.of(
			// Puts spilled messages back into the system of record (#167).
			"SpillDrain.drain",
			// What puts fixtures into scope; without it the recorder subscribes
			// to nothing, all night, reporting UP.
			"MarketScopeService.poll",
			// The suspend detector that tears down a stream the machine slept
			// through.
			"StreamWatchdog",
			// Betfair session keep-alive.
			"BetfairSession",
			// The ledger projection behind the health screen.
			"CaptureLedgerRefresh",
			// What keeps raw.stream_message supplied with partitions to write into
			// (#267). Unregistered, the runway shrinks by a day a day until every
			// insert fails with "no partition of relation found for row" — and the
			// recorder cannot write the system of record at all. The class was
			// described in V3's comment and not written for a year, so an absent
			// timer here is not a hypothetical failure mode.
			"PartitionMaintenance");

	/**
	 * Timers that have been retired, and must stay retired.
	 *
	 * <p>The mirror image of the list above, and it exists because a deletion is
	 * as invisible as an omission: {@code ArchiveSchedule} fetched the current
	 * football-data season at 08:30Z from S9 until #200, when the fetch became a
	 * step of the nightly close-out. Two timers for one source would mean the
	 * archive swept twice a day for no reason, and — worse — that the close-out's
	 * ledger row stopped being the whole story of what ran overnight. Nothing but
	 * this assertion would notice the old one coming back.
	 */
	private static final List<String> RETIRED = List.of(
			"ArchiveSchedule",
			// The analysis close-out (#212), switched off by #255 and DELETED by
			// #256 along with the rest of the analysis package. The class no
			// longer exists, so this assertion cannot fail by accident — which is
			// exactly why it stays. It is the standing guard against analysis
			// being reintroduced here: the derivation belongs to
			// overround-analysis, whose own AnalysisCloseOutIntegrationTest boots
			// its shipped config and asserts the timer IS registered there. Two
			// processes deriving one night into one set of tables is the failure
			// this pair exists to make impossible.
			"AnalysisCloseOut",
			// The nightly close-out's own cron, retired by #334: Spring's timer
			// slipped by every minute the Mac slept, so launchd fires it over
			// POST /ops/close-out instead. A cron coming back beside that agent
			// would sweep the archive twice a night, the second time behind the
			// close-out lock's refusal at best.
			"NightlyCloseOut");

	@Autowired ScheduledTaskHolder scheduledTasks;

	@Test
	void everyScheduledTaskIsRegistered() {
		List<String> registered = registeredTasks();

		assertThat(REQUIRED).allSatisfy(required -> assertThat(registered)
				.as("no scheduled task for %s — the method exists but nothing will ever "
						+ "invoke it, which is silent at runtime", required)
				.anyMatch(actual -> actual.contains(required)));
	}

	@Test
	@DisplayName("A retired timer stays retired")
	void noRetiredTimerIsStillRegistered() {
		List<String> registered = registeredTasks();

		assertThat(RETIRED).allSatisfy(retired -> assertThat(registered)
				.as("%s is scheduled again — its work has another trigger now (see "
						+ "RETIRED for which), and a second one would run it twice", retired)
				.noneMatch(actual -> actual.contains(retired)));
	}

	private List<String> registeredTasks() {
		return scheduledTasks.getScheduledTasks().stream()
				.map(ScheduledTask::getTask)
				.map(task -> task.getRunnable().toString())
				.toList();
	}
}
