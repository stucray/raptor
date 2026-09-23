package com.stucray.raptor.betfair;

import com.stucray.raptor.recorder.StreamSourceFactory;
import com.stucray.raptor.scope.CaptureScope;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Wiring for the live Betfair client. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({BetfairProperties.class, StreamProperties.class})
class BetfairConfiguration {

	/**
	 * A builder of last resort.
	 *
	 * <p>This module is a library: the application it is packaged into normally
	 * auto-configures a {@code RestClient.Builder}, but a slim context — the
	 * acquisition module's own tests, or a future headless job runner — has no
	 * web starter and would fail to create the client beans at startup, which is
	 * a confusing way to discover a missing auto-configuration.
	 */
	@Bean
	@ConditionalOnMissingBean(RestClient.Builder.class)
	RestClient.Builder betfairRestClientBuilder() {
		return RestClient.builder();
	}

	/**
	 * The live stream, when this process is the one that should be capturing.
	 *
	 * <p>Two conditions, and both are load-bearing. The switch is what makes
	 * running the recorder a deliberate act rather than a consequence of
	 * deployment — shadow capture starts when somebody turns it on, not when a
	 * container restarts. The credentials condition is what keeps a developer's
	 * {@code spring-boot:run} quiet: without it the supervisor would open a
	 * connection every five seconds forever, fail to log in every time, and say so
	 * in the log every time.
	 *
	 * <p>With no factory bean the supervisor stands down and reports it, which is
	 * the honest state of an instance that cannot capture.
	 */
	@Bean
	@ConditionalOnBooleanProperty("raptor.betfair.stream.enabled")
	@Conditional(OnBetfairCredentials.class)
	StreamSourceFactory betfairStreamSourceFactory(BetfairSession session,
			BetfairProperties properties, StreamProperties stream, CaptureScope scope,
			Clock clock) {
		return new TlsStreamSourceFactory(session, properties, stream, scope, clock);
	}
}
