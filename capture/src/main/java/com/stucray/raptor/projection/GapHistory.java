package com.stucray.raptor.projection;

import java.time.Instant;
import java.util.Optional;

/**
 * Gaps the recorder suffered <b>while a market was in play</b>.
 *
 * <p>The projection module's third published type, and it exists for the same
 * reason as the other two: the answer is not visible anywhere else at the time
 * it matters. A suspend is the largest single cause of holes in this corpus
 * (#107, #185) and it is silent by construction — the host is asleep, launchd
 * does not run, and nothing inside a stopped process can report that the process
 * is not there. So the finding can only ever be delivered <em>on wake</em>, and
 * something has to publish it for the host to read.
 *
 * <p><b>The overlap, not the span.</b> A 322-second gap with scope empty is a
 * non-event — precisely the 2026-09-11 case — while thirty seconds across three
 * in-play markets is what the whole programme exists to avoid. Only markets that
 * were actually in play during the interval qualify, which is the same rule
 * {@code CollectorHealthController.gapsDuringPlay()} applies to the screen.
 *
 * <p><b>A fact, never a verdict.</b> Nothing here can turn a health indicator
 * red: the gap is over, the recorder is back, and the response to it is a
 * sentence rather than the automatic restart an unhealthy recorder gets.
 */
// One method today and not a lambda target: this is a published type in the
// projection module's deliberately small surface, named for the question it
// answers, and a second method belongs on it the moment anything needs to ask
// about more than the last gap.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface GapHistory {

	/**
	 * The most recent gap that overlapped a market in play, if there has been
	 * one lately.
	 *
	 * <p>Bounded to the recent past rather than all history, for two reasons.
	 * The gap that matters here ends when the machine wakes, so it is recent by
	 * construction; and an unbounded query would make the first read after any
	 * fresh de-dupe state announce whatever the oldest ledger happens to hold.
	 *
	 * <p><b>And only a gap long enough to be more than a reconnect (#298.)</b>
	 * Every gap that had ever overlapped play when this shipped was a
	 * {@code DISCONNECT} of 30 seconds or less, sixteen of them on one card — so
	 * without a floor the first good night would have sent sixteen notifications
	 * about the reconnect machinery working, and taught its reader to ignore the
	 * channel before the night it matters.
	 *
	 * <p>Empty is the ordinary answer, and it is the one that keeps this quiet
	 * on every end-of-evening lid close with nothing in scope.
	 */
	Optional<GapInPlay> lastGapDuringPlay();

	/**
	 * One interval the recorder knows it was not receiving, measured against
	 * what was on at the time.
	 *
	 * @param endedAt when the recorder came back — the wake moment for a
	 *     suspend. <b>The report's identity</b>: the host keys its once-per-gap
	 *     de-dupe on this, so it must be the gap's own timestamp and never the
	 *     time it was read.
	 * @param cause {@code SLEEP}, {@code SILENCE} or {@code DISCONNECT}, as
	 *     decided at insert from the wall-vs-monotonic measurement (#264, #290).
	 *     Rows written before 2026-09-16 pre-date that and can carry a
	 *     {@code DISCONNECT} that was really a lid closure; they cannot be
	 *     corrected, because the cause is decided when the row is written and
	 *     re-projecting reads the same raw value.
	 * @param seconds how long it lasted
	 * @param markets how many markets were in scope for any part of it
	 * @param live how many of those were in play — the number that makes this
	 *     worth a notification at all
	 */
	record GapInPlay(Instant endedAt, String cause, long seconds, int markets, int live) {}
}
