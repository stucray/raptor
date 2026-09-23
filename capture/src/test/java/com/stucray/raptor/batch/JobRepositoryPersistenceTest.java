package com.stucray.raptor.batch;

import com.stucray.raptor.datasource.Acquisition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.corpus.CorpusMonth;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The run ledger, asserted on its rows rather than on its configuration (#84).
 *
 * <p>The previous guard checked that the configured table prefix and the
 * migration's schema named the same place. Both strings were right and neither
 * did anything: Boot 4 auto-configures a {@code ResourcelessJobRepository} and
 * has no {@code spring.batch.jdbc.*} to bind, so every job ran correctly and
 * recorded nothing. A test that reads the configuration cannot see that. This
 * one runs a job and reads the database back.
 *
 * <p>Both guarantees the {@code batch} schema exists to provide are checked
 * here, because both were absent and neither failed anything:
 * the durable execution record, and the refusal of a second identical run.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, JobRepositoryPersistenceTest.SampleCorpus.class})
@DisplayName("The job repository persists: executions are recorded, duplicates refused")
class JobRepositoryPersistenceTest {

	@Autowired JobOperator jobOperator;
	@Autowired Job loadHistoricCorpusJob;
	// The ledger lives in `batch`, which only the owner can read.
	@Autowired @Acquisition JdbcClient jdbc;

	@TestConfiguration(proxyBeanMethods = false)
	static class SampleCorpus {
		@Bean
		DynamicPropertyRegistrar corpusRoot() {
			return registry -> registry.add("raptor.corpus.historic-root",
					() -> Path.of("src/test/resources/sample-corpus").toAbsolutePath().toString());
		}
	}

	@Test
	@DisplayName("a completed run leaves a row in batch.BATCH_JOB_EXECUTION")
	void recordsTheExecution() throws Exception {
		JobExecution execution = jobOperator.start(loadHistoricCorpusJob, parameters("2020-03"));
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

		// Scoped to this month's parameter: the two tests share a context, so
		// the ledger legitimately holds the other one's run too.
		List<String> statuses = jdbc.sql("""
						select e.status
						from batch.batch_job_execution e
						join batch.batch_job_instance i on i.job_instance_id = e.job_instance_id
						join batch.batch_job_execution_params p
						  on p.job_execution_id = e.job_execution_id
						where i.job_name = :name
						  and p.parameter_name = :param
						  and p.parameter_value = :month""")
				.param("name", loadHistoricCorpusJob.getName())
				.param("param", CorpusMonth.PARAM)
				.param("month", "2020-03")
				.query(String.class)
				.list();

		assertThat(statuses)
				.as("the execution must be in the ledger, not only in the returned object, "
						+ "and the ledger must record which month it was for")
				.containsExactly("COMPLETED");
	}

	@Test
	@DisplayName("a second run with identical parameters is refused")
	void refusesADuplicateRun() throws Exception {
		JobParameters parameters = parameters("2024-03");
		assertThat(jobOperator.start(loadHistoricCorpusJob, parameters).getStatus())
				.isEqualTo(BatchStatus.COMPLETED);

		// This is what replaces launchd's no-double-start guarantee for the
		// finite jobs. With a resourceless repository there is no instance to
		// find, so the second run simply proceeds.
		assertThatExceptionOfType(JobInstanceAlreadyCompleteException.class)
				.isThrownBy(() -> jobOperator.start(loadHistoricCorpusJob, parameters));
	}

	private static JobParameters parameters(String month) {
		return new JobParametersBuilder().addString(CorpusMonth.PARAM, month).toJobParameters();
	}
}
