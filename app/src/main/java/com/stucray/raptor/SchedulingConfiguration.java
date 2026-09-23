package com.stucray.raptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for the whole application.
 *
 * <p><b>Moved here from the importer package, which S9 deleted (#94).</b> It
 * lived beside {@code ScheduledImportRunner} because that was the first thing
 * to want a schedule, and it stayed there while five other components grew to
 * depend on it — none of them in the read side, and none of them able to say
 * so. Deleting the spool machinery would have taken this with it and switched
 * every one of them off:
 *
 * <ul>
 * <li>{@code MarketScopeService} — what puts fixtures into scope, so the
 *     recorder would have subscribed to nothing, all night, reporting UP;</li>
 * <li>{@code StreamWatchdog} — the suspend detector that tears down a stream
 *     the machine slept through;</li>
 * <li>{@code NightlyCloseOut} — raw into query overnight, including the
 *     football-data sweep that had a timer of its own until #200 (and since
 *     #334 no timer here at all: launchd fires it over HTTP);</li>
 * <li>{@code BetfairSession} — the session keep-alive;</li>
 * <li>{@code CaptureLedgerRefresh} — the ledger projection.</li>
 * </ul>
 *
 * <p>Not one of those failures raises anything. A {@code @Scheduled} method
 * that is never invoked logs nothing, fails nothing, and leaves only an absence
 * — which is the same shape as the two bugs S9 already filed (#141, #144). So
 * it lives in the root package now, owned by the application rather than by
 * whichever feature happened to need it first.
 *
 * <p><b>Every timer here stretches over host sleep (#307).</b> The app runs in
 * Docker Desktop's VM, which is frozen while the Mac sleeps, and its monotonic
 * clock stops with it. Spring waits out every delay on that clock, so each
 * {@code @Scheduled} task (cron, fixed delay or fixed rate) fires late by any
 * sleep since it was last scheduled. The nightly close-out shows it most, because
 * its delay is the longest. It costs nothing while capturing, which already needs
 * the machine awake, but it means a timestamp that is late relative to its
 * schedule is not by itself evidence that something is wrong.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {}
