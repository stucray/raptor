package com.stucray.raptor.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The sweep end to end: real HTTP, real PostgreSQL, real files.
 *
 * <p>The upstream is a JDK {@link HttpServer} in this JVM rather than a mocked
 * request factory, because the behaviours that matter here are the server's, not
 * the client's — a {@code 304} in answer to an {@code If-None-Match}, and a
 * {@code 300} for a season that does not exist. Simulating those in a mock would
 * be asserting that the test's own idea of the protocol matches the code's.
 *
 * <p>The assertion this slice exists for is
 * {@link #aCorrectedFileIsAddedBesideTheVersionItReplaces}: upstream republishes
 * these CSVs in place, and until now the bytes an analysis actually ran against
 * were overwritten by the correction and gone.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, FetchArchiveIntegrationTest.FakeArchive.class})
class FetchArchiveIntegrationTest {

	private static final String FIRST = "Div,Date,HomeTeam\nE0,15/08/25,Arsenal\n";
	private static final String CORRECTED = "Div,Date,HomeTeam\nE0,15/08/25,Arsenal FC\n";

	/** The body the fake archive is currently serving for E0, and its ETag. */
	static volatile String body = FIRST;
	static volatile String etag = "\"one\"";
	static final AtomicInteger bodiesServed = new AtomicInteger();

	private static HttpServer server;

	@Autowired JobOperator jobOperator;
	@Autowired Job fetchArchiveJob;
	// The owner's client: `raw` is refused to the read identity outright.
	@Autowired @Acquisition JdbcClient jdbc;
	@Autowired ArchiveProperties properties;

	private static String season() {
		return ArchiveSeasons.currentSeason(Clock.systemUTC());
	}

	private static String path() {
		return "raw/E0/E0-%s.csv".formatted(season());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeArchive {

		@Bean
		DynamicPropertyRegistrar archiveProperties() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", FakeArchive::handle);
			server.start();
			Path custody = Files.createTempDirectory("football-archive");
			return registry -> {
				registry.add("raptor.football-archive.base-url",
						() -> "http://127.0.0.1:" + server.getAddress().getPort());
				registry.add("raptor.football-archive.custody-root", custody::toString);
				// Two divisions, one season: E0 exists upstream, E1 does not.
				registry.add("raptor.football-archive.divisions", () -> "E0,E1");
				registry.add("raptor.football-archive.first-season-year",
						() -> ArchiveSeasons.currentSeasonYear(Clock.systemUTC()));
				registry.add("raptor.football-archive.request-delay", () -> "0ms");
				registry.add("raptor.football-archive.sweep.enabled", () -> "false");
			};
		}

		/**
		 * Apache MultiViews' {@code 300} for anything but E0, a conditional
		 * {@code 304} when the ETag matches, and the body otherwise.
		 */
		private static void handle(HttpExchange exchange) throws IOException {
			String uri = exchange.getRequestURI().getPath();
			if (!uri.endsWith("/E0.csv")) {
				byte[] choices = "<html><title>300 Multiple Choices</title></html>"
						.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(300, choices.length);
				exchange.getResponseBody().write(choices);
				exchange.close();
				return;
			}
			if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
				exchange.sendResponseHeaders(304, -1);
				exchange.close();
				return;
			}
			byte[] payload = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("ETag", etag);
			exchange.getResponseHeaders().add("Content-Type", "text/csv");
			exchange.sendResponseHeaders(200, payload.length);
			exchange.getResponseBody().write(payload);
			exchange.close();
			bodiesServed.incrementAndGet();
		}
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@BeforeEach
	void resetArchive() throws IOException {
		Path raw = properties.custodyRoot().resolve("raw");
		if (Files.isDirectory(raw)) {
			try (var walk = Files.walk(raw)) {
				for (Path p : walk.filter(Files::isRegularFile).toList()) {
					Files.delete(p);
				}
			}
		}
		body = FIRST;
		etag = "\"one\"";
		bodiesServed.set(0);
	}

