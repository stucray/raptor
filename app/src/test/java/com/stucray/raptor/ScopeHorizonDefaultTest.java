package com.stucray.raptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.scope.ScopeProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The scope horizon is a measured decision (#52), so its value is pinned in both
 * places it is defaulted.
 *
 * <p>The two can drift apart silently: a plain {@link ApplicationContextRunner}
 * reads no config data at all, so it sees only the record's
 * {@code @DefaultValue}. The deployed application binds {@code application.yml}
 * instead, which {@link ConfigDataApplicationContextInitializer} loads. An
 * exported {@code RAPTOR_SCOPE_HORIZON} would override the file here, as it does
 * in production.
 */
@DisplayName("The scope horizon defaults to two hours, as #52 decided")
class ScopeHorizonDefaultTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ScopeProperties.class)
	static class Bind {
	}

	@Test
	@DisplayName("the value application.yml ships is 2h")
	void shippedDefault() {
		new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withUserConfiguration(Bind.class)
				.run(context -> assertThat(context.getBean(ScopeProperties.class).horizon())
						.isEqualTo(Duration.ofHours(2)));
	}

	@Test
	@DisplayName("the record's own default, with no configuration, is 2h")
	void recordDefault() {
		new ApplicationContextRunner()
				.withUserConfiguration(Bind.class)
				.run(context -> assertThat(context.getBean(ScopeProperties.class).horizon())
						.isEqualTo(Duration.ofHours(2)));
	}
}
