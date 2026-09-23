package com.stucray.raptor;

import java.io.IOException;
import java.nio.file.Files;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * One PostgreSQL for the whole JVM, deliberately not a bean (#118).
     *
     * <p>A {@code @Bean} container is started per <em>application context</em>,
     * and Spring's context cache keeps every context alive for the life of the
     * JVM — so this module's test run left one database container running per
     * distinct context, five of them in a single run on 2026-09-05, inside the
     * same 7.65 GiB Docker Desktop VM the resident recorder lives in. That is the
     * multiplier behind both symptoms on #118: the recorder killed with exit 137,
     * and a later context failing Flyway's very first connection with "An error
     * occurred while setting up the SSL connection".
     *
     * <p>Being a static field rather than a bean is what makes the sharing hold:
     * Boot's Testcontainers lifecycle support stops a container bean when its
     * context closes, which would take the shared database down under everything
     * that ran afterwards. Nothing stops it now; the JVM exiting does, via Ryuk.
     *
     * <p>The cost is that every context shares one database. Tests here already
     * clear what they depend on before each test rather than assuming an empty
     * schema, which is the property that has to keep holding.
     */
    static final PostgreSQLContainer POSTGRES =
        // Bounded so a stalled container fails loud instead of wedging the
        // build on pgjdbc's infinite default timeouts (see overround #839).
        new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
            .withUrlParam("socketTimeout", "60")
            .withUrlParam("loginTimeout", "15");

    static {
        POSTGRES.start();
    }

    /**
     * overround-analysis's role password (#247). paddock never connects as that
     * role, so nothing but the migration and the tests that log in as it read it.
     */
    static final String ANALYSIS_PASSWORD = "analysis-test-password";

    /**
     * Never let the archive sweep fire inside a test.
     *
     * <p>It reaches the public internet and writes into the custody root, so a
     * test that reached it would do both — against the real football-data.co.uk
     * and the real custody tree. Backend tests boot the whole application,
     * scheduling included, which is the only reason this belongs here rather
     * than in the archive's own module.
     *
     * <p><b>It matters more since #200</b>, not less. The sweep had its own cron
     * until then, so the exposure was a suite that happened to be running at
     * 08:30Z; it is a step of the nightly close-out now, and the close-out is
     * enabled in this application.yml because {@code ScheduledTasksTest} has to
     * see it registered.
     */
    @Bean
    public DynamicPropertyRegistrar noScheduledArchiveSweep() {
        return registry -> registry.add("raptor.football-archive.sweep.enabled",
            () -> "false");
    }

    /**
     * The two identities, pointed at the container.
     *
     * <p>Both pools are built from {@code raptor.datasource.*} so that the owner
     * and the restricted identity are separately addressable (PRD #73); nothing
     * reads Boot's {@code spring.datasource.*}, which is why the container needs
     * no {@code @ServiceConnection} and can stay outside the context altogether.
     *
     * <p>The read identity's role does not exist until acquisition migration V6
     * has run, which is why its pool must connect lazily.
     */
    @Bean
    public DynamicPropertyRegistrar databaseIdentities() {
        return registry -> {
            registry.add("raptor.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("raptor.datasource.owner.username", POSTGRES::getUsername);
            registry.add("raptor.datasource.owner.password", POSTGRES::getPassword);
            registry.add("raptor.datasource.read.username", () -> "paddock_reader");
            registry.add("raptor.datasource.read.password", () -> "reader-test-password");
            registry.add("raptor.analysis-role.password", () -> ANALYSIS_PASSWORD);
            // Every context builds both pools and none is ever closed, so the suite's
            // peak demand on the one shared database is contexts x 2 x this. Ten
            // apiece exhausts max_connections and the next context cannot start.
            registry.add("raptor.datasource.max-pool-size", () -> "2");
        };
    }

    /**
     * Never let a test touch the real spill directory.
     *
     * <p>{@code raptor.recorder.spill.directory} defaults to
     * {@code ${user.home}/.raptor/spill} — the developer's actual spill
     * directory, holding real messages that are not yet anywhere else. Two
     * things go wrong if a test context inherits that default, and the second
     * one destroys data:
     *
     * <ul>
     * <li>the spill health indicator reads whatever happens to be sitting there,
     *     so a verdict depends on the machine the suite runs on. Empty on CI,
     *     not empty on a laptop that has ever spilled — which is a test that
     *     passes everywhere it is watched and fails only where it is not.</li>
     * <li>{@code SpillDrain} is {@code @Scheduled}, and backend tests boot the
     *     whole application with scheduling on. A suite running longer than the
     *     drain interval would replay those real messages into the throwaway
     *     Testcontainers database and then unlink the files — deleting the only
     *     copy that exists, into a database thrown away seconds later.</li>
     * </ul>
     *
     * <p>Same shape as the archive sweep above: a scheduled job whose default
     * target is real, outside-the-test state.
     */
    @Bean
    public DynamicPropertyRegistrar spillIntoATempDirectory() {
        return registry -> registry.add("raptor.recorder.spill.directory",
            () -> SPILL_DIRECTORY);
    }

    /**
     * Resolved once, not per lookup: a registrar's supplier is invoked on every
     * property resolution, so creating the directory inside it would hand
     * different answers to different readers of the same setting.
     */
    private static final String SPILL_DIRECTORY = createSpillDirectory();

    private static String createSpillDirectory() {
        try {
            return Files.createTempDirectory("paddock-test-spill").toString();
        } catch (IOException e) {
            throw new IllegalStateException("could not create a temporary spill directory", e);
        }
    }
}
