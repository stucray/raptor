package com.stucray.raptor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * Docker waits for an orderly stop before it kills the backend (#75).
 *
 * <p>Compose sends SIGTERM and, unless told otherwise, SIGKILL ten seconds
 * later. The JVM's own shutdown is allowed far longer: Spring gives each phase
 * {@code spring.lifecycle.timeout-per-shutdown-phase}, and the recorder waits up
 * to 30s per connection slot for its thread. A stop that ran past ten seconds
 * was killed before the open session's {@code ended_at} was stamped, and the
 * next recorder closed that session as ABANDONED, so a deliberate deploy read as
 * a crash.
 *
 * <p>Asserted against the shipped files because nothing in a test run ever
 * stops a container.
 */
@DisplayName("Docker waits for an orderly stop before it kills the backend")
class ComposeStopGraceTest {

    private static final Path COMPOSE = Path.of("../compose.yaml");

    /** Four slots, each joined for up to 30s by RecorderSupervisor. */
    private static final Duration RECORDER_STOP = Duration.ofSeconds(4 * 30);

    @Test
    @DisplayName("the backend's grace period covers a shutdown phase plus the recorder's stop")
    void graceCoversTheShutdownBudget() throws IOException {
        Duration grace = backendGrace();
        Duration phase = shutdownPhase();

        assertThat(grace).isGreaterThanOrEqualTo(phase.plus(RECORDER_STOP));
    }

    private static Duration backendGrace() throws IOException {
        try (Reader reader = Files.newBufferedReader(COMPOSE)) {
            Map<String, Object> compose = new Yaml().load(reader);
            Map<?, ?> services = (Map<?, ?>) compose.get("services");
            Map<?, ?> backend = (Map<?, ?>) services.get("backend");
            Object grace = backend.get("stop_grace_period");
            assertThat(grace).as("backend.stop_grace_period in compose.yaml").isNotNull();
            return DurationStyle.detectAndParse(grace.toString());
        }
    }

    private static Duration shutdownPhase() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
        Object phase = sources.getFirst().getProperty("spring.lifecycle.timeout-per-shutdown-phase");
        assertThat(phase).as("spring.lifecycle.timeout-per-shutdown-phase").isNotNull();
        return DurationStyle.detectAndParse(phase.toString());
    }
}
