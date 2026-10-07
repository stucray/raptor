package com.stucray.raptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.scope.ScopeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Scope stays on one connection of 200 markets until the recorder can run
 * several (#64, #65), in both places it is defaulted.
 *
 * <p>A second connection planned before the recorder can hold one would put
 * fixtures on a slot nothing subscribes, and they would be lost without a word.
 * #67 raises {@code max-connections} and changes this pin with it.
 */
@DisplayName("Scope defaults to one connection of 200 markets until #67")
class ScopeConnectionDefaultsTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ScopeProperties.class)
	static class Bind {
	}

	@Test
	@DisplayName("the values application.yml ships")
	void shippedDefaults() {
		new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withUserConfiguration(Bind.class)
				.run(context -> {
					ScopeProperties scope = context.getBean(ScopeProperties.class);
					assertThat(scope.maxConnections()).isEqualTo(1);
					assertThat(scope.marketsPerConnection()).isEqualTo(200);
				});
	}

	@Test
	@DisplayName("the record's own defaults, with no configuration")
	void recordDefaults() {
		new ApplicationContextRunner()
				.withUserConfiguration(Bind.class)
				.run(context -> {
					ScopeProperties scope = context.getBean(ScopeProperties.class);
					assertThat(scope.maxConnections()).isEqualTo(1);
					assertThat(scope.marketsPerConnection()).isEqualTo(200);
				});
	}
}
