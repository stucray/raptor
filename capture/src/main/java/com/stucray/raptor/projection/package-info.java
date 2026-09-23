/**
 * The capture ledger in {@code query}, and the nightly close-out's record of what
 * it did.
 *
 * <p><b>This module used to be the projection</b> — {@code raw} into
 * {@code query}, launched over {@code /ops/*} and every night by the close-out.
 * #316 moved all of that to overround-analysis, which owns and writes
 * {@code query}'s projection tables now. What stays is capture's own
 * bookkeeping: the capture ledger ({@code capture_session}, {@code market_scope},
 * {@code capture_gap}), which #320 moves into raptor's schema, and the nightly
 * close-out, which only fetches the football archive into {@code raw}.
 *
 * <p>The package keeps its name until #320 rather than being renamed twice.
 */
package com.stucray.raptor.projection;
