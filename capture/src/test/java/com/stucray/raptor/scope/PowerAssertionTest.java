package com.stucray.raptor.scope;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * When the machine is held awake, and when it is let go.
 *
 * <p>The transitions are the whole of it, and they are asserted against a real
 * child process rather than a mock — {@code caffeinate} exists only on macOS, so
 * a test that spawned the real thing would prove nothing on CI and nothing on a
 * Linux box, which is where this would first be wrong.
 */
class PowerAssertionTest {

	private final AtomicInteger spawned = new AtomicInteger();

	@Test
	void holdsOneAssertionWhileAnythingIsInPlayAndReleasesItAfterwards()
			throws InterruptedException {
		StubAssertion assertion = new StubAssertion(true);

		assertion.covering(0);
		assertThat(assertion.holding()).isFalse();
		assertThat(spawned).hasValue(0);

		assertion.covering(2);
		assertThat(assertion.holding()).isTrue();

		// Four polls of a match in progress is still one assertion: the poll is
		// every fifteen minutes and a ninety-minute match is six of them.
		assertion.covering(2);
		assertion.covering(1);
		assertThat(spawned).hasValue(1);

		Process held = assertion.spawnedProcess();
		assertion.covering(0);
		assertThat(assertion.holding()).isFalse();
		assertThat(held.waitFor(Duration.ofSeconds(5).toMillis(),
				java.util.concurrent.TimeUnit.MILLISECONDS)).isTrue();
	}

	@Test
	void doesNothingAtAllWhenTheInstanceIsNotTheOneCapturing() {
		StubAssertion assertion = new StubAssertion(false);

		assertion.covering(3);

		// An instance that is not capturing has no business pinning a laptop
		// awake, and there is no way for it to notice that it has.
		assertThat(assertion.holding()).isFalse();
		assertThat(spawned).hasValue(0);
	}

	@Test
	void releasesTheAssertionWhenTheApplicationStops() {
		StubAssertion assertion = new StubAssertion(true);
		assertion.covering(1);

		assertion.shutdown();

		assertThat(assertion.holding()).isFalse();
	}

	/** A stand-in for {@code caffeinate}: a child that waits until it is killed. */
	private final class StubAssertion extends PowerAssertion {

		private @Nullable Process last;

		private StubAssertion(boolean enabled) {
			super(new ScopeProperties(Duration.ofHours(4), Duration.ofMinutes(15),
					Duration.ofSeconds(10), Duration.ofMinutes(130), Duration.ofHours(6),
					Duration.ofHours(48), Duration.ofMinutes(30), enabled, 3));
		}

		@Override
		@Nullable Process spawn() throws IOException {
			spawned.incrementAndGet();
			// `cat` with a pipe for a stdin waits forever and dies on destroy(),
			// which is exactly the shape of the process being stood in for.
			last = new ProcessBuilder(List.of("/bin/cat")).start();
			return last;
		}

		private Process spawnedProcess() {
			Process process = last;
			if (process == null) {
				throw new IllegalStateException("nothing was spawned");
			}
			return process;
		}
	}
}
