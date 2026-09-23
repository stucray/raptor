package com.stucray.raptor;

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
	 * JVM — so a module's test run left one database container running per
	 * distinct context, five of them in a single backend run on 2026-09-05,
	 * inside the same 7.65 GiB Docker Desktop VM the resident recorder lives in.
	 * That is the multiplier behind both symptoms on #118: the recorder killed
	 * with exit 137, and a later context failing Flyway's very first connection
	 * with "An error occurred while setting up the SSL connection".
	 *
	 * <p>Being a static field rather than a bean is what makes the sharing hold.
	 * Boot's Testcontainers lifecycle support stops a container bean when its
	 * context closes, and contexts do close here — {@code DatabaseOutageSpillTest}
	 * is {@code @DirtiesContext(AFTER_CLASS)} — which would take the shared
	 * database down under every test that ran after it. Nothing stops it now; the
	 * JVM exiting does, via Ryuk.
	 *
	 * <p>The cost is that every context shares one database. Integration tests
	 * here already delete what they depend on before each test rather than
	 * assuming an empty schema, which is the property that has to keep holding.
	 */
	public static final PostgreSQLContainer POSTGRES =
		// Bounded so a stalled container fails loud instead of wedging the build on
		// pgjdbc's infinite default timeouts (see overround #839). The socket
		// timeout moved off the URL and onto `raptor.datasource.socket-timeout`,
		// which the pools apply in production too: a URL parameter overrides a
		// driver property, so leaving it here would have made the production dial
		// silently untestable — and it is the dial that decides whether a wedged
		// database reaches the recorder's spill or stalls the writer forever.
		new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
			.withUrlParam("loginTimeout", "15");

	static {
		POSTGRES.start();
	}

	/**
	 * overround-analysis's role password (#247). paddock never connects as that
	 * role, so nothing but the migration and the tests that log in as it read it.
	 */
	public static final String ANALYSIS_PASSWORD = "analysis-test-password";

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
}