	/**
	 * The 711 CSVs already on disk are the corpus every finding in this project
	 * came from, and upstream may have corrected any of them since. Adopting
	 * before fetching is what keeps that version; fetching first would store the
	 * correction and overwrite the file in the same pass.
	 */
	@Test
	void aFileAlreadyInCustodyIsAdoptedBeforeTheSweepCanReplaceIt() throws Exception {
		String onDisk = "Div,Date,HomeTeam\nE0,15/08/25,Arsenal (as analysed)\n";
		Path file = properties.custodyRoot().resolve(path());
		Files.createDirectories(file.getParent());
		Files.writeString(file, onDisk);
		// Upstream has moved on since that copy was taken.
		body = CORRECTED;
		etag = "\"two\"";

		Map<String, Object> report = sweep();

		assertThat(report).containsEntry("adopted", 1L).containsEntry("updated", 1L);
		assertThat(versionsOf(path())).extracting(Version::content)
				.containsExactly(onDisk, CORRECTED);
		assertThat(Files.readString(file)).isEqualTo(CORRECTED);
	}

	@Test
	void firstSweepStoresTheFileInRawAndInCustodyAndSaysWhatItDid() throws Exception {
		Map<String, Object> report = sweep();

		assertThat(report).containsEntry("new", 1L).containsEntry("not_published", 1L)
				.containsEntry("failed", 0L);
		assertThat(Files.readString(properties.custodyRoot().resolve(path()))).isEqualTo(FIRST);
		assertThat(versionsOf(path())).singleElement()
				.satisfies(v -> assertThat(v.content()).isEqualTo(FIRST));
		// A season that does not exist yet is not a failure and leaves no trace.
		assertThat(jdbc.sql("select count(*) from raw.football_file where division = 'E1'")
				.query(Integer.class).single()).isZero();
	}

	@Test
	void aSecondSweepRevalidatesAndTransfersNothing() throws Exception {
		sweep();
		bodiesServed.set(0);

		Map<String, Object> report = sweep();

		assertThat(report).containsEntry("unchanged", 1L).containsEntry("new", 0L);
		// The whole economics of the sweep: the server sent no body at all.
		assertThat(bodiesServed).hasValue(0);
		assertThat(versionsOf(path())).hasSize(1);
		assertThat(jdbc.sql("select checked_at > fetched_at from raw.football_file where path = ?")
				.param(path()).query(Boolean.class).single()).isTrue();
	}

	/**
	 * The reason {@code raw.football_file} holds the bytes at all. Upstream
	 * republishes a season's CSV whenever a result is corrected, and the file on
	 * disk is overwritten — so without this the version every earlier analysis
	 * ran against would exist nowhere.
	 */
	@Test
	void aCorrectedFileIsAddedBesideTheVersionItReplaces() throws Exception {
		sweep();

		body = CORRECTED;
		etag = "\"two\"";
		Map<String, Object> report = sweep();

		assertThat(report).containsEntry("updated", 1L);
		assertThat(Files.readString(properties.custodyRoot().resolve(path()))).isEqualTo(CORRECTED);
		assertThat(versionsOf(path())).extracting(Version::content)
				.containsExactly(FIRST, CORRECTED);
	}

	private Map<String, Object> sweep() throws Exception {
		JobExecution execution = jobOperator.start(fetchArchiveJob, new JobParametersBuilder()
				.addLong(FetchArchiveJobConfig.RUN_PARAM, System.nanoTime())
				.addString(FetchArchiveJobConfig.CURRENT_PARAM, "false")
				.toJobParameters());
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		return FetchArchiveController.report(execution);
	}

	private record Version(String content, long bytes) {}

	/** Every version of a path, oldest first. */
	private List<Version> versionsOf(String path) {
		return jdbc.sql("""
				select content, bytes from raw.football_file
				where path = ? order by fetched_at, id""")
				.param(path)
				.query((rs, row) -> new Version(
						new String(rs.getBytes("content"), StandardCharsets.UTF_8), rs.getLong("bytes")))
				.list();
	}
}
