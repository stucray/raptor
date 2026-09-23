package com.stucray.raptor.schema;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * The read side's migrations run after the acquisition schemas exist.
 *
 * <p>{@link AcquisitionMigrations} used to say the ordering between the two
 * Flyway instances was "deliberately not constrained", and that was true and
 * worth saying: they shared no schema and no history table, and nothing read an
 * acquisition table during context refresh.
 *
 * <p><b>S8 ended it.</b> The read side is served from {@code query} through
 * compatibility views, and a view cannot be created over a table that does not
 * exist yet. On a fresh database the read-side line would race the acquisition
 * line and fail roughly half the time — the worst possible failure, because it
 * would pass on every machine where the acquisition schemas happened to already
 * be there and fail on the one machine standing the system up for the first
 * time.
 *
 * <p>Expressed as a bean dependency rather than as an ordered annotation because
 * Boot owns the read-side initialiser: {@code flywayInitializer} is declared by
 * {@code FlywayAutoConfiguration}, so the only way to get in front of it is to
 * make it depend on a bean of ours.
 */
@Configuration(proxyBeanMethods = false)
class MigrationOrdering {

	/**
	 * Static, and it must be: a {@code BeanFactoryPostProcessor} declared by an
	 * instance method forces its whole configuration class to be instantiated
	 * early, before post-processing has run on it.
	 *
	 * <p>Both names are looked for and neither is required. The initialiser is
	 * what actually runs the migrations; the {@code flyway} bean is included so
	 * that anything injecting it directly cannot observe an unmigrated database.
	 * A context without them — a slice test that never configures Flyway — is
	 * left alone rather than failed.
	 */
	@Bean
	static BeanFactoryPostProcessor acquisitionSchemasBeforeReadSideMigrations() {
		return beanFactory -> {
			for (String name : new String[] {"flywayInitializer", "flyway"}) {
				if (beanFactory.containsBeanDefinition(name)) {
					BeanDefinition definition = beanFactory.getBeanDefinition(name);
					definition.setDependsOn(StringUtils.addStringToArray(
							definition.getDependsOn(), "acquisitionMigrations"));
				}
			}
		};
	}
}
