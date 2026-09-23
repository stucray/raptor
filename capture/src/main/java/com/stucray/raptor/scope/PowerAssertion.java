package com.stucray.raptor.scope;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps the machine awake while a match is being recorded.
 *
 * <p>The unglamorous detail that decides whether residency works at all: a
 * long-running service does not stop a laptop sleeping, and the launchd era at
 * least ended each run at a known time. On 2026-08-23 the machine slept mid-match
 * — lid closed, on battery — and Man City v Bournemouth cut out around eighty
 * minutes in while the log went on printing heartbeats; the truncated capture was
 * indistinguishable from a complete one. Sleep is the largest single cause of
 * gaps in the existing corpus.
 *
 * <p>So a power assertion is held for exactly as long as something is in-play.
 * Not for as long as the process runs: a recorder that pins a laptop awake for a
 * fortnight to cover four hours of football would be turned off by whoever owns
 * the battery, and then it covers nothing.
 *
 * <p><b>{@code -w} our own pid</b>, so the assertion cannot outlive the process
 * that asked for it. A {@code caffeinate} left behind by a crash is an awake
 * machine nobody can account for.
 *
 * <p><b>It holds nothing in the deployed shape, and that is handled outside
 * this class.</b> {@code bin/up} runs paddock as a Linux container, where
 * {@code caffeinate} does not exist — so {@code compose.yaml} switches this off
 * rather than let it fail quietly at the file-exists check, and {@code
 * scripts/keep-awake} holds the assertion on the host instead, polling this
 * application's own {@code /actuator/health/capture} to know when to (#107).
 * A container can no more hold a host power assertion than read one; the fix
 * was never going to be in here. This class remains correct and is what runs
 * when paddock runs on the host.
 *
 * <p>What it does not buy: a closed lid on battery still sleeps, whatever is
 * asserted. That gap is recorded rather than prevented — the watchdog's
 * clock-jump check writes a {@code raw.capture_gap} row with {@code cause=SLEEP}
 * — and this class shortens the list of nights that need one.
 */
@Component
class PowerAssertion {

	private static final Logger log = LoggerFactory.getLogger(PowerAssertion.class);

	/** macOS only, and absent on CI, which is why its absence is not a failure. */
	private static final Path CAFFEINATE = Path.of("/usr/bin/caffeinate");

	private final boolean enabled;

	private @Nullable Process held;

	PowerAssertion(ScopeProperties properties) {
		this.enabled = properties.holdPowerAssertion();
	}

	/**
	 * Hold an assertion while any market is live, and release it when none is.
	 *
	 * <p>Idempotent in both directions: called on every scope poll, and the
	 * overwhelming majority of those calls find the world already the way it
	 * should be.
	 */
	synchronized void covering(int liveMarkets) {
		if (!enabled) {
			return;
		}
		if (liveMarkets > 0) {
			hold();
		} else {
			release("no market is live");
		}
	}

	private void hold() {
		Process current = held;
		if (current != null && current.isAlive()) {
			return;
		}
		try {
			Process started = spawn();
			if (started == null) {
				// Not macOS. At debug, because on a platform where the assertion has
				// no meaning a warning on every poll is just noise that trains people
				// to skip the log.
				log.debug("no {}; nothing is holding this machine awake", CAFFEINATE);
				return;
			}
			held = started;
			log.info("holding a power assertion for the duration of the live market(s)");
		} catch (IOException e) {
			log.warn("could not hold a power assertion ({}); a sleeping machine will now "
					+ "cost book, and the gap ledger is what will show it", e.toString());
		}
	}

	/**
	 * Start one, or answer {@code null} where there is nothing to start.
	 *
	 * <p>Package-private so a test can watch the transitions with a process it
	 * can see, on a platform that has no {@code caffeinate}.
	 */
	@Nullable Process spawn() throws IOException {
		if (!Files.isExecutable(CAFFEINATE)) {
			return null;
		}
		return new ProcessBuilder(List.of(CAFFEINATE.toString(),
				// -i idle sleep, -s system sleep, -w so it dies with us.
				"-i", "-s", "-w", String.valueOf(ProcessHandle.current().pid())))
				.redirectErrorStream(true)
				.start();
	}

	private void release(String why) {
		Process current = held;
		held = null;
		if (current == null) {
			return;
		}
		current.destroy();
		log.info("released the power assertion: {}", why);
	}

	@PreDestroy
	synchronized void shutdown() {
		release("the recorder is shutting down");
	}

	/** Whether an assertion is being held, for the tests and for the health view. */
	synchronized boolean holding() {
		Process current = held;
		return current != null && current.isAlive();
	}
}
