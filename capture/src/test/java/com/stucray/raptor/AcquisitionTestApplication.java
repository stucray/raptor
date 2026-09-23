package com.stucray.raptor;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A boot configuration for this module's own tests, and nothing more.
 *
 * <p>Acquisition is a library — the one Spring Boot application in this
 * repository is paddock's {@code RaptorApplication}, which these beans join at
 * runtime. {@code @SpringBootTest} still needs a {@code @SpringBootConfiguration}
 * to find, and the module's tests are worth keeping local: they exercise ingest
 * and projection against a real PostgreSQL without booting the read side.
 *
 * <p>Deliberately NOT annotated {@code @EnableBatchProcessing}, and no longer
 * for the reason this comment used to give. The annotation is required, not
 * banned (#84) — it is what backs off the auto-configuration that would
 * otherwise hand every job a repository persisting nothing. It belongs on
 * exactly one class, {@code BatchJobRepositoryConfiguration}, which this
 * application picks up by component scan along with everything else in the
 * module. A second one here would be a second answer to a question that has
 * one.
 */
@SpringBootApplication
class AcquisitionTestApplication {
}
