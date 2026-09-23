package com.stucray.raptor.betfair;

import java.util.List;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when this process actually has Betfair credentials.
 *
 * <p>A condition rather than a runtime check, because the difference has to be
 * visible before anything starts: a bean that exists and cannot log in makes the
 * supervisor take the single-writer lease, hold it, and fail every five seconds
 * — so an instance that genuinely could capture would stand by behind one that
 * never can.
 *
 * <p>It reads the bound property names rather than the environment variables
 * behind them, so a credential supplied any other way — a test property, a
 * command-line argument — counts the same. The application's own configuration
 * defaults every one of them to empty for exactly this reason.
 */
class OnBetfairCredentials implements Condition {

	private static final List<String> REQUIRED = List.of(
			"raptor.betfair.app-key",
			"raptor.betfair.username",
			"raptor.betfair.password",
			"raptor.betfair.cert-pem-base64",
			"raptor.betfair.key-pem-base64");

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		for (String property : REQUIRED) {
			String value = context.getEnvironment().getProperty(property);
			if (value == null || value.isBlank()) {
				return false;
			}
		}
		return true;
	}
}
