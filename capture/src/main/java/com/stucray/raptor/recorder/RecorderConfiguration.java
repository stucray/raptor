package com.stucray.raptor.recorder;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the write path. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecorderProperties.class)
class RecorderConfiguration {

	/**
	 * The spill, on by default and a flag either way.
	 *
	 * <p>Declared before the fallback below so that
	 * {@code @ConditionalOnMissingBean} sees it: with the spill on there is exactly
	 * one sink, and which one a refused batch reaches never depends on ordering.
	 */
	@Bean
	@ConditionalOnBooleanProperty(name = "raptor.recorder.spill.enabled", matchIfMissing = true)
	SpillSink spillWriter(RecorderProperties properties, Clock clock, MeterRegistry meters) {
		return new SpillWriter(properties, clock, meters);
	}

	/**
	 * With the spill switched off, a refused batch is lost — so it is counted and
	 * said loudly rather than quietly dropped.
	 *
	 * <p>{@code @ConditionalOnMissingBean} rather than the inverse property, so
	 * that a future sink of any kind displaces this one without a second condition
	 * to keep in step with the first.
	 */
	@Bean
	@ConditionalOnMissingBean(SpillSink.class)
	SpillSink loggingSpillSink(MeterRegistry meters) {
		return new LoggingSpillSink(meters);
	}

	/** Injected rather than called statically, so a test can move time. */
	@Bean
	@ConditionalOnMissingBean(Clock.class)
	Clock recorderClock() {
		return Clock.systemUTC();
	}
}
