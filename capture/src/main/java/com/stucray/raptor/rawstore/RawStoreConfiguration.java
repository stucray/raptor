package com.stucray.raptor.rawstore;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the raw store.
 *
 * <p>Registering {@link RawStoreProperties} explicitly rather than relying on
 * {@code @ConfigurationPropertiesScan} — which lives on {@code RaptorApplication}
 * in the <b>backend</b> module, so it is absent from every {@code @SpringBootTest}
 * context this module raises on its own. A {@code @Component} in here is still
 * found by component scanning, so an indicator that depends on an unregistered
 * properties record compiles, starts fine under the backend's context, and fails
 * to start under acquisition's — which is how it was found (CI, #268: 27 errors
 * across `SchemaMigrationTest` and `MarketScopeIntegrationTest`, none of them in
 * the module the change was tested in).
 *
 * <p>Every other properties record in this module is registered the same way:
 * `ScopeConfiguration`, `DataSourceConfiguration`, `BetfairConfiguration`,
 * `FetchArchiveJobConfig`. The pattern is the module's, not a workaround.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RawStoreProperties.class)
class RawStoreConfiguration {}
