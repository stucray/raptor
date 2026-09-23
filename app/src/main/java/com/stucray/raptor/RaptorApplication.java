package com.stucray.raptor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The one application in this repository.
 *
 * <p>Acquisition is a library module rather than a second application (PRD #73):
 * its ingest and projection beans share this package root and join this context,
 * so the system is one process with one health endpoint. No explicit scan list —
 * once the acquisition packages became {@code com.stucray.raptor.*} the default
 * scan reaches them, which is the point of the rename.
 *
 * <p>Deliberately NOT annotated {@code @EnableBatchProcessing}. Boot 4's
 * {@code BatchAutoConfiguration} carries
 * {@code @ConditionalOnMissingBean(value = DefaultBatchConfiguration.class,
 * annotation = EnableBatchProcessing.class)}, so adding the annotation backs
 * Boot's auto-configuration off — and Spring Batch 6 changed
 * {@code DefaultBatchConfiguration} to build a <em>resourceless</em>
 * (non-persistent) job repository. That would silently cost both the durable run
 * ledger and the SERIALIZABLE-on-create duplicate-run protection, with no
 * compile error and no startup failure.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RaptorApplication {

	public static void main(String[] args) {
		SpringApplication.run(RaptorApplication.class, args);
	}

}
