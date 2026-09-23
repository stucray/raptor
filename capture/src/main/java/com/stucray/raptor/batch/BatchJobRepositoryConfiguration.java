package com.stucray.raptor.batch;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Isolation;

/**
 * The job repository, backed by PostgreSQL rather than by memory.
 *
 * <p>This has to be declared. Boot 4's {@code BatchAutoConfiguration} is
 * "auto-configuration for Spring Batch <em>using an in-memory store</em>": it
 * registers a {@code DefaultBatchConfiguration}, and Spring Batch 6's
 * {@code DefaultBatchConfiguration#jobRepository()} returns a
 * {@code ResourcelessJobRepository}. There is no JDBC variant on the classpath —
 * {@code spring-boot-batch} ships three auto-configurations and none of them
 * reads a {@code DataSource}. Boot 4's {@code BatchProperties} has exactly one
 * property, {@code spring.batch.job.name}, so {@code spring.batch.jdbc.*} binds
 * to nothing and configures nothing. Without this class the jobs run correctly
 * and record nothing (#84).
 *
 * <p>{@code @EnableBatchProcessing} is required here, and its presence is
 * deliberate rather than an oversight. It backs Boot's auto-configuration off —
 * which is the point, because what Boot configures is the resourceless
 * repository we are replacing — and {@code @EnableJdbcJobRepository} documents
 * that it must sit on a class annotated with it.
 *
 * <p>What the two guarantees rest on:
 *
 * <ul>
 *   <li>{@code batch.BATCH_JOB_EXECUTION} is the acquisition run ledger, which
 *       is why the prefix names the schema the migration puts the tables in.
 *   <li>{@code SERIALIZABLE} on create is what makes the
 *       {@code (JOB_NAME, JOB_KEY)} uniqueness on {@code BATCH_JOB_INSTANCE} a
 *       real no-double-start guarantee rather than a race — it replaces the one
 *       launchd used to provide. It is Spring Batch's default; it is stated
 *       explicitly because it is load-bearing, and a future edit that quietly
 *       relaxed it would cost the guarantee silently.
 * </ul>
 *
 * <p>All three refs name the acquisition identity's beans rather than Boot's
 * defaults, which are built on the primary — the restricted identity, which has
 * no rights in {@code batch} at all. {@code jdbcOperationsRef} matters as much
 * as the datasource: the repository does its reads through that template, and
 * the default names Boot's {@code jdbcTemplate} on the primary.
 */
@Configuration(proxyBeanMethods = false)
@EnableBatchProcessing
@EnableJdbcJobRepository(
		tablePrefix = "batch.BATCH_",
		isolationLevelForCreate = Isolation.SERIALIZABLE,
		dataSourceRef = "acquisitionDataSource",
		transactionManagerRef = "acquisitionTransactionManager",
		jdbcOperationsRef = "acquisitionJdbcTemplate")
class BatchJobRepositoryConfiguration {
}
