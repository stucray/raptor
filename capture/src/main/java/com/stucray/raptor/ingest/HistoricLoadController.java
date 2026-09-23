package com.stucray.raptor.ingest;

import com.stucray.raptor.corpus.CorpusMonth;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Localhost-only ops surface for the historic load.
 *
 * <p>Jobs are launched deliberately — {@code spring.batch.job.enabled=false}, so
 * starting the application does not start work. A resident service that begins
 * ingesting several million rows merely because someone restarted it is not a
 * service anyone can operate.
 */
@RestController
class HistoricLoadController {

	private static final Logger log = LoggerFactory.getLogger(HistoricLoadController.class);

	private final JobOperator jobOperator;
	private final Job loadHistoricCorpusJob;
	private final CorpusScanner scanner;

	HistoricLoadController(JobOperator jobOperator, Job loadHistoricCorpusJob,
			CorpusScanner scanner) {
		this.jobOperator = jobOperator;
		this.loadHistoricCorpusJob = loadHistoricCorpusJob;
		this.scanner = scanner;
	}

	/**
	 * Load every month present in the corpus, or just one with {@code ?month=}.
	 *
	 * <p>Sequential by design. The months are independent, but the load is
	 * disk-bound on bzip2 decompression and running them concurrently would
	 * contend for the same connection pool while making failures harder to
	 * attribute. It is a one-off migration, not a hot path.
	 */
	@PostMapping("/ops/load-historic")
	Map<String, Object> load(@RequestParam(required = false) String month) throws Exception {
		List<String> months = month != null ? List.of(month) : allMonths();
		log.info("loading {} month(s) from {}", months.size(), scanner.root());

		var results = new java.util.LinkedHashMap<String, String>();
		for (String each : months) {
			JobExecution execution = jobOperator.start(loadHistoricCorpusJob,
					new JobParametersBuilder()
							.addString(CorpusMonth.PARAM, each)
							.toJobParameters());
			results.put(each, execution.getStatus().toString());
		}
		return Map.of("months", results.size(), "results", results);
	}

	private List<String> allMonths() throws IOException {
		var months = new TreeSet<String>();
		for (Path file : scanner.files()) {
			months.add(scanner.monthOf(file));
		}
		return List.copyOf(months);
	}
}
