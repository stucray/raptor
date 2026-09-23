/**
 * The football-data.co.uk results archive: the public internet into {@code raw}.
 *
 * <p>Acquisition for the third source, and the first whose raw is <em>fetched</em>
 * rather than captured or downloaded once. Two properties of the upstream shape
 * everything here, and both were verified against the live server (2026-09-04,
 * and by the data repo's {@code fetch.py} before that):
 *
 * <ul>
 * <li><b>It supports conditional requests.</b> A full sweep of 748
 * division×season pairs transfers a few hundred KB rather than ~90 MB, because
 * every file already held costs one {@code If-None-Match} and a {@code 304}.
 * The validators live in {@code raw.football_file}, so the state that makes the
 * sweep cheap is the same row that records the fetch.</li>
 * <li><b>A missing season is {@code 300}, not {@code 404}.</b> The server runs
 * Apache MultiViews and answers a "did you mean" list. Treating that as an error
 * would make every run of a part-published season look broken, so it is a
 * verdict of its own — {@code NOT_PUBLISHED} — and not a failure.</li>
 * </ul>
 *
 * <p>Like {@code ingest} and {@code recorder}, this package may not reach
 * {@code paddock-wire}: what it writes is the CSV exactly as served, and a parse
 * bug must be structurally incapable of reaching the system of record.
 * {@code WritePathIsolationTest} holds it.
 */
package com.stucray.raptor.archive;
