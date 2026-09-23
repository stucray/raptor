package com.stucray.raptor.recorder;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.jspecify.annotations.Nullable;

/**
 * A transaction manager that does nothing, for the unit tests of the write loop.
 *
 * <p>The loop's contract with transactions is only that a failing write rolls
 * back and reaches the spill; whether a real COPY joins the caller's transaction
 * is {@code CopyBuffer}'s business and is asserted against a real PostgreSQL in
 * {@code ReplayWritePathIntegrationTest}.
 */
final class NoOpTransactions implements PlatformTransactionManager {

	static TransactionTemplate template() {
		return new TransactionTemplate(new NoOpTransactions());
	}

	@Override
	public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
		return new SimpleTransactionStatus();
	}

	@Override
	public void commit(TransactionStatus status) {}

	@Override
	public void rollback(TransactionStatus status) {}
}
