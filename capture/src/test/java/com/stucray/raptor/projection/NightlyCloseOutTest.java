package com.stucray.raptor.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stucray.raptor.archive.ArchiveRefresh;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * What the close-out promises, with the database and the sweep mocked away.
 *
 * <p>Since #316 the close-out is the football-data sweep and nothing else; the
 * projection it used to run first is overround-analysis's. Every case here is
 * still about a failure that is silent in production: a run that does not record
 * itself, or an unsuccessful run that logs like a successful one.
 */
@DisplayName("The nightly close-out fetches the archive and always records what it did")
class NightlyCloseOutTest {

	private final ArchiveRefresh archive = mock(ArchiveRefresh.class);
	private final CloseOutLedger ledger = mock(CloseOutLedger.class);
	private final CloseOutLock lock = mock(CloseOutLock.class);
	private final CloseOutLock.Held held = mock(CloseOutLock.Held.class);

	private final NightlyCloseOut closeOut = new NightlyCloseOut(archive, ledger, lock);

	@BeforeEach
	void lockIsFree() {
		when(lock.tryHold()).thenReturn(held);
	}

	@Test
	@DisplayName("records the run before the sweep starts, and its verdict after")
	void recordsAtBothEnds() {
		when(ledger.started()).thenReturn(7L);
		when(archive.refreshCurrentSeason())
				.thenReturn(new ArchiveRefresh.Outcome(true, 3, "archive: changed=3"));

		NightlyCloseOut.Result result = closeOut.closeOut();

		InOrder order = inOrder(lock, ledger, archive, held);
		order.verify(lock).tryHold();
		order.verify(ledger).started();
		order.verify(archive).refreshCurrentSeason();
		order.verify(ledger).finished(7L, true, 3, null);
		order.verify(held).close();
		assertThat(result).isEqualTo(
				new NightlyCloseOut.Result(NightlyCloseOut.Status.COMPLETED, 3, null));
	}

	@Test
	@DisplayName("a run asked for while one holds the lock does nothing and records nothing (#334)")
	void aSecondRunIsRefused() {
		when(lock.tryHold()).thenReturn(null);

		NightlyCloseOut.Result result = closeOut.closeOut();

		assertThat(result.status()).isEqualTo(NightlyCloseOut.Status.ALREADY_RUNNING);
		verifyNoInteractions(archive, ledger);
	}

	@Test
	@DisplayName("a football-data outage is recorded as a failed run with the reason, not thrown")
	void aFailedSweepIsRecordedNotThrown() {
		when(ledger.started()).thenReturn(8L);
		when(archive.refreshCurrentSeason())
				.thenReturn(new ArchiveRefresh.Outcome(false, 0, "503"));

		NightlyCloseOut.Result result = closeOut.closeOut();

		verify(ledger).finished(eq(8L), eq(false), eq(0), startsWith("archive sweep failed (503"));
		assertThat(result.status()).isEqualTo(NightlyCloseOut.Status.FAILED);
		verify(held).close();
	}

	@Test
	@DisplayName("a chain that throws is still recorded, then rethrown")
	void aBrokenChainIsStillRecorded() {
		when(ledger.started()).thenReturn(9L);
		when(archive.refreshCurrentSeason()).thenThrow(new IllegalStateException("boom"));

		assertThatThrownBy(closeOut::closeOut).isInstanceOf(IllegalStateException.class);

		verify(ledger).finished(eq(9L), eq(false), eq(0), startsWith("java.lang.IllegalStateException: boom"));
		// A throw must not leave the lock held: the next night would be refused.
		verify(held).close();
	}

	@Test
	@DisplayName("a clean night records no detail")
	void aCleanNightHasNoDetail() {
		when(archive.refreshCurrentSeason())
				.thenReturn(new ArchiveRefresh.Outcome(true, 0, "archive: unchanged=22"));

		closeOut.closeOut();

		verify(ledger).finished(anyLong(), anyBoolean(), anyInt(), isNull());
		verify(ledger).started();
		verify(archive).refreshCurrentSeason();
		verify(ledger, org.mockito.Mockito.never()).finished(anyLong(), eq(false), anyInt(), any());
	}
}
