package com.stucray.raptor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The application must never stop the database it started (PRD #73 slice 1).
 *
 * <p>Spring Boot's Docker Compose support defaults to {@code start-and-stop},
 * which runs {@code docker compose stop} as the JVM exits. Harmless while
 * paddock is a read app; a data-availability hazard once it is resident and
 * holding a live capture, because a {@code Ctrl-C} then takes Postgres down
 * mid-match and the capture is unreplayable.
 *
 * <p>Asserted against the shipped YAML rather than a running context because
 * the support is skipped in tests by default — the setting has no observable
 * runtime effect here, so only the configuration itself can be checked.
 */
@DisplayName("Compose lifecycle: the app starts the database and never stops it")
class ComposeLifecycleTest {

    private static Object property(String name) throws IOException {
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).hasSize(1);
        return sources.getFirst().getProperty(name);
    }

    @Test
    @DisplayName("lifecycle management is start-only, not the start-and-stop default")
    void lifecycleIsStartOnly() throws IOException {
        assertThat(property("spring.docker.compose.lifecycle-management")).isEqualTo("start-only");
    }

    @Test
    @DisplayName("no stop command is configured — `down` would remove the container, and with -v the volume")
    void stopCommandIsUnset() throws IOException {
        assertThat(property("spring.docker.compose.stop.command")).isNull();
    }

}
