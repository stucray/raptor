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
 * Scope may use up to four connections of 200 markets (#67), in both places
 * it is defaulted.
 *
 * <p>Four is the operator's share of the app key's ten connections, which every
 * session on the key shares (#63). Raising it spends connections other
 * applications on the account may need; lowering it caps capture.
 */
@DisplayName("Scope defaults to up to four connections of 200 markets")
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
					assertThat(scope.maxConnections()).isEqualTo(4);
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
					assertThat(scope.maxConnections()).isEqualTo(4);
					assertThat(scope.marketsPerConnection()).isEqualTo(200);
				});
	}
}
